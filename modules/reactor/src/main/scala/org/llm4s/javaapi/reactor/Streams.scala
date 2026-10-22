package org.llm4s.javaapi.reactor

import org.llm4s.error.ValidationError
import org.llm4s.javaapi.{ JCompletionOptions, JLlmClient, LlmException }
import org.llm4s.llmconnect.model.Conversation
import reactor.adapter.JdkFlowAdapter
import reactor.core.publisher.Flux

import java.util.concurrent.Flow

/**
 * A model's reply as a Reactor `Flux<String>` of its text (#1492), for Spring WebFlux and other Reactor pipelines.
 *
 * Each method adapts the JDK `Flow.Publisher` of [[org.llm4s.javaapi.JLlmClient#streamAsPublisher]] with Reactor's
 * `JdkFlowAdapter`, so the `Flux` behaves as that publisher does:
 *
 *  - it is cold: each subscription makes its own provider call, on a thread of its own;
 *  - it emits the pieces of the reply's text in order, then completes;
 *  - a failed call ends it with `onError`, carrying an [[org.llm4s.javaapi.LlmException LlmException]] whose
 *    `getKind()` says what failed;
 *  - it honours backpressure: a subscriber that requests less than the model produces holds the provider's thread
 *    rather than losing text;
 *  - cancelling the subscription (`dispose()`, `take(n)`, a client that disconnects) interrupts the provider call.
 *
 * A `null` argument is never thrown: the `Flux` fails on subscription with an `LlmException` of kind `VALIDATION`.
 *
 * {{{
 * Flux<String> reply = Streams.toFlux(client, "Explain monads in one sentence");
 * reply.subscribe(System.out::print);
 * }}}
 */
object Streams {

  /** The reply to a single user query, as a `Flux` of its text. */
  def toFlux(client: JLlmClient, query: String): Flux[String] =
    adapt(client)(_.streamAsPublisher(query))

  /** A conversation's reply, as a `Flux` of its text. */
  def toFlux(client: JLlmClient, conversation: Conversation): Flux[String] =
    adapt(client)(_.streamAsPublisher(conversation))

  /** A conversation's reply with the options of a [[org.llm4s.javaapi.JCompletionOptions]], as a `Flux` of its text. */
  def toFlux(client: JLlmClient, conversation: Conversation, options: JCompletionOptions): Flux[String] =
    adapt(client)(_.streamAsPublisher(conversation, options))

  private def adapt(client: JLlmClient)(publisher: JLlmClient => Flow.Publisher[String]): Flux[String] =
    if (client == null) Flux.error(new LlmException(ValidationError.required("client")))
    else JdkFlowAdapter.flowPublisherToFlux(publisher(client))
}
