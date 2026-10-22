package org.llm4s.agent.graph

import org.llm4s.agent.graph.GraphTestSupport.*
import org.llm4s.error.ValidationError
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{ CountDownLatch, TimeUnit }

/** The thread registry's types, and its metadata through a runtime (design 4.52); the stores' part is the contract's. */
class ThreadRegistrySpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private val log = StateKey.appending[String]("log")

  /** A graph that logs its input, waiting on `gate` first when the input is "wait". */
  final private class Fixture {
    val entered = new CountDownLatch(1)
    val gate    = new CountDownLatch(1)
    val graph = {
      val b = GraphBuilder("registered", "v1")
      val work = b.node[String]("work", writes = Set(log)) { (input, _, _) =>
        if input == "wait" then
          entered.countDown()
          gate.await(10, TimeUnit.SECONDS): Unit
        continue(Command.empty.update(log, input))
      }
      b.compile(work)(_.get(log)).value
    }
  }

  private val acme = Some(TenantId("acme"))

  private def config(metadata: Map[String, String] = Map.empty): RunConfig =
    RunConfig().withTenantId(acme).withMetadata(metadata)

  private def listed(store: Checkpointer, thread: ThreadId): Option[ThreadSummary] =
    store.threads(ThreadQuery(acme)).value.threads.find(_.threadId == thread)

  "ThreadMetadata" should "hold a small map of strings, refusing one past its limits with every problem named" in {
    ThreadMetadata("team" -> "red").entries shouldBe Map("team" -> "red")
    ThreadMetadata.of(Map("a.b:c/d-e_f" -> "x" * ThreadMetadata.MaxValueLength)).isRight shouldBe true
    val refused = ThreadMetadata
      .of(Map("" -> "x", "spaced key" -> "x", "k" * (ThreadMetadata.MaxKeyLength + 1) -> "x", "v" -> "a\nb"))
      .left
      .value
    refused shouldBe a[ValidationError]
    refused.message should (include("key ''").and(include("'spaced key'")).and(include("the value of 'v'")))
    ThreadMetadata.of((0 to ThreadMetadata.MaxEntries).map(i => s"k$i" -> "v").toMap).left.value.message should
      include(s"more than ${ThreadMetadata.MaxEntries}")
    an[IllegalArgumentException] should be thrownBy ThreadMetadata("bad key" -> "x")
  }

  it should "match a filter whose every entry it holds, and round-trip through JSON" in {
    val metadata = ThreadMetadata("team" -> "red", "tier" -> "1")
    metadata.matches(ThreadMetadata.empty) shouldBe true
    metadata.matches(ThreadMetadata("team" -> "red")) shouldBe true
    metadata.matches(ThreadMetadata("team" -> "red", "tier" -> "2")) shouldBe false
    ThreadMetadata.fromJson(metadata.toJson).value shouldBe metadata
    ThreadMetadata.fromJson(ujson.Arr(1)).left.value shouldBe a[ValidationError]
    ThreadMetadata.fromJson(ujson.Obj("bad key" -> "x")).left.value shouldBe a[ValidationError]
  }

  "ThreadQuery" should "bound its page, refusing a limit below 1 or above MaxLimit" in {
    ThreadQuery(acme).limit shouldBe ThreadQuery.DefaultLimit
    ThreadQuery(acme, limit = ThreadQuery.MaxLimit).limit shouldBe ThreadQuery.MaxLimit
    an[IllegalArgumentException] should be thrownBy ThreadQuery(acme, limit = 0)
    an[IllegalArgumentException] should be thrownBy ThreadQuery(acme).withLimit(ThreadQuery.MaxLimit + 1)
    ThreadQuery.checkLimit(0).left.value shouldBe a[ValidationError]
    ThreadQuery.checkLimit(1).value shouldBe 1
  }

  "ThreadStatus" should "put a delete under way first, then a live claim, then the latest checkpoint's status" in {
    ThreadStatus.of(CheckpointStatus.Completed, deleting = true, claimed = true) shouldBe ThreadStatus.Deleted
    ThreadStatus.of(CheckpointStatus.Completed, deleting = false, claimed = true) shouldBe ThreadStatus.Running
    ThreadStatus.of(CheckpointStatus.Running, deleting = false, claimed = false) shouldBe ThreadStatus.Idle
    ThreadStatus.of(CheckpointStatus.Suspended, deleting = false, claimed = false) shouldBe ThreadStatus.Suspended
    ThreadStatus.of(CheckpointStatus.Completed, deleting = false, claimed = false) shouldBe ThreadStatus.Completed
    ThreadStatus.of(CheckpointStatus.Failed, deleting = false, claimed = false) shouldBe ThreadStatus.Failed
  }

  "A runtime" should "set a thread's metadata at start, keep it through a start that brings none, and replace it with one that does" in {
    val f       = Fixture()
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    val thread  = ThreadId("started")
    runtime.start(thread, f.graph, "a", config(Map("team" -> "red"))).awaited.value.completed
    val first = listed(store, thread).value
    first.metadata shouldBe ThreadMetadata("team" -> "red")
    first.agent shouldBe "registered"
    first.status shouldBe ThreadStatus.Completed
    runtime.start(thread, f.graph, "b", config()).awaited.value.completed
    listed(store, thread).value.metadata shouldBe ThreadMetadata("team" -> "red")
    runtime.start(thread, f.graph, "c", config(Map("team" -> "blue"))).awaited.value.completed
    listed(store, thread).value.metadata shouldBe ThreadMetadata("team" -> "blue")
  }

  it should "refuse a start whose metadata breaks the limits before it claims anything" in {
    val f       = Fixture()
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    val thread  = ThreadId("refused")
    runtime.start(thread, f.graph, "a", config(Map("bad key" -> "x"))).awaited.left.value shouldBe a[ValidationError]
    store.latest(thread).value shouldBe None
    // nothing claimed it: a run starts at once
    runtime.start(thread, f.graph, "a", config()).awaited.value.completed
  }

  it should "replace a thread's metadata with updateMetadata, fenced, refusing another tenant, a new thread and a busy one" in {
    val f       = Fixture()
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    val thread  = ThreadId("updated")
    runtime.updateMetadata(thread, ThreadMetadata("team" -> "red"), config()).left.value shouldBe a[ValidationError]
    runtime.start(thread, f.graph, "a", config(Map("team" -> "red"))).awaited.value.completed
    val before = listed(store, thread).value

    runtime.updateMetadata(thread, ThreadMetadata("tier" -> "1"), config()).value
    val after = listed(store, thread).value
    after.metadata shouldBe ThreadMetadata("tier" -> "1")
    after.checkpointId shouldBe before.checkpointId
    runtime.updateMetadata(thread, ThreadMetadata("x" -> "y"), RunConfig().withTenantId(TenantId("other"))) shouldBe
      Left(GraphError.TenantMismatch(thread.value, Some("other")))
    runtime.updateMetadata(thread, ThreadMetadata.empty, config()).value
    listed(store, thread).value.metadata shouldBe ThreadMetadata.empty

    // a run holding the thread refuses it
    val running = runtime.start(thread, f.graph, "wait", config()).value
    f.entered.await(10, TimeUnit.SECONDS) shouldBe true
    runtime.updateMetadata(thread, ThreadMetadata("x" -> "y"), config()).left.value shouldBe a[GraphError.ThreadBusy]
    f.gate.countDown()
    await(running)
    listed(store, thread).value.metadata shouldBe ThreadMetadata.empty
  }
}
