package org.llm4s.llmconnect.config

import org.scalatest.OptionValues.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class BedrockConfigSpec extends AnyWordSpec with Matchers {

  private val registry                = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  private given ContextWindowResolver = ContextWindowResolver(registry)

  /** What the bundled registry says about a Bedrock model; the tests follow it rather than copy it. */
  private def registered(model: String) =
    registry.lookup("bedrock", model).toOption.value

  "BedrockConfig.fromValues" should {

    "take the context window and reserve from the model registry" in {
      val config = BedrockConfig.fromValues("anthropic.claude-3-5-sonnet-20241022-v2:0", "us-east-1").toOption.value
      config.region shouldBe "us-east-1"
      config.model shouldBe "anthropic.claude-3-5-sonnet-20241022-v2:0"
      val metadata = registered("anthropic.claude-3-5-sonnet-20241022-v2:0")
      config.contextWindow shouldBe metadata.maxInputTokens.value
      config.reserveCompletion shouldBe metadata.maxOutputTokens.value
      config.contextWindow should not be 8192 // the unknown-model default
    }

    "know the regional inference-profile ids, not only the base model ids" in {
      val config = BedrockConfig.fromValues("us.anthropic.claude-3-5-sonnet-20241022-v2:0", "us-east-1").toOption.value
      config.contextWindow shouldBe registered("us.anthropic.claude-3-5-sonnet-20241022-v2:0").maxInputTokens.value
      config.contextWindow should not be 8192
    }

    "use one conservative default for a model the registry does not know" in {
      val config = BedrockConfig.fromValues("vendor.unregistered-model-v99", "ap-southeast-1").toOption.value
      config.contextWindow shouldBe 8192
      config.reserveCompletion shouldBe 4096
    }

    "return a Left, not throw, for a blank region" in {
      BedrockConfig.fromValues("amazon.titan-text-express-v1", "  ").left.toOption.value.message should include(
        "region"
      )
    }

    "return a Left for a blank model" in {
      BedrockConfig.fromValues(" ", "us-east-1").left.toOption.value.message should include("model")
    }

    "return a Left for blank credentials" in {
      val blankKey = Some(BedrockCredentials("", "secret"))
      BedrockConfig.fromValues("m", "us-east-1", credentials = blankKey).left.toOption.value.message should include(
        "accessKeyId"
      )
      val blankSecret = Some(BedrockCredentials("AKID", ""))
      BedrockConfig.fromValues("m", "us-east-1", credentials = blankSecret).left.toOption.value.message should include(
        "secretAccessKey"
      )
    }

    "return a Left for credentials together with a profile" in {
      BedrockConfig
        .fromValues("m", "us-east-1", credentials = Some(BedrockCredentials("a", "b")), profile = Some("work"))
        .isLeft shouldBe true
    }
  }

  "BedrockConfig" should {

    "identify as the bedrock provider and expose its endpoint override" in {
      val config = BedrockConfig.fromValues("m", "us-east-1", endpointUrl = Some("https://vpce")).toOption.value
      config.providerId.asString shouldBe "bedrock"
      config.endpointUrl shouldBe Some("https://vpce")
    }

    "keep its credentials, but switch model, with withModel" in {
      val config =
        BedrockConfig.fromValues("m1", "us-east-1", credentials = Some(BedrockCredentials("a", "b"))).toOption.value
      config.withModel("m2") shouldBe config.copy(model = "m2")
    }

    "redact credentials in toString" in {
      val config = BedrockConfig
        .fromValues(
          "amazon.titan-text-express-v1",
          "us-east-1",
          credentials = Some(
            BedrockCredentials(
              "AKIAIOSFODNN7EXAMPLE",
              "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
              Some("session-token-value")
            )
          )
        )
        .toOption
        .value
      val text = config.toString
      text should (include("us-east-1").and(include("amazon.titan-text-express-v1")))
      (text should not).include("AKIAIOSFODNN7EXAMPLE")
      (text should not).include("wJalrXUtnFEMI")
      (text should not).include("session-token-value")
    }

    "say it uses the default chain when it has no credentials" in {
      BedrockConfig.fromValues("m", "us-east-1").toOption.value.toString should include("default chain")
    }
  }
}
