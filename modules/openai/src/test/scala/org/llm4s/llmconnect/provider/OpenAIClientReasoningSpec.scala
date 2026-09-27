package org.llm4s.llmconnect.provider

import com.openai.core.ObjectMappers
import com.openai.models.chat.completions.ChatCompletionCreateParams
import org.llm4s.llmconnect.config.{ AzureConfig, ContextWindowResolver, OpenAIConfig, ProviderConfig }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, ReasoningEffort, UserMessage }
import org.llm4s.llmconnect.provider.OpenAISdkFixtures.{ completion => completionOf, transport }
import org.llm4s.model.ModelRegistryService
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicReference

/**
 * `reasoning_effort` and the reasoning-model request rules (#1132 follow-up to #1209): which
 * models get the parameter, how each [[ReasoningEffort]] maps, and what else changes for a
 * reasoning model - `max_completion_tokens`, and no sampling parameters.
 */
final class OpenAIClientReasoningSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given mrs: ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  private type Body = upickle.core.LinkedHashMap[String, ujson.Value]

  private val hello = Conversation(Seq(UserMessage("hello")))

  private val okResponse =
    """{"id":"c1","created":0,"model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"ok"}}],
      |"usage":{"prompt_tokens":10,"completion_tokens":50,"total_tokens":60,
      |"completion_tokens_details":{"reasoning_tokens":32}}}""".stripMargin

  private def openAI(model: String): OpenAIConfig =
    OpenAIConfig.fromValues(model, "sk-test", None, "https://example.invalid/v1").value

  private def azure(deployment: String, apiVersion: String = AzureConfig.DEFAULT_API_VERSION): AzureConfig =
    AzureConfig.fromValues(deployment, "https://r.openai.azure.com", "azure-key", apiVersion).value

  /** The request body `complete` sends for `config` and `options`, as the SDK would serialise it. */
  private def sentBody(config: ProviderConfig, model: String, options: CompletionOptions): Body = {
    val seen = new AtomicReference[ChatCompletionCreateParams]()
    val client =
      OpenAIClient.forTest(model, transport(complete = p => { seen.set(p); completionOf(okResponse) }), config)
    client.complete(hello, options).value
    ujson.read(ObjectMappers.jsonMapper().writeValueAsString(seen.get()._body())).obj
  }

  private def body(model: String, options: CompletionOptions): Body = sentBody(openAI(model), model, options)

  private def azureBody(deployment: String, options: CompletionOptions): Body =
    sentBody(azure(deployment), deployment, options)

  private val samplingParams = Seq("temperature", "top_p", "presence_penalty", "frequency_penalty")

  "OpenAIClient" should "map each reasoning effort to reasoning_effort for an o-series model" in {
    Seq(ReasoningEffort.Low -> "low", ReasoningEffort.Medium -> "medium", ReasoningEffort.High -> "high").foreach {
      (effort, wire) => body("o3-mini", CompletionOptions().withReasoning(effort))("reasoning_effort").str shouldBe wire
    }
  }

  it should "send no reasoning_effort for ReasoningEffort.None, leaving the model's default" in {
    body("o3-mini", CompletionOptions().withReasoning(ReasoningEffort.None)).contains("reasoning_effort") shouldBe false
    body("o3-mini", CompletionOptions()).contains("reasoning_effort") shouldBe false
  }

  it should "send reasoning_effort to the gpt-5 family, which the registry flags as reasoning models" in {
    Seq("gpt-5", "gpt-5-mini", "gpt-5.1", "o4-mini").foreach { model =>
      withClue(model) {
        body(model, CompletionOptions().withReasoning(ReasoningEffort.High))("reasoning_effort").str shouldBe "high"
      }
    }
  }

  it should "never send reasoning_effort to a model the registry knows does not reason" in {
    Seq("gpt-4o", "gpt-4o-mini", "gpt-4.1", "gpt-3.5-turbo").foreach { model =>
      withClue(model) {
        val sent = body(model, CompletionOptions(maxTokens = Some(64)).withReasoning(ReasoningEffort.High))
        sent.contains("reasoning_effort") shouldBe false
        // Nothing else changes for it either.
        sent("temperature").num shouldBe 0.7
        sent("max_tokens").num shouldBe 64
        sent.contains("max_completion_tokens") shouldBe false
      }
    }
  }

  it should "not send reasoning_effort to an unknown model on OpenAI's API" in {
    body("my-fine-tune", CompletionOptions().withReasoning(ReasoningEffort.High))
      .contains("reasoning_effort") shouldBe false
  }

  it should "recognise a reasoning model newer than the registry snapshot by its name" in {
    body("gpt-6-astra", CompletionOptions().withReasoning(ReasoningEffort.Low))("reasoning_effort").str shouldBe "low"
  }

  it should "use max_completion_tokens and send no sampling parameters to a reasoning model" in {
    val sent = body(
      "gpt-5-mini",
      CompletionOptions(
        temperature = 0.2,
        topP = 0.9,
        presencePenalty = 0.5,
        frequencyPenalty = 0.5,
        maxTokens = Some(99)
      )
    )
    sent("max_completion_tokens").num shouldBe 99
    sent.contains("max_tokens") shouldBe false
    samplingParams.foreach(p => withClue(p)(sent.contains(p) shouldBe false))
  }

  it should "keep sending sampling parameters to a non-reasoning model" in {
    val sent = body("gpt-4o", CompletionOptions(temperature = 0.2, topP = 0.9))
    sent("temperature").num shouldBe 0.2
    sent("top_p").num shouldBe 0.9
  }

  it should "send reasoning_effort but keep sampling parameters for an open-weight gpt-oss model" in {
    val sent = body("openai/gpt-oss-120b", CompletionOptions(temperature = 0.3).withReasoning(ReasoningEffort.Medium))
    sent("reasoning_effort").str shouldBe "medium"
    sent("temperature").num shouldBe 0.3
  }

  it should "resolve a Requesty-style routed model id through the registry" in {
    body("openai/o4-mini", CompletionOptions().withReasoning(ReasoningEffort.High))("reasoning_effort").str shouldBe
      "high"
    body("anthropic/claude-3-7-sonnet-latest", CompletionOptions().withReasoning(ReasoningEffort.High))
      .contains("reasoning_effort") shouldBe false
  }

  it should "report the response's reasoning tokens as thinkingTokens, within completionTokens" in {
    val client = OpenAIClient.forTest(
      "o3-mini",
      transport(complete = _ => completionOf(okResponse)),
      openAI("o3-mini")
    )
    val usage = client.complete(hello, CompletionOptions().withReasoning(ReasoningEffort.High)).value.usage.get
    usage.thinkingTokens shouldBe Some(32)
    usage.completionTokens shouldBe 50
    usage.totalTokens shouldBe 60
  }

  // Azure: the model is a deployment name, which need not name the model behind it.

  "OpenAIClient for Azure" should "send reasoning_effort to an unrecognised deployment when reasoning is asked for" in {
    val sent = azureBody("prod-reasoner", CompletionOptions(maxTokens = Some(64)).withReasoning(ReasoningEffort.High))
    sent("reasoning_effort").str shouldBe "high"
    // Asking for reasoning declares a reasoning deployment, so its other rules apply too.
    sent("max_completion_tokens").num shouldBe 64
    sent.contains("max_tokens") shouldBe false
    samplingParams.foreach(p => withClue(p)(sent.contains(p) shouldBe false))
  }

  it should "leave an unrecognised deployment's request unchanged when no reasoning is asked for" in {
    val sent = azureBody("prod-chat", CompletionOptions(maxTokens = Some(64)))
    sent.contains("reasoning_effort") shouldBe false
    sent("temperature").num shouldBe 0.7
    sent("max_tokens").num shouldBe 64
  }

  it should "not send reasoning_effort to a deployment named after a non-reasoning model" in {
    azureBody("gpt-4o", CompletionOptions().withReasoning(ReasoningEffort.High))
      .contains("reasoning_effort") shouldBe false
  }

  it should "apply reasoning-model rules to a deployment named after a reasoning model" in {
    val sent = azureBody("o4-mini", CompletionOptions(maxTokens = Some(64)))
    sent("max_completion_tokens").num shouldBe 64
    sent.contains("temperature") shouldBe false
    azureBody("o4-mini", CompletionOptions().withReasoning(ReasoningEffort.Low))("reasoning_effort").str shouldBe "low"
  }

  it should "not read Azure's gpt-35-turbo naming as a gpt-5-or-later model" in {
    val sent = azureBody("gpt-35-turbo", CompletionOptions(maxTokens = Some(64)))
    sent("max_tokens").num shouldBe 64
    sent("temperature").num shouldBe 0.7
  }

  "OpenAIReasoning.support" should "take the registry's supports_reasoning flag for OpenAI models" in {
    OpenAIReasoning.support("o3", mrs) shouldBe OpenAIReasoning.Support.Reasoning
    OpenAIReasoning.support("gpt-5.2", mrs) shouldBe OpenAIReasoning.Support.Reasoning
    OpenAIReasoning.support("gpt-4o", mrs) shouldBe OpenAIReasoning.Support.NonReasoning
    OpenAIReasoning.support("definitely-not-a-model", mrs) shouldBe OpenAIReasoning.Support.Unknown
  }

  // Fine-tunes (#1221 review): the id wraps the base model, which neither the registry nor the
  // name fallback matched, so a fine-tuned o4-mini was sent sampling parameters and max_tokens.

  it should "judge a fine-tuned model by the model it was trained from" in {
    OpenAIReasoning.support("ft:o4-mini-2025-04-16:acme:project:9AbC", mrs) shouldBe OpenAIReasoning.Support.Reasoning
    OpenAIReasoning.support("ft:gpt-5-mini-2025-08-07:acme::9AbC", mrs) shouldBe OpenAIReasoning.Support.Reasoning
    OpenAIReasoning.support("ft:gpt-4o-mini-2024-07-18:acme:support-bot:9AbC", mrs) shouldBe
      OpenAIReasoning.Support.NonReasoning
    // A checkpoint, and a Requesty-style routed fine-tune.
    OpenAIReasoning.support("ft:o4-mini-2025-04-16:acme:project:9AbC:ckpt-step-200", mrs) shouldBe
      OpenAIReasoning.Support.Reasoning
    OpenAIReasoning.support("openai/ft:o4-mini-2025-04-16:acme:project:9AbC", mrs) shouldBe
      OpenAIReasoning.Support.Reasoning
    // An Azure fine-tuned model name, and the legacy GPT-3 form.
    OpenAIReasoning.support("o4-mini-2025-04-16.ft-0e208cf33a6a466994aff31a08aba678", mrs) shouldBe
      OpenAIReasoning.Support.Reasoning
    OpenAIReasoning.support("curie:ft-acme-2023-01-01-00-00-00", mrs) should not be OpenAIReasoning.Support.Reasoning
  }

  it should "fall back to the undated base, then to the base's name, for a fine-tune the registry lacks" in {
    // A snapshot date the bundled metadata does not list: resolved as `o4-mini`.
    OpenAIReasoning.support("ft:o4-mini-2031-01-01:acme:x:9AbC", mrs) shouldBe OpenAIReasoning.Support.Reasoning
    // A base the registry does not know at all: OpenAI's naming decides.
    OpenAIReasoning.support("ft:gpt-6-astra-2031-01-01:acme:x:9AbC", mrs) shouldBe OpenAIReasoning.Support.Reasoning
  }

  it should "treat a malformed fine-tune id as unknown" in {
    Seq("ft:", "ft::acme:x", "ft", "ft:::").foreach { id =>
      withClue(id)(OpenAIReasoning.support(id, mrs) shouldBe OpenAIReasoning.Support.Unknown)
    }
  }

  "OpenAIReasoning.baseModel" should "unwrap each documented fine-tune form and leave anything else alone" in {
    OpenAIReasoning.baseModel("ft:o4-mini-2025-04-16:acme:project:9AbC") shouldBe "o4-mini-2025-04-16"
    OpenAIReasoning.baseModel("ft:gpt-4o-mini-2024-07-18:acme::9AbC:ckpt-step-2000") shouldBe "gpt-4o-mini-2024-07-18"
    OpenAIReasoning.baseModel("davinci:ft-acme:bot-2023-01-01-00-00-00") shouldBe "davinci"
    OpenAIReasoning.baseModel("gpt-4o-mini-2024-07-18.ft-0e20") shouldBe "gpt-4o-mini-2024-07-18"
    OpenAIReasoning.baseModel("openai/o4-mini") shouldBe "openai/o4-mini"
    OpenAIReasoning.baseModel("ft::acme") shouldBe "ft::acme"
  }

  "OpenAIClient for a fine-tuned reasoning model" should "send reasoning_effort and max_completion_tokens, and no sampling parameters" in {
    val sent = body(
      "ft:o4-mini-2025-04-16:acme:project:9AbC",
      CompletionOptions(maxTokens = Some(64)).withReasoning(ReasoningEffort.Medium)
    )
    sent("reasoning_effort").str shouldBe "medium"
    sent("max_completion_tokens").num shouldBe 64
    sent.contains("max_tokens") shouldBe false
    samplingParams.foreach(p => withClue(p)(sent.contains(p) shouldBe false))
  }

  it should "leave a fine-tuned gpt-4o's request as it was" in {
    val sent = body(
      "ft:gpt-4o-mini-2024-07-18:acme:support-bot:9AbC",
      CompletionOptions(maxTokens = Some(64)).withReasoning(ReasoningEffort.High)
    )
    sent.contains("reasoning_effort") shouldBe false
    sent("temperature").num shouldBe 0.7
    sent("max_tokens").num shouldBe 64
  }

  "OpenAIReasoning.namedLikeReasoningModel" should "match OpenAI's reasoning-model names only" in {
    Seq("o1", "o3-mini", "o4-mini-2025-04-16", "gpt-5", "gpt-5.6-terra", "gpt-6-astra", "openai/gpt-oss-20b", "O3")
      .foreach(m => withClue(m)(OpenAIReasoning.namedLikeReasoningModel(m) shouldBe true))
    Seq("gpt-4o", "gpt-4.1-mini", "gpt-35-turbo", "gpt-3.5-turbo", "omni-moderation", "my-o3", "gpt-50x")
      .foreach(m => withClue(m)(OpenAIReasoning.namedLikeReasoningModel(m) shouldBe false))
  }

  "OpenAIReasoning.toSdk" should "map None to no parameter and the rest to their wire values" in {
    OpenAIReasoning.toSdk(ReasoningEffort.None) shouldBe None
    OpenAIReasoning.toSdk(ReasoningEffort.Low).map(_.asString()) shouldBe Some("low")
    OpenAIReasoning.toSdk(ReasoningEffort.Medium).map(_.asString()) shouldBe Some("medium")
    OpenAIReasoning.toSdk(ReasoningEffort.High).map(_.asString()) shouldBe Some("high")
  }
}
