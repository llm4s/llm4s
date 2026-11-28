package org.llm4s.agent.service

import org.llm4s.testutil.TestTimeLimit

import org.llm4s.agent.{ Agent, AgentId, CompletionFixture, SpecTools }
import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.tool.{ ToolContent, ToolOutcome }
import org.llm4s.error.{ CancelledError, ValidationError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.types.Result
import org.scalatest.{ BeforeAndAfterEach, EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{ ConcurrentLinkedQueue, CountDownLatch, Semaphore, TimeUnit }
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** [[AgentWorker]]'s bookkeeping (#3259): the run id it settles a dead letter as, and how soon it notices a lost lease. */
class AgentWorkerBookkeepingSpec
    extends AnyFlatSpec
    with TestTimeLimit
    with Matchers
    with EitherValues
    with OptionValues
    with BeforeAndAfterEach {

  private val agentId    = AgentId.of("assistant").value
  private val shortLease = RunQueueSettings(lease = LeasePolicy(ttl = 600.millis, renewEvery = 150.millis))
  private val fast       = WorkerSettings(pollInterval = 20.millis, sweepEvery = None, drainGrace = 2.seconds)
  private val started    = new ConcurrentLinkedQueue[AgentWorker]()

  override def afterEach(): Unit = {
    started.asScala.foreach(_.shutdown())
    started.clear()
  }

  final private class FnClient(answer: Int => Result[Completion]) extends LLMClient {
    private val count = new AtomicInteger(0)
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      answer(count.incrementAndGet())
    override def streamComplete(conversation: Conversation, options: CompletionOptions)(
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)
    override def contextWindow: Int     = 8192
    override def reserveCompletion: Int = 1024
  }

  /** A call that blocks until it is interrupted, releasing `entered` as it starts and `cut` once interrupted. */
  private def blocked(entered: Semaphore, cut: CountDownLatch): Result[Completion] = {
    entered.release()
    CancelledError.catchInterrupt(Thread.sleep(60_000)) match {
      case Left(e) =>
        cut.countDown()
        Thread.currentThread().interrupt()
        Left(CancelledError("model call", Some(e)))
      case Right(_) => Left(ValidationError("blocking", "was never interrupted"))
    }
  }

  private def thread(): ThreadId = ThreadId(s"book-${java.util.UUID.randomUUID().toString.take(8)}")

  private def workerOf(store: InMemoryCheckpointer, agent: Agent, queue: Option[RunQueue] = None): AgentWorker = {
    val worker =
      AgentWorker(queue.getOrElse(store.runQueue), store, Seq(QueuedAgent(agent)), fast, shortLease).value
    started.add(worker)
    worker
  }

  private def submit(store: InMemoryCheckpointer, on: ThreadId, maxAttempts: Int = 3): EntryId =
    store.runQueue
      .submit(
        Submission(
          on,
          agentId,
          QueuedInput.query("hello"),
          policy = BusyThreadPolicy.Enqueue,
          maxAttempts = maxAttempts
        )
      )
      .value
      .entryId

  "An exhausted entry's settlement" should "be committed as the entry's admitted run, not a random one (#3259)" in {
    val store   = InMemoryCheckpointer()
    val entered = new Semaphore(0)
    val cut     = new CountDownLatch(1)
    val calls   = new AtomicInteger(0)
    val slow = SpecTools.tool("slow") { (_, _) =>
      entered.release()
      CancelledError.catchInterrupt(Thread.sleep(60_000)): Unit
      ToolOutcome.success(ToolContent.Json(ujson.Str("late")))
    }
    val client = new FnClient(_ =>
      if (calls.incrementAndGet() == 1) Right(SpecTools.calling(SpecTools.call("c1", "slow")))
      else Right(CompletionFixture.simple("next"))
    )
    val agent =
      Agent
        .builder("assistant", client)
        .withRuntime(new GraphRuntime(store))
        .withToolSet(SpecTools.set(slow))
        .build()
        .value
    val on = thread()
    val id = submit(store, on, maxAttempts = 1)
    // a worker that died mid-run: its attempt admitted, its lease left to lapse
    val leased = store.runQueue.take(TakeRequest("dead-worker", Set(agentId), 300.millis)).value.value
    val run    = RunId.random()
    store.runQueue.recordAttempt(leased.lease, run).value shouldBe a[AttemptStart.Recorded]
    val running = agent.start(on, "hello", RunConfig().withRunId(run)).value
    entered.acquire()
    running.cancel()
    running.await().isLeft shouldBe true
    workerOf(store, agent).start()
    val entry = store.runQueue.awaitClosed(None, id, 10.seconds).value
    entry.outcome.value shouldBe EntryOutcome.Failed(FailureReason.AttemptsExhausted(1))
    val settled = store.latest(on).value.value.checkpoint
    settled.status shouldBe CheckpointStatus.Failed
    entry.runIds should contain(RunId(settled.runId))
    cut.getCount shouldBe 1
  }

  "A slot whose renewals hang" should "stop its run before its lease could lapse, not once a renewal returns (#3259)" in {
    val store   = InMemoryCheckpointer()
    val entered = new Semaphore(0)
    val cut     = new CountDownLatch(1)
    val agent =
      Agent
        .builder("assistant", new FnClient(_ => blocked(entered, cut)))
        .withRuntime(new GraphRuntime(store))
        .build()
        .value
    val hang = new CountDownLatch(1)
    val queue = new ForwardingRunQueue(store.runQueue) {
      // every renewal waits on a store that answers only once the test ends
      override def renew(l: Lease, ttl: FiniteDuration) = {
        hang.await(30, TimeUnit.SECONDS)
        super.renew(l, ttl)
      }
    }
    val on = thread()
    submit(store, on)
    workerOf(store, agent, Some(queue)).start()
    entered.tryAcquire(10, TimeUnit.SECONDS) shouldBe true
    val since = System.nanoTime()
    // the 600ms lease is given up within its ttl, though no renewal has returned
    cut.await(5, TimeUnit.SECONDS) shouldBe true
    (System.nanoTime() - since).nanos should be < 1.second
    hang.countDown()
  }
}
