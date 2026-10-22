package org.llm4s.javaapi

import org.llm4s.core.safety.Safety
import org.llm4s.error.{ CancelledError, LLMError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation }

import java.util.concurrent.{ Flow, SubmissionPublisher }
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The `Flow.Publisher` of [[JLlmClient.streamAsPublisher]] (#1485): cold, one provider call per subscription, on a
 * thread of its own. Each piece of text is submitted to a `SubmissionPublisher` of `bufferSize`, which blocks the
 * provider's thread while the subscriber's buffer is full, so nothing is dropped. A subscription cancelled before the
 * end interrupts the call. The stream completes after the last piece, or fails with an [[LlmException]].
 */
final private[javaapi] class CompletionPublisher(
  start: Either[LLMError, (LLMClient, Conversation, CompletionOptions)],
  bufferSize: Int
) extends Flow.Publisher[String] {

  override def subscribe(subscriber: Flow.Subscriber[? >: String]): Unit = {
    val cancelled = new AtomicBoolean(false)
    val publisher = new SubmissionPublisher[String](java.util.concurrent.ForkJoinPool.commonPool(), bufferSize)
    publisher.subscribe(new CompletionPublisher.Cancellable(subscriber, cancelled))
    start match {
      case Left(error) => publisher.closeExceptionally(new LlmException(error))
      case Right((client, conversation, options)) =>
        Thread
          .ofVirtual()
          .name("llm4s-java-publisher")
          .start(() => run(publisher, cancelled, client, conversation, options)): Unit
    }
  }

  private def run(
    publisher: SubmissionPublisher[String],
    cancelled: AtomicBoolean,
    client: LLMClient,
    conversation: Conversation,
    options: CompletionOptions
  ): Unit = {
    val result = CancelledError.attempt("JLlmClient.streamAsPublisher")(
      Safety
        .safely(
          client.streamComplete(
            conversation,
            options,
            chunk => chunk.content.filter(_.nonEmpty).foreach(offer(publisher, cancelled, _))
          )
        )
        .flatten
    )
    Thread.interrupted(): Unit
    result.fold(e => publisher.closeExceptionally(new LlmException(e)), _ => publisher.close())
  }

  /** Submits `text`, or - once the subscriber has cancelled - interrupts the call instead. */
  private def offer(publisher: SubmissionPublisher[String], cancelled: AtomicBoolean, text: String): Unit =
    if (cancelled.get) Thread.currentThread().interrupt()
    else publisher.submit(text): Unit
}

private[javaapi] object CompletionPublisher {

  /** `subscriber`, whose subscription sets `cancelled` when it is cancelled, so the call can be interrupted. */
  final private class Cancellable(subscriber: Flow.Subscriber[? >: String], cancelled: AtomicBoolean)
      extends Flow.Subscriber[String] {
    def onSubscribe(subscription: Flow.Subscription): Unit =
      subscriber.onSubscribe(new Flow.Subscription {
        def request(n: Long): Unit = subscription.request(n)
        def cancel(): Unit = {
          cancelled.set(true)
          subscription.cancel()
        }
      })
    def onNext(item: String): Unit      = subscriber.onNext(item)
    def onError(error: Throwable): Unit = subscriber.onError(error)
    def onComplete(): Unit              = subscriber.onComplete()
  }

  /** Pieces of text a subscriber may fall behind by before the provider's thread waits for it. */
  val BufferSize: Int = 64
}
