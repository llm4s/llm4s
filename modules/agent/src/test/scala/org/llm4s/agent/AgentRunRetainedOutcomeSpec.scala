package org.llm4s.agent

import org.llm4s.agent.AgentFixture._
import org.llm4s.agent.graph.{ GraphRuntime, InMemoryCheckpointer, RunStatus, ThreadId }
import org.llm4s.agent.guardrails.builtin.LengthCheck
import org.llm4s.testutil.TestTimeLimit
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * `AgentRun.await` retains a turn's outcome (#3251): a turn a guardrail blocked is `Blocked` on every call, also once
 * a later turn has moved its thread on, which once turned the retained outcome into a `Left(GuardrailBlocked)`.
 */
class AgentRunRetainedOutcomeSpec extends AnyFlatSpec with TestTimeLimit with Matchers {

  "A blocked turn's await" should "stay Blocked once a later turn moved its thread on (#3251)" in {
    val store  = new InMemoryCheckpointer
    val client = ScriptedLLMClient.of(CompletionFixture.simple("a long enough answer"))
    val agent = built(
      Agent.builder("assistant", client).withInputGuardrails(LengthCheck(10, 100)).withRuntime(new GraphRuntime(store))
    )
    val thread  = ThreadId("retained-block")
    val blocked = agent.start(thread, "Short").fold(e => fail(e.message), identity)
    blocked.await().value.status shouldBe a[AgentStatus.Blocked]
    // a later turn on the thread: its latest checkpoint is no longer the blocked run's
    agent.run(thread, "a query long enough to pass").value.status shouldBe a[AgentStatus.Completed]
    blocked.await().value.status shouldBe a[AgentStatus.Blocked]
  }

  it should "be Blocked when first awaited after a later turn moved its thread on (#3251)" in {
    val store  = new InMemoryCheckpointer
    val client = ScriptedLLMClient.of(CompletionFixture.simple("a long enough answer"))
    val agent = built(
      Agent.builder("assistant", client).withInputGuardrails(LengthCheck(10, 100)).withRuntime(new GraphRuntime(store))
    )
    val thread  = ThreadId("late-await")
    val blocked = agent.start(thread, "Short").fold(e => fail(e.message), identity)
    // the turn has ended (its claim is free) before a later one runs; its await comes only after
    val deadline = System.nanoTime() + 10_000_000_000L
    while (blocked.status == RunStatus.Running && System.nanoTime() < deadline) Thread.sleep(5)
    agent.run(thread, "a query long enough to pass").value.status shouldBe a[AgentStatus.Completed]
    blocked.await().value.status shouldBe a[AgentStatus.Blocked]
  }
}
