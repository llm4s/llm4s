package org.llm4s.agent.graph

import org.llm4s.agent.graph.GraphTestSupport.*
import org.llm4s.error.ValidationError
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{ CountDownLatch, TimeUnit }

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
    runtime.start(thread, f.graph, "x", tenant("a")).awaited.value.completed

    untouched(store)(runtime.start(thread, f.graph, "y", tenant("b")).awaited).left.value shouldBe
      GraphError.TenantMismatch("t", Some("b"))
    untouched(store)(runtime.start(thread, f.graph, "y", RunConfig()).awaited).left.value shouldBe
      GraphError.TenantMismatch("t", None)
    // the owning tenant is neither carried nor printed
    GraphError.TenantMismatch("t", None).message shouldBe "Thread 't' does not belong to tenant <none>"
    GraphError.TenantMismatch("t", Some("b")).message shouldBe "Thread 't' does not belong to tenant 'b'"
  }

  it should "refuse a tenant when it has none" in {
    val f       = Fixture()
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    runtime.start(thread, f.graph, "x").awaited.value.completed

    untouched(store)(runtime.start(thread, f.graph, "y", tenant("a")).awaited).left.value shouldBe
      GraphError.TenantMismatch("t", Some("a"))
  }

  it should "refuse recover by another tenant" in {
    val f       = Fixture()
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    f.failing.set(true)
    runtime.start(thread, f.graph, "x", tenant("a")).awaited.value.failed
    store.latest(thread).value.value.checkpoint.status shouldBe CheckpointStatus.Running

    untouched(store)(runtime.recover(thread, f.graph, tenant("b")).awaited).left.value shouldBe
      GraphError.TenantMismatch("t", Some("b"))
    runtime.recover(thread, f.graph, tenant("a")).awaited.value.completed
  }

  it should "refuse resume by another tenant" in {
    val f       = Fixture()
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    val parked  = runtime.start(thread, f.graph, "ask", tenant("a")).awaited.value.suspended
    val answers = Map(parked.interrupts.head.id -> f.approve.answer("yes"))

    untouched(store)(runtime.resume(thread, f.graph, answers, tenant("b")).awaited).left.value shouldBe
      GraphError.TenantMismatch("t", Some("b"))
    untouched(store)(runtime.resume(thread, f.graph, answers).awaited).left.value shouldBe
      GraphError.TenantMismatch("t", None)
    runtime.resume(thread, f.graph, answers, tenant("a")).awaited.value.completed._2 shouldBe Vector("yes")
  }

  it should "continue for a matching tenant, recording it on every checkpoint" in {
    val f       = Fixture()
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    val parked  = runtime.start(thread, f.graph, "ask", tenant("a")).awaited.value.suspended
    store.latest(thread).value.value.checkpoint.tenantId shouldBe Some("a")
    runtime
      .resume(thread, f.graph, Map(parked.interrupts.head.id -> f.approve.answer("yes")), tenant("a"))
      .awaited
      .value
      .completed
    store.latest(thread).value.value.checkpoint.tenantId shouldBe Some("a")
    runtime.start(thread, f.graph, "again", tenant("a")).awaited.value.completed
    store.latest(thread).value.value.checkpoint.tenantId shouldBe Some("a")
    store.latest(thread).value.value.checkpoint.formatVersion shouldBe Checkpoint.CurrentFormat
  }

  it should "accept another principal, and record tenant and principal on run events" in {
    val f       = Fixture()
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    val first   = tenant("a").withPrincipal(Principal("alice"))
    val parked  = runtime.start(thread, f.graph, "ask", first).awaited.value.suspended
    runtime
      .resume(
        thread,
        f.graph,
        Map(parked.interrupts.head.id -> f.approve.answer("yes")),
        tenant("a").withPrincipal(Principal("bob"))
      )
      .awaited
      .value
      .completed

    val events = store.eventsAfter(thread, 0L, 1000).value.map(_.event)
    events should contain(RunEvent.RunStarted(Some("a"), Some("alice")))
    events.collect { case r: RunEvent.RunResumed => r }.loneElement shouldBe
      RunEvent.RunResumed(Vector("0.0"), Some("a"), Some("bob"))
  }

  it should "refuse another tenant before revealing the thread's state" in {
    val f        = Fixture()
    val store    = InMemoryCheckpointer()
    val runtime  = GraphRuntime(store)
    val mismatch = GraphError.TenantMismatch("t", Some("b"))

    // Completed: recover and resume would otherwise say NothingToRecover and NotSuspended
    runtime.start(thread, f.graph, "x", tenant("a")).awaited.value.completed
    untouched(store)(runtime.recover(thread, f.graph, tenant("b")).awaited).left.value shouldBe mismatch
    untouched(store)(runtime.resume(thread, f.graph, Map.empty, tenant("b")).awaited).left.value shouldBe mismatch

    // Suspended: start and recover would otherwise say PendingInterrupts
    val parked = runtime.start(thread, f.graph, "ask", tenant("a")).awaited.value.suspended
    untouched(store)(runtime.start(thread, f.graph, "y", tenant("b")).awaited).left.value shouldBe mismatch
    untouched(store)(runtime.recover(thread, f.graph, tenant("b")).awaited).left.value shouldBe mismatch
    runtime
      .resume(thread, f.graph, Map(parked.interrupts.head.id -> f.approve.answer("yes")), tenant("a"))
      .awaited
      .value
      .completed

    // Running, after a failed run: start and resume would otherwise say IncompleteRun and NotSuspended
    f.failing.set(true)
    runtime.start(thread, f.graph, "z", tenant("a")).awaited.value.failed
    untouched(store)(runtime.start(thread, f.graph, "y", tenant("b")).awaited).left.value shouldBe mismatch
    untouched(store)(runtime.resume(thread, f.graph, Map.empty, tenant("b")).awaited).left.value shouldBe mismatch
  }

  it should "refuse another tenant rather than report a live run as ThreadBusy" in {
    val started = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val b       = GraphBuilder("held", "v1")
    val held = b.node[String]("held", writes = Set(log)) { (input, _, _) =>
      started.countDown()
      release.await(10, TimeUnit.SECONDS): Unit
      continue(Command.empty.update(log, input))
    }
    val g        = b.compile(held)(_.get(log)).value
    val store    = InMemoryCheckpointer()
    val runtime  = GraphRuntime(store)
    val handle   = runtime.start(thread, g, "x", tenant("a")).value
    val mismatch = GraphError.TenantMismatch("t", Some("b"))
    started.await(10, TimeUnit.SECONDS) shouldBe true

    untouched(store)(runtime.start(thread, g, "y", tenant("b"))).left.value shouldBe mismatch
    untouched(store)(runtime.recover(thread, g, tenant("b"))).left.value shouldBe mismatch
    untouched(store)(runtime.resume(thread, g, Map.empty, tenant("b"))).left.value shouldBe mismatch
    untouched(store)(runtime.start(thread, g, "y", tenant("a"))).left.value shouldBe a[GraphError.ThreadBusy]

    release.countDown()
    await(handle).completed._2 shouldBe Vector("x")
  }

  it should "refuse another tenant whose claim lost a race, rather than name the winner's checkpoint" in {
    val f          = Fixture()
    val underlying = InMemoryCheckpointer()
    GraphRuntime(underlying).start(thread, f.graph, "x", tenant("a")).awaited.value.completed
    // a read that misses tenant a's run, as one racing its claim would: the claim then conflicts
    val stale = new AtomicBoolean(true)
    val racing = new Checkpointer {
      def commit(threadId: ThreadId, commit: Commit) = underlying.commit(threadId, commit)
      def latest(threadId: ThreadId) = if stale.getAndSet(false) then Right(None) else underlying.latest(threadId)
      def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int) =
        underlying.eventsAfter(threadId, afterSeq, limit)
      def compactEvents(threadId: ThreadId, beforeSeq: Long) = underlying.compactEvents(threadId, beforeSeq)
      def deleteThread(threadId: ThreadId)                   = underlying.deleteThread(threadId)
    }
    GraphRuntime(racing).start(thread, f.graph, "y", tenant("b")).awaited.left.value shouldBe
      GraphError.TenantMismatch("t", Some("b"))
    stale.set(true)
    GraphRuntime(racing).start(thread, f.graph, "y", tenant("a")).awaited.left.value shouldBe
      a[GraphError.ThreadBusy]
  }

  it should "report a lost claim as ThreadBusy when the thread cannot be re-read" in {
    val f          = Fixture()
    val underlying = InMemoryCheckpointer()
    GraphRuntime(underlying).start(thread, f.graph, "x", tenant("a")).awaited.value.completed
    val winner = underlying.latest(thread).value.map(_.checkpoint.id)
    val reads  = new java.util.concurrent.atomic.AtomicInteger()
    // the first read misses the winner, so the claim conflicts; the re-read then fails
    val failing = new Checkpointer {
      def commit(threadId: ThreadId, commit: Commit) = underlying.commit(threadId, commit)
      def latest(threadId: ThreadId) =
        if reads.incrementAndGet() == 1 then Right(None) else Left(ValidationError("store", "down"))
      def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int) =
        underlying.eventsAfter(threadId, afterSeq, limit)
      def compactEvents(threadId: ThreadId, beforeSeq: Long) = underlying.compactEvents(threadId, beforeSeq)
      def deleteThread(threadId: ThreadId)                   = underlying.deleteThread(threadId)
    }
    GraphRuntime(failing).start(thread, f.graph, "y", tenant("a")).awaited.left.value shouldBe
      GraphError.ThreadBusy("t", winner)
  }

  it should "refuse another tenant while a first admission on a new thread is still reading it" in {
    val f          = Fixture()
    val underlying = InMemoryCheckpointer()
    val reading    = new CountDownLatch(1)
    val release    = new CountDownLatch(1)
    val armed      = new AtomicBoolean(true)
    // the first read blocks, holding tenant a's admission inside its reservation
    val slow = new Checkpointer {
      def commit(threadId: ThreadId, commit: Commit) = underlying.commit(threadId, commit)
      def latest(threadId: ThreadId) = {
        if armed.compareAndSet(true, false) then {
          reading.countDown()
          release.await(10, TimeUnit.SECONDS): Unit
        }
        underlying.latest(threadId)
      }
      def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int) =
        underlying.eventsAfter(threadId, afterSeq, limit)
      def compactEvents(threadId: ThreadId, beforeSeq: Long) = underlying.compactEvents(threadId, beforeSeq)
      def deleteThread(threadId: ThreadId)                   = underlying.deleteThread(threadId)
    }
    val runtime = GraphRuntime(slow)
    val first   = new java.util.concurrent.LinkedBlockingQueue[org.llm4s.types.Result[RunResult[Vector[String]]]]()
    Thread.ofVirtual().start(() => first.put(runtime.start(thread, f.graph, "x", tenant("a")).awaited))
    reading.await(10, TimeUnit.SECONDS) shouldBe true

    runtime.start(thread, f.graph, "y", tenant("b")).left.value shouldBe GraphError.TenantMismatch("t", Some("b"))
    runtime.start(thread, f.graph, "y").left.value shouldBe GraphError.TenantMismatch("t", None)
    runtime.start(thread, f.graph, "y", tenant("a")).left.value shouldBe GraphError.ThreadBusy("t", None)

    release.countDown()
    Option(first.poll(10, TimeUnit.SECONDS)).value.value.completed._2 shouldBe Vector("x")
  }

  it should "accept any tenant when it has no checkpoint" in {
    val f       = Fixture()
    val runtime = GraphRuntime(InMemoryCheckpointer())
    runtime.start(ThreadId("fresh"), f.graph, "x", tenant("zzz")).awaited.value.completed
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
