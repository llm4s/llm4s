package org.llm4s.agent.graph

import org.llm4s.agent.graph.GraphTestSupport.*
import org.llm4s.error.ValidationError
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }
import java.util.concurrent.{ ConcurrentHashMap, CountDownLatch, TimeUnit }

class BreakpointSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val log     = StateKey.appending[String]("log")
  private val results = StateKey.appending[String]("results")
  private val summary = StateKey.appending[String]("summary")
  private val thread  = ThreadId("t")

  private def nodes(ids: String*): Set[NodeId] = ids.map(NodeId(_)).toSet

  private def proceed(ids: String*): Map[InterruptId, ujson.Value] =
    ids.map(id => InterruptId(id) -> Breakpoint.proceed).toMap

  /** `start(n)` logs `start:n` and goes to `middle`, which logs and goes to `end`, which logs; each run is counted. */
  final private class Chain(failMiddleOnce: Boolean = false) {
    val runs                      = new ConcurrentHashMap[String, AtomicInteger]()
    def runsOf(node: String): Int = Option(runs.get(node)).fold(0)(_.get)
    private def ran(node: String) = runs.computeIfAbsent(node, _ => new AtomicInteger()).incrementAndGet()
    private val failed            = new AtomicBoolean(!failMiddleOnce)

    val graph: CompiledGraph[Int, Vector[String]] = {
      val b = GraphBuilder("chain", "v1")
      val end = b.node[Unit]("end", writes = Set(log)) { (_, _, _) =>
        ran("end"); continue(Command.empty.update(log, "end"))
      }
      val middle = b.declare[Unit]("middle")
      b.implement(middle, writes = Set(log)) { (_, _, _) =>
        ran("middle")
        if failed.compareAndSet(false, true) then NodeResult.Fail(ValidationError("middle", "failed once"))
        else continue(Command.empty.update(log, "middle").goto(end))
      }
      val start = b.node[Int]("start", writes = Set(log)) { (n, _, _) =>
        ran("start")
        continue(Command.empty.update(log, s"start:$n").goto(middle))
      }
      b.compile(start)(_.get(log)).value
    }
  }

  /** `plan` fans its items out to `worker` (dynamic join -> `summarize`); a worker records its item upper-cased. */
  final private class FanOut {
    val runs                      = new ConcurrentHashMap[String, AtomicInteger]()
    def runsOf(item: String): Int = Option(runs.get(item)).fold(0)(_.get)

    val (graph, approve) = {
      val b       = GraphBuilder("fan-out", "v1")
      val approve = b.declareResume[String, String]("approve")
      b.implement(approve.node, writes = Set(results)) { (resumed, _, _) =>
        continue(Command.empty.update(results, s"${resumed.question}:${resumed.answer}"))
      }
      val worker = b.node[String]("worker", writes = Set(results)) { (item, _, _) =>
        runs.computeIfAbsent(item, _ => new AtomicInteger()).incrementAndGet()
        if item.startsWith("ask") then NodeResult.Suspend(StateUpdate.empty, item, approve)
        else continue(Command.empty.update(results, item.toUpperCase))
      }
      val summarize = b.node[Unit]("summarize", writes = Set(summary)) { (_, state, _) =>
        NodeResult.fromResult(state.get(results).map(r => Command.empty.update(summary, r.sorted.mkString(","))))
      }
      val join = b.dynamicJoin("workers", summarize)
      val plan = b.node[Vector[String]]("plan", writes = Set(log)) { (items, _, _) =>
        continue(Command.empty.update(log, "planned").fanOut(join, worker, items))
      }
      (b.compile(plan)(_.get(summary)).value, approve)
    }
  }

  "An interruptBefore breakpoint" should "hold a task before its node runs, and run it once the interrupt is answered" in {
    val chain   = new Chain
    val runtime = GraphRuntime.inMemory()
    val config  = RunConfig(interruptBefore = nodes("middle"))

    val held = runtime.start(thread, chain.graph, 1, config).awaited.value.suspended
    held.interrupts shouldBe Vector(
      PendingInterrupt(InterruptId("1.0"), NodeId("middle"), ujson.Null, Some(BreakpointPhase.Before))
    )
    held.state.get(log).value shouldBe Vector("start:1")
    chain.runsOf("middle") shouldBe 0
    held.execution.pendingInterrupts shouldBe Vector(InterruptId("1.0"))

    // the same breakpoint does not hold the continuation again
    val (_, output) = runtime.resume(thread, chain.graph, proceed("1.0"), config).awaited.value.completed
    output shouldBe Vector("start:1", "middle", "end")
    chain.runsOf("middle") shouldBe 1
  }

  it should "report the held task's input as its question" in {
    val chain = new Chain
    val held  = runInMemory(chain.graph, 7, RunConfig(interruptBefore = nodes("start"))).suspended
    held.interrupts.map(i => (i.resumeNode, i.question, i.breakpoint)) shouldBe
      Vector((NodeId("start"), ujson.Num(7), Some(BreakpointPhase.Before)))
    chain.runsOf("start") shouldBe 0
  }

  it should "hold every task of the node, one interrupt each, while their siblings run" in {
    val f      = new FanOut
    val config = RunConfig(interruptBefore = nodes("worker"))
    val held   = runInMemory(f.graph, Vector("a", "b"), config).suspended
    held.interrupts.map(_.id) shouldBe Vector(InterruptId("1.0"), InterruptId("1.1"))
    held.interrupts.map(_.question) shouldBe Vector(ujson.Str("a"), ujson.Str("b"))
    f.runsOf("a") shouldBe 0
  }

  it should "take answers one at a time, the join waiting until every held task has run" in {
    val f       = new FanOut
    val runtime = GraphRuntime.inMemory()
    val config  = RunConfig(interruptBefore = nodes("worker"))
    runtime.start(thread, f.graph, Vector("a", "b"), config).awaited.value.suspended

    val partly = runtime.resume(thread, f.graph, proceed("1.1"), config).awaited.value.suspended
    partly.interrupts.map(_.id) shouldBe Vector(InterruptId("1.0"))
    partly.state.get(results).value shouldBe Vector("B")
    partly.state.get(summary).value shouldBe empty
    f.runsOf("a") shouldBe 0

    val (_, output) = runtime.resume(thread, f.graph, proceed("1.0"), config).awaited.value.completed
    output shouldBe Vector("A,B")
    (f.runsOf("a"), f.runsOf("b")) shouldBe ((1, 1))
  }

  it should "be answered together with a node's own typed interrupt, any subset at a time" in {
    val f       = new FanOut
    val runtime = GraphRuntime.inMemory()
    // `ask` suspends with a typed question, `b` is held by the breakpoint: two interrupts, of two kinds
    val config = RunConfig(interruptBefore = nodes("summarize"))
    val first  = runtime.start(thread, f.graph, Vector("ask", "b"), config).awaited.value.suspended
    first.interrupts shouldBe Vector(PendingInterrupt(InterruptId("1.0"), NodeId("approve"), ujson.Str("ask")))

    val answered = runtime.resume(thread, f.graph, Map(InterruptId("1.0") -> f.approve.answer("yes")), config)
    val atJoin   = answered.awaited.value.suspended
    atJoin.interrupts.map(i => (i.resumeNode, i.breakpoint)) shouldBe
      Vector((NodeId("summarize"), Some(BreakpointPhase.Before)))
    atJoin.state.get(results).value shouldBe Vector("B", "ask:yes")

    val held = atJoin.interrupts.head.id
    runtime.resume(thread, f.graph, Map(held -> Breakpoint.proceed), config).awaited.value.completed._2 shouldBe
      Vector("B,ask:yes")
  }

  it should "hold a typed interrupt's continuation at its resume node, and pass the answer on" in {
    val f       = new FanOut
    val runtime = GraphRuntime.inMemory()
    val config  = RunConfig(interruptBefore = nodes("approve"))
    runtime.start(thread, f.graph, Vector("ask"), config).awaited.value.suspended
    val held = runtime
      .resume(thread, f.graph, Map(InterruptId("1.0") -> f.approve.answer("ok")), config)
      .awaited
      .value
      .suspended
    held.interrupts.map(i => (i.resumeNode, i.breakpoint)) shouldBe Vector(
      (NodeId("approve"), Some(BreakpointPhase.Before))
    )
    held.interrupts.head.question shouldBe ujson.Obj("question" -> "ask", "answer" -> "ok")
    runtime.resume(thread, f.graph, proceed(held.interrupts.head.id.value), config).awaited.value.completed._2 shouldBe
      Vector("ask:ok")
  }

  "An interruptAfter breakpoint" should "commit the task's update and hold what follows it until answered" in {
    val chain   = new Chain
    val runtime = GraphRuntime.inMemory()
    val config  = RunConfig(interruptAfter = nodes("middle"))

    val held = runtime.start(thread, chain.graph, 2, config).awaited.value.suspended
    held.interrupts shouldBe Vector(
      PendingInterrupt(InterruptId("1.0"), NodeId("middle"), ujson.Null, Some(BreakpointPhase.After))
    )
    held.state.get(log).value shouldBe Vector("start:2", "middle")
    chain.runsOf("end") shouldBe 0

    val (_, output) = runtime.resume(thread, chain.graph, proceed("1.0"), config).awaited.value.completed
    output shouldBe Vector("start:2", "middle", "end")
    chain.runsOf("middle") shouldBe 1
  }

  it should "hold a fan-out's children and its join activation, releasing them once answered" in {
    val f       = new FanOut
    val runtime = GraphRuntime.inMemory()
    val config  = RunConfig(interruptAfter = nodes("plan"))
    val held    = runtime.start(thread, f.graph, Vector("a", "b"), config).awaited.value.suspended
    held.state.get(log).value shouldBe Vector("planned")
    (f.runsOf("a"), f.runsOf("b")) shouldBe ((0, 0))

    runtime.resume(thread, f.graph, proceed("0.0"), config).awaited.value.completed._2 shouldBe Vector("A,B")
  }

  it should "keep a join closed while a held task's arrival waits" in {
    val f       = new FanOut
    val runtime = GraphRuntime.inMemory()
    val config  = RunConfig(interruptAfter = nodes("worker"))
    val held    = runtime.start(thread, f.graph, Vector("a", "b"), config).awaited.value.suspended
    held.interrupts.map(_.id) shouldBe Vector(InterruptId("1.0"), InterruptId("1.1"))
    held.state.get(results).value shouldBe Vector("A", "B")

    val partly = runtime.resume(thread, f.graph, proceed("1.0"), config).awaited.value.suspended
    partly.interrupts.map(_.id) shouldBe Vector(InterruptId("1.1"))
    partly.state.get(summary).value shouldBe empty

    runtime.resume(thread, f.graph, proceed("1.1"), config).awaited.value.completed._2 shouldBe Vector("A,B")
    (f.runsOf("a"), f.runsOf("b")) shouldBe ((1, 1))
  }

  it should "fire the held node's static edges once answered" in {
    val b     = GraphBuilder("edges", "v1")
    val after = b.node[Unit]("after", writes = Set(log))((_, _, _) => continue(Command.empty.update(log, "after")))
    val first = b.node[Unit]("first", writes = Set(log))((_, _, _) => continue(Command.empty.update(log, "first")))
    b.edge(first, after)
    val graph   = b.compile(first)(_.get(log)).value
    val runtime = GraphRuntime.inMemory()
    val config  = RunConfig(interruptAfter = nodes("first"))
    runtime.start(thread, graph, (), config).awaited.value.suspended.state.get(log).value shouldBe Vector("first")
    runtime.resume(thread, graph, proceed("0.0"), config).awaited.value.completed._2 shouldBe Vector("first", "after")
  }

  "A node in both lists" should "be held before it runs, then after" in {
    val chain   = new Chain
    val runtime = GraphRuntime.inMemory()
    val config  = RunConfig(interruptBefore = nodes("middle"), interruptAfter = nodes("middle"))
    runtime.start(thread, chain.graph, 3, config).awaited.value.suspended.interrupts.head.breakpoint shouldBe
      Some(BreakpointPhase.Before)
    val after = runtime.resume(thread, chain.graph, proceed("1.0"), config).awaited.value.suspended
    after.interrupts.map(i => (i.resumeNode, i.breakpoint)) shouldBe Vector(
      (NodeId("middle"), Some(BreakpointPhase.After))
    )
    runtime
      .resume(thread, chain.graph, proceed(after.interrupts.head.id.value), config)
      .awaited
      .value
      .completed
      ._2 shouldBe Vector("start:3", "middle", "end")
    chain.runsOf("middle") shouldBe 1
  }

  "Resuming a breakpoint" should "refuse an answer other than null, changing nothing" in {
    val chain   = new Chain
    val cp      = new InMemoryCheckpointer
    val runtime = new GraphRuntime(cp)
    val config  = RunConfig(interruptBefore = nodes("middle"))
    runtime.start(thread, chain.graph, 1, config).awaited.value.suspended
    val before = cp.latest(thread).value.get.checkpoint.id

    val refused = runtime.resume(thread, chain.graph, Map(InterruptId("1.0") -> ujson.Str("go")), config)
    refused.left.value shouldBe GraphError.InvalidResume(
      "chain",
      List("the answer to breakpoint '1.0' must be null (Breakpoint.proceed)")
    )
    cp.latest(thread).value.get.checkpoint.id shouldBe before
    chain.runsOf("middle") shouldBe 0
  }

  it should "let start and recover refuse the held thread, as for any pending interrupt" in {
    val chain   = new Chain
    val runtime = GraphRuntime.inMemory()
    val config  = RunConfig(interruptBefore = nodes("middle"))
    runtime.start(thread, chain.graph, 1, config).awaited.value.suspended
    runtime.start(thread, chain.graph, 2, config).left.value shouldBe GraphError.PendingInterrupts("t", List("1.0"))
    runtime.recover(thread, chain.graph, config).left.value shouldBe GraphError.PendingInterrupts("t", List("1.0"))
  }

  it should "belong to the resuming run: a resume without the breakpoint runs the rest unheld" in {
    val chain   = new Chain
    val runtime = GraphRuntime.inMemory()
    runtime.start(thread, chain.graph, 1, RunConfig(interruptBefore = nodes("middle", "end"))).awaited.value.suspended
    runtime.resume(thread, chain.graph, proceed("1.0")).awaited.value.completed._2 shouldBe
      Vector("start:1", "middle", "end")
  }

  "A breakpoint on a node the graph does not have" should "be refused at admission, before the thread is read" in {
    val chain   = new Chain
    val runtime = GraphRuntime.inMemory()
    val config  = RunConfig(interruptBefore = nodes("nope", "middle"), interruptAfter = nodes("missing"))
    val error   = runtime.start(thread, chain.graph, 1, config).left.value
    error shouldBe ValidationError(
      "breakpoints",
      List(
        "interruptBefore names node 'nope', which graph 'chain' does not have",
        "interruptAfter names node 'missing', which graph 'chain' does not have"
      )
    )
    runtime.recover(thread, chain.graph, config).left.value shouldBe error
    runtime.resume(thread, chain.graph, proceed("1.0"), config).left.value shouldBe error
    runtime.start(thread, chain.graph, 1).awaited.value.completed // the thread was never touched
  }

  it should "fail a step" in {
    val chain = new Chain
    val (_, error) =
      drive(chain.graph, chain.graph.start(1), RunConfig(interruptAfter = nodes("nope"))).failed
    error shouldBe a[ValidationError]
  }

  "Stepping a graph in memory" should "hold tasks as a runtime does, and resume them" in {
    val chain  = new Chain
    val config = RunConfig(interruptBefore = nodes("end"), interruptAfter = nodes("start"))
    val first  = drive(chain.graph, chain.graph.start(4), config).suspended
    first.interrupts.map(_.breakpoint) shouldBe Vector(Some(BreakpointPhase.After))
    val second = drive(chain.graph, chain.graph.resume(first.execution, proceed("0.0")).value, config).suspended
    // the held task's continuation runs nothing, but takes a superstep: `end` is task 3.0
    second.interrupts.map(i => (i.id, i.breakpoint)) shouldBe Vector((InterruptId("3.0"), Some(BreakpointPhase.Before)))
    drive(chain.graph, chain.graph.resume(second.execution, proceed("3.0")).value, config).completed._2 shouldBe
      Vector("start:4", "middle", "end")
  }

  "A held thread" should "restore in another graph instance, in either phase, after a JSON round trip" in {
    val cp      = new InMemoryCheckpointer
    val config  = RunConfig(interruptBefore = nodes("worker"), interruptAfter = nodes("plan"))
    val first   = new FanOut
    val runtime = new GraphRuntime(cp)
    runtime.start(thread, first.graph, Vector("a"), config).awaited.value.suspended

    def roundTripped(): Unit = {
      val stored = cp.latest(thread).value.get.checkpoint
      Checkpoint.fromJson(Checkpoint.toJson(stored)).value shouldBe stored
    }
    roundTripped()
    // a new process: the same graph, built again
    val second = new FanOut
    val held   = new GraphRuntime(cp).resume(thread, second.graph, proceed("0.0"), config).awaited.value.suspended
    held.interrupts.map(i => (i.id, i.breakpoint)) shouldBe Vector((InterruptId("2.0"), Some(BreakpointPhase.Before)))
    roundTripped()
    val third = new FanOut
    new GraphRuntime(cp).resume(thread, third.graph, proceed("2.0"), config).awaited.value.completed._2 shouldBe
      Vector("A")
    third.runsOf("a") shouldBe 1
  }

  it should "report held data the graph cannot restore" in {
    val f      = new FanOut
    val cp     = new InMemoryCheckpointer
    val config = RunConfig(interruptAfter = nodes("plan"))
    new GraphRuntime(cp).start(thread, f.graph, Vector("a"), config).awaited.value.suspended
    val snapshot = cp.latest(thread).value.get.checkpoint.snapshot
    val parked   = snapshot.parked.head
    parked.breakpoint shouldBe Some(BreakpointPhase.After)

    val badRoute = snapshot.copy(parked = Vector(parked.copy(heldRoutes = Vector(EncodedRoute.Goto("nowhere")))))
    f.graph.restore(badRoute).left.value.message should include(
      "interrupt 0.0 holds a route that does not restore: unknown node 'nowhere'"
    )
    val badNode = snapshot.copy(parked = Vector(parked.copy(resumeNode = "nowhere")))
    f.graph.restore(badNode).left.value.message should include("interrupt 0.0 holds unknown node 'nowhere'")
    val badInput = snapshot.copy(parked =
      Vector(
        parked.copy(
          resumeNode = "worker",
          breakpoint = Some(BreakpointPhase.Before),
          question = VersionedJson(1, ujson.Obj())
        )
      )
    )
    f.graph.restore(badInput).left.value.message should include("interrupt 0.0 input does not decode for node 'worker'")
    val badOrigin = snapshot.copy(parked = Vector(parked.copy(originNode = "nowhere")))
    f.graph.restore(badOrigin).left.value.message should include("interrupt 0.0 continues unknown node 'nowhere'")
    val badReplay = snapshot.copy(frontier =
      Vector(
        GraphSnapshot.PendingTask(
          "1.0",
          "plan",
          VersionedJson(1, ujson.Null),
          None,
          None,
          Some("0.0"),
          Some("plan"),
          replay = Some(Vector(EncodedRoute.Goto("nowhere")))
        )
      )
    )
    f.graph.restore(badReplay).left.value.message should include(
      "pending task 1.0 holds a route that does not restore: unknown node 'nowhere'"
    )
  }

  "Cancelling a run" should "leave a task that a breakpoint would hold for recover, which holds it" in {
    val entered  = new CountDownLatch(1)
    val blocking = new AtomicBoolean(true)
    val heldRuns = new AtomicInteger(0)
    val b        = GraphBuilder("slow", "v1")
    val held = b.node[Unit]("held", writes = Set(log)) { (_, _, _) =>
      heldRuns.incrementAndGet()
      continue(Command.empty.update(log, "held"))
    }
    val slow = b.node[Unit]("slow", writes = Set(log)) { (_, _, _) =>
      if blocking.get then {
        entered.countDown()
        Thread.sleep(10000)
      }
      continue(Command.empty.update(log, "slow").goto(held))
    }
    val graph   = b.compile(slow)(_.get(log)).value
    val runtime = GraphRuntime.inMemory()
    val config  = RunConfig(interruptBefore = nodes("held"))
    val handle  = runtime.start(thread, graph, (), config).value
    entered.await(5, TimeUnit.SECONDS) shouldBe true
    handle.cancel()
    awaitResult(handle).value.failed._2 shouldBe a[GraphError.Cancelled]

    blocking.set(false)
    val suspended = runtime.recover(thread, graph, config).awaited.value.suspended
    suspended.interrupts.map(i => (i.resumeNode, i.breakpoint)) shouldBe Vector(
      (NodeId("held"), Some(BreakpointPhase.Before))
    )
    heldRuns.get shouldBe 0
    // a held run has ended: cancelling its handle afterwards changes nothing
    handle.cancel()
    runtime
      .resume(thread, graph, proceed(suspended.interrupts.head.id.value), config)
      .awaited
      .value
      .completed
      ._2 shouldBe
      Vector("slow", "held")
  }

  it should "cancel a resumed continuation, and recover runs it again without holding it" in {
    val entered  = new CountDownLatch(1)
    val blocking = new AtomicBoolean(true)
    val runs     = new AtomicInteger(0)
    val b        = GraphBuilder("resume-cancel", "v1")
    val gate = b.node[Unit]("gate", writes = Set(log)) { (_, _, _) =>
      runs.incrementAndGet()
      if blocking.get then {
        entered.countDown()
        Thread.sleep(10000)
      }
      continue(Command.empty.update(log, "gate"))
    }
    val start =
      b.node[Unit]("start", writes = Set(log))((_, _, _) => continue(Command.empty.update(log, "start").goto(gate)))
    val graph   = b.compile(start)(_.get(log)).value
    val cp      = new InMemoryCheckpointer
    val runtime = new GraphRuntime(cp)
    val config  = RunConfig(interruptBefore = nodes("gate"))
    runtime.start(thread, graph, (), config).awaited.value.suspended

    val handle = runtime.resume(thread, graph, proceed("1.0"), config).value
    entered.await(5, TimeUnit.SECONDS) shouldBe true
    handle.cancel()
    awaitResult(handle).value.failed._2 shouldBe a[GraphError.Cancelled]
    val stored = cp.latest(thread).value.get.checkpoint
    stored.status shouldBe CheckpointStatus.Running
    stored.snapshot.frontier.map(t => (t.nodeId, t.passedBefore)) shouldBe Vector(("gate", true))

    blocking.set(false)
    runtime.recover(thread, graph, config).awaited.value.completed._2 shouldBe Vector("start", "gate")
    runs.get shouldBe 2
  }

  "Recovering a failed run" should "not hold a continuation that passed its breakpoint" in {
    val chain   = new Chain(failMiddleOnce = true)
    val runtime = GraphRuntime.inMemory()
    val config  = RunConfig(interruptBefore = nodes("middle"))
    runtime.start(thread, chain.graph, 5, config).awaited.value.suspended
    runtime
      .resume(thread, chain.graph, proceed("1.0"), config)
      .awaited
      .value
      .failed
      ._2 shouldBe a[GraphError.NodeFailed]
    runtime.recover(thread, chain.graph, config).awaited.value.completed._2 shouldBe Vector("start:5", "middle", "end")
    chain.runsOf("middle") shouldBe 2
  }

  it should "reuse a held sibling's write and hold it again, without running it twice" in {
    val runs    = new AtomicInteger(0)
    val failing = new AtomicBoolean(true)
    val b       = GraphBuilder("siblings", "v1")
    val kept = b.node[String]("kept", writes = Set(results)) { (item, _, _) =>
      runs.incrementAndGet()
      continue(Command.empty.update(results, item))
    }
    val flaky = b.node[String]("flaky", writes = Set(results)) { (item, _, _) =>
      if failing.getAndSet(false) then NodeResult.Fail(ValidationError("flaky", "once"))
      else continue(Command.empty.update(results, item))
    }
    val start   = b.node[Unit]("start")((_, _, _) => continue(Command.empty.send(kept, "k").send(flaky, "f")))
    val graph   = b.compile(start)(_.get(results)).value
    val runtime = GraphRuntime.inMemory()
    val config  = RunConfig(interruptAfter = nodes("kept"))
    runtime.start(thread, graph, (), config).awaited.value.failed._2 shouldBe a[GraphError.NodeFailed]
    val held = runtime.recover(thread, graph, config).awaited.value.suspended
    held.interrupts.map(i => (i.resumeNode, i.breakpoint)) shouldBe Vector(
      (NodeId("kept"), Some(BreakpointPhase.After))
    )
    runs.get shouldBe 1
    runtime.resume(thread, graph, proceed(held.interrupts.head.id.value), config).awaited.value.completed._2 shouldBe
      Vector("k", "f")
    runs.get shouldBe 1
  }

  "Every durability mode" should "persist a held thread before the run returns" in {
    Durability.values.foreach { durability =>
      val chain   = new Chain
      val cp      = new InMemoryCheckpointer
      val runtime = new GraphRuntime(cp)
      val config  = RunConfig(interruptAfter = nodes("start"))
      runtime.start(thread, chain.graph, 1, config, durability).awaited.value.suspended
      cp.latest(thread).value.get.checkpoint.status shouldBe CheckpointStatus.Suspended
      new GraphRuntime(cp)
        .resume(thread, chain.graph, proceed("0.0"), config, durability)
        .awaited
        .value
        .completed
        ._2 shouldBe
        Vector("start:1", "middle", "end")
    }
  }

  "The run's events" should "list a held task's interrupt in RunSuspended and its answer in RunResumed" in {
    val chain   = new Chain
    val cp      = new InMemoryCheckpointer
    val runtime = new GraphRuntime(cp)
    val config  = RunConfig(interruptBefore = nodes("end"))
    runtime.start(thread, chain.graph, 1, config).awaited.value.suspended
    runtime.resume(thread, chain.graph, proceed("2.0"), config).awaited.value.completed
    val events = cp.eventsAfter(thread, 0L, 100).value.map(_.event)
    events should contain(RunEvent.RunSuspended(Vector("2.0")))
    events.collect { case r: RunEvent.RunResumed => r.answered } shouldBe Vector(Vector("2.0"))
  }
}
