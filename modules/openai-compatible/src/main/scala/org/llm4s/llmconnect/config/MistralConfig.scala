package org.llm4s.llmconnect.config

import org.llm4s.annotation.Experimental
import org.llm4s.http.HttpNetworkSettings
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.llm4s.util.Redaction

/**
 * Configuration for the Mistral AI API.
 *
 * Prefer [[MistralConfig.fromValues]] over the companion `apply`; it resolves
 * `contextWindow` and `reserveCompletion` automatically from the model name.
 *
 * This was in `llm4s-core` until Mistral moved onto `llm4s-openai-compatible`'s shared
 * client ([[https://github.com/llm4s/llm4s/issues/1132 #1132]]); its package is unchanged.
 *
 * The constructor is private: build one with the companion `apply`, whose defaults cover every
 * field after `model`, and adjust it with the `with*` setters. Java and Kotlin, which cannot see
 * Scala default arguments, use `MistralConfig.apply(apiKey, model)` and the setters, so adding a field
 * never breaks them ([[https://github.com/llm4s/llm4s/issues/2264 #2264]]).
 *
 * @param apiKey        Mistral API key; redacted in `toString`.
 * @param model         Model identifier, e.g. `"mistral-large-latest"`.
 * @param baseUrl       API root; defaults to [[MistralConfig.DEFAULT_BASE_URL]]. Requests go to
 *                      `<baseUrl>/v1/chat/completions` - see [[MistralConfig.apiBaseUrl]].
 * @param contextWindow Model's total token capacity (prompt + completion combined).
 * @param reserveCompletion Tokens held back from prompt history for the completion.
 * @param timeouts how long a request and a stream may take: the section's `timeouts` block. An absent
 *                 value keeps the client's own default ([[ProviderTimeouts]])
 */
@Experimental
final case class MistralConfig private (
  apiKey: String,
  model: String,
  baseUrl: String,
  contextWindow: Int,
  reserveCompletion: Int,
  override val timeouts: ProviderTimeouts,
  override val network: HttpNetworkSettings
) extends ProviderConfig:
  override val providerId: ProviderId                                   = ProviderId("mistral")
  override def endpointUrl: Option[String]                              = Some(baseUrl)
  override def withModel(model: String): MistralConfig                  = copy(model = model)
  override def withTimeouts(timeouts: ProviderTimeouts): MistralConfig  = copy(timeouts = timeouts)
  override def withNetwork(network: HttpNetworkSettings): MistralConfig = copy(network = network)

  def withApiKey(apiKey: String): MistralConfig                    = copy(apiKey = apiKey)
  def withBaseUrl(baseUrl: String): MistralConfig                  = copy(baseUrl = baseUrl)
  def withContextWindow(contextWindow: Int): MistralConfig         = copy(contextWindow = contextWindow)
  def withReserveCompletion(reserveCompletion: Int): MistralConfig = copy(reserveCompletion = reserveCompletion)
  override def toString: String =
    s"MistralConfig(apiKey=${Redaction.secret(apiKey)}, model=$model, baseUrl=$baseUrl, contextWindow=$contextWindow, " +
      s"reserveCompletion=$reserveCompletion)"

@Experimental
object MistralConfig:
  val DEFAULT_BASE_URL: String = "https://api.mistral.ai"

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
  ): MistralConfig =
    new MistralConfig(
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
  def apply(apiKey: String, model: String): MistralConfig =
    apply(apiKey, model, baseUrl = DEFAULT_BASE_URL)

  private val mistralFallback: String => (Int, Int) =
    _ => (DEFAULT_CONTEXT_WINDOW, DEFAULT_RESERVE_COMPLETION)

  /**
   * The versioned API base the chat client posts `/chat/completions` under: the configured
   * root plus `/v1`, as the model lister's `/v1/models` is.
   *
   * A base URL that already ends in `/v1` is used as it is, so the `https://api.mistral.ai/v1`
   * that Mistral's own OpenAI-SDK examples use works too. The old client appended `/v1`
   * unconditionally and so posted to `/v1/v1/chat/completions` for such a URL.
   */
  def apiBaseUrl(baseUrl: String): String =
    val root = baseUrl.trim.stripSuffix("/")
    if root.endsWith("/v1") then root else s"$root/v1"

  def fromValues(
    modelName: String,
    apiKey: String,
    baseUrl: String
  )(using resolver: ContextWindowResolver): Result[MistralConfig] =
    for {
      _ <- ProviderConfig.nonEmpty("Mistral", "apiKey", apiKey)
      _ <- ProviderConfig.nonEmpty("Mistral", "baseUrl", baseUrl)
    } yield {
      val (cw, rc) = resolver.resolve(
        lookupProviders = Seq("mistral"),
        modelName = modelName,
        defaultContextWindow = DEFAULT_CONTEXT_WINDOW,
        defaultReserve = DEFAULT_RESERVE_COMPLETION,
        fallbackResolver = mistralFallback
      )
      MistralConfig(
        apiKey = apiKey,
        model = modelName,
        baseUrl = baseUrl,
        contextWindow = cw,
        reserveCompletion = rc
      )
    }
