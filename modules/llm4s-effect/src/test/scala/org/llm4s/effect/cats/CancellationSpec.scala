package org.llm4s.effect.cats

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }

import cats.effect.IO
import cats.effect.kernel.Outcome
import cats.effect.unsafe.implicits.global
import org.llm4s.agent.AgentStatus
import org.llm4s.agent.testkit.Fixtures
import org.llm4s.error.CancelledError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{
  AssistantMessage,
  Completion,
  CompletionOptions,
  Conversation,
  StreamedChunk,
  ToolCall,
  ToolMessage
}
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolRegistry }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

/**
 * Core's contract is that cancellation is by interrupt: providers preserve the interrupt and return
 * `Left(CancelledError)`. The non-streaming bridges must therefore run the provider call on an
 * interruptible thread, otherwise cancelling the fiber (or `IO.timeout`) waits for the whole call.
 */
class CancellationSpec extends AnyFlatSpec with Matchers {
  import Fixtures.*

  final private class Parked {
    val started: CountDownLatch     = new CountDownLatch(1)
    val sawInterrupt: AtomicBoolean = new AtomicBoolean(false)
    val live: AtomicInteger         = new AtomicInteger(0)
    val client: Scripted = new Scripted(onComplete = () => {
      live.incrementAndGet()
      started.countDown()
      sawInterrupt.set(parkUntilInterrupted())
      live.decrementAndGet()
      Left(CancelledError("test"))
    })
  }

  "LLMClientIO.complete" should "interrupt the blocking provider call when the fiber is cancelled" in {
    val parked = new Parked
    val fiber  = LLMClientIO[IO](parked.client).complete(conversation).start.unsafeRunSync()
    parked.started.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
    fiber.cancel.timeout(PromptSeconds.seconds).attempt.unsafeRunSync().isRight shouldBe true
    withClue("provider thread was never interrupted: ")(parked.sawInterrupt.get() shouldBe true)
    fiber.join.unsafeRunSync() shouldBe a[Outcome.Canceled[?, ?, ?]]
  }

  it should "release the provider thread of every cancelled call" in {
    (0 until 5).foreach { _ =>
      val parked = new Parked
      val fiber  = LLMClientIO[IO](parked.client).complete(conversation).start.unsafeRunSync()
      parked.started.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
      fiber.cancel.timeout(PromptSeconds.seconds).attempt.unsafeRunSync()
      awaitCondition(parked.live.get() == 0) shouldBe true
      parked.sawInterrupt.get() shouldBe true
    }
  }

  "AgentIO.run" should "interrupt the provider call when the fiber is cancelled" in {
    val parked = new Parked
    val fiber  = AgentIO[IO](agentOf(parked.client)()).run("q").start.unsafeRunSync()
    parked.started.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
    fiber.cancel.timeout(PromptSeconds.seconds).attempt.unsafeRunSync().isRight shouldBe true
    withClue("provider thread was never interrupted: ")(parked.sawInterrupt.get() shouldBe true)
  }

  "AgentIO.continueConversation" should "interrupt the provider call when the fiber is cancelled" in {
    val parked = new Parked
    val calls  = new AtomicInteger(0)
    val client: LLMClient = new Scripted(onComplete =
      () =>
        if (calls.getAndIncrement() == 0) Right(completion)
        else parked.client.complete(conversation, CompletionOptions())
    )
    val agent = AgentIO[IO](agentOf(client)())
    val first = agent.run("q1").unsafeRunSync()
    first.status shouldBe AgentStatus.Completed("ok")
    val fiber = agent.continueConversation(first, "q2").start.unsafeRunSync()
    parked.started.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
    fiber.cancel.timeout(PromptSeconds.seconds).attempt.unsafeRunSync().isRight shouldBe true
    withClue("provider thread was never interrupted: ")(parked.sawInterrupt.get() shouldBe true)
  }

  "fiber cancellation" should "cancel the run: the tool observes the interrupt, and recover completes the thread" in {
    val toolStarted  = new CountDownLatch(1)
    val sawInterrupt = new AtomicBoolean(false)
    val block        = new AtomicBoolean(true)
    val blocking = ToolBuilder[Map[String, Any], String](
      "block",
      "blocks until interrupted",
      Schema.`object`[Map[String, Any]]("params")
    ).withHandler { _ =>
      if (block.get()) {
        toolStarted.countDown()
        CancelledError.catchInterrupt(new CountDownLatch(1).await()) match {
          case Left(e) =>
            sawInterrupt.set(CancelledError.fromThrowable(e, "tool").isDefined)
            Left("interrupted")
          case Right(_) => Right("unblocked")
        }
      } else Right("done")
    }.buildSafe() match {
      case Right(t) => t
      case Left(e)  => fail(s"could not build tool: $e")
    }
    val call = ToolCall(id = "c1", name = "block", arguments = ujson.Obj())
    // Calls the tool until a tool result is in the conversation, then answers: a recovered run
    // does not repeat the model calls it already finished.
    val scripted: LLMClient = new LLMClient {
      def complete(c: Conversation, o: CompletionOptions): Result[Completion] =
        if (c.messages.exists(_.isInstanceOf[ToolMessage]))
          Right(Completion("t", 0L, "finished", "m", AssistantMessage(Some("finished"))))
        else Right(Completion("t", 0L, "", "m", AssistantMessage("", Seq(call)), toolCalls = List(call)))
      def streamComplete(c: Conversation, o: CompletionOptions, onChunk: StreamedChunk => Unit): Result[Completion] =
        complete(c, o)
      def getContextWindow(): Int     = 4096
      def getReserveCompletion(): Int = 256
    }
    val (capture, thread) = threadCapture()
    val agent = AgentIO[IO](agentOf(scripted)(_.withTools(new ToolRegistry(Seq(blocking))).withMiddleware(capture)))
    val fiber = agent.run("go").start.unsafeRunSync()
    toolStarted.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
    fiber.cancel.timeout(5.seconds).attempt.unsafeRunSync().isRight shouldBe true
    withClue("tool thread was never interrupted: ")(sawInterrupt.get() shouldBe true)
    fiber.join.unsafeRunSync() shouldBe a[Outcome.Canceled[?, ?, ?]]

    block.set(false)
    agent.recover(thread.get().get).unsafeRunSync().answer shouldBe Some("finished")
  }
}
