package org.llm4s.spring

import org.llm4s.javaapi.JLlmClient
import org.llm4s.llmconnect.model.Conversation
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

import java.util.concurrent.Flow
import java.util.concurrent.atomic.AtomicReference
import scala.util.Try

/**
 * A model's reply as a Spring MVC `SseEmitter` (#2279): return it from a controller and each piece of the reply's text
 * reaches the browser as one server-sent event, as the model writes it.
 *
 * The `llm4sSseTemplate` bean, registered when Spring MVC is on the classpath. The provider call runs on a thread of its
 * own (`JLlmClient.streamAsPublisher`), so the request thread is free at once. The emitter completes after the last
 * piece, and completes with the `LlmException` when the call fails. When the client disconnects or the request times
 * out, the provider call is interrupted.
 */
final class SseLLM4STemplate(private val client: JLlmClient) {

  /** The reply to a single user query, one event per piece of its text. */
  def stream(query: String): SseEmitter = emit(client.streamAsPublisher(query))

  /** A conversation's reply, one event per piece of its text. */
  def stream(conversation: Conversation): SseEmitter = emit(client.streamAsPublisher(conversation))

  private def emit(publisher: Flow.Publisher[String]): SseEmitter = {
    val emitter = new SseEmitter()
    publisher.subscribe(new SseLLM4STemplate.Forward(emitter))
    emitter
  }
}

private[spring] object SseLLM4STemplate {

  /** Sends each piece to `emitter`; an emitter that ends first - a disconnect, a timeout - cancels the call. */
  final class Forward(emitter: SseEmitter) extends Flow.Subscriber[String] {
    private val subscription = new AtomicReference[Flow.Subscription]()

    override def onSubscribe(s: Flow.Subscription): Unit = {
      subscription.set(s)
      emitter.onCompletion(() => s.cancel())
      emitter.onTimeout(() => s.cancel())
      emitter.onError(_ => s.cancel())
      s.request(Long.MaxValue)
    }

    override def onNext(text: String): Unit =
      if (Try(emitter.send(text)).isFailure) subscription.get().cancel()

    override def onError(error: Throwable): Unit = emitter.completeWithError(error)

    override def onComplete(): Unit = emitter.complete()
  }
}
