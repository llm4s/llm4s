package org.llm4s.llmconnect

import org.llm4s.error.{ CancelledError, NetworkError }
import org.llm4s.llmconnect.model.*
import org.llm4s.metrics.MetricsCollector
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class BaseLifecycleCancellationSpec extends AnyFlatSpec with Matchers {

  /** A client whose every call runs `behaviour`. */
  final private class Stub(behaviour: () => Result[Completion]) extends BaseLifecycleLLMClient {
    protected def clientDescription = "stub client"
    protected def providerName      = "stub"
    protected def modelName         = "stub-model"
    protected def metrics           = MetricsCollector.noop
    def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      completeWithMetrics(behaviour())
    def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = completeWithMetrics(behaviour())
    def getContextWindow(): Int     = 1000
    def getReserveCompletion(): Int = 100
  }

  private val conversation = Conversation(Seq(UserMessage("hi")))

  "completeWithMetrics" should "return a thrown InterruptedException as CancelledError, keeping the flag" in {
    val result  = new Stub(() => throw new InterruptedException).complete(conversation, CompletionOptions())
    val flagged = Thread.interrupted()
    flagged shouldBe true
    result.left.toOption.get shouldBe a[CancelledError]
  }

  it should "report any failure that ends while interrupted as a cancellation" in {
    val stub = new Stub({ () =>
      Thread.currentThread().interrupt()
      Left(NetworkError("socket closed", None, "x"))
    })
    val result  = stub.streamComplete(conversation, CompletionOptions(), _ => ())
    val flagged = Thread.interrupted()
    flagged shouldBe true
    result.left.toOption.get shouldBe a[CancelledError]
  }

  it should "cancel immediately, without calling the provider, when called with the flag already set" in {
    val calls = new java.util.concurrent.atomic.AtomicInteger(0)
    // A provider that ignores the flag (an SDK on a platform thread) would send a paid request
    val stub = new Stub({ () =>
      calls.incrementAndGet()
      Right(
        Completion(
          id = "id",
          created = 0L,
          content = "answer",
          model = "stub-model",
          message = AssistantMessage("answer")
        )
      )
    })
    Thread.currentThread().interrupt()
    val result  = stub.complete(conversation, CompletionOptions())
    val flagged = Thread.interrupted()
    flagged shouldBe true
    result.left.toOption.get shouldBe a[CancelledError]
    calls.get() shouldBe 0
  }

  it should "leave uninterrupted failures alone" in {
    val failure = NetworkError("reset", None, "x")
    new Stub(() => Left(failure)).complete(conversation, CompletionOptions()) shouldBe Left(failure)
  }
}
