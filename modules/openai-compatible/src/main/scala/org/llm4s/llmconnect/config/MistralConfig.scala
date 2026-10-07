package org.llm4s.llmconnect.config

import org.llm4s.annotation.Experimental
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.llm4s.util.Redaction

/**
 * Configuration for the Mistral AI API.
 *
 * Prefer [[MistralConfig.fromValues]] over the primary constructor; it resolves
 * `contextWindow` and `reserveCompletion` automatically from the model name.
 *
 * This was in `llm4s-core` until Mistral moved onto `llm4s-openai-compatible`'s shared
 * client ([[https://github.com/llm4s/llm4s/issues/1132 #1132]]); its package is unchanged.
 *
 * @param apiKey        Mistral API key; redacted in `toString`.
 * @param model         Model identifier, e.g. `"mistral-large-latest"`.
 * @param baseUrl       API root; defaults to [[MistralConfig.DEFAULT_BASE_URL]]. Requests go to
 *                      `<baseUrl>/v1/chat/completions` - see [[MistralConfig.apiBaseUrl]].
 * @param contextWindow Model's total token capacity (prompt + completion combined).
 * @param reserveCompletion Tokens held back from prompt history for the completion.
 */
@Experimental
case class MistralConfig(
  apiKey: String,
  model: String,
  baseUrl: String,
  contextWindow: Int,
  reserveCompletion: Int,
  override val timeouts: ProviderTimeouts = ProviderTimeouts.default
) extends ProviderConfig:
  override val providerId: ProviderId                  = ProviderId("mistral")
  override def endpointUrl: Option[String]             = Some(baseUrl)
  override def withModel(model: String): MistralConfig = copy(model = model)
  override def withTimeouts(timeouts: ProviderTimeouts): MistralConfig = copy(timeouts = timeouts)
  override def toString: String =
    s"MistralConfig(apiKey=${Redaction.secret(apiKey)}, model=$model, baseUrl=$baseUrl, contextWindow=$contextWindow, " +
      s"reserveCompletion=$reserveCompletion)"

object MistralConfig:
  val DEFAULT_BASE_URL: String = "https://api.mistral.ai"

  private val DefaultContextWindow     = 128000
  private val DefaultReserveCompletion = 4096

  private val mistralFallback: String => (Int, Int) =
    _ => (DefaultContextWindow, DefaultReserveCompletion)

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
        defaultContextWindow = DefaultContextWindow,
        defaultReserve = DefaultReserveCompletion,
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
