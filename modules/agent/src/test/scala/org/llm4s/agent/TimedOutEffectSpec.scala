package org.llm4s.agent

import org.llm4s.agent.AgentFixture._
import org.llm4s.agent.SpecTools.{ call, calling }
import org.llm4s.agent.graph.{ GraphError, ReplayGuard, StateCheck }
import org.llm4s.agent.graph.middleware.{ AgentMiddleware, MiddlewareId, ToolCallRequest }
import org.llm4s.agent.graph.tool.{ AgentTool, ToolContext, ToolOutcome }
import org.llm4s.llmconnect.model._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.{ AtomicInteger, AtomicReference }
import scala.concurrent.duration._

/**
 * A guarded effect whose call timed out (#1903 with design §4.24): the timeout interrupts the tool's fork after
 * the guard recorded its intent, so the record stays begun, and a run of the same call again - a retrying
 * middleware, or `recover` - is decided by the target's support class. The record goes with the task's commit.
 */
class TimedOutEffectSpec extends AnyFlatSpec with Matchers {

  private def results(result: AgentResult): Vector[String] = result.messages.collect { case t: ToolMessage =>
    t.content
  }.toVector

  /** Runs `next` again when it timed out, as a retrying middleware does: the same call, the same key. */
  private val retry = new AgentMiddleware {
    val id: MiddlewareId = MiddlewareId("retry")
    override def wrapToolCall(request: ToolCallRequest, context: ToolContext)(next: () => ToolOutcome): ToolOutcome =
      next() match {
        case ToolOutcome.Error(message) if message.contains("timed out") => next()
        case other                                                       => other
      }
  }

  private def outcome[A](result: Either[org.llm4s.error.LLMError, A]): ToolOutcome = result match {
    case Right(value)                            => ToolOutcome.Success(ujson.Str(value.toString))
    case Left(r: GraphError.EffectReplayRefused) => ToolOutcome.Error(r.message)
    case Left(other)                             => ToolOutcome.Fatal(other)
  }

  private def agentWith(tool: AgentTool[?]): Agent =
    built(
      Agent
        .builder("assistant", ScriptedLLMClient.of(calling(call("c1", "effect")), CompletionFixture.simple("done")))
        .withTools(SpecTools.set(tool))
        .withMiddleware(retry)
    )

  "An unverifiable effect that timed out after it ran" should "be refused, not run again, when the call is retried" in {
    val applied = new AtomicInteger()
    val tool = AgentTool(SpecTools.spec("effect").withTimeout(200.millis)) { (_, context) =>
      outcome(ReplayGuard.unverifiable[Int](context.run, context.effectKey("log.append", "log"), idempotent = false) {
        val n = applied.incrementAndGet()
        Thread.sleep(60_000) // the effect landed, then the call hangs past its timeout
        Right(n)
      })
    }
    val result = agentWith(tool).run("go").value
    result.answer shouldBe Some("done")
    applied.get shouldBe 1
    results(result).head should include("may already have run")
  }

  "An unverifiable idempotent effect that timed out" should "run again when the call is retried" in {
    val applied = new AtomicInteger()
    val tool = AgentTool(SpecTools.spec("effect").withTimeout(200.millis)) { (_, context) =>
      outcome(ReplayGuard.unverifiable[Int](context.run, context.effectKey("flag.set", "flag"), idempotent = true) {
        val n = applied.incrementAndGet()
        if n == 1 then Thread.sleep(60_000)
        Right(n)
      })
    }
    results(agentWith(tool).run("go").value) shouldBe Vector("2")
  }

  "A verifiable effect that timed out after it landed" should "replay its presumed outcome when the call is retried" in {
    val cell    = new AtomicReference("text")
    val applied = new AtomicInteger()
    val tool = AgentTool(SpecTools.spec("effect").withTimeout(200.millis)) { (_, context) =>
      outcome(
        ReplayGuard.verifiable[String](context.run, context.effectKey("text.append", "cell"))(
          Right(StateCheck.of(cell.get))
        ) {
          val before = cell.get
          val after  = before + "!"
          Right(ReplayGuard.Plan(StateCheck.of(before), StateCheck.of(after), after) {
            if cell.compareAndSet(before, after) then
              applied.incrementAndGet()
              Thread.sleep(60_000) // landed, then hangs
              Right(after)
            else Left(GraphError.EffectReplayRefused("text.append", "changed"))
          })
        }
      )
    }
    results(agentWith(tool).run("go").value) shouldBe Vector("text!")
    applied.get shouldBe 1
    cell.get shouldBe "text!"
  }

  "A verifiable effect that timed out before it landed" should "run when the call is retried" in {
    val cell    = new AtomicReference("text")
    val entered = new AtomicInteger()
    val tool = AgentTool(SpecTools.spec("effect").withTimeout(200.millis)) { (_, context) =>
      outcome(
        ReplayGuard.verifiable[String](context.run, context.effectKey("text.append", "cell"))(
          Right(StateCheck.of(cell.get))
        ) {
          val before = cell.get
          val after  = before + "!"
          Right(ReplayGuard.Plan(StateCheck.of(before), StateCheck.of(after), after) {
            if entered.incrementAndGet() == 1 then Thread.sleep(60_000) // hangs before writing
            if cell.compareAndSet(before, after) then Right(after)
            else Left(GraphError.EffectReplayRefused("x", "changed"))
          })
        }
      )
    }
    results(agentWith(tool).run("go").value) shouldBe Vector("text!")
    cell.get shouldBe "text!"
  }
}
