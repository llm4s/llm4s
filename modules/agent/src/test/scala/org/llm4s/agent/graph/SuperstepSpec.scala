package org.llm4s.agent.graph

import org.llm4s.agent.graph.GraphTestSupport.*
import org.llm4s.error.ValidationError
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SuperstepSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val log = StateKey.appending[String]("log")

  "A graph" should "run a typed linear route and project its output" in {
    val b     = GraphBuilder("linear", "v1")
    val total = StateKey.replace[Int]("total", 0)
    val end   = b.node[Int]("end", writes = Set(total))((n, _, _) => continue(Command.empty.update(total, n * 2)))
    val start = b.node[String]("start")((s, _, _) => continue(Command.empty.send(end, s.length)))
    val graph = b.compile(start)(_.get(total)).value

    val result = runInMemory(graph, "four")
    result.completed._2 shouldBe 8
    result match {
      case RunResult.Completed(_, _, supersteps) => supersteps shouldBe 2
      case other                                 => fail(other.toString)
    }
  }

  it should "loop through a declared cycle until a node stops routing" in {
    val b     = GraphBuilder("counter", "v1")
    val count = StateKey.replace[Int]("count", 0)
    val tick  = b.declare[Unit]("tick")
    b.implement(tick, writes = Set(count)) { (_, state, _) =>
      NodeResult.fromResult(state.get(count).map { n =>
        val next = Command.empty.update(count, n + 1)
        if n + 1 < 5 then next.goto(tick) else next
      })
    }
    runInMemory(b.compile(tick)(_.get(count)).value, ()).completed._2 shouldBe 5
  }

  it should "fail a run that exceeds its superstep limit, keeping the last committed state" in {
    val b     = GraphBuilder("forever", "v1")
    val count = StateKey.replace[Int]("count", 0)
    val tick  = b.declare[Unit]("tick")
    b.implement(tick, writes = Set(count)) { (_, state, _) =>
      NodeResult.fromResult(state.get(count).map(n => Command.empty.update(count, n + 1).goto(tick)))
    }
    val (state, error) = runInMemory(
      b.compile(tick)(_.get(count)).value,
      (),
      RunConfig().withBudgets(RunBudgets(maxSupersteps = 3))
    ).failed
    error shouldBe GraphError.SuperstepLimitExceeded(3)
    state.get(count).value shouldBe 3
  }

  it should "give every task in a superstep the same committed snapshot" in {
    val b     = GraphBuilder("snapshot", "v1")
    val count = StateKey.replace[Int]("count", 10)
    val seen  = StateKey.appending[Int]("seen")
    val read: GraphNode[Unit] = (_, state, _) =>
      NodeResult.fromResult(state.get(count).map(n => Command.empty.update(seen, n).update(count, n + 1)))
    val left       = b.node[Unit]("left", writes = Set(count, seen))(read)
    val right      = b.node[Unit]("right", writes = Set(count, seen))(read)
    val fork       = b.node[Unit]("fork")((_, _, _) => continue(Command.empty.goto(left).goto(right)))
    val (state, _) = runInMemory(b.compile(fork)(_ => Right(())).value, ()).completed
    state.get(seen).value shouldBe Vector(10, 10)
    state.get(count).value shouldBe 11 // both replaced 10 with 11; the later task in order wins
  }

  it should "apply updates in frontier order then emission order, however tasks interleave" in {
    def graph = {
      val b = GraphBuilder("order", "v1")
      val worker = b.node[String]("worker", writes = Set(log)) { (name, _, _) =>
        continue(Command.empty.update(log, s"$name.1").update(log, s"$name.2"))
      }
      val fork = b.node[Unit]("fork") { (_, _, _) =>
        continue(Command.empty.send(worker, "a").send(worker, "b").send(worker, "c").send(worker, "d"))
      }
      b.compile(fork)(_.get(log)).value
    }
    val expected = Vector("a.1", "a.2", "b.1", "b.2", "c.1", "c.2", "d.1", "d.2")
    runInMemory(graph, ()).completed._2 shouldBe expected
    runInMemory(graph.withExecutor(reversed), ()).completed._2 shouldBe expected
    (1L to 20L).foreach(seed => runInMemory(graph.withExecutor(concurrent(seed)), ()).completed._2 shouldBe expected)
  }

  it should "schedule static edges before command routes, in declaration order" in {
    val b                                     = GraphBuilder("edges", "v1")
    def record(name: String): GraphNode[Unit] = (_, _, _) => continue(Command.empty.update(log, name))
    val e1                                    = b.node[Unit]("e1", writes = Set(log))(record("e1"))
    val e2                                    = b.node[Unit]("e2", writes = Set(log))(record("e2"))
    val r1                                    = b.node[Unit]("r1", writes = Set(log))(record("r1"))
    val start = b.node[Unit]("start")((_, _, _) => continue(Command.empty.goto(r1).goto(e1)))
    b.edge(start, e2)
    b.edge(start, e1)
    val graph = b.compile(start)(_.get(log)).value

    val first = graph.step(ThreadId("t"), graph.start(()), RunConfig()) match {
      case Step.Next(next) => next
      case other           => fail(other.toString)
    }
    first.pendingTasks.map(_._2.value) shouldBe Vector("e2", "e1", "r1", "e1")
    first.pendingTasks.map(_._1.value) shouldBe Vector("1.0", "1.1", "1.2", "1.3")
    drive(graph, first).completed._2 shouldBe Vector("e2", "e1", "r1", "e1")
  }

  it should "give each task its own identity" in {
    val b = GraphBuilder("ids", "v1")
    val where = b.node[Unit]("where", writes = Set(log)) { (_, _, context) =>
      continue(
        Command.empty.update(
          log,
          s"${context.position.nodeId.value}@${context.position.taskId.value}/${context.position.superstep}"
        )
      )
    }
    val start = b.node[Unit]("start")((_, _, _) => continue(Command.empty.goto(where).goto(where)))
    runInMemory(b.compile(start)(_.get(log)).value, ()).completed._2 shouldBe Vector("where@1.0/1", "where@1.1/1")
  }

  it should "reject an update to a key outside the node's write set and commit nothing" in {
    val b     = GraphBuilder("writes", "v1")
    val other = StateKey.replace[Int]("other", 0)
    val sneaky =
      b.node[Unit]("sneaky", writes = Set(log))((_, _, _) => continue(Command.empty.update(log, "x").update(other, 1)))
    val honest = b.node[Unit]("honest", writes = Set(other))((_, _, _) => continue(Command.empty.update(other, 2)))
    val start  = b.node[Unit]("start")((_, _, _) => continue(Command.empty.goto(honest).goto(sneaky)))
    val (state, error) = runInMemory(b.compile(start)(_.get(log)).value, ()).failed
    error shouldBe GraphError.UndeclaredWrite(NodeId("sneaky"), TaskId("1.1"), other.id)
    state.get(log).value shouldBe Vector.empty
    state.get(other).value shouldBe 0
  }

  it should "fail with the first failure in frontier order and commit none of the superstep" in {
    val b     = GraphBuilder("failure", "v1")
    val ok    = b.node[Unit]("ok", writes = Set(log))((_, _, _) => continue(Command.empty.update(log, "ok")))
    val bad   = b.node[Unit]("bad")((_, _, _) => NodeResult.Fail(ValidationError("input", "bad")))
    val worse = b.node[Unit]("worse")((_, _, _) => throw new IllegalStateException("boom"))
    val start = b.node[Unit]("start")((_, _, _) => continue(Command.empty.goto(ok).goto(worse).goto(bad)))
    val graph = b.compile(start)(_.get(log)).value

    Seq(graph, graph.withExecutor(reversed)).foreach { g =>
      val (state, error) = runInMemory(g, ()).failed
      error shouldBe a[GraphError.NodeFailed]
      val failed = error.asInstanceOf[GraphError.NodeFailed]
      failed.nodeId shouldBe NodeId("worse")
      failed.cause.message should include("boom")
      state.get(log).value shouldBe Vector.empty
    }
  }

  it should "fail when a key's update function rejects an update" in {
    val b = GraphBuilder("rejected", "v1")
    val strict =
      StateKey[Int, Int]("strict", 0)((_, n) => if n < 0 then Left(ValidationError("strict", "negative")) else Right(n))
    val start = b.node[Unit]("start", writes = Set(strict))((_, _, _) => continue(Command.empty.update(strict, -1)))
    val (_, error) = runInMemory(b.compile(start)(_.get(strict)).value, ()).failed
    error shouldBe a[GraphError.StateUpdateFailed]
  }

  it should "fail, not throw, when a key's update function throws" in {
    val b      = GraphBuilder("throwing-reducer", "v1")
    val parsed = StateKey[Int, String]("parsed", 0)((_, raw) => Right(raw.toInt))
    val start  = b.node[Unit]("start", writes = Set(parsed))((_, _, _) => continue(Command.empty.update(parsed, "x")))
    val (state, error) = runInMemory(b.compile(start)(_.get(parsed)).value, ()).failed
    error shouldBe a[GraphError.StateUpdateFailed]
    state.get(parsed).value shouldBe 0
  }

  it should "reject routes to another graph's nodes and joins" in {
    val other     = GraphBuilder("other", "v1")
    val alien     = other.node[Unit]("alien")((_, _, _) => continue(Command.empty))
    val alienJoin = other.dynamicJoin("alien-join", alien)
    val b         = GraphBuilder("routes", "v1")
    val sink      = b.node[Unit]("sink")((_, _, _) => continue(Command.empty))
    val toNode    = b.node[Unit]("to-node")((_, _, _) => continue(Command.empty.goto(alien)))
    val toJoin    = b.node[Unit]("to-join")((_, _, _) => continue(Command.empty.fanOut(alienJoin, sink, Vector(()))))
    val alienTgt  = b.dynamicJoin("mine", sink)
    val toAlienTgt =
      b.node[Unit]("to-alien-target")((_, _, _) => continue(Command.empty.fanOut(alienTgt, alien, Vector(()))))

    def errorOf(entry: NodeRef[Unit]) = runInMemory(b.compile(entry)(_ => Right(())).value, ()).failed._2
    errorOf(toNode) shouldBe GraphError.InvalidRoute(
      NodeId("to-node"),
      TaskId("0.0"),
      "node 'alien' is not part of this graph"
    )
    errorOf(toJoin) shouldBe GraphError.InvalidRoute(
      NodeId("to-join"),
      TaskId("0.0"),
      "dynamic join 'alien-join' is not part of this graph"
    )
    errorOf(toAlienTgt) shouldBe GraphError.InvalidRoute(
      NodeId("to-alien-target"),
      TaskId("0.0"),
      "node 'alien' is not part of this graph"
    )
  }

  it should "fail when the output projection fails" in {
    val b     = GraphBuilder("projection", "v1")
    val start = b.node[Unit]("start")((_, _, _) => continue(Command.empty))
    runInMemory(b.compile(start)(_ => Left(ValidationError("output", "missing"))).value, ()).failed._2 shouldBe
      ValidationError("output", "missing")
    runInMemory(
      b.compile(start)(_ => throw new IllegalStateException("projection threw")).value,
      ()
    ).failed._2.message should
      include("projection threw")
  }

  it should "refuse to step or snapshot another graph's execution" in {
    def graph(id: String) = {
      val b = GraphBuilder(id, "v1")
      b.compile(b.node[Unit]("start")((_, _, _) => continue(Command.empty)))(_ => Right(())).value
    }
    val mine   = graph("mine")
    val theirs = graph("theirs").start(())
    mine.step(ThreadId("t"), theirs, RunConfig()) match {
      case Step.Done(RunResult.Failed(_, error)) => error shouldBe GraphError.ForeignExecution("mine")
      case other                                 => fail(other.toString)
    }
    mine.snapshot(theirs).left.value shouldBe GraphError.ForeignExecution("mine")
  }

  "Command" should "accumulate updates and routes in order" in {
    val b       = GraphBuilder("command", "v1")
    val n       = b.node[Int]("n")((_, _, _) => continue(Command.empty))
    val u       = b.node[Unit]("u")((_, _, _) => continue(Command.empty))
    val join    = b.dynamicJoin("j", u)
    val count   = StateKey.replace[Int]("count", 0)
    val command = Command.empty.update(count, 1).remove(count).goto(u).send(n, 1).fanOut(join, n, Vector(2, 3))
    command.update.operations.size shouldBe 2
    command.routes shouldBe List(Route.Goto(u), Route.Send(n, 1), Route.FanOut(join, n, Vector(2, 3)))
    NodeResult.fromResult(Left(ValidationError("x", "y"))) shouldBe NodeResult.Fail(ValidationError("x", "y"))
  }
}
