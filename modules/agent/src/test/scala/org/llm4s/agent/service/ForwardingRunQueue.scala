package org.llm4s.agent.service

import org.llm4s.agent.AgentId
import org.llm4s.agent.graph.{ RunId, TenantId, ThreadId }

import scala.concurrent.duration.FiniteDuration

/** A [[RunQueue]] passing every call to `to`: a spec overrides the calls it changes. */
private[agent] class ForwardingRunQueue(to: RunQueue) extends RunQueue {
  def submit(s: Submission)                                                     = to.submit(s)
  def entry(t: Option[TenantId], id: EntryId)                                   = to.entry(t, id)
  override def awaitClosed(t: Option[TenantId], id: EntryId, d: FiniteDuration) = to.awaitClosed(t, id, d)
  def openEntries(t: Option[TenantId], th: ThreadId)                            = to.openEntries(t, th)
  def take(r: TakeRequest)                                                      = to.take(r)
  def depth(as: Set[AgentId])                                                   = to.depth(as)
  def allDepths()                                                               = to.allDepths()
  def closeUntaken(age: FiniteDuration, limit: Int)                             = to.closeUntaken(age, limit)
  def renew(l: Lease, ttl: FiniteDuration)                                      = to.renew(l, ttl)
  def cancel(t: Option[TenantId], c: CancelTarget, r: CancelReason)             = to.cancel(t, c, r)
  def release(l: Lease)                                                         = to.release(l)
  def recordAttempt(l: Lease, r: RunId)                                         = to.recordAttempt(l, r)
  def close(l: Lease, o: EntryOutcome)                                          = to.close(l, o)
  def reclaim(d: FiniteDuration, n: Int)                                        = to.reclaim(d, n)
  def openRuns(ts: Vector[ThreadId])                                            = to.openRuns(ts)
}
