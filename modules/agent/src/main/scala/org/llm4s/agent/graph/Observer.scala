package org.llm4s.agent.graph

/**
 * A listener subscribed to a run's thread during admission - before the run's claim commits, with
 * no replay - so it receives every durable event of the run from its claim, and every live event,
 * which a [[GraphRuntime.subscribe]] subscription can miss: it delivers live events only once it has
 * replayed and switched to live, which `subscribe` returns before. `capacity` is the subscription's queue
 * size, at least 2, as for [[GraphRuntime.subscribe]]. The subscription is thread-scoped like any
 * other: it keeps delivering later runs until cancelled, through [[RunHandle.observation]]. A
 * refused admission abandons it, and its listener is never called.
 */
final case class Observer(capacity: Int, listener: StreamEvent => Unit)
