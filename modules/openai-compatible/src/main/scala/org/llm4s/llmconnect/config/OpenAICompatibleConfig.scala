package org.llm4s.llmconnect.config

import org.llm4s.annotation.Stable
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.auth.{ TokenExchange, TokenExchangeConfig }
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
 * Prefer [[OpenAICompatibleConfig.fromValues]], which validates the values.
 * The constructor is private: build one with the companion `apply`, whose
 * defaults cover every field after `baseUrl`, and adjust it with the `with*`
 * setters. Java and Kotlin, which cannot see Scala default arguments, use
 * `OpenAICompatibleConfig.apply(model, baseUrl)` and the setters, so adding a
 * field never breaks them.
 *
 * @param model             model identifier sent in every request.
 * @param baseUrl           API base URL; requests go to `<baseUrl>/chat/completions`.
 * @param apiKey            sent as `Authorization: Bearer <key>`; `None` sends no
 *                          `Authorization` header, for servers that need none. Redacted in `toString`.
 * @param contextWindow     the model's total token capacity (prompt + completion).
 * @param reserveCompletion tokens held back from prompt history for the completion.
 * @param headers           extra headers sent on every request, e.g. a gateway's
 *                          own auth header. Values are redacted in `toString`.
 * @param streamUsage       whether a streaming request asks for token usage with
 *                          `"stream_options": {"include_usage": true}`. Servers that follow
 *                          OpenAI (vLLM, Ollama's `/v1`) stream no usage without it; turn it
 *                          off for an endpoint that rejects the field. A named section sets it
 *                          with the `streamUsage` key.
 * @param tokenExchange     workload-identity auth: the identity token is exchanged here for the bearer
 *                          token, which replaces `apiKey`; never set together with `apiKey` or an
 *                          `Authorization` entry in `headers`, and its `tokenUrl` and `baseUrl` must be
 *                          `https` (plain `http` only to a loopback host). The exchanged token goes to
 *                          `baseUrl`, a host the user chooses, so no host is allow-listed here. [[OpenAICompatibleConfig.fromValues]] checks
 *                          these, and `OpenAICompatibleClient` refuses a config built any other way that
 *                          breaks them.
 */
@Stable
final case class OpenAICompatibleConfig private (
  model: String,
  baseUrl: String,
  apiKey: Option[String],
  contextWindow: Int,
  reserveCompletion: Int,
  headers: Map[String, String],
  streamUsage: Boolean,
  tokenExchange: Option[TokenExchangeConfig]
) extends ProviderConfig:
  override def providerId: ProviderId                           = ProviderId(OpenAICompatibleConfig.ProviderIdName)
  override def endpointUrl: Option[String]                      = Some(baseUrl)
  override def withModel(model: String): OpenAICompatibleConfig = copy(model = model)

  def withBaseUrl(baseUrl: String): OpenAICompatibleConfig          = copy(baseUrl = baseUrl)
  def withApiKey(apiKey: String): OpenAICompatibleConfig            = copy(apiKey = Some(apiKey))
  def withApiKey(apiKey: Option[String]): OpenAICompatibleConfig    = copy(apiKey = apiKey)
  def withContextWindow(contextWindow: Int): OpenAICompatibleConfig = copy(contextWindow = contextWindow)
  def withReserveCompletion(reserveCompletion: Int): OpenAICompatibleConfig =
    copy(reserveCompletion = reserveCompletion)
  def withHeaders(headers: Map[String, String]): OpenAICompatibleConfig = copy(headers = headers)

  /** Adds (or replaces) one header, for Java and Kotlin callers that would otherwise build a Scala `Map`. */
  def withHeader(name: String, value: String): OpenAICompatibleConfig = copy(headers = headers.updated(name, value))
  def withStreamUsage(streamUsage: Boolean): OpenAICompatibleConfig   = copy(streamUsage = streamUsage)
  def withTokenExchange(tokenExchange: TokenExchangeConfig): OpenAICompatibleConfig =
    copy(tokenExchange = Some(tokenExchange))
  def withTokenExchange(tokenExchange: Option[TokenExchangeConfig]): OpenAICompatibleConfig =
    copy(tokenExchange = tokenExchange)
  override def toString: String =
    s"OpenAICompatibleConfig(model=$model, baseUrl=$baseUrl, apiKey=${Redaction.secretOpt(apiKey)}, " +
      s"contextWindow=$contextWindow, reserveCompletion=$reserveCompletion, " +
      s"headers=${headers.keys.map(k => s"$k -> ***").mkString("{", ", ", "}")}, streamUsage=$streamUsage, " +
      s"tokenExchange=$tokenExchange)"

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
   * Builds a config without validating it; [[fromValues]] validates. Every field after `baseUrl`
   * has a default.
   */
  def apply(
    model: String,
    baseUrl: String,
    apiKey: Option[String] = None,
    contextWindow: Int = DEFAULT_CONTEXT_WINDOW,
    reserveCompletion: Int = DEFAULT_RESERVE_COMPLETION,
    headers: Map[String, String] = Map.empty,
    streamUsage: Boolean = true,
    tokenExchange: Option[TokenExchangeConfig] = None
  ): OpenAICompatibleConfig =
    new OpenAICompatibleConfig(
      model,
      baseUrl,
      apiKey,
      contextWindow,
      reserveCompletion,
      headers,
      streamUsage,
      tokenExchange
    )

  /**
   * The model and base URL, every other field at its default: the entry point for Java and
   * Kotlin, which do not see Scala default arguments. Set the rest with the `with*` setters.
   */
  def apply(model: String, baseUrl: String): OpenAICompatibleConfig =
    apply(model, baseUrl, apiKey = None)

  /**
   * The rules every [[OpenAICompatibleConfig]] must meet, whichever way it was built: [[fromValues]]
   * applies them, and `OpenAICompatibleClient` applies them again to a config built with the
   * companion `apply` or the `with*` setters. With `tokenExchange` set:
   *
   *  - `apiKey` must be absent or blank: a config authenticates one way;
   *  - `headers` must have no `Authorization` entry, in any case: the exchanged token is the
   *    request's bearer, and a configured header would replace it - so a 401 would retry with the
   *    same stale header rather than a fresh token;
   *  - `tokenExchange.tokenUrl` must be `https`, or plain `http` to a loopback host, since the
   *    exchange carries the identity token;
   *  - `baseUrl` must be `https`, or plain `http` to a loopback host, since every request carries the
   *    exchanged token as its bearer.
   *
   * Unlike OpenAI's and Anthropic's workload identity, the hosts are not allow-listed: this provider
   * talks to an endpoint the user chooses (Databricks, a gateway, a self-hosted server), and the
   * token exchange is configured for that endpoint. The exchanged token goes to `baseUrl`, so
   * `tokenUrl` and `baseUrl` must belong to the same trusted service - check both when configuring.
   */
  private[llm4s] def validate(config: OpenAICompatibleConfig): Result[OpenAICompatibleConfig] =
    config.tokenExchange match
      case None => Right(config)
      case Some(exchange) =>
        for
          _ <- Either.cond(
            config.apiKey.forall(_.trim.isEmpty),
            (),
            ConfigurationError(
              "OpenAI-compatible config sets both apiKey and tokenExchange; use one",
              List("apiKey", "auth")
            )
          )
          _ <- Either.cond(
            !config.headers.keys.exists(_.equalsIgnoreCase("Authorization")),
            (),
            ConfigurationError(
              "an Authorization header cannot be set with tokenExchange: the request's Authorization is the " +
                "exchanged token - remove the header",
              List("headers")
            )
          )
          _ <- TokenExchange
            .requireSecureUrl(exchange.tokenUrl)
            .left
            .map(e => ConfigurationError(e.message, List("tokenExchange.tokenUrl")))
          _ <- TokenExchange
            .requireSecureUrl(config.baseUrl, "baseUrl", "the exchanged token")
            .left
            .map(e => ConfigurationError(e.message, List("baseUrl")))
        yield config

  /**
   * Builds and validates a config.
   *
   * @return `Left(ConfigurationError)` for a blank `model` or `baseUrl`, a
   *         non-positive context window, a reserve that is negative or does
   *         not leave room for a prompt, or a `tokenExchange` that [[validate]]
   *         refuses. A blank `apiKey` is treated as none.
   */
  def fromValues(
    model: String,
    baseUrl: String,
    apiKey: Option[String] = None,
    contextWindow: Option[Int] = None,
    reserveCompletion: Option[Int] = None,
    headers: Map[String, String] = Map.empty,
    streamUsage: Boolean = true,
    tokenExchange: Option[TokenExchangeConfig] = None
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
      config <- validate(
        OpenAICompatibleConfig(
          model = model.trim,
          baseUrl = baseUrl.trim.stripSuffix("/"),
          apiKey = apiKey.map(_.trim).filter(_.nonEmpty),
          contextWindow = window,
          reserveCompletion = reserve,
          headers = headers,
          streamUsage = streamUsage,
          tokenExchange = tokenExchange
        )
      )
    yield config
}
