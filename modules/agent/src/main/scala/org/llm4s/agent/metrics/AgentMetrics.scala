package org.llm4s.agent.metrics

import org.llm4s.annotation.Experimental
import org.llm4s.metrics.Metric

import scala.concurrent.duration.*

/**
 * The agent runtime's metrics (Stage 5 O2, design 4.59), declared as core [[org.llm4s.metrics.Metric Metric]]s and
 * recorded through any `MetricsCollector` - `PrometheusMetrics`, `MicrometerMetrics` or a `MetricsCollector.compose` of
 * them - so neither backend depends on `llm4s-agent`. Two things record them:
 *
 *  - a [[MeteredCheckpointer]] wrapped around the store the runtimes and workers share: runs, claims, commits and
 *    child tasks, read from what each call to the store did, so every run is counted once, by the process that ran it;
 *  - an `AgentWorker` given the collector: the run queue and the worker's own work.
 *
 * Every label takes its values from a fixed set named on the metric, except `graph` (a graph's id) and `agent` (an
 * agent's id), which the code that builds the agents fixes; backends cap every label's distinct values. No thread,
 * run, entry or tenant id is ever a label, and no metric carries message content (Stage 5 rule 10).
 *
 * The names below are Micrometer's; a Prometheus backend exposes `llm4s.agent.runs.started` as
 * `llm4s_agent_runs_started_total`, a timer with the suffix `_seconds`, a gauge under its name with dots as
 * underscores.
 */
@Experimental
object AgentMetrics:

  private val RunBuckets: Seq[FiniteDuration] =
    Seq(
      100.millis,
      500.millis,
      1.second,
      2.seconds,
      5.seconds,
      10.seconds,
      30.seconds,
      1.minute,
      2.minutes,
      5.minutes,
      10.minutes,
      30.minutes,
      1.hour
    )

  private val StoreBuckets: Seq[FiniteDuration] =
    Seq(
      1.milli,
      5.millis,
      10.millis,
      25.millis,
      50.millis,
      100.millis,
      250.millis,
      500.millis,
      1.second,
      2500.millis,
      5.seconds,
      10.seconds
    )

  // ---- runs (MeteredCheckpointer) ----

  /** Runs that began: `graph`, `trigger` (`start`, `resume`, `recover`). A recovery is a run continuing an incomplete one. */
  val RunsStarted: Metric.Counter =
    Metric.counter("llm4s.agent.runs.started", "Agent runs started, by how they began", "graph", "trigger")

  /**
   * Runs that ended: `graph`, `status` (`completed`, `suspended`, `failed`, `cancelled`, `timed_out`). A run that
   * stops without a terminal event - its process died, or it lost its claim - ends nothing here; its recovery starts
   * a run.
   */
  val RunsEnded: Metric.Counter =
    Metric.counter("llm4s.agent.runs.ended", "Agent runs ended, by status", "graph", "status")

  /**
   * How long runs took, from the event that began the run to its terminal event, both stamped by the process that ran
   * it: `graph`, `status`.
   */
  val RunDuration: Metric.Timer =
    Metric.timer("llm4s.agent.run.duration", "Agent run duration, by status", RunBuckets, "graph", "status")

  // ---- claims and commits (MeteredCheckpointer) ----

  /**
   * Claims asked of the store: `result` - `acquired`, `taken_over` (granted over an expired claim nobody released: a
   * dead run's, or one whose process lost the store), `busy` (a live claim held the thread) or `failed`. A worker's
   * probe of a thread's claim and its sweep's lock are claims too.
   */
  val Claims: Metric.Counter =
    Metric.counter("llm4s.agent.claims", "Thread claims asked of the checkpoint store, by result", "result")

  /** Claim renewals: `result` - `renewed`, `lost` (another claim replaced it, or the thread was deleted) or `failed`. */
  val ClaimRenewals: Metric.Counter =
    Metric.counter("llm4s.agent.claim.renewals", "Thread claim renewals, by result", "result")

  /**
   * Commits to the store and how long each took: `result` - `ok`, `conflict` (the parent checkpoint was not the
   * thread's latest), `claim_lost` (the claim, or the fencing claim, was no longer current) or `failed`.
   */
  val CommitDuration: Metric.Timer =
    Metric.timer("llm4s.agent.commit.duration", "Checkpoint store commit latency, by result", StoreBuckets, "result")

  // ---- child tasks (MeteredCheckpointer) ----

  /** Delegated tasks whose child run was first admitted: `agent` (the child's agent). */
  val TasksStarted: Metric.Counter =
    Metric.counter("llm4s.agent.tasks.started", "Delegated child tasks started", "agent")

  /**
   * Delegated tasks that ended (`agent.task_ended`): `agent`, `status` (`completed`, `blocked`, `step_limit_reached`,
   * `token_limit_reached`, `output_invalid`, `failed`).
   */
  val TasksEnded: Metric.Counter =
    Metric.counter("llm4s.agent.tasks.ended", "Delegated child tasks ended, by status", "agent", "status")

  /** How long delegated tasks took, from the child's first admission to the call's end: `agent`, `status`. */
  val TaskDuration: Metric.Timer =
    Metric.timer("llm4s.agent.task.duration", "Delegated child task duration, by status", RunBuckets, "agent", "status")

  // ---- the run queue (AgentWorker) ----

  /**
   * How long entries waited from submission to their first recorded attempt, both by the store's clock: `agent`. A
   * deferral to a busy thread, or an entry queued behind its thread's running one, waits on.
   */
  val QueueWait: Metric.Timer =
    Metric.timer("llm4s.agent.queue.wait", "Run queue wait from submission to first attempt", RunBuckets, "agent")

  /**
   * Open entries now, as a worker last sampled them for its agents: `agent`, `state` (`waiting`, `leased`). Every worker
   * of the fleet reports the same store-wide numbers; aggregate them with `max`, not `sum`.
   */
  val QueueOpenEntries: Metric.Gauge =
    Metric.gauge("llm4s.agent.queue.depth", "Open run queue entries, by state", "agent", "state")

  /** How long the oldest waiting entry has been open, in seconds, by the store's clock; 0 when none waits: `agent`. */
  val QueueOldest: Metric.Gauge =
    Metric.gauge("llm4s.agent.queue.oldest.seconds", "Age of the oldest waiting run queue entry", "agent")

  /** Attempts a worker asked to record: `agent`, `result` (`recorded`, `exhausted`, `thread_deleted`). */
  val QueueAttempts: Metric.Counter =
    Metric.counter("llm4s.agent.queue.attempts", "Run queue attempts, by result", "agent", "result")

  /**
   * What a recorded attempt did ([[org.llm4s.agent.service.AttemptPlan AttemptPlan]]): `agent`, `plan` (`start`,
   * `recover` - the input was admitted and its run is continued, the recovery of a dead worker's run - or `close`).
   */
  val QueuePlans: Metric.Counter =
    Metric.counter("llm4s.agent.queue.plans", "What run queue attempts did", "agent", "plan")

  /**
   * Entries a worker gave back without an attempt: `agent`, `reason` (`busy` - a live claim held the thread - or
   * `unrunnable` - its graph could not take the thread on).
   */
  val QueueDeferrals: Metric.Counter =
    Metric.counter("llm4s.agent.queue.deferrals", "Run queue entries deferred without an attempt", "agent", "reason")

  /**
   * Takes that replaced an expired lease (`LeasedEntry.expiredLease`): `agent`. An expired lease is a dead or cut-off
   * worker's, or one a worker let lapse after a failed run ([[QueueLapses]]).
   */
  val QueueLeaseExpiries: Metric.Counter =
    Metric.counter("llm4s.agent.queue.lease.expiries", "Run queue takes that replaced an expired lease", "agent")

  /**
   * Leases a worker let lapse on purpose, so the entry is tried again a lease later - after a failed run, or a store
   * error reading its thread: `agent`.
   */
  val QueueLapses: Metric.Counter =
    Metric.counter("llm4s.agent.queue.lease.lapses", "Run queue leases let lapse to retry the entry later", "agent")

  /**
   * Leases a worker lost while it ran the entry, cancelling the run: `agent`, `reason` (`refused` - another worker took
   * the entry or it closed - or `unrenewed` - no renewal landed for a whole ttl).
   */
  val QueueLeaseLosses: Metric.Counter =
    Metric.counter("llm4s.agent.queue.lease.losses", "Run queue leases lost mid-run", "agent", "reason")

  /**
   * Entries closed: `agent`, `outcome` (`done`, `failed`, `cancelled`, `interrupted`) and `reason`: for `failed`,
   * `attempts_exhausted` (a dead letter), `run_failed` or `unrunnable`; for `cancelled`, `requested` or `thread_deleted`;
   * otherwise `none`.
   */
  val QueueClosed: Metric.Counter =
    Metric.counter("llm4s.agent.queue.closed", "Run queue entries closed, by outcome", "agent", "outcome", "reason")

  // ---- the worker (AgentWorker) ----

  /** Retention sweep passes: `result` (`done`, `skipped` - another instance was sweeping - or `failed`). */
  val SweepPasses: Metric.Counter =
    Metric.counter("llm4s.agent.sweep.passes", "Retention sweep passes, by result", "result")

  /** Threads the retention sweep examined. */
  val SweepExamined: Metric.Counter =
    Metric.counter("llm4s.agent.sweep.threads.examined", "Threads the retention sweep examined")

  /** Tombstones the retention sweep reclaimed. */
  val SweepTombstones: Metric.Counter =
    Metric.counter("llm4s.agent.sweep.tombstones.reclaimed", "Tombstones the retention sweep reclaimed")

  /** Closed queue entries past their ticket lifetime the retention sweep reclaimed. */
  val SweepEntries: Metric.Counter =
    Metric.counter("llm4s.agent.sweep.entries.reclaimed", "Closed run queue entries the retention sweep reclaimed")

  /**
   * Runs a draining worker had, by how they ended: `result` (`finished` within the grace period, `cancelled` and
   * exited, `abandoned` - not exited, their claims and leases left to expire).
   */
  val DrainedRuns: Metric.Counter =
    Metric.counter("llm4s.agent.worker.drained", "Runs of a draining worker, by how they ended", "result")
