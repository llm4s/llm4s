package org.llm4s.agent.service

import org.llm4s.agent.{ Agent, AgentId, CompletionFixture, NTurnFakeLLMClient }
import org.llm4s.agent.graph.*
import org.llm4s.agent.metrics.{ AgentMetrics, QueueMetrics, RecordingMetrics }
import org.scalatest.{ BeforeAndAfterEach, EitherValues, OptionValues }
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** An [[AgentWorker]] given a collector records the run queue's and its own metrics (design 4.59). */
class WorkerMetricsSpec
    extends AnyFlatSpec
    with Matchers
    with EitherValues
    with OptionValues
    with Eventually
    with BeforeAndAfterEach {
  import AgentMetrics.*

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(10, Seconds), interval = Span(20, Millis))

  private val agentId    = AgentId.of("assistant").value
  private val shortLease = RunQueueSettings(lease = LeasePolicy(ttl = 600.millis, renewEvery = 150.millis))
  private val fast =
    WorkerSettings(pollInterval = 20.millis, sweepEvery = None, drainGrace = 2.seconds, queueDepthEvery = 20.millis)

  private val started = new ConcurrentLinkedQueue[AgentWorker]()

  override def afterEach(): Unit = {
    started.asScala.foreach(_.shutdown())
    started.clear()
  }

  private def thread(): ThreadId = ThreadId(s"metrics-${java.util.UUID.randomUUID().toString.take(8)}")

  private def agentOver(store: Checkpointer): Agent =
    Agent
      .builder("assistant", new NTurnFakeLLMClient(CompletionFixture.simple("done")))
      .withRuntime(new GraphRuntime(store))
      .build()
      .value

  private def workerOf(store: InMemoryCheckpointer, metrics: RecordingMetrics, settings: WorkerSettings = fast) = {
    val worker =
      AgentWorker(store.runQueue, store, Seq(QueuedAgent(agentOver(store))), settings, shortLease, metrics).value
    started.add(worker)
    worker
  }

  private def submit(store: InMemoryCheckpointer, on: ThreadId): EntryId =
    store.runQueue
      .submit(Submission(on, agentId, QueuedInput.query("hello"), policy = BusyThreadPolicy.Enqueue))
      .value
      .entryId

  "An AgentWorker with a collector" should "count an entry's attempt, plan, wait and close" in {
    val store   = InMemoryCheckpointer()
    val metrics = new RecordingMetrics
    val id      = submit(store, thread())
    workerOf(store, metrics).start()
    store.runQueue.awaitClosed(None, id, 10.seconds).value.isOpen shouldBe false
    eventually(metrics.count(QueueClosed, "assistant", "done", "none") shouldBe 1.0)
    metrics.series(QueueAttempts) shouldBe Map(Vector("assistant", "recorded") -> 1.0)
    metrics.series(QueuePlans) shouldBe Map(Vector("assistant", "start") -> 1.0)
    metrics.durations(QueueWait, "assistant") should have size 1
    metrics.series(QueueLeaseExpiries) shouldBe empty
  }

  it should "count a dead worker's expired lease when it takes the entry over, and its recovery" in {
    val store   = InMemoryCheckpointer()
    val metrics = new RecordingMetrics
    val on      = thread()
    val id      = submit(store, on)
    // a worker that died holding the lease, before it recorded an attempt
    store.runQueue.take(TakeRequest("dead-worker", Set(agentId), 200.millis)).value.value.expiredLease shouldBe false
    workerOf(store, metrics).start()
    store.runQueue.awaitClosed(None, id, 10.seconds).value.isOpen shouldBe false
    eventually(metrics.count(QueueLeaseExpiries, "assistant") shouldBe 1.0)
  }

  it should "count a deferral to a live run on the thread, recording no attempt" in {
    val store   = InMemoryCheckpointer()
    val metrics = new RecordingMetrics
    val on      = thread()
    val held    = store.claim(on, ClaimRequest(RunId("direct"), None, 30.seconds)).value
    val id      = submit(store, on)
    workerOf(store, metrics).start()
    eventually(metrics.count(QueueDeferrals, "assistant", "busy") should be >= 1.0)
    metrics.series(QueueAttempts) shouldBe empty
    store.release(on, held.token).value
    store.runQueue.awaitClosed(None, id, 10.seconds).value.isOpen shouldBe false
  }

  it should "sample its agents' queue depth, waiting and leased, into the gauges" in {
    val store   = InMemoryCheckpointer()
    val metrics = new RecordingMetrics
    val on      = thread()
    store.claim(on, ClaimRequest(RunId("direct"), None, 30.seconds)).value
    submit(store, on)
    submit(store, on)
    workerOf(store, metrics).start()
    eventually {
      metrics.gauge(QueueOpenEntries, "assistant", "waiting") shouldBe Some(2.0)
      metrics.gauge(QueueOpenEntries, "assistant", "leased") shouldBe Some(0.0)
      metrics.gauge(QueueOldest, "assistant").value should be >= 0.0
    }
  }

  it should "sample nothing with no collector" in {
    val store  = InMemoryCheckpointer()
    val worker = AgentWorker(store.runQueue, store, Seq(QueuedAgent(agentOver(store))), fast, shortLease).value
    started.add(worker)
    worker.start()
    worker.health().ready shouldBe true
  }

  it should "count a sweep pass and what it reclaimed, and a skipped one" in {
    val store   = InMemoryCheckpointer()
    val metrics = new RecordingMetrics
    val worker  = workerOf(store, metrics)
    worker.sweepOnce().value.skipped shouldBe false
    val lock = store.claim(AgentWorker.SweepLock, ClaimRequest(RunId("other"), None, 30.seconds)).value
    worker.sweepOnce().value.skipped shouldBe true
    store.release(AgentWorker.SweepLock, lock.token).value
    metrics.series(SweepPasses) shouldBe Map(Vector("done") -> 1.0, Vector("skipped") -> 1.0)
    metrics.count(SweepExamined) should be >= 0.0
  }

  it should "count a drain's runs by how they ended" in {
    val store   = InMemoryCheckpointer()
    val metrics = new RecordingMetrics
    val worker  = workerOf(store, metrics)
    worker.start()
    worker.shutdown()
    metrics.series(DrainedRuns) shouldBe
      Map(Vector("finished") -> 0.0, Vector("cancelled") -> 0.0, Vector("abandoned") -> 0.0)
  }

  "QueueMetrics.closed" should "label each outcome and reason, for W3's cancels and interrupts too" in {
    val metrics = new RecordingMetrics
    val meter   = new QueueMetrics(metrics)
    meter.closed(agentId, EntryOutcome.Failed(FailureReason.AttemptsExhausted(3)))
    meter.closed(agentId, EntryOutcome.Cancelled(CancelReason.Requested(Some("user"))))
    meter.closed(agentId, EntryOutcome.Cancelled(CancelReason.ThreadDeleted))
    meter.closed(agentId, EntryOutcome.Interrupted(None))
    metrics.series(QueueClosed) shouldBe Map(
      Vector("assistant", "failed", "attempts_exhausted") -> 1.0,
      Vector("assistant", "cancelled", "requested")       -> 1.0,
      Vector("assistant", "cancelled", "thread_deleted")  -> 1.0,
      Vector("assistant", "interrupted", "none")          -> 1.0
    )
  }
}
