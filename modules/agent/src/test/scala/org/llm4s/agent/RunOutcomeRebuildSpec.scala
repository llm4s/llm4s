package org.llm4s.agent

import org.llm4s.agent.AgentFixture._
import org.llm4s.agent.graph.{ GraphRuntime, InMemoryCheckpointer, RunOutcome, ThreadId }
import org.llm4s.agent.graph.middleware.GuardrailMiddleware
import org.llm4s.agent.guardrails.builtin.LengthCheck
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * A settled turn's status rebuilt from its thread alone - the outcome its terminal checkpoint records, and that
 * checkpoint's state (design 4.41) - equals the status the turn returned live, for each way a turn settles.
 */
class RunOutcomeRebuildSpec extends AnyFlatSpec with Matchers with EitherValues {

  /** The live result of one turn on a fresh store, and the status rebuilt from what the store kept. */
  private def liveAndRebuilt(
    builder: AgentBuilder,
    query: String
  ): (Result[AgentStatus], Result[AgentStatus], Option[RunOutcome]) =
    val store  = new InMemoryCheckpointer()
    val agent  = built(builder.withRuntime(GraphRuntime(store)))
    val thread = ThreadId("settled")
    val live   = agent.run(thread, query).map(_.status)
    val stored = store.latest(thread).value.get.checkpoint
    val state  = agent.loop.graph.restore(stored.snapshot).value.state
    val rebuilt = stored.outcome
      .toRight(org.llm4s.error.ValidationError("outcome", "none recorded"))
      .flatMap(AgentRun.settledStatus(_, state))
    (live, rebuilt, stored.outcome)

  "A settled turn" should "rebuild Completed from Answered and the state's last answer" in {
    val (live, rebuilt, outcome) =
      liveAndRebuilt(Agent.builder("assistant", ScriptedLLMClient.of(CompletionFixture.simple("done"))), "go")
    outcome shouldBe Some(RunOutcome.Answered)
    live.value shouldBe AgentStatus.Completed("done")
    rebuilt shouldBe live
  }

  it should "rebuild StepLimitReached" in {
    val looping                  = new NTurnFakeLLMClient(CompletionFixture.withToolCall("missing_tool", ujson.Obj()))
    val (live, rebuilt, outcome) = liveAndRebuilt(Agent.builder("assistant", looping).withMaxSteps(2), "loop")
    outcome shouldBe Some(RunOutcome.StepLimitReached)
    live.value shouldBe AgentStatus.StepLimitReached
    rebuilt shouldBe live
  }

  it should "rebuild Blocked with the guardrail's name and reason" in {
    val builder = Agent
      .builder("assistant", ScriptedLLMClient.of(CompletionFixture.simple("never")))
      .withMiddleware(new GuardrailMiddleware(input = Seq(new LengthCheck(1, 3)), output = Nil))
    val (live, rebuilt, outcome) = liveAndRebuilt(builder, "far too long")
    outcome.get shouldBe a[RunOutcome.Blocked]
    live.value shouldBe a[AgentStatus.Blocked]
    rebuilt shouldBe live
  }

  it should "rebuild OutputInvalid with every error, as the live Left carries them" in {
    val builder = Agent
      .builder(
        "extractor",
        ScriptedLLMClient.of(
          CompletionFixture.simple("""{"customer":"Acme","total":"lots"}"""),
          CompletionFixture.simple("""{"customer":"Acme","total":"lots"}""")
        )
      )
      .withOutput(AgentOutputSpec.invoiceSchema)
    val (live, rebuilt, outcome) = liveAndRebuilt(builder, "bill Acme")
    outcome.get shouldBe a[RunOutcome.OutputInvalid]
    rebuilt shouldBe live
  }
}
