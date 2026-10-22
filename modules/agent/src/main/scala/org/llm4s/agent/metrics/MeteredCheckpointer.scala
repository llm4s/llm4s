package org.llm4s.agent.metrics

import org.llm4s.agent.events.{ SubagentEvents, TaskEnded }
import org.llm4s.agent.graph.*
import org.llm4s.annotation.Experimental
import org.llm4s.metrics.MetricsCollector
import org.llm4s.types.Result

import java.time.{ Duration as JDuration, Instant }
import scala.concurrent.duration.*
import scala.util.Try

/**
 * A [[org.llm4s.agent.graph.Checkpointer Checkpointer]] that records the agent runtime's run, claim, commit and
 * child-task metrics ([[AgentMetrics]], design 4.59) into `metrics` as it forwards every call to `inner` unchanged. Give
 * it to every runtime and worker of a process in place of the store:
 *
 * {{{
 * val metrics = PrometheusMetrics.create()
 * for
 *   store  <- CheckpointerConfigLoader.open()           // llm4s.agent.checkpointer
 *   queue  <- store.runQueue
 *   metered = MeteredCheckpointer(store.checkpointer, metrics)
 *   agent  <- Agent.builder("assistant", client).withRuntime(new GraphRuntime(metered)).build()
 *   worker <- AgentWorker(queue, metered, Seq(QueuedAgent(agent)), metrics = metrics)
 * yield worker
 * }}}
 *
 * It reads what each call did, so it needs no hook in the runtime and counts every run once, in the process whose
 * runtime committed it:
 *
 *  - '''claims''' ([[AgentMetrics.Claims]]) from `claim`'s answer - a grant naming `takenOverFrom` is a takeover, which
 *    the store names only within one tenant, so a takeover of another tenant's expired claim counts as `acquired` - and
 *    renewals ([[AgentMetrics.ClaimRenewals]]) from `renew`'s;
 *  - '''commits''' ([[AgentMetrics.CommitDuration]]) timed around `commit`, by result;
 *  - '''runs''' from the durable events a commit wrote: `RunStarted`, `RunResumed` and `RunRecovered` begin a run
 *    ([[AgentMetrics.RunsStarted]]), and its terminal event ends it ([[AgentMetrics.RunsEnded]],
 *    [[AgentMetrics.RunDuration]]: from the first event's timestamp to the last's, both stamped by this process). A run
 *    is named by its graph's id, read from the commit's checkpoint and remembered from its start for a terminal commit
 *    that writes none (a cancel's). At most [[MeteredCheckpointer.InFlightLimit]] runs are remembered at once; a run
 *    past that, or one that began before this checkpointer did, ends without a duration;
 *  - '''child tasks''': a child thread's `RunStarted` - its first admission, its checkpoint naming its origin - starts
 *    one ([[AgentMetrics.TasksStarted]]), and the parent's durable `agent.task_ended` ends it
 *    ([[AgentMetrics.TasksEnded]], [[AgentMetrics.TaskDuration]]).
 *
 * Every other call is forwarded as it is - `awaitEventsAfter` included, so a store's change notification is kept. A
 * metric that fails to record never changes a call's answer, and no metric carries a thread, run or tenant id or any
 * content (Stage 5 rule 10).
 */
@Experimental
final class MeteredCheckpointer private (val inner: Checkpointer, metrics: MetricsCollector) extends Checkpointer:
  import AgentMetrics.*
  import MeteredCheckpointer.*

  /** The runs begun and not yet ended, by thread, run and claim: their graph and first event's timestamp. */
  private val inFlight = new java.util.LinkedHashMap[(String, String, Long), (String, Instant)](16, 0.75f, false):
    override def removeEldestEntry(eldest: java.util.Map.Entry[(String, String, Long), (String, Instant)]): Boolean =
      size() > InFlightLimit

  private def record(body: => Unit): Unit = Try(body): Unit

  // ---- claims ----

  def claim(threadId: ThreadId, request: ClaimRequest): Result[RunClaim] =
    val answer = inner.claim(threadId, request)
    record {
      val result = answer match
        case Right(granted) if granted.takenOverFrom.isDefined => "taken_over"
        case Right(_)                                          => "acquired"
        case Left(_: GraphError.ThreadBusy)                    => "busy"
        case Left(_)                                           => "failed"
      metrics.increment(Claims, Seq(result))
    }
    answer

  def renew(threadId: ThreadId, token: FencingToken, ttl: FiniteDuration): Result[RunClaim] =
    val answer = inner.renew(threadId, token, ttl)
    record {
      val result = answer match
        case Right(_)                       => "renewed"
        case Left(_: GraphError.StaleClaim) => "lost"
        case Left(_)                        => "failed"
      metrics.increment(ClaimRenewals, Seq(result))
    }
    answer

  def release(threadId: ThreadId, token: FencingToken): Result[Unit] = inner.release(threadId, token)

  // ---- commits ----

  def commit(threadId: ThreadId, commit: Commit): Result[Vector[EventRecord]] =
    val began  = System.nanoTime()
    val answer = inner.commit(threadId, commit)
    val took   = (System.nanoTime() - began).nanos
    record {
      val result = answer match
        case Right(_)                               => "ok"
        case Left(_: GraphError.CheckpointConflict) => "conflict"
        case Left(_: GraphError.StaleClaim)         => "claim_lost"
        case Left(_)                                => "failed"
      metrics.observe(CommitDuration, Seq(result), took)
    }
    answer.foreach(records => record(project(commit, records)))
    answer

  /** The run and child-task metrics of the durable events `records` that `commit` wrote. */
  private def project(commit: Commit, records: Vector[EventRecord]): Unit =
    val graph = commit.checkpoint.map(_.snapshot.graphId)
    records.foreach { r =>
      r.event match
        case RunEvent.RunStarted(_, _) =>
          began(r, graph, "start")
          commit.checkpoint
            .flatMap(_.origin)
            .foreach(origin => metrics.increment(TasksStarted, Seq(origin.agent.value)))
        case RunEvent.RunResumed(_, _, _)      => began(r, graph, "resume")
        case RunEvent.RunRecovered(_, _, _, _) => began(r, graph, "recover")
        case RunEvent.RunCompleted             => ended(r, graph, "completed")
        case RunEvent.RunSuspended(_)          => ended(r, graph, "suspended")
        case RunEvent.RunFailed(_, _, _)       => ended(r, graph, "failed")
        case RunEvent.RunCancelled(_, _)       => ended(r, graph, "cancelled")
        case RunEvent.RunTimedOut(_, _)        => ended(r, graph, "timed_out")
        case RunEvent.Custom(name, _, payload) if name == SubagentEvents.TaskEnded.name =>
          Try(upickle.default.read[TaskEnded](payload)).foreach { task =>
            val labels = Seq(task.child.agent.value, taskStatus(task.status))
            metrics.increment(TasksEnded, labels)
            metrics.observe(TaskDuration, labels, task.duration)
          }
        case _ => ()
    }

  private def key(r: EventRecord): (String, String, Long) = (r.threadId, r.runId, r.token.value)

  private def began(r: EventRecord, graph: Option[String], trigger: String): Unit =
    val name = graph.getOrElse(UnknownGraph)
    inFlight.synchronized(inFlight.put(key(r), name -> r.timestamp)): Unit
    metrics.increment(RunsStarted, Seq(name, trigger))

  private def ended(r: EventRecord, graph: Option[String], status: String): Unit =
    val start  = inFlight.synchronized(Option(inFlight.remove(key(r))))
    val name   = graph.orElse(start.map(_._1)).getOrElse(UnknownGraph)
    val labels = Seq(name, status)
    metrics.increment(RunsEnded, labels)
    start.foreach { (_, at) =>
      metrics.observe(RunDuration, labels, math.max(0L, JDuration.between(at, r.timestamp).toNanos).nanos)
    }

  // ---- forwarded as they are ----

  def latest(threadId: ThreadId): Result[Option[StoredCheckpoint]]             = inner.latest(threadId)
  def effect(threadId: ThreadId, key: EffectKey): Result[Option[EffectRecord]] = inner.effect(threadId, key)
  def history(threadId: ThreadId, before: Option[String], limit: Int): Result[Vector[Checkpoint]] =
    inner.history(threadId, before, limit)
  def checkpoint(threadId: ThreadId, checkpointId: String): Result[Option[Checkpoint]] =
    inner.checkpoint(threadId, checkpointId)
  def prune(threadId: ThreadId, policy: RetentionPolicy): Result[Unit] = inner.prune(threadId, policy)
  def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int): Result[Vector[EventRecord]] =
    inner.eventsAfter(threadId, afterSeq, limit)
  override def awaitEventsAfter(
    threadId: ThreadId,
    afterSeq: Long,
    limit: Int,
    timeout: FiniteDuration
  ): Result[Vector[EventRecord]] = inner.awaitEventsAfter(threadId, afterSeq, limit, timeout)
  def children(parent: ThreadId, after: Option[ThreadId], limit: Int): Result[Vector[ChildThread]] =
    inner.children(parent, after, limit)
  def threads(query: ThreadQuery): Result[ThreadPage] = inner.threads(query)
  def incompleteThreads(tenantId: Option[TenantId], after: Option[ThreadCursor], limit: Int): Result[ThreadPage] =
    inner.incompleteThreads(tenantId, after, limit)
  def latestOfRuns(threadId: ThreadId, runIds: Set[RunId]): Result[Option[Checkpoint]] =
    inner.latestOfRuns(threadId, runIds)
  def sweep(policy: SweepPolicy, after: Option[ThreadId], guard: SweepGuard): Result[SweepPage] =
    inner.sweep(policy, after, guard)
  def compactEvents(threadId: ThreadId, beforeSeq: Long): Result[Unit] = inner.compactEvents(threadId, beforeSeq)
  def deleteThread(threadId: ThreadId, token: FencingToken, fence: Option[(ThreadId, FencingToken)]): Result[Unit] =
    inner.deleteThread(threadId, token, fence)

@Experimental
object MeteredCheckpointer:

  /** The most runs begun and not yet ended a checkpointer remembers for their duration; the oldest is forgotten first. */
  val InFlightLimit: Int = 10000

  /** The `graph` label of a run whose graph a commit did not name. */
  private val UnknownGraph = "unknown"

  /** `inner`, recording the agent runtime's metrics into `metrics`. */
  def apply(inner: Checkpointer, metrics: MetricsCollector): MeteredCheckpointer =
    new MeteredCheckpointer(inner, metrics)

  /** A task's status as a label: `blocked:<guardrail>` is `blocked`, so a guardrail's name never becomes a series. */
  private[metrics] def taskStatus(status: String): String =
    if status.startsWith("blocked") then "blocked" else status
