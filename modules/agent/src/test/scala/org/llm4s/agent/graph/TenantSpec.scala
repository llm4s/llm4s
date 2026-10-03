package org.llm4s.agent.graph

import org.llm4s.agent.graph.GraphTestSupport.*
import org.llm4s.error.ValidationError
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicBoolean

class TenantSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private val thread = ThreadId("t")
  private val log    = StateKey.appending[String]("log")

  /** `work` fails once while `failing` is set; `ask` suspends on a question that `approve` answers. */
  final private class Fixture {
    val failing = new AtomicBoolean(false)

    val (graph, approve) = {
      val b       = GraphBuilder("tenants", "v1")
      val approve = b.declareResume[String, String]("approve")
      b.implement(approve.node, writes = Set(log))((resumed, _, _) =>
        continue(Command.empty.update(log, resumed.answer))
      )
      val work = b.node[String]("work", writes = Set(log)) { (input, _, _) =>
        if failing.compareAndSet(true, false) then NodeResult.Fail(ValidationError("work", "boom"))
        else if input == "ask" then NodeResult.Suspend(StateUpdate.empty, "ok?", approve)
        else continue(Command.empty.update(log, input))
      }
      (b.compile(work)(_.get(log)).value, approve)
    }
  }

  private def tenant(id: String): RunConfig = RunConfig().withTenantId(TenantId(id))

  private def snapshotOf(store: InMemoryCheckpointer) =
    (store.latest(thread).value, store.eventsAfter(thread, 0L, 1000).value)

  private def untouched[A](store: InMemoryCheckpointer)(call: => A): A =
    val before = snapshotOf(store)
    val result = call
    snapshotOf(store) shouldBe before
    result

  "A thread" should "refuse start by another tenant, and by none, leaving the thread unchanged" in {
    val f       = Fixture()
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    runtime.start(thread, f.graph, "x", tenant("a")).value.completed

    untouched(store)(runtime.start(thread, f.graph, "y", tenant("b"))).left.value shouldBe
      GraphError.TenantMismatch("t", Some("a"), Some("b"))
    untouched(store)(runtime.start(thread, f.graph, "y", RunConfig())).left.value shouldBe
      GraphError.TenantMismatch("t", Some("a"), None)
    GraphError.TenantMismatch("t", Some("a"), None).message should (include("'a'").and(include("<none>")))
  }

  it should "refuse a tenant when it has none" in {
    val f       = Fixture()
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    runtime.start(thread, f.graph, "x").value.completed

    untouched(store)(runtime.start(thread, f.graph, "y", tenant("a"))).left.value shouldBe
      GraphError.TenantMismatch("t", None, Some("a"))
  }

  it should "refuse recover by another tenant" in {
    val f       = Fixture()
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    f.failing.set(true)
    runtime.start(thread, f.graph, "x", tenant("a")).value.failed
    store.latest(thread).value.value.checkpoint.status shouldBe CheckpointStatus.Running

    untouched(store)(runtime.recover(f.graph, thread, tenant("b"))).left.value shouldBe
      GraphError.TenantMismatch("t", Some("a"), Some("b"))
    runtime.recover(f.graph, thread, tenant("a")).value.completed
  }

  it should "refuse resume by another tenant" in {
    val f       = Fixture()
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    val parked  = runtime.start(thread, f.graph, "ask", tenant("a")).value.suspended
    val answers = Map(parked.interrupts.head.id -> f.approve.answer("yes"))

    untouched(store)(runtime.resume(f.graph, thread, answers, tenant("b"))).left.value shouldBe
      GraphError.TenantMismatch("t", Some("a"), Some("b"))
    untouched(store)(runtime.resume(f.graph, thread, answers)).left.value shouldBe
      GraphError.TenantMismatch("t", Some("a"), None)
    runtime.resume(f.graph, thread, answers, tenant("a")).value.completed._2 shouldBe Vector("yes")
  }

  it should "continue for a matching tenant, recording it on every checkpoint" in {
    val f       = Fixture()
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    val parked  = runtime.start(thread, f.graph, "ask", tenant("a")).value.suspended
    store.latest(thread).value.value.checkpoint.tenantId shouldBe Some("a")
    runtime
      .resume(f.graph, thread, Map(parked.interrupts.head.id -> f.approve.answer("yes")), tenant("a"))
      .value
      .completed
    store.latest(thread).value.value.checkpoint.tenantId shouldBe Some("a")
    runtime.start(thread, f.graph, "again", tenant("a")).value.completed
    store.latest(thread).value.value.checkpoint.tenantId shouldBe Some("a")
    store.latest(thread).value.value.checkpoint.formatVersion shouldBe Checkpoint.CurrentFormat
  }

  it should "accept another principal, and record tenant and principal on run events" in {
    val f       = Fixture()
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    val first   = tenant("a").withPrincipal(Principal("alice"))
    val parked  = runtime.start(thread, f.graph, "ask", first).value.suspended
    runtime
      .resume(
        f.graph,
        thread,
        Map(parked.interrupts.head.id -> f.approve.answer("yes")),
        tenant("a").withPrincipal(Principal("bob"))
      )
      .value
      .completed

    val events = store.eventsAfter(thread, 0L, 1000).value.map(_.event)
    events should contain(RunEvent.RunStarted(Some("a"), Some("alice")))
    events.collect { case r: RunEvent.RunResumed => r }.loneElement shouldBe
      RunEvent.RunResumed(Vector("0.0"), Some("a"), Some("bob"))
  }

  it should "accept any tenant when it has no checkpoint" in {
    val f       = Fixture()
    val runtime = GraphRuntime(InMemoryCheckpointer())
    runtime.start(ThreadId("fresh"), f.graph, "x", tenant("zzz")).value.completed
  }

  "The event log" should "read events written before tenants existed" in {
    // written by the code before RunStarted carried identity: a bare string for the case object
    def record(event: String) =
      upickle.default.read[EventRecord](
        s"""{"threadId":"t","seq":1,"runId":"r","checkpointId":null,"taskId":null,"nodeId":null,""" +
          s""""timestamp":"2026-10-02T12:00:00Z","event":$event}"""
      )
    record("\"RunStarted\"").event shouldBe RunEvent.RunStarted(None, None)
    record("""{"$type":"RunRecovered","fromCheckpoint":"c1"}""").event shouldBe RunEvent.RunRecovered("c1", None, None)
    record("""{"$type":"RunResumed","answered":["i"]}""").event shouldBe RunEvent.RunResumed(Vector("i"), None, None)
    record("\"RunCompleted\"").event shouldBe RunEvent.RunCompleted
  }

  it should "round-trip events with identity" in {
    val events = Seq[RunEvent](
      RunEvent.RunStarted(Some("a"), Some("p")),
      RunEvent.RunRecovered("c", None, Some("p")),
      RunEvent.RunCompleted
    )
    events.foreach(e => upickle.default.read[RunEvent](upickle.default.write(e)) shouldBe e)
  }

  implicit private class LoneOps[A](xs: Seq[A]) {
    def loneElement: A = { xs should have size 1; xs.head }
  }
}
