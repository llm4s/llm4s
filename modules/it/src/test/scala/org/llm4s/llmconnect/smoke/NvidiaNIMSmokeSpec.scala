package org.llm4s.llmconnect.smoke

import org.llm4s.error.AuthenticationError
import org.llm4s.llmconnect.config.{ ContextWindowResolver, NvidiaNIMConfig }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.llmconnect.provider.NvidiaNIMClient
import org.llm4s.model.ModelRegistryService
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Smoke tests for NVIDIA NIM provider.
 *
 * These tests live in the integration-test module so default `sbt test` stays fast.
 * Run them with `sbt "it/testOnly org.llm4s.llmconnect.smoke.*"` or `sbt testSmoke`.
 *
 * == Cloud mode (tagged: CloudSmoke) ==
 * Requires: `NVIDIA_API_KEY` environment variable.
 * Connects to `https://integrate.api.nvidia.com/v1` (NVIDIA hosted API).
 *
 * == On-premise mode (tagged: NIMRequired) ==
 * Requires: `NVIDIA_NIM_BASE_URL` environment variable pointing to a local NIM container.
 * No API key required for on-premise NIM deployments.
 */
class NvidiaNIMSmokeSpec extends AnyFlatSpec with Matchers {

  private given mrs: ModelRegistryService = ModelRegistryService.default().toOption.get
  private given ContextWindowResolver    = ContextWindowResolver(mrs)

  private val apiKey: Option[String]    = Option(System.getenv("NVIDIA_API_KEY")).filter(_.nonEmpty)
  private val onPremUrl: Option[String] = Option(System.getenv("NVIDIA_NIM_BASE_URL")).filter(_.nonEmpty)

  private val model          = "meta/llama-3.1-8b-instruct"
  private val cloudBaseUrl   = NvidiaNIMConfig.DEFAULT_BASE_URL

  private def conversation: Conversation = Conversation(Seq(UserMessage("Say hi in exactly one word")))

  // ---------------------------------------------------------------------------
  // Cloud mode
  // ---------------------------------------------------------------------------

  "NvidiaNIM cloud" should "complete a basic request (requires NVIDIA_API_KEY)" in {
    assume(apiKey.isDefined, "NVIDIA_API_KEY not set — skipping CloudSmoke test")

    val config = NvidiaNIMConfig.fromValues(
      modelName = model,
      apiKey = apiKey.get,
      baseUrl = cloudBaseUrl
    )
    val clientResult = NvidiaNIMClient(config)
    withClue(s"Client creation failed: ${clientResult.swap.toOption}") {
      clientResult.isRight shouldBe true
    }

    val client     = clientResult.toOption.get
    val completion = client.complete(conversation, CompletionOptions())

    withClue(s"Completion failed: ${completion.swap.toOption}") {
      completion.isRight shouldBe true
    }
    completion.toOption.get.content should not be empty
  }

  it should "return AuthenticationError for an invalid API key" in {
    assume(apiKey.isDefined, "NVIDIA_API_KEY not set — skipping CloudSmoke test")

    val config = NvidiaNIMConfig.fromValues(
      modelName = model,
      apiKey = "nvapi-invalid-key-for-testing",
      baseUrl = cloudBaseUrl
    )
    val client = NvidiaNIMClient(config).toOption.get
    val result = client.complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe an[AuthenticationError]
  }

  // ---------------------------------------------------------------------------
  // On-premise mode
  // ---------------------------------------------------------------------------

  "NvidiaNIM on-premise" should "complete a request without an API key (requires NVIDIA_NIM_BASE_URL)" in {
    assume(onPremUrl.isDefined, "NVIDIA_NIM_BASE_URL not set — skipping NIMRequired test")

    val config = NvidiaNIMConfig.fromValues(
      modelName = model,
      apiKey = "",
      baseUrl = onPremUrl.get
    )
    val clientResult = NvidiaNIMClient(config)
    withClue(s"Client creation failed: ${clientResult.swap.toOption}") {
      clientResult.isRight shouldBe true
    }

    val client     = clientResult.toOption.get
    val completion = client.complete(conversation, CompletionOptions())

    withClue(s"On-premise completion failed: ${completion.swap.toOption}") {
      completion.isRight shouldBe true
    }
    completion.toOption.get.content should not be empty
  }
}
