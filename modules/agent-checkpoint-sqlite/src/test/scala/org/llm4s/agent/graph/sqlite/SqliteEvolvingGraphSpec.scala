package org.llm4s.agent.graph.sqlite

import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.sqlite.EvolvingGraphFixture.*
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Clock

/**
 * Threads of `evolving-graph.db`, written by `main` before #1934 recorded a graph's shape in each snapshot, restore
 * into the graph once it gained a key (design §4.29): at rest, suspended and running. A change by more than added keys
 * is refused until the thread has run again under this build.
 */
class SqliteEvolvingGraphSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private def output(result: Result[RunResult[Vector[String]]]): Vector[String] =
    result.value match {
      case RunResult.Completed(_, out, _) => out
      case other                          => fail(s"run did not complete: $other")
    }

  "A thread written before graph shapes were recorded" should "carry no shape" in {
    val store = opened()
    Vector(AtRest, Waiting, Crashed).foreach(t => store.latest(t).value.value.checkpoint.snapshot.shape shouldBe None)
    store.latest(Crashed).value.value.checkpoint.status shouldBe CheckpointStatus.Running
    store.close()
  }

  it should "restore at rest into the graph that gained a key, which reads its initial value" in {
    val store   = opened()
    val runtime = GraphRuntime(store)
    output(runtime.start(AtRest, graph(tallied = true).graph, "again").flatMap(_.await())) shouldBe
      Vector("plan:hello", "work", "done", "plan:again", "work", "done")
    store.latest(AtRest).value.value.checkpoint.snapshot.state("tally") shouldBe VersionedJson(1, ujson.Num(1))
    store.close()
  }

  it should "resume a suspended thread, and recover a running one, in the graph that gained a key" in {
    val store   = opened()
    val runtime = GraphRuntime(store)
    val evolved = graph(tallied = true)
    output(
      runtime
        .resume(Waiting, evolved.graph, Map(InterruptId("0.0") -> evolved.approve.answer("yes")))
        .flatMap(_.await())
    ) shouldBe Vector("plan:ask-me", "approved:yes", "work", "done")
    output(runtime.recover(Crashed, evolved.graph).flatMap(_.await())) shouldBe Vector("plan:crash", "work", "done")
    store.latest(Crashed).value.value.checkpoint.snapshot.state("tally") shouldBe VersionedJson(1, ujson.Num(1))
    store.close()
  }

  it should "be refused when the graph gained a node, or lost a key it holds a value for, until it runs again" in {
    val store   = opened()
    val runtime = GraphRuntime(store)
    runtime.start(AtRest, graph(audit = true).graph, "again").left.value shouldBe a[GraphError.RestoreRejected]
    runtime.start(AtRest, graph(withoutNotes = true).graph, "again").left.value shouldBe a[GraphError.RestoreRejected]
    runtime.resume(Waiting, graph(audit = true).graph, Map(InterruptId("0.0") -> ujson.Str("yes"))).left.value shouldBe
      a[GraphError.RestoreRejected]

    // once run under this build, the thread records its shape, and a node nothing reaches is no change it can observe
    output(runtime.start(AtRest, graph().graph, "again").flatMap(_.await())).last shouldBe "done"
    store.close()
    val reopened = SqliteFiles.opened(store.path, Clock.systemUTC())
    reopened.latest(AtRest).value.value.checkpoint.snapshot.shape shouldBe Some(graph().graph.shape)
    output(GraphRuntime(reopened).start(AtRest, graph(audit = true).graph, "later").flatMap(_.await())).last shouldBe
      "done"
    reopened.close()
  }
}
