package org.llm4s.agent

import org.llm4s.agent.AgentFixture._
import org.llm4s.agent.graph.{ GraphError, ThreadId }
import org.llm4s.error.{ CancelledError, ValidationError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Seconds, Span }

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ CountDownLatch, LinkedBlockingQueue, Semaphore, TimeUnit }
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
}
