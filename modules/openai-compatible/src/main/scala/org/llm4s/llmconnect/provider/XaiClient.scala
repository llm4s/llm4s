package org.llm4s.llmconnect.provider

import org.llm4s.annotation.Experimental
import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.llmconnect.config.{ ProviderConfig, XaiConfig }
import org.llm4s.llmconnect.model.{ CompletionOptions, ReasoningEffort }
import org.llm4s.metrics.MetricsCollector
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.{ Result, TryOps }

import java.util.concurrent.atomic.AtomicBoolean
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
  exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled
)(using ModelRegistryService)
    extends OpenAICompatibleClient(
      OpenAICompatibleClient.Settings(
        providerName = "xai",
        displayName = "xAI",
        model = config.model,
        baseUrl = config.baseUrl,
        credential = OpenAICompatibleClient.Credential.Static(config.apiKey),
        contextWindow = config.contextWindow,
        reserveCompletion = config.reserveCompletion,
        timeouts = config.timeouts
      ),
      XaiDialect,
      metrics,
      exchangeLogging
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
    exchangeLogging: ProviderExchangeLogging
  )(using ModelRegistryService): Result[XaiClient] =
    ProviderConfig
      .nonEmpty("xAI", "apiKey", config.apiKey)
      .flatMap(_ => Try(new XaiClient(config, metrics, exchangeLogging)).toResult)
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
   *    `low`, `medium` and `high` (their default; 4.6 and later also `xhigh`). Each level sends
   *    its own name; `None` sends `low`, the least they allow, and logs a warning once per
   *    process, because the model still reasons and its reasoning tokens are still billed.
   *  - Grok 3 mini takes `low` and `high` only. `None` and `Low` send `low`; `Medium` and `High`
   *    send `high`.
   *  - Any other model is sent nothing: `grok-4` and its fast variants reject the parameter, and
   *    on `grok-4.20-multi-agent` it sets the number of agents, not the depth of reasoning.
   *
   * With `reasoning` unset nothing is sent for it.
   */
  override def addReasoning(body: ujson.Obj, model: String, options: CompletionOptions): Unit = {
    options.reasoning.foreach { effort =>
      XaiDialect.family(model) match {
        case XaiDialect.Family.Effort =>
          if (effort == ReasoningEffort.None) warnReasoningNotDisabled(model)
          body("reasoning_effort") = effort match {
            case ReasoningEffort.None | ReasoningEffort.Low => "low"
            case ReasoningEffort.Medium                     => "medium"
            case ReasoningEffort.High                       => "high"
          }
        case XaiDialect.Family.LowHigh =>
          body("reasoning_effort") = effort match {
            case ReasoningEffort.None | ReasoningEffort.Low    => "low"
            case ReasoningEffort.Medium | ReasoningEffort.High => "high"
          }
        case XaiDialect.Family.Other => ()
      }
    }
    if (XaiDialect.isReasoningModel(model)) {
      val dropped = Seq("presence_penalty", "frequency_penalty").filter(body.value.contains)
      if (dropped.nonEmpty) {
        dropped.foreach(body.value.remove)
        warnPenaltiesDropped(model, dropped)
      }
    }
  }

  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)

  /** Whether the `ReasoningEffort.None` warning has been logged in this process. */
  private[provider] val warnedReasoningNotDisabled = new AtomicBoolean(false)

  /** Whether the dropped-penalties warning has been logged in this process. */
  private[provider] val warnedPenaltiesDropped = new AtomicBoolean(false)

  private def warnReasoningNotDisabled(model: String): Unit =
    if (warnedReasoningNotDisabled.compareAndSet(false, true))
      logger.warn(
        s"xAI model $model cannot disable reasoning: ReasoningEffort.None is sent as reasoning_effort=low, " +
          "and reasoning tokens are still generated and billed. Logged once per process."
      )

  private def warnPenaltiesDropped(model: String, dropped: Seq[String]): Unit =
    if (warnedPenaltiesDropped.compareAndSet(false, true))
      logger.warn(
        s"xAI reasoning model $model rejects ${dropped.mkString(" and ")}; not sent. Logged once per process."
      )

  /** How a Grok model takes `reasoning_effort`. */
  private[provider] enum Family:
    case Effort, LowHigh, Other

  private val EffortModel  = """grok-4\.[5-9](?:-.*)?""".r
  private val LowHighModel = """grok-3-mini(?:-.*)?""".r

  /** The family of a configured model id, matched case-insensitively. */
  private[provider] def family(model: String): Family =
    model.toLowerCase match {
      case EffortModel()  => Family.Effort
      case LowHighModel() => Family.LowHigh
      case _              => Family.Other
    }

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
