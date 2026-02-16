package org.llm4s.llmconnect

import org.llm4s.error.{ AuthenticationError, RateLimitError, ServiceError, SimpleError, TimeoutError, ValidationError }
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

/**
 * Deterministic unit tests for [[LLMClientRetry]].
 * Uses a stub LLMClient; no real sleep or network.
 */
class LLMClientRetrySpec extends AnyFlatSpec with Matchers {

  private val stubCompletion = Completion(
    id = "test-1",
    created = 0L,
    content = "ok",
    model = "test",
    message = AssistantMessage("ok")
  )

  private val stubChunk = StreamedChunk(
    id = "c1",
    content = Some("x"),
    toolCall = None,
    finishReason = None,
    thinkingDelta = None
  )

  /** Stub client that returns a sequence of results for complete() and streamComplete(). */
  private def stubClient(
    completeResults: Seq[Result[Completion]],
    streamBehaviors: Seq[(StreamedChunk => Unit) => Result[Completion]]
  ): LLMClient = new LLMClient {
    private var completeCalls = 0
    private var streamCalls   = 0
    override def complete(
      conversation: Conversation,
      options: CompletionOptions
    ): Result[Completion] = {
      val i = completeCalls
      completeCalls += 1
      completeResults(i)
    }
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = {
      val i = streamCalls
      streamCalls += 1
      streamBehaviors(i)(onChunk)
    }
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  private val conv = Conversation(Seq(UserMessage("hello")))

  // ---- completeWithRetry ----

  "LLMClientRetry.completeWithRetry" should "succeed on first attempt" in {
    val client = stubClient(
      completeResults = Seq(Right(stubCompletion)),
      streamBehaviors = Seq.empty
    )
    val result = LLMClientRetry.completeWithRetry(
      client,
      conv,
      maxAttempts = 3,
      baseDelay = 1.milli
    )
    result shouldBe Right(stubCompletion)
  }

  it should "retry on recoverable error then succeed" in {
    val client = stubClient(
      completeResults = Seq(
        Left(RateLimitError("p")),
        Right(stubCompletion)
      ),
      streamBehaviors = Seq.empty
    )
    val result = LLMClientRetry.completeWithRetry(
      client,
      conv,
      maxAttempts = 3,
      baseDelay = 1.milli
    )
    result shouldBe Right(stubCompletion)
  }

  it should "fail immediately on non-recoverable error" in {
    val client = stubClient(
      completeResults = Seq(Left(AuthenticationError("p", "invalid"))),
      streamBehaviors = Seq.empty
    )
    val result = LLMClientRetry.completeWithRetry(
      client,
      conv,
      maxAttempts = 3,
      baseDelay = 1.milli
    )
    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[AuthenticationError]
  }

  it should "retry on ServiceError 5xx then succeed" in {
    val client = stubClient(
      completeResults = Seq(
        Left(ServiceError(503, "p", "Service Unavailable")),
        Right(stubCompletion)
      ),
      streamBehaviors = Seq.empty
    )
    val result = LLMClientRetry.completeWithRetry(
      client,
      conv,
      maxAttempts = 3,
      baseDelay = 1.milli
    )
    result shouldBe Right(stubCompletion)
  }

  it should "fail immediately on ServiceError 4xx (e.g. 400)" in {
    val err = ServiceError(400, "p", "Bad request")
    val client = stubClient(
      completeResults = Seq(Left(err)),
      streamBehaviors = Seq.empty
    )
    val result = LLMClientRetry.completeWithRetry(
      client,
      conv,
      maxAttempts = 3,
      baseDelay = 1.milli
    )
    result.isLeft shouldBe true
    result.left.toOption.get shouldBe err
  }

  it should "retry on ServiceError 429 then succeed" in {
    val client = stubClient(
      completeResults = Seq(
        Left(ServiceError(429, "p", "Rate limited")),
        Right(stubCompletion)
      ),
      streamBehaviors = Seq.empty
    )
    val result = LLMClientRetry.completeWithRetry(
      client,
      conv,
      maxAttempts = 3,
      baseDelay = 1.milli
    )
    result shouldBe Right(stubCompletion)
  }

  it should "retry on TimeoutError (fallback to backoff) then succeed" in {
    val client = stubClient(
      completeResults = Seq(
        Left(TimeoutError("timeout", 1.second, "api-call")),
        Right(stubCompletion)
      ),
      streamBehaviors = Seq.empty
    )
    val result = LLMClientRetry.completeWithRetry(
      client,
      conv,
      maxAttempts = 3,
      baseDelay = 1.milli
    )
    result shouldBe Right(stubCompletion)
  }

  it should "return last error when retries exhausted" in {
    val err = RateLimitError("p")
    val client = stubClient(
      completeResults = Seq(Left(err), Left(err), Left(err)),
      streamBehaviors = Seq.empty
    )
    val result = LLMClientRetry.completeWithRetry(
      client,
      conv,
      maxAttempts = 3,
      baseDelay = 1.milli
    )
    result.isLeft shouldBe true
    result.left.toOption.get shouldBe err
  }

  it should "return ValidationError when maxAttempts <= 0" in {
    val client = stubClient(Seq(Right(stubCompletion)), Seq.empty)
    val result = LLMClientRetry.completeWithRetry(
      client,
      conv,
      maxAttempts = 0,
      baseDelay = 1.second
    )
    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[ValidationError]
    result.left.toOption.get.asInstanceOf[ValidationError].field shouldBe "maxAttempts"
  }

  it should "return ValidationError when baseDelay is non-positive" in {
    val client = stubClient(Seq(Right(stubCompletion)), Seq.empty)
    val result = LLMClientRetry.completeWithRetry(
      client,
      conv,
      maxAttempts = 3,
      baseDelay = 0.millis
    )
    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[ValidationError]
    result.left.toOption.get.asInstanceOf[ValidationError].field shouldBe "baseDelay"
  }

  it should "return SimpleError when sleep is interrupted" in {
    val client = stubClient(
      completeResults = Seq(Left(RateLimitError("p")), Right(stubCompletion)),
      streamBehaviors = Seq.empty
    )
    var result: Result[Completion] = Left(SimpleError("placeholder"))
    val t = new Thread(() =>
      result = LLMClientRetry.completeWithRetry(
        client,
        conv,
        maxAttempts = 3,
        baseDelay = 10.seconds
      )
    )
    t.start()
    Thread.sleep(50)
    t.interrupt()
    t.join(2000)
    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[SimpleError]
    val msg = result.left.toOption.get.asInstanceOf[SimpleError].message
    msg should include("interrupted")
    msg should include("attempt 1")
    msg should include("RateLimitError")
  }

  // ---- streamCompleteWithRetry ----

  "LLMClientRetry.streamCompleteWithRetry" should "retry when failure happens before any chunk" in {
    val client = stubClient(
      completeResults = Seq.empty,
      streamBehaviors = Seq(
        (_: StreamedChunk => Unit) => Left(RateLimitError("p")),
        (onChunk: StreamedChunk => Unit) => {
          onChunk(stubChunk)
          Right(stubCompletion)
        }
      )
    )
    var chunkCount = 0
    val result = LLMClientRetry.streamCompleteWithRetry(
      client,
      conv,
      maxAttempts = 3,
      baseDelay = 1.milli
    )(_ => chunkCount += 1)
    result shouldBe Right(stubCompletion)
    chunkCount shouldBe 1
  }

  it should "not retry after a chunk has been emitted" in {
    val err = RateLimitError("p")
    val client = stubClient(
      completeResults = Seq.empty,
      streamBehaviors = Seq { (onChunk: StreamedChunk => Unit) =>
        onChunk(stubChunk)
        Left(err)
      }
    )
    var chunkCount = 0
    val result = LLMClientRetry.streamCompleteWithRetry(
      client,
      conv,
      maxAttempts = 3,
      baseDelay = 1.milli
    )(_ => chunkCount += 1)
    result.isLeft shouldBe true
    result.left.toOption.get shouldBe err
    chunkCount shouldBe 1
  }

  it should "return ValidationError when maxAttempts <= 0" in {
    val client = stubClient(Seq.empty, Seq.empty)
    val result = LLMClientRetry.streamCompleteWithRetry(
      client,
      conv,
      maxAttempts = 0,
      baseDelay = 1.second
    )(_ => ())
    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[ValidationError]
    result.left.toOption.get.asInstanceOf[ValidationError].field shouldBe "maxAttempts"
  }

  it should "return ValidationError when baseDelay is non-positive" in {
    val client = stubClient(Seq.empty, Seq.empty)
    val result = LLMClientRetry.streamCompleteWithRetry(
      client,
      conv,
      maxAttempts = 3,
      baseDelay = 0.millis
    )(_ => ())
    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[ValidationError]
    result.left.toOption.get.asInstanceOf[ValidationError].field shouldBe "baseDelay"
  }

  it should "fail immediately on non-recoverable ServiceError 400 in stream" in {
    val err = ServiceError(400, "p", "Bad request")
    val client = stubClient(
      completeResults = Seq.empty,
      streamBehaviors = Seq((_: StreamedChunk => Unit) => Left(err))
    )
    val result = LLMClientRetry.streamCompleteWithRetry(
      client,
      conv,
      maxAttempts = 3,
      baseDelay = 1.milli
    )(_ => ())
    result.isLeft shouldBe true
    result.left.toOption.get shouldBe err
  }
}
