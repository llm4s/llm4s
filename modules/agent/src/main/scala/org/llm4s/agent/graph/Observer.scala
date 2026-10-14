package org.llm4s.agent.graph

/**
 * A listener subscribed to a run's thread during admission - before the run's claim commits, with
 * no replay - so it receives every durable event of the run from its claim, and every live event,
 * of which a [[GraphRuntime.subscribe]] made after `start` returns can miss the first: a subscription
 * receives only the live events sent after it returns. `capacity` is the subscription's queue
 * size, at least 2, as for [[GraphRuntime.subscribe]]. Its durable events start at the run's claim,
 * as a [[RunHandle.subscribe]]'s do: a commit of an earlier run on the thread handed over during
 * admission - a lost run's late write, even one of a run with the same `RunId` - is not delivered, nor
 * is a live event sent before the claim. The run's own claim is recognised by its fencing token
 * ([[EventRecord.token]]), which, unlike a run id, is never reused. The subscription is thread-scoped like any
 * other: it keeps delivering later runs until cancelled, through [[RunHandle.observation]]. A
 * refused admission abandons it, and its listener is never called.
 */
final case class Observer(capacity: Int, listener: StreamEvent => Unit)
