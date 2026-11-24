package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.AnthropicConfig
import org.llm4s.llmconnect.model.{
  CompletionOptions,
  Conversation,
  ReasoningEffort,
  RequestAdjustment,
  RequestOption,
  Thinking,
  UserMessage
}
import org.llm4s.model.{ ModelCapabilities, ModelRegistryService }
import org.llm4s.security.Secret
import org.llm4s.testkit.LocalProviderTestServer
import org.llm4s.types.ProviderModelTypes.{ BaseUrl, ModelName }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks

import java.nio.charset.StandardCharsets

/**
 * [[AnthropicModelRules]]: which Claude models take only adaptive thinking and no sampling parameters, by name, and
 * the requests `AnthropicClient` then sends (#2909). Nothing here runs against the live API.
 */
class AnthropicModelRulesSpec extends AnyFlatSpec with Matchers with TableDrivenPropertyChecks {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  // -------- the name rule --------

  private val adaptiveOnly = Table(
    "model",
    // Opus from 4.7
    "claude-opus-4-7",
    "claude-opus-4-8",
    "claude-opus-4-7-20260416",
    "claude-opus-4-10",
    "claude-opus-5",
    "claude-opus-5-20260101",
    "claude-opus-5-5",
    "claude-opus-10-2",
    // Sonnet from 5, Haiku from 5
    "claude-sonnet-5",
    "claude-sonnet-5-5",
    "claude-sonnet-5-5-20260901",
    "claude-haiku-5-5",
    // every Fable and Mythos
    "claude-fable-5",
    "claude-fable-5-1",
    "claude-fable-5-2",
    "claude-mythos-5-2",
    "claude-mythos-preview",
    // an alias with no version is the family's current model
    "claude-opus-latest",
    "claude-sonnet-latest",
    "claude-haiku-latest",
    "claude-fable-latest",
    // Bedrock, with a region or geography prefix, a version suffix, a path or an inference-profile ARN
    "anthropic.claude-sonnet-5-5",
    "us.anthropic.claude-sonnet-5-5",
    "eu.anthropic.claude-opus-5-5",
    "apac.anthropic.claude-haiku-5-5",
    "global.anthropic.claude-fable-5-2",
    "us-gov.anthropic.claude-opus-4-8",
    "us.anthropic.claude-sonnet-5-v1:0",
    "bedrock/us-gov-east-1/anthropic.claude-sonnet-5-5",
    "arn:aws:bedrock:us-east-1:123456789012:inference-profile/us.anthropic.claude-sonnet-5-5",
    // Vertex AI, routers and Foundry
    "vertex_ai/claude-sonnet-5-5@default",
    "claude-sonnet-5@20260301",
    "anthropic/claude-opus-4-8",
    "openrouter/anthropic/claude-fable-5.2",
    "anthropic/claude-sonnet-5.5",
    "azure_ai/claude-fable-5-2-fast",
    "Claude-Sonnet-5-5"
  )

  private val older = Table(
    "model",
    "claude-opus-4-6",
    "claude-opus-4-6-20260205",
    "claude-opus-4-5",
    "claude-opus-4-1-20250805",
    "claude-opus-4-20250514", // Opus 4, dated: not version 4.20
    "claude-sonnet-4-6",
    "claude-sonnet-4-5-20250929",
    "claude-sonnet-4-20250514",
    "claude-haiku-4-5",
    "claude-haiku-4-5-20251001",
    "claude-3-opus-20240229", // the version before the family
    "claude-3-7-sonnet-latest",
    "claude-3-5-haiku-20241022",
    "anthropic.claude-haiku-4-5-20251001-v1:0",
    "us.anthropic.claude-sonnet-4-6",
    "anthropic.claude-haiku-4-5@20251001",
    "claude-opus-4-5@20251101",
    "openrouter/anthropic/claude-sonnet-4.6",
    "gpt-5.6-luna",
    "amazon.titan-text-express-v1"
  )

  "AnthropicModelRules.adaptiveOnly" should "be true for the models that take no sampling parameter and no budget" in {
    forAll(adaptiveOnly)(model => AnthropicModelRules.adaptiveOnly(model) shouldBe true)
  }

  it should "be false for Opus 4.6, Sonnet 4.6, Haiku 4.5, earlier models and other vendors' models" in {
    forAll(older)(model => AnthropicModelRules.adaptiveOnly(model) shouldBe false)
  }

  "AnthropicModelRules.adjust" should "rule out the sampling parameters and the budget where the registry is silent" in {
    val found = ModelCapabilities().withDisallowedParams(Set("logprobs"))
    val caps  = AnthropicModelRules.adjust("claude-sonnet-5-5", found)
    caps.supportsSamplingParams shouldBe Some(false)
    caps.supportsBudgetThinking shouldBe Some(false)
    caps.supportsAdaptiveThinking shouldBe Some(true)
    caps.supportsOutputConfig shouldBe Some(true)
    caps.disallowedParams shouldBe Some(Set("logprobs"))
    AnthropicModelRules.adjust("claude-sonnet-4-6", found) shouldBe found
  }

  it should "leave what the registry states as it is: the registry wins over the name" in {
    val stated = ModelCapabilities()
      .withSupportsSamplingParams(true)
      .withSupportsBudgetThinking(true)
      .withSupportsAdaptiveThinking(false)
      .withSupportsOutputConfig(false)
      .withSupportsThinkingOff(true)
    AnthropicModelRules.adjust("claude-sonnet-5-5", stated) shouldBe stated
  }

  // -------- the bundled catalog and the name rule agree --------

  /** Every Claude entry of the bundled catalog on a route that speaks Anthropic's request rules. */
  private def catalog: List[(String, ModelCapabilities)] = {
    val routes =
      Set("anthropic", "bedrock", "bedrock_converse", "bedrock_mantle", "vertex_ai-anthropic_models", "azure_ai")
    summon[ModelRegistryService]
      .search("claude")
      .filter(m => routes.contains(m.provider.asString))
      .map(m => (m.modelId.asString, m.capabilities))
  }

  "The bundled catalog" should "flag no Claude model as rejecting sampling parameters that the name rule would send them to" in {
    val flagged = catalog.collect { case (id, caps) if caps.supportsSamplingParams.contains(false) => id }
    flagged.size should be > 100
    (flagged should contain).allOf("claude-sonnet-5-5", "claude-haiku-5-5", "claude-opus-4-7", "claude-fable-5-2")
    flagged.filterNot(AnthropicModelRules.adaptiveOnly) shouldBe empty
  }

  it should "leave unflagged every Claude model the name rule sends them to, an alias with no version aside" in {
    val unflagged = catalog.collect { case (id, caps) if caps.supportsSamplingParams.isEmpty => id }
    (unflagged should contain).allOf("claude-opus-4-6", "claude-sonnet-4-6", "claude-haiku-4-5")
    unflagged.filter(id =>
      AnthropicModelRules.generation(id).exists(c => c.adaptiveOnly && c.version.isDefined)
    ) shouldBe
      empty
  }

  it should "mark no model the name rule calls adaptive-only as taking a thinking budget" in {
    catalog.collect {
      case (id, caps) if AnthropicModelRules.adaptiveOnly(id) && caps.supportsBudgetThinking.contains(true) => id
    } shouldBe empty
  }

  it should "mark the models whose thinking cannot be turned off" in {
    for (model <- List("claude-sonnet-5-5", "claude-opus-5-5", "claude-fable-5-2", "claude-mythos-5-1"))
      withClue(model)(AnthropicModelRules.adjust(model, ModelCapabilities()).supportsThinkingOff shouldBe Some(false))
    for (model <- List("claude-sonnet-5", "claude-opus-5", "claude-opus-4-8", "claude-haiku-5-5"))
      withClue(model)(AnthropicModelRules.adjust(model, ModelCapabilities()).supportsThinkingOff shouldBe None)
  }

  it should "leave an unknown model unknown unless its name places it" in {
    AnthropicModelRules.capabilities("some-model", None) shouldBe None
    AnthropicModelRules.capabilities("claude-sonnet-5-5-20260901", None).flatMap(_.supportsBudgetThinking) shouldBe
      Some(false)
  }

  // -------- the request on the wire --------

  private def okReply = LocalProviderTestServer.anthropicMessage("ok")

  private def sse(events: (String, String)*): String =
    events.map { case (event, data) => s"event: $event\ndata: $data\n\n" }.mkString

  private def okStream = sse(
    "message_start" -> (
      """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","content":[],""" +
        """"model":"claude-test","stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":9,"output_tokens":0}}}"""
    ),
    "content_block_start" -> """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
    "content_block_delta" -> """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}""",
    "content_block_stop"  -> """{"type":"content_block_stop","index":0}""",
    "message_delta" -> (
      """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},""" +
        """"usage":{"output_tokens":4}}"""
    ),
    "message_stop" -> """{"type":"message_stop"}"""
  )

  /** The request body sent to `model`, and the completion's adjustments. */
  private def sent(
    model: String,
    options: CompletionOptions,
    streaming: Boolean = false
  )(using ModelRegistryService): (ujson.Value, Seq[RequestAdjustment]) = {
    var seen: Option[String]                = None
    var adjustments: Seq[RequestAdjustment] = Nil
    val conversation                        = Conversation(Seq(UserMessage("hi")))
    LocalProviderTestServer.withServer("/v1/messages") { exchange =>
      seen = Some(new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8))
      if (streaming) LocalProviderTestServer.sendSseResponse(exchange, okStream)
      else LocalProviderTestServer.sendJsonResponse(exchange, 200, okReply)
    } { url =>
      val config = AnthropicConfig(
        apiKey = Secret("test-key"),
        model = ModelName(model),
        baseUrl = BaseUrl(url),
        contextWindow = 200000,
        reserveCompletion = 4096
      )
      val client = new AnthropicClient(config)
      val completion =
        if (streaming) client.streamComplete(conversation, options)(_ => ())
        else client.complete(conversation, options)
      adjustments = completion.toOption.get.adjustments
    }
    (ujson.read(seen.get), adjustments)
  }

  private def sampling = Set("temperature", "top_p", "top_k")

  /** One model per affected family, a Bedrock-prefixed id, and a snapshot the registry has never heard of. */
  private def affected = List(
    "claude-sonnet-5-5",
    "claude-sonnet-5",
    "claude-haiku-5-5",
    "claude-opus-5-5",
    "claude-opus-4-8",
    "claude-fable-5-2",
    "claude-mythos-5-2",
    "us.anthropic.claude-sonnet-5-5",
    "claude-sonnet-5-5-20260901"
  )

  "AnthropicClient" should "send no sampling parameter with default options" in {
    for {
      model     <- affected :+ "claude-sonnet-4-6"
      streaming <- List(false, true)
    }
      withClue(s"$model streaming=$streaming") {
        val (body, adjustments) = sent(model, CompletionOptions(), streaming)
        (body.obj.keySet & sampling) shouldBe empty
        body.obj.keySet should not contain "thinking"
        body.obj.keySet should not contain "output_config"
        adjustments shouldBe Nil
      }
  }

  it should "drop a temperature the caller set for a model that rejects one, and report it" in {
    for {
      model     <- affected
      streaming <- List(false, true)
    }
      withClue(s"$model streaming=$streaming") {
        val (body, adjustments) = sent(model, CompletionOptions().withTemperature(0.2), streaming)
        (body.obj.keySet & sampling) shouldBe empty
        adjustments.collect { case RequestAdjustment.Dropped(option, _) => option } shouldBe
          Seq(RequestOption.Temperature)
      }
  }

  it should "report a dropped temperature whatever its value, the model's own default included" in {
    val (body, adjustments) = sent("claude-sonnet-5-5", CompletionOptions().withTemperature(1.0))
    body.obj.keySet should not contain "temperature"
    adjustments.map(_.option) shouldBe Seq(RequestOption.Temperature)
  }

  it should "still send the temperature to a model that takes one" in {
    for {
      model     <- List("claude-sonnet-4-6", "claude-opus-4-6", "claude-haiku-4-5", "claude-3-7-sonnet-latest")
      streaming <- List(false, true)
    }
      withClue(s"$model streaming=$streaming") {
        val (body, adjustments) = sent(model, CompletionOptions().withTemperature(0.2), streaming)
        body("temperature").num shouldBe 0.2
        adjustments shouldBe Nil
      }
  }

  it should "honour a registry entry's disallowed_params for a model the name rule does not place" in {
    val patched = ModelRegistryService
      .fromJsonString(
        """{"my-claude-deployment": {"litellm_provider": "anthropic", "mode": "chat", "max_tokens": 4096,
          |"max_input_tokens": 200000, "max_output_tokens": 4096, "disallowed_params": ["temperature"]}}""".stripMargin
      )
      .toOption
      .get
    val (body, adjustments) = sent("my-claude-deployment", CompletionOptions().withTemperature(0.2))(using patched)
    body.obj.keySet should not contain "temperature"
    adjustments.map(_.option) shouldBe Seq(RequestOption.Temperature)
  }

  it should "report a top_p the caller set, which it never sends" in {
    for (model <- List("claude-sonnet-5-5", "claude-sonnet-4-6"))
      withClue(model) {
        val (body, adjustments) = sent(model, CompletionOptions().withTopP(0.9))
        (body.obj.keySet & sampling) shouldBe empty
        adjustments.collect { case RequestAdjustment.Dropped(option, _) => option } shouldBe Seq(RequestOption.TopP)
      }
  }

  it should "report a temperature it leaves out because the request thinks" in {
    val options = CompletionOptions(maxTokens = Some(4096)).withTemperature(0.2).withReasoning(ReasoningEffort.Low)
    val (body, adjustments) = sent("claude-sonnet-4-5", options)
    body("thinking") shouldBe ujson.Obj("type" -> "enabled", "budget_tokens" -> 2048)
    body.obj.keySet should not contain "temperature"
    adjustments.collect { case RequestAdjustment.Dropped(option, _) => option } shouldBe Seq(RequestOption.Temperature)
  }

  // -------- thinking: no budget_tokens (#2909, finding 2) --------

  private def noBudget =
    List(
      "claude-sonnet-5-5",
      "claude-sonnet-5",
      "claude-haiku-5-5",
      "claude-opus-5-5",
      "claude-opus-5",
      "claude-mythos-5-2",
      "us.anthropic.claude-sonnet-5-5",
      "claude-sonnet-5-5-20260901"
    )

  it should "send a reasoning level as adaptive thinking at that effort, never as budget_tokens" in {
    for {
      model     <- noBudget
      streaming <- List(false, true)
    }
      withClue(s"$model streaming=$streaming") {
        val (body, adjustments) =
          sent(model, CompletionOptions(maxTokens = Some(4096)).withReasoning(ReasoningEffort.High), streaming)
        body("thinking") shouldBe ujson.Obj("type" -> "adaptive")
        body("output_config") shouldBe ujson.Obj("effort" -> "high")
        (body.obj.keySet & sampling) shouldBe empty
        adjustments.map(_.option) shouldBe Seq(RequestOption.Reasoning)
      }
  }

  it should "send an explicit budget as adaptive thinking, and report it" in {
    val budget = Thinking.budget(8000).toOption.get
    val (body, adjustments) =
      sent("claude-sonnet-5-5", CompletionOptions(maxTokens = Some(16000)).withThinking(budget))
    body("thinking") shouldBe ujson.Obj("type" -> "adaptive")
    adjustments shouldBe Seq(
      RequestAdjustment.Adjusted(
        RequestOption.Thinking,
        "Budget(8000)",
        "Adaptive",
        "model does not accept a thinking budget"
      )
    )
  }

  it should "still send budget_tokens to a model that takes one" in {
    val (body, _) =
      sent("claude-sonnet-4-5", CompletionOptions(maxTokens = Some(4096)).withReasoning(ReasoningEffort.Low))
    body("thinking") shouldBe ujson.Obj("type" -> "enabled", "budget_tokens" -> 2048)
  }

  // -------- output_config.effort on Sonnet 5.5 (#2909, finding 1) --------

  it should "send output_config.effort to Claude Sonnet 5.5 at each level, with nothing else changed" in {
    for (level <- List(ReasoningEffort.Low, ReasoningEffort.Medium, ReasoningEffort.XHigh, ReasoningEffort.Max))
      withClue(level.name) {
        val (body, _) = sent(
          "claude-sonnet-5-5",
          CompletionOptions(maxTokens = Some(16000)).withThinking(Thinking.Adaptive).withReasoning(level)
        )
        body("thinking") shouldBe ujson.Obj("type" -> "adaptive")
        body("output_config") shouldBe ujson.Obj("effort" -> level.name)
      }
  }

  it should "leave thinking to the model when asked to turn it off on a model that cannot" in {
    for (model <- List("claude-sonnet-5-5", "claude-opus-5-5"))
      withClue(model) {
        val (body, adjustments) = sent(model, CompletionOptions().withThinking(Thinking.Off))
        body.obj.keySet should not contain "thinking"
        adjustments shouldBe Seq(
          RequestAdjustment.Adjusted(
            RequestOption.Thinking,
            "Off",
            "the model's default",
            s"$model cannot turn thinking off"
          )
        )
      }
  }
}
