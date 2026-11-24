package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, RequestAdjustment, RequestOption, UserMessage }
import org.llm4s.llmconnect.provider.BedrockTestSupport.*
import org.llm4s.model.{ ModelCapabilities, ModelRegistryService }
import org.llm4s.testkit.LocalProviderTestServer.{ sendJsonResponse, withServer }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks

import java.nio.charset.StandardCharsets

/**
 * [[BedrockClaudeRules]]: the Claude models on Bedrock that take no sampling parameter, and the Converse requests
 * `BedrockClient` then sends (#2909). The id tables are the ones `llm4s-anthropic`'s `AnthropicModelRulesSpec` checks.
 */
class BedrockClaudeRulesSpec extends AnyFlatSpec with Matchers with TableDrivenPropertyChecks {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val rejecting = Table(
    "model",
    "anthropic.claude-opus-4-7",
    "anthropic.claude-opus-4-8",
    "anthropic.claude-opus-5",
    "anthropic.claude-opus-5-5",
    "anthropic.claude-sonnet-5",
    "anthropic.claude-sonnet-5-5",
    "anthropic.claude-haiku-5-5",
    "anthropic.claude-fable-5",
    "anthropic.claude-fable-5-2",
    "anthropic.claude-mythos-5-2",
    "us.anthropic.claude-sonnet-5-5",
    "eu.anthropic.claude-opus-5-5",
    "apac.anthropic.claude-haiku-5-5",
    "global.anthropic.claude-fable-5-2",
    "us-gov.anthropic.claude-opus-4-8",
    "us.anthropic.claude-sonnet-5-v1:0",
    "anthropic.claude-sonnet-5-5-20260901-v1:0",
    "bedrock/us-gov-east-1/anthropic.claude-sonnet-5-5",
    "arn:aws:bedrock:us-east-1:123456789012:inference-profile/us.anthropic.claude-sonnet-5-5",
    "claude-sonnet-5-5",
    "claude-opus-10-2",
    "claude-sonnet-latest",
    "openrouter/anthropic/claude-fable-5.2"
  )

  private val accepting = Table(
    "model",
    "anthropic.claude-opus-4-6-v1",
    "anthropic.claude-sonnet-4-6",
    "us.anthropic.claude-sonnet-4-6",
    "anthropic.claude-haiku-4-5-20251001-v1:0",
    "anthropic.claude-haiku-4-5@20251001",
    "anthropic.claude-opus-4-20250514-v1:0",
    "anthropic.claude-sonnet-4-20250514-v1:0",
    "anthropic.claude-3-opus-20240229-v1:0",
    "anthropic.claude-3-7-sonnet-20250219-v1:0",
    "anthropic.claude-3-5-haiku-20241022-v1:0",
    "amazon.titan-text-express-v1",
    "amazon.nova-pro-v1:0",
    "meta.llama3-70b-instruct-v1:0",
    "arn:aws:bedrock:us-east-1:123456789012:application-inference-profile/abc123"
  )

  "BedrockClaudeRules.rejectsSampling" should "be true for the Claude models that take no sampling parameter" in {
    forAll(rejecting)(model => BedrockClaudeRules.rejectsSampling(model) shouldBe true)
  }

  it should "be false for earlier Claude models, other vendors' models and an id that names no model" in {
    forAll(accepting)(model => BedrockClaudeRules.rejectsSampling(model) shouldBe false)
  }

  "BedrockClaudeRules.adjust" should "rule the sampling parameters out where the registry is silent" in {
    val found = ModelCapabilities().withDisallowedParams(Set("logprobs"))
    BedrockClaudeRules.adjust("us.anthropic.claude-sonnet-5-5", found) shouldBe found.withSupportsSamplingParams(false)
    BedrockClaudeRules.adjust("anthropic.claude-sonnet-4-6", found) shouldBe found
    val stated = found.withSupportsSamplingParams(true)
    BedrockClaudeRules.adjust("us.anthropic.claude-sonnet-5-5", stated) shouldBe stated
  }

  "The bundled catalog" should "flag no Bedrock Claude model that the name rule would send sampling parameters to" in {
    val claude = summon[ModelRegistryService]
      .search("claude")
      .filter(m => Set("bedrock", "bedrock_converse", "bedrock_mantle").contains(m.provider.asString))
    val flagged = claude.collect {
      case m if m.capabilities.supportsSamplingParams.contains(false) => m.modelId.asString
    }
    flagged.size should be > 50
    flagged.filterNot(BedrockClaudeRules.rejectsSampling) shouldBe empty
  }

  // -------- the Converse request --------

  /** The Converse request body sent to `model`, and the completion's adjustments. */
  private def sent(
    model: String,
    options: CompletionOptions,
    streaming: Boolean
  ): (ujson.Value, Seq[RequestAdjustment]) = {
    var seen: Option[String]                = None
    var adjustments: Seq[RequestAdjustment] = Nil
    val conversation                        = Conversation(Seq(UserMessage("hi")))
    withServer("/") { exchange =>
      seen = Some(new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8))
      if (streaming) sendEventStream(exchange, textStream("o", "k"))
      else sendJsonResponse(exchange, 200, converseResponse("ok"))
    } { url =>
      val client = new BedrockClient(config(url, model))
      val completion =
        if (streaming) client.streamComplete(conversation, options)(_ => ())
        else client.complete(conversation, options)
      adjustments = completion.toOption.get.adjustments
      client.close()
    }
    (ujson.read(seen.get), adjustments)
  }

  private def inference(body: ujson.Value): Set[String] =
    body.obj.get("inferenceConfig").map(_.obj.keySet.toSet).getOrElse(Set.empty)

  private def affected = List(
    "us.anthropic.claude-sonnet-5-5",
    "anthropic.claude-sonnet-5",
    "eu.anthropic.claude-haiku-5-5",
    "global.anthropic.claude-opus-5-5",
    "anthropic.claude-opus-4-8",
    "apac.anthropic.claude-fable-5-2",
    "anthropic.claude-sonnet-5-5-20260901-v1:0"
  )

  "BedrockClient" should "send no sampling parameter with default options" in {
    for {
      model     <- affected
      streaming <- List(false, true)
    }
      withClue(s"$model streaming=$streaming") {
        val (body, adjustments) = sent(model, CompletionOptions(), streaming)
        (inference(body) & Set("temperature", "topP")) shouldBe empty
        adjustments shouldBe Nil
      }
  }

  it should "drop a temperature and a top-p the caller set for a Claude model that rejects them, and report both" in {
    for {
      model     <- affected
      streaming <- List(false, true)
    }
      withClue(s"$model streaming=$streaming") {
        val options             = CompletionOptions(maxTokens = Some(321)).withTemperature(0.2).withTopP(0.9)
        val (body, adjustments) = sent(model, options, streaming)
        inference(body) shouldBe Set("maxTokens")
        adjustments.collect { case RequestAdjustment.Dropped(option, _) => option }.toSet shouldBe
          Set(RequestOption.Temperature, RequestOption.TopP)
      }
  }

  it should "still send them to a Claude model that takes them, and to another vendor's model" in {
    for {
      model     <- List("us.anthropic.claude-sonnet-4-6", "anthropic.claude-haiku-4-5-20251001-v1:0", Model)
      streaming <- List(false, true)
    }
      withClue(s"$model streaming=$streaming") {
        val (body, adjustments) = sent(model, CompletionOptions().withTemperature(0.2), streaming)
        body("inferenceConfig")("temperature").num shouldBe 0.2 +- 1e-6
        adjustments shouldBe Nil
      }
  }
}
