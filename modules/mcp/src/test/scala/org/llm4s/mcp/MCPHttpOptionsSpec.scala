package org.llm4s.mcp

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{ Level, Logger => LogbackLogger }
import ch.qos.logback.core.read.ListAppender
import org.llm4s.error.ConfigurationError
import org.llm4s.http.{ HttpRequest, Llm4sHttpClient, StreamingHttpResponse }
import org.llm4s.llmconnect.auth.ApiKeyProvider
import org.llm4s.security.Secret
import org.llm4s.toolapi.{ Schema, ToolBuilder }
import org.llm4s.types.ProviderModelTypes.ApiKey
import org.llm4s.types.Result
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * The client reaches an authenticated or routed HTTP MCP server (#3078): `MCPHttpOptions` headers and a bearer key go
 * on every request, a supplied `Llm4sHttpClient` carries them and is never closed, one the transport created is, and
 * the session id is never logged.
 */
class MCPHttpOptionsSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val secretKey         = "mcp-test-key-3078"
  private var server: MCPServer = _
  private def url               = s"http://127.0.0.1:${server.boundPort}/mcp"

  override def beforeAll(): Unit = {
    val echo = ToolBuilder[String]("echo", "Echoes", Schema.`object`[Map[String, Any]]("No parameters"))
      .withHandler(_ => Right("echoed"))
      .build()
      .fold(e => fail(e.formatted), identity)
    server =
      new MCPServer(MCPServerOptions(0, "/mcp", "AuthServer", "1.0", apiKey = Some(Secret(secretKey))), Seq(echo))
    server.start().fold(e => throw e, _ => ())
  }

  override def afterAll(): Unit = if (server != null) server.stop()

  /** An `Llm4sHttpClient` that records each request and whether it was closed, sending through a real client. */
  private class Recording extends Llm4sHttpClient {
    private val real = Llm4sHttpClient.create()
    val requests     = new ConcurrentLinkedQueue[HttpRequest]()
    val closes       = new AtomicInteger(0)
    override def send(request: HttpRequest): Result[StreamingHttpResponse] = {
      requests.add(request)
      real.send(request)
    }
    override def close(): Unit = closes.incrementAndGet(): Unit
  }

  private def config(http: MCPHttpOptions) = MCPServerConfig.streamableHTTP("auth", url, 5.seconds, http = http)

  "MCPClientImpl" should "call a server that requires a bearer key, given the key" in {
    val client = new MCPClientImpl(config(MCPHttpOptions().withApiKey(ApiKeyProvider.static(ApiKey(secretKey)))))
    try client.getTools().map(_.map(_.name)) shouldBe Right(Seq("echo"))
    finally client.close()
  }

  it should "report the server's 401 without the key, not fall back to HTTP+SSE" in {
    val client = new MCPClientImpl(config(MCPHttpOptions.default))
    try {
      val message = client.initialize().left.map(_.message).left.getOrElse(fail("expected a 401"))
      message should include("HTTP error 401")
      (message should not).include("both transports")
    } finally client.close()
  }

  it should "send the headers and the key on every request, the session DELETE included, through the supplied client" in {
    val http = new Recording
    val client = new MCPClientImpl(
      config(
        MCPHttpOptions()
          .withHeader("X-Tenant", "acme")
          .withHeader("Authorization", "Basic overridden")
          .withApiKey(ApiKeyProvider.static(ApiKey(secretKey)))
          .withHttpClient(http)
      )
    )
    client.getTools().isRight shouldBe true
    client.close()

    val sent = http.requests.asScala.toList
    (sent.map(_.method.name) should contain).allOf("POST", "DELETE")
    sent.foreach { request =>
      withClue(s"${request.method.name}: ") {
        request.headers.get("X-Tenant") shouldBe Some("acme")
        request.headers.get("Authorization") shouldBe Some(s"Bearer $secretKey")
      }
    }
    http.closes.get shouldBe 0 // the caller's client is the caller's to close
  }

  it should "fail a request whose secretKey cannot be read, with the provider's error" in {
    val broken = new ApiKeyProvider {
      override def current(): Result[ApiKey] = Left(ConfigurationError("no secretKey in the vault"))
    }
    val client = new MCPClientImpl(config(MCPHttpOptions().withApiKey(broken)))
    try
      client.initialize().left.map(_.message).left.getOrElse(fail("expected a Left")) should include(
        "no secretKey in the vault"
      )
    finally client.close()
  }

  it should "never log the session id" in {
    val logger   = LoggerFactory.getLogger(classOf[StreamableHTTPTransportImpl]).asInstanceOf[LogbackLogger]
    val appender = new ListAppender[ILoggingEvent]()
    val previous = logger.getLevel
    appender.start()
    logger.addAppender(appender)
    logger.setLevel(Level.DEBUG)
    val http = new Recording
    val client = new MCPClientImpl(
      config(MCPHttpOptions().withApiKey(ApiKeyProvider.static(ApiKey(secretKey))).withHttpClient(http))
    )
    try client.getTools().isRight shouldBe true
    finally {
      client.close()
      logger.detachAppender(appender)
      logger.setLevel(previous)
    }
    val sessionIds = http.requests.asScala.flatMap(_.headers.get("mcp-session-id")).toSet
    sessionIds should not be empty
    val logged = appender.list.asScala.map(_.getFormattedMessage)
    sessionIds.foreach(id => logged.foreach(line => (line should not).include(id)))
  }

  "StreamableHTTPTransportImpl" should "never close a client it was given" in {
    val supplied = new Recording
    new StreamableHTTPTransportImpl(url, "given", 5.seconds, supplied).close()
    supplied.closes.get shouldBe 0
  }

  "MCPHttpOptions" should "let the transport's own headers win, whatever the case of the caller's" in {
    val options = MCPHttpOptions(headers = Map("content-type" -> "text/plain", "X-A" -> "1"))
    options.requestHeaders(Map("Content-Type" -> "application/json")) shouldBe
      Right(Map("X-A" -> "1", "Content-Type" -> "application/json"))
  }

  it should "show header names but never their values or the key" in {
    val text = MCPHttpOptions()
      .withHeader("X-Api-Token", "secret-value")
      .withApiKey(ApiKeyProvider.static(ApiKey("secret-key")))
      .toString
    text should include("X-Api-Token")
    (text should not).include("secret-value")
    (text should not).include("secret-key")
  }
}
