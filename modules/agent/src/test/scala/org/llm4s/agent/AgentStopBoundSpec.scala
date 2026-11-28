package org.llm4s.agent

import org.llm4s.agent.AgentFixture._
import org.llm4s.agent.SpecTools.{ call, calling }
import org.llm4s.agent.graph._
import org.llm4s.agent.graph.tool.{ ToolContent, ToolOutcome }
import org.llm4s.testutil.TestTimeLimit
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }
import java.util.concurrent.{ CountDownLatch, TimeUnit }
import scala.concurrent.duration._

/**
 * A cancel ends a run whatever its tool does with the interrupt (#3253): a tool that swallows it and returns has its
 * result dropped, never committed for `recover` to reuse. `StopBoundSpec` covers a node that ignores it.
 */
class AgentStopBoundSpec extends AnyFlatSpec with TestTimeLimit with Matchers {

  private val claims = ClaimPolicy(ttl = 300.millis, renewEvery = 100.millis)

  "A cancel of a run whose tool swallows the interrupt and returns" should "drop the tool's result, never committing it (#3253)" in {
    val entered = new CountDownLatch(1)
    val ran     = new AtomicInteger(0)
    val swallow = new AtomicBoolean(true)
    val tool = SpecTools.tool("swallowing") { (_, _) =>
      ran.incrementAndGet()
      entered.countDown()
      if (swallow.get) {
        // sleeps until interrupted, then clears the flag and answers as if nothing happened
        org.llm4s.error.CancelledError.catchInterrupt(Thread.sleep(30_000)): Unit
      }
      ToolOutcome.success(ToolContent.Json(ujson.Str("swallowed")))
    }
    val store = new InMemoryCheckpointer
    val agent = built(
      Agent
        .builder(
          "assistant",
          ScriptedLLMClient.of(calling(call("w1", "swallowing")), CompletionFixture.simple("after recover"))
        )
        .withToolSet(SpecTools.set(tool))
        .withRuntime(new GraphRuntime(store, claims = claims))
    )
    val thread = ThreadId("swallowed-interrupt")
    val run    = agent.start(thread, "go").fold(e => fail(e.message), identity)
    entered.await(10, TimeUnit.SECONDS) shouldBe true
    run.cancel()
    run.await().isLeft shouldBe true
    store.latest(thread).fold(e => fail(e.message), identity).get.pendingWrites shouldBe empty
    swallow.set(false)
    // recover runs the call again: its result was never committed
    agent.recover(thread).value.answer shouldBe Some("after recover")
    ran.get shouldBe 2
  }
}
