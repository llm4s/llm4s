package org.llm4s.llmconnect.provider

import org.llm4s.config.DefaultConfig
import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.llmconnect.config.{ ContextWindowResolver, ProviderConfig, VertexAIConfig }
import org.llm4s.llmconnect.spi.{ ProviderConfigSpec, ProviderDescriptor }
import org.llm4s.llmconnect.{ LLMClient, LlmClientOptions }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

/**
 * Registration for Google Cloud Vertex AI.
 *
 * Vertex repurposes three section fields: `endpoint` is the GCP project id,
 * `organization` is the region, and `apiKey` is a path to a service-account
 * credential file (it authenticates with OAuth2, not an API key).
 */
object VertexAIProvider extends ProviderDescriptor:
  val id: ProviderId = ProviderId("vertexai")

  /** `provider = "vertex"` has always been accepted. */
  override val aliases: Set[String] = Set("vertex")

  val configSpec: ProviderConfigSpec = ProviderConfigSpec(
    requiresEndpoint = true,
    endpointDescription = "the GCP project ID that owns your Vertex AI resources"
  )

  def buildConfig(providerName: String, section: NamedProviderConfig)(using
    ContextWindowResolver
  ): Result[ProviderConfig] =
    ProviderDescriptor
      .requireField(
        providerName,
        "endpoint (GCP project ID)",
        section.endpoint,
        "llm4s.providers.<name>.endpoint"
      )
      .flatMap { projectId =>
        VertexAIConfig.fromValues(
          modelName = section.model.asString,
          projectId = projectId,
          location = section.organization.getOrElse(DefaultConfig.DEFAULT_VERTEXAI_LOCATION),
          credentialFilePath = section.apiKey.map(_.asKey)
        )
      }

  def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
    ModelRegistryService
  ): Result[LLMClient] =
    ProviderDescriptor
      .expectConfig[VertexAIConfig](id, config)
      .flatMap(VertexAIClient(_, options.metrics, options.exchangeLogging))
