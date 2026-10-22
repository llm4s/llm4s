package org.llm4s.agent.graph

import org.llm4s.agent.AgentId
import org.llm4s.agent.graph.GraphTestSupport.*
import org.llm4s.agent.graph.middleware.GuardrailBlocked
import org.llm4s.error.ValidationError
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.{ AtomicBoolean, AtomicReference }
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

  it should "stop a cascade at the next delete when the thread's claim is lost part-way" in {
    val store   = InMemoryCheckpointer()
    val parent  = ThreadId("cascade-lost")
    val taking  = TakingOver(store, parent)
    val runtime = GraphRuntime(taking, clock)
    runtime.start(parent, graph, "go").awaited.value.completed
    val children = Vector(1, 2).map(n => startChild(runtime, parent, n))
    taking.armed = _ == "delete"

    runtime.deleteThread(parent).left.value shouldBe a[GraphError.ThreadBusy]

    children.map(store.latest(_).value.isDefined) shouldBe Vector(false, true)
    store.latest(parent).value shouldBe defined
  }

  it should "delete nothing when the thread is taken over between its claim's check and a descendant's delete (#2063)" in {
    val store   = InMemoryCheckpointer()
    val parent  = ThreadId("cascade-taken-before-delete")
    val taking  = TakingOver(store, parent)
    val runtime = GraphRuntime(taking, clock)
    runtime.start(parent, graph, "go").awaited.value.completed
    val children = Vector(1, 2).map(n => startChild(runtime, parent, n))
    taking.armed = _ == "before-delete"

    runtime.deleteThread(parent).left.value match
      case busy: GraphError.ThreadBusy => busy.threadId shouldBe parent.value
      case other                       => fail(s"expected ThreadBusy, got $other")

    taking.takenOver shouldBe true
    children.map(store.latest(_).value.isDefined) shouldBe Vector(true, true)
    store.latest(parent).value shouldBe defined
    // the refused child's own claim was given back, not taken for lost
    store.claim(children(0), ClaimRequest(RunId("next"), None, 1.minute)).isRight shouldBe true
  }

  it should "finish a cascade a failure interrupted when run again" in {
    val store   = InMemoryCheckpointer()
    val failing = new AtomicBoolean(true)
    val parent  = ThreadId("cascade")
    val flaky = new ForwardingCheckpointer(store) {
      override def deleteThread(threadId: ThreadId, token: FencingToken, fence: Option[(ThreadId, FencingToken)]) =
        if failing.get && threadId == childOf(parent, 3) then Left(ValidationError("store", "down"))
        else store.deleteThread(threadId, token, fence)
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

  "Child retention" should "keep only the newest settled children when the parent's run ends Blocked (#2074)" in {
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store, clock)
    val parent  = ThreadId("blocked-parent")
    // the run starts three children, which settle, then blocks: its calls never run again
    val b = GraphBuilder("spawning", "v1")
    val spawn = b.node[String]("spawn", writes = Set(log)) { (_, _, _) =>
      Vector(1, 2, 3).foreach(n => startChild(runtime, parent, n))
      NodeResult.Block(StateUpdate.update(log, "blocked"), GuardrailBlocked("LengthCheck", "long"))
    }
    val spawning = b.compile(spawn)(_.get(log)).value

    runtime.start(parent, spawning, "go", RunConfig().withChildRetention(1)).awaited.value.failed

    store.latest(parent).value.value.checkpoint.outcome.value shouldBe a[RunOutcome.Blocked]
    runtime.children(parent).value.map(_.threadId) shouldBe Vector(childOf(parent, 3))
    Vector(1, 2).map(n => store.latest(childOf(parent, n)).value) shouldBe Vector(None, None)
  }

  it should "never mark a pruned child deleting, even when its cascade stops part-way (#2074)" in {
    val store  = InMemoryCheckpointer()
    val parent = ThreadId("retained-unmarked")
    val down   = new AtomicBoolean(true)
    val flaky = new ForwardingCheckpointer(store) {
      override def deleteThread(threadId: ThreadId, token: FencingToken, fence: Option[(ThreadId, FencingToken)]) =
        if down.get && threadId == childOf(childOf(parent, 1), 2) then Left(ValidationError("store", "down"))
        else store.deleteThread(threadId, token, fence)
    }
    val runtime = GraphRuntime(flaky, clock)
    runtime.start(parent, graph, "go").awaited.value.completed
    val pruned = startChild(runtime, parent, 1)
    runtime.startChild(childOf(pruned, 2), graph, "go", origin(pruned, 2, depth = 2), RunConfig()).awaited.value
    startChild(runtime, parent, 3)

    runtime.start(parent, graph, "next", RunConfig().withChildRetention(1)).awaited.value.completed

    // the pass could not delete the grandchild, so the child stays - unmarked, as retention marks nothing
    store.latest(pruned).value.value.checkpoint.deleting shouldBe false
    down.set(false)
    runtime.start(parent, graph, "again", RunConfig().withChildRetention(1)).awaited.value.completed
    store.latest(pruned).value shouldBe None
  }

  it should "keep the newest settled children at a settled parent's start, an abandoned one counting" in {
    val (store, runtime, parent) = fresh()
    val children = Vector(1, 2, 3, 4).map(n => startChild(runtime, parent, n, if n == 1 then "ask" else "go"))
    runtime.start(parent, graph, "next", RunConfig().withChildRetention(1)).awaited.value.completed
    // the suspended child of the earlier run is settled as abandoned, then pruned as the oldest; of the rest the newest
    runtime.children(parent).value.map(_.threadId) shouldBe Vector(children(3))
    Vector(0, 1, 2).map(i => store.latest(children(i)).value) shouldBe Vector(None, None, None)
  }

  it should "settle an earlier run's suspended or running child as abandoned at start, and keep it when newest" in {
    val (store, runtime, parent) = fresh()
    val settled                  = startChild(runtime, parent, 1)
    val asking                   = startChild(runtime, parent, 2, "ask")
    failing.set(true)
    val running =
      try startChild(runtime, parent, 3, "fail")
      finally failing.set(false)
    store.latest(running).value.value.checkpoint.status shouldBe CheckpointStatus.Running

    runtime.start(parent, graph, "next", RunConfig().withChildRetention(2)).awaited.value.completed

    runtime.children(parent).value.map(_.threadId) shouldBe Vector(asking, running)
    store.latest(settled).value shouldBe None
    Vector(asking, running).foreach { child =>
      val latest = store.latest(child).value.value.checkpoint
      latest.status shouldBe CheckpointStatus.Failed
      latest.outcome.value shouldBe a[RunOutcome.Failed]
      latest.outcome.value.asInstanceOf[RunOutcome.Failed].kind shouldBe "abandoned"
    }
  }

  it should "skip an abandoned child busy through the whole run, leave it uncounted, and settle it at the next start" in {
    val (store, runtime, parent) = fresh()
    val asking                   = startChild(runtime, parent, 1, "ask")
    val later                    = startChild(runtime, parent, 2)
    val held                     = store.claim(asking, ClaimRequest(RunId("elsewhere"), None, 1.minute)).value

    runtime.start(parent, graph, "next", RunConfig().withChildRetention(1)).awaited.value.completed
    runtime.children(parent).value.map(_.threadId) shouldBe Vector(asking, later)
    store.latest(asking).value.value.checkpoint.status shouldBe CheckpointStatus.Suspended

    store.release(asking, held.token).value
    runtime.start(parent, graph, "again", RunConfig().withChildRetention(1)).awaited.value.completed
    runtime.children(parent).value.map(_.threadId) shouldBe Vector(later)
  }

  /**
   * A parent graph whose single node, given the input "next", runs `free` - the release of a claim an earlier run's
   * child is held by - and then starts child `own` of `parent`, which fails and stays `Running`, as a child this run
   * leaves behind would. Any other input it just logs.
   */
  private def freeing(runtime: GraphRuntime, parent: ThreadId, own: Int, free: AtomicReference[() => Unit]) =
    val b = GraphBuilder("freeing", "v1")
    val act = b.node[String]("free", writes = Set(log)) { (input, _, context) =>
      if input == "next" then
        free.get()()
        failing.set(true)
        try
          runtime
            .startChild(
              childOf(parent, own),
              graph,
              "fail",
              origin(parent, own),
              RunConfig().withTenantId(context.config.tenantId)
            )
            .awaited
            .value
        finally failing.set(false)
      continue(Command.empty.update(log, input))
    }
    b.compile(act)(_.get(log)).value

  Vector(Durability.Sync, Durability.Async, Durability.OnExit).foreach { durability =>
    it should s"settle and prune at the terminal pass an earlier run's child busy at start and freed since, under $durability (#2073)" in {
      val store   = InMemoryCheckpointer()
      val runtime = GraphRuntime(store, clock)
      val parent  = ThreadId(s"freed-$durability")
      val free    = new AtomicReference[() => Unit](() => ())
      val parents = freeing(runtime, parent, 3, free)
      runtime.start(parent, parents, "go").awaited.value.completed
      val asking = startChild(runtime, parent, 1, "ask")
      val later  = startChild(runtime, parent, 2)
      val held   = store.claim(asking, ClaimRequest(RunId("elsewhere"), None, 1.minute)).value
      free.set(() => store.release(asking, held.token).value)

      runtime.start(parent, parents, "next", RunConfig().withChildRetention(1), durability).awaited.value.completed

      // busy at start, so skipped there; free at the end, so settled as abandoned and then pruned as the oldest
      store.latest(asking).value shouldBe None
      // the run's own unsettled child is never abandoned, and does not count
      runtime.children(parent).value.map(_.threadId) shouldBe Vector(later, childOf(parent, 3))
      store.latest(childOf(parent, 3)).value.value.checkpoint.status shouldBe CheckpointStatus.Running
    }
  }

  /**
   * A store over `store` that hands the claim on `parent` to another run once `trigger` returns true for an operation:
   * the claim this runtime holds is released and a successor's granted, as a takeover after a stall would.
   */
  private class TakingOver(store: InMemoryCheckpointer, parent: ThreadId) extends ForwardingCheckpointer(store) {
    @volatile var token: Option[FencingToken] = None
    @volatile var armed: String => Boolean    = _ => false
    @volatile var takenOver                   = false
    def takeOver(): Unit =
      if !takenOver then
        takenOver = true
        token.foreach(store.release(parent, _).value)
        store.claim(parent, ClaimRequest(RunId("successor"), None, 1.minute)).value: Unit
    override def claim(threadId: ThreadId, request: ClaimRequest) =
      val granted = store.claim(threadId, request)
      if threadId == parent then granted.foreach(c => token = Some(c.token))
      granted
    override def children(of: ThreadId, after: Option[ThreadId], limit: Int) =
      val listed = store.children(of, after, limit)
      if of == parent && armed("children") then takeOver()
      listed
    override def commit(threadId: ThreadId, commit: Commit) =
      // between the parent run's last check of its claim and a commit the parent drives on its child, each of which
      // carries the child's origin: its first, a reattachment's or a settlement's (#2071)
      if threadId != parent && commit.checkpoint.exists(_.origin.isDefined) && armed("child-commit") then takeOver()
      store.commit(threadId, commit)
    override def deleteThread(threadId: ThreadId, token: FencingToken, fence: Option[(ThreadId, FencingToken)]) =
      // between the runtime's last check of the claim above and the store's delete (#2063)
      if threadId != parent && armed("before-delete") then takeOver()
      val deleted = store.deleteThread(threadId, token, fence)
      if threadId != parent && armed("delete") then takeOver()
      deleted
  }

  it should "register no child when the parent is taken over before the child's first commit (#2071)" in {
    val store   = InMemoryCheckpointer()
    val parent  = ThreadId("taken-before-admission")
    val taking  = TakingOver(store, parent)
    val runtime = GraphRuntime(taking, clock)
    runtime.start(parent, graph, "go").awaited.value.completed
    // the parent's run, as the child runner admits its children: holding the parent's claim
    val held = taking.claim(parent, ClaimRequest(RunId("parent-run"), None, 1.minute)).value
    // under the parent's current claim, the fenced first commit lands
    val kept = childOf(parent, 1)
    runtime
      .startChild(kept, graph, "go", origin(parent, 1), RunConfig(), parentClaim = Some(held.token))
      .awaited
      .value
      .completed
    taking.armed = _ == "child-commit"
    val late = childOf(parent, 2)

    runtime.startChild(late, graph, "go", origin(parent, 2), RunConfig(), parentClaim = Some(held.token)) match
      case Left(lost: GraphError.ClaimLost) => lost.threadId shouldBe parent.value
      case other                            => fail(s"expected ClaimLost on the parent, got $other")

    taking.takenOver shouldBe true
    store.latest(late).value shouldBe None
    runtime.children(parent).value.map(_.threadId) shouldBe Vector(kept)
    // the refused child's own claim was given back, not taken for lost
    store.claim(late, ClaimRequest(RunId("next"), None, 1.minute)).isRight shouldBe true
  }

  /** Each admission or settlement the parent drives on an existing child, with the parent's token (#2071). */
  private def driven: Vector[(String, String, (GraphRuntime, ThreadId, FencingToken) => Result[Unit])] = Vector(
    ("recoverChild", "fail", (r, c, t) => r.recoverChild(c, graph, RunConfig(), parentClaim = Some(t)).map(_ => ())),
    (
      "resumeChild",
      "ask",
      (r, c, t) =>
        r.resumeChild(c, graph, Map(InterruptId("0.0") -> ujson.Str("yes")), RunConfig(), parentClaim = Some(t))
          .map(_ => ())
    ),
    (
      "settleChild",
      "fail",
      (r, c, t) =>
        r.settleChild(c, graph, RunOutcome.Failed("down", "down"), "down", RunConfig(), parentClaim = Some(t))
    )
  )

  driven.foreach { (name, input, drive) =>
    it should s"leave the child as it was when the parent is taken over before $name's commit (#2071)" in {
      val store   = InMemoryCheckpointer()
      val parent  = ThreadId(s"taken-before-$name")
      val taking  = TakingOver(store, parent)
      val runtime = GraphRuntime(taking, clock)
      runtime.start(parent, graph, "go").awaited.value.completed
      val child = childOf(parent, 1)
      failing.set(true)
      try runtime.startChild(child, graph, input, origin(parent, 1), RunConfig()).awaited.value
      finally failing.set(false)
      val before = store.latest(child).value.value.checkpoint
      val held   = taking.claim(parent, ClaimRequest(RunId("parent-run"), None, 1.minute)).value
      taking.armed = _ == "child-commit"

      drive(runtime, child, held.token) match
        case Left(lost: GraphError.ClaimLost) => lost.threadId shouldBe parent.value
        case other                            => fail(s"expected ClaimLost on the parent, got $other")

      taking.takenOver shouldBe true
      store.latest(child).value.value.checkpoint.id shouldBe before.id
      // the refused child's own claim was given back, not taken for lost
      store.claim(child, ClaimRequest(RunId("next"), None, 1.minute)).isRight shouldBe true
    }
  }

  it should "leave an abandoned child unsettled when the parent is taken over before the abandon's commit (#2071)" in {
    val store   = InMemoryCheckpointer()
    val parent  = ThreadId("taken-before-abandon")
    val taking  = TakingOver(store, parent)
    val runtime = GraphRuntime(taking, clock)
    runtime.start(parent, graph, "go").awaited.value.completed
    val child = startChild(runtime, parent, 1, "ask")
    taking.armed = _ == "child-commit"

    runtime.start(parent, graph, "next").isLeft shouldBe true

    taking.takenOver shouldBe true
    store.latest(child).value.value.checkpoint.status shouldBe CheckpointStatus.Suspended
    store.claim(child, ClaimRequest(RunId("next"), None, 1.minute)).isRight shouldBe true
  }

  it should "delete nothing when the parent is taken over between the fence's check and a delete (#2063)" in {
    val store   = InMemoryCheckpointer()
    val parent  = ThreadId("taken-before-delete")
    val taking  = TakingOver(store, parent)
    val runtime = GraphRuntime(taking, clock)
    runtime.start(parent, graph, "go").awaited.value.completed
    val children = Vector(1, 2, 3).map(n => startChild(runtime, parent, n))
    taking.armed = _ == "before-delete"

    runtime.start(parent, graph, "next", RunConfig().withChildRetention(0)).isLeft shouldBe true

    taking.takenOver shouldBe true
    children.map(store.latest(_).value.isDefined) shouldBe Vector(true, true, true)
    // the refused child's own claim was given back, not taken for lost
    store.claim(children(0), ClaimRequest(RunId("next"), None, 1.minute)).isRight shouldBe true
  }

  it should "stop the pass before any delete when the parent's claim is lost while the children are listed" in {
    val store   = InMemoryCheckpointer()
    val parent  = ThreadId("stalled-listing")
    val taking  = TakingOver(store, parent)
    val runtime = GraphRuntime(taking, clock)
    runtime.start(parent, graph, "go").awaited.value.completed
    val children = Vector(1, 2, 3).map(n => startChild(runtime, parent, n))
    taking.armed = _ == "children"

    runtime.start(parent, graph, "next", RunConfig().withChildRetention(0)).isLeft shouldBe true

    taking.takenOver shouldBe true
    children.map(store.latest(_).value.isDefined) shouldBe Vector(true, true, true)
  }

  it should "stop the pass at the next delete when the parent's claim is lost part-way" in {
    val store   = InMemoryCheckpointer()
    val parent  = ThreadId("stalled-deletes")
    val taking  = TakingOver(store, parent)
    val runtime = GraphRuntime(taking, clock)
    runtime.start(parent, graph, "go").awaited.value.completed
    val children = Vector(1, 2, 3).map(n => startChild(runtime, parent, n))
    taking.armed = _ == "delete"

    runtime.start(parent, graph, "next", RunConfig().withChildRetention(0)).isLeft shouldBe true

    children.map(store.latest(_).value.isDefined) shouldBe Vector(false, true, true)
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

  Vector(Durability.Sync, Durability.Async, Durability.OnExit).foreach { durability =>
    it should s"keep the newest settled children when a one-shot parent completes under $durability (#2067)" in {
      val runtime = GraphRuntime(InMemoryCheckpointer(), clock)
      val parent  = ThreadId("one-shot")
      runtime
        .start(parent, delegating(runtime, parent, 2), "go", RunConfig().withChildRetention(1), durability)
        .awaited
        .value
        .completed
      runtime.children(parent).value.map(_.threadId) shouldBe Vector(childOf(parent, 2))
    }
  }

  it should "leave the pass to the next start when an Async parent's buffered commits fail to drain" in {
    val store  = InMemoryCheckpointer()
    val parent = ThreadId("undrained")
    val flaky = new ForwardingCheckpointer(store) {
      override def commit(threadId: ThreadId, commit: Commit) =
        if threadId == parent && commit.checkpoint.exists(_.status == CheckpointStatus.Completed)
        then Left(ValidationError("store", "down"))
        else store.commit(threadId, commit)
    }
    val runtime = GraphRuntime(flaky, clock)
    runtime
      .start(parent, delegating(runtime, parent, 2), "go", RunConfig().withChildRetention(1), Durability.Async)
      .awaited
      .value
      .failed
    runtime.children(parent).value.map(_.threadId) shouldBe Vector(childOf(parent, 1), childOf(parent, 2))
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
      override def deleteThread(threadId: ThreadId, token: FencingToken, fence: Option[(ThreadId, FencingToken)]) =
        if failDelete.get && threadId == childOf(parent, 1) then Left(ValidationError("store", "down"))
        else store.deleteThread(threadId, token, fence)
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

  it should "prune at a blocked parent's next start, though not at the Block itself" in {
    val (store, runtime, parent) = fresh()
    runtime.start(parent, graph, "block").awaited.value.failed
    val children = Vector(1, 2).map(n => startChild(runtime, parent, n))
    // the Block that ends a run prunes nothing ...
    runtime.start(parent, graph, "block", RunConfig().withChildRetention(1)).awaited.value.failed
    runtime.children(parent).value.map(_.threadId) shouldBe Vector(children(1))
    val third = startChild(runtime, parent, 3)
    // ... but a blocked parent's start admission does: every settled child is prunable there
    runtime.start(parent, graph, "block", RunConfig().withChildRetention(0)).awaited.value.failed
    runtime.children(parent).value shouldBe empty
    store.latest(third).value shouldBe None
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
