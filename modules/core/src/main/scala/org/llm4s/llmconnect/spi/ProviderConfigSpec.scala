package org.llm4s.llmconnect.spi

/**
 * The shape of a provider's `llm4s.providers.<name>` section: which fields it
 * requires, and what to tell the user when one is missing.
 *
 * This replaces the per-provider `NamedProviderValidator` implementations that
 * `llm4s-core` used to hold, one object per provider in a shared file. A
 * provider now declares its own requirements, and a single generic validator
 * turns that declaration into the same error messages as before.
 *
 * == Why defaults live here rather than in HOCON ==
 * Chat-provider config is keyed by the user's ''instance'' name
 * (`llm4s.providers.my-openai.baseUrl`), so a `reference.conf` fragment shipped
 * by a provider module cannot express "the default `baseUrl` for anything whose
 * `provider = "openai"`" — it does not know the instance name. `defaultBaseUrl`
 * is therefore code, and is the single source for a provider's default endpoint.
 *
 * @param requiresApiKey       the section must carry a non-empty `apiKey`.
 * @param requiresBaseUrl      the section must carry a non-empty `baseUrl`;
 *                             set this only when there is no `defaultBaseUrl`.
 * @param requiresEndpoint     the section must carry a non-empty `endpoint`.
 * @param defaultBaseUrl       base URL used when the section omits one.
 * @param baseUrlExample       example shown when a required `baseUrl` is missing.
 * @param endpointDescription  what this provider means by `endpoint`, shown when
 *                             a required one is missing. Providers repurpose the
 *                             field (Azure: deployment name; Vertex AI: GCP project id).
 */
final case class ProviderConfigSpec(
  requiresApiKey: Boolean = false,
  requiresBaseUrl: Boolean = false,
  requiresEndpoint: Boolean = false,
  defaultBaseUrl: Option[String] = None,
  baseUrlExample: String = "e.g. https://api.example.com/",
  endpointDescription: String = "the provider endpoint"
)

object ProviderConfigSpec:

  /**
   * The common shape: an API key, and a base URL that defaults to the
   * provider's public endpoint.
   */
  def apiKeyAndDefaultBaseUrl(defaultBaseUrl: String): ProviderConfigSpec =
    ProviderConfigSpec(requiresApiKey = true, defaultBaseUrl = Some(defaultBaseUrl))
