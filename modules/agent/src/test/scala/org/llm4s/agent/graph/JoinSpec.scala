package org.llm4s.agent.graph

import org.llm4s.agent.graph.GraphTestSupport.*
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class JoinSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val log     = StateKey.appending[String]("log")
  private val results = StateKey.appending[String]("results")

  private def record(name: String): GraphNode[Unit] = (_, _, _) => continue(Command.empty.update(log, name))

  "A static join" should "wait for every source, however long each branch takes" in {
    val b     = GraphBuilder("static", "v1")
    val a2    = b.node[Unit]("a2", writes = Set(log))(record("a2"))
    val a1    = b.node[Unit]("a1", writes = Set(log))((_, _, _) => continue(Command.empty.update(log, "a1").goto(a2)))
    val bOnly = b.node[Unit]("b", writes = Set(log))(record("b"))
    val merge = b.node[Unit]("merge", writes = Set(log))(record("merge"))
    val start = b.node[Unit]("start")((_, _, _) => continue(Command.empty.goto(a1).goto(bOnly)))
    b.staticJoin("both", Set(a2, bOnly), merge)
    runInMemory(b.compile(start)(_.get(log)).value, ()).completed._2 shouldBe Vector("a1", "b", "a2", "merge")
  }

  it should "count each source once per activation, and re-arm after releasing" in {
    val b     = GraphBuilder("rearm", "v1")
    val round = StateKey.replace[Int]("round", 0)
    val left  = b.declare[Unit]("left")
    val right = b.node[Unit]("right", writes = Set(log))(record("right"))
    b.implement(left, writes = Set(log, round)) { (_, state, _) =>
      NodeResult.fromResult(state.get(round).map { n =>
        val c = Command.empty.update(log, s"left$n").update(round, n + 1)
        // `left` arrives twice before `right` is first scheduled
        if n == 0 then c.goto(left) else if n == 1 then c.goto(right) else c
      })
    }
    val merge = b.declare[Unit]("merge")
    b.implement(merge, writes = Set(log)) { (_, state, _) =>
      NodeResult.fromResult(state.get(round).map { n =>
        val c = Command.empty.update(log, "merge")
        if n < 4 then c.goto(left).goto(right) else c
      })
    }
    b.staticJoin("both", Set(left, right), merge)
    runInMemory(b.compile(left)(_.get(log)).value, ()).completed._2 shouldBe
      Vector("left0", "left1", "right", "merge", "left2", "right", "merge", "left3", "right", "merge")
  }

  it should "fail with UnsatisfiedJoin when the graph goes quiescent before every source arrives" in {
    val b     = GraphBuilder("unsatisfied", "v1")
    val ran   = b.node[Unit]("ran", writes = Set(log))(record("ran"))
    val never = b.node[Unit]("never", writes = Set(log))(record("never"))
    val merge = b.node[Unit]("merge")(record("merge"))
    val start = b.node[Unit]("start")((_, _, _) => continue(Command.empty.goto(ran)))
    b.staticJoin("both", Set(ran, never), merge)
    val (state, error) = runInMemory(b.compile(start)(_.get(log)).value, ()).failed
    error shouldBe GraphError.UnsatisfiedJoin(JoinId("both"), List("node 'never'"))
    state.get(log).value shouldBe Vector("ran")
  }

  "A dynamic join" should "release its target once every fan-out child has committed, in item order" in {
    def graph = {
      val b = GraphBuilder("fan-out", "v1")
      val worker = b.node[String]("worker", writes = Set(results))((s, _, _) =>
        continue(Command.empty.update(results, s.toUpperCase))
      )
      val summarize = b.node[Unit]("summarize", writes = Set(log)) { (_, state, _) =>
        NodeResult.fromResult(state.get(results).map(r => Command.empty.update(log, r.mkString(","))))
      }
      val join = b.dynamicJoin("workers", summarize)
      val plan =
        b.node[Unit]("plan")((_, _, _) => continue(Command.empty.fanOut(join, worker, Vector("a", "b", "c", "d"))))
      b.compile(plan)(_.get(log)).value
    }
    runInMemory(graph, ()).completed._2 shouldBe Vector("A,B,C,D")
    runInMemory(graph.withExecutor(reversed), ()).completed._2 shouldBe Vector("A,B,C,D")
    (1L to 20L).foreach(seed =>
      runInMemory(graph.withExecutor(concurrent(seed)), ()).completed._2 shouldBe Vector("A,B,C,D")
    )
  }

  it should "order children by emitting task, route, then item, and release each activation separately" in {
    val b = GraphBuilder("two-fan-outs", "v1")
    val worker =
      b.node[String]("worker", writes = Set(results))((s, _, _) => continue(Command.empty.update(results, s)))
    val summarize = b.node[Unit]("summarize", writes = Set(log)) { (_, state, _) =>
      NodeResult.fromResult(state.get(results).map(r => Command.empty.update(log, s"summary(${r.size})")))
    }
    val other = b.node[Unit]("other", writes = Set(log))(record("other"))
    val join  = b.dynamicJoin("workers", summarize)
    val first =
      b.node[Unit]("first")((_, _, _) => continue(Command.empty.fanOut(join, worker, Vector("1a", "1b")).goto(other)))
    val second =
      b.node[Unit]("second")((_, _, _) => continue(Command.empty.fanOut(join, worker, Vector("2a", "2b", "2c"))))
    val start = b.node[Unit]("start")((_, _, _) => continue(Command.empty.goto(first).goto(second)))
    val graph = b.compile(start)(state => state.get(results).flatMap(r => state.get(log).map(r -> _))).value

    val afterFanOut = graph.runSteps(2)
    afterFanOut.pendingTasks.map(_._2.value) shouldBe Vector("worker", "worker", "other", "worker", "worker", "worker")
    val afterChildren = graph.runSteps(3)
    afterChildren.pendingTasks.map(_._2.value) shouldBe Vector("summarize", "summarize")

    val (_, (ordered, summaries)) = runInMemory(graph.withExecutor(reversed), ()).completed
    ordered shouldBe Vector("1a", "1b", "2a", "2b", "2c")
    summaries shouldBe Vector("other", "summary(5)", "summary(5)")
  }

  it should "release immediately on an empty fan-out" in {
    val b = GraphBuilder("empty", "v1")
    val worker =
      b.node[String]("worker", writes = Set(results))((s, _, _) => continue(Command.empty.update(results, s)))
    val summarize = b.node[Unit]("summarize", writes = Set(log))(record("summarize"))
    val join      = b.dynamicJoin("workers", summarize)
    val plan      = b.node[Unit]("plan")((_, _, _) => continue(Command.empty.fanOut(join, worker, Vector.empty)))
    val graph     = b.compile(plan)(_.get(log)).value
    graph.runSteps(1).pendingTasks shouldBe Vector(TaskId("1.0") -> NodeId("summarize"))
    runInMemory(graph, ()).completed._2 shouldBe Vector("summarize")
  }

  it should "count a child's arrival when it completes, not when its own routes finish" in {
    val b = GraphBuilder("child-routes", "v1")
    val after =
      b.node[String]("after", writes = Set(log))((s, _, _) => continue(Command.empty.update(log, s"after-$s")))
    val worker =
      b.node[String]("worker", writes = Set(log))((s, _, _) => continue(Command.empty.update(log, s).send(after, s)))
    val summarize = b.node[Unit]("summarize", writes = Set(log))(record("summarize"))
    val join      = b.dynamicJoin("workers", summarize)
    val plan      = b.node[Unit]("plan")((_, _, _) => continue(Command.empty.fanOut(join, worker, Vector("x", "y"))))
    runInMemory(b.compile(plan)(_.get(log)).value, ()).completed._2 shouldBe
      Vector("x", "y", "after-x", "after-y", "summarize")
  }

  it should "reject a task that fans out to the same join twice" in {
    val b      = GraphBuilder("twice", "v1")
    val worker = b.node[String]("worker")((_, _, _) => continue(Command.empty))
    val sink   = b.node[Unit]("sink")((_, _, _) => continue(Command.empty))
    val join   = b.dynamicJoin("workers", sink)
    val plan = b.node[Unit]("plan") { (_, _, _) =>
      continue(Command.empty.fanOut(join, worker, Vector("a")).fanOut(join, worker, Vector("b")))
    }
    runInMemory(b.compile(plan)(_ => Right(())).value, ()).failed._2 shouldBe
      GraphError.InvalidRoute(NodeId("plan"), TaskId("0.0"), "it fans out to join 'workers' more than once")
  }

  extension [O](graph: CompiledGraph[Unit, O])
    /** The execution after `n` supersteps. */
    private def runSteps(n: Int): Execution =
      (1 to n).foldLeft(graph.start(())) { (execution, _) =>
        graph.step(ThreadId("t"), execution, RunConfig()) match {
          case Step.Next(next) => next
          case other           => fail(s"run ended early: $other")
        }
      }
}
