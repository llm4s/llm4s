package org.llm4s.zio

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }

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
import zio.{ ZIO, durationInt }
import zio.test.*

/**
 * Core's contract is that cancellation is by interrupt: providers preserve the interrupt and return
 * `Left(CancelledError)`. The non-streaming bridges must therefore run the provider call on an
 * interruptible thread, otherwise interrupting the fiber (or `ZIO.timeout`) waits for the whole call.
 */
object CancellationSpec extends ZIOSpecDefault {
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

  private def awaitStarted(p: Parked): ZIO[Any, Nothing, Boolean] =
    ZIO.attemptBlocking(p.started.await(DeadlineSeconds, TimeUnit.SECONDS)).orDie

  // The interrupt must return promptly; a non-interruptible call would hold it for the park deadline.
  private def interruptPromptly[E, A](fiber: zio.Fiber[E, A]): ZIO[Any, Nothing, Boolean] =
    fiber.interrupt.timeout(zio.Duration.fromSeconds(PromptSeconds)).map(_.isDefined).withClock(zio.Clock.ClockLive)

  val spec =
    suite("cancellation by interrupt")(
      test("LLMClientZ.complete interrupts the blocking provider call when the fiber is interrupted") {
        val parked = new Parked
        for {
          fiber    <- LLMClientZ(parked.client).complete(conversation).fork
          started  <- awaitStarted(parked)
          promptly <- interruptPromptly(fiber)
        } yield assertTrue(started) && assertTrue(promptly) && assertTrue(parked.sawInterrupt.get())
      },
      test("LLMClientZ.complete releases the provider thread of every interrupted call") {
        ZIO
          .foreach(0 until 5) { _ =>
            val parked = new Parked
            for {
              fiber <- LLMClientZ(parked.client).complete(conversation).fork
              _     <- awaitStarted(parked)
              _     <- interruptPromptly(fiber)
              freed <- ZIO.attemptBlocking(awaitCondition(parked.live.get() == 0)).orDie
            } yield freed && parked.sawInterrupt.get()
          }
          .map(rs => assertTrue(rs.forall(identity)))
      },
      test("AgentZ.run interrupts the provider call when the fiber is interrupted") {
        val parked = new Parked
        for {
          fiber    <- AgentZ(agentOf(parked.client)()).run("q").fork
          _        <- awaitStarted(parked)
          promptly <- interruptPromptly(fiber)
        } yield assertTrue(promptly) && assertTrue(parked.sawInterrupt.get())
      },
      test("AgentZ.continueConversation interrupts the provider call when the fiber is interrupted") {
        val parked = new Parked
        val calls  = new AtomicInteger(0)
        val client: LLMClient = new Scripted(onComplete =
          () =>
            if (calls.getAndIncrement() == 0) Right(completion)
            else parked.client.complete(conversation, CompletionOptions())
        )
        val agent = AgentZ(agentOf(client)())
        for {
          first    <- agent.run("q1")
          fiber    <- agent.continueConversation(first, "q2").fork
          _        <- awaitStarted(parked)
          promptly <- interruptPromptly(fiber)
        } yield assertTrue(first.status == AgentStatus.Completed("ok")) && assertTrue(promptly) && assertTrue(
          parked.sawInterrupt.get()
        )
      },
      test("fiber interruption cancels the run: the tool observes the interrupt, and recover completes the thread") {
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
          case Left(e)  => throw new IllegalStateException(s"could not build tool: $e")
        }
        val call = ToolCall(id = "c1", name = "block", arguments = ujson.Obj())
        // Calls the tool until a tool result is in the conversation, then answers: a recovered run
        // does not repeat the model calls it already finished.
        val scripted: LLMClient = new LLMClient {
          def complete(c: Conversation, o: CompletionOptions): Result[Completion] =
            if (c.messages.exists(_.isInstanceOf[ToolMessage]))
              Right(Completion("t", 0L, "finished", "m", AssistantMessage(Some("finished"))))
            else Right(Completion("t", 0L, "", "m", AssistantMessage("", Seq(call)), toolCalls = List(call)))
          def streamComplete(
            c: Conversation,
            o: CompletionOptions,
            onChunk: StreamedChunk => Unit
          ): Result[Completion] =
            complete(c, o)
          def getContextWindow(): Int     = 4096
          def getReserveCompletion(): Int = 256
        }
        val (capture, thread) = threadCapture()
        val agent = AgentZ(agentOf(scripted)(_.withTools(new ToolRegistry(Seq(blocking))).withMiddleware(capture)))
        for {
          fiber     <- agent.run("go").fork
          started   <- ZIO.attemptBlocking(toolStarted.await(DeadlineSeconds, TimeUnit.SECONDS)).orDie
          promptly  <- interruptPromptly(fiber)
          _         <- ZIO.succeed(block.set(false))
          recovered <- agent.recover(thread.get().get)
        } yield assertTrue(started) && assertTrue(promptly) && assertTrue(sawInterrupt.get()) &&
          assertTrue(recovered.answer == Some("finished"))
      }
    ) @@ TestAspect.sequential @@ TestAspect.timeout(300.seconds)
}
