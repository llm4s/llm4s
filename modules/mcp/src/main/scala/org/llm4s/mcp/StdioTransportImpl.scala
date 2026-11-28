// scalafix:off DisableSyntax.NoKeywordTry, DisableSyntax.NoKeywordCatch, DisableSyntax.NoKeywordFinally
package org.llm4s.mcp

import org.llm4s.error.{ CancelledError, SimpleError }
import org.llm4s.types.Result
import org.llm4s.util.DurationRounding
import org.slf4j.{ Logger, LoggerFactory }
import upickle.default._

import java.io.{ BufferedReader, IOException, InputStreamReader, OutputStreamWriter, PrintWriter }
import java.nio.charset.StandardCharsets
import java.util.concurrent.{ CompletableFuture, ConcurrentHashMap, ExecutionException, TimeUnit, TimeoutException }
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.{ Failure, Success, Try }
import PayloadLog.debugPayload

private[mcp] object StdioTransportImpl {

  /** The transport a [[StdioTransport]] config describes, its environment and working directory included. */
  def of(config: StdioTransport, timeout: FiniteDuration): StdioTransportImpl =
    new StdioTransportImpl(
      config.command,
      config.name,
      timeout,
      env = config.env,
      workingDirectory = config.workingDirectory,
      inheritEnvironment = config.inheritEnvironment
    )

  /**
   * How long a server is given to exit once its stdin is closed, and again once it has been asked to terminate,
   * before it is killed: the shutdown sequence the MCP specification gives for stdio.
   */
  val ShutdownGrace: FiniteDuration = 2.seconds

  /**
   * Turns a server's stderr, arriving in chunks cut anywhere, into lines that are safe to log and to put in an error.
   * A line is redacted as a whole before it is shortened: a secret and the key that names it are never separated by a
   * chunk boundary or by the cut. A line longer than `lineCap` is redacted from its first `lineCap` characters, emitted
   * then with a marker, and the rest of it is dropped, counted but never kept or shown, so a line that never ends
   * costs `lineCap` characters of memory and one log entry. `emit` receives each redacted, bounded line.
   */
  final class StderrLines(lineCap: Int, emit: String => Unit) {
    private val line    = new StringBuilder
    private var dropped = 0L
    private var capped  = false

    def feed(text: String): Unit = {
      var start = 0
      while (start < text.length) {
        val nl  = text.indexOf('\n', start)
        val end = if (nl < 0) text.length else nl
        append(text.substring(start, end))
        if (nl >= 0) endLine()
        start = end + 1
      }
    }

    /** Emits the line still open, at the end of the stream. */
    def finish(): Unit = if (line.nonEmpty || capped) endLine()

    private def append(part: String): Unit =
      if (capped) dropped += part.length
      else {
        val room = lineCap - line.length
        line.append(part.substring(0, math.min(room, part.length)))
        if (part.length >= room) {
          dropped += part.length - room
          capped = true
        }
      }

    private def endLine(): Unit = {
      val text = line.toString.stripSuffix("\r")
      val marker =
        if (capped) s" [line cut at $lineCap chars, ${if (dropped > 0) s"$dropped more " else ""}omitted]" else ""
      emit(PayloadLog.preview(text) + marker)
      line.clear()
      dropped = 0
      capped = false
    }
  }

  /** The last `max` characters of `text`, cut at a line start when there is one, so a line is never kept in part. */
  def lastLines(text: String, max: Int): String =
    if (text.length <= max) text
    else {
      val tail = text.takeRight(max)
      val nl   = tail.indexOf('\n')
      if (nl >= 0 && nl < tail.length - 1) tail.drop(nl + 1) else tail
    }

  /**
   * Stops `process` and every process it started (#3077), as the MCP specification's stdio shutdown says: close its
   * stdin, give it `grace` to exit, then terminate it, and kill it if it is still running `grace` later.
   *
   * A server is often a launcher (`npx` starting `node`, which starts a browser), and `Process.destroy` reaches the
   * launcher alone: the rest lived on, orphaned. The descendants are therefore listed first, while the tree is still
   * whole (once a parent exits, its children are no longer its descendants), and each one still running once the
   * server is gone is terminated, then killed, the same way.
   *
   * An interrupt of the calling thread skips the waits and kills at once, and stays set.
   */
  def stopTree(process: Process, closeInput: () => Unit, grace: FiniteDuration, logger: Logger, name: String): Unit = {
    val descendants = Try(process.descendants().iterator().asScala.toList).getOrElse(Nil)
    closeInput()
    val exited = waitFor(process.toHandle, grace)
    if (!exited) Try(process.destroy())
    val survivors = descendants.filter(_.isAlive)
    if (survivors.nonEmpty)
      logger.debug(s"StdioTransport($name) stopping ${survivors.size} process(es) the server started")
    survivors.foreach(handle => Try(handle.destroy()))
    val deadline = Deadline.now + grace
    (if (exited) survivors else process.toHandle +: survivors).foreach { handle =>
      if (!waitFor(handle, deadline.timeLeft)) {
        logger.debug(s"StdioTransport($name) killing process ${handle.pid()}, still running after termination")
        Try(handle.destroyForcibly())
      }
    }
  }

  /** Whether `handle` has exited within `within`; an interrupt ends the wait at once and stays set. */
  private def waitFor(handle: ProcessHandle, within: FiniteDuration): Boolean =
    if (!handle.isAlive) true
    else if (Thread.currentThread().isInterrupted || within <= Duration.Zero) false
    else
      try {
        handle.onExit().get(DurationRounding.ceilMillis(within), TimeUnit.MILLISECONDS)
        true
      } catch {
        case _: InterruptedException =>
          Thread.currentThread().interrupt()
          !handle.isAlive
        case _: TimeoutException | _: ExecutionException => !handle.isAlive
      }
}

/**
 * The stdio transport: the MCP server runs as a child process, and JSON-RPC messages are exchanged one per line on its
 * stdin and stdout. Its stderr is the server's log, drained and kept (redacted, bounded) for diagnostics.
 *
 * The process starts on the first message and the first request is written at once: a server is ready when it has
 * answered `initialize`, which is what the client sends first, and a server that correctly says nothing until then
 * costs no wait (#3077). One reader thread per process reads stdout to its end and hands each response to the call
 * waiting for its id; a request the server sends the client (`ping`, or a capability the client does not declare) is
 * answered at once and is never taken for a reply (#3077, #3079).
 *
 * A process that has exited is started again on the next request. A restarted server has not been through the
 * handshake, so until `initialize` is sent again every other request is refused unsent with [[MCPSessionLost]]
 * (#3072). A call that times out or is interrupted while waiting for its reply to `initialize` stops the process, so
 * the next attempt starts afresh.
 *
 * Thread-safe: requests may be sent from several threads at once; starting a process is serialised.
 *
 * @param command            the command line that starts the server
 * @param name               the server's name, for logs and errors
 * @param timeout            the longest wait for the reply to one request: `MCPServerConfig.timeout`
 * @param env                variables set for the server over the allowlisted ones it inherits
 * @param workingDirectory   the directory to start it in; `None` is the JVM's
 * @param inheritEnvironment `true` passes the JVM's whole environment, plus `env`
 */
class StdioTransportImpl(
  command: Seq[String],
  override val name: String,
  timeout: FiniteDuration = 30.seconds,
  env: Map[String, String] = Map.empty,
  workingDirectory: Option[java.nio.file.Path] = None,
  inheritEnvironment: Boolean = false
) extends MCPTransportImpl {

  /** The 2-parameter constructor: the default 30-second reply timeout. */
  def this(command: Seq[String], name: String) = this(command, name, 30.seconds)

  private val logger = LoggerFactory.getLogger(getClass)

  // The server's stderr: how much of it is kept for a diagnostic, how much of one line is read before it is redacted
  // and cut, and how many lines per process are logged at info before the rest go to debug (#1952).
  private val StderrBufferChars = 8192
  private val StderrLineChars   = 16384
  private val StderrInfoLines   = 100

  private val startLock                      = new Object
  @volatile private var child: Option[Child] = None
  @volatile private var hasRun               = false
  @volatile private var handshakeNeeded      = false

  logger.info(s"StdioTransport($name) initialized with command: ${command.mkString(" ")}")

  /**
   * One run of the server process, with its pipes, its reader and stderr threads and the requests waiting on it. A
   * restart is a new `Child`, so a reply, a failure or a shutdown of one process never reaches another's calls.
   */
  final private class Child(val process: Process) {
    private val stdin     = new PrintWriter(new OutputStreamWriter(process.getOutputStream, StandardCharsets.UTF_8))
    private val stdout    = new BufferedReader(new InputStreamReader(process.getInputStream, StandardCharsets.UTF_8))
    private val stderr    = new BufferedReader(new InputStreamReader(process.getErrorStream, StandardCharsets.UTF_8))
    private val writeLock = new Object

    val pending = new ConcurrentHashMap[String, CompletableFuture[String]]()

    // Set by the reader once stdout has ended: the server will answer nothing more, and `endReason` says why.
    @volatile var ended              = false
    @volatile var endReason: String  = ""
    @volatile private var stopping   = false
    private val stderrLock           = new Object
    private val stderrBuffer         = new StringBuilder
    private val stderrThread: Thread = daemon("stderr")(drainStderr())
    private val readerThread: Thread = daemon("reader")(readStdout())

    def alive: Boolean = !ended && process.isAlive

    /** Writes one message, a line of JSON; `Left` with the reason when the pipe is broken. */
    def write(json: String): Either[String, Unit] = writeLock.synchronized {
      stdin.print(json)
      stdin.print('\n')
      stdin.flush()
      if (stdin.checkError()) Left(s"Failed to write to process stdin (broken pipe); ${deathNote()}") else Right(())
    }

    /** Why the server cannot answer: its exit code once it has exited, and the last of its stderr. */
    def deathNote(): String = {
      val code =
        if (Try(process.waitFor(1, TimeUnit.SECONDS)).getOrElse(false)) s"exited with code ${process.exitValue()}"
        else "closed its stdout"
      val err = stderrText()
      s"the MCP server process $code" + (if (err.nonEmpty) s". Server stderr: $err" else "")
    }

    /** What has been collected of the server's stderr, bounded, for an error; waits briefly for its last words. */
    def stderrText(): String = {
      if (!process.isAlive) joinQuietly(stderrThread, 1000)
      val text = stderrLock.synchronized(stderrBuffer.toString)
      PayloadLog.preview(StdioTransportImpl.lastLines(text, PayloadLog.MaxChars)).trim
    }

    /** Stops the process tree, fails the calls still waiting, and ends both threads. */
    def stop(): Unit = {
      stopping = true
      StdioTransportImpl.stopTree(
        process,
        () => writeLock.synchronized(Try(stdin.close())): Unit,
        StdioTransportImpl.ShutdownGrace,
        logger,
        name
      )
      // The process is gone, so its pipes have ended and both threads end with them. Closing the raw streams is the
      // backstop for a grandchild that kept one open; a reader is closed only after its thread, since closing a reader
      // that another thread is blocked in waits for that thread.
      Seq(readerThread -> process.getInputStream, stderrThread -> process.getErrorStream).foreach {
        case (thread, stream) =>
          joinQuietly(thread, 1000)
          if (thread.isAlive) {
            Try(stream.close())
            joinQuietly(thread, 1000)
          }
      }
      Try(stdout.close())
      Try(stderr.close())
      failPending("Transport closing")
    }

    private def failPending(reason: String): Unit =
      pending.keySet.asScala.toList.foreach { id =>
        Option(pending.remove(id)).foreach(_.completeExceptionally(new IOException(reason)))
      }

    // Reads stdout to its end: a reply written just before the server exits is still delivered (#3077).
    private def readStdout(): Unit = {
      var failure: Option[String] = None
      try {
        var line = stdout.readLine()
        while (line != null) {
          if (line.trim.nonEmpty) route(line)
          line = stdout.readLine()
        }
      } catch {
        case e: IOException =>
          if (!stopping) failure = Some(s"reading the server's stdout failed: ${e.getMessage}")
      } finally {
        val reason =
          if (stopping) "Transport closing"
          else failure.fold(deathNote())(f => s"$f; ${deathNote()}")
        endReason = reason
        ended = true
        if (!stopping) logger.warn(s"StdioTransport($name) server stopped answering: $reason")
        failPending(reason)
        logger.debug(s"StdioTransport($name) reader thread exiting")
      }
    }

    // A response goes to the call waiting for its id; a request the server sends is answered, never taken for a reply
    // (#3077); a notification is logged.
    private def route(line: String): Unit = {
      logger.debugPayload(s"StdioTransport($name) received: ", line)
      Try(ujson.read(line)) match {
        case Failure(e) =>
          logger.warn(
            s"StdioTransport($name) ignoring a line that is not JSON (${e.getClass.getSimpleName}): " +
              PayloadLog.preview(line)
          )
        case Success(json) =>
          JsonRpcReplies.serverRequest(json) match {
            case Some((id, method)) =>
              logger.debug(s"StdioTransport($name) answering the server's request $method")
              val answer = JsonRpcReplies.answerToServerRequest(id, method).render()
              // Written off the reader thread: a server blocked writing stdout would never read its stdin.
              val _ = Thread.ofVirtual().name(s"StdioTransport-$name-answer").start { () =>
                write(answer).left.foreach(r => logger.debug(s"StdioTransport($name) could not answer the server: $r"))
              }
            case None =>
              // A response to this client: no `method`, and the id as this client sent it, a string (#3071).
              json.objOpt.filterNot(_.contains("method")).flatMap(_.get("id")).flatMap(_.strOpt) match {
                case Some(id) =>
                  Option(pending.remove(id)) match {
                    case Some(future) => future.complete(line): Unit
                    case None         => logger.warn(s"StdioTransport($name) received a reply to unknown request $id")
                  }
                case None =>
                  logger.debugPayload(s"StdioTransport($name) received a notification or an unmatched message: ", line)
              }
          }
      }
    }

    // Reads the server's stderr until it ends, for as long as the process lives: nothing else reads it while a request
    // is in flight, and a server that logs more than the pipe holds would block in its write and never answer (#1952).
    private def drainStderr(): Unit = {
      val chunk  = new Array[Char](4096)
      var logged = 0
      // Each line arrives redacted whole and bounded; the buffer for diagnostics keeps these, never raw chunks
      val lines = new StdioTransportImpl.StderrLines(
        StderrLineChars,
        line => {
          if (logged < StderrInfoLines) {
            logger.info(s"StdioTransport($name) stderr: $line")
            logged += 1
            if (logged == StderrInfoLines)
              logger.info(s"StdioTransport($name) stderr: further lines are logged at debug level")
          } else logger.debug(s"StdioTransport($name) stderr: $line")
          stderrLock.synchronized {
            stderrBuffer.append(line).append('\n')
            val kept = StdioTransportImpl.lastLines(stderrBuffer.toString, StderrBufferChars)
            if (kept.length != stderrBuffer.length) {
              stderrBuffer.clear()
              stderrBuffer.append(kept)
            }
          }
        }
      )
      Iterator
        .continually(Try(stderr.read(chunk)).getOrElse(-1))
        .takeWhile(_ >= 0)
        .foreach(n => lines.feed(new String(chunk, 0, n)))
      lines.finish()
      logger.debug(s"StdioTransport($name) stderr drain thread exiting")
    }

    private def daemon(role: String)(body: => Unit): Thread = {
      val thread = new Thread(() => body, s"StdioTransport-$name-$role")
      thread.setDaemon(true)
      thread.start()
      thread
    }
  }

  // Waits up to `millis` for `thread` to end, keeping an interrupt of this thread set
  private def joinQuietly(thread: Thread, millis: Long): Unit =
    Try(thread.join(millis)).failed.foreach {
      case _: InterruptedException => Thread.currentThread().interrupt()
      case _                       => ()
    }

  /**
   * The running process for `message`, started if there is none or the last one has exited; serialised, so two calls
   * that find the server gone start one process between them (#3077). `request` is checked against the handshake
   * gate: a restarted server gets `initialize` first, and any other request is refused unsent with
   * [[MCPSessionLost]], so the client can run the handshake and retry it without the server having run it twice.
   */
  private def childFor(request: Option[JsonRpcRequest]): Result[Child] = startLock.synchronized {
    val running = child match {
      case Some(c) if c.alive => Right(c)
      case previous =>
        previous.foreach(_.stop())
        child = None
        val restart = hasRun
        start().map { started =>
          child = Some(started)
          hasRun = true
          if (restart) {
            logger.warn(
              s"StdioTransport($name) server process had exited; started a new one, which needs the handshake"
            )
            handshakeNeeded = true
          }
          started
        }
    }
    running.flatMap { c =>
      request match {
        case Some(r) if r.method == "initialize" => handshakeNeeded = false; Right(c)
        case Some(_) if handshakeNeeded          => Left(MCPSessionLost.Restarted)
        case _                                   => Right(c)
      }
    }
  }

  private def start(): Result[Child] = {
    logger.info(s"StdioTransport($name) starting new process: ${command.mkString(" ")}")
    Try {
      val builder = new ProcessBuilder(command: _*)
      builder.redirectErrorStream(false)
      StdioEnvironment.prepare(builder.environment(), env, inheritEnvironment)
      workingDirectory.foreach(dir => builder.directory(dir.toFile))
      new Child(builder.start())
    } match {
      case Success(started) => Right(started)
      case Failure(e) =>
        logger.error(s"StdioTransport($name) failed to start process: ${e.getMessage}")
        Left(SimpleError(s"Failed to start MCP server process: ${e.getMessage}"))
    }
  }

  // Stops `c` if it is still the current process: after an `initialize` that was never answered, so the next attempt
  // gets a fresh server rather than one that may be half way through the handshake.
  private def abandon(c: Child): Unit = {
    val mine = startLock.synchronized {
      if (child.contains(c)) { child = None; true }
      else false
    }
    if (mine) {
      logger.warn(s"StdioTransport($name) stopping a server that did not answer initialize")
      c.stop()
    }
  }

  /**
   * Sends `request` and waits up to `timeout` for its reply. Thread-safe: each call waits on its own future, matched
   * by id. An interrupt returns `Left(CancelledError)` with the flag set and leaves nothing pending.
   */
  override def sendRequest(request: JsonRpcRequest): Result[JsonRpcResponse] =
    CancelledError.attempt("mcp.stdio.request") {
      logger.debug(s"StdioTransport($name) sending request: method=${request.method}, id=${request.id}")
      childFor(Some(request)).flatMap(exchange(_, request))
    }

  private def exchange(c: Child, request: JsonRpcRequest): Result[JsonRpcResponse] = {
    val reply = new CompletableFuture[String]()
    c.pending.put(request.id, reply)
    // The reader may have ended between childFor and the put, and failed every call pending then: this one too.
    if (c.ended) reply.completeExceptionally(new IOException(c.endReason))
    val json = write(request)
    logger.debugPayload(s"StdioTransport($name) writing to stdin: ", json)
    c.write(json) match {
      case Left(reason) =>
        c.pending.remove(request.id)
        logger.error(s"StdioTransport($name) $reason")
        Left(SimpleError(s"Stdio transport error: $reason"))
      case Right(()) => awaitReply(c, request, reply)
    }
  }

  private def awaitReply(
    c: Child,
    request: JsonRpcRequest,
    reply: CompletableFuture[String]
  ): Result[JsonRpcResponse] = {
    def unanswered(): Unit = {
      c.pending.remove(request.id)
      if (request.method == "initialize") abandon(c)
    }
    try {
      val line = reply.get(DurationRounding.ceilMillis(timeout), TimeUnit.MILLISECONDS)
      Try(read[JsonRpcResponse](line)) match {
        case Failure(e) => Left(SimpleError(s"Failed to parse response: ${e.getMessage}"))
        case Success(response) =>
          response.error match {
            case Some(error) =>
              val message = JsonRpcReplies.errorMessage(error)
              logger.error(s"StdioTransport($name) $message")
              Left(SimpleError(message))
            case None =>
              logger.debug(s"StdioTransport($name) request successful: id=${response.id}")
              Right(response)
          }
      }
    } catch {
      case e: InterruptedException =>
        // A cancellation (design section 4.4): the flag the throw cleared is set again, and nothing is left pending.
        unanswered()
        Thread.currentThread().interrupt()
        Left(CancelledError("mcp.stdio.request", Some(e)))
      case _: TimeoutException =>
        unanswered()
        val err = c.stderrText()
        val message = s"Timeout waiting for response to request ${request.id} after $timeout" +
          (if (err.nonEmpty) s". Server stderr: $err" else "")
        logger.error(s"StdioTransport($name) $message")
        Left(SimpleError(s"Stdio transport error: $message"))
      case e: ExecutionException =>
        c.pending.remove(request.id)
        val reason = Option(e.getCause).map(_.getMessage).getOrElse(e.getMessage)
        logger.error(s"StdioTransport($name) request ${request.id} failed: $reason")
        Left(SimpleError(s"Stdio transport error: $reason"))
    }
  }

  /** Writes `notification`; no reply is awaited. An interrupt returns `Left(CancelledError)` with the flag set. */
  override def sendNotification(notification: JsonRpcNotification): Result[Unit] =
    CancelledError.attempt("mcp.stdio.notification") {
      logger.debug(s"StdioTransport($name) sending notification: method=${notification.method}")
      childFor(None).flatMap { c =>
        val json = write(notification)
        logger.debugPayload(s"StdioTransport($name) writing notification to stdin: ", json)
        c.write(json).left.map { reason =>
          logger.error(s"StdioTransport($name) notification error: $reason")
          SimpleError(s"Stdio notification error: $reason")
        }
      }
    }

  /** Stops the server and every process it started, and fails any call still waiting. */
  override def close(): Unit = {
    logger.info(s"StdioTransport($name) closing process and streams")
    val stopped = startLock.synchronized {
      val c = child
      child = None
      hasRun = false
      handshakeNeeded = false
      c
    }
    stopped.foreach(_.stop())
  }
}
