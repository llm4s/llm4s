package org.llm4s.llmconnect.provider

import org.llm4s.types.ProviderModelTypes.BaseUrl
import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.annotation.Stable
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.llmconnect.LlmClientOptions
import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.OpenAIConfig
import org.llm4s.llmconnect.model.{
  CompletionOptions,
  ContentPart,
  ReasoningEffort,
  RequestAdjustment,
  RequestOption,
  Thinking,
  ThinkingBlock,
  ToolCall,
  ToolCallId
}
import org.llm4s.media.{ MediaSource, MediaType }
import org.llm4s.metrics.MetricsCollector
import org.llm4s.model.{ EffortMapping, ModelRegistryService }
import org.llm4s.syntax.toResult
import org.llm4s.types.Result

import scala.util.Try

/**
 * [[org.llm4s.llmconnect.LLMClient]] implementation for the OpenRouter unified model gateway.
 *
 * Sends requests to the OpenRouter REST API using the OpenAI-compatible
 * `/chat/completions` endpoint. Accepts `OpenAIConfig` — there is no
 * separate `OpenRouterConfig`; `LLMConnect` detects OpenRouter by checking
 * whether `baseUrl` contains `"openrouter.ai"` and routes accordingly.
 *
 * An [[OpenAICompatibleClient]] with [[OpenRouterDialect]].
 *
 * == Required headers ==
 *
 * OpenRouter's usage policy requires two additional headers on every
 * request. This client sends them automatically:
 *  - `HTTP-Referer: https://github.com/llm4s/llm4s`
 *  - `X-Title: LLM4S`
 *
 * == Reasoning / extended thinking ==
 *
 * Model type is detected by substring matching on the lower-cased model name:
 *  - Names containing `"claude"` or `"anthropic"` → Anthropic-style
 *    `thinking` object (`type: "enabled"`, `budget_tokens`).
 *  - Names containing `"o1"`, `"o3"`, or `"o4"` → OpenAI-style
 *    `reasoning_effort` string parameter.
 *  - All other models → reasoning configuration is silently omitted.
 *
 * The thinking budget of an Anthropic model is at least 1024, the Anthropic API's minimum, and below
 * `CompletionOptions.maxTokens` when that is set. When it is not, the request carries no `max_tokens`, and the
 * budget is fitted below the model's maximum output tokens if the model registry states them, and otherwise below
 * [[OpenRouterThinkingBudget.UnknownModelMaxOutput]].
 *
 * == Thinking content ==
 *
 * Extended thinking text is extracted from whichever field the model
 * populates: `message.thinking`, `message.reasoning`, or
 * `choice.thinking` (checked in that order).
 *
 * When the underlying model returns signed, summarised or encrypted reasoning (Claude, Gemini,
 * OpenAI reasoning models), OpenRouter also returns it as `message.reasoning_details`, and
 * requires the whole sequence back unchanged when a conversation continues after tool calls. Each
 * item is kept as a sealed [[org.llm4s.llmconnect.model.ThinkingBlock.Opaque]] block beside the
 * reasoning text, bound to the request it answered, and sent back as `reasoning_details` with the
 * same order and fields while the conversation before it is unchanged; once it changes (pruned,
 * compressed, edited) the details are dropped and only the `reasoning` text is sent.
 *
 * @param config  `OpenAIConfig` whose `baseUrl` must contain `"openrouter.ai"`;
 *                carries the API key and model name. A config with `workloadIdentity` or a
 *                blank `apiKey` is refused: the companion's `apply` returns a `ConfigurationError`.
 * @param metrics Receives per-call latency and token-usage events.
 *                Defaults to `MetricsCollector.noop`.
 * @param exchangeLogging where raw request/response exchanges are recorded, if anywhere
 *
 * The class is private to this module: it is an [[OpenAICompatibleClient]] with this vendor's settings and
 * dialect, and the companion's factory returns it as one. Outside the module, build it with that factory, or
 * let `LLMConnect.getClient` build it from a named section.
 */
final private[provider] class OpenRouterClient(
  config: OpenAIConfig,
  metrics: MetricsCollector = MetricsCollector.noop,
  exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled,
  httpClient: Llm4sHttpClient = Llm4sHttpClient.FromConfig
)(using ModelRegistryService)
    extends OpenAICompatibleClient(
      OpenAICompatibleClient.Settings(
        providerName = "openrouter",
        displayName = "OpenRouter",
        model = config.model,
        baseUrl = config.baseUrl,
        credential = OpenRouterClient.credential(config),
        contextWindow = config.contextWindow,
        reserveCompletion = config.reserveCompletion,
        timeouts = config.timeouts,
        network = config.network
      ),
      new OpenRouterModelDialect(OpenRouterClient.maxOutputTokens(config.model)),
      metrics,
      exchangeLogging,
      httpClient
    )

@Stable
object OpenRouterClient {

  private val WorkloadIdentityRefusal: String =
    "OpenRouter does not support workloadIdentity: OpenAI workload identity mints an OpenAI credential, which " +
      "OpenRouter does not accept - set apiKey to an OpenRouter key instead"

  /**
   * The bearer credential for `config`, refusing what OpenRouter cannot use: `workloadIdentity`, whose
   * exchanged token is an OpenAI credential, and a blank `apiKey` (refused by [[OpenAICompatibleClient]]).
   * The constructor calls this, so a config passed to it directly is refused rather than its identity being
   * dropped in favour of an empty static key.
   */
  private def credential(config: OpenAIConfig): OpenAICompatibleClient.Credential =
    if (config.workloadIdentity.isDefined) throw new IllegalArgumentException(WorkloadIdentityRefusal)
    config.apiKeyProvider.fold(OpenAICompatibleClient.Credential.Static(config.apiKey))(
      OpenAICompatibleClient.Credential.Rotating(_)
    )

  /**
   * The registry's maximum output tokens for `model` on OpenRouter, `None` when it does not know the model or
   * states none: the limit a thinking budget is fitted under when the caller set no `maxTokens`.
   */
  private def maxOutputTokens(model: ModelName)(using registry: ModelRegistryService): Option[Int] =
    registry.lookup(OpenRouterDialect.ProviderId, model).toOption.flatMap(_.maxOutputTokens).filter(_ > 0)

  /** `config` unless it carries `workloadIdentity`, which OpenRouter cannot use; then the rules of `OpenAIConfig.validate`. */
  private def validate(config: OpenAIConfig): Result[OpenAIConfig] =
    Either
      .cond(
        config.workloadIdentity.isEmpty,
        config,
        ConfigurationError(WorkloadIdentityRefusal, List("workloadIdentity"))
      )
      .flatMap(OpenAIConfig.validate)

  /**
   * The OpenRouter client for `config`, an [[OpenAICompatibleClient]], or why one cannot be built: a config with `workloadIdentity` or a blank
   * `apiKey` is a `Left(ConfigurationError)`, and nothing is thrown.
   *
   * @param config  `OpenAIConfig` with the OpenRouter API key, model, and a `baseUrl` that contains `"openrouter.ai"`.
   * @param options the metrics collector, exchange logging and HTTP client of the client; none of them by default.
   *                `options.httpClient`, when set, carries every request and stays the caller's to close.
   */
  def apply(config: OpenAIConfig, options: LlmClientOptions = LlmClientOptions())(using
    ModelRegistryService
  ): Result[OpenAICompatibleClient] =
    validate(config).flatMap(valid =>
      Try(
        new OpenRouterClient(
          valid,
          options.metrics,
          options.exchangeLogging,
          Llm4sHttpClient.suppliedOrFromConfig(options.httpClient)
        )
      ).toResult
    )
}

/**
 * OpenRouter's departures from the standard format:
 *
 *  - the `HTTP-Referer` and `X-Title` headers its usage policy asks for;
 *  - an assistant turn always carries `content`, `""` or `null` when it has no text;
 *  - reasoning requested per underlying model family (see [[addReasoning]]);
 *  - thinking read from `thinking` or `reasoning` (on the message, then the
 *    choice, and on a streamed delta), and `usage.reasoning_tokens` (else the standard
 *    `completion_tokens_details` count, both inside `completion_tokens`); an assistant turn's
 *    thinking sent back as its `reasoning`, which OpenRouter documents for preserving reasoning;
 *  - `reasoning_details` read whole or streamed into opaque blocks and sent back unchanged (see
 *    [[decodeThinkingDetails]] and [[encodeThinking]]);
 *  - strict tool-call parsing: a call missing its `id` or `name`, or with
 *    unparseable arguments, fails the completion rather than being defaulted.
 *
 * @param modelMaxOutput the model registry's maximum output tokens for the client's model, `None` when it knows
 *                       none; read when a request first needs it (see [[addReasoning]])
 */
private[provider] class OpenRouterModelDialect(modelMaxOutput: => Option[Int]) extends OpenAICompatibleDialect:

  private lazy val maxOutput: Option[Int] = modelMaxOutput

  // OpenRouter's `/models` answers without a key, so it would pass a wrong one; `GET /key` describes the key sent
  // and answers 401 to a wrong one (https://openrouter.ai/docs/api/api-reference/api-keys/get-current-key, read
  // 2026-11-24)
  override def validationUrl(baseUrl: BaseUrl, model: ModelName): Option[String] = Some(s"${baseUrl.asUrl}/key")

  override val headers: Seq[(String, String)] =
    Seq("HTTP-Referer" -> "https://github.com/llm4s/llm4s", "X-Title" -> "LLM4S")

  override val alwaysSendAssistantContent: Boolean = true

  /**
   * The standard parts, and a PDF by URL or inline as OpenRouter's `file` part, whose `file_data`
   * takes a URL or a `data:` URL.
   */
  override def encodeUserPart: PartialFunction[ContentPart, ujson.Value] =
    OpenAICompatibleDialect.standardUserParts.orElse {
      case ContentPart.Document(MediaSource.Url(uri), MediaType.Pdf) if PartEncoding.isWebUrl(uri) => pdf(uri.toString)
      case ContentPart.Document(inline: MediaSource.Inline, MediaType.Pdf) =>
        pdf(PartEncoding.dataUrl(inline, MediaType.Pdf))
    }

  private def pdf(data: String): ujson.Value =
    ujson.Obj("type" -> "file", "file" -> ujson.Obj("filename" -> "document.pdf", "file_data" -> data))

  /**
   * Not sent. OpenRouter documents `stream_options.include_usage` as deprecated with no effect:
   * full usage is always included on a stream.
   */
  override val streamUsageOption: Boolean = false

  /**
   * Model type is detected by substring matching on the lower-cased model name.
   * Anthropic models receive a `thinking` object with a budget; OpenAI o1/o3/o4 models receive
   * `reasoning_effort`; all others are left unchanged (reasoning silently ignored, as before).
   *
   * For an Anthropic model the budget is `Thinking.Budget`'s, fitted (see below) and reported `Adjusted` when
   * fitted; with no `thinking`, the default budget of `reasoning`, fitted the same way.
   * `Thinking.Off` sends no thinking block and wins over a `reasoning` also set. `Thinking.Adaptive`
   * is `Dropped` (this dialect sends Anthropic models a budget only) and the budget comes from
   * `reasoning`. On any other model a `thinking` is `Dropped`.
   *
   * A budget is at least 1024 and below the request's output limit: `CompletionOptions.maxTokens` when the caller
   * set it. When they did not, the request carries no `max_tokens`, so the only limit there is to fit under is the
   * model's own maximum output: the registry's figure, or [[OpenRouterThinkingBudget.UnknownModelMaxOutput]] for
   * a model it states none for. A budget fitted under that assumed figure is reported `Adjusted`, a `reasoning`
   * level's too, with what lifts it. (Until 0.7.0 an unset `maxTokens` counted as 2048, which cut every level
   * above `Low` down to 2047.)
   */
  override def addReasoning(body: ujson.Obj, model: ModelName, options: CompletionOptions): Seq[RequestAdjustment] = {
    val modelLower       = model.asString.toLowerCase
    val isAnthropicModel = modelLower.contains("claude") || modelLower.contains("anthropic")
    val isOpenAIReasoningModel =
      modelLower.contains("o1") || modelLower.contains("o3") || modelLower.contains("o4")
    // the limit a budget must stay below, and why
    val assumedLimit = options.maxTokens.isEmpty && maxOutput.isEmpty
    val (limit, limitReason) = options.maxTokens
      .map(tokens => tokens -> s"Anthropic takes a budget of at least 1024 and below max_tokens ($tokens)")
      .orElse(
        maxOutput.map(tokens =>
          tokens -> s"Anthropic takes a budget of at least 1024 and below the model's maximum output ($tokens)"
        )
      )
      .getOrElse(
        OpenRouterThinkingBudget.UnknownModelMaxOutput -> OpenRouterThinkingBudget.unknownModelReason(model)
      )

    def fitted(budgetTokens: Int): Int = math.max(1024, math.min(budgetTokens, limit - 1))

    if (isAnthropicModel) {
      val fromReasoning = options.reasoning.map(ReasoningEffort.defaultBudgetTokens)
      options.thinking match {
        case Some(Thinking.Budget(tokens)) =>
          val sent = fitted(tokens)
          body("thinking") = ujson.Obj("type" -> "enabled", "budget_tokens" -> sent)
          if (sent == tokens) Nil
          else
            List(
              RequestAdjustment.Adjusted(
                RequestOption.Thinking,
                s"Budget($tokens)",
                s"Budget($sent)",
                limitReason
              )
            )
        case Some(Thinking.Off) => EffortMapping.overriddenByOff(options, "no thinking")
        case other =>
          fromReasoning.foreach(budget =>
            body("thinking") = ujson.Obj("type" -> "enabled", "budget_tokens" -> fitted(budget))
          )
          // a level fitted under a limit the caller set, or the model's own, is not reported, as before; one
          // fitted under a limit this client assumed is
          val assumed = fromReasoning.toList.collect {
            case budget if assumedLimit && fitted(budget) != budget =>
              RequestAdjustment.Adjusted(
                RequestOption.Reasoning,
                s"budget_tokens $budget",
                s"budget_tokens ${fitted(budget)}",
                limitReason
              )
          }
          assumed ++ other.toList.map(_ =>
            RequestAdjustment.Dropped(
              RequestOption.Thinking,
              "OpenRouter sends Anthropic models a thinking budget only"
            )
          )
      }
    } else {
      if (isOpenAIReasoningModel) options.reasoning.foreach(effort => body("reasoning_effort") = effort.name)
      // a reasoning effort this dialect does not send for some models is not reported yet: a follow-up under RFC 0008
      options.thinking.toList.map(_ =>
        RequestAdjustment.Dropped(
          RequestOption.Thinking,
          s"OpenRouter sends a thinking setting to Anthropic models only"
        )
      )
    }
  }

  override def thinking(obj: ujson.Value): Option[String] =
    OpenAICompatibleDialect.firstString(obj, "thinking", "reasoning")

  /**
   * The turn's thinking text as `reasoning`, and its `reasoning_details` - the [[org.llm4s.llmconnect.model.ThinkingBlock.Opaque ThinkingBlock.Opaque]]
   * blocks this dialect read, in order and exactly as received - when it still has them. A turn
   * unsealed since (its history or content changed) has only its text, and sends only `reasoning`.
   * Opaque blocks from any other provider are ignored.
   */
  override def encodeThinking(message: ujson.Obj, thinking: Seq[ThinkingBlock]): Unit = {
    ThinkingBlock.text(thinking).foreach(text => message("reasoning") = text)
    val details = thinking.collect { case ThinkingBlock.Opaque(OpenRouterDialect.ProviderId, data) => ujson.read(data) }
    if (details.nonEmpty) message("reasoning_details") = ujson.Arr.from(details)
  }

  override def thinkingDetails(obj: ujson.Value): Seq[ujson.Value] =
    obj.objOpt.flatMap(_.get("reasoning_details")).flatMap(_.arrOpt).toSeq.flatten.filter(_.objOpt.isDefined)

  /**
   * One [[org.llm4s.llmconnect.model.ThinkingBlock.Opaque ThinkingBlock.Opaque]] per `reasoning_details` item, holding the item's JSON with every
   * field (`type`, `text`, `summary`, `data`, `signature`, `id`, `format`, `index` and any other).
   *
   * A stream sends an item in fragments that share its `index` and `type`: the `text` and `summary`
   * fragments are concatenated, and any other field takes the latest non-null value it is sent
   * with (a signature, say, arrives on a later fragment than the text). An item with no `index`, or
   * whose `type` differs from the item before at that index, starts a new item. A non-streaming
   * reply's items, each with its own index, come through unchanged.
   */
  override def decodeThinkingDetails(details: Seq[ujson.Value]): Seq[ThinkingBlock] = {
    val items = scala.collection.mutable.ArrayBuffer.empty[ujson.Obj]
    // the position in `items` of the latest item at each index
    val byIndex = scala.collection.mutable.Map.empty[Int, Int]
    details.flatMap(_.objOpt).foreach { fragment =>
      val index = fragment.get("index").flatMap(_.numOpt).map(_.toInt)
      val kind  = fragment.get("type").flatMap(_.strOpt)
      index.flatMap(byIndex.get).filter(i => items(i).value.get("type").flatMap(_.strOpt) == kind) match {
        case Some(position) =>
          val item = items(position)
          fragment.foreach {
            case (key @ ("text" | "summary"), ujson.Str(more)) =>
              item(key) = item.value.get(key).flatMap(_.strOpt).getOrElse("") + more
            case (_, ujson.Null) => ()
            case (key, value)    => item(key) = value
          }
        case None =>
          items += ujson.Obj.from(fragment)
          index.foreach(byIndex(_) = items.length - 1)
      }
    }
    items.toSeq.map(item => ThinkingBlock.Opaque(OpenRouterDialect.ProviderId, item.render()))
  }

  /** OpenRouter's top-level `usage.reasoning_tokens`, else the standard `completion_tokens_details` count. */
  override def reasoningTokens(usage: ujson.Value): Option[Int] =
    usage.objOpt
      .flatMap(_.get("reasoning_tokens"))
      .flatMap(_.numOpt)
      .map(_.toInt)
      .orElse(OpenAICompatibleDialect.completionReasoningTokens(usage))

  /**
   * Strict about each call's shape: a call without an `id`, a `function` or its `name` is a malformed call, not one
   * filled in with `""`. Its arguments follow the shared rules (RFC 0009, Part 3): absent or blank is `{}`, anything
   * that is not a JSON object a `Left`.
   */
  override def parseToolCalls(toolCalls: ujson.Value): Result[Seq[ToolCall]] =
    Try(
      toolCalls.arr.toVector.map(call => (call("id").str, call("function")("name").str, call("function")))
    ).toEither.left
      .map(e => ToolCallRules.malformed(s"a tool call is not in OpenRouter's shape: ${e.getMessage}"))
      .flatMap(_.foldLeft[Result[Vector[ToolCall]]](Right(Vector.empty)) { case (done, (id, name, function)) =>
        for {
          calls     <- done
          arguments <- OpenAICompatibleDialect.toolArguments(name, function.obj.get("arguments"))
        } yield calls :+ ToolCall(ToolCallId(id), name, arguments)
      })

/**
 * The limit a Claude model's thinking budget is fitted under when nothing states one: the caller set no
 * `CompletionOptions.maxTokens`, so the request carries no `max_tokens`, and the model registry states no maximum
 * output for the model.
 *
 * Anthropic refuses a `budget_tokens` that is not below the request's output limit, and OpenRouter repeats the rule
 * (https://openrouter.ai/docs/use-cases/reasoning-tokens) without saying what the limit is when no `max_tokens` is
 * sent. The most it can be is the model's maximum output, so a budget at or above that risks a 400. Most of
 * OpenRouter's Claude ids are unknown to the registry, which spells them differently (`anthropic/claude-sonnet-4.5`
 * against `claude-sonnet-4-5`), so the budget of such a model is kept below the smallest maximum output of any
 * Claude model that thinks: [[UnknownModelMaxOutput]], Claude Opus 4 and 4.1's. That costs a larger model its
 * `High` (32,768), `XHigh` and `Max` levels until `maxTokens` is set or the registry knows the model, which is why
 * the fitted budget is reported `Adjusted`, saying so.
 */
private[provider] object OpenRouterThinkingBudget:

  /** The maximum output assumed for a model the registry states none for: a budget is sent below it. */
  val UnknownModelMaxOutput: Int = 32000

  /** Why a budget was fitted under [[UnknownModelMaxOutput]], and what lifts it. */
  def unknownModelReason(model: ModelName): String =
    s"The model registry states no maximum output for ${model.asString}, so the thinking budget is kept below " +
      s"$UnknownModelMaxOutput, the smallest maximum output of a Claude model that thinks: set maxTokens, or give " +
      "the model a max_output_tokens in the model registry, to send a larger one"

/**
 * [[OpenRouterModelDialect]] for no model in particular: what `OpenRouterClient` sends for a model the registry
 * does not know. The client itself uses an [[OpenRouterModelDialect]] that knows its model's maximum output.
 */
private[llm4s] object OpenRouterDialect extends OpenRouterModelDialect(None):

  /** The provider id on the [[org.llm4s.llmconnect.model.ThinkingBlock.Opaque ThinkingBlock.Opaque]] blocks that hold OpenRouter's `reasoning_details`. */
  val ProviderId: org.llm4s.types.ProviderModelTypes.ProviderId =
    org.llm4s.types.ProviderModelTypes.ProviderId("openrouter")
