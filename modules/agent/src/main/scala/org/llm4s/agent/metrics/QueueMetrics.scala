package org.llm4s.agent.metrics

import org.llm4s.agent.AgentId
import org.llm4s.agent.service.*
import org.llm4s.metrics.MetricsCollector

import scala.concurrent.duration.FiniteDuration
import scala.util.Try

/**
 * What an `AgentWorker` records of the run queue and of its own work ([[AgentMetrics]], design 4.59): one method per
 * thing that happened, each turning it into bounded labels. A recording that fails never reaches the caller.
 *
 * '''W3's hook.''' [[closed]] is the one place an entry's close is counted, labelled by its outcome - `cancelled` with
 * reason `requested`, `interrupted` - so the cancel and interrupt counts of W3 (#2136) come from calling it wherever an
 * entry is closed outside `AgentWorker` (a cancel of a queued entry, an `Interrupt` closing the running one). The runs
 * a cancel stops are counted by [[MeteredCheckpointer]] already, as `llm4s.agent.runs.ended{status="cancelled"}`.
 */
final private[agent] class QueueMetrics(metrics: MetricsCollector):
  import AgentMetrics.*

  /** Whether anything is recorded: a worker with the no-op collector skips sampling the queue's depth. */
  val enabled: Boolean = metrics ne MetricsCollector.noop

  private def record(body: => Unit): Unit = if enabled then Try(body): Unit

  /** `leased` was taken: a lease expiry if the take replaced an expired lease. */
  def taken(leased: LeasedEntry): Unit = record {
    if leased.expiredLease then metrics.increment(QueueLeaseExpiries, Seq(leased.entry.agentId.value))
  }

  /** `entry`'s attempt was asked for: by result, and the entry's wait, from submission to its first attempt. */
  def attempted(agent: AgentId, start: AttemptStart): Unit = record {
    val result = start match
      case AttemptStart.Recorded(entry) =>
        if entry.attempts.size == 1 then
          entry.attempts.headOption.foreach(first =>
            metrics.observe(QueueWait, Seq(agent.value), QueueDepth.age(entry.submittedAt, first.startedAt))
          )
        "recorded"
      case AttemptStart.Exhausted(_) => "exhausted"
      case AttemptStart.ThreadDeleted(entry) =>
        entry.outcome.foreach(closed(agent, _))
        "thread_deleted"
    metrics.increment(QueueAttempts, Seq(agent.value, result))
  }

  /** A recorded attempt acted on `plan`. */
  def planned(agent: AgentId, plan: AttemptPlan): Unit = record {
    val name = plan match
      case AttemptPlan.Start    => "start"
      case AttemptPlan.Recover  => "recover"
      case AttemptPlan.Close(_) => "close"
    metrics.increment(QueuePlans, Seq(agent.value, name))
  }

  /** An entry was given back with no attempt: `busy` or `unrunnable`. */
  def deferred(agent: AgentId, reason: String): Unit = record {
    metrics.increment(QueueDeferrals, Seq(agent.value, reason))
  }

  /** The worker let an entry's lease lapse, so a failed run comes back a lease later. */
  def lapsed(agent: AgentId): Unit = record(metrics.increment(QueueLapses, Seq(agent.value)))

  /** The worker lost an entry's lease mid-run: `refused` or `unrenewed`. */
  def leaseLost(agent: AgentId, reason: String): Unit = record {
    metrics.increment(QueueLeaseLosses, Seq(agent.value, reason))
  }

  /** An entry of `agent` closed with `outcome`: W3's hook (see the class doc). */
  def closed(agent: AgentId, outcome: EntryOutcome): Unit = record {
    val reason = outcome match
      case EntryOutcome.Failed(FailureReason.AttemptsExhausted(_)) => "attempts_exhausted"
      case EntryOutcome.Failed(FailureReason.RunFailed(_))         => "run_failed"
      case EntryOutcome.Failed(FailureReason.Unrunnable(_))        => "unrunnable"
      case EntryOutcome.Cancelled(CancelReason.ThreadDeleted)      => "thread_deleted"
      case EntryOutcome.Cancelled(CancelReason.Requested(_))       => "requested"
      case _                                                       => "none"
    metrics.increment(QueueClosed, Seq(agent.value, outcome.state, reason))
  }

  /** The queue's depth for each of `agents`, as sampled: an agent absent from `depths` has no open entry. */
  def depths(agents: Set[AgentId], depths: Map[AgentId, QueueDepth]): Unit = record {
    agents.foreach { agent =>
      val d = depths.getOrElse(agent, QueueDepth.empty)
      metrics.set(QueueOpenEntries, Seq(agent.value, "waiting"), d.waiting.toDouble)
      metrics.set(QueueOpenEntries, Seq(agent.value, "leased"), d.leased.toDouble)
      metrics.set(QueueOldest, Seq(agent.value), d.oldestWaiting.fold(0.0)(seconds))
    }
  }

  /** A retention sweep pass ended as `report`, or failed. */
  def swept(report: Either[?, SweepReport]): Unit = record {
    report match
      case Left(_)               => metrics.increment(SweepPasses, Seq("failed"))
      case Right(r) if r.skipped => metrics.increment(SweepPasses, Seq("skipped"))
      case Right(r) =>
        metrics.increment(SweepPasses, Seq("done"))
        metrics.increment(SweepExamined, Seq.empty, r.examined.toDouble)
        metrics.increment(SweepTombstones, Seq.empty, r.tombstones.toDouble)
        metrics.increment(SweepEntries, Seq.empty, r.entries.toDouble)
  }

  /** A drain ended as `report`. */
  def drained(report: DrainReport): Unit = record {
    metrics.increment(DrainedRuns, Seq("finished"), report.finished.size.toDouble)
    metrics.increment(DrainedRuns, Seq("cancelled"), report.cancelled.size.toDouble)
    metrics.increment(DrainedRuns, Seq("abandoned"), report.abandoned.size.toDouble)
  }

  private def seconds(d: FiniteDuration): Double = d.toNanos / 1e9
