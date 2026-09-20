package org.llm4s.llmconnect.spi

import org.llm4s.error.ConfigurationError
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

/**
 * One embedding provider's `llm4s.embeddings.<id>` section, as read from config.
 *
 * The shape is the same for every provider, which is what lets `llm4s-core`
 * parse it without knowing which providers exist: the provider-specific part is
 * what [[EmbeddingProviderDescriptor.buildConfig]] makes of it.
 *
 * @param apiKey  `llm4s.embeddings.<id>.apiKey`.
 * @param baseUrl `llm4s.embeddings.<id>.baseUrl`.
 * @param model   `llm4s.embeddings.<id>.model`.
 */
final case class EmbeddingProviderSection(
  apiKey: Option[String] = None,
  baseUrl: Option[String] = None,
  model: Option[String] = None
)

/**
 * What an embedding provider needs from its section, and what to use when the
 * section omits it.
 *
 * The embedding counterpart of [[ProviderConfigSpec]], with one structural
 * difference worth knowing. Chat config is keyed by the user's ''instance''
 * name (`llm4s.providers.my-openai.baseUrl`), so a `reference.conf` fragment
 * cannot express a per-provider default - it does not know the instance name.
 * Embedding config is keyed by the ''provider id'' (`llm4s.embeddings.openai`),
 * so a module's own `reference.conf` can and does bind that provider's
 * environment variables.
 *
 * The division is therefore: **defaults live here, environment bindings live in
 * the module's `reference.conf`**. Before this existed the two were duplicated -
 * `reference.conf` said `baseUrl = "http://localhost:11434"` and
 * `EmbeddingsConfigLoader` said `DefaultOllamaEmbeddingBaseUrl` - and nothing
 * kept them in step.
 *
 * @param requiresApiKey the provider cannot work without a key; a missing one is an error.
 * @param defaultBaseUrl base URL used when the section omits one.
 * @param defaultModel   model used when neither `EMBEDDING_MODEL` nor the section names one.
 * @param defaultApiKey  stand-in for a provider that takes a key but does not need a real
 *                       one - Ollama running locally.
 * @param apiKeyPath     absolute config path this provider's key is read from when its own
 *                       section carries none, e.g. `"llm4s.openai.apiKey"`. OpenAI's embedding
 *                       endpoint takes the same key as its chat client, so users set it once.
 *                       This is a ''declaration'', not a read: `org.llm4s.config` resolves it
 *                       and hands the result back in the section, because reading configuration
 *                       outside that package is what the configuration boundary forbids.
 *                       Declaring it is also what makes the "missing key" error name the place
 *                       the key would actually be set.
 * @param apiKeyEnv      the environment variable this provider's key conventionally comes
 *                       from, named in the error when it is missing.
 * @param modelEnv       likewise for the model.
 */
final case class EmbeddingConfigSpec(
  requiresApiKey: Boolean = false,
  defaultBaseUrl: Option[String] = None,
  defaultModel: Option[String] = None,
  defaultApiKey: Option[String] = None,
  apiKeyPath: Option[String] = None,
  apiKeyEnv: Option[String] = None,
  modelEnv: Option[String] = None
)

object EmbeddingConfigSpec:

  /**
   * Resolves the model: the `EMBEDDING_MODEL=<id>/<model>` override first, then
   * the section, then the spec's default.
   *
   * @param modelOverride the `<model>` half of a unified `EMBEDDING_MODEL`, when one was given.
   */
  def resolveModel(
    id: ProviderId,
    section: EmbeddingProviderSection,
    modelOverride: Option[String],
    spec: EmbeddingConfigSpec
  ): Result[String] =
    nonEmpty(modelOverride)
      .orElse(nonEmpty(section.model))
      .orElse(spec.defaultModel)
      .toRight {
        val env = spec.modelEnv.fold("")(name => s" or $name")
        ConfigurationError(
          s"Missing ${id.asString} embeddings model " +
            s"(set EMBEDDING_MODEL=${id.asString}/<model>, llm4s.embeddings.${id.asString}.model$env)"
        )
      }

  /** Resolves the base URL: the section first, then the spec's default. */
  def resolveBaseUrl(id: ProviderId, section: EmbeddingProviderSection, spec: EmbeddingConfigSpec): Result[String] =
    nonEmpty(section.baseUrl)
      .orElse(spec.defaultBaseUrl)
      .toRight(
        ConfigurationError(
          s"Missing ${id.asString} embeddings base URL (llm4s.embeddings.${id.asString}.baseUrl)"
        )
      )

  /**
   * Resolves the API key: the section first, then the spec's stand-in.
   *
   * The section's `apiKey` already accounts for [[EmbeddingConfigSpec.apiKeyPath]]:
   * the loader resolves that path and fills it in before calling the descriptor,
   * so no configuration is read from here.
   *
   * A provider whose spec neither requires a key nor supplies a default gets
   * the empty string, which is what a local provider that ignores the field
   * expects.
   */
  def resolveApiKey(
    id: ProviderId,
    section: EmbeddingProviderSection,
    spec: EmbeddingConfigSpec
  ): Result[String] =
    nonEmpty(section.apiKey)
      .orElse(spec.defaultApiKey) match
      case Some(key)                    => Right(key)
      case None if !spec.requiresApiKey => Right("")
      case None                         =>
        // Name the path the key is actually read from, which for a provider with an
        // `apiKeyPath` is not its own section.
        val path = spec.apiKeyPath.getOrElse(s"llm4s.embeddings.${id.asString}.apiKey")
        val env  = spec.apiKeyEnv.fold("")(name => s" / $name")
        Left(ConfigurationError(s"Missing ${id.asString} embeddings apiKey ($path$env)"))

  private def nonEmpty(value: Option[String]): Option[String] =
    value.map(_.trim).filter(_.nonEmpty)
