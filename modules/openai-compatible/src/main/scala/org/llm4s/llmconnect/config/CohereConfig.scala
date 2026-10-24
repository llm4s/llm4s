package org.llm4s.llmconnect.config

import org.llm4s.annotation.Experimental
import org.llm4s.http.HttpNetworkSettings
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.llm4s.util.Redaction
import org.slf4j.LoggerFactory

/**
 * Configuration for the Cohere API.
 *
 * Prefer [[CohereConfig.fromValues]] over the companion `apply`; it resolves
 * `contextWindow` and `reserveCompletion` automatically from the model name, and
 * normalises the base URL to Cohere's OpenAI-compatibility endpoint.
 *
 * This was in `llm4s-core` until Cohere moved onto `llm4s-openai-compatible`'s shared client
 * ([[https://github.com/llm4s/llm4s/issues/1132 #1132]]); its package is unchanged. The client
 * now speaks Cohere's Compatibility API (`/compatibility/v1/chat/completions`) rather than
 * the native v2 `/v2/chat`, which is what gives it streaming, tool calling and structured
 * output ([[https://github.com/llm4s/llm4s/issues/925 #925]]).
 *
 * The constructor is private: build one with the companion `apply`, whose defaults cover every
 * field after `model`, and adjust it with the `with*` setters. Java and Kotlin, which cannot see
 * Scala default arguments, use `CohereConfig.apply(apiKey, model)` and the setters, so adding a field
 * never breaks them ([[https://github.com/llm4s/llm4s/issues/2264 #2264]]).
 *
 * @param apiKey        Cohere API key; redacted in `toString`.
 * @param model         Model identifier, e.g. `"command-a-03-2025"`.
 * @param baseUrl       The Compatibility API base; defaults to [[CohereConfig.DEFAULT_BASE_URL]].
 *                      Requests go to `<baseUrl>/chat/completions`. A native API root such as
 *                      `https://api.cohere.com` is accepted and mapped - see
 *                      [[CohereConfig.compatibilityBaseUrl]].
 * @param contextWindow Model's total token capacity (prompt + completion combined).
 * @param reserveCompletion Tokens held back from prompt history for the completion.
 * @param timeouts how long a request and a stream may take: the section's `timeouts` block. An absent
 *                 value keeps the client's own default ([[ProviderTimeouts]])
 */
@Experimental
final case class CohereConfig private (
  apiKey: String,
  model: String,
  baseUrl: String,
  contextWindow: Int,
  reserveCompletion: Int,
  override val timeouts: ProviderTimeouts,
  override val network: HttpNetworkSettings
) extends ProviderConfig:
  override val providerId: ProviderId                                  = ProviderId("cohere")
  override def endpointUrl: Option[String]                             = Some(baseUrl)
  override def withModel(model: String): CohereConfig                  = copy(model = model)
  override def withTimeouts(timeouts: ProviderTimeouts): CohereConfig  = copy(timeouts = timeouts)
  override def withNetwork(network: HttpNetworkSettings): CohereConfig = copy(network = network)

  def withApiKey(apiKey: String): CohereConfig                    = copy(apiKey = apiKey)
  def withBaseUrl(baseUrl: String): CohereConfig                  = copy(baseUrl = baseUrl)
  def withContextWindow(contextWindow: Int): CohereConfig         = copy(contextWindow = contextWindow)
  def withReserveCompletion(reserveCompletion: Int): CohereConfig = copy(reserveCompletion = reserveCompletion)
  override def toString: String =
    s"CohereConfig(apiKey=${Redaction.secret(apiKey)}, model=$model, baseUrl=$baseUrl, contextWindow=$contextWindow, " +
      s"reserveCompletion=$reserveCompletion)"

@Experimental
object CohereConfig {
  private val logger = LoggerFactory.getLogger(getClass)

  /** The path of Cohere's OpenAI-compatibility API under an API root. */
  val COMPATIBILITY_PATH: String = "/compatibility/v1"

  /**
   * Cohere's OpenAI-compatibility endpoint. This was `https://api.cohere.com`, the native API
   * root, until the client moved onto the Compatibility API (#1132).
   */
  val DEFAULT_BASE_URL: String = s"https://api.cohere.ai$COMPATIBILITY_PATH"

  /** The context window `apply` uses when none is given; [[fromValues]] resolves the model's own. */
  val DEFAULT_CONTEXT_WINDOW: Int = 128000

  /** The completion reserve `apply` uses when none is given; [[fromValues]] resolves the model's own. */
  val DEFAULT_RESERVE_COMPLETION: Int = 4096

  /**
   * Builds a config without validating it; [[fromValues]] validates and resolves the context window
   * from the model. Every field after `model` has a default.
   */
  def apply(
    apiKey: String,
    model: String,
    baseUrl: String = DEFAULT_BASE_URL,
    contextWindow: Int = DEFAULT_CONTEXT_WINDOW,
    reserveCompletion: Int = DEFAULT_RESERVE_COMPLETION,
    timeouts: ProviderTimeouts = ProviderTimeouts.default
  ): CohereConfig =
    new CohereConfig(
      apiKey,
      model,
      baseUrl,
      contextWindow,
      reserveCompletion,
      timeouts,
      network = HttpNetworkSettings.default
    )

  /**
   * The API key and model, every other field at its default: the entry point for Java and Kotlin,
   * which do not see Scala default arguments. Set the rest with the `with*` setters.
   */
  def apply(apiKey: String, model: String): CohereConfig =
    apply(apiKey, model, baseUrl = DEFAULT_BASE_URL)

  private val cohereFallback: String => (Int, Int) = _ => (DEFAULT_CONTEXT_WINDOW, DEFAULT_RESERVE_COMPLETION)

  /**
   * The Compatibility API base for a configured base URL.
   *
   * A URL already ending in `/compatibility/v1` is used as it is. Anything else is taken to be
   * a Cohere API root - which is what `baseUrl` meant before #1132, when the client appended
   * `/v2/chat` to it - and gets `/compatibility/v1` appended, after dropping a trailing native
   * version segment (`/v1` or `/v2`). So `https://api.cohere.com`, `https://api.cohere.com/v2`
   * and a proxy root that forwards to Cohere all keep working. Idempotent.
   */
  def compatibilityBaseUrl(baseUrl: String): String =
    val url = baseUrl.trim.stripSuffix("/")
    if url.endsWith(COMPATIBILITY_PATH) then url
    else url.stripSuffix("/v2").stripSuffix("/v1") + COMPATIBILITY_PATH

  /**
   * Constructs a [[CohereConfig]], resolving `contextWindow` and
   * `reserveCompletion` from the model name automatically.
   *
   * @param modelName Model identifier, e.g. `"command-a-03-2025"`.
   * @param apiKey    Cohere API key; must be non-empty.
   * @param baseUrl   Compatibility API base, or a Cohere API root; must be non-empty. Mapped
   *                  through [[compatibilityBaseUrl]], and logged when that changes it.
   *                  Defaults to [[CohereConfig.DEFAULT_BASE_URL]] when loaded via
   *                  [[org.llm4s.config.Llm4sConfig]].
   */
  def fromValues(
    modelName: String,
    apiKey: String,
    baseUrl: String
  )(using resolver: ContextWindowResolver): Result[CohereConfig] =
    for {
      _ <- ProviderConfig.nonEmpty("Cohere", "apiKey", apiKey)
      _ <- ProviderConfig.nonEmpty("Cohere", "baseUrl", baseUrl)
    } yield {
      val (cw, rc) = resolver.resolve(
        lookupProviders = Seq("cohere"),
        modelName = modelName,
        defaultContextWindow = DEFAULT_CONTEXT_WINDOW,
        defaultReserve = DEFAULT_RESERVE_COMPLETION,
        fallbackResolver = cohereFallback
      )
      val endpoint = compatibilityBaseUrl(baseUrl)
      if (endpoint != baseUrl.trim.stripSuffix("/"))
        logger.info(
          s"Cohere baseUrl $baseUrl is not a Compatibility API base; using $endpoint. " +
            s"llm4s calls Cohere through its OpenAI-compatibility API since #1132."
        )
      CohereConfig(
        apiKey = apiKey,
        model = modelName,
        baseUrl = endpoint,
        contextWindow = cw,
        reserveCompletion = rc
      )
    }
}
