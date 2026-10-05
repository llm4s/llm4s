package org.llm4s.llmconnect.provider

import org.llm4s.annotation.Stable
import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.llmconnect.config.{ ContextWindowResolver, ProviderConfig, VertexAIConfig }
import org.llm4s.llmconnect.spi.{ ProviderConfigKey, ProviderConfigSpec, ProviderDescriptor }
import org.llm4s.llmconnect.{ LLMClient, LlmClientOptions }
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
 * from `endpoint` and the location from `organization`. Both still work as
 * deprecated aliases, with a warning, and will be removed in a later release.
 */
@Stable
object VertexAIProvider extends ProviderDescriptor:
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
        required = true,
        deprecatedAliases = Seq("endpoint")
      ),
      ProviderConfigKey(
        LocationKey,
        "the GCP region that serves the model, e.g. us-central1",
        default = Some(VertexAIConfig.DEFAULT_LOCATION),
        deprecatedAliases = Seq("organization")
      )
    )
  )

  def buildConfig(providerName: String, section: NamedProviderConfig)(using
    ContextWindowResolver
  ): Result[ProviderConfig] =
    ProviderDescriptor
      .requireExtra(providerName, section, ProjectKey)
      .flatMap { projectId =>
        VertexAIConfig.fromValues(
          modelName = section.model.asString,
          projectId = projectId,
          location = section.extra(LocationKey).getOrElse(VertexAIConfig.DEFAULT_LOCATION),
          credentialFilePath = section.apiKey.map(_.asKey)
        )
      }

  def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
    ModelRegistryService
  ): Result[LLMClient] =
    ProviderDescriptor
      .expectConfig[VertexAIConfig](id, config)
      .flatMap(VertexAIClient(_, options.metrics, options.exchangeLogging))
