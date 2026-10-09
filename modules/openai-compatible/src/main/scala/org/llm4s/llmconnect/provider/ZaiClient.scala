package org.llm4s.llmconnect.provider

import org.llm4s.annotation.Stable
import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.llmconnect.config.ZaiConfig
import org.llm4s.llmconnect.model.{ CompletionOptions, ReasoningEffort, ThinkingBlock }
import org.llm4s.metrics.MetricsCollector
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.{ Result, TryOps }

import java.util.concurrent.atomic.AtomicBoolean
import scala.util.Try

/**
 * LLM client for the Z.ai API.
 *
 * Z.ai uses an OpenAI-compatible `/chat/completions` endpoint with one important
 * difference: message content is always an array of typed objects
 * (`[{"type":"text","text":"..."}]`) rather than a plain string.  This applies
 * to user, system, assistant, and tool messages alike.  Sending a plain string
 * causes a rejection from the Z.ai API.
 *
 * Both non-streaming (`complete`) and streaming (`streamComplete`) are supported.
 * Tool calling follows the standard OpenAI function-calling format.
 *
 * An [[OpenAICompatibleClient]] with [[ZaiDialect]].
 *
 * @param config  Z.ai connection configuration (API key, model, base URL, context window)
 * @param metrics records per-call latency and token-usage events;
 *                use [[org.llm4s.metrics.MetricsCollector.noop]] when metrics are not needed
 * @param exchangeLogging where raw request/response exchanges are recorded, if anywhere
 */
@Stable
class ZaiClient(
  config: ZaiConfig,
  metrics: MetricsCollector = MetricsCollector.noop,
  exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled
)(using ModelRegistryService)
    extends OpenAICompatibleClient(
      OpenAICompatibleClient.Settings(
        providerName = "zai",
        displayName = "Z.ai",
        model = config.model,
        baseUrl = config.baseUrl,
        apiKey = Some(config.apiKey),
        contextWindow = config.contextWindow,
        reserveCompletion = config.reserveCompletion,
        timeouts = config.timeouts
      ),
      ZaiDialect,
      metrics,
      exchangeLogging
    )

object ZaiClient {

  def apply(
    config: ZaiConfig,
    metrics: MetricsCollector = MetricsCollector.noop
  )(using ModelRegistryService): Result[ZaiClient] =
    Try(new ZaiClient(config, metrics)).toResult

  def apply(
    config: ZaiConfig,
    metrics: MetricsCollector,
    exchangeLogging: ProviderExchangeLogging
  )(using ModelRegistryService): Result[ZaiClient] =
    Try(new ZaiClient(config, metrics, exchangeLogging)).toResult
}

/**
 * Z.ai's departures from the standard format: every message's text is sent as
 * an array of text parts, and a reply's `content` - in a completion or a
 * streamed delta - may come back either as a string or as such an array. A GLM thinking
 * model's reasoning is its `reasoning_content`, read as thinking and sent back unchanged on the
 * assistant turn, as Z.ai's thinking-mode guide asks (preserved thinking, and tool calls). A
 * request that replays it also sets `thinking.clear_thinking` to `false`: the standard endpoint
 * otherwise drops earlier turns' `reasoning_content`. `CompletionOptions.reasoning` goes out as
 * `thinking.type` or `reasoning_effort`, whichever the GLM model accepts (see [[addReasoning]]).
 */
private[llm4s] object ZaiDialect extends OpenAICompatibleDialect:
  override val headers: Seq[(String, String)] = Seq("User-Agent" -> "llm4s-coding-assistant/1.0")

  /**
   * Not sent. Z.ai's chat-completion reference has no `stream_options` parameter; it documents
   * `usage` as returned when the call ends.
   */
  override val streamUsageOption: Boolean = false

  override def encodeContent(text: String): ujson.Value =
    ujson.Arr(ujson.Obj("type" -> "text", "text" -> ujson.Str(text)))

  override def decodeContent(content: ujson.Value): Option[String] =
    content.strOpt.orElse(
      content.arrOpt.flatMap(_.headOption).flatMap(_.objOpt).flatMap(_.get("text")).flatMap(_.strOpt)
    )

  override def thinking(obj: ujson.Value): Option[String] =
    OpenAICompatibleDialect.firstString(obj, "reasoning_content").filter(_.nonEmpty)

  override def encodeThinking(message: ujson.Obj, thinking: Seq[ThinkingBlock]): Unit =
    ThinkingBlock.text(thinking).foreach(text => message("reasoning_content") = text)

  /**
   * Maps `CompletionOptions.reasoning` onto what the configured GLM model accepts, then sets
   * `thinking.clear_thinking` to `false` when an assistant turn in `body` carries
   * `reasoning_content`, keeping any other field of the `thinking` object.
   *
   * The reasoning mapping follows Z.ai's chat-completion reference and thinking-mode guide, in
   * which thinking is on by default and `reasoning_effort`, where accepted, defaults to `max`. The
   * mapping is monotonic and `High` is Z.ai's maximum, so it never reasons less than the default:
   *  - GLM-5.3 and its Flash variants always think, and reject `thinking.type: disabled`. They
   *    accept `reasoning_effort` `low`, `high` or `max` only. `None` and `Low` send `low`, the least
   *    they allow, which is Z.ai's own advice for a caller that disabled thinking; `Medium` sends
   *    `high` and `High` sends `max`. Because `None` still thinks, and thinking tokens are still
   *    billed, the first such request in the process logs a warning naming the model.
   *  - GLM-5.2 accepts `none`, `low`, `medium`, `high` and `max` (`none` skips thinking). `None`,
   *    `Low` and `Medium` send their own name and `High` sends `max`. Z.ai currently treats `low`
   *    and `medium` as `high` on this model; the requested level is still sent, so a later change
   *    on Z.ai's side takes effect without a change here.
   *  - GLM-5.1, GLM-5, GLM-4.7, GLM-4.6 and GLM-4.5 (with their variants) document no
   *    `reasoning_effort`. `None` sends `thinking.type: disabled`; the other levels send nothing and
   *    leave the model's default.
   *  - Any other model is sent nothing: `thinking` is documented only for GLM-4.5 and later.
   *
   * With `reasoning` unset nothing is sent for it, as before.
   *
   * Preserved thinking is disabled by default on Z.ai's standard endpoint (`clear_thinking`
   * defaults to `true`, removing earlier turns' `reasoning_content`) and enabled on the Coding Plan
   * endpoint; `false` is correct on both. A request that neither replays reasoning nor disables
   * thinking gets no `thinking` field.
   */
  override def addReasoning(body: ujson.Obj, model: String, options: CompletionOptions): Unit = {
    options.reasoning.foreach { effort =>
      ZaiDialect.family(model) match {
        case ZaiDialect.Family.ForcedThinking =>
          if (effort == ReasoningEffort.None) warnThinkingNotDisabled(model)
          body("reasoning_effort") = effort match {
            case ReasoningEffort.None | ReasoningEffort.Low => "low"
            case ReasoningEffort.Medium                     => "high"
            case ReasoningEffort.High                       => "max"
          }
        case ZaiDialect.Family.Effort =>
          body("reasoning_effort") = effort match {
            case ReasoningEffort.High => "max"
            case other                => other.name
          }
        case ZaiDialect.Family.Toggle =>
          if (effort == ReasoningEffort.None) mergeThinking(body, "type", ujson.Str("disabled"))
        case ZaiDialect.Family.Unknown => ()
      }
    }
    if (replaysReasoning(body)) mergeThinking(body, "clear_thinking", ujson.False)
  }

  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)

  /** Whether the GLM-5.3 `ReasoningEffort.None` warning has been logged in this process. */
  private[provider] val warnedThinkingNotDisabled = new AtomicBoolean(false)

  private def warnThinkingNotDisabled(model: String): Unit =
    if (warnedThinkingNotDisabled.compareAndSet(false, true))
      logger.warn(
        s"Z.ai model $model cannot disable thinking: ReasoningEffort.None is sent as reasoning_effort=low, " +
          "and thinking tokens are still generated and billed. Logged once per process."
      )

  /** Sets one field of the request's `thinking` object, creating it if absent and keeping its other fields. */
  private def mergeThinking(body: ujson.Obj, field: String, value: ujson.Value): Unit = {
    val thinking = body.value.get("thinking").flatMap(_.objOpt).fold(ujson.Obj())(ujson.Obj.from(_))
    thinking(field) = value
    body("thinking") = thinking
  }

  /** How a GLM model takes a reasoning setting. */
  private[provider] enum Family:
    case ForcedThinking, Effort, Toggle, Unknown

  private val ForcedThinkingModel = """glm-5\.3(?:-.*)?""".r
  private val EffortModel         = """glm-5\.2(?:-.*)?""".r
  private val ToggleModel         = """glm-(?:5|5\.1|4\.[567]v?)(?:-.*)?""".r

  /** The family of a configured model id, matched case-insensitively. */
  private[provider] def family(model: String): Family =
    model.toLowerCase match {
      case ForcedThinkingModel() => Family.ForcedThinking
      case EffortModel()         => Family.Effort
      case ToggleModel()         => Family.Toggle
      case _                     => Family.Unknown
    }

  private def replaysReasoning(body: ujson.Obj): Boolean =
    body.value
      .get("messages")
      .flatMap(_.arrOpt)
      .exists(_.exists { m =>
        m.objOpt.exists(o => o.get("role").flatMap(_.strOpt).contains("assistant") && o.contains("reasoning_content"))
      })
