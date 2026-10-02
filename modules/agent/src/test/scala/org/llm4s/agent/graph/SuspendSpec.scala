package org.llm4s.agent.graph

import org.llm4s.agent.graph.GraphTestSupport.*
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class SuspendSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val results = StateKey.appending[String]("results")
  private val log     = StateKey.appending[String]("log")
  private val asked   = StateKey.appending[String]("asked")

  /**
   * plan fans items out to workers (dynamic join -> summarize). A worker whose item is in `ask`
   * records that it asked and suspends with the item as its question; `approve` resumes it: "yes"
   * records `item!`, "again" suspends once more with the same question, anything else `item-no`.
   */
  final private class Fixture(ask: Set[String], version: String = "v1") {
    val calls                      = new ConcurrentHashMap[String, AtomicInteger]()
    def callsOf(item: String): Int = Option(calls.get(item)).fold(0)(_.get)

    val (graph, approve) = {
      val b       = GraphBuilder("approvals", version)
      val approve = b.declareResume[String, String]("approve")
      b.implement(approve.node, writes = Set(results)) { (resumed, _, _) =>
        resumed.answer match {
          case "yes"   => continue(Command.empty.update(results, s"${resumed.question}!"))
          case "again" => NodeResult.Suspend(StateUpdate.empty, resumed.question, approve)
          case _       => continue(Command.empty.update(results, s"${resumed.question}-no"))
        }
      }
      val worker = b.node[String]("worker", writes = Set(results, asked)) { (item, _, _) =>
        calls.computeIfAbsent(item, _ => new AtomicInteger()).incrementAndGet()
        if ask.contains(item) then NodeResult.Suspend(StateUpdate.update(asked, item), item, approve)
        else continue(Command.empty.update(results, item.toUpperCase))
      }
      val summarize = b.node[Unit]("summarize", writes = Set(log)) { (_, state, _) =>
        NodeResult.fromResult(state.get(results).map(r => Command.empty.update(log, r.mkString(","))))
      }
      val join = b.dynamicJoin("workers", summarize)
      val plan = b.node[Vector[String]]("plan")((items, _, _) => continue(Command.empty.fanOut(join, worker, items)))
      (b.compile(plan)(_.get(log)).value, approve)
    }

    def answers(pairs: (String, String)*): Map[InterruptId, ujson.Value] =
      pairs.map((id, answer) => InterruptId(id) -> approve.answer(answer)).toMap
  }

  "A suspension" should "pause the whole run after its superstep, committing its siblings and its own update" in {
    val f         = Fixture(ask = Set("b"))
    val suspended = f.graph.run(Vector("a", "b", "c")).suspended
    suspended.interrupts shouldBe Vector(PendingInterrupt(InterruptId("1.1"), NodeId("approve"), ujson.Str("b")))
    suspended.state.get(results).value shouldBe Vector("A", "C")
    suspended.state.get(asked).value shouldBe Vector("b")
    suspended.state.get(log).value shouldBe empty
    suspended.execution.isPaused shouldBe true
    suspended.execution.pendingInterrupts shouldBe Vector(InterruptId("1.1"))
    // stepping a paused execution does not advance it
    f.graph.step(suspended.execution) match {
      case Step.Done(RunResult.Suspended(_, interrupts, _)) => interrupts.map(_.id) shouldBe Vector(InterruptId("1.1"))
      case other                                            => fail(other.toString)
    }
  }

  it should "keep the barrier closed until every parked call is answered, in separate resumes" in {
    val f     = Fixture(ask = Set("b", "c"))
    val first = f.graph.run(Vector("a", "b", "c", "d")).suspended
    first.interrupts.map(_.id.value) shouldBe Vector("1.1", "1.2")

    val second = f.graph.runFrom(f.graph.resume(first.execution, f.answers("1.2" -> "yes")).value).suspended
    second.interrupts.map(_.id.value) shouldBe Vector("1.1")
    second.state.get(results).value shouldBe Vector("A", "D", "c!")
    second.state.get(log).value shouldBe empty // summarize has not run
    second.execution.isPaused shouldBe false   // quiescent, waiting only on a parked continuation

    val (state, summary) = f.graph.runFrom(f.graph.resume(second.execution, f.answers("1.1" -> "no")).value).completed
    summary shouldBe Vector("A,D,c!,b-no")
    state.get(asked).value shouldBe Vector("b", "c")
    Seq("a", "b", "c", "d").map(f.callsOf) shouldBe Seq(1, 1, 1, 1) // continuations do not re-run the worker
  }

  it should "let a continuation suspend again in the name of the original task" in {
    val f       = Fixture(ask = Set("a"))
    val first   = f.graph.run(Vector("a", "b")).suspended
    val again   = f.graph.runFrom(f.graph.resume(first.execution, f.answers("1.0" -> "again")).value).suspended
    val reasked = again.interrupts.loneElementOr(fail("one interrupt"))
    reasked.question shouldBe ujson.Str("a")
    reasked.id should not be InterruptId("1.0")
    f.graph
      .runFrom(f.graph.resume(again.execution, Map(reasked.id -> f.approve.answer("yes"))).value)
      .completed
      ._2 shouldBe
      Vector("B,a!")
  }

  it should "make a static join arrival for the suspended node when its continuation completes" in {
    val b = GraphBuilder("static", "v1")
    val done = b.resumeNode[String, String]("left-done", writes = Set(log))((r, _, _) =>
      continue(Command.empty.update(log, s"left:${r.answer}"))
    )
    val left  = b.node[Unit]("left")((_, _, _) => NodeResult.Suspend(StateUpdate.empty, "left?", done))
    val right = b.node[Unit]("right", writes = Set(log))((_, _, _) => continue(Command.empty.update(log, "right")))
    val merge = b.node[Unit]("merge", writes = Set(log))((_, _, _) => continue(Command.empty.update(log, "merge")))
    val start = b.node[Unit]("start")((_, _, _) => continue(Command.empty.goto(left).goto(right)))
    b.staticJoin("both", Set(left, right), merge)
    val graph = b.compile(start)(_.get(log)).value

    val suspended = graph.run(()).suspended
    suspended.state.get(log).value shouldBe Vector("right")
    graph
      .runFrom(graph.resume(suspended.execution, Map(InterruptId("1.0") -> done.answer("ok"))).value)
      .completed
      ._2 shouldBe
      Vector("right", "left:ok", "merge")
  }

  it should "fail a join that no task and no parked continuation can satisfy" in {
    val b       = GraphBuilder("unsatisfiable", "v1")
    val resumeX = b.resumeNode[String, String]("x-done")((_, _, _) => continue(Command.empty))
    val x       = b.node[Unit]("x")((_, _, _) => NodeResult.Suspend(StateUpdate.empty, "x?", resumeX))
    val y       = b.node[Unit]("y")((_, _, _) => continue(Command.empty))
    val z       = b.node[Unit]("z")((_, _, _) => continue(Command.empty))
    val merge   = b.node[Unit]("merge")((_, _, _) => continue(Command.empty))
    val start   = b.node[Unit]("start")((_, _, _) => continue(Command.empty.goto(x).goto(y)))
    b.staticJoin("never", Set(y, z), merge)
    val graph = b.compile(start)(_ => Right(())).value

    val suspended = graph.run(()).suspended
    graph
      .runFrom(graph.resume(suspended.execution, Map(InterruptId("1.0") -> resumeX.answer("go"))).value)
      .failed
      ._2 shouldBe
      GraphError.UnsatisfiedJoin(JoinId("never"), List("node 'z'"))
  }

  it should "check a suspension's update and resume node like any other result" in {
    val other   = GraphBuilder("other", "v1")
    val foreign = other.resumeNode[String, String]("foreign")((_, _, _) => continue(Command.empty))
    val b       = GraphBuilder("checked", "v1")
    val mine    = b.resumeNode[String, String]("mine")((_, _, _) => continue(Command.empty))
    val sneaky  = b.node[Unit]("sneaky")((_, _, _) => NodeResult.Suspend(StateUpdate.update(log, "x"), "q", mine))
    val astray  = b.node[Unit]("astray")((_, _, _) => NodeResult.Suspend(StateUpdate.empty, "q", foreign))
    b.stateKey(log)
    def errorOf(entry: NodeRef[Unit]) = b.compile(entry)(_ => Right(())).value.run(()).failed._2
    errorOf(sneaky) shouldBe GraphError.UndeclaredWrite(NodeId("sneaky"), TaskId("0.0"), log.id)
    errorOf(astray) shouldBe
      GraphError.InvalidRoute(NodeId("astray"), TaskId("0.0"), "resume node 'foreign' is not part of this graph")
  }

  "Resume" should "refuse no answers, unknown interrupts and answers that do not decode, changing nothing" in {
    val f         = Fixture(ask = Set("a"))
    val execution = f.graph.run(Vector("a")).suspended.execution
    def problems(answers: Map[InterruptId, ujson.Value]) = f.graph.resume(execution, answers).left.value match {
      case GraphError.InvalidResume(_, found) => found
      case other                              => fail(other.toString)
    }
    problems(Map.empty) shouldBe List("no answers were given")
    problems(f.answers("9.9" -> "yes")) shouldBe List("interrupt '9.9' is not pending")
    problems(Map(InterruptId("1.0") -> ujson.Arr(1))).loneElementOr(fail("one problem")) should
      startWith("the answer to interrupt '1.0' does not decode")
    execution.pendingInterrupts shouldBe Vector(InterruptId("1.0"))
    Fixture(ask = Set("a")).graph
      .resume(execution, f.answers("1.0" -> "yes"))
      .left
      .value shouldBe a[GraphError.ForeignExecution]
  }

  "A suspended snapshot" should "restore in another graph instance, through JSON, and resume there" in {
    val f        = Fixture(ask = Set("b", "c"))
    val first    = f.graph.run(Vector("a", "b", "c")).suspended
    val json     = upickle.default.write(f.graph.snapshot(first.execution).value)
    val snapshot = upickle.default.read[GraphSnapshot](json)
    snapshot.paused shouldBe true
    snapshot.parked.map(p => (p.interruptId, p.question.value, p.originTask, p.joinId)) shouldBe Vector(
      ("1.1", ujson.Str("b"), "1.1", Some("workers")),
      ("1.2", ujson.Str("c"), "1.2", Some("workers"))
    )

    val elsewhere = Fixture(ask = Set("b", "c"))
    val restored  = elsewhere.graph.restore(snapshot).value
    val partly =
      elsewhere.graph.runFrom(elsewhere.graph.resume(restored, elsewhere.answers("1.1" -> "yes")).value).suspended
    val resnap = elsewhere.graph.snapshot(partly.execution).value
    resnap.frontier shouldBe empty
    val again = Fixture(ask = Set("b", "c"))
    again.graph
      .runFrom(again.graph.resume(again.graph.restore(resnap).value, again.answers("1.2" -> "yes")).value)
      .completed
      ._2 shouldBe
      Vector("A,b!,c!")
  }

  it should "reject parked continuations the graph cannot resume" in {
    val f        = Fixture(ask = Set("b"))
    val snapshot = f.graph.snapshot(f.graph.run(Vector("a", "b")).suspended.execution).value
    val parked   = snapshot.parked.head
    def problems(changed: GraphSnapshot) = f.graph.restore(changed).left.value match {
      case GraphError.RestoreRejected(_, found) => found
      case other                                => fail(other.toString)
    }
    problems(snapshot.copy(parked = Vector(parked.copy(resumeNode = "worker")))) should
      contain("interrupt 1.1 resumes at 'worker', not a resume node")
    problems(snapshot.copy(parked = Vector(parked.copy(question = VersionedJson(1, ujson.Arr()))))).head should
      startWith("interrupt 1.1 question does not decode")
    problems(snapshot.copy(parked = Vector(parked.copy(originNode = "gone")))) shouldBe
      List("interrupt 1.1 continues unknown node 'gone'")
    problems(snapshot.copy(parked = Vector(parked.copy(fanOutTask = None)))) should
      contain("interrupt 1.1 has half a join slot")
    problems(snapshot.copy(parked = Vector(parked, parked))) shouldBe List("interrupt 1.1 is parked more than once")
    // without the parked continuation, the join waits for an arrival nothing will make
    problems(snapshot.copy(parked = Vector.empty)) shouldBe
      List("dynamic join 'workers' (fan-out 0.0) waits for 1.1, which are not pending")
    problems(snapshot.copy(dynamicJoins = Vector.empty)) should
      contain("interrupt 1.1 belongs to no open activation of dynamic join 'workers'")
  }

  it should "reject a pending continuation task with a broken origin" in {
    val f        = Fixture(ask = Set("b"))
    val first    = f.graph.run(Vector("a", "b")).suspended
    val resumed  = f.graph.resume(first.execution, f.answers("1.1" -> "yes")).value
    val snapshot = f.graph.snapshot(resumed).value
    val task     = snapshot.frontier.head
    task.originTask shouldBe Some("1.1")
    def problems(changed: GraphSnapshot.PendingTask) =
      f.graph.restore(snapshot.copy(frontier = Vector(changed))).left.value match {
        case GraphError.RestoreRejected(_, found) => found
        case other                                => fail(other.toString)
      }
    problems(task.copy(originNode = None)) should contain(s"pending task ${task.taskId} has half an origin")
    problems(task.copy(originNode = Some("gone"))) should contain(
      s"pending task ${task.taskId} continues unknown node 'gone'"
    )
    f.graph.runFrom(f.graph.restore(snapshot).value).completed._2 shouldBe Vector("A,b!")
  }

  extension [A](values: Vector[A])
    private def loneElementOr(otherwise: => Nothing): A = if values.size == 1 then values.head else otherwise
  extension (values: List[String])
    private def loneElementOr(otherwise: => Nothing): String = if values.size == 1 then values.head else otherwise
}
