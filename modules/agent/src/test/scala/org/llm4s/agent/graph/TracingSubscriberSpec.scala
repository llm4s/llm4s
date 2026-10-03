package org.llm4s.agent.graph

import org.llm4s.agent.graph.GraphTestSupport.*
import org.llm4s.error.UnknownError
import org.llm4s.llmconnect.model.{ Completion, TokenUsage }
import org.llm4s.trace.{ TraceEvent, Tracing }
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{ LinkedBlockingQueue, TimeUnit }
import scala.jdk.CollectionConverters.*

class TracingSubscriberSpec extends AnyFlatSpec with Matchers with EitherValues {

  final private class Recording(failing: Boolean) extends Tracing {
    val events = new LinkedBlockingQueue[TraceEvent]()
    def traceEvent(event: TraceEvent): Result[Unit] = {
      events.put(event)
      if failing then Left(UnknownError("tracing down", new RuntimeException("x"))) else Right(())
    }
    def traceToolCall(toolName: String, input: String, output: String): Result[Unit]       = Right(())
    def traceError(error: Throwable, context: String): Result[Unit]                        = Right(())
    def traceCompletion(completion: Completion, model: String): Result[Unit]               = Right(())
    def traceTokenUsage(usage: TokenUsage, model: String, operation: String): Result[Unit] = Right(())

    def take(count: Int): Vector[TraceEvent.CustomEvent] =
      Vector
        .fill(count) {
          Option(events.poll(5, TimeUnit.SECONDS)).getOrElse(fail("no trace event within 5s"))
        }
        .collect { case c: TraceEvent.CustomEvent => c }
  }

  private def graph: CompiledGraph[String, Unit] = {
    val b = GraphBuilder("two", "v1")
    val a = b.node[String]("a") { (_, _, context) =>
      context.emit("hello", 2, ujson.Obj("x" -> 1)); continue(Command.empty)
    }
    val c = b.node[Unit]("b")((_, _, _) => continue(Command.empty))
    b.edge(a, c)
    b.compile(a)(_ => Right(())).value
  }

  private val thread = ThreadId("t")

  "TracingSubscriber" should "name, order and stamp every event of a run" in {
    val runtime = GraphRuntime.inMemory()
    val tracing = new Recording(failing = false)
    val seen    = new LinkedBlockingQueue[EventRecord]()
    runtime.subscribe(thread) { case StreamEvent.Durable(r) => seen.put(r); case _ => () }.value
    TracingSubscriber.attach(runtime, thread, tracing).value
    runtime.start(thread, graph, "go").awaited.value.completed

    // two nodes: started, a done, custom, ... ends with checkpoint_committed + run_completed
    val traced = Iterator
      .continually(Option(tracing.events.poll(1, TimeUnit.SECONDS)))
      .takeWhile(_.isDefined)
      .flatten
      .collect { case c: TraceEvent.CustomEvent => c }
      .toVector
    val names = traced.map(_.name)
    names.head shouldBe "graph.run_started"
    names.last shouldBe "graph.run_completed"
    (names should contain).allOf("graph.task_completed", "graph.custom", "graph.checkpoint_committed")
    val seqs = traced.map(_.data("seq").num)
    seqs shouldBe seqs.sorted
    seqs.distinct.size shouldBe seqs.size
    val custom = traced.find(_.name == "graph.custom").get
    custom.data("name").str shouldBe "hello"
    custom.data("version").num shouldBe 2.0
    custom.data("payload")("x").num shouldBe 1.0
    custom.data("nodeId").str shouldBe "a"
    custom.data.obj.contains("$type") shouldBe false
    val recorded = Iterator.continually(Option(seen.poll(1, TimeUnit.SECONDS))).takeWhile(_.isDefined).flatten.toVector
    traced.map(_.timestamp) shouldBe recorded.map(_.timestamp)
  }

  it should "keep delivering when the tracing backend fails" in {
    val runtime = GraphRuntime.inMemory()
    val tracing = new Recording(failing = true)
    TracingSubscriber.attach(runtime, thread, tracing).value
    runtime.start(thread, graph, "go").awaited.value.completed
    val traced =
      Iterator.continually(Option(tracing.events.poll(1, TimeUnit.SECONDS))).takeWhile(_.isDefined).flatten.toVector
    traced.size should be > 4
    traced.collect { case c: TraceEvent.CustomEvent => c.name }.last shouldBe "graph.run_completed"
  }

  "snake" should "convert case names" in {
    TracingSubscriber.snake("RunStarted") shouldBe "run_started"
    TracingSubscriber.snake("Custom") shouldBe "custom"
  }

  "The graph runtime sources" should "not use synchronized, which pins a virtual thread's carrier" in {
    val rel = "src/main/scala/org/llm4s/agent/graph"
    val dir = Seq(new java.io.File(rel), new java.io.File("modules/agent/" + rel)).find(_.isDirectory).getOrElse(null)
    if dir == null then cancel("graph sources not found from " + new java.io.File(".").getAbsolutePath)
    val offenders = java.nio.file.Files
      .walk(dir.toPath)
      .iterator()
      .asScala
      .filter(_.toString.endsWith(".scala"))
      .filter(p => java.nio.file.Files.readString(p).contains("synchronized"))
      .toList
    offenders shouldBe empty
  }
}
