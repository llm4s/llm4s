package org.llm4s.trace

import org.llm4s.error.{ RateLimitError, UnknownError }
import org.llm4s.llmconnect.{ EmbeddingClient, LLMClient }
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.model.*
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.model.{ ModelRegistryService, ModelRegistryTestSupport }
import org.llm4s.testutil.FixtureChatConfig
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.*

/** `TracingLLMClient` and `EmbeddingClient` trace each call with the details GenAI tracing needs (#2275). */
class TracingLLMClientSpec extends AnyWordSpec with Matchers {

  private given ModelRegistryService = ModelRegistryTestSupport.defaultService()

  private class Recording extends Tracing {
    val events                                                                       = ArrayBuffer.empty[TraceEvent]
    def traceEvent(event: TraceEvent): Result[Unit]                                  = { events += event; Right(()) }
    def traceToolCall(toolName: String, input: String, output: String): Result[Unit] = Right(())
    def traceError(error: Throwable, context: String): Result[Unit]                  = Right(())
    def traceCompletion(completion: Completion, model: String): Result[Unit]         = Right(())
    def traceTokenUsage(usage: TokenUsage, model: String, operation: String): Result[Unit] = Right(())
  }

  private val completion = Completion(
    id = "c-1",
    created = Instant.EPOCH,
    content = "answer",
    model = "model-2026",
    message = AssistantMessage("answer"),
    usage = Some(TokenUsage(3, 2, 5))
  )

  private class Stub(result: Result[Completion]) extends LLMClient {
    var closed                                                                               = false
    def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = result
    def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = {
      onChunk(StreamedChunk("c-1", Some("answer")))
      onChunk(StreamedChunk("c-1", None, finishReason = Some("length")))
      result
    }
    def getContextWindow(): Int                 = 1000
    def getReserveCompletion(): Int             = 100
    override def validate(): Result[Unit]       = Left(UnknownError("invalid", new RuntimeException("x")))
    override def close(): Unit                  = closed = true
    override def providerId: Option[ProviderId] = Some(ProviderId("OpenAI"))
  }

  private val conversation = Conversation(Seq(UserMessage("question")))
  private val options      = CompletionOptions().withMaxTokens(10)
  private val config       = FixtureChatConfig("key", "model", "https://api.example.com:8443/v1")

  "TracingLLMClient" should {

    "trace a completion with the call's details" in {
      val tracing = new Recording
      TracingLLMClient(new Stub(Right(completion)), tracing, config).complete(conversation, options) shouldBe Right(
        completion
      )

      val e = tracing.events.loneElement.asInstanceOf[TraceEvent.CompletionReceived]
      e.id shouldBe "c-1"
      e.model shouldBe "model"
      e.responseModel shouldBe Some("model-2026")
      e.provider shouldBe Some("openai")
      e.serverAddress shouldBe Some("api.example.com")
      e.streamed shouldBe false
      e.duration should not be empty
      e.usage shouldBe Some(TokenUsage(3, 2, 5))
      e.options shouldBe Some(options)
      e.input shouldBe conversation.messages
      e.finishReasons shouldBe empty
      e.content shouldBe "answer"
    }

    "trace a streamed completion with its finish reason, passing every chunk on" in {
      val tracing = new Recording
      val chunks  = ArrayBuffer.empty[StreamedChunk]
      TracingLLMClient(new Stub(Right(completion)), tracing).streamComplete(conversation, options, chunks += _)

      chunks should have size 2
      val e = tracing.events.loneElement.asInstanceOf[TraceEvent.CompletionReceived]
      e.streamed shouldBe true
      e.finishReasons shouldBe Seq("length")
      e.model shouldBe "model-2026"
      e.serverAddress shouldBe None
    }

    "trace a failed call as an error naming the operation, model, provider and error type" in {
      val tracing = new Recording
      TracingLLMClient(new Stub(Left(RateLimitError("openai"))), tracing, config)
        .complete(conversation, options)
        .isLeft shouldBe true

      val e = tracing.events.loneElement.asInstanceOf[TraceEvent.ErrorOccurred]
      e.operation shouldBe Some("chat")
      e.model shouldBe Some("model")
      e.provider shouldBe Some("openai")
      e.errorType shouldBe Some("RateLimitError")
      e.duration should not be empty
      e.toJson("error_type").str shouldBe "RateLimitError"
      e.toJson("operation").str shouldBe "chat"
    }

    "return the call's result when tracing fails" in {
      val failing = new Recording {
        override def traceEvent(event: TraceEvent): Result[Unit] = throw new IllegalStateException("down")
      }
      TracingLLMClient(new Stub(Right(completion)), failing).complete(conversation, options) shouldBe Right(completion)
    }

    "forward the rest to the wrapped client" in {
      val stub   = new Stub(Right(completion))
      val client = TracingLLMClient(stub, new Recording)
      client.getContextWindow() shouldBe 1000
      client.getReserveCompletion() shouldBe 100
      client.validate().isLeft shouldBe true
      client.providerId shouldBe Some(ProviderId("openai"))
      client.close()
      stub.closed shouldBe true
    }

    "be what ReliableClient and CachingLLMClient forward as their provider" in {
      val stub = new Stub(Right(completion))
      new org.llm4s.reliability.ReliableClient(
        stub,
        "openai",
        org.llm4s.reliability.ReliabilityConfig.default
      ).providerId shouldBe Some(
        ProviderId("openai")
      )
      val embeddings = new EmbeddingClient(new EmbeddingProvider {
        def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = Right(EmbeddingResponse(Seq(Seq(0.1))))
      })
      val cacheConfig =
        org.llm4s.llmconnect.caching.CacheConfig.create(0.9, 1.minute).fold(e => fail(e.message), identity)
      new org.llm4s.llmconnect.caching.CachingLLMClient(
        stub,
        embeddings,
        EmbeddingModelConfig("embed-1", 1),
        cacheConfig,
        new Recording
      ).providerId shouldBe Some(ProviderId("openai"))
    }

    "take no server address from a config without a parsable endpoint" in {
      val tracing = new Recording
      TracingLLMClient(new Stub(Right(completion)), tracing, config.copy(baseUrl = "not a url"))
        .complete(conversation, options)
      tracing.events.loneElement.asInstanceOf[TraceEvent.CompletionReceived].serverAddress shouldBe None
    }
  }

  "EmbeddingClient" should {

    val model = EmbeddingModelConfig("embed-1", 3)

    def provider(result: Result[EmbeddingResponse]): EmbeddingProvider = new EmbeddingProvider {
      def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = result
    }

    "trace a call's usage with its provider and duration" in {
      val tracing = new Recording
      new EmbeddingClient(
        provider(Right(EmbeddingResponse(Seq(Seq(0.1)), usage = Some(EmbeddingUsage(4, 4))))),
        providerId = Some(ProviderId("voyage"))
      ).withOperation("query")
        .withTracing(tracing)
        .embed(EmbeddingRequest(input = Seq("text"), model = model))

      val e = tracing.events.collect { case e: TraceEvent.EmbeddingUsageRecorded => e }.loneElement
      e.provider shouldBe Some("voyage")
      e.operation shouldBe "query"
      e.duration should not be empty
      e.toJson("provider").str shouldBe "voyage"
    }

    "trace a failed call as an embeddings error" in {
      val tracing = new Recording
      new EmbeddingClient(provider(Left(RateLimitError("voyage"))), providerId = Some(ProviderId("voyage")))
        .withTracing(tracing)
        .embed(EmbeddingRequest(input = Seq("text"), model = model))

      val e = tracing.events.loneElement.asInstanceOf[TraceEvent.ErrorOccurred]
      e.operation shouldBe Some("embeddings")
      e.model shouldBe Some("embed-1")
      e.provider shouldBe Some("voyage")
      e.errorType shouldBe Some("RateLimitError")
    }
  }

  "The call fields of trace events" should {

    "be set by their setters and written by toJson" in {
      val received = TraceEvent
        .CompletionReceived("id", "m", 0, "c", Instant.EPOCH)
        .withProvider("openai")
        .withServerAddress("host")
        .withStreamed(true)
        .withDuration(2.seconds)
        .withResponseModel("m-1")
        .withFinishReasons(Seq("stop"))
        .withUsage(TokenUsage(1, 2, 3))
        .withOptions(options)
        .withInput(conversation.messages)
      val json = received.toJson
      json("provider").str shouldBe "openai"
      json("server_address").str shouldBe "host"
      json("streamed").bool shouldBe true
      json("duration_ms").num shouldBe 2000
      json("response_model").str shouldBe "m-1"
      json("finish_reasons").arr.map(_.str) shouldBe Seq("stop")
      json("completion_tokens").num shouldBe 2
      received.input shouldBe conversation.messages
      received
        .withProvider(None)
        .withServerAddress(None)
        .withDuration(None)
        .withResponseModel(None)
        .withUsage(None)
        .withOptions(None)
        .toJson
        .obj
        .keySet should contain noneOf ("provider", "server_address", "duration_ms", "response_model", "prompt_tokens")

      val error = TraceEvent
        .ErrorOccurred(new RuntimeException("x"), "ctx", Instant.EPOCH, None)
        .withOperation("chat")
        .withModel("m")
        .withProvider("p")
        .withErrorType("E")
        .withDuration(1.second)
      error.toJson("model").str shouldBe "m"
      error.toJson("provider").str shouldBe "p"
      error.toJson("duration_ms").num shouldBe 1000
      error
        .withOperation(None)
        .withModel(None)
        .withProvider(None)
        .withErrorType(None)
        .withDuration(None)
        .operation shouldBe None

      val embedding = TraceEvent
        .EmbeddingUsageRecorded(EmbeddingUsage(1, 1), "e", "op", 1, Instant.EPOCH)
        .withProvider("p")
        .withDuration(3.millis)
      embedding.toJson("duration_ms").num shouldBe 3
      embedding
        .withProvider(None)
        .withDuration(None)
        .toJson
        .obj
        .keySet should contain noneOf ("provider", "duration_ms")

      val ended = TraceEvent
        .AgentRunEnded("t", "r", "a", "completed", Seq.empty, UsageSummary(), Instant.EPOCH, None, None, None)
        .withProvider("anthropic")
      ended.toJson("provider").str shouldBe "anthropic"
      ended.withProvider(None).provider shouldBe None
    }
  }

  extension [A](as: collection.Seq[A])
    private def loneElement: A = {
      as should have size 1
      as.head
    }
}
