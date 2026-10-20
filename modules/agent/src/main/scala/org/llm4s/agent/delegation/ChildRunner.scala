package org.llm4s.agent.delegation

import org.llm4s.agent.{ Agent, AgentResult, AgentRun, AgentStatus, RunScope }
import org.llm4s.agent.events.{ SubagentEvents, TaskStarted }
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
 *  - `Suspended`: `Ended` with the `Suspended` status, nothing run - unless the call is a resume with answers to
 *    interrupts the child still waits on, which resume it ([[GraphRuntime.resumeChild]], design 4.46). Answers the
 *    child no longer waits on were applied by an earlier attempt of the resume: the child is reattached instead.
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
 *
 * Its events reach the parent's subscribers (design 4.47). It admits the child with a forwarder observing the child's
 * run from its claim, so not even a fast child's first event is missed: on the claim it sends the parent's live
 * `SubagentEvents.TaskStarted` for the admitted run, then each event of the run, a grandchild's included, goes to the
 * parent's nested subscribers as `StreamEvent.Nested`, queued behind it, and the run's await waits for the forwarder
 * to pass on its terminal event, so the call's result follows it. Only a child this call admits fresh is gap-free: for
 * any other child run it did not forward - one an earlier attempt left `Running` or `Suspended`, one another runtime
 * holds, one already settled - the parent's nested subscribers get a `TaskStarted` naming that run and one
 * `Nested(child, LiveGap(0))`, then the events of any run this call drives.
 */
private[agent] object ChildRunner:
  /** The first pause while a child's claim is held elsewhere; it doubles up to [[MaxBackoff]]. */
  val InitialBackoff: FiniteDuration = 10.millis

  /** The longest pause between attempts on a busy child. */
  val MaxBackoff: FiniteDuration = 500.millis

  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)

  /** How many events a usage scan reads at a time. */
  private val EventPage: Int = 256

  /**
   * Runs `agent` on the child thread `origin` names, or reattaches to it, with `input` as its turn's input, for the
   * tool call `ctx`. See the object's description.
   *
   * `config`'s budgets and `stepLimit` are the child's grant for its whole life (design 4.45), not for one admission:
   *
   *  - its `maxTokens`, less the tokens the child used before (its roll-up), at each admission, and at most
   *    `parentTokens`, the tokens the parent's current run has left (one run's allowance, never recorded: at the first
   *    admission the lower of the two is the whole-life limit);
   *  - its `timeout`, as a deadline of the child's `RunOrigin.startedAt` (as its first checkpoint recorded it) plus
   *    the timeout, capped by the parent's deadline;
   *  - `stepLimit`, the cap on the child's turn steps, which a recovered child keeps counting.
   *
   * The first admission records the step cap, the token limit, the granted deadline and the concurrency on the child
   * ([[DelegationKeys.grant]]); each later one takes the lower of each recorded limit and its own, so a recovery under
   * a looser budget never widens the grant. The deadline is fixed at the first admission - its start plus the granted
   * timeout, or the parent's deadline then if sooner - and a later parent run's deadline only caps an admission, never
   * moves it. The steps a failed attempt's model calls took, which committed no step, count against the step cap.
   *
   * A child recovered or resumed whose tokens or steps are already spent is settled `TokenLimitReached` or
   * `StepLimitReached` without running, and one whose own granted time has passed is settled `Failed` with a
   * `DeadlineExceeded`, as a child timing out mid-run records it, whatever the failure policy.
   */
  def run(
    agent: Agent,
    origin: RunOrigin,
    input: AgentInput,
    config: RunConfig,
    ctx: ToolContext,
    settleOnFailure: Boolean = true,
    stepLimit: Option[Int] = None,
    parentTokens: Option[Long] = None
  ): Result[ChildEnd] =
    new Attempt(agent, origin, input, config, ctx, settleOnFailure, None, stepLimit, parentTokens).drive()

  /**
   * [[run]] for a call resumed with `answers` to its suspended child's interrupts (design 4.46): the child, read first
   * as [[run]] reads it, is resumed with the answers it still waits on - composite ones grouped for its own `task`
   * calls - and awaited. A child that no longer waits on any of them (an earlier attempt of this resume applied them)
   * is reattached as [[run]] does, with no second resume. A child thread that no longer exists is a `ValidationError`.
   */
  def resume(
    agent: Agent,
    origin: RunOrigin,
    input: AgentInput,
    answers: DelegatedAnswers,
    config: RunConfig,
    ctx: ToolContext,
    settleOnFailure: Boolean,
    stepLimit: Option[Int] = None,
    parentTokens: Option[Long] = None
  ): Result[ChildEnd] =
    new Attempt(agent, origin, input, config, ctx, settleOnFailure, Some(answers.answers), stepLimit, parentTokens)
      .drive()

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
    settleOnFailure: Boolean,
    answers: Option[Map[InterruptId, ujson.Value]],
    stepLimit: Option[Int],
    parentTokens: Option[Long]
  ):
    private val runtime = agent.runtime
    private val graph   = agent.loop.graph
    private val childId = ThreadId.child(origin.parent, origin.agent, origin.call)
    private val parent  = ctx.run.config

    /** The parent run's claim token: every admission and settlement of the child is fenced by it (#2071). */
    private val parentClaim = ctx.run.position.fencingToken

    /**
     * The child's config, without its timeout: identity from the parent (rule 16) and the agent's breakpoints. Reads
     * use it; each admission adds the timeout ([[fresh]]).
     */
    private val base: RunConfig =
      agent.configured(config.withTenantId(parent.tenantId).withPrincipal(parent.principal))

    /** Whether this attempt resumed the child: it resumes it at most once. */
    private var applied = false

    /** Whether the latest admission's timeout was the parent's deadline rather than the grant. */
    @volatile private var parentsDeadline = false

    /** The run this attempt last sent `TaskStarted` for. */
    @volatile private var announced = Option.empty[RunId]

    /** The child runs whose events this attempt forwarded, or reported a gap for: each is in the merged stream. */
    private var streamed = Set.empty[RunId]

    /** The child as the parent's events name it, for run `runId`. */
    private def ref(runId: RunId): ChildRef = ChildRef(childId, runId, origin.agent, Some(ctx.toolCallId), origin.depth)

    /** Sends the parent's live `TaskStarted` for the child's run `runId`. */
    private def announce(runId: RunId): Unit =
      val started = SubagentEvents.TaskStarted
      // remembered as running until the call ends, so a nested subscriber attaching mid-child gets it and a gap (#2074)
      ctx.run.childStarted(ref(runId), started.name, started.version, started.encode(TaskStarted(ref(runId))))
      announced = Some(runId)

    /**
     * The live events of the child's run `runId` cannot reach the parent's subscribers from here: `TaskStarted` for it
     * (unless the last one named it) and one `Nested(child, LiveGap(0))`, once per run, so the gap always follows a start
     * naming the same run. Durable events are kept, and `Agent.subscribeChild` replays them.
     */
    private def gap(runId: RunId): Unit =
      if !streamed.contains(runId) then
        if !announced.contains(runId) then announce(runId)
        ctx.run.nested(StreamEvent.Nested(ref(runId), StreamEvent.LiveGap(0)))
        streamed += runId

    /**
     * The forwarder a child run `runId` is admitted with: it passes on every event of the run to the parent's nested
     * subscribers, a grandchild's included; a disconnection (it fell behind) as a gap. It sends `TaskStarted` first, on
     * the run's first event - its claim, so only a run that was admitted is announced - from the same thread as the
     * events, so the start is queued ahead of them. A refused admission abandons the observer, which then sends nothing.
     */
    private def forwarder(runId: RunId): (Observer, RunScope) =
      val child = ref(runId)
      val first = new java.util.concurrent.atomic.AtomicBoolean(true)
      val scope = RunScope(
        runId,
        event =>
          if first.getAndSet(false) then announce(runId)
          event match
            case _: StreamEvent.Disconnected => ctx.run.nested(StreamEvent.Nested(child, StreamEvent.LiveGap(0)))
            case other                       => ctx.run.nested(StreamEvent.Nested(child, other))
      )
      (Observer(Agent.StreamCapacity, scope, nested = true), scope)

    /**
     * This call's whole-life grant for a child started at `startedAt`: its step cap, token limit, granted deadline and
     * concurrency. A first admission also caps the tokens by `parentTokens`, the parent run's allowance, since nothing
     * is used yet; a later one does not, because that allowance is one run's, not the child's life's.
     */
    private def granted(startedAt: java.time.Instant, first: Boolean): ChildGrant =
      val tokens = if first then lower(config.budgets.maxTokens, parentTokens) else config.budgets.maxTokens
      val own    = config.budgets.timeout.map(t => startedAt.plusNanos(t.toNanos))
      // the first admission fixes the deadline, the parent's then included; a later parent run never moves it
      val deadline = if first then lower(own, ctx.run.deadline) else own
      ChildGrant(stepLimit, tokens, deadline, Some(config.budgets.maxConcurrency))

    private def lower[A](a: Option[A], b: Option[A])(using ord: Ordering[A]): Option[A] = (a ++ b).reduceOption(ord.min)

    /** The grant a child in `state` runs under: the one recorded on it, narrowed by this call's. */
    private def effective(state: ThreadState, startedAt: java.time.Instant): Result[ChildGrant] =
      state
        .get(DelegationKeys.grant)
        .map(_.fold(granted(startedAt, first = true))(_.narrowed(granted(startedAt, first = false))))

    /**
     * A new run's config: each admission is a run of its own, with what is left of the child's `grant` (design 4.45) -
     * the time to its deadline, capped by what is left of the parent's deadline now, so time spent waiting for a busy
     * child never lets the child outlive its parent, and its tokens less `consumed`, the tokens the child's earlier
     * attempts used, at most the parent run's allowance. A spent grant is settled before this is called; what is left
     * is kept positive regardless.
     */
    private def fresh(grant: ChildGrant, consumed: Long): RunConfig =
      val now               = runtime.now()
      val ownTime           = grant.deadline.map(d => JDuration.between(now, d).toNanos.max(1.millis.toNanos).nanos)
      val (timeout, capped) = cappedTimeout(ownTime, ctx.run.deadline, now)
      parentsDeadline = capped
      val tokens      = lower(grant.tokens.map(_ - consumed), parentTokens).map(_.max(1L))
      val concurrency = grant.concurrency.getOrElse(config.budgets.maxConcurrency)
      base
        .withBudgets(config.budgets.withTimeout(timeout).withMaxTokens(tokens).withMaxConcurrency(concurrency))
        .withRunId(RunId.random())

    /** `grant` recorded on the child, with its admission. */
    private def recorded(grant: ChildGrant): StateUpdate = StateUpdate.update(DelegationKeys.grant, Some(grant))

    /** Whether `grant`'s own deadline has passed by the runtime's clock. */
    private def grantExpired(grant: ChildGrant): Boolean = grant.deadline.exists(d => !runtime.now().isBefore(d))

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
        case Right(None) if answers.isDefined =>
          Step.Done(
            Left(
              ValidationError(
                "threadId",
                s"the child thread '${childId.value}' no longer exists, so its interrupts cannot be answered"
              )
            )
          )
        case Right(None) =>
          val run                = fresh(granted(origin.startedAt, first = true), 0L)
          val (observer, events) = forwarder(run.runId)
          admitted(
            runtime.startChild(
              childId,
              graph,
              input,
              origin,
              run,
              observer = Some(observer),
              notAfter = ctx.run.deadline,
              amend = _ =>
                Right(
                  StateUpdate
                    .update(DelegationKeys.input, Some(inputDigest))
                    .combine(recorded(granted(origin.startedAt, first = true)))
                ),
              // the child is registered only while the parent run still holds its claim (#2071)
              parentClaim = parentClaim
            ),
            events
          )
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
        case Right(Some((_, exec))) if !sameInput(exec.state) =>
          Step.Done(
            Left(
              ValidationError(
                "input",
                s"the child thread '${childId.value}' was started with other input; a delegated call's arguments " +
                  "cannot change once its child has started"
              )
            )
          )
        case Right(Some((checkpoint, exec))) =>
          checkpoint.status match
            case CheckpointStatus.Running =>
              // an earlier attempt's run: its live events cannot be replayed, so a gap comes before this run's events
              gap(RunId(checkpoint.runId))
              recovered(checkpoint, exec)
            case CheckpointStatus.Suspended =>
              answering(exec) match
                case Some(pending) =>
                  // the run that suspended it was an earlier attempt's: a gap, then the resumed run's events
                  gap(RunId(checkpoint.runId))
                  resumed(checkpoint, exec, pending)
                case None =>
                  // nothing runs here, so the child's live events are gone
                  gap(RunId(checkpoint.runId))
                  Step.Done(
                    AgentRun
                      .status(runtime.suspendedResult(graph, exec), isBlock = false, agent.loop)
                      .flatMap(status => rebuilt(checkpoint, exec, status))
                      .flatMap(result => usage().map(ChildEnd.Ended(result, _)))
                  )
            case CheckpointStatus.Completed | CheckpointStatus.Failed =>
              // settled by a run whose events this attempt did not forward - an earlier run (a replay after a crash),
              // or this attempt's settlement of a failed child - so the run that names the outcome gets a gap
              gap(RunId(checkpoint.runId))
              Step.Done(settled(checkpoint, exec))

    /**
     * The answers this resume gives the suspended child `exec` (design 4.46): composite ones grouped for its own `task`
     * calls, kept only for interrupts it still waits on. `None` once this attempt resumed it, for a call that is not a
     * resume, when none is still pending - an earlier attempt applied them - or when they do not group.
     */
    private def answering(exec: Execution): Option[Map[InterruptId, ujson.Value]] =
      if applied then None
      else
        answers.flatMap { all =>
          val parked = runtime.suspendedResult(graph, exec) match
            case suspended: RunResult.Suspended => suspended.interrupts.map(_.id).toSet
            case _                              => Set.empty[InterruptId]
          DelegatedAnswers.group(all).toOption.map(_.filter((id, _) => parked.contains(id))).filter(_.nonEmpty)
        }

    /**
     * What resuming the child with `pending` calls for: its run awaited; for answers the child refuses, the child read
     * again and its interrupts surfaced again, with a warning; any other refusal as [[admitted]] takes it.
     */
    private def resumed(checkpoint: Checkpoint, exec: Execution, pending: Map[InterruptId, ujson.Value]): Step =
      // a continuation like a recovery: with what is left of the child's whole-life grant (design 4.45)
      continued(checkpoint, exec) { (grant, consumed) =>
        val run                = fresh(grant, consumed)
        val (observer, events) = forwarder(run.runId)
        resumeWith(
          runtime.resumeChild(
            childId,
            graph,
            pending,
            run,
            observer = Some(observer),
            notAfter = ctx.run.deadline,
            parentClaim = parentClaim,
            amend = _ => Right(recorded(grant))
          ),
          events
        )
      }

    private def resumeWith(handle: Result[RunHandle[TurnOutput]], events: RunScope): Step =
      handle match
        case handle @ Right(_) =>
          applied = true
          admitted(handle, events)
        case Left(invalid: GraphError.InvalidResume) =>
          applied = true
          logger.warn(
            s"The child thread ${childId.value} refused the answers given through its parent: " +
              s"${invalid.problems.mkString("; ")}; its interrupts are asked again"
          )
          Step.Again
        case refused => admitted(refused, events)

    /** What a `Running` child calls for (design 4.45): a recovery, under [[continued]]. */
    private def recovered(checkpoint: Checkpoint, exec: Execution): Step =
      continued(checkpoint, exec) { (grant, consumed) =>
        val run                = fresh(grant, consumed)
        val (observer, events) = forwarder(run.runId)
        admitted(
          runtime.recoverChild(
            childId,
            graph,
            run,
            observer = Some(observer),
            amend = state => foldLostUsage(state).map(_.combine(recorded(grant))),
            notAfter = ctx.run.deadline,
            parentClaim = parentClaim
          ),
          events
        )
      }

    /**
     * Every continuation of a child - a recovery or a resume - under its whole-life grant (design 4.45): settled
     * `TokenLimitReached` or `StepLimitReached` at a spent token or step grant, settled `Failed` with a
     * `DeadlineExceeded` once its stored deadline has passed, each without running;
     * else `admit` with the grant and the tokens the child has used, which [[fresh]] deducts.
     */
    private def continued(checkpoint: Checkpoint, exec: Execution)(admit: (ChildGrant, Long) => Step): Step =
      val startedAt = checkpoint.origin.fold(origin.startedAt)(_.startedAt)
      val spent = for
        grant  <- effective(exec.state, startedAt)
        rollUp <- usage()
        lost   <- lostSteps()
        turn   <- exec.state.get(LoopKeys.turn)
      yield
        val consumed = rollUp.inputTokens + rollUp.outputTokens
        // a failed attempt's model calls count as steps, though they never committed one
        val counted = grant.narrowed(grant.copy(lostSteps = lost))
        val outcome =
          if counted.tokens.exists(consumed >= _) then Some(RunOutcome.TokenLimitReached)
          else if counted.steps.exists(turn.steps + counted.lostSteps >= _) then Some(RunOutcome.StepLimitReached)
          else None
        (counted, consumed, outcome)
      spent match
        case Left(error) => Step.Done(Left(error))
        case Right((_, _, Some(limitHit))) =>
          val what = if limitHit == RunOutcome.TokenLimitReached then "token" else "step"
          settleSpent(limitHit, s"the child's $what grant is spent")
        case Right((grant, _, _)) if grantExpired(grant) =>
          // its stored deadline - from its grant or its first parent's cap - has passed, whatever deadline a recovered
          // parent has now: settled failed as a child timing out mid-run records it, never admitted with no time left
          val late = GraphError.DeadlineExceeded(childId.value, Some(checkpoint.id))
          settleSpent(RunOutcome.failed(late), s"the child's granted time is spent: ${late.message}")
        case Right((grant, consumed, None)) => admit(grant, consumed)

    /** Settles a child whose grant is spent with `outcome`, then reads it again: its refusal, with no run. */
    private def settleSpent(outcome: RunOutcome, message: String): Step =
      runtime.settleChild(
        childId,
        graph,
        outcome,
        message,
        base.withRunId(RunId.random()),
        foldLostUsage,
        ours,
        parentClaim
      ) match
        case Right(_)                          => Step.Again
        case Left(busy: GraphError.ThreadBusy) => Step.Busy(busy)
        case Left(other)                       => Step.Done(Left(other))

    /**
     * Adds the child's roll-up to the calling task's accumulator, unless the thread's committed state shows it counted
     * already: by this call's suspension, before the call ran again (`DelegationKeys.counted`).
     */
    private def add(rollUp: UsageSummary): Unit =
      val already = ctx.state.get(DelegationKeys.counted).toOption.exists(_.contains(childId.value))
      if !already then ctx.run.usage.merge(rollUp, childId)

    /** Whether the parent's deadline has passed, by the runtime's clock: a child timing out then timed out with it. */
    private def parentDeadlinePassed: Boolean = ctx.run.deadline.exists(d => !runtime.now().isBefore(d))

    /** The digest of the input this call delegates: its query and history, canonically encoded. */
    private lazy val inputDigest: String =
      val bytes = upickle.default.write(input).getBytes(java.nio.charset.StandardCharsets.UTF_8)
      java.security.MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

    /** Whether the child was started with this call's input; a child recorded with none is taken as started with it. */
    private def sameInput(state: ThreadState): Boolean =
      state.get(DelegationKeys.input).toOption.flatten.forall(_ == inputDigest)

    /** Whether `checkpoint` is this call's child's: its origin names this call's parent, call and agent. */
    private def ours(checkpoint: Checkpoint): Boolean =
      checkpoint.origin.exists(o => o.parent == origin.parent && o.call == origin.call && o.agent == origin.agent)

    /**
     * What an admission of the child calls for: its run awaited - with its forwarder `events` drained, so its terminal
     * event reaches the parent's subscribers before the call's result - or a refusal that reads the child again.
     */
    private def admitted(handle: Result[RunHandle[TurnOutput]], events: RunScope): Step = handle match
      // awaited even if the interrupt came after the admission: an admitted child is always cancelled with its parent
      case Right(h) =>
        streamed += h.runId
        val parent = Some(origin.parent -> ctx.run.position.runId)
        ended(Agent.awaitBlocking(agent.agentRun(h, Some(events), drain = true, parent = parent)))
      // another runtime started it, settled it, or moved it on between the read and the admission
      case Left(
            _: GraphError.ThreadExists | _: GraphError.NothingToRecover | _: GraphError.IncompleteRun |
            _: GraphError.PendingInterrupts | _: GraphError.NotSuspended
          ) =>
        Step.Again
      case Left(busy: GraphError.ThreadBusy) =>
        // another runtime (or run) holds the child: its live events cannot reach this one
        gap(busy.holder match
          case GraphError.BusyHolder.Run(runId) => runId
          case _                                => config.runId
        )
        Step.Busy(busy)
      case Left(error) => Step.Done(Left(error))

    /** What the end of a child's live run calls for. */
    private def ended(outcome: Result[AgentResult]): Step = outcome match
      case Right(result) => Step.Done(usage().map(total => counted(ChildEnd.Ended(result, total))))
      // cancelled with the calling task, and drained: the child's whole life so far goes into the task's accumulator,
      // unsettled, so a recover still continues the child. A parent cancel or lost claim fails the task, which commits
      // nothing, and the replay that reattaches counts the child once (its fold covers this RunCancelled); a timeout on
      // the call commits its error with the accumulator, once, and that call never runs again
      case Left(cancelled: CancelledError) =>
        usage().foreach(add)
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
        foldLostUsage,
        ours,
        parentClaim
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
      // also when the calling task was interrupted as the child committed: a task that then fails drops a roll-up (it is
      // never the run's lost usage, TaskUsage.merge), and a timed-out call commits its error with it
      if commits then add(childEnd.usage)
      childEnd

    private def rebuilt(checkpoint: Checkpoint, exec: Execution, status: AgentStatus): Result[AgentResult] =
      AgentRun.result(childId, RunId(checkpoint.runId), agent.id, exec.state, None, status)

    /**
     * The child's roll-up (rules 10, 11): its committed usage, its prior usage, and the lost usage on the terminal
     * events after the last one folded - the final attempt's, or one settled or recovered after it.
     */
    /**
     * The agent model steps of the child's attempts that committed nothing: its prior failed steps and those on the
     * terminals not yet folded. A middleware's model call (a summariser's) is not a step, so it is not counted.
     */
    private def lostSteps(): Result[Long] =
      runtime.childAtRest(childId, graph, base).flatMap {
        case None => Right(0L)
        case Some((_, exec)) =>
          for
            prior <- exec.state.get(DelegationKeys.priorUsage)
            lost  <- terminalsAfter(prior.foldedThrough)
          yield prior.failedSteps + lost.map(_._3).sum
      }

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
        val folded = terminals.foldLeft(prior)((p, t) => p.fold(t._1, t._2, t._3))
        if folded == prior then StateUpdate.empty else StateUpdate.update(DelegationKeys.priorUsage, folded)

    /**
     * The child's terminal events of attempts that committed nothing after `afterSeq`: each one's seq, usage and agent
     * model steps.
     */
    private def terminalsAfter(afterSeq: Long): Result[Vector[(Long, UsageSummary, Long)]] =
      @tailrec def pages(
        after: Long,
        found: Vector[(Long, UsageSummary, Long)]
      ): Result[Vector[(Long, UsageSummary, Long)]] =
        runtime.storedEvents(childId, after, EventPage) match
          case Left(error) => Left(error)
          case Right(page) =>
            val terminals = page.flatMap { record =>
              val lost = record.event match
                case RunEvent.RunFailed(_, lost, steps) => Some(lost -> steps)
                case RunEvent.RunCancelled(lost, steps) => Some(lost -> steps)
                case RunEvent.RunTimedOut(lost, steps)  => Some(lost -> steps)
                case _                                  => None
              lost.map((l, steps) => (record.seq, l.getOrElse(UsageSummary()), steps))
            }
            if page.size < EventPage then Right(found ++ terminals)
            else pages(page.last.seq, found ++ terminals)
      pages(afterSeq, Vector.empty)
