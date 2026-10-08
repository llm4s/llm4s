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
import java.util.concurrent.{ LinkedBlockingQueue, Semaphore, TimeUnit }
class AgentRunCancellationSpec extends AnyFlatSpec with Matchers with Eventually {

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(5, Seconds))

  /**
   * A model whose calls numbered in `blockOn` (1-based) block until interrupted, and whose other
   * calls answer "done". `entered` is released by each blocked call that starts; `interrupted` by
   * each that saw its interrupt.
   */
  final private class BlockingClient(blockOn: Set[Int]) extends LLMClient {
    private val calls = new AtomicInteger(0)
    val entered       = new Semaphore(0)
    val interrupted   = new Semaphore(0)

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      if (blockOn.contains(calls.incrementAndGet())) {
        entered.release()
        CancelledError.catchInterrupt(Thread.sleep(60_000)) match {
          case Left(e) =>
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
}
