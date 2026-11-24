package org.llm4s.agent.service

import org.llm4s.agent.{ Agent, AgentId, CompletionFixture, SpecTools }
import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.tool.{ ToolContent, ToolOutcome }
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

/**
 * `Agent.abandon` on a thread whose open run belongs to a queue entry (#1750 follow-up, design 4.57): the thread's
 * owner may abandon it once no live claim holds it, and the entry then closes once, `Failed(Abandoned)`, with nothing
 * run again - whatever the thread did between the abandon and the worker that closes the entry. Over the in-memory
 * store, with real agents and workers.
 */
class AgentAbandonQueuedSpec
    extends AnyFlatSpec
    with Matchers
    with EitherValues
    with OptionValues
    with BeforeAndAfterEach {

  private val agentId = AgentId.of("assistant").value

  private val shortLease = RunQueueSettings(lease = LeasePolicy(ttl = 600.millis, renewEvery = 150.millis))

  private val fast = WorkerSettings(pollInterval = 20.millis, sweepEvery = None, drainGrace = 2.seconds)

  private val started = new ConcurrentLinkedQueue[AgentWorker]()

  override def afterEach(): Unit = {
    started.asScala.foreach(_.shutdown())
    started.clear()
  }

  /** A model answering each call with `answer(n)`, `n` from 1, keeping what it was sent. */
  final private class FnClient(answer: Int => Completion) extends LLMClient {
    private val count = new AtomicInteger(0)
    val sent          = new ConcurrentLinkedQueue[Vector[Message]]()
    def calls: Int    = count.get

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      sent.add(conversation.messages.toVector)
      Right(answer(count.incrementAndGet()))
    }

    override def streamComplete(conversation: Conversation, options: CompletionOptions)(
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 8192
    override def getReserveCompletion(): Int = 1024
  }

  /**
   * A store, a model whose first answer calls the `slow` tool and whose later ones are text, and an agent whose `slow`
   * tool blocks until it is interrupted or `release` is counted down, counting its runs in `ran`.
   */
  final private class Fixture {
    val store   = InMemoryCheckpointer()
    val entered = new Semaphore(0)
    val release = new CountDownLatch(1)
    val ran     = new AtomicInteger(0)
    val client = new FnClient(n =>
      if (n == 1) SpecTools.calling(SpecTools.call("c1", "slow")) else CompletionFixture.simple(s"answer $n")
    )
    private val slow = SpecTools.tool("slow") { (_, _) =>
      ran.incrementAndGet()
      entered.release()
      release.await(60, TimeUnit.SECONDS) // interrupted by a cancel: the call then has no result
      ToolOutcome.success(ToolContent.Json(ujson.Str("late")))
    }
    val agent: Agent =
      Agent
        .builder("assistant", client)
        .withRuntime(new GraphRuntime(store))
        .withTools(SpecTools.set(slow))
        .build()
        .value
    val on: ThreadId = ThreadId(s"abandon-q-${java.util.UUID.randomUUID().toString.take(8)}")

    def submit(input: QueuedInput = QueuedInput.query("hello"), maxAttempts: Int = 3): EntryId =
      store.runQueue
        .submit(Submission(on, agentId, input, policy = BusyThreadPolicy.Enqueue, maxAttempts = maxAttempts))
        .value
        .entryId

    /**
     * A worker that crashed mid tool batch: the entry leased for `ttl`, its attempt recorded and run until its tool
     * call blocks, then cut - the thread is left `Running` by that run, its claim given up, the lease left to expire.
     */
    def diedMidBatch(ttl: FiniteDuration = 300.millis): RunId = {
      val leased = store.runQueue.take(TakeRequest("dead-worker", Set(agentId), ttl)).value.value
      val run    = RunId.random()
      store.runQueue.recordAttempt(leased.lease, run).value shouldBe a[AttemptStart.Recorded]
      val running = agent.start(on, "hello", RunConfig().withRunId(run)).value
      entered.acquire()
      running.cancel()
      running.await().isLeft shouldBe true
      store.latest(on).value.value.checkpoint.status shouldBe CheckpointStatus.Running
      run
    }

    def worker(): AgentWorker = {
      val w = AgentWorker(store.runQueue, store, Seq(QueuedAgent(agent)), fast, shortLease).value
      started.add(w)
      w.start()
      w
    }

    def closed(id: EntryId): QueueEntry = store.runQueue.awaitClosed(None, id, 10.seconds).value

    def latest: Checkpoint = store.latest(on).value.value.checkpoint

    /** The tool results the model was last sent. */
    def lastResults: Vector[(String, String)] =
      client.sent.asScala.last.collect { case t: ToolMessage => t.toolCallId.asString -> t.content }
  }

  private val abandoned = EntryOutcome.Failed(FailureReason.Abandoned)

  "Agent.abandon on a queued run whose worker crashed mid tool batch" should
    "settle the thread, and the next worker closes the entry Failed(Abandoned), once, running nothing again" in {
      val f  = new Fixture
      val id = f.submit()
      f.diedMidBatch()

      f.agent.abandon(f.on) shouldBe Right(())
      val settled = f.latest
      settled.status shouldBe CheckpointStatus.Failed

      f.worker()
      val entry = f.closed(id)
      entry.outcome.value shouldBe abandoned
      // what the ticket's holder is told
      val submitter = new RunSubmitter(f.store.runQueue, f.store)
      submitter.await(None, id, 1.second).value.outcome.value shouldBe abandoned
      // nothing ran again: one tool run, one model call, and the thread is as the abandon left it
      f.ran.get shouldBe 1
      f.client.calls shouldBe 1
      f.latest.id shouldBe settled.id
      // and nothing is left to take, or to take again
      Thread.sleep(300)
      f.store.runQueue.openEntries(None, f.on).value shouldBe empty
      f.store.runQueue.entry(None, id).value.attempts.size shouldBe entry.attempts.size
      f.client.calls shouldBe 1
    }

  it should "close the entry Failed(Abandoned) even once a direct run has taken the thread on past the abandon" in {
    val f  = new Fixture
    val id = f.submit()
    f.diedMidBatch()
    f.agent.abandon(f.on) shouldBe Right(())
    // the owner abandons to move the thread on, so its next run usually lands before any worker re-leases the entry
    f.agent.run(f.on, "direct").value.answer shouldBe Some("answer 2")
    f.latest.status shouldBe CheckpointStatus.Completed

    f.worker()
    f.closed(id).outcome.value shouldBe abandoned
    f.ran.get shouldBe 1
    f.client.calls shouldBe 2
    f.lastResults shouldBe Vector("c1" -> Agent.AbandonedResult)
  }

  it should "close an entry out of attempts Failed(Abandoned) too, not AttemptsExhausted" in {
    val f  = new Fixture
    val id = f.submit(maxAttempts = 1)
    f.diedMidBatch()
    f.agent.abandon(f.on, "abandoned: by its owner") shouldBe Right(())
    f.worker()
    f.closed(id).outcome.value shouldBe abandoned
    f.ran.get shouldBe 1
  }

  it should "close the entry Failed(Abandoned) when a cancel had been asked of it and its owner abandoned first" in {
    val f  = new Fixture
    val id = f.submit()
    f.diedMidBatch()
    f.store.runQueue.cancel(None, CancelTarget.Entry(id), CancelReason.Requested(None)).value
    f.agent.abandon(f.on) shouldBe Right(())
    f.worker()
    f.closed(id).outcome.value shouldBe abandoned
    f.latest.outcome.value.asInstanceOf[RunOutcome.Failed].kind shouldBe "Abandoned"
  }

  it should "let the entry waiting behind it on the thread run next, the model reading the abandoned call's result" in {
    val f      = new Fixture
    val first  = f.submit()
    val second = f.submit(QueuedInput.query("again"))
    f.diedMidBatch()
    f.agent.abandon(f.on, "abandoned: slow never answered") shouldBe Right(())

    f.worker()
    f.closed(first).outcome.value shouldBe abandoned
    f.closed(second).outcome.value.asInstanceOf[EntryOutcome.Done].status shouldBe Some(CheckpointStatus.Completed)
    f.lastResults shouldBe Vector("c1" -> "abandoned: slow never answered")
    f.ran.get shouldBe 1
    f.client.calls shouldBe 2
  }

  "Agent.abandon while a worker's run holds the thread" should "be ThreadBusy, the run and its entry left alone" in {
    val f  = new Fixture
    val id = f.submit()
    f.worker()
    f.entered.acquire()

    f.agent.abandon(f.on).left.value shouldBe a[GraphError.ThreadBusy]
    f.latest.status shouldBe CheckpointStatus.Running
    f.store.runQueue.entry(None, id).value.isOpen shouldBe true

    f.release.countDown()
    f.closed(id).outcome.value.asInstanceOf[EntryOutcome.Done].status shouldBe Some(CheckpointStatus.Completed)
    f.ran.get shouldBe 1
    f.lastResults shouldBe Vector("c1" -> "late")
  }

  "Agent.abandon under a dead worker's unexpired lease" should
    "settle the thread at once, the entry closing Failed(Abandoned) when the lease expires" in {
      val f  = new Fixture
      val id = f.submit()
      // the claim is the thread's lock, the lease only the entry's: the dead worker's lease has seconds left
      f.diedMidBatch(ttl = 1500.millis)
      f.agent.abandon(f.on) shouldBe Right(())
      f.store.runQueue.entry(None, id).value.isOpen shouldBe true

      f.worker()
      f.closed(id).outcome.value shouldBe abandoned
      f.ran.get shouldBe 1
      f.client.calls shouldBe 1
    }

  "Agent.abandon on a thread a queued recover was still to continue" should
    "close that entry Failed, saying there was nothing to recover, with nothing run" in {
      val f = new Fixture
      // a direct run that crashed mid tool batch, its recovery handed to the queue, then abandoned by its owner
      val running = f.agent.start(f.on, "hello").value
      f.entered.acquire()
      running.cancel()
      running.await().isLeft shouldBe true
      val id = f.submit(QueuedInput.Recover)
      f.agent.abandon(f.on) shouldBe Right(())

      f.worker()
      val entry = f.closed(id)
      entry.outcome.value shouldBe EntryOutcome.Failed(
        FailureReason.RunFailed(GraphError.NothingToRecover(f.on.value).message)
      )
      entry.attempts should have size 1
      f.ran.get shouldBe 1
      f.client.calls shouldBe 1
    }
}
