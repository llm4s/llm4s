package org.llm4s.agent

import org.llm4s.agent.AgentFixture._
import org.llm4s.agent.SpecTools.{ call, calling }
import org.llm4s.agent.graph._
import org.llm4s.agent.graph.tool.{ ToolContent, ToolOutcome }
import org.llm4s.agent.graph.toolloop.ToolResultRule
import org.llm4s.llmconnect.model._
import org.llm4s.testutil.TestTimeLimit
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ CountDownLatch, TimeUnit }

/**
 * A queued turn stopped at once ([[AgentRun.stopSettling]] with no boundary) during a superstep of parallel tool calls
 * (#3252): a call that had finished keeps its real result, as `Agent.abandon` keeps it; only the call that was cut gets
 * the settlement's result. A finished call answered as cut could be run again by the model on the next turn.
 */
class AgentStopSettledSpec extends AnyFlatSpec with TestTimeLimit with Matchers {

  private def results(messages: Seq[Message]): Vector[(String, String)] =
    messages.collect { case t: ToolMessage => t.toolCallId.asString -> ContentPart.text(t.parts) }.toVector

  "A settling stop that cuts a superstep" should "keep a finished call's result and settle only the cut call (#3252)" in {
    val client = ScriptedLLMClient.of(
      calling(call("g1", "good"), call("s1", "slow")),
      CompletionFixture.simple("moved on")
    )
    val goodDone    = new CountDownLatch(1)
    val slowStarted = new CountDownLatch(1)
    val goodRan     = new AtomicInteger(0)
    val good = SpecTools.tool("good") { (_, _) =>
      goodRan.incrementAndGet()
      goodDone.countDown()
      ToolOutcome.success(ToolContent.Json(ujson.Str("good")))
    }
    val slow = SpecTools.tool("slow") { (_, _) =>
      slowStarted.countDown()
      // waits to be cut by the stop's interrupt
      new CountDownLatch(1).await(30, TimeUnit.SECONDS)
      ToolOutcome.success(ToolContent.Json(ujson.Str("slow")))
    }
    val store = new InMemoryCheckpointer
    val agent = built(
      Agent.builder("assistant", client).withToolSet(SpecTools.set(good, slow)).withRuntime(new GraphRuntime(store))
    )
    val thread = ThreadId("settled-mid-superstep")
    val run    = agent.start(thread, "go").fold(e => fail(e.message), identity)
    goodDone.await(10, TimeUnit.SECONDS) shouldBe true
    slowStarted.await(10, TimeUnit.SECONDS) shouldBe true
    // the finished call's write is stored before the stop cuts the other one
    val deadline = System.nanoTime() + 10_000_000_000L
    while (store.latest(thread).toOption.flatten.forall(_.pendingWrites.isEmpty) && System.nanoTime() < deadline)
      Thread.sleep(5)
    run.stopSettling("cancelled", RunOutcome.Failed("Cancelled", "stopped"), atBoundary = false)
    run.await().isLeft shouldBe true

    val settled = store.latest(thread).toOption.flatten.map(_.checkpoint).getOrElse(fail("no checkpoint"))
    settled.status shouldBe CheckpointStatus.Failed

    val done = agent.run(thread, "carry on").value
    done.answer shouldBe Some("moved on")
    ToolResultRule.violations(client.sent.last) shouldBe empty
    results(client.sent.last) should contain theSameElementsAs Vector("g1" -> "good", "s1" -> "cancelled")
    goodRan.get shouldBe 1
  }
}
