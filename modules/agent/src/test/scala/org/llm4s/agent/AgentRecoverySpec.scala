package org.llm4s.agent

import org.llm4s.agent.AgentFixture._
import org.llm4s.agent.SpecTools.{ call, calling }
import org.llm4s.agent.graph.{ GraphError, RunBudgets, RunConfig, ThreadId }
import org.llm4s.agent.graph.tool.ToolOutcome
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }
import scala.concurrent.duration._

/** A failed or interrupted turn leaves its thread for `recover`, which re-runs only the unfinished work. */
class AgentRecoverySpec extends AnyFlatSpec with Matchers {

  private val thread = ThreadId("recovery")

  "A tool's Fatal" should "fail the run with ToolFailed, and recover run only the failed tool again" in {
    val broken   = new AtomicBoolean(true)
    val goodRuns = new AtomicInteger()
    val good = SpecTools.tool("good") { (_, _) =>
      goodRuns.incrementAndGet(); ToolOutcome.Success(ujson.Str("good"))
    }
    val flaky = SpecTools.tool("flaky") { (_, _) =>
      if (broken.get) ToolOutcome.Fatal(ValidationError("flaky", "backend down"))
      else ToolOutcome.Success(ujson.Str("fixed"))
    }
    val client = ScriptedLLMClient.of(
      calling(call("c1", "good"), call("c2", "flaky")),
      CompletionFixture.simple("all done")
    )
    val agent = built(Agent.builder("assistant", client).withTools(SpecTools.set(good, flaky)))

    val failed = agent.run(thread, "go")
    cause(failed.error) match {
      case GraphError.ToolFailed(tool, callId, _) =>
        tool shouldBe org.llm4s.agent.graph.ToolName("flaky")
        callId shouldBe org.llm4s.agent.graph.ToolCallId("c2")
      case other => fail(s"expected ToolFailed, got $other")
    }

    broken.set(false)
    val recovered = agent.recover(thread).value

    recovered.answer shouldBe Some("all done")
    recovered.messages.collect { case t: ToolMessage => t.toolCallId } shouldBe Vector("c1", "c2")
    goodRuns.get shouldBe 1
    client.callCount shouldBe 2
  }

  "A cancelled run" should "end Cancelled, and recover completes it" in {
    val started = new CountDownLatch(1)
    val block   = new AtomicBoolean(true)
    val slow = SpecTools.tool("slow") { (_, _) =>
      if (block.get) {
        started.countDown()
        Thread.sleep(60_000) // interrupted by cancel
      }
      ToolOutcome.Success(ujson.Str("slow done"))
    }
    val client = ScriptedLLMClient.of(calling(call("c1", "slow")), CompletionFixture.simple("finished"))
    val agent  = built(Agent.builder("assistant", client).withTools(SpecTools.set(slow)))

    val run = agent.start(thread, "go").fold(e => fail(e.message), identity)
    started.await(10, TimeUnit.SECONDS) shouldBe true
    run.cancel()

    cause(run.await().error) shouldBe a[GraphError.Cancelled]

    block.set(false)
    agent.recover(thread).value.answer shouldBe Some("finished")
  }

  "startRecover" should "return a run that can be cancelled, leaving the thread for recover again" in {
    val entered = new java.util.concurrent.Semaphore(0)
    val block   = new AtomicBoolean(true)
    val slow = SpecTools.tool("slow") { (_, _) =>
      if (block.get) {
        entered.release()
        Thread.sleep(60_000) // interrupted by cancel
      }
      ToolOutcome.Success(ujson.Str("slow done"))
    }
    val client = ScriptedLLMClient.of(calling(call("c1", "slow")), CompletionFixture.simple("finished"))
    val agent  = built(Agent.builder("assistant", client).withTools(SpecTools.set(slow)))

    val first = agent.start(thread, "go").fold(e => fail(e.message), identity)
    entered.tryAcquire(10, TimeUnit.SECONDS) shouldBe true
    first.cancel()
    first.await(): Unit

    val again = agent.startRecover(thread).fold(e => fail(e.message), identity)
    entered.tryAcquire(10, TimeUnit.SECONDS) shouldBe true
    again.cancel()
    cause(again.await().error) shouldBe a[GraphError.Cancelled]

    block.set(false)
    agent.recover(thread).value.answer shouldBe Some("finished")
  }

  "A run past its deadline" should "end DeadlineExceeded, and recover with no timeout completes it" in {
    val block = new AtomicBoolean(true)
    val slow = SpecTools.tool("slow") { (_, _) =>
      if (block.get) Thread.sleep(60_000) // interrupted at the deadline
      ToolOutcome.Success(ujson.Str("slow done"))
    }
    val client = ScriptedLLMClient.of(calling(call("c1", "slow")), CompletionFixture.simple("in time"))
    val agent  = built(Agent.builder("assistant", client).withTools(SpecTools.set(slow)))

    val timed = agent.run(thread, "go", RunConfig().withBudgets(RunBudgets(timeout = Some(50.millis))))
    cause(timed.error) shouldBe a[GraphError.DeadlineExceeded]

    block.set(false)
    agent.recover(thread).value.answer shouldBe Some("in time")
  }

  "StepLimitReached" should "be a completed turn: the next one runs normally" in {
    val echo   = SpecTools.tool("echo")((a, _) => ToolOutcome.Success(ujson.Str(a.text)))
    val client = ScriptedLLMClient.of(calling(call("c1", "echo")), CompletionFixture.simple("next turn"))
    val agent  = built(Agent.builder("assistant", client).withMaxSteps(1).withTools(SpecTools.set(echo)))

    val limited = agent.run(thread, "go").value
    limited.status shouldBe AgentStatus.StepLimitReached

    val next = agent.continueConversation(limited, "again").value
    next.answer shouldBe Some("next turn")
    next.messages.collect { case u: UserMessage => u.content } shouldBe Vector("go", "again")
  }
}
