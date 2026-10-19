package org.llm4s.agent.delegation

import org.llm4s.agent.{ Agent, AgentResult, AgentRun, AgentStatus }
import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.tool.ToolContext
import org.llm4s.agent.graph.toolloop.{ AgentInput, LoopKeys, TurnOutput }
import org.llm4s.error.{ CancelledError, LLMError, ValidationError }
import org.llm4s.llmconnect.model.UsageSummary
import org.llm4s.types.Result

import java.time.Duration as JDuration
import java.util.concurrent.TimeUnit
import scala.annotation.tailrec
import scala.concurrent.duration.*

/** How a child run ended, for the call that started it (design 4.42). Internal: D1 maps it to its `TaskOutcome`. */
private[agent] enum ChildEnd:

  /** The child's roll-up. */
  def usage: UsageSummary

  /**
   * The child's turn ended with `result`: completed, at a limit, blocked, or suspended. `usage` is the child's whole
   * life's model usage (its roll-up).
   */
  case Ended(result: AgentResult, usage: UsageSummary)

  /**
   * The child's turn failed with `error`. A `GraphError.OutputInvalid` (a typed child's answer failed its schema twice)
   * carries every error, as replay rebuilds it from `RunOutcome.OutputInvalid`; any other error is the child's
   * failure. `usage` is the child's roll-up.
   */
  case Failed(error: LLMError, usage: UsageSummary)

/**
 * Runs, or reattaches to, the child thread a tool call delegates to (design 4.42), on the parent's runtime.
 *
 * The child thread is the call's journal (rule 14): its id is derived from the origin's parent thread, call key and
 * agent ([[ThreadId.child]]), and every run of the call reads the child's latest checkpoint first:
 *
 *  - none: the child is started ([[GraphRuntime.startChild]]); a start that loses a race to another runtime reads
 *    again.
 *  - `Running`: the child is recovered ([[GraphRuntime.recoverChild]]), folding the usage of the attempts that
 *    committed nothing into [[DelegationKeys.priorUsage]]. A child whose claim another run holds is waited on, with
 *    backoff, for up to twice the claim's lifetime (no later than the parent's deadline), then the call returns that
 *    `ThreadBusy` as a `Left`, for the parent to fail and recover later.
 *  - `Suspended`: `Ended` with the `Suspended` status, nothing run.
 *  - `Completed` or `Failed`: the outcome the child's terminal checkpoint records, nothing run. A settled checkpoint
 *    without one is corrupt stored state, a `Left`.
 *
 * A child failure is `Failed`. With `settleOnFailure` (the caller's `ChildFailure.Report`, whose call commits the
 * failure as a tool error) the child is first settled ([[GraphRuntime.settleChild]]), so no child is left `Running`
 * behind a committed call, and the failure returned is the one replay rebuilds from the settled checkpoint. Without it
 * (`ChildFailure.Fail`) the child stays `Running`, for the parent's `recover` to reattach. A child stopped by the
 * deadline it took from its parent is left `Running` too, and the error returned as a `Left`: the parent's own run is
 * stopping.
 *
 * Interrupting the calling thread (the parent's cancel, its lost claim, a timeout on the call) cancels the child, waits
 * up to [[AgentRun.Drain]] for it to end, and returns `Left(CancelledError)` with the interrupt flag kept. Every other
 * `Left` is a refusal the call should fail on (`ToolOutcome.Fatal`).
 *
 * The child's usage (its committed `usage` key, its prior usage, and the lost usage on terminal events not yet
 * folded) is added to the calling task's accumulator by the result its call commits: a completed report, and with
 * `settleOnFailure` any other settled end. A suspended child, a refusal without `settleOnFailure` (it fails the
 * parent run) and a `Left` add nothing, because their call commits nothing final, and a later run of it adds the
 * child's whole life once. The exception is a child cancelled with the calling task: its usage so far is added once it
 * has drained, because a timeout on the call commits the call's error with the accumulator (a cancelled parent task
 * commits nothing, and its replay counts the child once).
 */
private[agent] object ChildRunner:
  /** The first pause while a child's claim is held elsewhere; it doubles up to [[MaxBackoff]]. */
  val InitialBackoff: FiniteDuration = 10.millis

  /** The longest pause between attempts on a busy child. */
  val MaxBackoff: FiniteDuration = 500.millis

  /** How many events a usage scan reads at a time. */
  private val EventPage: Int = 256

  /**
   * Runs `agent` on the child thread `origin` names, or reattaches to it, with `input` as its turn's input and
   * `config`'s budgets (its timeout capped by the parent's deadline), for the tool call `ctx`. See the object's
   * description.
   */
  def run(
    agent: Agent,
    origin: RunOrigin,
    input: AgentInput,
    config: RunConfig,
    ctx: ToolContext,
    settleOnFailure: Boolean = true
  ): Result[ChildEnd] =
    new Attempt(agent, origin, input, config, ctx, settleOnFailure).drive()

  /**
   * The child's timeout: its grant, capped by what is left of the parent's deadline (`deadline`, by the runtime's
   * clock at `now`), and whether the parent's deadline is the one that applies. A deadline already passed leaves a
   * millisecond, so the child stops at once with the parent.
   */
  def cappedTimeout(
    granted: Option[FiniteDuration],
    deadline: Option[java.time.Instant],
    now: java.time.Instant
  ): (Option[FiniteDuration], Boolean) =
    val remaining = deadline.map(d => JDuration.between(now, d).toNanos.max(1.millis.toNanos).nanos)
    remaining match
      case None                                                            => (granted, false)
      case Some(left) if granted.forall(left.lt) || granted.contains(left) => (Some(left), true)
      case Some(_)                                                         => (granted, false)

  /** The step of an attempt: done, read the child again, or wait for a busy child and read it again. */
  private enum Step:
    case Done(result: Result[ChildEnd])
    case Again
    case Busy(error: GraphError.ThreadBusy)

  final private class Attempt(
    agent: Agent,
    origin: RunOrigin,
    input: AgentInput,
    config: RunConfig,
    ctx: ToolContext,
    settleOnFailure: Boolean
  ):
    private val runtime = agent.runtime
    private val graph   = agent.loop.graph
    private val childId = ThreadId.child(origin.parent, origin.agent, origin.call)
    private val parent  = ctx.run.config

    /**
     * The child's config, without its timeout: identity from the parent (rule 16) and the agent's breakpoints. Reads
     * use it; each admission adds the timeout ([[fresh]]).
     */
    private val base: RunConfig =
      agent.configured(config.withTenantId(parent.tenantId).withPrincipal(parent.principal))

    /** Whether the latest admission's timeout was the parent's deadline rather than the grant. */
    @volatile private var parentsDeadline = false

    /**
     * A new run's config: each admission is a run of its own, with the timeout capped by what is left of the parent's
     * deadline now, so time spent waiting for a busy child never lets the child outlive its parent.
     */
    private def fresh(): RunConfig =
      val (timeout, capped) = cappedTimeout(config.budgets.timeout, ctx.run.deadline, runtime.now())
      parentsDeadline = capped
      base.withBudgets(config.budgets.withTimeout(timeout)).withRunId(RunId.random())

    /**
     * When waiting on a busy child gives up, as a `System.nanoTime` value: twice the claim's life from now, or the
     * parent's deadline if that is sooner. Fixed at the first `ThreadBusy`.
     */
    private def busyCutoff(): Long =
      val claims = runtime.claimTtl * 2
      val window =
        ctx.run.deadline.fold(claims)(d => claims.min(JDuration.between(runtime.now(), d).toNanos.max(0L).nanos))
      System.nanoTime() + window.toNanos

    def drive(): Result[ChildEnd] =
      @tailrec def loop(cutoff: Option[Long], waits: Int): Result[ChildEnd] =
        if Thread.currentThread().isInterrupted then Left(CancelledError("child run"))
        else
          step() match
            case Step.Done(result) => result
            case Step.Again        => loop(cutoff, waits)
            case Step.Busy(error) =>
              val until = cutoff.getOrElse(busyCutoff())
              pause(until, waits, error) match
                case Left(stop) => Left(stop)
                case Right(_)   => loop(Some(until), waits + 1)
      loop(None, 0)

    /** Waits before the next read of a busy child; `Left` once `until` has passed, or when interrupted. */
    private def pause(until: Long, waits: Int, busy: GraphError.ThreadBusy): Result[Unit] =
      val left = until - System.nanoTime()
      if left <= 0 then Left(busy)
      else
        val backoff = (InitialBackoff.toNanos << waits.min(16)).min(MaxBackoff.toNanos)
        CancelledError.catchInterrupt(TimeUnit.NANOSECONDS.sleep(backoff.min(left))) match
          case Left(_) =>
            Thread.currentThread().interrupt()
            Left(CancelledError("child run"))
          case Right(_) => Right(())

    /** One read of the child and what it calls for. */
    private def step(): Step =
      runtime.childAtRest(childId, graph, base) match
        case Left(error) => Step.Done(Left(error))
        case Right(None) =>
          admitted(runtime.startChild(childId, graph, input, origin, fresh(), notAfter = ctx.run.deadline))
        // a thread at the child's id that is not this call's child - a top-level thread named like one, or a child of
        // another origin - is never adopted: its answer is not this call's
        case Right(Some((checkpoint, _))) if !ours(checkpoint) =>
          Step.Done(
            Left(
              ValidationError(
                "threadId",
                s"'${childId.value}' exists and is not the child thread of call ${origin.call.value} on '${origin.parent.value}'"
              )
            )
          )
        case Right(Some((checkpoint, exec))) =>
          checkpoint.status match
            case CheckpointStatus.Running =>
              admitted(
                runtime.recoverChild(childId, graph, fresh(), amend = foldLostUsage, notAfter = ctx.run.deadline)
              )
            case CheckpointStatus.Suspended =>
              Step.Done(
                AgentRun
                  .status(runtime.suspendedResult(graph, exec), isBlock = false, agent.loop)
                  .flatMap(status => rebuilt(checkpoint, exec, status))
                  .flatMap(result => usage().map(ChildEnd.Ended(result, _)))
              )
            case CheckpointStatus.Completed | CheckpointStatus.Failed => Step.Done(settled(checkpoint, exec))

    /** Whether the parent's deadline has passed, by the runtime's clock: a child timing out then timed out with it. */
    private def parentDeadlinePassed: Boolean = ctx.run.deadline.exists(d => !runtime.now().isBefore(d))

    /** Whether `checkpoint` is this call's child's: its origin names this call's parent, call and agent. */
    private def ours(checkpoint: Checkpoint): Boolean =
      checkpoint.origin.exists(o => o.parent == origin.parent && o.call == origin.call && o.agent == origin.agent)

    /** What an admission of the child calls for: its run awaited, or a refusal that reads the child again. */
    private def admitted(handle: Result[RunHandle[TurnOutput]]): Step = handle match
      // awaited even if the interrupt came after the admission: an admitted child is always cancelled with its parent
      case Right(h) => ended(Agent.awaitBlocking(agent.agentRun(h, None, drain = false)))
      // another runtime started it, settled it, or moved it on between the read and the admission
      case Left(
            _: GraphError.ThreadExists | _: GraphError.NothingToRecover | _: GraphError.IncompleteRun |
            _: GraphError.PendingInterrupts
          ) =>
        Step.Again
      case Left(busy: GraphError.ThreadBusy) => Step.Busy(busy)
      case Left(error)                       => Step.Done(Left(error))

    /** What the end of a child's live run calls for. */
    private def ended(outcome: Result[AgentResult]): Step = outcome match
      case Right(result) => Step.Done(usage().map(total => counted(ChildEnd.Ended(result, total))))
      // cancelled with the calling task, and drained: the child's whole life so far goes into the task's accumulator,
      // unsettled, so a recover still continues the child. A parent cancel or lost claim fails the task, which commits
      // nothing, and the replay that reattaches counts the child once (its fold covers this RunCancelled); a timeout on
      // the call commits its error with the accumulator, once, and that call never runs again
      case Left(cancelled: CancelledError) =>
        usage().foreach(ctx.run.usage.merge)
        Step.Done(Left(cancelled))
      // the parent's own deadline stopped the child: the parent is stopping too, and its recover reattaches
      // (decided after the run as well as before it: a slow admission can make the parent's the sooner one)
      case Left(late: GraphError.DeadlineExceeded) if parentsDeadline || parentDeadlinePassed => Step.Done(Left(late))
      // a Block: the child's run settled it with its outcome, which is rebuilt as replay rebuilds it
      case Left(invalid: GraphError.OutputInvalid) =>
        runtime.childAtRest(childId, graph, base) match
          case Right(Some((checkpoint, exec))) if checkpoint.outcome.isDefined => Step.Done(settled(checkpoint, exec))
          case _ => Step.Done(usage().map(total => counted(ChildEnd.Failed(invalid, total))))
      case Left(error) if settleOnFailure => settle(error)
      case Left(error)                    => Step.Done(usage().map(total => counted(ChildEnd.Failed(error, total))))

    /** Settles the failed child, then reads it again: the failure returned is the one its checkpoint records. */
    private def settle(error: LLMError): Step =
      runtime.settleChild(
        childId,
        graph,
        RunOutcome.failed(error),
        error.message,
        base.withRunId(RunId.random()),
        foldLostUsage
      ) match
        case Right(_)                          => Step.Again
        case Left(busy: GraphError.ThreadBusy) => Step.Busy(busy)
        case Left(other)                       => Step.Done(Left(other))

    /** A settled child's outcome, rebuilt from its terminal checkpoint, with no run (rule 14). */
    private def settled(checkpoint: Checkpoint, exec: Execution): Result[ChildEnd] =
      checkpoint.outcome match
        case None =>
          Left(
            GraphError.RestoreRejected(
              graph.id,
              List(s"child thread '${childId.value}' is ${checkpoint.status} with no recorded outcome")
            )
          )
        case Some(outcome) =>
          AgentRun.settledStatus(outcome, exec.state) match
            case Right(status) =>
              for
                result <- rebuilt(checkpoint, exec, status)
                total  <- usage()
              yield counted(ChildEnd.Ended(result, total))
            case Left(error) => usage().map(total => counted(ChildEnd.Failed(error, total)))

    /**
     * `childEnd`, its roll-up added to the calling task's accumulator when the call commits it as its result: a report, and
     * under `settleOnFailure` a refusal. A suspended child's call commits nothing final, and without `settleOnFailure`
     * a refusal fails the parent run, so neither adds anything: the call's later run adds the child's whole life once.
     */
    private def counted(childEnd: ChildEnd): ChildEnd =
      val commits = childEnd match
        case ChildEnd.Ended(result, _) =>
          result.status match
            case _: AgentStatus.Suspended => false
            case _: AgentStatus.Completed => true
            case _                        => settleOnFailure
        case ChildEnd.Failed(_, _) => settleOnFailure
      // also when the calling task was interrupted as the child committed: a task that then fails commits nothing, and
      // a timed-out call commits its error with the accumulator
      if commits then ctx.run.usage.merge(childEnd.usage)
      childEnd

    private def rebuilt(checkpoint: Checkpoint, exec: Execution, status: AgentStatus): Result[AgentResult] =
      AgentRun.result(childId, RunId(checkpoint.runId), agent.id, exec.state, None, status)

    /**
     * The child's roll-up (rules 10, 11): its committed usage, its prior usage, and the lost usage on the terminal
     * events after the last one folded - the final attempt's, or one settled or recovered after it.
     */
    private def usage(): Result[UsageSummary] =
      runtime.childAtRest(childId, graph, base).flatMap {
        case None => Right(UsageSummary())
        case Some((_, exec)) =>
          for
            committed <- exec.state.get(LoopKeys.usage)
            prior     <- exec.state.get(DelegationKeys.priorUsage)
            lost      <- terminalsAfter(prior.foldedThrough)
          yield committed.merge(prior.total).merge(lost.foldLeft(UsageSummary())((sum, t) => sum.merge(t._2)))
      }

    /**
     * [[recoverChild]]'s and [[settleChild]]'s amendment, under the child's claim: the lost usage of every terminal
     * event after `foldedThrough` folded into the prior usage, which commits with the claim's checkpoint.
     */
    private val foldLostUsage: ThreadState => Result[StateUpdate] = state =>
      for
        prior     <- state.get(DelegationKeys.priorUsage)
        terminals <- terminalsAfter(prior.foldedThrough)
      yield
        val folded = terminals.foldLeft(prior)((p, t) => p.fold(t._1, t._2))
        if folded == prior then StateUpdate.empty else StateUpdate.update(DelegationKeys.priorUsage, folded)

    /** The child's terminal events of attempts that committed nothing after `afterSeq`: each one's seq and usage. */
    private def terminalsAfter(afterSeq: Long): Result[Vector[(Long, UsageSummary)]] =
      @tailrec def pages(after: Long, found: Vector[(Long, UsageSummary)]): Result[Vector[(Long, UsageSummary)]] =
        runtime.storedEvents(childId, after, EventPage) match
          case Left(error) => Left(error)
          case Right(page) =>
            val terminals = page.flatMap { record =>
              val lost = record.event match
                case RunEvent.RunFailed(_, lost) => Some(lost)
                case RunEvent.RunCancelled(lost) => Some(lost)
                case RunEvent.RunTimedOut(lost)  => Some(lost)
                case _                           => None
              lost.map(l => record.seq -> l.getOrElse(UsageSummary()))
            }
            if page.size < EventPage then Right(found ++ terminals)
            else pages(page.last.seq, found ++ terminals)
      pages(afterSeq, Vector.empty)
