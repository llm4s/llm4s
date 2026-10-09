package org.llm4s.javaapi

import org.llm4s.agent.graph.{ EventType, StreamEvent }

import java.util.Optional

/**
 * Reads an [[AgentStreamListener]]'s events from Java without `scala.Option`.
 *
 * {{{
 * agent.stream(threadId, "Explain monads", event ->
 *     StreamEvents.decode(AgentEvents.TextDelta(), event).ifPresent(d -> System.out.print(d.text())));
 * }}}
 *
 * A `StreamEvent.LiveGap` - live events dropped because the listener fell behind - is matched with
 * `instanceof StreamEvent.LiveGap`, and its count read with `dropped()`.
 */
object StreamEvents {

  /**
   * The payload of `event` when it is an event of `eventType` - one of `AgentEvents`' (`TextDelta()`,
   * `ToolExecuted()`, ...) or your own - and empty otherwise: another event, version or an
   * undecodable payload.
   */
  def decode[A](eventType: EventType[A], event: StreamEvent): Optional[A] =
    eventType.unapply(event).fold(Optional.empty[A]())(Optional.of(_))
}
