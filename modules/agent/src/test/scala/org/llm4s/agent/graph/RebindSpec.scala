package org.llm4s.agent.graph

import org.llm4s.agent.graph.GraphTestSupport.*
import org.llm4s.error.ValidationError
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger

/**
 * Dependencies are captured by node closures when a graph is built, and checkpoints are data, so a
 * run continued with a freshly built graph uses that graph's dependencies.
 */
class RebindSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val thread = ThreadId("t")
  private val out    = StateKey.appending[String]("out")

  /** `ask` suspends; `call` answers by calling `client` with the question. */
  private def build(client: String => String): CompiledGraph[String, String] = {
    val b    = GraphBuilder("rebind", "v1")
    val call = b.declareResume[String, String]("call")
    b.implement(call.node, writes = Set(out)) { (resumed, _, _) =>
      continue(Command.empty.update(out, client(resumed.question)))
    }
    val ask = b.node[String]("ask")((question, _, _) => NodeResult.Suspend(StateUpdate.empty, question, call))
    b.compile(ask)(_.get(out).map(_.mkString)).value
  }

  "A resumed run" should "use the dependencies of the graph it is resumed with" in {
    val runtime   = GraphRuntime.inMemory()
    val suspended = runtime.start(thread, build(_ => "A"), "question").awaited.value.suspended
    val answers   = suspended.interrupts.map(_.id -> ujson.Str("go")).toMap
    runtime
      .resume(thread, build(_ => "B"), answers)
      .awaited
      .value
      .completed
      ._2 shouldBe "B"
  }

  "A resolver keyed by thread" should "resolve the same resource after recover" in {
    val counters = Map(ThreadId("t") -> new AtomicInteger(), ThreadId("other") -> new AtomicInteger())
    val b        = GraphBuilder("resolver", "v1")
    val node = b.node[String]("n", writes = Set(out)) { (input, _, context) =>
      val attempts = counters(context.position.threadId).incrementAndGet()
      if attempts == 1 then NodeResult.Fail(ValidationError("n", "first attempt fails"))
      else continue(Command.empty.update(out, input))
    }
    val graph   = b.compile(node)(_.get(out).map(_.mkString)).value
    val runtime = GraphRuntime.inMemory()

    runtime.start(thread, graph, "x").awaited.value.failed
    runtime.recover(thread, graph).awaited.value.completed._2 shouldBe "x"
    counters(ThreadId("t")).get shouldBe 2
    counters(ThreadId("other")).get shouldBe 0
  }
}
