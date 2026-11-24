package org.llm4s.agent

import org.llm4s.agent.delegation.{ AncestorPermissions, DelegationMiddleware }
import org.llm4s.agent.graph.middleware.{ AgentMiddleware, MiddlewareId, MiddlewareStack }
import org.llm4s.agent.guardrails.builtin.LengthCheck
import org.llm4s.testutil.TestTimeLimit
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * The ids the agent gives the middleware it installs itself are listed in one place, `MiddlewareId.reserved`, and a
 * middleware of the user's with one of them is refused by name (#2795, item 10) - whether or not the agent happens to
 * install its own, so adding subagents or guardrails later cannot turn a working id into a duplicate.
 */
class ReservedMiddlewareIdSpec extends AnyFlatSpec with TestTimeLimit with Matchers with EitherValues {

  private def named(name: String): AgentMiddleware = new AgentMiddleware { val id: MiddlewareId = MiddlewareId(name) }

  private def client = ScriptedLLMClient.of(CompletionFixture.simple("ok"))

  "MiddlewareId.reserved" should "list every id the agent installs a middleware or a prompt section under" in {
    MiddlewareId.reserved.keySet.map(_.value) shouldBe
      Set("agent-guardrails", "delegation", "ancestor-permissions", "response-format")
    MiddlewareId.reserved.values.foreach(_ should not be empty)
  }

  it should "be what the agent's own middleware use" in {
    AgentBuilder.GuardrailsId shouldBe MiddlewareId.AgentGuardrails
    DelegationMiddleware.Id shouldBe MiddlewareId.Delegation
    AncestorPermissions.Id shouldBe MiddlewareId.AncestorPermissions
    MiddlewareId.reserved.keySet shouldBe Set(
      MiddlewareId.AgentGuardrails,
      MiddlewareId.Delegation,
      MiddlewareId.AncestorPermissions,
      MiddlewareId.ResponseFormat
    )
  }

  "AgentBuilder.build" should "refuse a middleware with a reserved id, saying what the id is reserved for" in {
    for (id, purpose) <- MiddlewareId.reserved do
      val error = Agent.builder("assistant", client).withMiddleware(named(id.value)).build().left.value
      error.message should include(s"middleware id '${id.value}' is reserved for $purpose")
  }

  it should "refuse it whether or not the agent installs its own under that id" in {
    // no guardrails on the builder, so nothing else has the id: refused all the same
    Agent.builder("assistant", client).withMiddleware(named("agent-guardrails")).build().isLeft shouldBe true
    Agent
      .builder("assistant", client)
      .withInputGuardrails(LengthCheck(1, 100))
      .withMiddleware(named("agent-guardrails"))
      .build()
      .left
      .value
      .message should include("middleware id 'agent-guardrails' is reserved")
  }

  it should "report every reserved id used, once" in {
    val error = Agent
      .builder("assistant", client)
      .withMiddleware(named("delegation"), named("fine"), named("response-format"))
      .build()
      .left
      .value
    error.message should include("middleware id 'delegation' is reserved")
    error.message should include("middleware id 'response-format' is reserved")
    (error.message should not).include("'fine'")
  }

  it should "accept any other id" in {
    Agent.builder("assistant", client).withMiddleware(named("guardrails"), named("mine")).build().isRight shouldBe true
  }

  "MiddlewareStack.of" should "say a duplicate id is reserved, when it is" in {
    MiddlewareStack.of(named("delegation"), named("delegation")).left.value.message should include(
      "duplicate middleware id 'delegation': the id is reserved for " + MiddlewareId.reserved(MiddlewareId.Delegation)
    )
    val plain = MiddlewareStack.of(named("mine"), named("mine")).left.value.message
    plain should include("duplicate middleware id 'mine'")
    (plain should not).include("reserved")
  }
}
