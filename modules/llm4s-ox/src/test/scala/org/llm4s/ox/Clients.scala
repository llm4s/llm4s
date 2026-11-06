package org.llm4s.ox

import org.llm4s.error.{ CancelledError, LLMError, SimpleError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation, StreamedChunk, UserMessage }
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.types.Result

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.{ AtomicInteger, AtomicReference }
import scala.util.Try

/** Test clients for the specs of this module. Every wait is bounded, so a hang fails a case instead of the build. */
object Clients {

  val conversation: Conversation = Conversation(Seq(UserMessage("hello")))

  /** How long a latch is awaited before a hang counts as a failure. */
  val DeadlineSeconds: Long = 30L

  /**
   * Delegates to `delegate` and records what its `streamComplete` and `complete` returned, an interrupt counting as
   * `Left(CancelledError)`, and the thread that ran the call. `ended` opens when the call has returned.
   */
  final class Recording(delegate: LLMClient) extends LLMClient {
    val outcome: AtomicReference[Option[Result[Completion]]] = new AtomicReference(None)
    val thread: AtomicReference[Option[Thread]]              = new AtomicReference(None)
    val ended: CountDownLatch                                = new CountDownLatch(1)

    private def recorded(call: => Result[Completion]): Result[Completion] = {
      thread.set(Some(Thread.currentThread()))
      val result = CancelledError.attempt("recording")(call)
      outcome.set(Some(result))
      ended.countDown()
      result
    }

    def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      recorded(delegate.complete(conversation, options))
    def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] =
      recorded(delegate.streamComplete(conversation, options, onChunk))
    def getContextWindow(): Int     = delegate.getContextWindow()
    def getReserveCompletion(): Int = delegate.getReserveCompletion()
  }

  /**
   * Streams `first`, then parks until its thread is interrupted, as an llm4s provider waiting on the network does.
   * `complete` parks the same way. `started` opens when a call begins.
   */
  final class Parking(first: Seq[StreamedChunk] = Nil) extends LLMClient {
    val started: CountDownLatch = new CountDownLatch(1)

    private def park(): Result[Completion] = {
      started.countDown()
      CancelledError
        .catchInterrupt(new CountDownLatch(1).await(DeadlineSeconds * 4, TimeUnit.SECONDS))
        .left
        .map(e => CancelledError("parked", Some(e)))
        .flatMap(_ => Left(SimpleError("the parked call was never interrupted")))
    }

    def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = park()
    def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = {
      first.foreach(onChunk)
      park()
    }
    def getContextWindow(): Int     = 8192
    def getReserveCompletion(): Int = 512
  }

  /** Answers every call with `answer`, after `delay` milliseconds; counts the calls running at once. */
  final class Timed(answer: Conversation => Result[Completion], delayMillis: Long) extends LLMClient {
    val active: AtomicInteger  = new AtomicInteger(0)
    val maxSeen: AtomicInteger = new AtomicInteger(0)

    def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      val now = active.incrementAndGet()
      maxSeen.accumulateAndGet(now, math.max): Unit
      Thread.sleep(delayMillis)
      active.decrementAndGet(): Unit
      answer(conversation)
    }
    def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)
    def getContextWindow(): Int     = 8192
    def getReserveCompletion(): Int = 512
  }

  /** Fails every `streamComplete` call by throwing `thrown` after `first`, as a buggy provider might. */
  final class Throwing(first: Seq[StreamedChunk], thrown: RuntimeException) extends LLMClient {
    def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = throw thrown
    def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = {
      first.foreach(onChunk)
      throw thrown
    }
    def getContextWindow(): Int     = 8192
    def getReserveCompletion(): Int = 512
  }

  /**
   * Offers `total` chunks, swallowing whatever the callback throws, an interrupt included, as a careless provider
   * might; then returns a `Right`. Counts the chunks offered and those the callback accepted.
   */
  final class Deaf(total: Int) extends LLMClient {
    val offered: AtomicInteger   = new AtomicInteger(0)
    val delivered: AtomicInteger = new AtomicInteger(0)

    def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      ScriptedLLMClient.always(Reply.text("x")).complete(conversation, options)
    def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = {
      (1 to total).foreach { _ =>
        offered.incrementAndGet(): Unit
        if (CancelledError.catchInterrupt(Try(onChunk(StreamedChunk("id", Some("x"))))).exists(_.isSuccess))
          delivered.incrementAndGet(): Unit
      }
      Thread.interrupted(): Unit
      complete(conversation, options)
    }
    def getContextWindow(): Int     = 8192
    def getReserveCompletion(): Int = 512
  }

  /** Streams `chunks`, then returns `Left(error)`. */
  final class FailingAfter(chunks: Seq[StreamedChunk], error: LLMError) extends LLMClient {
    def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = Left(error)
    def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = {
      chunks.foreach(onChunk)
      Left(error)
    }
    def getContextWindow(): Int     = 8192
    def getReserveCompletion(): Int = 512
  }
}
