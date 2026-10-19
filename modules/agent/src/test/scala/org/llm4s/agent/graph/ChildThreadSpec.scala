package org.llm4s.agent.graph

import org.llm4s.agent.AgentId
import org.llm4s.agent.graph.GraphTestSupport.*
import org.llm4s.agent.graph.middleware.GuardrailBlocked
import org.llm4s.error.ValidationError
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.*

/**
 * Child threads (design 4.41, #2045): hierarchical ids, the origin a child's checkpoints carry, the refusals that keep
 * a child driven only through its parent (rule 19), cascade delete, child retention (rule 12), and the outcome a
 * terminal checkpoint records.
 */
class ChildThreadSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private val log     = StateKey.appending[String]("log")
  private val worker  = AgentId.unsafe("worker")
  private val failing = new AtomicBoolean(false)

  /**
   * `act` logs its input; `ask` suspends it on `review`, `block` and `guard` block it (the second as a guardrail
   * would), and while `failing` is set `fail` fails it, leaving the thread `Running`.
   */
  private val graph: CompiledGraph[String, Vector[String]] = {
    val b      = GraphBuilder("children", "v1")
    val review = b.declareResume[String, String]("review")
    b.implement(review.node, writes = Set(log)) { (resumed, _, _) =>
      if resumed.answer == "again" then NodeResult.Suspend(StateUpdate.update(log, "asked again"), "ok?", review)
      else continue(Command.empty.update(log, s"answered:${resumed.answer}"))
    }
    val act = b.node[String]("act", writes = Set(log)) { (input, _, _) =>
      input match
        case "ask"   => NodeResult.Suspend(StateUpdate.update(log, "asked"), "ok?", review)
        case "block" => NodeResult.Block(StateUpdate.update(log, "blocked"), ValidationError("act", "no"))
        case "guard" => NodeResult.Block(StateUpdate.update(log, "guarded"), GuardrailBlocked("LengthCheck", "long"))
        case "fail" if failing.get => NodeResult.Fail(ValidationError("act", "down"))
        case other                 => continue(Command.empty.update(log, other))
    }
    b.compile(act)(_.get(log)).value
  }

  private def key(n: Int): IdempotencyKey = IdempotencyKey(s"call-$n")

  /** The runtime's clock, which stamps a child's `startedAt`. */
  private val clock = java.time.Clock.fixed(java.time.Instant.parse("2026-10-19T12:00:00Z"), java.time.ZoneOffset.UTC)

  private def origin(parent: ThreadId, n: Int, depth: Int = 1): RunOrigin =
    RunOrigin(parent, key(n), worker, clock.instant(), depth)

  private def childOf(parent: ThreadId, n: Int): ThreadId = ThreadId.child(parent, worker, key(n))

  /** Starts child `n` of `parent` with `input` and waits for it. */
  private def startChild(
    runtime: GraphRuntime,
    parent: ThreadId,
    n: Int,
    input: String = "go",
    depth: Int = 1,
    config: RunConfig = RunConfig()
  ): ThreadId =
    val id = childOf(parent, n)
    runtime.startChild(id, graph, input, origin(parent, n, depth), config).awaited.value: Unit
    id

  private def fresh(): (InMemoryCheckpointer, GraphRuntime, ThreadId) =
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store, clock)
    val parent  = ThreadId(s"parent-${java.util.UUID.randomUUID()}")
    runtime.start(parent, graph, "go").awaited.value.completed: Unit
    (store, runtime, parent)

  "ThreadId.child" should "give a hierarchical id, stable for one call and different for another call, thread or agent" in {
    val parent = ThreadId("p")
    val id     = ThreadId.child(parent, worker, key(1))
    (id.value should fullyMatch).regex("p/~task/worker/[0-9a-f]{24}")
    ThreadId.child(parent, worker, key(1)) shouldBe id
    ThreadId.child(parent, worker, key(2)) should not be id
    (ThreadId.child(ThreadId("q"), worker, key(1)).value should not).endWith(id.value.drop(1))
    ThreadId.child(ThreadId("q"), worker, key(1)) should not be id
    ThreadId.child(parent, AgentId.unsafe("other"), key(1)).value.split('/').last should not be id.value.split('/').last
  }

  it should "decide nothing by its shape: a top-level thread named like a child is a top-level thread" in {
    val (store, runtime, parent) = fresh()
    val named                    = childOf(parent, 1)
    runtime.start(named, graph, "go").awaited.value.completed
    store.latest(named).value.value.checkpoint.origin shouldBe None
    runtime.children(parent).value shouldBe empty
    runtime.start(named, graph, "again").awaited.value.completed
  }

  "A child thread" should "record its origin on every checkpoint, through a suspend and a resume" in {
    val (store, runtime, parent) = fresh()
    val child                    = startChild(runtime, parent, 1, "ask")
    store.latest(child).value.value.checkpoint.status shouldBe CheckpointStatus.Suspended
    runtime
      .resumeChild(child, graph, Map(InterruptId("0.0") -> ujson.Str("yes")), RunConfig())
      .awaited
      .value
      .completed
    val history = store.history(child, None, 100).value
    history.size should be > 2
    history.map(_.origin) should contain only Some(origin(parent, 1))
  }

  it should "record when it was first admitted, by the runtime's clock, whatever its caller passed" in {
    val (store, runtime, parent) = fresh()
    val child                    = childOf(parent, 1)
    runtime
      .startChild(child, graph, "go", origin(parent, 1).withStartedAt(java.time.Instant.EPOCH), RunConfig())
      .awaited
      .value
    store.latest(child).value.value.checkpoint.origin.map(_.startedAt) shouldBe Some(clock.instant())
  }

  it should "be listed among its parent's children, oldest first, paged, with its status and latest checkpoint" in {
    val (store, runtime, parent) = fresh()
    val children                 = (1 to 3).map(n => startChild(runtime, parent, n, if n == 2 then "ask" else "go"))
    val listed                   = runtime.children(parent).value
    listed.map(_.threadId) shouldBe children
    listed
      .map(_.status) shouldBe Vector(CheckpointStatus.Completed, CheckpointStatus.Suspended, CheckpointStatus.Completed)
    listed.head.latest shouldBe store.latest(children.head).value.value.checkpoint.id
    listed.head.origin shouldBe origin(parent, 1)
    val first = runtime.children(parent, None, 2).value
    first.map(_.threadId) shouldBe children.take(2)
    runtime.children(parent, first.lastOption.map(_.threadId), 2).value.map(_.threadId) shouldBe children.drop(2)
    runtime.children(parent, None, 0).left.value shouldBe a[ValidationError]
    runtime.children(parent, Some(ThreadId("stranger")), 2).left.value shouldBe a[ValidationError]
    runtime.children(children.head).value shouldBe empty
  }

  it should "be listed only to its parent's tenant" in {
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store, clock)
    val parent  = ThreadId("tenant-parent")
    val tenantA = RunConfig().withTenantId(TenantId("a"))
    runtime.start(parent, graph, "go", tenantA).awaited.value.completed
    val child = childOf(parent, 1)
    runtime.startChild(child, graph, "go", origin(parent, 1), tenantA.withRunId(RunId.random())).awaited.value
    runtime.children(parent, config = RunConfig().withTenantId(TenantId("b"))).left.value shouldBe
      GraphError.TenantMismatch(parent.value, Some("b"))
    runtime.children(parent).left.value shouldBe GraphError.TenantMismatch(parent.value, None)
    runtime.children(parent, config = tenantA).value.map(_.threadId) shouldBe Vector(child)
  }

  it should "refuse start, recover, resume, fork, updateState and deleteThread, before any claim, and allow reads" in {
    val (store, runtime, parent) = fresh()
    val child                    = startChild(runtime, parent, 1, "ask")
    val stored                   = store.latest(child).value.value
    val refused                  = GraphError.ChildThread(child.value, parent.value)
    val events                   = store.eventsAfter(child, 0L, 1000).value.size

    runtime.start(child, graph, "go").left.value shouldBe refused
    runtime.recover(child, graph).left.value shouldBe refused
    runtime.resume(child, graph, Map(InterruptId("0.0") -> ujson.Str("yes"))).left.value shouldBe refused
    runtime.fork(child, stored.checkpoint.id, ThreadId("fork-target")).left.value shouldBe refused
    runtime.updateState(child, graph, stored.checkpoint.id, StateUpdate.update(log, "x")).left.value shouldBe refused
    runtime.deleteThread(child).left.value shouldBe refused

    // nothing was claimed or written: the thread is as it was, and free
    store.latest(child).value.value.checkpoint shouldBe stored.checkpoint
    store.eventsAfter(child, 0L, 1000).value.size shouldBe events
    runtime.store.claim(child, ClaimRequest(RunId("probe"), None, 1.minute)).value
    store.latest(ThreadId("fork-target")).value shouldBe None

    runtime.history(child).value.map(_.id) should contain(stored.checkpoint.id)
    runtime.checkpoint(child, stored.checkpoint.id).value shouldBe stored.checkpoint
    runtime.children(child).value shouldBe empty
  }

  it should "allow subscribing to it" in {
    val (_, runtime, parent) = fresh()
    val child                = startChild(runtime, parent, 1)
    val seen                 = new java.util.concurrent.LinkedBlockingQueue[StreamEvent]()
    val subscription         = runtime.subscribe(child)(e => seen.put(e)).value
    Option(seen.poll(5, java.util.concurrent.TimeUnit.SECONDS)) shouldBe defined
    subscription.cancel()
  }

  it should "refuse the child runner's start of an existing thread, and its recover or resume of a top-level one" in {
    val (_, runtime, parent) = fresh()
    val child                = startChild(runtime, parent, 1)
    runtime.startChild(child, graph, "go", origin(parent, 1), RunConfig()).left.value shouldBe
      GraphError.ThreadExists(child.value)
    runtime.recoverChild(parent, graph, RunConfig()).left.value shouldBe a[ValidationError]
    runtime.resumeChild(parent, graph, Map.empty, RunConfig()).left.value shouldBe a[ValidationError]
  }

  it should "be recovered by the child runner, its origin copied forward" in {
    val (store, runtime, parent) = fresh()
    failing.set(true)
    val child = childOf(parent, 1)
    try runtime.startChild(child, graph, "fail", origin(parent, 1), RunConfig()).awaited.value.failed
    finally failing.set(false)
    store.latest(child).value.value.checkpoint.status shouldBe CheckpointStatus.Running
    runtime.recoverChild(child, graph, RunConfig()).awaited.value.completed
    store.latest(child).value.value.checkpoint.origin shouldBe Some(origin(parent, 1))
  }

  "deleteThread" should "delete a thread's children and their children first, then the thread" in {
    val (store, runtime, parent) = fresh()
    val child                    = startChild(runtime, parent, 1)
    val grandchild               = childOf(child, 2)
    runtime.startChild(grandchild, graph, "go", origin(child, 2, depth = 2), RunConfig()).awaited.value
    val sibling = startChild(runtime, parent, 3, "ask")
    runtime.children(child).value.map(_.threadId) shouldBe Vector(grandchild)

    runtime.deleteThread(parent).value

    Vector(parent, child, grandchild, sibling).map(store.latest(_).value) shouldBe Vector(None, None, None, None)
    runtime.children(parent).value shouldBe empty
    store.children(parent, None, 10).value shouldBe empty
    store.children(child, None, 10).value shouldBe empty
  }

  it should "refuse the whole delete when a child holds a live claim, deleting nothing" in {
    val (store, runtime, parent) = fresh()
    val child                    = startChild(runtime, parent, 1)
    val grandchild               = childOf(child, 2)
    runtime.startChild(grandchild, graph, "go", origin(child, 2, depth = 2), RunConfig()).awaited.value
    val sibling = startChild(runtime, parent, 3)
    store.claim(grandchild, ClaimRequest(RunId("elsewhere"), None, 1.minute)).value

    runtime.deleteThread(parent).left.value shouldBe a[GraphError.ThreadBusy]

    Vector(parent, child, grandchild, sibling)
      .map(store.latest(_).value.isDefined) shouldBe Vector(true, true, true, true)
    runtime.children(parent).value.map(_.threadId) shouldBe Vector(child, sibling)
  }

  it should "finish a cascade a failure interrupted when run again" in {
    val store   = InMemoryCheckpointer()
    val failing = new AtomicBoolean(true)
    val parent  = ThreadId("cascade")
    val flaky = new ForwardingCheckpointer(store) {
      override def deleteThread(threadId: ThreadId, token: FencingToken) =
        if failing.get && threadId == childOf(parent, 3) then Left(ValidationError("store", "down"))
        else store.deleteThread(threadId, token)
    }
    val runtime = GraphRuntime(flaky, clock)
    runtime.start(parent, graph, "go").awaited.value
    val first  = startChild(runtime, parent, 1)
    val second = startChild(runtime, parent, 3)

    runtime.deleteThread(parent).isLeft shouldBe true
    store.latest(first).value shouldBe None
    store.latest(second).value shouldBe defined
    store.latest(parent).value shouldBe defined

    failing.set(false)
    runtime.deleteThread(parent).value
    Vector(parent, first, second).map(store.latest(_).value) shouldBe Vector(None, None, None)
  }

  "Child retention" should "keep the newest settled children at a settled parent's start, never a suspended one" in {
    val (store, runtime, parent) = fresh()
    val children = Vector(1, 2, 3, 4).map(n => startChild(runtime, parent, n, if n == 1 then "ask" else "go"))
    runtime.start(parent, graph, "next", RunConfig().withChildRetention(1)).awaited.value.completed
    // the suspended child stays, and of the settled ones only the newest
    runtime.children(parent).value.map(_.threadId) shouldBe Vector(children(0), children(3))
    store.latest(children(1)).value shouldBe None
  }

  /** A parent graph whose single node starts children `1 to n` of `parent`, as `n` task calls would, then completes. */
  private def delegating(runtime: GraphRuntime, parent: ThreadId, n: Int): CompiledGraph[String, Vector[String]] =
    val b = GraphBuilder("delegating", "v1")
    val delegate = b.node[String]("delegate", writes = Set(log)) { (_, _, context) =>
      (1 to n).foreach { i =>
        runtime
          .startChild(
            childOf(parent, i),
            graph,
            "go",
            origin(parent, i),
            RunConfig().withTenantId(context.config.tenantId)
          )
          .awaited
          .value
      }
      continue(Command.empty.update(log, "delegated"))
    }
    b.compile(delegate)(_.get(log)).value

  it should "keep the newest settled children when the parent completes: one of two calls' children at 1" in {
    val runtime = GraphRuntime(InMemoryCheckpointer(), clock)
    val parent  = ThreadId("one-shot")
    runtime
      .start(parent, delegating(runtime, parent, 2), "go", RunConfig().withChildRetention(1))
      .awaited
      .value
      .completed
    runtime.children(parent).value.map(_.threadId) shouldBe Vector(childOf(parent, 2))
  }

  it should "prune before the parent settles, so a crash during the pass is finished by recover" in {
    val store      = InMemoryCheckpointer()
    val parent     = ThreadId("crashing")
    val failSettle = new AtomicBoolean(true)
    val failDelete = new AtomicBoolean(true)
    val flaky = new ForwardingCheckpointer(store) {
      override def commit(threadId: ThreadId, commit: Commit) =
        if failSettle.get && threadId == parent && commit.checkpoint.exists(_.status == CheckpointStatus.Completed)
        then Left(ValidationError("store", "down"))
        else store.commit(threadId, commit)
      override def deleteThread(threadId: ThreadId, token: FencingToken) =
        if failDelete.get && threadId == childOf(parent, 1) then Left(ValidationError("store", "down"))
        else store.deleteThread(threadId, token)
    }
    val runtime = GraphRuntime(flaky, clock)
    val config  = RunConfig().withChildRetention(1)

    runtime.start(parent, delegating(runtime, parent, 3), "go", config).awaited.value.failed
    // the pass ran before the terminal commit: child 2 went, child 1's delete failed, and the parent is unsettled
    store.latest(parent).value.value.checkpoint.status shouldBe CheckpointStatus.Running
    runtime.children(parent).value.map(_.threadId) shouldBe Vector(childOf(parent, 1), childOf(parent, 3))

    failSettle.set(false)
    failDelete.set(false)
    runtime.recover(parent, delegating(runtime, parent, 3), config.withRunId(RunId.random())).awaited.value.completed
    runtime.children(parent).value.map(_.threadId) shouldBe Vector(childOf(parent, 3))
  }

  it should "not prune at a Block, and prune the blocked turn's children once a later turn completes" in {
    val (_, runtime, parent) = fresh()
    runtime.start(parent, graph, "block").awaited.value.failed
    val children = Vector(1, 2).map(n => startChild(runtime, parent, n))
    // a Failed parent's start admission prunes nothing, nor does the Block that ends the run
    runtime.start(parent, graph, "block", RunConfig().withChildRetention(0)).awaited.value.failed
    runtime.children(parent).value.map(_.threadId) shouldBe children
    runtime.start(parent, graph, "go", RunConfig().withChildRetention(0)).awaited.value.completed
    runtime.children(parent).value shouldBe empty
  }

  it should "never run at recover or resume admission" in {
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store, clock)
    val parent  = ThreadId("recovering")
    failing.set(true)
    try
      runtime.start(parent, graph, "fail").awaited.value.failed
      val children = Vector(1, 2).map(n => startChild(runtime, parent, n))
      // the recover fails again, so no terminal checkpoint commits: only admission could prune
      runtime.recover(parent, graph, RunConfig().withChildRetention(0)).awaited.value.failed
      runtime.children(parent).value.map(_.threadId) shouldBe children
    finally failing.set(false)

    val asking = ThreadId("resuming")
    runtime.start(asking, graph, "ask").awaited.value.suspended
    val kept = Vector(1, 2).map(n => startChild(runtime, asking, n))
    // the resume suspends again, so no terminal checkpoint commits: only admission could prune
    runtime
      .resume(asking, graph, Map(InterruptId("0.0") -> ujson.Str("again")), RunConfig().withChildRetention(0))
      .awaited
      .value
      .suspended
    runtime.children(asking).value.map(_.threadId) shouldBe kept
  }

  it should "reject a negative retention" in {
    an[IllegalArgumentException] should be thrownBy RunConfig().withChildRetention(-1)
  }

  "A terminal checkpoint" should "record how its run settled, and a later run's checkpoints clear it" in {
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store, clock)
    val thread  = ThreadId("outcomes")
    def latest  = store.latest(thread).value.value.checkpoint

    runtime.start(thread, graph, "go").awaited.value.completed
    latest.outcome shouldBe Some(RunOutcome.Answered)
    runtime.start(thread, graph, "guard").awaited.value.failed
    latest.outcome shouldBe Some(RunOutcome.Blocked("LengthCheck", "long"))
    runtime.start(thread, graph, "block").awaited.value.failed
    latest.outcome shouldBe Some(RunOutcome.Failed("ValidationError", ValidationError("act", "no").message))
    val parked = runtime.start(thread, graph, "ask").awaited.value.suspended.interrupts.head.id
    latest.outcome shouldBe None
    store
      .history(thread, None, 100)
      .value
      .filter(_.status == CheckpointStatus.Running)
      .flatMap(_.outcome) shouldBe empty

    // updateState keeps the status, and with it the outcome
    runtime.resume(thread, graph, Map(parked -> ujson.Str("yes"))).awaited.value.completed
    val settled = latest
    runtime.updateState(thread, graph, settled.id, StateUpdate.update(log, "edited")).value.outcome shouldBe
      Some(RunOutcome.Answered)
  }

  "RunOutcome.blocked" should "keep a guardrail's name and reason, and every error of an invalid output" in {
    RunOutcome.blocked(GuardrailBlocked("g", "r")) shouldBe RunOutcome.Blocked("g", "r")
    RunOutcome.blocked(GraphError.OutputInvalid(Vector("a", "b", "c", "d", "e", "f"))) shouldBe
      RunOutcome.OutputInvalid(Vector("a", "b", "c", "d", "e", "f"))
    RunOutcome.blocked(ValidationError("x", "y")) shouldBe RunOutcome.Failed(
      "ValidationError",
      ValidationError("x", "y").message
    )
  }

  "RunOrigin" should "round-trip through JSON and refuse a depth below 1" in {
    val o = RunOrigin(ThreadId("p"), key(1), worker, clock.instant(), 2)
    upickle.default.read[RunOrigin](upickle.default.write(o)) shouldBe o
    o.withDepth(3).depth shouldBe 3
    an[IllegalArgumentException] should be thrownBy RunOrigin(ThreadId("p"), key(1), worker, clock.instant(), 0)
  }
}
