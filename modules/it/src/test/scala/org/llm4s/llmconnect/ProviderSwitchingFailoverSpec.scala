package org.llm4s.llmconnect

// scalafix:off DisableSyntax.NoConfigFactory
import com.typesafe.config.ConfigFactory
// scalafix:on DisableSyntax.NoConfigFactory
import com.sun.net.httpserver.HttpExchange
import org.llm4s.config.Llm4sConfig
import org.llm4s.error.{
  AuthenticationError,
  ConfigurationError,
  LLMError,
  NetworkError,
  RateLimitError,
  ServiceError,
  TimeoutError
}
import org.llm4s.it.tags.Local
import org.llm4s.llmconnect.config.{ AnthropicConfig, OllamaConfig, OpenAIConfig, ProviderConfig }
import org.llm4s.llmconnect.model.{
  Completion,
  CompletionOptions,
  Conversation,
  StreamedChunk,
  TokenUsage,
  UserMessage
}
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.model.ModelRegistryService
import org.llm4s.reliability.{ CircuitBreakerConfig, CircuitState, ReliabilityConfig, ReliableClient, RetryPolicy }
import org.llm4s.testkit.{ LocalProviderTestServer, ProviderTestConfig }
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, LoneElement }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.{ Try, Using }

/**
 * Switching between providers in one JVM, and what an application sees when one of them fails (#380).
 *
 * Every provider here is a real client - `OpenAIClient` (openai-java SDK), `AnthropicClient` (Anthropic
 * SDK) and `OllamaClient` (llm4s's own HTTP client) - built the way an application builds one: a named
 * section under `llm4s.providers`, loaded by name with `Llm4sConfig.provider` (through
 * `ProviderTestConfig`, which is that path with the environment supplied by the test), then
 * `LLMConnect.getClient`. Each talks to its own local mock server speaking that provider's wire format,
 * so nothing leaves the machine: no network, no API key, no Docker.
 *
 * The mock servers send `x-should-retry: false` on every error, which tells both vendor SDKs not to
 * retry on their own: retrying is `ReliableClient`'s job, and a test that counts requests needs to know
 * who sent them.
 *
 * llm4s has no built-in failover client. The failover tests cover the documented pattern instead
 * (`docs/reliability-guide.md`, "Fallback between providers", and the cookbook's `FallbackRecipe`): each
 * provider wrapped in its own `ReliableClient`, the second asked only when the first still fails.
 *
 * Nothing asserts on elapsed time: the timeout test only needs the configured timeout to end the call.
 */
@Local
final class ProviderSwitchingFailoverSpec extends AnyWordSpec with Matchers with EitherValues with LoneElement {

  import ProviderSwitchingFailoverSpec.*

  private given ProviderRegistry     = ProviderRegistry.default
  private given ModelRegistryService = ModelRegistryService.default().value

  private val hello = Conversation(Seq(UserMessage("hello")))

  // ---- loading ------------------------------------------------------------------------------------

  /** `application.conf` declaring all three providers, each pointing at its own server. */
  private def threeProviders(openAI: String, anthropic: String, ollama: String, extra: String = ""): String =
    s"""llm4s.providers {
       |  provider = "openai-it"
       |  openai-it {
       |    provider = "openai"
       |    model    = "gpt-4o-mini"
       |    baseUrl  = "$openAI/v1"
       |    apiKey   = "$OpenAIKey"
       |    $extra
       |  }
       |  claude-it {
       |    provider = "anthropic"
       |    model    = "claude-3-5-haiku-latest"
       |    baseUrl  = "$anthropic"
       |    apiKey   = "$AnthropicKey"
       |    $extra
       |  }
       |  ollama-it {
       |    provider = "ollama"
       |    model    = "llama3"
       |    baseUrl  = "$ollama"
       |    $extra
       |  }
       |}
       |""".stripMargin

  private def load(name: String, hocon: String): Result[ProviderConfig] =
    ProviderTestConfig.loadProvider(name, hocon)

  private def clientFor(name: String, hocon: String): LLMClient =
    load(name, hocon).flatMap(LLMConnect.getClient(_)).value

  /** Runs `test` with one mock server per provider format and the three clients built from config. */
  private def withThreeProviders(extra: String = "")(test: Providers => Any): Unit = {
    val openAI    = new MockProvider(Format.OpenAI)
    val anthropic = new MockProvider(Format.Anthropic)
    val ollama    = new MockProvider(Format.Ollama)
    LocalProviderTestServer.withServer("/")(openAI.handle) { openAIUrl =>
      LocalProviderTestServer.withServer("/")(anthropic.handle) { anthropicUrl =>
        LocalProviderTestServer.withServer("/")(ollama.handle) { ollamaUrl =>
          // 127.0.0.1, not localhost: OkHttp would try ::1 first, and on a timeout report that refused
          // connection instead of the timeout.
          val hocon = threeProviders(ipv4(openAIUrl), ipv4(anthropicUrl), ipv4(ollamaUrl), extra)
          Using.resources(clientFor("openai-it", hocon), clientFor("claude-it", hocon), clientFor("ollama-it", hocon)) {
            (o, a, l) =>
              test(
                Providers(
                  hocon,
                  Seq(Live("openai", o, openAI), Live("anthropic", a, anthropic), Live("ollama", l, ollama))
                )
              )
          }
        }
      }
    }
  }

  // ---- 1. switching -----------------------------------------------------------------------------

  "Providers configured side by side" should {

    "each load as their own config type, with their own model, base URL and key" in {
      val hocon = threeProviders("http://openai.invalid", "http://anthropic.invalid", "http://ollama.invalid")

      val openAI = load("openai-it", hocon).value
      openAI shouldBe a[OpenAIConfig]
      openAI.model shouldBe "gpt-4o-mini"
      openAI.asInstanceOf[OpenAIConfig].baseUrl shouldBe "http://openai.invalid/v1"
      openAI.asInstanceOf[OpenAIConfig].apiKey shouldBe OpenAIKey

      val anthropic = load("claude-it", hocon).value
      anthropic shouldBe a[AnthropicConfig]
      anthropic.model shouldBe "claude-3-5-haiku-latest"
      anthropic.asInstanceOf[AnthropicConfig].baseUrl shouldBe "http://anthropic.invalid"
      anthropic.asInstanceOf[AnthropicConfig].apiKey shouldBe AnthropicKey

      val ollama = load("ollama-it", hocon).value
      ollama shouldBe a[OllamaConfig]
      ollama.model shouldBe "llama3"
      ollama.asInstanceOf[OllamaConfig].baseUrl shouldBe "http://ollama.invalid"
    }

    "answer in turn from one JVM, OpenAI -> Anthropic -> Ollama -> OpenAI, each request reaching only its own server" in
      withThreeProviders() { providers =>
        val order = providers.all ++ providers.all.take(1)
        order.foreach(p => p.client.complete(hello).value.content shouldBe s"hello from ${p.id}")

        providers.byId("openai").server.requests.map(_.path) shouldBe Seq(
          "/v1/chat/completions",
          "/v1/chat/completions"
        )
        providers.byId("anthropic").server.requests.map(_.path) shouldBe Seq("/v1/messages")
        providers.byId("ollama").server.requests.map(_.path) shouldBe Seq("/api/chat")
      }

    "keep each provider's credentials and model to its own requests" in
      withThreeProviders() { providers =>
        providers.all.foreach(_.client.complete(hello).value)

        val openAI = providers.byId("openai").server.requests.loneElement
        openAI.header("authorization") shouldBe Some(s"Bearer $OpenAIKey")
        openAI.header("x-api-key") shouldBe None
        openAI.json("model").str shouldBe "gpt-4o-mini"

        val anthropic = providers.byId("anthropic").server.requests.loneElement
        anthropic.header("x-api-key") shouldBe Some(AnthropicKey)
        anthropic.header("authorization") shouldBe None
        anthropic.json("model").str shouldBe "claude-3-5-haiku-latest"

        val ollama = providers.byId("ollama").server.requests.loneElement
        ollama.header("authorization") shouldBe None
        ollama.header("x-api-key") shouldBe None
        ollama.json("model").str shouldBe "llama3"

        // No provider's key reached another provider's server, in any header.
        providers.all.foreach { p =>
          val foreignKeys = Seq(OpenAIKey, AnthropicKey).filterNot(ownKey(p.id).contains)
          for {
            request <- p.server.requests
            key     <- foreignKeys
          } withClue(s"${p.id} received $key: ")(request.carries(key) shouldBe false)
        }
      }

    "stream after switching, from each provider in turn" in
      withThreeProviders() { providers =>
        (providers.all ++ providers.all).foreach { p =>
          val chunks = new ConcurrentLinkedQueue[StreamedChunk]()
          val result = p.client.streamComplete(hello, CompletionOptions(), chunk => chunks.add(chunk): Unit).value
          result.content shouldBe s"hello from ${p.id}"
          chunks.asScala.flatMap(_.content).mkString shouldBe s"hello from ${p.id}"
        }
      }

    "report each provider's model metadata: a positive context window and completion reserve" in
      withThreeProviders() { providers =>
        providers.all.foreach { p =>
          withClue(p.id) {
            p.client.getContextWindow() should be > 0
            p.client.getReserveCompletion() should be > 0
            p.client.getReserveCompletion() should be <= p.client.getContextWindow()
          }
        }
      }

    "close one provider's client without affecting the others" in
      withThreeProviders() { providers =>
        val openAI = providers.byId("openai")
        openAI.client.close()

        openAI.client.complete(hello).isLeft shouldBe true
        openAI.server.requests shouldBe empty // a closed client sends nothing

        providers.all.filterNot(_.id == "openai").foreach { p =>
          p.client.complete(hello).value.content shouldBe s"hello from ${p.id}"
        }
      }
  }

  // ---- 2. cross-provider consistency ------------------------------------------------------------

  "The same exchange through each provider" should {

    "report the same token usage, on a completion and on a stream" in
      withThreeProviders() { providers =>
        providers.all.foreach { p =>
          withClue(s"${p.id} complete: ") {
            usageOf(p.client.complete(hello).value) shouldBe Some((10, 5, 15))
          }
          withClue(s"${p.id} stream: ") {
            usageOf(p.client.streamComplete(hello, CompletionOptions(), _ => ()).value) shouldBe Some((10, 5, 15))
          }
        }
      }

    "map a tool call to the same ToolCall: name and parsed arguments" in
      withThreeProviders() { providers =>
        providers.all.foreach { p =>
          p.server.mode = Mode.ToolCall
          val completion = p.client.complete(hello).value
          withClue(p.id) {
            val call = completion.toolCalls.loneElement
            call.name shouldBe "get_weather"
            call.arguments shouldBe ujson.Obj("city" -> "Paris")
            call.id should not be empty
          }
        }
      }

    "map a 401 to a non-recoverable AuthenticationError" in
      withThreeProviders() { providers =>
        providers.all.foreach { p =>
          p.server.mode = Mode.Fail(401)
          val error = p.client.complete(hello).left.value
          withClue(s"${p.id}: $error") {
            error shouldBe an[AuthenticationError]
            retryable(error) shouldBe false
          }
        }
      }

    "map a 429 to a recoverable RateLimitError carrying the server's Retry-After" in
      withThreeProviders() { providers =>
        providers.all.foreach { p =>
          p.server.mode = Mode.Fail(429)
          val error = p.client.complete(hello).left.value
          withClue(s"${p.id}: $error") {
            error shouldBe a[RateLimitError]
            retryable(error) shouldBe true
            error.asInstanceOf[RateLimitError].retryAfter shouldBe Some(RetryAfterSeconds.seconds)
          }
        }
      }

    "map a 500 to a recoverable ServiceError carrying the status" in
      withThreeProviders() { providers =>
        providers.all.foreach { p =>
          p.server.mode = Mode.Fail(500)
          val error = p.client.complete(hello).left.value
          withClue(s"${p.id}: $error") {
            error shouldBe a[ServiceError]
            error.asInstanceOf[ServiceError].httpStatus shouldBe 500
            retryable(error) shouldBe true
          }
        }
      }
  }

  // ---- 3. failures --------------------------------------------------------------------------------

  "A provider that cannot be reached" should {

    "fail with a recoverable NetworkError, from each provider" in {
      val dead  = unusedLocalUrl()
      val hocon = threeProviders(dead, dead, dead)
      Seq("openai-it", "claude-it", "ollama-it").foreach { name =>
        Using.resource(clientFor(name, hocon)) { client =>
          val error = client.complete(hello).left.value
          withClue(s"$name: $error") {
            error shouldBe a[NetworkError]
            retryable(error) shouldBe true
          }
        }
      }
    }
  }

  "A provider that never answers" should {

    "fail with a recoverable timeout error once the section's timeouts.request has passed" in
      withThreeProviders(extra = "timeouts { request = 300ms }") { providers =>
        providers.all.foreach { p =>
          p.server.mode = Mode.Hang
          val error = p.client.complete(hello).left.value
          withClue(s"${p.id}: $error") {
            retryable(error) shouldBe true
            error should (be(a[TimeoutError]).or(be(a[NetworkError])))
          }
        }
      }
  }

  // ---- 4. failover ------------------------------------------------------------------------------

  "Failover to a second provider (the documented ReliableClient + fallback pattern)" should {

    /** Two attempts per provider, no wait between them, the circuit opening after two failed calls. */
    val reliability = ReliabilityConfig(
      retryPolicy = RetryPolicy.fixedDelay(maxAttempts = 2, delay = 1.milli),
      circuitBreaker = CircuitBreakerConfig(failureThreshold = 2, recoveryTimeout = 1.hour),
      deadline = None
    )

    def reliable(p: Live): ReliableClient = new ReliableClient(p.client, p.id, reliability, sleep = _ => ())

    "answer from the fallback when the primary still fails after its retries" in
      withThreeProviders() { providers =>
        val openAI    = providers.byId("openai")
        val anthropic = providers.byId("anthropic")
        openAI.server.mode = Mode.Fail(503)
        val primary  = reliable(openAI)
        val fallback = reliable(anthropic)

        val served = withFallback(primary, fallback).value
        served.content shouldBe "hello from anthropic"

        openAI.server.requests should have size 2 // retried once by ReliableClient
        anthropic.server.requests should have size 1
      }

    "stop calling a primary whose circuit has opened, and keep serving from the fallback" in
      withThreeProviders() { providers =>
        val openAI = providers.byId("openai")
        val ollama = providers.byId("ollama")
        openAI.server.mode = Mode.Fail(503)
        val primary  = reliable(openAI)
        val fallback = reliable(ollama)

        // The breaker counts failed calls, not attempts: two calls of two attempts each open it.
        withFallback(primary, fallback).value.content shouldBe "hello from ollama"
        primary.currentCircuitState shouldBe CircuitState.Closed
        withFallback(primary, fallback).value.content shouldBe "hello from ollama"
        primary.currentCircuitState shouldBe CircuitState.Open
        openAI.server.requests should have size 4

        withFallback(primary, fallback).value.content shouldBe "hello from ollama"
        openAI.server.requests should have size 4 // the open circuit fails fast, sending nothing
        ollama.server.requests should have size 3
      }

    "not retry a primary that rejects the credentials, and still fail over" in
      withThreeProviders() { providers =>
        val anthropic = providers.byId("anthropic")
        val openAI    = providers.byId("openai")
        anthropic.server.mode = Mode.Fail(401)

        withFallback(reliable(anthropic), reliable(openAI)).value.content shouldBe "hello from openai"
        anthropic.server.requests should have size 1 // an AuthenticationError is not retried
      }

    "return the fallback's error when both providers fail" in
      withThreeProviders() { providers =>
        val openAI    = providers.byId("openai")
        val anthropic = providers.byId("anthropic")
        openAI.server.mode = Mode.Fail(503)
        anthropic.server.mode = Mode.Fail(401)

        withFallback(reliable(openAI), reliable(anthropic)).left.value shouldBe an[AuthenticationError]
      }

    "keep the primary when it recovers within its retries" in
      withThreeProviders() { providers =>
        val openAI    = providers.byId("openai")
        val anthropic = providers.byId("anthropic")
        openAI.server.mode = Mode.FailOnce(503)

        withFallback(reliable(openAI), reliable(anthropic)).value.content shouldBe "hello from openai"
        openAI.server.requests should have size 2
        anthropic.server.requests shouldBe empty
      }
  }

  // ---- 5. configuration errors --------------------------------------------------------------------

  "An invalid or missing provider section" should {

    def errorFor(name: String, section: String): LLMError =
      load(name, s"llm4s.providers.$name { $section }").left.value

    "name the section when its API key is missing" in {
      val error = errorFor("claude-nokey", """provider = "anthropic", model = "claude-3-5-haiku-latest"""")
      error shouldBe a[ConfigurationError]
      error.message should include("llm4s.providers.claude-nokey")
    }

    "name the section when its API key is empty" in {
      val error = errorFor("openai-empty", """provider = "openai", model = "gpt-4o-mini", apiKey = """"")
      error shouldBe a[ConfigurationError]
      error.message should include("openai-empty")
    }

    "name the section when it has no model" in {
      val error = errorFor("ollama-nomodel", """provider = "ollama"""")
      error shouldBe a[ConfigurationError]
      error.message should include("ollama-nomodel")
    }

    "name the section when its provider is unknown" in {
      val error = errorFor("mystery", """provider = "no-such-provider", model = "m"""")
      error shouldBe a[ConfigurationError]
      error.message should include("mystery")
      error.message should include("no-such-provider")
    }

    // Loading checks that a base URL is an http(s) URL with a host (#2347), so a broken one is reported
    // against its key before any client is built, rather than failing on the first call.
    "refuse to load a section whose base URL is not a URL, naming the key" in {
      Seq("openai", "anthropic", "ollama").foreach { id =>
        val key   = if (id == "ollama") "" else """apiKey = "k""""
        val error = errorFor(s"$id-badurl", s"""provider = "$id", model = "m", baseUrl = "not a url", $key""")
        withClue(s"$id: $error") {
          error shouldBe a[ConfigurationError]
          error.message should include(s"llm4s.providers.$id-badurl.baseUrl")
        }
      }
    }

    "report a section that does not exist" in {
      val error = load("absent", threeProviders("http://a.invalid", "http://b.invalid", "http://c.invalid")).left.value
      error shouldBe a[ConfigurationError]
      error.message should include("absent")
    }

    "not stop the other sections from loading" in {
      val hocon =
        threeProviders("http://a.invalid", "http://b.invalid", "http://c.invalid") +
          """llm4s.providers.broken { provider = "anthropic", model = "claude-3-5-haiku-latest" }"""
      load("broken", hocon).left.value.message should include("llm4s.providers.broken")
      Seq("openai-it", "claude-it", "ollama-it").foreach(name => load(name, hocon).isRight shouldBe true)
    }
  }

  "Llm4sConfig.provider" should {

    "pick up a section's base URL changed through system properties mid-run" in {
      val first  = new MockProvider(Format.Ollama, reply = "first")
      val second = new MockProvider(Format.Ollama, reply = "second")
      LocalProviderTestServer.withServer("/")(first.handle) { firstUrl =>
        LocalProviderTestServer.withServer("/")(second.handle) { secondUrl =>
          val section = "llm4s.providers.it380-sysprop"
          withSystemProperties(
            s"$section.provider" -> "ollama",
            s"$section.model"    -> "llama3",
            s"$section.baseUrl"  -> firstUrl
          ) {
            def answer(): String =
              Llm4sConfig
                .provider("it380-sysprop")
                .flatMap(LLMConnect.getClient(_))
                .flatMap(client => Using.resource(client)(_.complete(hello)))
                .value
                .content

            answer() shouldBe "first"

            System.setProperty(s"$section.baseUrl", secondUrl)
            ConfigFactory.invalidateCaches()
            answer() shouldBe "second"

            first.requests should have size 1
            second.requests should have size 1
          }
        }
      }
    }
  }

  // ---- helpers ----------------------------------------------------------------------------------

  private def ownKey(id: String): Option[String] = id match {
    case "openai"    => Some(OpenAIKey)
    case "anthropic" => Some(AnthropicKey)
    case _           => None
  }

  /** Whether `ReliableClient` would retry `error`: the library's one retry rule, as every policy applies it. */
  private def retryable(error: LLMError): Boolean = RetryPolicy.exponentialBackoff().isRetryable(error)

  private def usageOf(completion: Completion): Option[(Int, Int, Int)] =
    completion.usage.map((u: TokenUsage) => (u.promptTokens, u.completionTokens, u.totalTokens))

  /** The documented pattern: ask the primary, and the fallback only when the primary still fails. */
  private def withFallback(primary: LLMClient, fallback: LLMClient): Result[Completion] =
    primary.complete(hello) match {
      case Left(_)  => fallback.complete(hello)
      case answered => answered
    }

  private def ipv4(url: String): String = url.replace("//localhost:", "//127.0.0.1:")

  /** A local URL nothing listens on: a port the OS handed out, then released. */
  private def unusedLocalUrl(): String = {
    val port = Using.resource(new ServerSocket(0))(_.getLocalPort)
    s"http://127.0.0.1:$port"
  }

  private def withSystemProperties(props: (String, String)*)(test: => Any): Unit = {
    val originals = props.map { case (key, _) => key -> Option(System.getProperty(key)) }
    props.foreach { case (key, value) => System.setProperty(key, value) }
    ConfigFactory.invalidateCaches()
    val outcome = Try(test)
    originals.foreach {
      case (key, Some(value)) => System.setProperty(key, value)
      case (key, None)        => System.clearProperty(key)
    }
    ConfigFactory.invalidateCaches()
    outcome.get: Unit
  }
}

object ProviderSwitchingFailoverSpec {

  val OpenAIKey         = "sk-openai-it-380"
  val AnthropicKey      = "sk-ant-it-380"
  val RetryAfterSeconds = 7

  /** The three wire formats the mock servers speak. */
  enum Format:
    case OpenAI, Anthropic, Ollama

  /** How a mock server answers its next request. */
  enum Mode:
    case Ok
    case ToolCall
    case Fail(status: Int)
    case FailOnce(status: Int)
    case Hang

  /** One request as the server received it. */
  final case class Recorded(path: String, headers: Map[String, String], body: String) {
    def header(name: String): Option[String] = headers.get(name.toLowerCase)
    def json: ujson.Value                    = ujson.read(body)
    def carries(secret: String): Boolean     = headers.values.exists(_.contains(secret)) || body.contains(secret)
  }

  final case class Live(id: String, client: LLMClient, server: MockProvider)

  final case class Providers(hocon: String, all: Seq[Live]) {
    def byId(id: String): Live = all.find(_.id == id).get
  }

  /**
   * A local stand-in for one provider: records every request and answers in that provider's wire format -
   * a completion, a stream (when the request asks for one), a tool call, an error status, or nothing at all.
   */
  final class MockProvider(format: Format, reply: String = "") {

    private val received = new ConcurrentLinkedQueue[Recorded]()

    @volatile var mode: Mode = Mode.Ok

    private def text: String = if (reply.nonEmpty) reply else s"hello from ${formatId(format)}"

    def requests: Seq[Recorded] = received.asScala.toSeq

    def handle(exchange: HttpExchange): Unit = {
      val body = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
      val headers = exchange.getRequestHeaders.asScala.map { case (k, v) =>
        k.toLowerCase -> v.asScala.mkString(",")
      }.toMap
      received.add(Recorded(exchange.getRequestURI.getPath, headers, body)): Unit
      val streaming = Try(ujson.read(body).obj.get("stream").exists(_.bool)).getOrElse(false)

      mode match {
        case Mode.Hang         => LocalProviderTestServer.holdOpen(exchange)
        case Mode.Fail(status) => sendError(exchange, status)
        case Mode.FailOnce(status) =>
          mode = Mode.Ok
          sendError(exchange, status)
        case Mode.ToolCall => LocalProviderTestServer.sendJsonResponse(exchange, 200, toolCall)
        case Mode.Ok if streaming =>
          if (format == Format.Ollama) {
            exchange.getResponseHeaders.add("Content-Type", "application/x-ndjson")
            val bytes = stream.getBytes(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.length.toLong)
            exchange.getResponseBody.write(bytes)
            exchange.getResponseBody.close()
          } else LocalProviderTestServer.sendSseResponse(exchange, stream)
        case Mode.Ok => LocalProviderTestServer.sendJsonResponse(exchange, 200, completion)
      }
    }

    private def sendError(exchange: HttpExchange, status: Int): Unit = {
      // Both vendor SDKs honour this header: retrying is left to ReliableClient.
      exchange.getResponseHeaders.add("x-should-retry", "false")
      if (status == 429) exchange.getResponseHeaders.add("Retry-After", RetryAfterSeconds.toString)
      LocalProviderTestServer.sendJsonResponse(exchange, status, errorBody(status))
    }

    private def errorBody(status: Int): String = format match {
      case Format.OpenAI =>
        ujson.Obj("error" -> ujson.Obj("message" -> s"mock error $status", "type" -> "mock_error")).render()
      case Format.Anthropic =>
        ujson
          .Obj("type" -> "error", "error" -> ujson.Obj("type" -> "mock_error", "message" -> s"mock error $status"))
          .render()
      case Format.Ollama => ujson.Obj("error" -> s"mock error $status").render()
    }

    private def completion: String = format match {
      case Format.OpenAI    => LocalProviderTestServer.openAICompletion(text, "gpt-4o-mini")
      case Format.Anthropic => LocalProviderTestServer.anthropicMessage(text, "claude-3-5-haiku-latest")
      case Format.Ollama =>
        ujson
          .Obj(
            "model"             -> "llama3",
            "message"           -> ujson.Obj("role" -> "assistant", "content" -> text),
            "done"              -> true,
            "prompt_eval_count" -> 10,
            "eval_count"        -> 5
          )
          .render()
    }

    /** The reply split in two, so a stream has more than one content chunk. */
    private def halves: Seq[String] = Seq(text.take(text.length / 2), text.drop(text.length / 2))

    private def stream: String = format match {
      case Format.OpenAI => LocalProviderTestServer.openAISseBody(halves, "gpt-4o-mini")
      case Format.Anthropic =>
        val start = ujson.Obj(
          "type" -> "message_start",
          "message" -> ujson.Obj(
            "id"            -> "msg_it",
            "type"          -> "message",
            "role"          -> "assistant",
            "content"       -> ujson.Arr(),
            "model"         -> "claude-3-5-haiku-latest",
            "stop_reason"   -> ujson.Null,
            "stop_sequence" -> ujson.Null,
            "usage"         -> ujson.Obj("input_tokens" -> 10, "output_tokens" -> 0)
          )
        )
        val deltas = halves.map(part =>
          "content_block_delta" -> ujson.Obj(
            "type"  -> "content_block_delta",
            "index" -> 0,
            "delta" -> ujson.Obj("type" -> "text_delta", "text" -> part)
          )
        )
        val events =
          Seq(
            "message_start" -> start,
            "content_block_start" -> ujson.Obj(
              "type"          -> "content_block_start",
              "index"         -> 0,
              "content_block" -> ujson.Obj("type" -> "text", "text" -> "")
            )
          ) ++ deltas ++ Seq(
            "content_block_stop" -> ujson.Obj("type" -> "content_block_stop", "index" -> 0),
            "message_delta" -> ujson.Obj(
              "type"  -> "message_delta",
              "delta" -> ujson.Obj("stop_reason" -> "end_turn", "stop_sequence" -> ujson.Null),
              // cumulative, as the Messages API sends it: input tokens too
              "usage" -> ujson.Obj("input_tokens" -> 10, "output_tokens" -> 5)
            ),
            "message_stop" -> ujson.Obj("type" -> "message_stop")
          )
        events.map { case (event, data) => s"event: $event\ndata: ${data.render()}\n\n" }.mkString
      case Format.Ollama =>
        val parts = halves.map(part =>
          ujson.Obj(
            "model"   -> "llama3",
            "message" -> ujson.Obj("role" -> "assistant", "content" -> part),
            "done"    -> false
          )
        )
        val done = ujson.Obj(
          "model"             -> "llama3",
          "message"           -> ujson.Obj("role" -> "assistant", "content" -> ""),
          "done"              -> true,
          "prompt_eval_count" -> 10,
          "eval_count"        -> 5
        )
        (parts :+ done).map(_.render()).mkString("", "\n", "\n")
    }

    private def toolCall: String = format match {
      case Format.OpenAI =>
        ujson
          .Obj(
            "id"      -> "chatcmpl-tool",
            "object"  -> "chat.completion",
            "created" -> 1700000000,
            "model"   -> "gpt-4o-mini",
            "choices" -> ujson.Arr(
              ujson.Obj(
                "index" -> 0,
                "message" -> ujson.Obj(
                  "role"    -> "assistant",
                  "content" -> ujson.Null,
                  "tool_calls" -> ujson.Arr(
                    ujson.Obj(
                      "id"       -> "call_it",
                      "type"     -> "function",
                      "function" -> ujson.Obj("name" -> "get_weather", "arguments" -> """{"city":"Paris"}""")
                    )
                  )
                ),
                "finish_reason" -> "tool_calls"
              )
            ),
            "usage" -> ujson.Obj("prompt_tokens" -> 10, "completion_tokens" -> 5, "total_tokens" -> 15)
          )
          .render()
      case Format.Anthropic =>
        ujson
          .Obj(
            "id"    -> "msg_tool",
            "type"  -> "message",
            "role"  -> "assistant",
            "model" -> "claude-3-5-haiku-latest",
            "content" -> ujson.Arr(
              ujson.Obj(
                "type"  -> "tool_use",
                "id"    -> "toolu_it",
                "name"  -> "get_weather",
                "input" -> ujson.Obj("city" -> "Paris")
              )
            ),
            "stop_reason"   -> "tool_use",
            "stop_sequence" -> ujson.Null,
            "usage"         -> ujson.Obj("input_tokens" -> 10, "output_tokens" -> 5)
          )
          .render()
      case Format.Ollama =>
        ujson
          .Obj(
            "model" -> "llama3",
            "message" -> ujson.Obj(
              "role"    -> "assistant",
              "content" -> "",
              "tool_calls" -> ujson.Arr(
                ujson.Obj("function" -> ujson.Obj("name" -> "get_weather", "arguments" -> ujson.Obj("city" -> "Paris")))
              )
            ),
            "done"              -> true,
            "prompt_eval_count" -> 10,
            "eval_count"        -> 5
          )
          .render()
    }
  }

  private def formatId(format: Format): String = format match {
    case Format.OpenAI    => "openai"
    case Format.Anthropic => "anthropic"
    case Format.Ollama    => "ollama"
  }
}
