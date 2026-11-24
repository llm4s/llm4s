package org.llm4s.llmconnect.provider

import org.llm4s.annotation.Stable
import org.llm4s.config.NamedProviderConfig
import org.llm4s.config.VertexAIAdcConfigLoader
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.llmconnect.config.{ ContextWindowResolver, ProviderConfig, VertexAIConfig }
import org.llm4s.llmconnect.spi.{
  ProviderBuildContext,
  ProviderConfigContext,
  ProviderConfigKey,
  ProviderConfigSpec,
  ProviderDescriptor,
  ProviderFeatures
}
import org.llm4s.llmconnect.LLMClient
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

/**
 * Registration for Google Cloud Vertex AI.
 *
 * A section names the GCP `project` (required) and `location` (default
 * `us-central1`), both provider-specific keys, and may set `apiKey` to a path to a
 * service-account credential file - Vertex authenticates with OAuth2, not an API key.
 * With no `apiKey` it uses Application Default Credentials. It therefore has no
 * `llm4s.credentials.vertexai` block: there is no vendor API key to share.
 *
 * {{{
 * vertex-main {
 *   provider = "vertexai"
 *   model    = "gemini-2.0-flash"
 *   project  = "my-gcp-project"
 *   location = "europe-west4"
 * }
 * }}}
 *
 * Until [[https://github.com/llm4s/llm4s/issues/1215 #1215]] the project was read
 * from `endpoint` and the location from `organization`; those names are no longer read.
 */
@Stable
object VertexAIProvider extends ProviderDescriptor:

  /** Sends its requests through `LlmClientOptions.httpClient` when one is supplied (#2341). */
  override def features: ProviderFeatures = ProviderFeatures.default.withCustomHttpClient(true)

  val id: ProviderId = ProviderId("vertexai")

  /** `provider = "vertex"` has always been accepted. */
  override val aliases: Set[String] = Set("vertex")

  /** The key naming the GCP project. */
  val ProjectKey: String = "project"

  /** The key naming the GCP region. */
  val LocationKey: String = "location"

  val configSpec: ProviderConfigSpec = ProviderConfigSpec(
    extras = Seq(
      ProviderConfigKey(
        ProjectKey,
        "the GCP project ID that owns your Vertex AI resources",
        required = true
      ),
      ProviderConfigKey(
        LocationKey,
        "the GCP region that serves the model, e.g. us-central1",
        default = Some(VertexAIConfig.DEFAULT_LOCATION)
      ),
      GeminiGrounding.GoogleSearchConfigKey
    )
  )

  def buildConfig(section: NamedProviderConfig, context: ProviderConfigContext): Result[ProviderConfig] =
    given ContextWindowResolver = context.contextWindows
    for
      projectId <- ProviderDescriptor.requireExtra(context.providerName, section, ProjectKey)
      search    <- GeminiGrounding.googleSearch(context.providerName, section)
      config <- VertexAIConfig.fromValues(
        modelName = section.model.asString,
        projectId = projectId,
        location = section.extra(LocationKey).getOrElse(VertexAIConfig.DEFAULT_LOCATION),
        credentialFilePath = section.apiKey.map(_.asKey)
      )
    yield config.withGoogleSearch(search)

  def build(config: ProviderConfig, context: ProviderBuildContext): Result[LLMClient] =
    given ModelRegistryService = context.modelRegistry
    ProviderDescriptor
      .expectConfig[VertexAIConfig](id, config)
      .flatMap(withWellKnownCredentialPath)
      .flatMap(
        VertexAIClient(
          _,
          context.metrics,
          context.exchangeLogging,
          Llm4sHttpClient.suppliedOrFromConfig(context.httpClient)
        )
      )

  /** Fills in gcloud's well-known ADC path, from the configuration edge, unless the config already has one. */
  private def withWellKnownCredentialPath(config: VertexAIConfig): Result[VertexAIConfig] =
    config.wellKnownCredentialPath match
      case Some(_) => Right(config)
      case None    => VertexAIAdcConfigLoader.load().map(config.withWellKnownCredentialPath)
