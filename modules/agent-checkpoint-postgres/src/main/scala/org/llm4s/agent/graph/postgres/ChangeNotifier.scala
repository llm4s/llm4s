package org.llm4s.agent.graph.postgres

import org.llm4s.agent.graph.{ Checkpointer, ThreadId }
import org.llm4s.error.CancelledError
import org.llm4s.types.Result
import org.postgresql.PGConnection
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{ ConcurrentHashMap, Semaphore, TimeUnit }
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.{ Try, Using }

/**
 * A [[PostgresCheckpointer]]'s change notification (design 4.51): one dedicated connection that `LISTEN`s on the
 * store's channel and wakes the store's waiting [[PostgresCheckpointer.awaitEventsAfter]] calls when a commit through
 * any store over the same database and schema `NOTIFY`s the thread they wait on.
 *
 * A notification is a hint, never data: a woken waiter reads the log again, so a missed, late, duplicated or
 * colliding notification changes when a waiter reads, never what it returns. Waiters never wait on the listener
 * itself, only on their own `timeout`, so a listener that is connecting, lost or stalled leaves each call bounded and
 * the store polling under its callers' `WatchPolicy` until it listens again.
 *
 * The listener starts with the first waiter and lives until [[close]], on a daemon thread of its own: connect, `LISTEN`,
 * wake every waiter (so anything committed before the `LISTEN` took effect is read), then receive notifications,
 * running a bounded heartbeat (`SELECT 1`, aborted after `callTimeout`) whenever `callTimeout` passes without one, so
 * a connection the network lost silently is noticed. A connection that fails is discarded, and connecting is retried
 * with a backoff from 100 ms up to `callTimeout`.
 */
final private[postgres] class ChangeNotifier(
  val channel: String,
  connect: () => Connection,
  callTimeout: FiniteDuration,
  name: String
):
  import ChangeNotifier.*

  private val waiters                                  = new ConcurrentHashMap[String, java.util.Set[Waiter]]()
  private val started                                  = new AtomicBoolean(false)
  private val opened                                   = new java.util.concurrent.atomic.AtomicInteger(0)
  @volatile private var closed                         = false
  @volatile private var current: Option[Listen]        = None
  @volatile private var listenerThread: Option[Thread] = None

  /** Whether the listener is connected and `LISTEN`ing: a commit then wakes its waiters as soon as it is delivered. */
  def listening: Boolean = current.isDefined

  /** The listening connection's backend process id, while it is listening: for tests that kill it. */
  def listenerPid: Option[Int] = current.map(_.pid)

  /** How many listening connections it has opened: one, unless one was lost. */
  def connectionsOpened: Int = opened.get

  /**
   * A waiter for commits on `threadId`, registered before the caller reads the log, so a commit that lands after that
   * read wakes it; close it when the call ends. Starts the listener on first use.
   */
  def register(threadId: ThreadId): Waiter =
    val waiter = new Waiter(payload(threadId), this)
    // added inside the map's own update, so a concurrent removal of the key's last waiter cannot orphan this one
    waiters.compute(
      waiter.key,
      { (_, found) =>
        val set = Option(found).getOrElse(ConcurrentHashMap.newKeySet[Waiter]())
        set.add(waiter): Unit
        set
      }
    ): Unit
    if !closed && started.compareAndSet(false, true) then start()
    waiter

  private[postgres] def remove(waiter: Waiter): Unit =
    waiters.computeIfPresent(
      waiter.key,
      { (_, set) =>
        set.remove(waiter): Unit
        if set.isEmpty then null else set
      }
    ): Unit

  /** Wakes the waiters on the thread whose payload is `key`. */
  private[postgres] def wake(key: String): Unit =
    Option(waiters.get(key)).foreach(_.asScala.foreach(_.wake()))

  /** Wakes every waiter: each reads its log again. */
  private[postgres] def wakeAll(): Unit = waiters.values().asScala.foreach(_.asScala.foreach(_.wake()))

  /** Stops the listener and closes its connection; every waiter is woken, so it reads again (and finds the store shut). */
  def close(): Unit =
    closed = true
    current.foreach(listen => abort(listen.connection))
    listenerThread.foreach(_.interrupt())
    wakeAll()

  private def start(): Unit =
    val thread =
      Thread.ofPlatform().daemon(true).name(s"llm4s-postgres-checkpointer-listener-$name").unstarted(() => run())
    listenerThread = Some(thread)
    thread.start()

  private def run(): Unit =
    var backoff = InitialBackoff
    while !closed do
      listen() match
        case None =>
          // the waiters poll meanwhile; each attempt is bounded by the connection's own timeouts, and only close
          // interrupts this sleep, after which the loop ends
          CancelledError.catchInterrupt(Thread.sleep(backoff.toMillis)): Unit
          backoff = (backoff * 2).min(callTimeout.max(InitialBackoff))
        case Some(listen) =>
          backoff = InitialBackoff
          current = Some(listen)
          if closed then abort(listen.connection)
          else
            logger.info(s"listening for checkpoint commits on channel $channel")
            // a commit made before the LISTEN took effect sent this store nothing: every waiter reads again
            wakeAll()
            receive(listen)
          current = None
          abort(listen.connection)
          if !closed then logger.warn(s"lost the connection listening on channel $channel; polling until it is back")

  /** A connection `LISTEN`ing on the channel, or `None` if one cannot be had now. */
  private def listen(): Option[Listen] =
    Try(connect()).toOption.flatMap { c =>
      opened.incrementAndGet(): Unit
      val listening = Try {
        c.setAutoCommit(true)
        bounded(c)(Using.resource(c.createStatement())(_.execute(s"""LISTEN "$channel"""")): Unit)
        Listen(c, c.unwrap(classOf[PGConnection]))
      }
      if listening.isFailure then abort(c)
      listening.toOption
    }

  /** Receives notifications until the connection fails, or the notifier is closed. */
  private def receive(listen: Listen): Unit =
    val waitMillis = math.max(1L, callTimeout.toMillis).min(Int.MaxValue.toLong).toInt
    var healthy    = true
    while healthy && !closed do
      Try(listen.pg.getNotifications(waitMillis)) match
        case scala.util.Failure(_) => healthy = false
        case scala.util.Success(found) =>
          val notes = Option(found).toVector.flatten
          if notes.isEmpty then
            // quiet for a whole interval: prove the connection still answers, within the call timeout
            healthy = Try(bounded(listen.connection)(Using.resource(listen.connection.createStatement()) {
              _.execute("SELECT 1"): Unit
            })).isSuccess
          else notes.foreach(note => wake(note.getParameter))

  /** `body` on `c`, which is aborted if `body` runs past the call timeout. */
  private def bounded[A](c: Connection)(body: => A): A =
    val watch   = PostgresCheckpointer.Watch.arm(c, callTimeout)
    val outcome = Try(body)
    val fired   = !watch.disarm()
    if fired then throw new java.sql.SQLTimeoutException(PostgresCheckpointer.timedOut(callTimeout))
    outcome.get

private[postgres] object ChangeNotifier:

  private val logger = LoggerFactory.getLogger(classOf[ChangeNotifier])

  private val InitialBackoff: FiniteDuration = 100.millis

  /** PostgreSQL's longest identifier, which a channel name must fit. */
  private val MaxChannel = 63

  final private case class Listen(connection: Connection, pg: PGConnection):
    val pid: Int = pg.getBackendPID

  /**
   * The channel of the stores over `schema`: `llm4s_checkpoint_<schema>` where that fits an identifier, and otherwise
   * a hash of the schema after `llm4s_checkpoint.` (a `.` no schema name has), so stores over two schemas of one
   * database never wake each other's waiters.
   */
  def channelFor(schema: String): String =
    val readable = s"llm4s_checkpoint_$schema"
    if readable.length <= MaxChannel then readable else s"llm4s_checkpoint.${digest(schema)}"

  /**
   * A thread's notification payload: a 64-bit hash of its id, in hex. Fixed in size and carrying nothing of the thread
   * (rule 10); two threads whose hashes collide only wake each other's waiters, which read again and find nothing.
   */
  def payload(threadId: ThreadId): String = digest(threadId.value)

  /**
   * `read` (a read of `threadId`'s log), waiting on `waiter` when it finds nothing yet - the body of
   * [[PostgresCheckpointer.awaitEventsAfter]]. `waiter` is registered before the first read, so a commit that lands
   * after a read wakes it; a wake reads again and returns what it finds, or waits on for what is left of `timeout` if
   * the notification brought nothing (a hash collision, or a commit that stored nothing the read wants). It waits
   * only on its own `timeout`, never on the listener, so it returns within `timeout` plus the bound of each read. A
   * failed read is returned at once; an interrupt ends the wait with `CancelledError`, the flag set again.
   */
  def readUntilWoken[A](threadId: ThreadId, waiter: Waiter, timeout: FiniteDuration)(
    read: => Result[Vector[A]]
  ): Result[Vector[A]] =
    val deadline = System.nanoTime() + timeout.toNanos
    @scala.annotation.tailrec
    def await(): Result[Vector[A]] =
      read match
        case Right(found) if found.isEmpty =>
          val remaining = deadline - System.nanoTime()
          if remaining <= 0 then Right(found)
          else
            CancelledError.catchInterrupt(waiter.await(remaining)) match
              case Right(true)       => await()
              case Right(false)      => Right(found)
              case Left(interrupted) => Checkpointer.waitCancelled(threadId, interrupted)
        case other => other
    await()

  /** The statement a commit sends its notification with, inside its transaction, so it is delivered only on commit. */
  val NotifySql: String = "SELECT pg_notify(?, ?)"

  private def digest(value: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(value.getBytes(StandardCharsets.UTF_8))
      .take(8)
      .map(b => f"${b & 0xff}%02x")
      .mkString

  private def abort(c: Connection): Unit =
    Try(c.abort(command => command.run())): Unit
    Try(c.close()): Unit

  /** One waiting call's wake-up: woken any number of times, each wake ending at most one [[await]]. */
  final class Waiter private[postgres] (private[postgres] val key: String, notifier: ChangeNotifier)
      extends AutoCloseable:
    private val signal = new Semaphore(0)

    private[postgres] def wake(): Unit = signal.release()

    /** Waits up to `nanos` for a wake; whether one came. Throws `InterruptedException` if interrupted. */
    def await(nanos: Long): Boolean =
      val woken = signal.tryAcquire(nanos, TimeUnit.NANOSECONDS)
      // wakes that piled up while the caller read cover what it is about to read again
      if woken then signal.drainPermits(): Unit
      woken

    def close(): Unit = notifier.remove(this)
