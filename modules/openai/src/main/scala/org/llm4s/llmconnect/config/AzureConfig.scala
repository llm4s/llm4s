package org.llm4s.llmconnect.config

import org.llm4s.annotation.Stable
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.llm4s.util.Redaction

/**
 * Configuration for Azure OpenAI deployments.
 *
 * Although Azure exposes an OpenAI-compatible API, it uses a different URL
 * structure (per-deployment endpoint) and requires an `apiVersion` query
 * parameter. [[org.llm4s.llmconnect.LLMConnect]] constructs an
 * [[org.llm4s.llmconnect.provider.OpenAIClient]] internally; this config
 * carries the Azure-specific fields that [[OpenAIConfig]] does not have.
 *
 * Prefer [[AzureConfig.fromValues]], which validates the values and resolves
 * `contextWindow` and `reserveCompletion` from the bundled model catalogue.
 * The constructor is private: build one with the companion `apply` and adjust
 * it with the `with*` setters. Java and Kotlin, which cannot see Scala default
 * arguments, use `AzureConfig.apply(endpoint, apiKey, model)` and the setters,
 * so adding a field never breaks them.
 *
 * @param endpoint      Azure OpenAI deployment endpoint URL, e.g.
 *                      `"https://my-resource.openai.azure.com/openai/deployments/my-deploy"`.
 * @param apiKey        Azure API key; redacted in `toString`.
 * @param model         Deployment name used as the model identifier.
 * @param apiVersion    Azure OpenAI API version string, e.g. `"2025-01-01-preview"`.
 * @param contextWindow Model's total token capacity (prompt + completion combined).
 * @param reserveCompletion Tokens held back from prompt history for the completion.
 * @param timeouts how long a request and a stream may take: the section's `timeouts` block. An absent
 *                 value keeps the client's own default ([[ProviderTimeouts]])
 */
@Stable
final case class AzureConfig private (
  endpoint: String,
  apiKey: String,
  model: String,
  apiVersion: String,
  contextWindow: Int,
  reserveCompletion: Int,
  override val timeouts: ProviderTimeouts
) extends ProviderConfig:
  override val providerId: ProviderId                                = ProviderId("azure")
  override def endpointUrl: Option[String]                           = Some(endpoint)
  override def withModel(model: String): AzureConfig                 = copy(model = model)
  override def withTimeouts(timeouts: ProviderTimeouts): AzureConfig = copy(timeouts = timeouts)

  def withEndpoint(endpoint: String): AzureConfig                = copy(endpoint = endpoint)
  def withApiKey(apiKey: String): AzureConfig                    = copy(apiKey = apiKey)
  def withApiVersion(apiVersion: String): AzureConfig            = copy(apiVersion = apiVersion)
  def withContextWindow(contextWindow: Int): AzureConfig         = copy(contextWindow = contextWindow)
  def withReserveCompletion(reserveCompletion: Int): AzureConfig = copy(reserveCompletion = reserveCompletion)
  override def toString: String =
    s"AzureConfig(endpoint=$endpoint, apiKey=${Redaction.secret(apiKey)}, model=$model, apiVersion=$apiVersion, " +
      s"contextWindow=$contextWindow, reserveCompletion=$reserveCompletion)"

object AzureConfig {

  /**
   * The Azure OpenAI API version used when a provider section sets no `apiVersion`.
   *
   * This was `DefaultConfig.DEFAULT_AZURE_V2025_01_01_PREVIEW` in `llm4s-core` until the
   * provider moved to `llm4s-openai` ([[https://github.com/llm4s/llm4s/issues/1132 #1132]]).
   */
  val DEFAULT_API_VERSION: String = "V2025_01_01_PREVIEW"

  private val standardReserve = 4096

  /** Builds a config without validating it; [[fromValues]] validates. `timeouts` defaults to [[ProviderTimeouts.default]]. */
  def apply(
    endpoint: String,
    apiKey: String,
    model: String,
    apiVersion: String,
    contextWindow: Int,
    reserveCompletion: Int,
    timeouts: ProviderTimeouts = ProviderTimeouts.default
  ): AzureConfig =
    new AzureConfig(endpoint, apiKey, model, apiVersion, contextWindow, reserveCompletion, timeouts)

  /**
   * The endpoint, API key and deployment name, every other field at its default: the entry point
   * for Java and Kotlin, which do not see Scala default arguments. The API version is
   * [[DEFAULT_API_VERSION]], and `contextWindow` and `reserveCompletion` come from the model name
   * alone (`gpt-4o` is 128k, an unknown model 8192); set them, and the rest, with the `with*`
   * setters, or use [[fromValues]] to consult the bundled model catalogue.
   */
  def apply(endpoint: String, apiKey: String, model: String): AzureConfig = {
    val (cw, rc) = azureFallback(model)
    apply(endpoint, apiKey, model, DEFAULT_API_VERSION, cw, rc)
  }

  private def azureFallback(modelName: String): (Int, Int) =
    modelName match {
      case name if name.contains("gpt-4o")        => (128000, standardReserve)
      case name if name.contains("gpt-4-turbo")   => (128000, standardReserve)
      case name if name.contains("gpt-4")         => (8192, standardReserve)
      case name if name.contains("gpt-3.5-turbo") => (16384, standardReserve)
      case name if name.contains("o1-")           => (128000, standardReserve)
      case _                                      => (8192, standardReserve)
    }

  /**
   * Constructs an [[AzureConfig]], resolving `contextWindow` and
   * `reserveCompletion` from the model name automatically.
   *
   * The resolver checks Azure-specific and OpenAI model catalogues in order
   * before falling back to name-pattern matching.
   *
   * @param modelName  Deployment name used as the model identifier.
   * @param endpoint   Azure deployment endpoint URL; must be non-empty.
   * @param apiKey     Azure API key; must be non-empty.
   * @param apiVersion Azure OpenAI API version string.
   */
  def fromValues(
    modelName: String,
    endpoint: String,
    apiKey: String,
    apiVersion: String
  )(using resolver: ContextWindowResolver): Result[AzureConfig] =
    for {
      _ <- ProviderConfig.nonEmpty("Azure", "endpoint", endpoint)
      _ <- ProviderConfig.nonEmpty("Azure", "apiKey", apiKey)
    } yield {
      val (cw, rc) = resolver.resolve(
        lookupProviders = Seq("azure", "openai"),
        modelName = modelName,
        defaultContextWindow = 8192,
        defaultReserve = standardReserve,
        fallbackResolver = azureFallback,
        logPrefix = "Azure "
      )
      AzureConfig(
        endpoint = endpoint,
        apiKey = apiKey,
        model = modelName,
        apiVersion = apiVersion,
        contextWindow = cw,
        reserveCompletion = rc
      )
    }
}
