package org.llm4s.config

import org.llm4s.annotation.Experimental
import org.llm4s.config.ProvidersConfigModel.{ ProviderName, ProvidersConfig }
import org.llm4s.error.LLMError
import org.llm4s.http.HttpNetworkSettings
import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.llmconnect.config.{ EmbeddingProviderConfig, ProviderConfig, TracingSettings }
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.model.{ ModelRegistryConfig, ModelRegistryService }
import org.llm4s.types.Result
import pureconfig.ConfigSource

/**
 * The llm4s settings of one configuration the application supplied, from [[Llm4sConfig.from]].
 *
 * Each method reads that configuration exactly as the method of the same name on
 * [[Llm4sConfig]] reads the process-wide one: the same keys, the same validation (only the
 * section being loaded), the same credential fallback to `llm4s.credentials.<providerId>.apiKey`
 * and the same errors. Instances are immutable and independent, so an application can hold one
 * per tenant, or a test one per case, in the same JVM.
 *
 * @example
 * {{{
 * // appConfig: the com.typesafe.config.Config the application loaded, with an
 * // llm4s.providers block such as  provider = "main", main { provider = "openai", model = "gpt-4o-mini" }
 * for {
 *   settings <- Llm4sConfig.from(appConfig)
 *   registry <- settings.modelRegistryService()
 *   cfg      <- settings.defaultProvider()
 *   client   <- LLMConnect.getClient(cfg)(using registry)
 * } yield client
 * }}}
 *
 * Experimental, as [[Llm4sConfig.from]] is.
 */
@Experimental
final class Llm4sSettings private[config] (source: ConfigSource) {

  /** As [[Llm4sConfig.modelRegistryConfig]]: the `llm4s.modelRegistry` block. */
  def modelRegistryConfig(): Result[ModelRegistryConfig] =
    Llm4sConfig.modelRegistryConfig(source)

  /** As [[Llm4sConfig.modelRegistryService]]: the model registry `llm4s.modelRegistry` selects. */
  def modelRegistryService(): Result[ModelRegistryService] =
    Llm4sConfig.modelRegistryService(source)

  /** As [[Llm4sConfig.provider(name:String)*]]: the named section `llm4s.providers.<name>`. */
  def provider(name: String)(using ProviderRegistry): Result[ProviderConfig] =
    Llm4sConfig.provider(source, name)

  /** As [[Llm4sConfig.providerConfigs()*]]: every section, each section's error reported separately. */
  def providerConfigs()(using
    ProviderRegistry
  ): Result[(Map[ProviderName, LLMError], Map[ProviderName, ProviderConfig])] =
    Llm4sConfig.providerConfigs(source)

  /** As [[Llm4sConfig.providers()*]]: the whole `llm4s.providers` block, every section validated. */
  def providers()(using ProviderRegistry): Result[ProvidersConfig] =
    Llm4sConfig.providers(source)

  /** As [[Llm4sConfig.defaultProviderName()*]]: the name `llm4s.providers.provider` sets. */
  def defaultProviderName(): Result[ProviderName] =
    Llm4sConfig.defaultProviderName(source)

  /** As [[Llm4sConfig.defaultProvider()*]]: the default section; only that section is validated. */
  def defaultProvider()(using ProviderRegistry): Result[ProviderConfig] =
    Llm4sConfig.defaultProvider(source)

  /** As [[Llm4sConfig.httpNetwork()*]]: the proxy and TLS settings of the `llm4s.http` block. */
  def httpNetwork(): Result[HttpNetworkSettings] =
    Llm4sConfig.httpNetwork(source)

  /** As [[Llm4sConfig.listModels()*]]: the default provider's models. */
  def listModels()(using ProviderRegistry): Result[List[DiscoveredModel]] =
    Llm4sConfig.listModels(source)

  /** As [[Llm4sConfig.listModels(name:String)*]]: the models of the section `name`. */
  def listModels(name: String)(using ProviderRegistry): Result[List[DiscoveredModel]] =
    Llm4sConfig.listModels(source, name)

  /** As [[Llm4sConfig.apiKeySources()*]]: where each chat section's API key comes from. */
  def apiKeySources()(using ProviderRegistry): Result[Map[ProviderName, ApiKeySource]] =
    Llm4sConfig.apiKeySourcesFrom(source)

  /** As [[Llm4sConfig.tracing()*]]: the `llm4s.tracing` block. */
  def tracing(): Result[TracingSettings] =
    TracingConfigLoader.load(source)

  /** As [[Llm4sConfig.exchangeLogging()*]]: the optional `llm4s.exchangeLogging` block. */
  def exchangeLogging(): Result[ProviderExchangeLogging] =
    ProviderExchangeLoggingConfigLoader.load(source)

  /** As [[Llm4sConfig.embeddings()*]]: the embedding provider `llm4s.embeddings.model` selects. */
  def embeddings()(using ProviderRegistry): Result[(String, EmbeddingProviderConfig)] =
    Llm4sConfig.embeddings(source)

  /** As [[Llm4sConfig.textEmbeddingModel()*]]: the active embedding model and its dimensions. */
  def textEmbeddingModel()(using ProviderRegistry): Result[Llm4sConfig.TextEmbeddingModelSettings] =
    Llm4sConfig.textEmbeddingModel(source)
}
