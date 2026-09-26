package org.llm4s.llmconnect.config

import org.llm4s.error.ConfigurationError
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.llm4s.util.Redaction

/**
 * Configuration for the generic `openai-compatible` provider: any endpoint
 * that speaks the OpenAI `/chat/completions` API, such as Groq, Together,
 * Fireworks, a vLLM, LM Studio or llama.cpp server, or an internal gateway.
 *
 * Nothing is known about the model up front, so the context window and
 * completion reserve come from config, defaulting to the conservative
 * [[OpenAICompatibleConfig.DEFAULT_CONTEXT_WINDOW]] and
 * [[OpenAICompatibleConfig.DEFAULT_RESERVE_COMPLETION]]. Set them to the
 * model's real limits so context compression neither truncates early nor
 * overflows.
 *
 * Prefer [[OpenAICompatibleConfig.fromValues]], which validates the values,
 * over the primary constructor.
 *
 * @param model             model identifier sent in every request.
 * @param baseUrl           API base URL; requests go to `<baseUrl>/chat/completions`.
 * @param apiKey            sent as `Authorization: Bearer <key>`; `None` sends no
 *                          `Authorization` header, for servers that need none. Redacted in `toString`.
 * @param contextWindow     the model's total token capacity (prompt + completion).
 * @param reserveCompletion tokens held back from prompt history for the completion.
 * @param headers           extra headers sent on every request, e.g. a gateway's
 *                          own auth header. Values are redacted in `toString`.
 */
final case class OpenAICompatibleConfig(
  model: String,
  baseUrl: String,
  apiKey: Option[String] = None,
  contextWindow: Int = OpenAICompatibleConfig.DEFAULT_CONTEXT_WINDOW,
  reserveCompletion: Int = OpenAICompatibleConfig.DEFAULT_RESERVE_COMPLETION,
  headers: Map[String, String] = Map.empty
) extends ProviderConfig:
  override def providerId: ProviderId                           = ProviderId(OpenAICompatibleConfig.ProviderIdName)
  override def endpointUrl: Option[String]                      = Some(baseUrl)
  override def withModel(model: String): OpenAICompatibleConfig = copy(model = model)
  override def toString: String =
    s"OpenAICompatibleConfig(model=$model, baseUrl=$baseUrl, apiKey=${Redaction.secretOpt(apiKey)}, " +
      s"contextWindow=$contextWindow, reserveCompletion=$reserveCompletion, " +
      s"headers=${headers.keys.map(k => s"$k -> ***").mkString("{", ", ", "}")})"

object OpenAICompatibleConfig {

  /** The provider id, as written in `provider = "openai-compatible"`. */
  val ProviderIdName: String = "openai-compatible"

  /**
   * Context window used when config sets none: 8192 tokens, small enough that
   * any current chat model accepts it.
   */
  val DEFAULT_CONTEXT_WINDOW: Int = 8192

  /** Completion reserve used when config sets none. */
  val DEFAULT_RESERVE_COMPLETION: Int = 2048

  /**
   * Builds and validates a config.
   *
   * @return `Left(ConfigurationError)` for a blank `model` or `baseUrl`, a
   *         non-positive context window, or a reserve that is negative or does
   *         not leave room for a prompt. A blank `apiKey` is treated as none.
   */
  def fromValues(
    model: String,
    baseUrl: String,
    apiKey: Option[String] = None,
    contextWindow: Option[Int] = None,
    reserveCompletion: Option[Int] = None,
    headers: Map[String, String] = Map.empty
  ): Result[OpenAICompatibleConfig] =
    val window  = contextWindow.getOrElse(DEFAULT_CONTEXT_WINDOW)
    val reserve = reserveCompletion.getOrElse(math.min(DEFAULT_RESERVE_COMPLETION, window / 4))
    for
      _ <- ProviderConfig.nonEmpty("OpenAI-compatible", "model", model)
      _ <- ProviderConfig.nonEmpty("OpenAI-compatible", "baseUrl", baseUrl)
      _ <- Either.cond(
        window > 0,
        (),
        ConfigurationError(s"OpenAI-compatible contextWindow must be positive, got $window", List("contextWindow"))
      )
      _ <- Either.cond(
        reserve >= 0 && reserve < window,
        (),
        ConfigurationError(
          s"OpenAI-compatible reserveCompletion must be at least 0 and less than contextWindow ($window), got $reserve",
          List("reserveCompletion")
        )
      )
    yield OpenAICompatibleConfig(
      model = model.trim,
      baseUrl = baseUrl.trim.stripSuffix("/"),
      apiKey = apiKey.map(_.trim).filter(_.nonEmpty),
      contextWindow = window,
      reserveCompletion = reserve,
      headers = headers
    )
}
