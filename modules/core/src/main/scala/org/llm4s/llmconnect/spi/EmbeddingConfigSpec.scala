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
 *                       endpoint takes the same key as its chat client, so users set it once;
 *                       declaring the path here is also what makes the "missing key" error
 *                       name the place they would actually set it.
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
   * Resolves the API key: the section first, then [[EmbeddingConfigSpec.apiKeyPath]]
   * if the provider declares one, then the spec's stand-in.
   *
   * A provider whose spec neither requires a key nor supplies a default gets
   * the empty string, which is what a local provider that ignores the field
   * expects.
   */
  def resolveApiKey(
    id: ProviderId,
    section: EmbeddingProviderSection,
    spec: EmbeddingConfigSpec,
    lookup: EmbeddingConfigLookup
  ): Result[String] =
    nonEmpty(section.apiKey)
      .orElse(spec.apiKeyPath.flatMap(lookup.string))
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

/**
 * A read-only window onto the wider `llm4s` config, for an embedding provider
 * whose credentials live outside its own section.
 *
 * OpenAI is the case: its embedding endpoint uses the same key as its chat
 * client, so `llm4s.embeddings.openai` has never carried an `apiKey` and the
 * loader reached across to `llm4s.openai.apiKey`. That reach is provider
 * knowledge, so it belongs in the provider's descriptor - which needs a way to
 * perform it without `llm4s-core` knowing why.
 */
trait EmbeddingConfigLookup:

  /**
   * The string at an absolute config path, e.g. `"llm4s.openai.apiKey"`.
   *
   * `None` for a path that is absent, or whose value is blank or not a string.
   * Reading config must not throw here: a provider asking for an optional
   * fallback should not be able to fail the whole load.
   */
  def string(path: String): Option[String]
