package org.llm4s.samples.cookbook

import org.llm4s.error.{ AuthenticationError, ServiceError }
import org.llm4s.llmconnect.model.AssistantMessage
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Runs the fallback recipe against scripted providers that are up, down, or flaky. */
class FallbackRecipeSpec extends AnyFlatSpec with Matchers with EitherValues {

  private def answering(text: String) = new ScriptedClient((_, _) => Right(AssistantMessage(text)))

  "FallbackClients.ask" should "answer from the primary, and never ask the fallback, when the primary is up" in {
    val (primary, fallback) = (answering("from primary"), answering("from fallback"))

    new FallbackClients(primary, fallback, FallbackRecipe.config).ask("q").value shouldBe Served(
      "primary",
      "from primary"
    )
    fallback.calls shouldBe empty
  }

  it should "retry the primary, then answer from the fallback, when the primary is down" in {
    val (primary, fallback) = (FallbackRecipe.outage, answering("from fallback"))

    new FallbackClients(primary, fallback, FallbackRecipe.config).ask("q").value shouldBe Served(
      "fallback",
      "from fallback"
    )
    primary.calls should have size 2 // maxAttempts = 2
    fallback.calls should have size 1
  }

  it should "stay on the primary when a retry succeeds" in {
    val flaky = new ScriptedClient((_, call) =>
      if (call == 1) Left(ServiceError(503, "primary", "busy")) else Right(AssistantMessage("second try"))
    )
    val fallback = answering("from fallback")

    new FallbackClients(flaky, fallback, FallbackRecipe.config).ask("q").value shouldBe Served("primary", "second try")
    fallback.calls shouldBe empty
  }

  it should "not retry an error a retry cannot fix, and go straight to the fallback" in {
    val badKey   = new ScriptedClient((_, _) => Left(AuthenticationError("primary", "invalid API key")))
    val fallback = answering("from fallback")

    new FallbackClients(badKey, fallback, FallbackRecipe.config).ask("q").value.by shouldBe "fallback"
    badKey.calls should have size 1
  }

  it should "return the fallback's error when both providers fail" in {
    val down = new ScriptedClient((_, _) => Left(ServiceError(502, "fallback", "bad gateway")))

    val error = new FallbackClients(FallbackRecipe.outage, down, FallbackRecipe.config).ask("q").left.value

    error shouldBe a[ServiceError]
    error.message should include("bad gateway")
  }

  "FallbackRecipe.demo" should "show the fallback answering for the simulated outage" in {
    FallbackRecipe.demo(FallbackRecipe.script).value should startWith("[fallback] ")
  }
}
