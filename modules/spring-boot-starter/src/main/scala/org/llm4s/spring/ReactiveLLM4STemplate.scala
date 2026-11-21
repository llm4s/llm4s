package org.llm4s.spring

import org.llm4s.javaapi.JLlmClient
import org.llm4s.javaapi.reactor.Streams
import org.llm4s.llmconnect.model.Conversation
import reactor.core.publisher.Flux

/**
 * A model's reply as a Reactor `Flux<String>` of its text (#2279), for Spring WebFlux: return it from a controller with
 * `produces = text/event-stream` and each piece reaches the browser as the model writes it.
 *
 * The `llm4sReactiveTemplate` bean, registered when Reactor and `llm4s-reactor` are on the classpath. A class of its own,
 * not methods of [[LLM4STemplate]], so that an application without Reactor can still load that one. Each `Flux` is
 * `Streams.toFlux`'s: cold (each subscription makes its own provider call), failing with an `LlmException`, and a
 * cancelled subscription - a client that disconnects - interrupts the provider call.
 */
final class ReactiveLLM4STemplate(private val client: JLlmClient) {

  /** The reply to a single user query, as a `Flux` of its text. */
  def stream(query: String): Flux[String] = Streams.toFlux(client, query)

  /** A conversation's reply, as a `Flux` of its text. */
  def stream(conversation: Conversation): Flux[String] = Streams.toFlux(client, conversation)
}
