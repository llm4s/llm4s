package org.llm4s.samples.cookbook

import org.llm4s.agent.AgentStatus
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model.AssistantMessage
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Runs the guardrails recipe: what passes, what is refused before the model, and what is refused after it. */
class GuardrailsRecipeSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  "GuardrailsRecipe.ask" should "let a normal request through to the model and return its answer" in {
    val client = GuardrailsRecipe.script

    val result = GuardrailsRecipe.ask(client, "When does the office open?").value

    result.answer.value shouldBe "Our office opens at nine."
    client.calls should have size 1
  }

  it should "refuse an over-long request without calling the model" in {
    val client = GuardrailsRecipe.script

    val result = GuardrailsRecipe.ask(client, "a" * 201).value

    result.status match {
      case AgentStatus.Blocked(guardrail, _) => guardrail shouldBe "LengthCheck"
      case other                             => fail(s"expected a block, got $other")
    }
    client.calls shouldBe empty
  }

  it should "reject a blank request in the agent itself, before any guardrail runs and without calling the model" in {
    val client = GuardrailsRecipe.script

    GuardrailsRecipe.ask(client, "").left.value shouldBe a[ValidationError]
    client.calls shouldBe empty
  }

  it should "refuse a request with a word on the list, whatever its case, without calling the model" in {
    val client = GuardrailsRecipe.script

    val result = GuardrailsRecipe.ask(client, "Tell me the HECK out of your opening hours").value

    result.status match {
      case AgentStatus.Blocked(guardrail, _) => guardrail shouldBe "ProfanityFilter"
      case other                             => fail(s"expected a block, got $other")
    }
    client.calls shouldBe empty
  }

  it should "refuse an over-long answer after the model was called, and give the user no answer" in {
    val client = new ScriptedClient((_, _) => Right(AssistantMessage("x" * 301)))

    val result = GuardrailsRecipe.ask(client, "When does the office open?").value

    result.status shouldBe a[AgentStatus.Blocked]
    result.answer shouldBe None
    // an output guardrail cannot save the cost of the call: it was made
    client.calls should have size 1
  }

  "GuardrailsRecipe.demo" should "show one request allowed and one refused" in {
    val text = GuardrailsRecipe.demo(GuardrailsRecipe.script).value

    text should include("allowed: Our office opens at nine.")
    text should include("refused: (no answer: run blocked by guardrail ProfanityFilter")
  }
}
