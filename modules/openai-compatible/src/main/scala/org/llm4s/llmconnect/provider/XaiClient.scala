package org.llm4s.llmconnect.provider

import org.llm4s.annotation.Experimental
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.llmconnect.config.XaiConfig
import org.llm4s.llmconnect.model.{ CompletionOptions, ReasoningEffort, RequestAdjustment, RequestOption, Thinking }
import org.llm4s.metrics.MetricsCollector
import org.llm4s.model.{ EffortMapping, ModelRegistryService }
import org.llm4s.types.{ Result, TryOps }

import scala.util.Try

/**
 * xAI (Grok) client, over xAI's OpenAI-compatible Chat Completions API
 * (`https://api.x.ai/v1/chat/completions`).
 *
 * An [[OpenAICompatibleClient]] with [[XaiDialect]], so it has everything the shared client has:
 * completion, streaming (including streamed tool calls and usage), tool calling, structured output
 * and one exchange recorded per call. What the dialect adds is reasoning: a reply's
 * `reasoning_content` is read as thinking, its `reasoning_tokens` as thinking tokens, and
 * `CompletionOptions.reasoning` is sent as `reasoning_effort` to the models that take it.
 *
 * xAI calls Chat Completions its legacy endpoint; new features ship on its Responses API, which
 * this client does not speak.
 *
 * @param config          xAI configuration: API key, model, base URL and context settings.
 * @param metrics         receives per-call latency and token-usage events.
 * @param exchangeLogging where raw request/response exchanges are recorded, if anywhere.
 */
@Experimental
class XaiClient(
  config: XaiConfig,
  metrics: MetricsCollector = MetricsCollector.noop,
  exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled,
  httpClient: Llm4sHttpClient = Llm4sHttpClient.FromConfig
)(using ModelRegistryService)
    extends OpenAICompatibleClient(
      OpenAICompatibleClient.Settings(
        providerName = "xai",
        displayName = "xAI",
        model = config.model,
        baseUrl = config.baseUrl,
        credential = OpenAICompatibleClient.keyCredential(config.apiKey, config.apiKeyProvider),
        contextWindow = config.contextWindow,
        reserveCompletion = config.reserveCompletion,
        timeouts = config.timeouts,
        network = config.network
      ),
      XaiDialect,
      metrics,
      exchangeLogging,
      httpClient
    )

@Experimental
object XaiClient {

  def apply(config: XaiConfig)(using ModelRegistryService): Result[XaiClient] =
    apply(config, MetricsCollector.noop, ProviderExchangeLogging.Disabled)

  def apply(config: XaiConfig, metrics: MetricsCollector)(using ModelRegistryService): Result[XaiClient] =
    apply(config, metrics, ProviderExchangeLogging.Disabled)

  def apply(
    config: XaiConfig,
    metrics: MetricsCollector,
    exchangeLogging: ProviderExchangeLogging,
    httpClient: Llm4sHttpClient = Llm4sHttpClient.FromConfig
  )(using ModelRegistryService): Result[XaiClient] =
    OpenAICompatibleClient
      .requireKey("xAI", config.apiKey, config.apiKeyProvider)
      .flatMap(_ => Try(new XaiClient(config, metrics, exchangeLogging, httpClient)).toResult)
}

/**
 * xAI's departures from the standard format, from xAI's Chat Completions and reasoning guides.
 *
 *  - '''Thinking.''' A reasoning model that exposes its reasoning returns it as
 *    `reasoning_content`, on a completion's `message` and on a stream's deltas; it is read as
 *    thinking. Grok 4.5 and later keep their reasoning encrypted and return it only on the
 *    Responses API, so on Chat Completions their replies carry no thinking text.
 *  - '''Reasoning tokens''' are read from `usage.completion_tokens_details.reasoning_tokens`.
 *    xAI counts them outside `completion_tokens` (but inside `total_tokens`), which is how
 *    `TokenUsage.thinkingTokens` is meant.
 *  - '''Thinking is not sent back.''' Chat Completions has no field for an earlier turn's
 *    reasoning, and xAI documents that it is discarded, so [[encodeThinking]] keeps the
 *    standard behaviour of dropping it.
 *  - '''Reasoning effort''' - see [[addReasoning]].
 *  - '''Penalties.''' xAI rejects `presence_penalty`, `frequency_penalty` and `stop` on reasoning
 *    models. llm4s never sends `stop`, and sends the penalties only when they are non-zero; on a
 *    reasoning model they are left out (see [[addReasoning]]) rather than failing the request.
 */
private[llm4s] object XaiDialect extends OpenAICompatibleDialect:

  override def thinking(obj: ujson.Value): Option[String] =
    OpenAICompatibleDialect.firstString(obj, "reasoning_content").filter(_.nonEmpty)

  override def reasoningTokens(usage: ujson.Value): Option[Int] =
    usage.objOpt
      .flatMap(_.get("completion_tokens_details"))
      .flatMap(_.objOpt)
      .flatMap(_.get("reasoning_tokens"))
      .flatMap(_.numOpt)
      .map(_.toInt)

  /**
   * Sends `CompletionOptions.reasoning` as `reasoning_effort` where the model takes it, and drops
   * the penalties a reasoning model rejects.
   *
   * xAI's reasoning models cannot stop reasoning; the effort sets only how much:
   *  - Grok 4.5 to 4.9 (`grok-4.5`, `grok-4.6`, `grok-4.7` and their `-latest` aliases) take
   *    `low`, `medium` and `high` (their default), and 4.6 and later also `xhigh`. Each level sends
   *    its own name; one the model lacks sends the nearest it has, reported `Adjusted`.
   *  - Grok 3 mini takes `low` and `high` only. `Minimal` and `Low` send `low`; `Medium` and above
   *    send `high`, each reported `Adjusted` where the name changes.
   *  - Any other model is sent nothing: `grok-4` and its fast variants reject the parameter, and
   *    on `grok-4.20-multi-agent` it sets the number of agents, not the depth of reasoning.
   *
   * `Thinking.Off` sends `low`, the least these models allow, reported `Adjusted`: the model still
   * reasons and its reasoning tokens are still billed. It wins over a `reasoning` also set.
   * `Thinking.Adaptive` is `NotNeeded` (the effort is all xAI takes) and `Thinking.Budget` is
   * `Dropped`. With neither set nothing is sent for them.
   */
  override def addReasoning(body: ujson.Obj, model: String, options: CompletionOptions): List[RequestAdjustment] = {
    val family = XaiDialect.family(model)
    val levels: Option[Seq[ReasoningEffort]] = family match {
      case XaiDialect.Family.Effort =>
        val base = Seq(ReasoningEffort.Low, ReasoningEffort.Medium, ReasoningEffort.High)
        Some(if (XaiDialect.takesXHigh(model)) base :+ ReasoningEffort.XHigh else base)
      case XaiDialect.Family.LowHigh => Some(Seq(ReasoningEffort.Low, ReasoningEffort.High))
      case XaiDialect.Family.Other   => None
    }
    val reasoningReport = levels match {
      case None => Nil // a reasoning effort a Family.Other model is not sent is not reported yet: a follow-up
      case Some(accepted) =>
        options.thinking match {
          case Some(Thinking.Off) =>
            body("reasoning_effort") = "low"
            RequestAdjustment.Adjusted(RequestOption.Thinking, "Off", "low", s"$model cannot stop reasoning") ::
              EffortMapping.overriddenByOff(options, "low")
          case _ =>
            options.reasoning.toList.flatMap { effort =>
              val sent =
                if (family == XaiDialect.Family.LowHigh)
                  // as before RFC 0008: Medium rounds up, to high
                  if (ReasoningEffort.values.indexOf(effort) >= ReasoningEffort.values.indexOf(ReasoningEffort.Medium))
                    "high"
                  else "low"
                else EffortMapping.nearest(effort, accepted.toSet).name
              body("reasoning_effort") = sent
              EffortMapping.level(model, effort, sent)
            }
        }
    }
    val thinkingReport = (options.thinking, levels) match {
      case (Some(Thinking.Off), Some(_)) => Nil // reported with the effort it was sent as
      case (Some(Thinking.Adaptive), Some(_)) =>
        List(RequestAdjustment.NotNeeded(RequestOption.Thinking, s"$model sets how much it reasons by its effort"))
      case (Some(_), _) =>
        List(RequestAdjustment.Dropped(RequestOption.Thinking, s"xAI takes no thinking setting for $model"))
      case (None, _) => Nil
    }
    // a reasoning model rejects the penalties, so they are left out and reported (the client logs them once)
    val penalties =
      Seq("presence_penalty" -> RequestOption.PresencePenalty, "frequency_penalty" -> RequestOption.FrequencyPenalty)
    val unsent =
      if (XaiDialect.isReasoningModel(model)) penalties.filter((field, _) => body.value.contains(field)) else Nil
    unsent.foreach((field, _) => body.value.remove(field))
    reasoningReport ++ thinkingReport ++ unsent.toList.map((field, option) =>
      RequestAdjustment.Dropped(option, s"$model is a reasoning model, which rejects $field")
    )
  }

  /** How a Grok model takes `reasoning_effort`. */
  private[provider] enum Family:
    case Effort, LowHigh, Other

  private val EffortModel  = """grok-4\.[5-9](?:-.*)?""".r
  private val XHighModel   = """grok-4\.[6-9](?:-.*)?""".r
  private val LowHighModel = """grok-3-mini(?:-.*)?""".r

  /** The family of a configured model id, matched case-insensitively. */
  private[provider] def family(model: String): Family =
    model.toLowerCase match {
      case EffortModel()  => Family.Effort
      case LowHighModel() => Family.LowHigh
      case _              => Family.Other
    }

  /** Whether a [[Family.Effort]] model also takes `xhigh`: Grok 4.6 and later. */
  private[provider] def takesXHigh(model: String): Boolean = XHighModel.matches(model.toLowerCase)

  private val ReasoningModel =
    """grok-(?:3-mini|4|4\.[5-9]|4\.20-multi-agent|4\.20(?:-.*)?-reasoning|code-fast)(?:-.*)?""".r

  /**
   * Whether `model` is one of xAI's reasoning models, which reject `presence_penalty`,
   * `frequency_penalty` and `stop`: Grok 3 mini, Grok 4 (`grok-4`, its dated, `-latest` and fast
   * variants), Grok 4.5 to 4.9, Grok 4.20's reasoning and multi-agent models, and Grok Code Fast.
   * A `-non-reasoning` variant never is.
   */
  private[provider] def isReasoningModel(model: String): Boolean = {
    val id = model.toLowerCase
    !id.contains("non-reasoning") && ReasoningModel.matches(id)
  }
