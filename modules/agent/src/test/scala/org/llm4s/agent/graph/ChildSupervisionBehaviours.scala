package org.llm4s.agent.graph

import org.llm4s.agent.{ Agent, AgentBuilder, AgentOutputSpec, AgentStatus, CompletionFixture }
import org.llm4s.agent.{ ScriptedLLMClient, SpecTools }
import org.llm4s.agent.SpecTools.{ call, calling }
import org.llm4s.agent.delegation.{ ChildEnd, ChildRunner, DelegationKeys, PriorUsage }
import org.llm4s.agent.events.SubagentEvents
import org.llm4s.agent.graph.middleware.{
  AgentMiddleware,
  GuardrailMiddleware,
  MiddlewareId,
  ModelRequest,
  ModelResponse
}
import org.llm4s.agent.graph.middleware.ModelContext
import org.llm4s.agent.graph.tool.{ AgentTool, ToolContent, ToolContext, ToolOutcome }
import org.llm4s.agent.graph.toolloop.AgentInput
import org.llm4s.agent.guardrails.builtin.LengthCheck
import org.llm4s.error.{ CancelledError, ProcessingError }
import org.llm4s.llmconnect.model.UsageSummary
import org.llm4s.types.Result
import org.scalatest.flatspec.{ AnyFlatSpec, AnyFlatSpecLike }
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.concurrent.{ CopyOnWriteArrayList, CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger, AtomicReference }
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Child-run supervision (design 4.42, #2046), on each store: a delegating tool runs a child agent through
 * [[ChildRunner]], and the specs crash, restart, cancel and time out around it. Each store's spec gives `storage`: a
 * function that opens the same storage again, as a restarted process would.
 */
trait ChildSupervisionBehaviours extends AnyFlatSpecLike with Matchers {

  /** A new storage: each call of the function it returns opens that same storage again. */
  protected def storage(): () => Checkpointer

  extension [A](result: Result[A])
    private def ok: A = result.fold(e => fail(s"expected Right, got ${e.message}"), identity)

  /** What a family's delegating tool does, shared by every runtime the family is rebuilt on. */
  final private class Setup(
    val parentClient: ScriptedLLMClient,
    val childClient: ScriptedLLMClient,
    val settle: Boolean = true,
    val child: AgentBuilder => AgentBuilder = identity,
    val childTools: Seq[AgentTool[?]] = Nil,
    val delegateTimeout: Option[FiniteDuration] = None,
    val parentMiddleware: Seq[AgentMiddleware] = Nil,
    val childConfig: RunConfig = RunConfig()
  ) {
    val crashAfterChild                   = new AtomicBoolean(false)
    val ends                              = new CopyOnWriteArrayList[Result[ChildEnd]]()
    val lastOrigin                        = new AtomicReference[RunOrigin]()
    def allEnds: Vector[Result[ChildEnd]] = ends.asScala.toVector
  }

  /** The tool result a child's end becomes; a `Fatal` fails the parent run. */
  private def outcome(setup: Setup, ended: Result[ChildEnd]): ToolOutcome = ended match {
    case Right(ChildEnd.Ended(r, _)) =>
      r.status match {
        case AgentStatus.Completed(answer) => ToolOutcome.Success(ToolContent.Json(ujson.Str(answer)))
        case other                         => ToolOutcome.Error(s"refused: $other")
      }
    case Right(ChildEnd.Failed(e, _)) if setup.settle => ToolOutcome.Error(e.message)
    case Right(ChildEnd.Failed(e, _))                 => ToolOutcome.Fatal(e)
    case Left(e)                                      => ToolOutcome.Fatal(e)
  }

  /** The parent `lead` and child `worker` agents on `runtime`. */
  private def family(runtime: GraphRuntime, setup: Setup): (Agent, Agent) = {
    val childBuilder = setup.child(Agent.builder("worker", setup.childClient).withRuntime(runtime))
    val child = (if (setup.childTools.isEmpty) childBuilder
                 else childBuilder.withTools(SpecTools.set(setup.childTools*)))
      .build()
      .ok
    val spec = setup.delegateTimeout.fold(SpecTools.spec("delegate"))(t => SpecTools.spec("delegate").withTimeout(t))
    val delegate = AgentTool(spec) { (args, ctx) =>
      val origin = RunOrigin(ctx.run.position.threadId, ctx.idempotencyKey, child.id, Instant.now(), 1)
      setup.lastOrigin.set(origin)
      val ended =
        ChildRunner.run(child, origin, AgentInput(args.text, Vector.empty), setup.childConfig, ctx, setup.settle)
      setup.ends.add(ended)
      if (setup.crashAfterChild.getAndSet(false)) ToolOutcome.Fatal(ProcessingError("crash", "after the child"))
      else outcome(setup, ended)
    }
    val parent =
      Agent
        .builder("lead", setup.parentClient)
        .withRuntime(runtime)
        .withTools(SpecTools.set(delegate))
        .withMiddleware(setup.parentMiddleware*)
        .build()
        .ok
    (parent, child)
  }

  /** The parent's model: delegate once, then compose. */
  private def delegating(text: String = "task"): ScriptedLLMClient =
    ScriptedLLMClient.of(calling(call("c1", "delegate", text)), CompletionFixture.simple("composed"))

  private def answers(texts: String*): ScriptedLLMClient = ScriptedLLMClient.of(texts.map(CompletionFixture.simple)*)

  /** The only child of `thread`. */
  private def onlyChild(runtime: GraphRuntime, thread: ThreadId): ChildThread = {
    val children = runtime.children(thread).ok
    children should have size 1
    children.head
  }

  private def events(store: Checkpointer, thread: ThreadId): Vector[RunEvent] =
    store.eventsAfter(thread, 0L, 10_000).ok.map(_.event)

  private def tokens(usage: UsageSummary): (Long, Long, Long) =
    (usage.requestCount, usage.inputTokens, usage.outputTokens)

  /** A tool that, while `failing` is set, fails the run (`Fatal`); otherwise it succeeds. */
  private def flaky(failing: AtomicBoolean): AgentTool[SpecTools.Text] =
    SpecTools.tool("flaky")((_, _) =>
      if (failing.get) ToolOutcome.Fatal(ProcessingError("flaky", "down"))
      else ToolOutcome.Success(ToolContent.Json(ujson.Str("up")))
    )

  /** A tool that, while `blocking` is set, sleeps until interrupted, counting down `entered` first. */
  private def hanging(blocking: AtomicBoolean, entered: CountDownLatch): AgentTool[SpecTools.Text] =
    SpecTools.tool("hang") { (_, _) =>
      if (blocking.get) {
        entered.countDown()
        try Thread.sleep(60_000)
        catch { case _: InterruptedException => Thread.currentThread().interrupt() }
      }
      ToolOutcome.Success(ToolContent.Json(ujson.Str("hung")))
    }

  private def eventually(what: String)(condition: => Boolean): Unit = {
    val deadline = System.nanoTime() + 10.seconds.toNanos
    var held     = condition
    while (!held && System.nanoTime() < deadline) {
      Thread.sleep(20)
      held = condition
    }
    withClue(what)(held shouldBe true)
  }

  behavior.of("A parent that crashes after its child completed")

  it should "on recover after a restart run no child model call and return the same report" in {
    val open  = storage()
    val setup = new Setup(delegating(), answers("report"))
    setup.crashAfterChild.set(true)
    val thread = ThreadId("crash-after-child")
    family(new GraphRuntime(open()), setup)._1.run(thread, "go").isLeft shouldBe true

    val recovered = family(new GraphRuntime(open()), setup)._1.recover(thread).ok
    recovered.answer shouldBe Some("composed")
    setup.childClient.callCount shouldBe 1
    // the child's call is counted once, by the replay that committed it, never as the failed attempt's lost usage
    tokens(recovered.usage) shouldBe ((3L, 30L, 60L))
    events(open(), thread).collect { case RunEvent.RunFailed(_, lost, _) => lost } shouldBe Vector(None)
    setup.allEnds should have size 2
    setup.allEnds(1) shouldBe setup.allEnds(0)
    setup.allEnds(0).ok shouldBe a[ChildEnd.Ended]
  }

  behavior.of("A parent whose child failed part-way")

  it should "recover the child from its checkpoint, not restart it" in {
    val open    = storage()
    val failing = new AtomicBoolean(true)
    val setup = new Setup(
      delegating(),
      ScriptedLLMClient.of(calling(call("k1", "flaky")), CompletionFixture.simple("report")),
      settle = false,
      childTools = Seq(flaky(failing))
    )
    val thread = ThreadId("crash-mid-child")
    family(new GraphRuntime(open()), setup)._1.run(thread, "go").isLeft shouldBe true
    val runtime = new GraphRuntime(open())
    onlyChild(runtime, thread).status shouldBe CheckpointStatus.Running

    failing.set(false)
    family(runtime, setup)._1.recover(thread).ok.answer shouldBe Some("composed")
    val child = onlyChild(runtime, thread)
    child.status shouldBe CheckpointStatus.Completed
    // the model call that asked for the tool ran once: recover re-ran only the failed tool call
    setup.childClient.callCount shouldBe 2
    val log = events(open(), child.threadId)
    log.count(_.isInstanceOf[RunEvent.RunStarted]) shouldBe 1
    log.count(_.isInstanceOf[RunEvent.RunRecovered]) shouldBe 1
  }

  /** Each settled outcome: its child setup, and what its end must be. */
  private val outcomes: Seq[(String, () => Setup, ChildEnd => Unit)] = Seq(
    (
      "an answer",
      () => new Setup(delegating(), answers("report")),
      {
        case ChildEnd.Ended(r, _) => r.status shouldBe AgentStatus.Completed("report")
        case other                => fail(s"unexpected $other")
      }
    ),
    (
      "the step limit",
      () =>
        new Setup(
          delegating(),
          ScriptedLLMClient.of(calling(call("k1", "flaky"))),
          child = _.withMaxSteps(1),
          childTools = Seq(flaky(new AtomicBoolean(false)))
        ),
      {
        case ChildEnd.Ended(r, _) => r.status shouldBe AgentStatus.StepLimitReached
        case other                => fail(s"unexpected $other")
      }
    ),
    (
      "the token limit",
      () =>
        new Setup(
          delegating(),
          ScriptedLLMClient.of(calling(call("k1", "flaky"))),
          childTools = Seq(flaky(new AtomicBoolean(false))),
          childConfig = RunConfig().withBudgets(RunBudgets.default.withMaxTokens(1L))
        ),
      {
        case ChildEnd.Ended(r, _) => r.status shouldBe AgentStatus.TokenLimitReached
        case other                => fail(s"unexpected $other")
      }
    ),
    (
      "a Block",
      () =>
        new Setup(
          delegating("a task far too long"),
          answers(),
          child = _.withMiddleware(new GuardrailMiddleware(Seq(new LengthCheck(1, 3)), Seq.empty))
        ),
      {
        case ChildEnd.Ended(r, _) => r.status shouldBe a[AgentStatus.Blocked]
        case other                => fail(s"unexpected $other")
      }
    ),
    (
      "an invalid typed answer",
      () =>
        new Setup(
          delegating(),
          answers("""{"customer":"Acme","total":"lots"}""", "not json"),
          child = _.withOutput(AgentOutputSpec.invoiceSchema)
        ),
      {
        case ChildEnd.Failed(e: GraphError.OutputInvalid, _) => e.errors should not be empty
        case other                                           => fail(s"unexpected $other")
      }
    ),
    (
      "a failure",
      () =>
        new Setup(
          delegating(),
          ScriptedLLMClient.of(calling(call("k1", "flaky"))),
          childTools = Seq(flaky(new AtomicBoolean(true)))
        ),
      {
        case ChildEnd.Failed(e, _) => e.message should include("down")
        case other                 => fail(s"unexpected $other")
      }
    )
  )

  outcomes.foreach { case (name, setupOf, check) =>
    behavior.of(s"A child that settled with $name")
    it should "give the same end after a restart, running nothing again" in {
      val open  = storage()
      val setup = setupOf()
      setup.crashAfterChild.set(true)
      val thread = ThreadId(s"settled-${name.replace(' ', '-')}")
      family(new GraphRuntime(open()), setup)._1.run(thread, "go").isLeft shouldBe true
      val calls = setup.childClient.callCount

      val runtime = new GraphRuntime(open())
      family(runtime, setup)._1.recover(thread).ok.answer shouldBe Some("composed")
      setup.childClient.callCount shouldBe calls
      setup.allEnds(1) shouldBe setup.allEnds(0)
      check(setup.allEnds(0).ok)
      onlyChild(runtime, thread).status should (be(CheckpointStatus.Completed).or(be(CheckpointStatus.Failed)))
    }
  }

  behavior.of("ChildFailure.Report")

  it should "settle a failed child, so no Running child is left behind a committed call" in {
    val open = storage()
    val setup = new Setup(
      ScriptedLLMClient.of(
        (1 to 3).flatMap(n => Seq(calling(call(s"c$n", "delegate")), CompletionFixture.simple(s"turn $n")))*
      ),
      ScriptedLLMClient.of((1 to 3).map(n => calling(call(s"k$n", "flaky")))*),
      childTools = Seq(flaky(new AtomicBoolean(true)))
    )
    val runtime = new GraphRuntime(open())
    val parent  = family(runtime, setup)._1
    val thread  = ThreadId("report-settles")
    (1 to 3).foreach { n =>
      parent.run(thread, s"turn $n", RunConfig().withChildRetention(1)).ok.answer shouldBe Some(s"turn $n")
      val children = runtime.children(thread).ok
      children.map(_.status).toSet shouldBe Set(CheckpointStatus.Failed)
      children should have size 1
    }
  }

  /**
   * A store whose first child commit closing a turn as output-invalid lands unsettled - `Running`, with no outcome -
   * so the child's run ends `OutputInvalid` with no recorded outcome to rebuild it from (#2079).
   */
  private def unsettlingOutputInvalid(open: () => Checkpointer): () => Checkpointer = {
    val dropped = new AtomicBoolean(false)
    () =>
      new ForwardingCheckpointer(open()) {
        override def commit(threadId: ThreadId, commit: Commit) = {
          val blocked = commit.checkpoint.filter(c =>
            c.origin.isDefined && c.outcome.exists(_.isInstanceOf[RunOutcome.OutputInvalid])
          )
          blocked match {
            case Some(c) if !dropped.getAndSet(true) =>
              super.commit(threadId, commit.withCheckpoint(c.withStatus(CheckpointStatus.Running).withOutcome(None)))
            case _ => super.commit(threadId, commit)
          }
        }
      }
  }

  private def invalidTypedChild(settle: Boolean): Setup =
    new Setup(
      delegating(),
      answers("""{"customer":"Acme","total":"lots"}""", "not json"),
      settle = settle,
      child = _.withOutput(AgentOutputSpec.invoiceSchema)
    )

  it should "settle an output-invalid child left unsettled, and replay its refusal with no model call (#2079)" in {
    val open  = unsettlingOutputInvalid(storage())
    val setup = invalidTypedChild(settle = true)
    setup.crashAfterChild.set(true)
    val thread = ThreadId("report-output-invalid")
    family(new GraphRuntime(open()), setup)._1.run(thread, "go").isLeft shouldBe true

    val runtime = new GraphRuntime(open())
    val child   = onlyChild(runtime, thread)
    child.status shouldBe CheckpointStatus.Failed
    open().latest(child.threadId).ok.map(_.checkpoint.outcome) match {
      case Some(Some(RunOutcome.OutputInvalid(errors))) => errors should not be empty
      case other                                        => fail(s"expected an OutputInvalid outcome, got $other")
    }

    family(runtime, setup)._1.recover(thread).ok.answer shouldBe Some("composed")
    setup.childClient.callCount shouldBe 2
    setup.allEnds should have size 2
    setup.allEnds(1) shouldBe setup.allEnds(0)
    setup.allEnds(0).ok match {
      case ChildEnd.Failed(e: GraphError.OutputInvalid, _) => e.errors should not be empty
      case other                                           => fail(s"unexpected $other")
    }
  }

  behavior.of("ChildFailure.Fail")

  it should "leave an output-invalid child it did not settle Running (#2079)" in {
    val open   = unsettlingOutputInvalid(storage())
    val setup  = invalidTypedChild(settle = false)
    val thread = ThreadId("fail-output-invalid")
    family(new GraphRuntime(open()), setup)._1.run(thread, "go").isLeft shouldBe true

    onlyChild(new GraphRuntime(open()), thread).status shouldBe CheckpointStatus.Running
    setup.allEnds should have size 1
    setup.allEnds.head.ok shouldBe a[ChildEnd.Failed]
  }

  behavior.of("A parent taken over before its child's first commit")

  it should "register no child, so a successor's cascade delete cannot leave one behind (#2071)" in {
    val open        = storage()
    val setup       = new Setup(delegating(), answers("report"))
    val thread      = ThreadId("taken-before-child")
    val parentToken = new AtomicReference[FencingToken]()
    val takenOver   = new AtomicBoolean(false)
    val store = new ForwardingCheckpointer(open()) {
      override def claim(threadId: ThreadId, request: ClaimRequest) = {
        val granted = super.claim(threadId, request)
        if (threadId == thread) granted.foreach(c => parentToken.set(c.token))
        granted
      }
      // the parent's claim is taken over after its run started the child, before the child's first commit
      override def commit(threadId: ThreadId, commit: Commit) = {
        if (threadId != thread && commit.checkpoint.exists(_.origin.isDefined) && !takenOver.getAndSet(true)) {
          val under = open()
          under.release(thread, parentToken.get).ok
          under.claim(thread, ClaimRequest(RunId("successor"), None, 1.minute)).ok: Unit
        }
        super.commit(threadId, commit)
      }
    }

    family(new GraphRuntime(store), setup)._1.run(thread, "go").isLeft shouldBe true

    takenOver.get shouldBe true
    open().children(thread, None, 10).ok shouldBe empty
    setup.childClient.callCount shouldBe 0
  }

  behavior.of("Two runtimes")

  it should "start one child when a start loses the race" in {
    val open  = storage()
    val setup = new Setup(delegating(), answers("report"))
    // the first read of the child finds nothing, as if another runtime had not yet committed it
    val staleOnce = new AtomicBoolean(true)
    val raced     = new AtomicInteger()
    val loser = new ForwardingCheckpointer(open()) {
      override def latest(threadId: ThreadId) =
        if (threadId.value.contains("/~task/") && staleOnce.getAndSet(false)) {
          raced.incrementAndGet()
          // the other runtime starts the child first
          val other    = new GraphRuntime(open())
          val (_, kid) = family(other, new Setup(answers(), setup.childClient))
          other
            .startChild(threadId, kid.loop.graph, AgentInput("task", Vector.empty), setup.lastOrigin.get, RunConfig())
            .ok
            .await()
            .ok
          Right(None)
        } else super.latest(threadId)
    }
    val thread = ThreadId("race")
    family(new GraphRuntime(loser), setup)._1.run(thread, "go").ok.answer shouldBe Some("composed")
    raced.get shouldBe 1
    setup.childClient.callCount shouldBe 1
    setup.allEnds.head.ok match {
      case ChildEnd.Ended(r, _) => r.status shouldBe AgentStatus.Completed("report")
      case other                => fail(s"unexpected $other")
    }
  }

  behavior.of("A thread at the child's id that is not the call's child")

  it should "never be adopted" in {
    val open  = storage()
    val setup = new Setup(delegating(), answers("not the child's answer"))
    // before the runner's first read, a top-level thread is run at the child's id
    val planted = new AtomicBoolean(false)
    val store = new ForwardingCheckpointer(open()) {
      override def latest(threadId: ThreadId) = {
        if (threadId.value.contains("/~task/") && planted.compareAndSet(false, true)) {
          val (_, kid) = family(new GraphRuntime(open()), new Setup(answers(), setup.childClient))
          kid.run(threadId, "planted").ok
        }
        super.latest(threadId)
      }
    }
    val thread = ThreadId("planted")
    val failed = family(new GraphRuntime(store), setup)._1.run(thread, "go")
    failed.isLeft shouldBe true
    setup.allEnds.head.left.toOption.get.message should include("is not the child thread")
    setup.childClient.callCount shouldBe 1
  }

  behavior.of("A child another runtime holds")

  it should "be waited on for twice the claim's life, then fail the call, and be reattached once free" in {
    val open = storage()
    // 0: the gate fails the run; 1: it waits for `release`; 2: it passes
    val mode    = new AtomicInteger(0)
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val gate = SpecTools.tool("gate") { (_, _) =>
      mode.get match {
        case 0 => ToolOutcome.Fatal(ProcessingError("gate", "shut"))
        case 1 =>
          entered.countDown()
          release.await(30, TimeUnit.SECONDS)
          ToolOutcome.Success(ToolContent.Json(ujson.Str("open")))
        case _ => ToolOutcome.Success(ToolContent.Json(ujson.Str("open")))
      }
    }
    val setup = new Setup(
      delegating(),
      ScriptedLLMClient.of(calling(call("k1", "gate")), CompletionFixture.simple("report")),
      settle = false,
      childTools = Seq(gate)
    )
    val thread = ThreadId("held-elsewhere")
    // a lease long enough that the holder's renewals land on a slow runner (a 300 ms lease lapsed on Windows CI, so
    // the waiter took the child over instead of waiting): the holder renews every 100 ms with 900 ms to spare
    val claims = ClaimPolicy(ttl = 1.second, renewEvery = 100.millis)
    val here   = new GraphRuntime(open(), claims = claims)
    family(here, setup)._1.run(thread, "go").isLeft shouldBe true
    val childId = onlyChild(here, thread).threadId

    // another runtime recovers the child and holds it
    mode.set(1)
    val elsewhere = new GraphRuntime(open(), claims = claims)
    val held      = elsewhere.recoverChild(childId, family(elsewhere, setup)._2.loop.graph, RunConfig()).ok
    entered.await(10, TimeUnit.SECONDS) shouldBe true

    val began = System.nanoTime()
    family(here, setup)._1.recover(thread).isLeft shouldBe true
    // waited twice the claim's life, less a little for the clock's granularity
    (System.nanoTime() - began).nanos should be >= (claims.ttl * 2 - 100.millis)
    setup.allEnds.last.left.toOption.get shouldBe a[GraphError.ThreadBusy]

    release.countDown()
    held.await().ok shouldBe a[RunResult.Completed[?]]
    family(here, setup)._1.recover(thread).ok.answer shouldBe Some("composed")
    setup.childClient.callCount shouldBe 2
  }

  behavior.of("Cancelling the parent during a child")

  it should "cancel the child, and both recover" in {
    val open     = storage()
    val blocking = new AtomicBoolean(true)
    val entered  = new CountDownLatch(1)
    val setup = new Setup(
      delegating(),
      ScriptedLLMClient.of(calling(call("k1", "hang")), CompletionFixture.simple("report")),
      childTools = Seq(hanging(blocking, entered))
    )
    val runtime = new GraphRuntime(open())
    val parent  = family(runtime, setup)._1
    val thread  = ThreadId("cancel-parent")
    val run     = parent.start(thread, "go").ok
    entered.await(10, TimeUnit.SECONDS) shouldBe true
    run.cancel()
    run.await().isLeft shouldBe true
    eventually("the child's end is recorded")(!setup.ends.isEmpty)
    setup.allEnds.head.left.toOption.get shouldBe a[CancelledError]

    val child = onlyChild(runtime, thread)
    eventually("the child is cancelled")(events(open(), child.threadId).exists(_.isInstanceOf[RunEvent.RunCancelled]))
    blocking.set(false)
    val recovered = new AtomicReference[Option[org.llm4s.agent.AgentResult]](None)
    eventually("the parent recovers") {
      recovered.set(parent.recover(thread).toOption)
      recovered.get.flatMap(_.answer).contains("composed")
    }
    onlyChild(runtime, thread).status shouldBe CheckpointStatus.Completed
    // the parent's two calls, and the child's two - the one before the cancel and the one after - each once
    tokens(recovered.get.get.usage) shouldBe ((4L, 40L, 80L))
  }

  behavior.of("A timeout on the delegating call")

  it should "cancel the child" in {
    val open    = storage()
    val entered = new CountDownLatch(1)
    val setup = new Setup(
      ScriptedLLMClient.of(calling(call("c1", "delegate")), CompletionFixture.simple("moved on")),
      ScriptedLLMClient.of(calling(call("k1", "hang"))),
      childTools = Seq(hanging(new AtomicBoolean(true), entered)),
      delegateTimeout = Some(300.millis)
    )
    val runtime = new GraphRuntime(open())
    val thread  = ThreadId("timed-out-call")
    val result  = family(runtime, setup)._1.run(thread, "go").ok
    result.answer shouldBe Some("moved on")
    val child = onlyChild(runtime, thread)
    eventually("the child is cancelled")(events(open(), child.threadId).exists(_.isInstanceOf[RunEvent.RunCancelled]))
    child.status shouldBe CheckpointStatus.Running
    // the timed-out call committed the child's call with its error: the parent's two calls and the child's one
    tokens(result.usage) shouldBe ((3L, 30L, 60L))
  }

  behavior.of("A parent that loses its claim during a child")

  it should "cancel the child" in {
    val open    = storage()
    val entered = new CountDownLatch(1)
    val losing  = new AtomicBoolean(false)
    val thread  = ThreadId("claim-lost")
    val store = new ForwardingCheckpointer(open()) {
      override def renew(threadId: ThreadId, token: FencingToken, ttl: FiniteDuration) =
        if (losing.get && threadId == thread) Left(GraphError.StaleClaim(threadId.value, token, None))
        else super.renew(threadId, token, ttl)
    }
    val setup = new Setup(
      delegating(),
      ScriptedLLMClient.of(calling(call("k1", "hang"))),
      childTools = Seq(hanging(new AtomicBoolean(true), entered))
    )
    val runtime = new GraphRuntime(store, claims = ClaimPolicy(ttl = 2.seconds, renewEvery = 100.millis))
    val run     = family(runtime, setup)._1.start(thread, "go").ok
    entered.await(10, TimeUnit.SECONDS) shouldBe true
    losing.set(true)
    run.await().isLeft shouldBe true
    val child = onlyChild(runtime, thread)
    eventually("the child is cancelled")(events(open(), child.threadId).exists(_.isInstanceOf[RunEvent.RunCancelled]))
    eventually("the child's end is recorded")(!setup.ends.isEmpty)
    setup.allEnds.head.left.toOption.get shouldBe a[CancelledError]
  }

  behavior.of("A child's deadline")

  it should "be capped by its parent's" in {
    val open = storage()
    val seen = new AtomicReference[Option[Instant]](None)
    val peek = SpecTools.tool("peek") { (_, ctx) =>
      seen.set(ctx.run.deadline)
      ToolOutcome.Success(ToolContent.Json(ujson.Str("peeked")))
    }
    val setup = new Setup(
      delegating(),
      ScriptedLLMClient.of(calling(call("k1", "peek")), CompletionFixture.simple("report")),
      childTools = Seq(peek)
    )
    val runtime = new GraphRuntime(open())
    val parent  = family(runtime, setup)._1
    val timed   = RunConfig().withBudgets(RunBudgets.default.withTimeout(30.seconds))
    val before  = Instant.now()
    parent.run(ThreadId("deadline"), "go", timed).ok.answer shouldBe Some("composed")
    val child = seen.get.getOrElse(fail("the child had no deadline"))
    child.isAfter(before.plusSeconds(31)) shouldBe false
    child.isAfter(before) shouldBe true

    ChildRunner.cappedTimeout(Some(1.hour), Some(before.plusSeconds(10)), before) shouldBe (Some(10.seconds), true)
    ChildRunner.cappedTimeout(Some(1.second), Some(before.plusSeconds(10)), before) shouldBe (Some(1.second), false)
    ChildRunner.cappedTimeout(None, Some(before.plusSeconds(10)), before) shouldBe (Some(10.seconds), true)
    ChildRunner.cappedTimeout(Some(1.second), None, before) shouldBe (Some(1.second), false)
    ChildRunner.cappedTimeout(None, Some(before.minusSeconds(1)), before) shouldBe (Some(1.milli), true)
  }

  /** A wrapper that, the first time, makes the model call and then fails the model task: its usage is lost usage. */
  private def failingOnce(failing: AtomicBoolean): AgentMiddleware = new AgentMiddleware {
    val id: MiddlewareId = MiddlewareId("fail-once")
    override def wrapModelCall(request: ModelRequest, context: ModelContext)(
      next: ModelRequest => Result[ModelResponse]
    ): Result[ModelResponse] =
      next(request).flatMap(r => if (failing.getAndSet(false)) Left(ProcessingError("model", "lost")) else Right(r))
  }

  behavior.of("A child's usage")

  it should "be counted exactly once across a crash and replay, with the tokens of a failed attempt recover finished" in {
    val open    = storage()
    val failing = new AtomicBoolean(true)
    val setup = new Setup(
      delegating(),
      answers("report", "report"),
      settle = false,
      child = _.withMiddleware(failingOnce(failing))
    )
    val thread = ThreadId("usage-once")
    family(new GraphRuntime(open()), setup)._1.run(thread, "go").isLeft shouldBe true

    val runtime = new GraphRuntime(open())
    val result  = family(runtime, setup)._1.recover(thread).ok
    result.answer shouldBe Some("composed")
    setup.childClient.callCount shouldBe 2
    val rollUp = setup.allEnds.last.ok match {
      case ChildEnd.Ended(_, usage) => usage
      case other                    => fail(s"unexpected $other")
    }
    // both child calls: the lost one, carried forward, and the committed one
    tokens(rollUp) shouldBe ((2L, 20L, 40L))
    // the parent's two calls and the child's roll-up, once
    tokens(result.usage) shouldBe ((4L, 40L, 80L))
  }

  it should "fold nothing twice when a recovered attempt crashes after its prior usage commits" in {
    val open    = storage()
    val failing = new AtomicBoolean(true)
    // once armed, a child's commits after its recover's claim are refused: the attempt ends with no terminal event
    val armed   = new AtomicBoolean(false)
    val crashed = new AtomicBoolean(false)
    val store = new ForwardingCheckpointer(open()) {
      override def commit(threadId: ThreadId, commit: Commit) =
        if (!threadId.value.contains("/~task/") || !armed.get) super.commit(threadId, commit)
        else if (crashed.get) Left(ProcessingError("store", "the process died"))
        else {
          val landed = super.commit(threadId, commit)
          if (commit.events.exists(_.event.isInstanceOf[RunEvent.RunRecovered])) crashed.set(true)
          landed
        }
    }
    val setup = new Setup(
      delegating(),
      answers("report", "report", "report"),
      settle = false,
      child = _.withMiddleware(failingOnce(failing))
    )
    val thread  = ThreadId("usage-crash")
    val runtime = new GraphRuntime(store)
    family(runtime, setup)._1.run(thread, "go").isLeft shouldBe true
    val childId = onlyChild(runtime, thread).threadId

    armed.set(true)
    family(runtime, setup)._1.recover(thread).isLeft shouldBe true
    def prior: PriorUsage = {
      val (_, kid) = family(runtime, setup)
      runtime.childAtRest(childId, kid.loop.graph, RunConfig()).ok.get._2.state.get(DelegationKeys.priorUsage).ok
    }
    tokens(prior.total) shouldBe ((1L, 10L, 20L))

    armed.set(false)
    val result = family(new GraphRuntime(open()), setup)._1.recover(thread).ok
    result.answer shouldBe Some("composed")
    tokens(prior.total) shouldBe ((1L, 10L, 20L))
    val rollUp = setup.allEnds.last.ok match {
      case ChildEnd.Ended(_, usage) => usage
      case other                    => fail(s"unexpected $other")
    }
    // the attempt that crashed made a call no event recorded; the other two are counted once each
    tokens(rollUp) shouldBe ((2L, 20L, 40L))
  }

  /** A child config whose retention removes every event older than a millisecond. */
  private val ageing: RunConfig = RunConfig().withRetention(RetentionPolicy.keepAll.withMaxAge(1.millis))

  /** The roll-up of the last child call that ended. */
  private def lastRollUp(setup: Setup): UsageSummary = setup.allEnds.last.ok match {
    case ChildEnd.Ended(_, usage) => usage
    case other                    => fail(s"unexpected $other")
  }

  it should "keep a failed attempt's lost usage when the retention's maxAge is shorter than the gap between attempts" in {
    val open    = storage()
    val failing = new AtomicBoolean(true)
    val setup = new Setup(
      delegating(),
      answers("report", "report", "report"),
      settle = false,
      child = _.withMiddleware(failingOnce(failing)),
      childConfig = ageing
    )
    val thread = ThreadId("usage-aged")
    family(new GraphRuntime(open()), setup)._1.run(thread, "go").isLeft shouldBe true
    // each failed attempt's terminal event, which carries its lost usage, is older than the retention's maxAge when
    // the next attempt is admitted
    Thread.sleep(50)
    failing.set(true)
    family(new GraphRuntime(open()), setup)._1.recover(thread).isLeft shouldBe true
    Thread.sleep(50)

    val result = family(new GraphRuntime(open()), setup)._1.recover(thread).ok
    result.answer shouldBe Some("composed")
    setup.childClient.callCount shouldBe 3
    // each lost call was folded, and committed, before an admission pruned its event
    tokens(lastRollUp(setup)) shouldBe ((3L, 30L, 60L))
    tokens(result.usage) shouldBe ((5L, 50L, 100L))
  }

  it should "fold the next attempt's lost usage when an admission's prune also removed its own claim's event" in {
    val open    = storage()
    val failing = new AtomicBoolean(true)
    // as if the claim's event had aged out too by the time the prune ran: every event of the child goes
    val store = new ForwardingCheckpointer(open()) {
      override def prune(threadId: ThreadId, policy: RetentionPolicy) =
        if (threadId.value.contains("/~task/")) super.prune(threadId, policy.withMaxEvents(0))
        else super.prune(threadId, policy)
    }
    val setup = new Setup(
      delegating(),
      answers("report", "report", "report"),
      settle = false,
      child = _.withMiddleware(failingOnce(failing)),
      childConfig = ageing
    )
    val thread = ThreadId("usage-floor")
    family(new GraphRuntime(store), setup)._1.run(thread, "go").isLeft shouldBe true
    failing.set(true)
    family(new GraphRuntime(store), setup)._1.recover(thread).isLeft shouldBe true

    val result = family(new GraphRuntime(store), setup)._1.recover(thread).ok
    result.answer shouldBe Some("composed")
    setup.childClient.callCount shouldBe 3
    // the events below the floor were folded before the prune: the fold reads on from the floor
    tokens(lastRollUp(setup)) shouldBe ((3L, 30L, 60L))
  }

  it should "fail the recover, folding nothing, when the child's events cannot be read" in {
    val open    = storage()
    val failing = new AtomicBoolean(true)
    val broken  = new AtomicBoolean(false)
    val store = new ForwardingCheckpointer(open()) {
      override def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int) =
        if (broken.get && threadId.value.contains("/~task/")) Left(ProcessingError("store", "unreadable"))
        else super.eventsAfter(threadId, afterSeq, limit)
    }
    val setup = new Setup(
      delegating(),
      answers("report", "report"),
      settle = false,
      child = _.withMiddleware(failingOnce(failing)),
      childConfig = ageing
    )
    val thread = ThreadId("usage-unreadable")
    family(new GraphRuntime(store), setup)._1.run(thread, "go").isLeft shouldBe true

    broken.set(true)
    family(new GraphRuntime(store), setup)._1.recover(thread).isLeft shouldBe true
    setup.childClient.callCount shouldBe 1

    broken.set(false)
    family(new GraphRuntime(store), setup)._1.recover(thread).ok.answer shouldBe Some("composed")
    tokens(lastRollUp(setup)) shouldBe ((2L, 20L, 40L))
  }

  it should "keep a failed attempt's lost usage when the recover's claim commit fails after the fold" in {
    val open    = storage()
    val failing = new AtomicBoolean(true)
    // once armed, the next claim commit of a child's recover is refused: the fold it carried is never stored
    val armed = new AtomicBoolean(false)
    val store = new ForwardingCheckpointer(open()) {
      override def commit(threadId: ThreadId, commit: Commit) =
        if (
          threadId.value.contains("/~task/") &&
          commit.events.exists(_.event.isInstanceOf[RunEvent.RunRecovered]) &&
          armed.getAndSet(false)
        ) Left(ProcessingError("store", "the process died"))
        else super.commit(threadId, commit)
    }
    val setup = new Setup(
      delegating(),
      answers("report", "report"),
      settle = false,
      child = _.withMiddleware(failingOnce(failing)),
      childConfig = ageing
    )
    val thread  = ThreadId("usage-commit-lost")
    val runtime = new GraphRuntime(store)
    family(runtime, setup)._1.run(thread, "go").isLeft shouldBe true
    Thread.sleep(50)

    armed.set(true)
    family(runtime, setup)._1.recover(thread).isLeft shouldBe true
    armed.get shouldBe false
    setup.childClient.callCount shouldBe 1
    Thread.sleep(50)

    val result = family(new GraphRuntime(open()), setup)._1.recover(thread).ok
    result.answer shouldBe Some("composed")
    setup.childClient.callCount shouldBe 2
    // the refused claim commit pruned nothing, so the next recover folded the lost call's event
    tokens(lastRollUp(setup)) shouldBe ((2L, 20L, 40L))
    tokens(result.usage) shouldBe ((4L, 40L, 80L))
  }

  it should "prune a top-level thread's admission before the claim's own checkpoint and event exist" in {
    val store   = storage()()
    val runtime = new GraphRuntime(store)
    val agent   = Agent.builder("solo", answers("one", "two")).withRuntime(runtime).build().ok
    val thread  = ThreadId("top-level-retention")
    agent.run(thread, "first").ok.answer shouldBe Some("one")
    val config = RunConfig().withRetention(RetentionPolicy.keepAll.withMaxEvents(1))
    agent.run(thread, "second", config).ok.answer shouldBe Some("two")
    // admission kept only the first run's newest event, then the second run wrote all of its own
    val kept = store.eventsAfter(thread, 0L, 10_000) match {
      case Left(GraphError.ReplayUnavailable(_, earliest)) =>
        store.eventsAfter(thread, earliest - 1, 10_000).ok.map(_.event)
      case other => other.ok.map(_.event)
    }
    kept.head shouldBe RunEvent.RunCompleted
    kept.collect { case started: RunEvent.RunStarted => started } should have size 1
  }

  /** A wrapper that runs the call, then asks for approval; approved, it runs the call again or answers itself. */
  private def reviewing(rerun: Boolean): AgentMiddleware = new AgentMiddleware {
    val id: MiddlewareId = MiddlewareId("review")
    override def wrapToolCall(request: org.llm4s.agent.graph.middleware.ToolCallRequest, context: ToolContext)(
      next: () => ToolOutcome
    ): ToolOutcome =
      if (!context.approved) {
        next(): Unit
        ToolOutcome.NeedsApproval("check the report")
      } else if (rerun) next()
      else ToolOutcome.Success(ToolContent.Json(ujson.Str("approved as it was")))
  }

  Seq(true -> "runs the call again", false -> "answers without running it").foreach { case (rerun, how) =>
    it should s"be counted once when a wrapper asks for approval after the child ran, and approved $how" in {
      val open    = storage()
      val setup   = new Setup(delegating(), answers("report"), parentMiddleware = Seq(reviewing(rerun)))
      val runtime = new GraphRuntime(open())
      val parent  = family(runtime, setup)._1
      val thread  = ThreadId(s"approved-after-child-$rerun")
      val parked  = parent.run(thread, "go").ok
      val id = parked.status match {
        case AgentStatus.Suspended(approvals, _, _, _, _) => approvals.head._1
        case other                                        => fail(s"unexpected $other")
      }
      val done = parent.resume(thread, Map(parked.approve(id))).ok
      done.answer shouldBe Some("composed")
      setup.childClient.callCount shouldBe 1
      // the parent's two calls and the child's one, once
      tokens(done.usage) shouldBe ((3L, 30L, 60L))
    }
  }

  it should "refuse a call whose arguments an approval edited after its child started" in {
    val open    = storage()
    val setup   = new Setup(delegating(), answers("report"), parentMiddleware = Seq(reviewing(rerun = true)))
    val runtime = new GraphRuntime(open())
    val parent  = family(runtime, setup)._1
    val thread  = ThreadId("edited-after-child")
    val parked  = parent.run(thread, "go").ok
    val id = parked.status match {
      case AgentStatus.Suspended(approvals, _, _, _, _) => approvals.head._1
      case other                                        => fail(s"unexpected $other")
    }
    parent.resume(thread, Map(parked.edit(id, ujson.Obj("text" -> "another task")))).isLeft shouldBe true
    setup.allEnds.last.left.toOption.get.message should include("started with other input")
    setup.childClient.callCount shouldBe 1
  }

  /** A family whose reviewed call left its failed child `Running`, the parent parked on the approval (#2096). */
  private def parkedOverRunningChild(name: String, open: () => Checkpointer) = {
    val setup = new Setup(
      delegating(),
      ScriptedLLMClient.of(calling(call("k1", "flaky"))),
      settle = false,
      childTools = Seq(flaky(new AtomicBoolean(true))),
      parentMiddleware = Seq(reviewing(rerun = true))
    )
    val runtime = new GraphRuntime(open())
    val parent  = family(runtime, setup)._1
    val thread  = ThreadId(name)
    val parked  = parent.run(thread, "go").ok
    val id = parked.status match {
      case AgentStatus.Suspended(approvals, _, _, _, _) => approvals.head._1
      case other                                        => fail(s"unexpected $other")
    }
    val child = onlyChild(runtime, thread)
    child.status shouldBe CheckpointStatus.Running
    (setup, runtime, parent, thread, parked.edit(id, ujson.Obj("text" -> "another task")), child.threadId)
  }

  private def latestRun(store: Checkpointer, child: ThreadId): RunId = RunId(
    store.latest(child).ok.get.checkpoint.runId
  )

  /** The child runs `events` announced (`TaskStarted`) and gave a `LiveGap(0)`, in order. */
  private def announced(events: Vector[StreamEvent]): (Vector[RunId], Vector[RunId]) =
    (
      events.flatMap(SubagentEvents.TaskStarted.unapply).map(_.child.runId),
      events.collect { case StreamEvent.Nested(child, StreamEvent.LiveGap(0)) => child.runId }
    )

  private def abandoned(store: Checkpointer, child: ThreadId): Unit =
    store.latest(child).ok.map(s => (s.checkpoint.status, s.checkpoint.outcome)) match {
      case Some((CheckpointStatus.Failed, Some(RunOutcome.Failed("abandoned", _)))) => ()
      case other => fail(s"expected the child settled abandoned, got $other")
    }

  it should "settle the Running child of a call whose arguments an approval edited, then refuse it (#2096)" in {
    val open                                        = storage()
    val (setup, _, parent, thread, edited, childId) = parkedOverRunningChild("edited-over-running", open)
    val original                                    = latestRun(open(), childId)
    val seen                                        = new CopyOnWriteArrayList[StreamEvent]()
    parent.streamResume(thread, Map(edited), nested = true)(seen.add(_): Unit).ok.await().isLeft shouldBe true
    setup.allEnds.last.left.toOption.get.message should include("started with other input")
    abandoned(open(), childId)
    setup.childClient.callCount shouldBe 1
    // the run found, then the settlement run, now the child's latest checkpoint, each announced with a gap
    val runs = Vector(original, latestRun(open(), childId))
    announced(seen.asScala.toVector) shouldBe ((runs, runs))
  }

  it should "announce the run that settled the child first when another settles it before its abandon (#2096)" in {
    val open                                   = storage()
    val (setup, _, _, thread, edited, childId) = parkedOverRunningChild("edited-raced", open)
    val original                               = latestRun(open(), childId)
    val racing                                 = new AtomicBoolean(false)
    val other                                  = new GraphRuntime(open())
    val otherGraph                             = family(other, setup)._2.loop.graph
    val otherRun                               = RunId("settled-elsewhere")
    // between the runner's read and its abandon's claim, another runtime settles the child
    val store = new ForwardingCheckpointer(open()) {
      override def claim(threadId: ThreadId, request: ClaimRequest) = {
        if (threadId == childId && racing.getAndSet(false))
          other
            .settleChild(
              childId,
              otherGraph,
              RunOutcome.Failed("elsewhere", "raced"),
              "raced",
              RunConfig().withRunId(otherRun)
            )
            .ok
        super.claim(threadId, request)
      }
    }
    val parent = family(new GraphRuntime(store), setup)._1
    racing.set(true)
    val seen = new CopyOnWriteArrayList[StreamEvent]()
    parent.streamResume(thread, Map(edited), nested = true)(seen.add(_): Unit).ok.await().isLeft shouldBe true
    racing.get shouldBe false
    setup.allEnds.last.left.toOption.get.message should include("started with other input")
    open().latest(childId).ok.map(_.checkpoint.runId) shouldBe Some(otherRun.value)
    announced(seen.asScala.toVector) shouldBe ((Vector(original, otherRun), Vector(original, otherRun)))
  }

  it should "return ThreadBusy for an edited call over a busy child, and settle it on a later recover (#2096)" in {
    val open                                        = storage()
    val (setup, _, parent, thread, edited, childId) = parkedOverRunningChild("edited-over-busy", open)
    val original                                    = latestRun(open(), childId)
    val held = open().claim(childId, ClaimRequest(RunId("elsewhere"), None, 1.minute)).ok
    val seen = new CopyOnWriteArrayList[StreamEvent]()
    parent.streamResume(thread, Map(edited), nested = true)(seen.add(_): Unit).ok.await().isLeft shouldBe true
    setup.allEnds.last.left.toOption.get shouldBe a[GraphError.ThreadBusy]
    // the run found is announced, with a gap, though the busy child could not be settled
    announced(seen.asScala.toVector) shouldBe ((Vector(original), Vector(original)))
    open().latest(childId).ok.map(_.checkpoint.status) shouldBe Some(CheckpointStatus.Running)

    open().release(childId, held.token).ok
    parent.recover(thread).isLeft shouldBe true
    setup.allEnds.last.left.toOption.get.message should include("started with other input")
    abandoned(open(), childId)
    setup.childClient.callCount shouldBe 1
  }

  it should "be counted once when a wrapper runs the call twice" in {
    val open = storage()
    val twice = new AgentMiddleware {
      val id: MiddlewareId = MiddlewareId("twice")
      override def wrapToolCall(request: org.llm4s.agent.graph.middleware.ToolCallRequest, context: ToolContext)(
        next: () => ToolOutcome
      ): ToolOutcome = {
        next(): Unit
        next()
      }
    }
    val setup = new Setup(delegating(), answers("report"), parentMiddleware = Seq(twice))
    val done  = family(new GraphRuntime(open()), setup)._1.run(ThreadId("run-twice"), "go").ok
    done.answer shouldBe Some("composed")
    setup.childClient.callCount shouldBe 1
    tokens(done.usage) shouldBe ((3L, 30L, 60L))
  }

  behavior.of("A child thread")

  it should "keep at least its newest event, whatever retention it inherits" in {
    GraphRuntime
      .childConfig(RunConfig().withRetention(RetentionPolicy.keepAll.withMaxEvents(0)))
      .retention
      .flatMap(
        _.maxEvents
      ) shouldBe Some(1)
    GraphRuntime
      .childConfig(RunConfig().withRetention(RetentionPolicy.keepAll.withMaxEvents(5)))
      .retention
      .flatMap(
        _.maxEvents
      ) shouldBe Some(5)
    GraphRuntime.childConfig(RunConfig()).retention shouldBe None
  }
}

/** [[ChildSupervisionBehaviours]] on the in-memory store, which a restart keeps (the same instance). */
class ChildSupervisionSpec extends AnyFlatSpec with ChildSupervisionBehaviours {
  protected def storage(): () => Checkpointer = {
    val store = new InMemoryCheckpointer()
    () => store
  }
}
