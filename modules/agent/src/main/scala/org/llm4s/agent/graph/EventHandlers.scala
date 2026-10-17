package org.llm4s.agent.graph

/**
 * Typed handlers for a run's events, each registered with its [[EventType]], so that a selection of
 * mixed event types keeps each payload's type:
 *
 * {{{
 * val handlers = EventHandlers
 *   .on(AgentEvents.TextDelta)(d => print(d.text))
 *   .on(Checked)(c => println(s"checked ${c.path}"))
 *   .otherwise {
 *     case StreamEvent.LiveGap(n) => println(s"[$n live events lost]")
 *     case _                      => ()
 *   }
 * agent.stream(threadId, query)(handlers)
 * }}}
 *
 * An event is looked up once by its name and version - a durable [[RunEvent.Custom]] or a
 * [[StreamEvent.Live]] alike - and its payload decoded once, by the one type registered for them. `R`
 * is what a handler returns: `Unit` for a listener, or a caller's own type, to turn a run's events
 * into a stream of one ADT (`on(Checked)(MyEvent.Checked(_))`, the effect modules' `events`).
 *
 * An `EventHandlers` is a listener (`StreamEvent => Unit`): pass it to `Agent.stream`,
 * `AgentRun.subscribe` or `GraphRuntime.subscribe`. As a listener it runs [[dispatch]] and discards
 * the result; a handler that throws is a listener that throws.
 */
final class EventHandlers[+R] private (
  handlers: Map[(String, Int), ujson.Value => Option[R]],
  fallback: Option[StreamEvent => R]
) extends (StreamEvent => Unit):

  /**
   * These handlers, with `handler` for events of `eventType`'s name and version; it replaces a
   * handler already registered for them.
   */
  def on[A, R2 >: R](eventType: EventType[A])(handler: A => R2): EventHandlers[R2] =
    new EventHandlers[R2](
      handlers.updated((eventType.name, eventType.version), payload => eventType.decode(payload).map(handler)),
      fallback
    )

  /**
   * These handlers, with `handler` for every event no handler takes: a kernel event, a `LiveGap`, a
   * `Disconnected`, an event of an unregistered name or version, or a payload that does not decode.
   * Without it those are skipped. It replaces an earlier `otherwise`.
   */
  def otherwise[R2 >: R](handler: StreamEvent => R2): EventHandlers[R2] = new EventHandlers[R2](handlers, Some(handler))

  /**
   * The result of the handler for `event`: the one registered for its name and version, given the
   * decoded payload, or else `otherwise`'s; `None` when neither takes it.
   */
  def dispatch(event: StreamEvent): Option[R] =
    val typed = event match
      case StreamEvent.Durable(record) =>
        record.event match
          case RunEvent.Custom(name, version, payload) => handlers.get((name, version)).flatMap(_(payload))
          case _                                       => None
      case StreamEvent.Live(_, _, _, _, _, name, version, payload) => handlers.get((name, version)).flatMap(_(payload))
      case _                                                       => None
    typed.orElse(fallback.map(_(event)))

  /** Runs [[dispatch]], discarding its result. */
  def apply(event: StreamEvent): Unit = dispatch(event): Unit

  override def toString: String =
    handlers.keys.toVector.sorted.map((n, v) => s"$n v$v").mkString("EventHandlers(", ", ", ")")

object EventHandlers:

  /** Handlers with `handler` for events of `eventType`; add more with [[EventHandlers.on]]. */
  def on[A, R](eventType: EventType[A])(handler: A => R): EventHandlers[R] =
    new EventHandlers[Nothing](Map.empty, None).on(eventType)(handler)
