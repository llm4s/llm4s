package org.llm4s.llmconnect.provider

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.{
  LLMClient,
  LLMConnect,
  LlmClientOptions,
  ProviderExchange,
  ProviderExchangeLogging,
  ProviderExchangeOutcome,
  ProviderExchangeSink
}
import org.llm4s.llmconnect.config.DeepSeekConfig
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer._
import org.llm4s.types.Result
import org.scalatest.OptionValues._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.ServerSocket
import java.nio.file.{ Files, Path }
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters._

/**
 * The snippets of `docs/guide/observability/provider-exchange-logging.md`, compiled and run as written,
 * through a real client against a local server (no network, no keys).
 *
 * If a snippet here stops compiling or an assertion fails, the guide is describing something that no
 * longer happens: change the guide and this spec together. The sink and file format claims that need no
 * client (naming, redaction, truncation, configuration) are in `ProviderExchangeLoggingGuideCoreSpec`.
 */
class ProviderExchangeLoggingGuideSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val apiKey = "guide-secret-key-123"

  private def config(baseUrl: String): DeepSeekConfig =
    DeepSeekConfig(
      apiKey = apiKey,
      model = "deepseek-chat",
      baseUrl = baseUrl,
      contextWindow = 128000,
      reserveCompletion = 8192
    )

  private def conversation(text: String): Conversation = Conversation(Seq(UserMessage(text)))

  // ---- "From configuration": the snippet compiles (it reads the application's own configuration,
  //      so it is not run here; the keys it reads are checked in the core spec)

  def fromConfiguration(): Result[LLMClient] =
    for {
      providerConfig <- Llm4sConfig.defaultProvider()
      registry       <- Llm4sConfig.modelRegistryService()
      given ModelRegistryService = registry
      exchangeLogging <- Llm4sConfig.exchangeLogging()
      client          <- LLMConnect.getClient(providerConfig, LlmClientOptions(exchangeLogging = exchangeLogging))
    } yield client

  // ---- "From code": a JSONL file for this run

  def jsonlLogging(dir: Path): Result[ProviderExchangeLogging] =
    ProviderExchangeSink.createRunScopedJsonl(dir).map(ProviderExchangeLogging.enabled)

  // ---- "Writing your own sink"

  final class MetadataOnlySink(out: String => Unit) extends ProviderExchangeSink:
    def record(exchange: ProviderExchange): Unit =
      out(s"${exchange.provider} ${exchange.model.getOrElse("-")} ${exchange.outcome} ${exchange.duration.toMillis}ms")

  final class ErrorsOnlySink(delegate: ProviderExchangeSink) extends ProviderExchangeSink:
    def record(exchange: ProviderExchange): Unit =
      if exchange.outcome == ProviderExchangeOutcome.Error then delegate.record(exchange)

  // ---- helpers

  private def collecting(): (ProviderExchangeSink, ListBuffer[ProviderExchange]) = {
    val recorded = ListBuffer.empty[ProviderExchange]
    val sink = new ProviderExchangeSink:
      def record(exchange: ProviderExchange): Unit = recorded += exchange
    (sink, recorded)
  }

  private def clientWith(baseUrl: String, sink: ProviderExchangeSink): LLMClient =
    LLMConnect
      .getClient(config(baseUrl), LlmClientOptions(exchangeLogging = ProviderExchangeLogging.enabled(sink)))
      .getOrElse(fail("could not build the client"))

  private def freeLoopbackUrl(): String = {
    val socket = new ServerSocket(0)
    val port   = socket.getLocalPort
    socket.close()
    s"http://127.0.0.1:$port"
  }

  // ---- what is recorded

  "exchange logging" should "be off unless the options carry a sink" in {
    LlmClientOptions.default.exchangeLogging shouldBe ProviderExchangeLogging.Disabled
  }

  it should "record one exchange for a call, with the fields the guide lists" in
    withServer("/chat/completions")(ex => sendJsonResponse(ex, 200, openAICompletion("Hello there", "deepseek-chat"))) {
      baseUrl =>
        val (sink, recorded) = collecting()
        val before           = Instant.now()
        val result           = clientWith(baseUrl, sink).complete(conversation("What is Scala?"), CompletionOptions())

        result.isRight shouldBe true
        recorded should have size 1
        val exchange = recorded.head
        noException should be thrownBy UUID.fromString(exchange.exchangeId)
        exchange.provider shouldBe "deepseek"
        exchange.model shouldBe Some("deepseek-chat")
        exchange.requestId shouldBe None
        exchange.correlationId shouldBe None
        exchange.outcome shouldBe ProviderExchangeOutcome.Success
        exchange.requestBody should include("What is Scala?")
        exchange.responseBody.value should include("Hello there")
        exchange.errorMessage shouldBe None
        exchange.startedAt.isBefore(before) shouldBe false
        exchange.completedAt.isBefore(exchange.startedAt) shouldBe false
        exchange.duration.toMillis should be >= 0L
    }

  it should "record the configured model, not the model the response reports" in
    withServer("/chat/completions")(ex => sendJsonResponse(ex, 200, openAICompletion("ok", "another-model"))) {
      baseUrl =>
        val (sink, recorded) = collecting()
        val completion =
          clientWith(baseUrl, sink).complete(conversation("hi"), CompletionOptions()).getOrElse(fail("call failed"))

        completion.model shouldBe "another-model"
        recorded.head.model shouldBe Some("deepseek-chat")
    }

  it should "record the JSON body that was sent, and not the headers, so the API key is not in it" in {
    val authorization = new AtomicReference[String]("")
    withServer("/chat/completions") { ex =>
      authorization.set(ex.getRequestHeaders.getFirst("Authorization"))
      sendJsonResponse(ex, 200, openAICompletion("ok", "deepseek-chat"))
    } { baseUrl =>
      val (sink, recorded) = collecting()
      clientWith(baseUrl, sink).complete(conversation("hi"), CompletionOptions()).isRight shouldBe true

      // the key really was sent, as a header...
      authorization.get should include(apiKey)
      // ...and the recorded exchange carries none of it
      val exchange = recorded.head
      (exchange.requestBody should not).include(apiKey)
      (exchange.responseBody.value should not).include(apiKey)
      ujson.read(exchange.requestBody)("model").str shouldBe "deepseek-chat"
    }
  }

  it should "record the raw server-sent events of a streamed call" in
    withServer("/chat/completions")(ex => sendSseResponse(ex, openAISseBody(Seq("Hel", "lo"), "deepseek-chat"))) {
      baseUrl =>
        val (sink, recorded) = collecting()
        clientWith(baseUrl, sink)
          .streamComplete(conversation("stream it"), CompletionOptions(), _ => ())
          .isRight shouldBe true

        recorded should have size 1
        ujson.read(recorded.head.requestBody)("stream").bool shouldBe true
        recorded.head.responseBody.value should startWith("data:")
        recorded.head.responseBody.value should include("[DONE]")
        recorded.head.outcome shouldBe ProviderExchangeOutcome.Success
    }

  it should "record an HTTP error as an Error, with the error body and the error's message" in
    withServer("/chat/completions")(ex => sendJsonResponse(ex, 401, """{"error":"Unauthorized"}""")) { baseUrl =>
      val (sink, recorded) = collecting()
      clientWith(baseUrl, sink).complete(conversation("will fail"), CompletionOptions()).isLeft shouldBe true

      recorded should have size 1
      recorded.head.outcome shouldBe ProviderExchangeOutcome.Error
      recorded.head.responseBody shouldBe Some("""{"error":"Unauthorized"}""")
      recorded.head.errorMessage.value should include("Unauthorized")
    }

  it should "record a call that got no response as an Error with no response body" in {
    val (sink, recorded) = collecting()
    clientWith(freeLoopbackUrl(), sink).complete(conversation("nobody home"), CompletionOptions()).isLeft shouldBe true

    recorded should have size 1
    recorded.head.outcome shouldBe ProviderExchangeOutcome.Error
    recorded.head.responseBody shouldBe None
    recorded.head.errorMessage should not be empty
  }

  // ---- a custom sink gets the exchange untouched

  it should "hand a custom sink the whole body, neither redacted nor truncated" in
    withServer("/chat/completions")(ex => sendJsonResponse(ex, 200, openAICompletion("ok", "deepseek-chat"))) {
      baseUrl =>
        val (sink, recorded) = collecting()
        val prompt           = "Authorization: Bearer abc123def456 " + ("x" * 3000)
        clientWith(baseUrl, sink).complete(conversation(prompt), CompletionOptions()).isRight shouldBe true

        recorded.head.requestBody should include("Bearer abc123def456")
        recorded.head.requestBody.length should be > 3000
    }

  it should "call the sink on the thread that made the call" in
    withServer("/chat/completions")(ex => sendJsonResponse(ex, 200, openAICompletion("ok", "deepseek-chat"))) {
      baseUrl =>
        val seen = new AtomicReference[Thread]()
        val sink = new ProviderExchangeSink:
          def record(exchange: ProviderExchange): Unit = seen.set(Thread.currentThread())

        clientWith(baseUrl, sink).complete(conversation("hi"), CompletionOptions()).isRight shouldBe true

        seen.get shouldBe Thread.currentThread()
    }

  it should "carry on when the sink throws: the call still succeeds" in
    withServer("/chat/completions")(ex => sendJsonResponse(ex, 200, openAICompletion("fine", "deepseek-chat"))) {
      baseUrl =>
        val calls = new java.util.concurrent.atomic.AtomicInteger(0)
        val sink = new ProviderExchangeSink:
          def record(exchange: ProviderExchange): Unit =
            calls.incrementAndGet()
            throw new IllegalStateException("the sink is down")

        val result = clientWith(baseUrl, sink).complete(conversation("hi"), CompletionOptions())

        calls.get shouldBe 1
        result.map(_.content) shouldBe Right("fine")
    }

  // ---- the file the JSONL sink writes

  it should "write one JSON line per exchange to a file in the directory, in the documented shape" in {
    val dir = Files.createTempDirectory("exchange-guide")
    val sink = ProviderExchangeSink
      .createRunScopedJsonl(dir)
      .getOrElse(fail("could not create the sink"))
    withServer("/chat/completions") { ex =>
      sendJsonResponse(ex, 200, openAICompletion("Hello there", "deepseek-chat"))
    } { baseUrl =>
      val client = clientWith(baseUrl, sink)
      client.complete(conversation("What is Scala?"), CompletionOptions()).isRight shouldBe true
    }
    withServer("/chat/completions")(ex => sendJsonResponse(ex, 401, """{"error":"Unauthorized"}""")) { baseUrl =>
      clientWith(baseUrl, sink).complete(conversation("will fail"), CompletionOptions()).isLeft shouldBe true
    }

    val lines = Files.readAllLines(sink.path).asScala.toList
    lines should have size 2
    val rows = lines.map(line => ujson.read(line).obj)

    val expectedKeys = List(
      "exchange_id",
      "provider",
      "model",
      "request_id",
      "correlation_id",
      "started_at",
      "completed_at",
      "duration_ms",
      "outcome",
      "request_body",
      "response_body",
      "error_message"
    )
    rows.foreach(_.keys.toList shouldBe expectedKeys)

    rows.map(_("outcome").str) shouldBe List("Success", "Error")
    // no request or correlation id is ever set, and an absent value is an empty string
    rows.foreach(_("request_id").str shouldBe "")
    rows.foreach(_("correlation_id").str shouldBe "")
    rows.head("error_message").str shouldBe ""
    rows(1)("error_message").str should include("Unauthorized")
    // durations are written as JSON strings, not numbers
    rows.foreach(row => row("duration_ms") shouldBe a[ujson.Str])
    rows.foreach(row => row("duration_ms").str.toLong should be >= 0L)
    rows.foreach(row => Instant.parse(row("started_at").str).isAfter(Instant.EPOCH) shouldBe true)
    (Files.readString(sink.path) should not).include(apiKey)
  }

  it should "write a line of the shape the guide shows" in {
    // the line in the guide, copied from a real run (the ids and times differ from run to run)
    val example =
      """{"exchange_id":"bd1e7d34-3c95-44e5-a9be-428bf3ca85f1","provider":"deepseek","model":"deepseek-chat","request_id":"","correlation_id":"","started_at":"2026-10-07T10:27:19.668236Z","completed_at":"2026-10-07T10:27:19.676649Z","duration_ms":"8","outcome":"Error","request_body":"{\"model\":\"deepseek-chat\",\"messages\":[{\"role\":\"user\",\"content\":\"will fail\"}],\"temperature\":0.7,\"top_p\":1}","response_body":"{\"error\":\"Unauthorized\"}","error_message":"Authentication failed for deepseek: Unauthorized"}"""

    val dir  = Files.createTempDirectory("exchange-guide-shape")
    val sink = ProviderExchangeSink.createRunScopedJsonl(dir).getOrElse(fail("could not create the sink"))
    withServer("/chat/completions")(ex => sendJsonResponse(ex, 401, """{"error":"Unauthorized"}""")) { baseUrl =>
      clientWith(baseUrl, sink).complete(conversation("will fail"), CompletionOptions()).isLeft shouldBe true
    }
    val produced = ujson.read(Files.readAllLines(sink.path).get(0)).obj
    val shown    = ujson.read(example).obj

    produced.keys.toList shouldBe shown.keys.toList
    // everything except the ids and times is the same text
    List("provider", "model", "request_id", "correlation_id", "outcome", "request_body", "response_body").foreach {
      key => produced(key).str shouldBe shown(key).str
    }
    produced("error_message").str shouldBe shown("error_message").str
  }

  // ---- writing a sink: the two examples in the guide

  "the guide's MetadataOnlySink" should "see the provider, model, outcome and timing, and never a body" in
    withServer("/chat/completions")(ex => sendJsonResponse(ex, 200, openAICompletion("ok", "deepseek-chat"))) {
      baseUrl =>
        val lines = ListBuffer.empty[String]
        clientWith(baseUrl, new MetadataOnlySink(line => lines += line))
          .complete(conversation("a private prompt"), CompletionOptions())
          .isRight shouldBe true

        lines should have size 1
        (lines.head should fullyMatch).regex("deepseek deepseek-chat Success \\d+ms")
        (lines.head should not).include("private")
    }

  "the guide's ErrorsOnlySink" should "keep an exchange only when the call failed" in {
    val (inner, kept) = collecting()
    val sink          = new ErrorsOnlySink(inner)
    withServer("/chat/completions")(ex => sendJsonResponse(ex, 200, openAICompletion("ok", "deepseek-chat"))) {
      baseUrl => clientWith(baseUrl, sink).complete(conversation("fine"), CompletionOptions()).isRight shouldBe true
    }
    kept shouldBe empty

    withServer("/chat/completions")(ex => sendJsonResponse(ex, 401, """{"error":"Unauthorized"}""")) { baseUrl =>
      clientWith(baseUrl, sink).complete(conversation("broken"), CompletionOptions()).isLeft shouldBe true
    }
    kept should have size 1
    kept.head.outcome shouldBe ProviderExchangeOutcome.Error
  }

  "the guide's file snippet" should "give a logging setting that writes into the directory" in {
    val dir = Files.createTempDirectory("exchange-guide-code")

    jsonlLogging(dir) match {
      case Right(ProviderExchangeLogging.Enabled(sink: org.llm4s.llmconnect.JsonlProviderExchangeSink)) =>
        sink.path.getParent.toRealPath() shouldBe dir.toRealPath()
        (sink.path.getFileName.toString should fullyMatch).regex("provider-exchanges-.*\\.jsonl")
      case other => fail(s"expected an enabled JSONL setting, got $other")
    }
  }
}
