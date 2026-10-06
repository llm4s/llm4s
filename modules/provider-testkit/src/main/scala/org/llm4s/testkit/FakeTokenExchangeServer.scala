package org.llm4s.testkit

import com.sun.net.httpserver.{ HttpExchange, HttpServer }

import java.net.{ InetSocketAddress, URLDecoder }
import java.nio.charset.StandardCharsets
import java.util.concurrent.{ ExecutorService, Executors }
import java.util.concurrent.atomic.{ AtomicInteger, AtomicLong }
import scala.collection.mutable
import scala.util.Try

/**
 * A local identity provider plus a protected API, for testing workload-identity auth without a
 * network: an RFC 8693 token endpoint (Databricks' `/oidc/v1/token`), Anthropic's `jwt-bearer`
 * grant, and OpenAI-format and Anthropic-format endpoints that accept only the most recently
 * issued token. Tokens are `t1`, `t2`, ... in issue order.
 */
final class FakeTokenExchangeServer private (server: HttpServer, executor: ExecutorService):
  import FakeTokenExchangeServer.*

  val baseUrl: String = s"http://localhost:${server.getAddress.getPort}"

  private val lock           = new Object
  private val exchangeLog    = mutable.Buffer.empty[Map[String, String]]
  private val authorizations = mutable.Buffer.empty[String]
  private val issued         = mutable.Buffer.empty[String]
  private val expiresIn      = new AtomicLong(3600)
  private val toReject       = new AtomicInteger(0)
  private val rejectStatus   = new AtomicInteger(401)
  @volatile private var validator: String => Either[String, Unit] =
    token => Either.cond(token.trim.nonEmpty, (), "empty subject token")

  /** Every token request received, as its form fields (or JSON fields, for the Anthropic grant). */
  def exchanges: Seq[Map[String, String]] = lock.synchronized(exchangeLog.toSeq)

  /** The `Authorization` header of every API call, in order. */
  def apiAuthorizations: Seq[String] = lock.synchronized(authorizations.toSeq)

  /** The tokens issued so far: `t1`, `t2`, ... */
  def issuedTokens: Seq[String] = lock.synchronized(issued.toSeq)

  /** How long the tokens issued from now on say they live; 3600 by default. */
  def setExpiresIn(seconds: Long): Unit = expiresIn.set(seconds)

  /** Answers the next `n` API calls with 401, whatever token they carry. */
  def rejectNextApiCalls(n: Int): Unit = rejectNextApiCalls(n, 401)

  /** Answers the next `n` API calls with `status` (401, or 403 for a token that is valid but not allowed). */
  def rejectNextApiCalls(n: Int, status: Int): Unit =
    rejectStatus.set(status)
    toReject.set(n)

  /** Decides whether a presented subject token is acceptable; the default accepts anything non-blank. */
  def setSubjectValidator(f: String => Either[String, Unit]): Unit = validator = f

  def close(): Unit =
    server.stop(0)
    executor.shutdownNow(): Unit

  server.createContext(TokenPath, ex => exchange(ex, formFields(body(ex)), "subject_token"))
  server.createContext(AnthropicTokenPath, ex => exchange(ex, jsonFields(body(ex)), "assertion"))
  server.createContext(ChatPath, ex => api(ex, openAIReply))
  server.createContext(OpenAIChatPath, ex => api(ex, openAIReply))
  server.createContext(ModelsPath, ex => api(ex, _ => (200, "application/json", """{"data":[{"id":"fake-model"}]}""")))
  server.createContext(AnthropicMessagesPath, ex => api(ex, _ => (200, "application/json", AnthropicMessage)))

  private def exchange(ex: HttpExchange, fields: Map[String, String], subjectField: String): Unit =
    lock.synchronized(exchangeLog += fields)
    validator(fields.getOrElse(subjectField, "")) match
      case Left(reason) =>
        send(
          ex,
          400,
          "application/json",
          ujson.Obj("error" -> "invalid_grant", "error_description" -> reason).render()
        )
      case Right(()) =>
        val token = lock.synchronized {
          issued += s"t${issued.size + 1}"
          issued.last
        }
        send(
          ex,
          200,
          "application/json",
          // A number, as a real identity provider sends it (ujson would write a Scala Long as a string).
          ujson
            .Obj("access_token" -> token, "token_type" -> "Bearer", "expires_in" -> ujson.Num(expiresIn.get.toDouble))
            .render()
        )

  private def api(ex: HttpExchange, reply: String => (Int, String, String)): Unit =
    val auth = Option(ex.getRequestHeaders.getFirst("Authorization")).getOrElse("")
    val text = body(ex)
    lock.synchronized(authorizations += auth)
    val latest   = lock.synchronized(issued.lastOption)
    val rejected = toReject.getAndUpdate(n => math.max(0, n - 1)) > 0
    if rejected || latest.forall(t => auth != s"Bearer $t") then
      val status = if rejected then rejectStatus.get else 401
      send(ex, status, "application/json", """{"error":{"type":"authentication_error","message":"invalid token"}}""")
    else
      val (status, contentType, out) = reply(text)
      send(ex, status, contentType, out)

  private def openAIReply(request: String): (Int, String, String) =
    if request.contains("\"stream\":true") then
      (200, "text/event-stream", LocalProviderTestServer.openAISseBody(Seq("hello")))
    else (200, "application/json", LocalProviderTestServer.openAICompletion("hello"))

object FakeTokenExchangeServer:
  val TokenPath: String             = "/oidc/v1/token"
  val ChatPath: String              = "/serving-endpoints/chat/completions"
  val ModelsPath: String            = "/serving-endpoints/models"
  val OpenAIChatPath: String        = "/v1/chat/completions"
  val AnthropicTokenPath: String    = "/v1/oauth/token"
  val AnthropicMessagesPath: String = "/v1/messages"

  private val AnthropicMessage =
    """{"id":"msg_1","type":"message","role":"assistant","model":"claude-test","content":[{"type":"text","text":"hello"}],""" +
      """"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}"""

  def start(): FakeTokenExchangeServer =
    val server   = HttpServer.create(new InetSocketAddress("localhost", 0), 0)
    val executor = Executors.newVirtualThreadPerTaskExecutor()
    server.setExecutor(executor)
    val fake = new FakeTokenExchangeServer(server, executor)
    server.start()
    fake

  /** Starts a server, runs `test` with it, and stops it - also when `test` throws. */
  def withServer(test: FakeTokenExchangeServer => Any): Unit =
    val fake    = start()
    val outcome = Try(test(fake))
    fake.close()
    outcome.fold(error => throw error, _ => ())

  private def body(ex: HttpExchange): String =
    new String(ex.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)

  private def formFields(text: String): Map[String, String] =
    text
      .split('&')
      .toSeq
      .filter(_.contains('='))
      .map { pair =>
        val Array(k, v) = pair.split("=", 2)
        URLDecoder.decode(k, StandardCharsets.UTF_8) -> URLDecoder.decode(v, StandardCharsets.UTF_8)
      }
      .toMap

  private def jsonFields(text: String): Map[String, String] =
    Try(ujson.read(text).obj.collect { case (k, ujson.Str(v)) => k -> v }.toMap).getOrElse(Map.empty)

  private def send(ex: HttpExchange, status: Int, contentType: String, out: String): Unit =
    val bytes = out.getBytes(StandardCharsets.UTF_8)
    ex.getResponseHeaders.set("Content-Type", contentType)
    ex.sendResponseHeaders(status, bytes.length.toLong)
    ex.getResponseBody.write(bytes)
    ex.close()
