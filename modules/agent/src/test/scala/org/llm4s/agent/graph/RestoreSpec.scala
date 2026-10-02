package org.llm4s.agent.graph

import org.llm4s.agent.graph.GraphTestSupport.*
import org.scalatest.{ EitherValues, LoneElement }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RestoreSpec extends AnyFlatSpec with Matchers with EitherValues with LoneElement {

  private val log     = StateKey.appending[String]("log")
  private val results = StateKey.appending[String]("results")

  /**
   * plan fans out to three workers (dynamic join -> summarize) and starts a three-step chain;
   * summarize and the chain's end meet at a static join. Snapshots at superstep 1 have an open
   * dynamic activation; at superstep 3 a partially arrived static join.
   */
  private def graph(version: String = "v1", extraNode: Boolean = false) = {
    val b = GraphBuilder("research", version)
    val worker = b.node[String]("worker", writes = Set(results))((s, _, _) =>
      continue(Command.empty.update(results, s.toUpperCase))
    )
    val summarize = b.node[Unit]("summarize", writes = Set(log)) { (_, state, _) =>
      NodeResult.fromResult(state.get(results).map(r => Command.empty.update(log, s"summary:${r.mkString}")))
    }
    def step(name: String): GraphNode[Unit] = (_, _, _) => continue(Command.empty.update(log, name))
    val c3                                  = b.node[Unit]("c3", writes = Set(log))(step("c3"))
    val c2                                  = b.node[Unit]("c2", writes = Set(log))(step("c2"))
    val c1                                  = b.node[Unit]("c1", writes = Set(log))(step("c1"))
    val merge                               = b.node[Unit]("merge", writes = Set(log))(step("merge"))
    val join                                = b.dynamicJoin("workers", summarize)
    b.edge(c1, c2)
    b.edge(c2, c3)
    b.staticJoin("both", Set(summarize, c3), merge)
    val plan =
      b.node[Unit]("plan")((_, _, _) => continue(Command.empty.fanOut(join, worker, Vector("x", "y", "z")).goto(c1)))
    if extraNode then b.node[Unit]("extra")(step("extra"))
    b.compile(plan)(state => state.get(log).flatMap(l => state.get(results).map(l -> _))).value
  }

  private val expected = (Vector("c1", "c2", "summary:XYZ", "c3", "merge"), Vector("X", "Y", "Z"))

  private def executions(g: CompiledGraph[Unit, ?]): Vector[Execution] =
    Iterator
      .iterate(Option(g.start(()))) {
        case Some(e) =>
          g.step(e) match {
            case Step.Next(next) => Some(next)
            case Step.Done(_)    => None
          }
        case None => None
      }
      .takeWhile(_.isDefined)
      .flatten
      .toVector

  private def snapshotAt(superstep: Int): GraphSnapshot = {
    val g = graph()
    g.snapshot(executions(g)(superstep)).value
  }

  private def problems(result: org.llm4s.types.Result[Execution]): List[String] =
    result.left.value match {
      case GraphError.RestoreRejected(_, found) => found
      case other                                => fail(s"expected RestoreRejected, got $other")
    }

  "A snapshot" should "restore at every superstep boundary, through JSON, and finish as an uninterrupted run would" in {
    graph().run(()).completed._2 shouldBe expected
    val original = graph()
    val all      = executions(original)
    all.size shouldBe 6
    all.foreach { execution =>
      val json = upickle.default.write(original.snapshot(execution).value)
      // a separately built instance of the same definition, as in another process
      val elsewhere = graph()
      val restored  = elsewhere.restore(upickle.default.read[GraphSnapshot](json)).value
      restored.superstep shouldBe execution.superstep
      restored.pendingTasks shouldBe execution.pendingTasks
      elsewhere.runFrom(restored).completed._2 shouldBe expected
    }
  }

  it should "carry open join activations" in {
    val atFanOut = snapshotAt(1)
    atFanOut.dynamicJoins shouldBe Vector(
      GraphSnapshot.Activation("workers", "0.0", Vector("1.0", "1.1", "1.2"), Vector.empty)
    )
    atFanOut.frontier.map(t => (t.nodeId, t.input, t.joinId)) shouldBe Vector(
      ("worker", VersionedJson(1, ujson.Str("x")), Some("workers")),
      ("worker", VersionedJson(1, ujson.Str("y")), Some("workers")),
      ("worker", VersionedJson(1, ujson.Str("z")), Some("workers")),
      ("c1", VersionedJson(1, ujson.Null), None)
    )
    snapshotAt(3).staticJoins shouldBe Vector(GraphSnapshot.StaticArrivals("both", Vector("summarize")))
  }

  "Restore" should "reject a snapshot of another graph, version or structure" in {
    val snapshot = snapshotAt(1)
    (problems(graph(version = "v2").restore(snapshot)) should contain).allOf(
      "snapshot is of version 'v1', this graph is version 'v2'",
      "snapshot's graph structure differs from this graph's"
    )
    problems(graph(extraNode = true).restore(snapshot)) shouldBe List(
      "snapshot's graph structure differs from this graph's"
    )
    problems(graph().restore(snapshot.copy(graphId = "elsewhere", superstep = -1))) shouldBe List(
      "snapshot is of graph 'elsewhere'",
      "superstep -1 is negative"
    )
  }

  it should "reject state that is unregistered or does not decode" in {
    val snapshot = snapshotAt(3)
    problems(
      graph().restore(snapshot.copy(state = snapshot.state + ("ghost" -> VersionedJson(1, ujson.Num(1)))))
    ) shouldBe
      List("state key 'ghost' is not registered with this graph")
    problems(
      graph().restore(snapshot.copy(state = snapshot.state.updated("log", VersionedJson(1, ujson.Str("not-a-list")))))
    ).loneElement should startWith("state key 'log' does not decode")
  }

  it should "reject pending tasks for unknown nodes, mis-typed inputs and broken join slots" in {
    val snapshot               = snapshotAt(1)
    val Vector(w0, w1, w2, c1) = snapshot.frontier
    def withFrontier(tasks: GraphSnapshot.PendingTask*) =
      problems(graph().restore(snapshot.copy(frontier = tasks.toVector)))

    withFrontier(w0, w1, w2, c1.copy(nodeId = "gone")) shouldBe List("pending task 1.3 targets unknown node 'gone'")
    withFrontier(w0, w1, w2.copy(input = VersionedJson(1, ujson.Arr(3))), c1).loneElement should
      startWith("pending task 1.2 input does not decode for node 'worker'")
    withFrontier(w0, w1, w2, c1.copy(joinId = Some("workers"))) should contain(
      "pending task 1.3 has half a join slot"
    )
    withFrontier(w0, w1, w2, c1.copy(joinId = Some("nope"), fanOutTask = Some("0.0"))) shouldBe List(
      "pending task 1.3 belongs to unknown dynamic join 'nope'"
    )
    withFrontier(w0, w1, w2, w2, c1) shouldBe List("task 1.2 is pending more than once")
  }

  it should "reject a dynamic activation that waits for work nothing will do, or has already released" in {
    val snapshot = snapshotAt(1)
    val dropped  = snapshot.copy(frontier = snapshot.frontier.filterNot(_.taskId == "1.1"))
    problems(graph().restore(dropped)) shouldBe List(
      "dynamic join 'workers' (fan-out 0.0) waits for 1.1, which are not pending"
    )
    val activation = snapshot.dynamicJoins.head
    problems(
      graph().restore(snapshot.copy(dynamicJoins = Vector(activation.copy(arrived = Vector("9.9")))))
    ) shouldBe List(
      "dynamic join 'workers' records unexpected arrivals 9.9"
    )
    problems(graph().restore(snapshot.copy(dynamicJoins = Vector(activation.copy(joinId = "nope"))))) shouldBe List(
      "dynamic join 'nope' is not part of this graph",
      "pending task 1.0 belongs to no open activation of dynamic join 'workers'",
      "pending task 1.1 belongs to no open activation of dynamic join 'workers'",
      "pending task 1.2 belongs to no open activation of dynamic join 'workers'"
    )
    problems(
      graph().restore(snapshot.copy(dynamicJoins = Vector(activation.copy(arrived = activation.expected))))
    ) shouldBe
      List("dynamic join 'workers' (fan-out 0.0) has every arrival, so it has already released")
    problems(graph().restore(snapshot.copy(dynamicJoins = Vector(activation.copy(expected = Vector.empty))))) should
      contain("dynamic join 'workers' (fan-out 0.0) has every arrival, so it has already released")
    problems(graph().restore(snapshot.copy(dynamicJoins = Vector(activation, activation)))) shouldBe List(
      "dynamic join 'workers' has more than one activation for fan-out 0.0"
    )
  }

  it should "reject static arrivals for unknown joins, from non-sources, or that are not partial" in {
    val snapshot = snapshotAt(3)
    problems(
      graph().restore(snapshot.copy(staticJoins = Vector(GraphSnapshot.StaticArrivals("nope", Vector("c3")))))
    ) shouldBe
      List("static join 'nope' is not part of this graph")
    problems(
      graph().restore(
        snapshot.copy(staticJoins = Vector(GraphSnapshot.StaticArrivals("both", Vector("c1", "summarize"))))
      )
    ) shouldBe List("static join 'both' records arrivals from non-sources c1")
    def withArrivals(arrived: String*) =
      problems(
        graph().restore(snapshot.copy(staticJoins = Vector(GraphSnapshot.StaticArrivals("both", arrived.toVector))))
      )
    withArrivals() shouldBe List("static join 'both' records no arrivals")
    withArrivals("c3", "summarize") shouldBe List("static join 'both' records every source, so it has already released")
  }
}
