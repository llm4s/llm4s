package org.llm4s.config

import org.llm4s.annotation.Stable
import org.llm4s.config.ProvidersConfigModel.{ NamedProviderConfig, ProviderId }
import org.llm4s.error.ConfigurationError
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.llmconnect.provider.{ OpenAIProvider, RequestyProvider }
import org.llm4s.types.Result

/**
 * Model lister for the OpenAI provider, using the OpenAI `/models` endpoint. The section's
 * `organization`, if set, is sent as the `OpenAI-Organization` header.
 *
 * This was `ProviderModelListers.OpenAI` until the provider moved to
 * `llm4s-openai` ([[https://github.com/llm4s/llm4s/issues/1132 #1132]]).
 */
@Stable
object OpenAIModelLister extends ProviderModelLister:
  private val delegate =
    ProviderModelListers.openAICompatible(
      ProviderId("openai"),
      OpenAIProvider.DEFAULT_BASE_URL,
      sectionHeaders = ProviderModelListers.openAIOrganizationHeader
    )

  def listModels(config: NamedProviderConfig, httpClient: Llm4sHttpClient): Result[List[DiscoveredModel]] =
    if config.auth.isDefined then
      Left(
        ConfigurationError(
          "model listing is not supported with workload identity auth for openai (the OpenAI SDK performs " +
            "the federation exchange only inside a client); " +
            "list models from a section that sets an apiKey"
        )
      )
    else delegate.listModels(config, httpClient)

/**
 * Model lister for the Requesty provider, using its OpenAI-compatible `/models` endpoint. The
 * section's `organization`, if set, is sent as the `OpenAI-Organization` header.
 *
 * This was `ProviderModelListers.Requesty` until the provider moved to
 * `llm4s-openai` ([[https://github.com/llm4s/llm4s/issues/1132 #1132]]).
 */
@Stable
object RequestyModelLister extends ProviderModelLister:
  private val delegate =
    ProviderModelListers.openAICompatible(
      ProviderId("requesty"),
      RequestyProvider.DEFAULT_BASE_URL,
      sectionHeaders = ProviderModelListers.openAIOrganizationHeader
    )

  def listModels(config: NamedProviderConfig, httpClient: Llm4sHttpClient): Result[List[DiscoveredModel]] =
    delegate.listModels(config, httpClient)
