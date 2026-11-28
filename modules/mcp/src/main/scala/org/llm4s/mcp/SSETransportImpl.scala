// scalafix:off DisableSyntax.NoKeywordCatch, DisableSyntax.NoKeywordFinally
package org.llm4s.mcp

import org.llm4s.error.{ CancelledError, LLMError, SimpleError }
import org.llm4s.http.{ HttpFailures, HttpMethod, HttpRequest, Llm4sHttpClient, StreamingHttpResponse }
import org.llm4s.types.Result
import org.llm4s.util.{ DurationRounding, Redaction }
import org.slf4j.LoggerFactory
import upickle.default._

import java.io.IOException
import java.net.URI
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{ CompletableFuture, ConcurrentHashMap, ExecutionException, TimeUnit, TimeoutException }
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.{ Failure, Success, Try }
import HttpExchanges.flattened
import PayloadLog.debugPayload

/**
 * The HTTP+SSE transport of MCP 2024-11-05 (#3073).
 *
 * The client `GET`s `url`, which opens an event stream. The server's first event, `endpoint`, names the URL to POST
 * messages to (relative to `url`, or absolute on the same origin); each POST is accepted (202) and its reply arrives
 * on the stream as a `message` event, matched to its request by id. A request the server sends on the stream is
 * answered: `ping` with an empty result, anything else with method-not-found (#3079).
 *
 * The stream is opened on the first message and kept open; one reader thread reads it. If it ends (the server closed
 * it or restarted), the calls waiting on it fail and the next message opens a new one, a new session: until
 * `initialize` is sent on it every other request is refused unsent with [[MCPSessionLost]], so the client runs the
 * handshake again and retries (#3072). A POST the server answers 404 means it no longer knows the session, and is
 * refused the same way.
 *
 * Each request, its POST and its reply, must be over within `timeout`; an event is bounded in size, as a Streamable
 * HTTP reply is. The `http` options' headers and bearer key go on the `GET` and every POST (#3078).
 *
 * Thread-safe: requests may be sent from several threads at once; opening the stream is serialised.
 *
 * @param url     the SSE endpoint (an llm4s `MCPServer`'s is `{path}/sse`)
 * @param name    the server's name, for logs and errors
 * @param timeout the longest one request may take, reply included; also the longest wait for the `endpoint` event
 * @param http    headers, credential and HTTP client; a client the transport creates is closed with it
 */
class SSETransportImpl(
  url: String,
  override val name: String,
  timeout: FiniteDuration = 30.seconds,
  http: MCPHttpOptions = MCPHttpOptions.default
) extends MCPTransportImpl {

  /** A transport that sends with `httpClient`, which it never closes. */
  def this(url: String, name: String, timeout: FiniteDuration, httpClient: Llm4sHttpClient) =
    this(url, name, timeout, MCPHttpOptions.default.withHttpClient(httpClient))

  private val logger                      = LoggerFactory.getLogger(getClass)
  private val requestId                   = new AtomicLong(0)
  private val httpClient: Llm4sHttpClient = http.httpClient.getOrElse(Llm4sHttpClient.create())
  private val ownsClient                  = http.httpClient.isEmpty

  private val connectLock                               = new Object
  @volatile private var stream: Option[EventStream]     = None
  @volatile private var hadStream                       = false
  @volatile private var handshakeNeeded                 = false
  @volatile private var protocolVersion: Option[String] = None

  logger.info(s"SSETransport($name) initialized for URL: ${HttpFailures.safeEndpoint(url)} with timeout: $timeout")

  override def useProtocolVersion(version: String): Unit = protocolVersion = Some(version)

  /** Whether an event stream is open and still being read. */
  private[mcp] def streamOpen: Boolean = stream.exists(!_.ended)

  /** One open event stream: one session, with its endpoint, its reader thread and the requests waiting on it. */
  final private class EventStream(response: StreamingHttpResponse) {
    private val body =
      new BoundedResponseBody(response.body, Deadline.now + SSETransportImpl.StreamLifetime, timeout, url)
    val endpoint = new CompletableFuture[String]()
    val pending  = new ConcurrentHashMap[String, CompletableFuture[ujson.Value]]()

    @volatile var ended             = false
    @volatile var endReason: String = ""
    @volatile private var closing   = false

    private val reader: Thread = {
      val thread = new Thread(() => read(), s"SSETransport-$name-reader")
      thread.setDaemon(true)
      thread.start()
      thread
    }

    def endpointUrl: Option[String] = Try(Option(endpoint.getNow(null))).toOption.flatten

    /** Closes the stream, which ends the session on the server, and fails the calls still waiting. */
    def close(): Unit = {
      closing = true
      body.close()
      Try(reader.join(1000)).failed.foreach {
        case _: InterruptedException => Thread.currentThread().interrupt()
        case _                       => ()
      }
      end("Transport closing")
    }

    private def end(reason: String): Unit = {
      if (!ended) {
        endReason = reason
        ended = true
      }
      endpoint.completeExceptionally(new IOException(reason))
      pending.keySet.asScala.toList.foreach { id =>
        Option(pending.remove(id)).foreach(_.completeExceptionally(new IOException(reason)))
      }
    }

    private def read(): Unit = {
      @scala.annotation.tailrec
      def loop(): Unit =
        SseEvents.next(
          body,
          line => logger.debugPayload(s"SSETransport($name) ignoring unrecognized SSE line: ", line)
        ) match {
          case Right(Some(event)) =>
            handle(event)
            loop()
          case Right(None) => end(if (closing) "Transport closing" else "the server closed the event stream")
          case Left(error) =>
            end(if (closing) "Transport closing" else s"reading the event stream failed: ${error.message}")
        }
      loop()
      if (!closing) logger.warn(s"SSETransport($name) event stream ended: $endReason")
      logger.debug(s"SSETransport($name) reader thread exiting")
    }

    private def handle(event: SseEvents.Event): Unit =
      event.eventType match {
        case Some("endpoint") =>
          SSETransportImpl.resolveEndpoint(url, event.data) match {
            case Right(target) =>
              logger.debug(s"SSETransport($name) received its message endpoint")
              endpoint.complete(target): Unit
            case Left(reason) =>
              logger.error(s"SSETransport($name) $reason")
              endpoint.completeExceptionally(new IOException(reason)): Unit
          }
        case _ if event.isMessage => route(event.data)
        case other => logger.debug(s"SSETransport($name) ignoring an event of type ${other.getOrElse("")}")
      }

    // A response goes to the call waiting for its id; a request the server sends is answered; a notification is logged.
    private def route(data: String): Unit =
      Try(ujson.read(data)) match {
        case Failure(_) => logger.debugPayload(s"SSETransport($name) skipping non-JSON SSE data: ", data)
        case Success(json) =>
          JsonRpcReplies.serverRequest(json) match {
            case Some((id, method)) =>
              logger.debug(s"SSETransport($name) answering the server's request $method")
              val answer = JsonRpcReplies.answerToServerRequest(id, method).render()
              // Posted off the reader thread, which must keep reading the stream the server may be writing to
              val _ = Thread.ofVirtual().name(s"SSETransport-$name-answer").start { () =>
                post(this, answer, Deadline.now + timeout).left.foreach { error =>
                  logger.warn(s"SSETransport($name) could not answer the server's $method: ${error.message}")
                }
              }
            case None =>
              json.objOpt.filterNot(_.contains("method")).flatMap(_.get("id")).flatMap(_.strOpt) match {
                case Some(id) =>
                  Option(pending.remove(id)) match {
                    case Some(future) => future.complete(json): Unit
                    case None         => logger.warn(s"SSETransport($name) received a reply to unknown request $id")
                  }
                case None => logger.debugPayload(s"SSETransport($name) received a notification: ", data)
              }
          }
      }
  }

  /**
   * The open stream, opened if there is none or it has ended; serialised, so concurrent first calls open one. A new
   * stream after an earlier one is a new session: anything but `initialize` is refused there until the handshake has
   * run again ([[MCPSessionLost]]).
   */
  private def streamFor(request: Option[JsonRpcRequest], deadline: Deadline): Result[EventStream] =
    connectLock.synchronized {
      val open = stream match {
        case Some(s) if !s.ended => Right(s)
        case previous =>
          previous.foreach(_.close())
          stream = None
          openStream(deadline).map { s =>
            stream = Some(s)
            if (hadStream) {
              logger.warn(s"SSETransport($name) reopened the event stream: a new session, which needs the handshake")
              handshakeNeeded = true
            }
            hadStream = true
            s
          }
      }
      open.flatMap { s =>
        request match {
          case Some(r) if r.method == "initialize" => handshakeNeeded = false; Right(s)
          case Some(_) if handshakeNeeded          => Left(MCPSessionLost.StreamLost)
          case _                                   => Right(s)
        }
      }
    }

  // GET the stream and wait for its endpoint event, all by `deadline`
  private def openStream(deadline: Deadline): Result[EventStream] = {
    logger.info(s"SSETransport($name) opening the event stream at ${HttpFailures.safeEndpoint(url)}")
    val opened = Try {
      http
        .requestHeaders(Map("Accept" -> "text/event-stream") ++ versionHeader)
        .flatMap { headers =>
          httpClient.send(
            HttpRequest(HttpMethod.Get, url, headers)
              .withResponseTimeout(SSETransportImpl.atLeastAMilli(deadline.timeLeft))
              .withIdleTimeout(SSETransportImpl.StreamIdleTimeout)
          )
        }
    }.flattened
    opened match {
      case Left(error) => Left(transportFailure(error))
      case Right(response) if response.statusCode < 200 || response.statusCode >= 300 =>
        readBounded(response, deadline).flatMap { text =>
          Left(
            MCPHttpStatus(
              response.statusCode,
              s"Transport error: HTTP error ${response.statusCode}: ${Redaction.safeBody(text)}"
            )
          )
        }
      case Right(response) if !response.header("content-type").exists(_.toLowerCase.contains("text/event-stream")) =>
        Try(response.body.close())
        Left(
          SimpleError(
            s"Transport error: GET ${HttpFailures.safeEndpoint(url)} did not open an event stream (content type " +
              s"${response.header("content-type").getOrElse("none")})"
          )
        )
      case Right(response) =>
        val s = new EventStream(response)
        await(s.endpoint, deadline) match {
          case Right(_) =>
            logger.info(s"SSETransport($name) established an MCP session")
            Right(s)
          case Left(cancelled: CancelledError) =>
            s.close()
            Left(cancelled)
          case Left(error) =>
            s.close()
            val reason =
              if (error.message.startsWith("timed out")) s"the server sent no endpoint event within $timeout"
              else error.message
            Left(SimpleError(s"Transport error: $reason"))
        }
    }
  }

  /**
   * The response's body, bounded, for an error message or to finish an acknowledgement. A cancelled read is a
   * `Left(CancelledError)`, which the caller returns; any other failure to read it is an empty body, since only the
   * status matters.
   */
  private def readBounded(response: StreamingHttpResponse, deadline: Deadline): Result[String] = {
    val body = new BoundedResponseBody(response.body, deadline, timeout, url, PayloadLog.MaxChars * 4)
    try
      body.readAll() match {
        case Left(cancelled: CancelledError) => Left(cancelled)
        case other                           => Right(other.getOrElse(""))
      }
    finally body.close()
  }

  private def versionHeader: Map[String, String] = protocolVersion.map("MCP-Protocol-Version" -> _).toMap

  /** POSTs one message to the session's endpoint; a 404 means the server no longer knows the session. */
  private def post(s: EventStream, json: String, deadline: Deadline): Result[Unit] =
    s.endpointUrl match {
      case None => Left(SimpleError("Transport error: no open event stream"))
      case Some(endpoint) =>
        val sent = Try {
          http
            .requestHeaders(
              Map("Content-Type" -> "application/json", "Accept" -> "application/json, text/event-stream") ++
                versionHeader
            )
            .flatMap { headers =>
              httpClient.send(
                HttpRequest(HttpMethod.Post, endpoint, headers)
                  .withTextBody(json)
                  .withTimeout(SSETransportImpl.atLeastAMilli(deadline.timeLeft))
              )
            }
        }.flattened
        sent match {
          case Left(error)     => Left(transportFailure(error))
          case Right(response) =>
            // A cancelled read of the acknowledgement is a cancelled post, never a sent one
            readBounded(response, deadline).flatMap { text =>
              if (response.statusCode == 404) {
                logger.warn(s"SSETransport($name) the server no longer knows the session (404)")
                s.close()
                Left(MCPSessionLost.Expired)
              } else if (response.statusCode >= 400)
                Left(
                  MCPHttpStatus(
                    response.statusCode,
                    s"Transport error: HTTP error ${response.statusCode}: ${Redaction.safeBody(text)}"
                  )
                )
              else Right(())
            }
        }
    }

  private def transportFailure(error: LLMError): LLMError = error match {
    case cancelled: CancelledError => cancelled
    case other =>
      logger.error(s"SSETransport($name) transport error for ${HttpFailures.safeEndpoint(url)}: ${other.message}")
      SimpleError(s"Transport error: ${other.message}")
  }

  /** Waits for `future` until `deadline`: an interrupt is a `CancelledError` with the flag set again. */
  private def await[A](future: CompletableFuture[A], deadline: Deadline): Result[A] =
    try Right(future.get(DurationRounding.ceilMillis(deadline.timeLeft.max(Duration.Zero)), TimeUnit.MILLISECONDS))
    catch {
      case e: InterruptedException =>
        Thread.currentThread().interrupt()
        Left(CancelledError("mcp.sse.request", Some(e)))
      case _: TimeoutException => Left(SimpleError(s"timed out after $timeout"))
      case e: ExecutionException =>
        Left(SimpleError(Option(e.getCause).map(_.getMessage).getOrElse(e.getMessage)))
    }

  /**
   * Sends `request` and waits for its reply on the stream, all within `timeout`. An interrupt returns
   * `Left(CancelledError)` with the flag set and leaves nothing pending.
   */
  override def sendRequest(request: JsonRpcRequest): Result[JsonRpcResponse] =
    CancelledError.attempt("mcp.sse.request") {
      logger.debug(s"SSETransport($name) sending request: method=${request.method}, id=${request.id}")
      val deadline = Deadline.now + timeout
      streamFor(Some(request), deadline).flatMap { s =>
        val reply = new CompletableFuture[ujson.Value]()
        s.pending.put(request.id, reply)
        // The reader may have ended between streamFor and the put, and failed every call pending then: this one too.
        if (s.ended) reply.completeExceptionally(new IOException(s.endReason))
        val json = write(request)
        logger.debugPayload(s"SSETransport($name) request JSON: ", json)
        post(s, json, deadline) match {
          case Left(error) =>
            s.pending.remove(request.id)
            Left(error)
          case Right(()) =>
            await(reply, deadline) match {
              case Left(cancelled: CancelledError) =>
                s.pending.remove(request.id)
                Left(cancelled)
              case Left(error) =>
                s.pending.remove(request.id)
                val message =
                  if (error.message.startsWith("timed out"))
                    s"Transport error: no reply to request ${request.id}: ${error.message}"
                  else s"Transport error: ${error.message}"
                logger.error(s"SSETransport($name) $message")
                Left(SimpleError(message))
              case Right(json) => parseReply(json)
            }
        }
      }
    }

  private def parseReply(json: ujson.Value): Result[JsonRpcResponse] =
    Try(read[JsonRpcResponse](json)) match {
      case Failure(e) => Left(SimpleError(s"Transport error: ${e.getMessage}"))
      case Success(response) =>
        response.error match {
          case Some(error) =>
            val message = JsonRpcReplies.errorMessage(error)
            logger.error(s"SSETransport($name) $message")
            Left(SimpleError(message))
          case None => Right(response)
        }
    }

  /** POSTs `notification` to the session's endpoint, opening the stream first if need be. */
  override def sendNotification(notification: JsonRpcNotification): Result[Unit] =
    CancelledError.attempt("mcp.sse.notification") {
      logger.debug(s"SSETransport($name) sending notification: method=${notification.method}")
      val deadline = Deadline.now + timeout
      streamFor(None, deadline).flatMap(s => post(s, write(notification), deadline)).left.map {
        case cancelled: CancelledError => cancelled
        case error =>
          logger.error(s"SSETransport($name) notification error: ${error.message}")
          SimpleError(s"Notification error: ${error.message}")
      }
    }

  /** Closes the event stream, which ends the session, fails any call still waiting, and closes an owned client. */
  override def close(): Unit = {
    logger.info(s"SSETransport($name) closing connection to ${HttpFailures.safeEndpoint(url)}")
    val open = connectLock.synchronized {
      val s = stream
      stream = None
      hadStream = false
      handshakeNeeded = false
      s
    }
    open.foreach(_.close())
    if (ownsClient) httpClient.close()
  }

  // Generates unique request IDs
  def generateId(): String = requestId.incrementAndGet().toString
}

private[mcp] object SSETransportImpl {

  /**
   * The longest the event stream may wait for its next bytes: a quiet session is normal, so this is long; a stream
   * that does pass it ends, and the next message opens a new one.
   */
  val StreamIdleTimeout: FiniteDuration = 1.hour

  /** The stream's body has no deadline of its own; this stands in for "none". */
  val StreamLifetime: FiniteDuration = 3650.days

  def atLeastAMilli(d: FiniteDuration): FiniteDuration = d.max(1.milli)

  /**
   * The URL an `endpoint` event names, resolved against the SSE `url` (a server may send a path, or an absolute
   * URL). It must be on the same origin - scheme, host and port - as `url`, as the official SDKs require: the
   * client's headers and bearer key go to it, and a server must not be able to send them elsewhere.
   */
  def resolveEndpoint(url: String, data: String): Either[String, String] =
    Try(URI.create(url).resolve(data.trim)).toEither.left
      .map(_ => "the endpoint event is not a URL")
      .flatMap { target =>
        val base           = URI.create(url)
        def origin(u: URI) = (Option(u.getScheme).map(_.toLowerCase), Option(u.getHost).map(_.toLowerCase), port(u))
        if (origin(target) == origin(base)) Right(target.toString)
        else Left(s"the endpoint event names another origin than ${HttpFailures.safeEndpoint(url)}; refused")
      }

  private def port(u: URI): Int =
    if (u.getPort >= 0) u.getPort
    else if ("https".equalsIgnoreCase(u.getScheme)) 443
    else 80
}
