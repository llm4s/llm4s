package org.llm4s.agent

import org.llm4s.agent.AgentFixture._
import org.llm4s.agent.graph.{
  Checkpointer,
  Commit,
  EventRecord,
  GraphError,
  GraphRuntime,
  InMemoryCheckpointer,
  RunEvent,
  StoredCheckpoint,
  ThreadId
}
import org.llm4s.error.{ CancelledError, ValidationError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.trace.{ TraceEvent, Tracing }
import org.llm4s.types.Result
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Seconds, Span }

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{
  ConcurrentLinkedQueue,
  CopyOnWriteArraySet,
  CountDownLatch,
  LinkedBlockingQueue,
  Semaphore,
  TimeUnit
}
import scala.jdk.CollectionConverters._
import scala.concurrent.duration._
class AgentRunCancellationSpec extends AnyFlatSpec with Matchers with Eventually {

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(5, Seconds))

  /**
   * A model whose calls numbered in `blockOn` (1-based) block until interrupted, and whose other
   * calls answer "done". `entered` is released by each blocked call that starts; `interrupted` by
   * each that saw its interrupt.
   */
  final private class BlockingClient(blockOn: Set[Int], unwind: Long = 0L) extends LLMClient {
    private val counter = new AtomicInteger(0)
    def calls: Int      = counter.get
    val entered         = new Semaphore(0)
    val interrupted     = new Semaphore(0)

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      if (blockOn.contains(counter.incrementAndGet())) {
        entered.release()
        CancelledError.catchInterrupt(Thread.sleep(60_000)) match {
          case Left(e) =>
            // a provider that takes a while to give up after its interrupt
            if (unwind > 0) CancelledError.catchInterrupt(Thread.sleep(unwind)): Unit
            interrupted.release()
            Thread.currentThread().interrupt()
            Left(CancelledError("model call", Some(e)))
          case Right(_) => Left(ValidationError("blocking", "was never interrupted"))
        }
      } else Right(CompletionFixture.simple("done"))

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 8192
    override def getReserveCompletion(): Int = 1024
  }

  /**
   * A model whose first call ignores interrupts until `release` is counted down, then answers "late";
   * `entered` is released when that call starts. Later calls answer "done".
   */
  final private class DeafClient extends LLMClient {
    private val counter = new AtomicInteger(0)
    val entered         = new Semaphore(0)
    val release         = new CountDownLatch(1)

    @scala.annotation.tailrec
    private def awaitRelease(): Unit =
      if !CancelledError.catchInterrupt(release.await()).isRight then awaitRelease()

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      if (counter.incrementAndGet() == 1) {
        entered.release()
        awaitRelease()
        Right(CompletionFixture.simple("late"))
      } else Right(CompletionFixture.simple("done"))

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 8192
    override def getReserveCompletion(): Int = 1024
  }

  /**
   * Runs `call` on a new thread, interrupts that thread once `entered` is released, and returns what
   * `call` returned and whether its thread was still interrupted when it returned.
   */
  private def interruptedCall(entered: Semaphore)(call: => Result[AgentResult]): (Result[AgentResult], Boolean) = {
    val outcome = new LinkedBlockingQueue[(Result[AgentResult], Boolean)]()
    val caller  = Thread.ofVirtual().start(() => outcome.offer(call -> Thread.currentThread().isInterrupted): Unit)
    entered.tryAcquire(10, TimeUnit.SECONDS) shouldBe true
    caller.interrupt()
    Option(outcome.poll(10, TimeUnit.SECONDS)).getOrElse(fail("the interrupted call did not return"))
  }

  "Agent.run" should "cancel its turn when the calling thread is interrupted, leaving the thread to recover" in {
    val client = new BlockingClient(blockOn = Set(1))
    val agent  = plain(client)
    val thread = ThreadId("interrupted-run")

    val (result, stillInterrupted) = interruptedCall(client.entered)(agent.run(thread, "q"))

    result.left.toOption.get shouldBe a[CancelledError]
    stillInterrupted shouldBe true
    // the turn itself was cancelled: its blocked model call saw the interrupt...
    client.interrupted.tryAcquire(10, TimeUnit.SECONDS) shouldBe true
    // ...and it ended: the thread is incomplete, not busy with a turn still running
    eventually(cause(agent.run(thread, "again").error) shouldBe a[GraphError.IncompleteRun])
    agent.recover(thread).value.answer shouldBe Some("done")
  }

  it should "return only once the cancelled turn has ended, so recover can follow at once" in {
    val client = new BlockingClient(blockOn = Set(1), unwind = 200L)
    val agent  = plain(client)
    val thread = ThreadId("interrupted-then-recovered")

    interruptedCall(client.entered)(agent.run(thread, "q"))._1.left.toOption.get shouldBe a[CancelledError]

    // no waiting: the turn ended before run returned
    agent.recover(thread).value.answer shouldBe Some("done")
  }

  it should "not start a turn when the calling thread is already interrupted" in {
    val client  = new BlockingClient(blockOn = Set.empty)
    val agent   = plain(client)
    val thread  = ThreadId("already-interrupted")
    val outcome = new LinkedBlockingQueue[(Result[AgentResult], Boolean)]()
    Thread
      .ofVirtual()
      .start { () =>
        Thread.currentThread().interrupt()
        outcome.offer(agent.run(thread, "q") -> Thread.currentThread().isInterrupted): Unit
      }
      .join()
    val (result, stillInterrupted) = outcome.poll(10, TimeUnit.SECONDS)

    result.left.toOption.get shouldBe a[CancelledError]
    stillInterrupted shouldBe true
    client.calls shouldBe 0
    // no thread was created: the same id starts a fresh turn
    agent.run(thread, "q").value.answer shouldBe Some("done")
  }

  "Agent.recover" should "cancel the recovered turn when the calling thread is interrupted" in {
    val client = new BlockingClient(blockOn = Set(1, 2))
    val agent  = plain(client)
    val thread = ThreadId("interrupted-recover")

    interruptedCall(client.entered)(agent.run(thread, "q"))._1.left.toOption.get shouldBe a[CancelledError]
    eventually(cause(agent.run(thread, "again").error) shouldBe a[GraphError.IncompleteRun])

    val (recovered, stillInterrupted) = interruptedCall(client.entered)(agent.recover(thread))

    recovered.left.toOption.get shouldBe a[CancelledError]
    stillInterrupted shouldBe true
    client.interrupted.tryAcquire(2, 10, TimeUnit.SECONDS) shouldBe true
    eventually(cause(agent.run(thread, "again").error) shouldBe a[GraphError.IncompleteRun])
    agent.recover(thread).value.answer shouldBe Some("done")
  }

  "AgentRun.cancelAndAwaitEnd" should "return within its bound when the provider ignores the interrupt, leaving the thread busy" in {
    val client = new DeafClient
    val agent  = plain(client)
    val thread = ThreadId("deaf-provider")
    val run    = agent.start(thread, "q").toOption.get
    client.entered.tryAcquire(10, TimeUnit.SECONDS) shouldBe true

    val began = System.nanoTime()
    run.cancelAndAwaitEnd(200.millis) shouldBe false
    (System.nanoTime() - began).nanos should be >= 200.millis
    // the turn is still running, so the thread is busy until it ends
    cause(agent.run(thread, "again").error) shouldBe a[GraphError.ThreadBusy]

    client.release.countDown()
    // once the provider returns, the cancelled turn ends and leaves the thread to recover
    eventually(cause(agent.run(thread, "again").error) shouldBe a[GraphError.IncompleteRun])
    // the late reply may be kept for recovery or asked again; either way the turn completes
    agent.recover(thread).value.answer should not be empty
  }

  it should "keep waiting through an interrupt of the waiting thread, and leave its flag set" in {
    val client  = new DeafClient
    val agent   = plain(client)
    val run     = agent.start(ThreadId("deaf-interrupted"), "q").toOption.get
    val outcome = new LinkedBlockingQueue[(Boolean, Long, Boolean)]()
    client.entered.tryAcquire(10, TimeUnit.SECONDS) shouldBe true

    val waiter = Thread.ofVirtual().start { () =>
      val began = System.nanoTime()
      val ended = run.cancelAndAwaitEnd(500.millis)
      outcome.offer((ended, System.nanoTime() - began, Thread.currentThread().isInterrupted)): Unit
    }
    Thread.sleep(100)
    waiter.interrupt()
    val (ended, waited, stillInterrupted) = outcome.poll(10, TimeUnit.SECONDS)

    ended shouldBe false
    waited.nanos should be >= 500.millis
    stillInterrupted shouldBe true
    client.release.countDown()
  }

  /**
   * An in-memory store that records the threads it stores and deletes; with `holdCompletion`, a commit
   * carrying `RunCompleted` opens `committing` and then waits, deaf to interrupts, until `release`. With
   * `throwOnDelete`, `deleteThread` records the thread and then throws a `LinkageError` - a store whose
   * driver class is missing - which the runtime does not turn into a `Left`.
   */
  final private class WatchedStore(holdCompletion: Boolean = false, throwOnDelete: Boolean = false)
      extends Checkpointer {
    private val underlying = InMemoryCheckpointer()
    val stored             = new CopyOnWriteArraySet[ThreadId]()
    val deleted            = new CopyOnWriteArraySet[ThreadId]()
    val committing         = new CountDownLatch(1)
    val release            = new CountDownLatch(1)

    @scala.annotation.tailrec
    private def awaitRelease(): Unit =
      if !CancelledError.catchInterrupt(release.await()).isRight then awaitRelease()

    def commit(threadId: ThreadId, commit: Commit): Result[Vector[EventRecord]] = {
      stored.add(threadId)
      if (holdCompletion && commit.events.exists(_.event == RunEvent.RunCompleted)) {
        committing.countDown()
        awaitRelease()
      }
      underlying.commit(threadId, commit)
    }
    def claim(threadId: ThreadId, request: org.llm4s.agent.graph.ClaimRequest) = underlying.claim(threadId, request)
    def renew(
      threadId: ThreadId,
      token: org.llm4s.agent.graph.FencingToken,
      ttl: scala.concurrent.duration.FiniteDuration
    ) =
      underlying.renew(threadId, token, ttl)
    def release(threadId: ThreadId, token: org.llm4s.agent.graph.FencingToken) = underlying.release(threadId, token)
    def latest(threadId: ThreadId): Result[Option[StoredCheckpoint]]           = underlying.latest(threadId)
    def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int): Result[Vector[EventRecord]] =
      underlying.eventsAfter(threadId, afterSeq, limit)
    def compactEvents(threadId: ThreadId, beforeSeq: Long): Result[Unit] = underlying.compactEvents(threadId, beforeSeq)
    def deleteThread(threadId: ThreadId): Result[Unit] = {
      deleted.add(threadId)
      if (throwOnDelete) throw new NoClassDefFoundError("store driver")
      underlying.deleteThread(threadId)
    }
  }

  /** Records every event; its `AgentRunEnded` is recorded only after `slowEnd`, as a slow tracing backend would. */
  final private class SlowTracing(slowEnd: FiniteDuration) extends Tracing {
    private val events = new ConcurrentLinkedQueue[TraceEvent]()
    def traceEvent(event: TraceEvent): Result[Unit] = {
      event match {
        case _: TraceEvent.AgentRunEnded => Thread.sleep(slowEnd.toMillis)
        case _                           => ()
      }
      events.add(event)
      Right(())
    }
    def traceToolCall(toolName: String, input: String, output: String): Result[Unit]       = Right(())
    def traceError(error: Throwable, context: String): Result[Unit]                        = Right(())
    def traceCompletion(completion: Completion, model: String): Result[Unit]               = Right(())
    def traceTokenUsage(usage: TokenUsage, model: String, operation: String): Result[Unit] = Right(())
    def ended: Vector[TraceEvent.AgentRunEnded] = events.asScala.toVector.collect { case e: TraceEvent.AgentRunEnded =>
      e
    }
  }

  "An interrupted traced Agent.run" should "return only once the cancelled turn's trace is complete, then trace a recovery once" in {
    val client  = new BlockingClient(blockOn = Set(1))
    val tracing = new SlowTracing(300.millis)
    val agent   = built(Agent.builder("assistant", client).withTracing(tracing))
    val thread  = ThreadId("traced-interrupted")

    interruptedCall(client.entered)(agent.run(thread, "q"))._1.left.toOption.get shouldBe a[CancelledError]

    // no waiting: the turn's tracing delivered its AgentRunEnded and detached before run returned
    tracing.ended.map(_.status) shouldBe Vector("cancelled")
    agent.recover(thread).value.answer shouldBe Some("done")
    // the recovery is traced once, by its own run's tracing: nothing of the cancelled turn's is still attached
    tracing.ended.map(_.status) shouldBe Vector("cancelled", "completed")
  }

  "Agent.run interrupted while its turn commits" should "return the turn's real outcome, with the interrupt flag still set" in {
    val store   = new WatchedStore(holdCompletion = true)
    val agent   = built(Agent.builder("assistant", new BlockingClient(Set.empty)).withRuntime(GraphRuntime(store)))
    val thread  = ThreadId("commit-beats-cancel")
    val outcome = new LinkedBlockingQueue[(Result[AgentResult], Boolean)]()
    val caller = Thread
      .ofVirtual()
      .start(() => outcome.offer(agent.run(thread, "q") -> Thread.currentThread().isInterrupted): Unit)

    // the turn is committing its completion, which no cancel interrupts
    store.committing.await(10, TimeUnit.SECONDS) shouldBe true
    caller.interrupt()
    // the caller's wait was interrupted and it now waits, bounded, for the turn's end
    eventually(caller.getState shouldBe Thread.State.TIMED_WAITING)
    store.release.countDown()

    val (result, stillInterrupted) =
      Option(outcome.poll(10, TimeUnit.SECONDS)).getOrElse(fail("the interrupted call did not return"))
    result.value.answer shouldBe Some("done")
    stillInterrupted shouldBe true
    // the turn is complete: nothing is left to recover
    cause(agent.recover(thread).error) shouldBe a[GraphError.NothingToRecover]
  }

  "A one-shot Agent.run" should "forget its random thread once a cancelled turn has ended" in {
    val store  = new WatchedStore()
    val client = new BlockingClient(blockOn = Set(1))
    val agent  = built(Agent.builder("assistant", client).withRuntime(GraphRuntime(store)))

    interruptedCall(client.entered)(agent.run("q"))._1.left.toOption.get shouldBe a[CancelledError]

    store.stored.asScala should have size 1
    store.deleted.asScala shouldBe store.stored.asScala
  }

  it should "forget its random thread when its turn fails, and keep it when the turn completes" in {
    val store = new WatchedStore()
    val down  = new AtomicInteger(0)
    val client = new LLMClient {
      override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
        if (down.getAndIncrement() == 0) Left(ValidationError("model", "down"))
        else Right(CompletionFixture.simple("ok"))
      override def streamComplete(
        conversation: Conversation,
        options: CompletionOptions,
        onChunk: StreamedChunk => Unit
      ): Result[Completion] = complete(conversation, options)
      override def getContextWindow(): Int     = 8192
      override def getReserveCompletion(): Int = 1024
    }
    val agent = built(Agent.builder("assistant", client).withRuntime(GraphRuntime(store)))

    agent.run("q").isLeft shouldBe true
    store.deleted.asScala shouldBe store.stored.asScala

    val kept = agent.run("q").value
    store.stored.asScala should contain(kept.threadId)
    store.deleted.asScala should not contain kept.threadId
  }

  it should "leave the caller's interrupt flag set when forgetting the cancelled turn's thread throws" in {
    val store   = new WatchedStore(throwOnDelete = true)
    val client  = new BlockingClient(blockOn = Set(1))
    val agent   = built(Agent.builder("assistant", client).withRuntime(GraphRuntime(store)))
    val outcome = new LinkedBlockingQueue[(Either[Throwable, Result[AgentResult]], Boolean)]()
    val caller = Thread.ofVirtual().start { () =>
      val result = scala.util.control.Exception.allCatch.either(agent.run("q"))
      outcome.offer(result -> Thread.currentThread().isInterrupted): Unit
    }
    client.entered.tryAcquire(10, TimeUnit.SECONDS) shouldBe true
    caller.interrupt()

    val (result, stillInterrupted) =
      Option(outcome.poll(10, TimeUnit.SECONDS)).getOrElse(fail("the interrupted call did not return"))
    // the store's failure escapes forget, and run with it...
    result.left.toOption.get shouldBe a[NoClassDefFoundError]
    store.deleted.asScala should have size 1
    // ...but the interrupt that cancelled the turn is not lost on the way
    stillInterrupted shouldBe true
  }

  "Agent.runMultiTurn" should "forget its random thread when a follow-up turn fails, and keep it when every turn completes" in {
    val store = new WatchedStore()
    val calls = new AtomicInteger(0)
    val client = new LLMClient {
      // the first conversation's follow-up (the second call) fails; every other call answers
      override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
        if (calls.incrementAndGet() == 2) Left(ValidationError("model", "down"))
        else Right(CompletionFixture.simple("ok"))
      override def streamComplete(
        conversation: Conversation,
        options: CompletionOptions,
        onChunk: StreamedChunk => Unit
      ): Result[Completion] = complete(conversation, options)
      override def getContextWindow(): Int     = 8192
      override def getReserveCompletion(): Int = 1024
    }
    val agent = built(Agent.builder("assistant", client).withRuntime(GraphRuntime(store)))

    agent.runMultiTurn("first", Seq("second", "third")).isLeft shouldBe true
    store.stored.asScala should have size 1
    store.deleted.asScala shouldBe store.stored.asScala

    val kept = agent.runMultiTurn("first", Seq("second")).value
    kept.answer shouldBe Some("ok")
    store.stored.asScala should contain(kept.threadId)
    store.deleted.asScala should not contain kept.threadId
  }
}
