package org.llm4s.agent

import org.llm4s.agent.AgentFixture._
import org.llm4s.agent.SpecTools.{ call, calling }
import org.llm4s.agent.graph.{ GraphError, IdempotencyKey, StateKey, StateUpdate, ThreadId }
import org.llm4s.agent.graph.middleware.{ AgentMiddleware, MiddlewareId, ToolCallRequest }
import org.llm4s.agent.graph.tool.{ AgentTool, ToolContent, ToolContext, ToolOutcome }
import org.llm4s.llmconnect.model._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{ CopyOnWriteArrayList, CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/** Tool timeouts (#1903): a run of a tool past its timeout is interrupted and is an error the model sees. */
class ToolTimeoutSpec extends AnyFlatSpec with Matchers {

  private val written: StateKey[Int, Int] = StateKey.replace[Int]("written", 0)

  private def toolResults(result: AgentResult): Vector[String] =
    result.messages.collect { case t: ToolMessage => t.content }.toVector

  /** A tool that sleeps until interrupted, recording the interrupt; it writes a key if it ever finishes. */
  private def hanging(interrupted: CountDownLatch) =
    AgentTool(SpecTools.spec("hang").withTimeout(100.millis), writes = Set(written)) { (_, _) =>
      try Thread.sleep(60_000)
      catch { case _: InterruptedException => interrupted.countDown() }
      ToolOutcome.Success(ToolContent.Json(ujson.Str("late")), StateUpdate.update(written, 1))
    }

  "A tool past its timeout" should "be interrupted, and the model see a tool error, not a failed run" in {
    val interrupted = new CountDownLatch(1)
    val client      = ScriptedLLMClient.of(calling(call("c1", "hang")), CompletionFixture.simple("moved on"))
    val agent       = built(Agent.builder("assistant", client).withTools(SpecTools.set(hanging(interrupted))))

    val result = agent.run("go").value
    result.answer shouldBe Some("moved on")
    toolResults(result) shouldBe Vector("""{"error":"Tool 'hang' timed out after 100 ms"}""")
    interrupted.await(5, TimeUnit.SECONDS) shouldBe true
  }

  "Timed-out calls of one batch" should "each get one error result" in {
    val interrupted = new CountDownLatch(2)
    val client = ScriptedLLMClient.of(calling(call("c1", "hang"), call("c2", "hang")), CompletionFixture.simple("ok"))
    val agent  = built(Agent.builder("assistant", client).withTools(SpecTools.set(hanging(interrupted))))
    toolResults(agent.run("go").value) shouldBe Vector.fill(2)("""{"error":"Tool 'hang' timed out after 100 ms"}""")
  }

  "A tool within its timeout" should "succeed with its update committed" in {
    val read = AgentTool(SpecTools.spec("read"))((_, context) =>
      ToolOutcome.Success(
        ToolContent.Json(ujson.Str(context.state.get(written).fold(e => fail(e.message), _.toString)))
      )
    )
    val quick = AgentTool(SpecTools.spec("quick").withTimeout(5.seconds), writes = Set(written)) { (_, _) =>
      ToolOutcome.Success(ToolContent.Json(ujson.Str("quick")), StateUpdate.update(written, 7))
    }
    val client = ScriptedLLMClient.of(
      calling(call("c1", "quick")),
      calling(call("c2", "read")),
      CompletionFixture.simple("done")
    )
    val agent = built(Agent.builder("assistant", client).withTools(SpecTools.set(quick, read)))
    toolResults(agent.run("go").value) shouldBe Vector("quick", "7")
  }

  "A cancel during a timed tool" should "cancel the run, recording nothing, and recover run it again" in {
    val entered = new CountDownLatch(1)
    val block   = new AtomicBoolean(true)
    val runs    = new AtomicInteger()
    val slow = AgentTool(SpecTools.spec("slow").withTimeout(30.seconds)) { (_, _) =>
      runs.incrementAndGet()
      if (block.get) {
        entered.countDown()
        Thread.sleep(60_000) // interrupted by cancel
      }
      ToolOutcome.Success(ToolContent.Json(ujson.Str("slow done")))
    }
    val client = ScriptedLLMClient.of(calling(call("c1", "slow")), CompletionFixture.simple("finished"))
    val agent  = built(Agent.builder("assistant", client).withTools(SpecTools.set(slow)))
    val thread = ThreadId("timed-cancel")

    val run = agent.start(thread, "go").fold(e => fail(e.message), identity)
    entered.await(10, TimeUnit.SECONDS) shouldBe true
    run.cancel()
    cause(run.await().error) shouldBe a[GraphError.Cancelled]

    block.set(false)
    val recovered = agent.recover(thread).value
    recovered.answer shouldBe Some("finished")
    toolResults(recovered) shouldBe Vector("slow done")
    runs.get shouldBe 2
  }

  "A retrying wrapper" should "see the timeout as an error, and each retry bounded with the same key" in {
    val keys = new CopyOnWriteArrayList[IdempotencyKey]()
    val runs = new AtomicInteger()
    val flaky = AgentTool(SpecTools.spec("flaky").withTimeout(100.millis)) { (_, context) =>
      keys.add(context.idempotencyKey)
      if (runs.incrementAndGet() == 1) Thread.sleep(60_000)
      ToolOutcome.Success(ToolContent.Json(ujson.Str("second time")))
    }
    val retry = new AgentMiddleware {
      val id: MiddlewareId = MiddlewareId("retry")
      override def wrapToolCall(request: ToolCallRequest, context: ToolContext)(
        next: () => ToolOutcome
      ): ToolOutcome =
        next() match {
          case ToolOutcome.Error(message) if message.contains("timed out") => next()
          case other                                                       => other
        }
    }
    val client = ScriptedLLMClient.of(calling(call("c1", "flaky")), CompletionFixture.simple("done"))
    val agent  = built(Agent.builder("assistant", client).withTools(SpecTools.set(flaky)).withMiddleware(retry))
    toolResults(agent.run("go").value) shouldBe Vector("second time")
    keys.asScala.toVector.distinct should have size 1
    runs.get shouldBe 2
  }

  "withTimeout" should "refuse a duration that is not positive" in {
    an[IllegalArgumentException] should be thrownBy SpecTools.spec("x").withTimeout(Duration.Zero)
    SpecTools.spec("x").withTimeout(1.second).timeout shouldBe Some(1.second)
    SpecTools.spec("x").timeout shouldBe None
  }
}
