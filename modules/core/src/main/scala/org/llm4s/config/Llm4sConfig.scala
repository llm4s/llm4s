// scalafix:off DisableSyntax.NoConfigFactory, DisableSyntax.NoSysEnv, DisableSyntax.NoSystemGetenv, DisableSyntax.NoPureConfigDefault
package org.llm4s.config

import org.llm4s.annotation.{ Experimental, Stable }
import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.llmconnect.config.*
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.types.Result
import org.llm4s.config.ProvidersConfigModel.{
  NamedProviderConfig,
  ProviderName,
  ProvidersConfig,
  RawNamedProviderSection
}
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.model.{ ModelRegistryConfig, ModelRegistryService }
import com.typesafe.config.{ Config, ConfigFactory }
import pureconfig.ConfigSource

import scala.util.Try

/**
 * Application-edge configuration loader for LLM4S.
 *
 * Reads runtime configuration through PureConfig's default source: JVM system
 * properties, then `application.conf`, then the `reference.conf` of every llm4s
 * module on the classpath. Environment variables are read only where a
 * `${?VAR}` substitution binds them - in a module's `reference.conf` (tracing,
 * embeddings, tools, and each provider module's vendor key) or in the
 * application's own `application.conf`. Nothing reads `LLM_MODEL`.
 * This is the single authorised entry point for configuration
 * in application and test code — never read `sys.env`, `System.getenv`, or
 * `ConfigFactory.load()` directly.
 *
 * == Provider setup ==
 * Define named providers under `llm4s.providers.<name>` and optionally set
 * `llm4s.providers.provider` to choose the default provider. Then call
 * [[defaultProvider]] or resolve a named provider directly with
 * [[provider(name)*]].
 *
 * == API keys ==
 * A client's key is its own `apiKey` when it sets one - in its chat section
 * `llm4s.providers.<name>`, embeddings block `llm4s.embeddings.<id>` or reranker
 * block - and otherwise its vendor's shared `llm4s.credentials.<providerId>.apiKey`.
 * Each provider module binds that shared key to the vendor's conventional variable
 * (`llm4s-openai` binds `OPENAI_API_KEY`), so with the variable set a section needs
 * only `provider` and `model`. A section for a second account sets its own
 * `apiKey = ${?OTHER_VAR}`. Where each key came from is logged at INFO - the path,
 * never the value - and [[apiKeySources()*]] reports it for a policy check.
 *
 * Then call [[defaultProvider]] to obtain a
 * [[org.llm4s.llmconnect.config.ProviderConfig]] ready for
 * [[org.llm4s.llmconnect.LLMConnect.getClient]]. Apps that need multiple
 * configured providers can call [[provider(name)*]] directly.
 *
 * == Which providers can be resolved ==
 * Every method that reads `llm4s.providers` takes an implicit
 * [[org.llm4s.llmconnect.spi.ProviderRegistry]], which decides what a
 * `provider = "..."` entry can resolve to. With none in scope it is
 * `ProviderRegistry.default`: everything found on the classpath through
 * `META-INF/services`. Pass one explicitly to restrict or extend that set —
 * `Llm4sConfig.defaultProvider()(using ProviderRegistry.of(MyProvider))`.
 *
 * @example
 * {{{
 * for {
 *   registry <- Llm4sConfig.modelRegistryService()
 *   cfg      <- Llm4sConfig.defaultProvider()
 *   client   <- LLMConnect.getClient(cfg)(using registry)
 *   agent    <- Agent.builder("assistant", client).build()   // llm4s-agent
 *   result   <- agent.run("Hello")
 * } yield result.answer
 * }}}
 *
 * == A config the application already has ==
 * Every method here reads the process-wide default configuration. To read a
 * `com.typesafe.config.Config` the application loaded itself - Play's
 * `Configuration.underlying`, a Pekko `ActorSystem.settings.config`, a config parsed
 * per test or per tenant - call [[from]] and use the returned [[Llm4sSettings]], which
 * has the same methods: each method here calls the one of the same name on the [[Llm4sSettings]] of the
 * process-wide configuration.
 *
 * The environment variables core's `reference.conf` binds are listed in
 * `docs/getting-started/configuration.md#environment-variables-llm4s-reads`.
 */
@Stable
object Llm4sConfig {

  /**
   * The llm4s settings of `config` instead of the process-wide `application.conf`.
   *
   * `config` takes the place of `application.conf` and nothing else changes: JVM system
   * properties still override it, every llm4s module's `reference.conf` still sits beneath it
   * (so `llm4s.credentials.<providerId>.apiKey` and each module's defaults apply), and `${?VAR}`
   * substitutions are resolved against the process environment - what `ConfigFactory.load(config)`
   * does. A config that is already loaded and resolved, such as Play's or Pekko's, is accepted
   * as is. Each call reads only its own `config`, so two settings in one JVM are independent.
   *
   * @param config the application's configuration; its `llm4s` block is read
   * @return the settings, or a [[org.llm4s.error.ConfigurationError]] when `config` cannot be
   *         resolved (a `${VAR}` substitution with nothing to substitute)
   * @example
   * {{{
   * for {
   *   settings <- Llm4sConfig.from(play.configuration.underlying)
   *   registry <- settings.modelRegistryService()
   *   cfg      <- settings.defaultProvider()
   *   client   <- LLMConnect.getClient(cfg)(using registry)
   * } yield client
   * }}}
   *
   * Experimental: it takes a Typesafe `Config`, a third-party type, so whether that is the
   * entry point to freeze is open until the API baseline (#2340, #1281). The [[Llm4sSettings]] it
   * returns is `@Stable`.
   */
  @Experimental
  def from(config: Config): Result[Llm4sSettings] =
    Try(ConfigFactory.load(config)).toEither.left
      .map(e => org.llm4s.error.ConfigurationError(s"Cannot resolve the given configuration: ${e.getMessage}"))
      .map(loaded => new Llm4sSettings(ConfigSource.fromConfig(loaded)))

  /** The process-wide configuration: what every method here without a `source` reads. */
  private val default: Llm4sSettings = new Llm4sSettings(ConfigSource.default)

  /** The settings of `source`: `llm4s-provider-testkit`, config-policy and the Spring starter read through it. */
  private[llm4s] def of(source: ConfigSource): Llm4sSettings = new Llm4sSettings(source)

  /** As [[Llm4sSettings.modelRegistryConfig]]: the `llm4s.modelRegistry` block. */
  def modelRegistryConfig(): Result[ModelRegistryConfig] = default.modelRegistryConfig()

  /** As [[Llm4sSettings.modelRegistryService]]: the model registry `llm4s.modelRegistry` selects. */
  def modelRegistryService(): Result[ModelRegistryService] = default.modelRegistryService()

  private[llm4s] def modelRegistryService(source: ConfigSource): Result[ModelRegistryService] =
    of(source).modelRegistryService()

  /**
   * Loads a named provider from `llm4s.providers.<name>`.
   *
   * Useful for applications that need to resolve multiple configured provider
   * instances, including multiple accounts for the same provider type.
   */
  def provider(name: String)(using ProviderRegistry): Result[ProviderConfig] = default.provider(name)

  /**
   * Every section of `llm4s.providers`, each loaded or failed on its own: a section with no key available, or
   * whose provider module is absent, is a `Left` under its name and does not stop the others loading.
   *
   * @return each section's config or error by name; a `Left` only when the providers block itself, or the model
   *         registry, cannot be read
   */
  def providerConfigs()(using ProviderRegistry): Result[Map[ProviderName, Result[ProviderConfig]]] =
    default.providerConfigs()

  private[llm4s] def providerConfigs(source: ConfigSource)(using
    ProviderRegistry
  ): Result[Map[ProviderName, Result[ProviderConfig]]] =
    of(source).providerConfigs()

  /** As [[Llm4sSettings.providerFromSection]], for the process-wide configuration. */
  private[llm4s] def providerFromSection(name: ProviderName, section: NamedProviderConfig)(using
    ProviderRegistry
  ): Result[ProviderConfig] =
    default.providerFromSection(name, section)

  // `private[llm4s]`, not `private[config]`: `llm4s-provider-testkit` loads a provider module's
  // section through it from a HOCON string and an injected environment. `ConfigSource` stays out
  // of every public signature.
  private[llm4s] def provider(source: ConfigSource, name: String)(using ProviderRegistry): Result[ProviderConfig] =
    of(source).provider(name)

  /** As [[Llm4sSettings.providerSection]]. */
  private[llm4s] def providerSection(source: ConfigSource, name: String)(using
    ProviderRegistry
  ): Result[NamedProviderConfig] =
    of(source).providerSection(name)

  /** As [[Llm4sSettings.providerBlock]]. */
  private[llm4s] def providerBlock(source: ConfigSource): Result[
    (Option[ProviderName], Map[ProviderName, Result[RawNamedProviderSection]])
  ] =
    of(source).providerBlock()

  /** As [[Llm4sSettings.providers]]: every section validated, for core's own specs. */
  private[llm4s] def providers(source: ConfigSource)(using ProviderRegistry): Result[ProvidersConfig] =
    of(source).providers()

  /**
   * Loads the configured default provider name from `llm4s.providers.provider`.
   *
   * Fails if no default is set or no section has that name. No section is validated.
   */
  def defaultProviderName(): Result[ProviderName] = default.defaultProviderName()

  private[llm4s] def defaultProviderName(source: ConfigSource): Result[ProviderName] =
    of(source).defaultProviderName()

  /**
   * Loads the configured default named provider as a runtime [[org.llm4s.llmconnect.config.ProviderConfig ProviderConfig]].
   *
   * Only the default section is validated: another section's problem does not fail this.
   */
  def defaultProvider()(using ProviderRegistry): Result[ProviderConfig] = default.defaultProvider()

  private[llm4s] def defaultProvider(source: ConfigSource)(using ProviderRegistry): Result[ProviderConfig] =
    of(source).defaultProvider()

  /**
   * The proxy and TLS settings of the `llm4s.http` block: what every provider client loaded through this
   * object uses, unless its section's own `http` block overrides it. Pass them to
   * `Llm4sHttpClient.create(HttpClientOptions(network))` for an HTTP client of your own - a tool, a vector
   * store - that should take the same route.
   *
   * A missing trust or key store, or a wrong password, is a `ConfigurationError` naming the key.
   */
  def httpNetwork(): Result[org.llm4s.http.HttpNetworkSettings] = default.httpNetwork()

  private[llm4s] def httpNetwork(source: ConfigSource): Result[org.llm4s.http.HttpNetworkSettings] =
    of(source).httpNetwork()

  /**
   * Lists models for the configured default named provider.
   */
  def listModels()(using ProviderRegistry): Result[List[DiscoveredModel]] = default.listModels()

  private[llm4s] def listModels(source: ConfigSource)(using ProviderRegistry): Result[List[DiscoveredModel]] =
    of(source).listModels()

  private[llm4s] def listModels(
    source: ConfigSource,
    httpClient: Llm4sHttpClient
  )(using ProviderRegistry): Result[List[DiscoveredModel]] =
    of(source).listModels(httpClient)

  /**
   * Lists models for a named provider configured under `llm4s.providers.<name>`.
   */
  def listModels(name: String)(using ProviderRegistry): Result[List[DiscoveredModel]] = default.listModels(name)

  private[llm4s] def listModels(source: ConfigSource, name: String)(using
    ProviderRegistry
  ): Result[List[DiscoveredModel]] =
    of(source).listModels(name)

  private[llm4s] def listModels(
    name: String,
    source: ConfigSource,
    httpClient: Llm4sHttpClient
  )(using ProviderRegistry): Result[List[DiscoveredModel]] =
    of(source).listModels(name, httpClient)

  /**
   * Loads the default LLM provider configuration from a custom PureConfig source.
   *
   * Useful for policy checks and validation workflows that need to evaluate a
   * specific config source (for example, file overlays in CI). The source must
   * define named providers under `llm4s.providers` with a default.
   */
  private[llm4s] def providerFrom(source: ConfigSource)(using ProviderRegistry): Result[ProviderConfig] =
    of(source).defaultProvider()

  /**
   * Where each named chat section's API key comes from: its own `apiKey`, or its vendor's
   * shared `llm4s.credentials.<providerId>.apiKey`.
   *
   * A section with no key of its own silently uses the shared one - convenient for the default
   * account, and a billing mistake for a section meant for a second account that forgot its
   * key. The config-policy `prod` preset uses this to require explicit keys. Only sections whose
   * provider requires a key are listed; no section is validated.
   */
  def apiKeySources()(using ProviderRegistry): Result[Map[ProviderName, ApiKeySource]] = default.apiKeySources()

  /** [[apiKeySources()*]] for a custom PureConfig source, as [[providerFrom]] is for the default provider. */
  private[llm4s] def apiKeySourcesFrom(source: ConfigSource)(using
    ProviderRegistry
  ): Result[Map[ProviderName, ApiKeySource]] =
    of(source).apiKeySources()

  /**
   * Loads tracing configuration from the current environment.
   *
   * Reads `TRACING_MODE` (`console`, `none`, or a backend module's mode such as
   * `langfuse` or `opentelemetry`) and hands the selected mode's
   * `llm4s.tracing.<mode>` block to its backend as `TracingSettings.extras`. The
   * backend's own module binds that block's variables (`LANGFUSE_*` in
   * `llm4s-observability`, `OTEL_*` in `llm4s-observability-otel`) and validates it
   * when `Tracing.fromSettings` builds the tracer.
   *
   * @return the tracing settings, or a [[org.llm4s.error.ConfigurationError]]
   *         when the `llm4s.tracing` tree cannot be parsed, or when the selected
   *         mode's `llm4s.tracing.<mode>` is present but is not an object (an absent
   *         block is fine: the backend applies its defaults).
   */
  def tracing(): Result[TracingSettings] = default.tracing()

  /** [[tracing()*]] for a given source; `llm4s-spring-boot-starter` reads `llm4s.tracing.*` properties through it. */
  private[llm4s] def tracing(source: ConfigSource): Result[TracingSettings] = of(source).tracing()

  /**
   * Loads provider exchange logging configuration from the current environment.
   *
   * Reads the optional `llm4s.exchangeLogging` section. When absent or disabled,
   * exchange logging remains off. When enabled, a JSONL sink is constructed
   * from a configured directory and writes to a new per-run file.
   */
  def exchangeLogging(): Result[ProviderExchangeLogging] = default.exchangeLogging()

  /**
   * The active embedding model, as [[textEmbeddingModel]] resolves it.
   *
   * @param provider   the embedding provider id, lower-cased
   * @param modelName  the provider's model name
   * @param dimensions the model's output dimensions
   */
  final case class TextEmbeddingModelSettings private (
    provider: String,
    modelName: String,
    dimensions: Int
  ) {

    /** Sets the embedding provider id. */
    def withProvider(provider: String): TextEmbeddingModelSettings = copy(provider = provider)

    /** Sets the model name. */
    def withModelName(modelName: String): TextEmbeddingModelSettings = copy(modelName = modelName)

    /** Sets the output dimensions. */
    def withDimensions(dimensions: Int): TextEmbeddingModelSettings = copy(dimensions = dimensions)
  }

  object TextEmbeddingModelSettings {

    /** Creates a [[TextEmbeddingModelSettings]]. */
    def apply(provider: String, modelName: String, dimensions: Int): TextEmbeddingModelSettings =
      new TextEmbeddingModelSettings(provider, modelName, dimensions)
  }

  /**
   * Loads the active embedding provider and its configuration from the current environment.
   *
   * Reads `EMBEDDING_MODEL` in `provider/model` format (e.g.
   * `"openai/text-embedding-3-small"`, `"voyage/voyage-3"`,
   * `"ollama/nomic-embed-text"`). Returns the provider name and typed config.
   *
   * The provider is resolved through the given
   * [[org.llm4s.llmconnect.spi.ProviderRegistry]], so an embedding provider
   * supplied by a module - or registered explicitly - is configurable here
   * without `llm4s-core` knowing it exists.
   *
   * @return a pair of `(providerName, EmbeddingProviderConfig)`, or a
   *         [[org.llm4s.error.ConfigurationError]] when `EMBEDDING_MODEL` is
   *         absent or the provider is unrecognised.
   */
  def embeddings()(using ProviderRegistry): Result[(String, EmbeddingProviderConfig)] = default.embeddings()

  /** [[embeddings()*]] for a given source; `llm4s-provider-testkit` reads embedding blocks through it. */
  private[llm4s] def embeddings(source: ConfigSource)(using
    ProviderRegistry
  ): Result[(String, EmbeddingProviderConfig)] =
    of(source).embeddings()

  /** As [[Llm4sSettings.embeddingsFor]], for the process-wide configuration. */
  private[llm4s] def embeddingsFor(modelSpec: String)(using
    ProviderRegistry
  ): Result[(String, EmbeddingProviderConfig)] =
    default.embeddingsFor(modelSpec)

  /** [[embeddingsFor(modelSpec:String)*]] for a given source. */
  private[llm4s] def embeddingsFor(source: ConfigSource, modelSpec: String)(using
    ProviderRegistry
  ): Result[(String, EmbeddingProviderConfig)] =
    of(source).embeddingsFor(modelSpec)

  /**
   * Loads and resolves the active embedding model, including its output dimensions.
   *
   * Reads `EMBEDDING_MODEL` and looks up the known dimension count for the
   * provider/model combination from the bundled dimension registry.
   *
   * Resolves the provider through [[embeddings]], and so through the given
   * [[org.llm4s.llmconnect.spi.ProviderRegistry]]: a provider that is
   * configurable there is configurable here, by the same registry.
   *
   * @return the resolved settings, or a [[org.llm4s.error.ConfigurationError]]
   *         when `EMBEDDING_MODEL` is absent or unrecognised.
   */
  def textEmbeddingModel()(using ProviderRegistry): Result[TextEmbeddingModelSettings] = default.textEmbeddingModel()

  private[llm4s] def textEmbeddingModel(source: ConfigSource)(using
    ProviderRegistry
  ): Result[TextEmbeddingModelSettings] =
    of(source).textEmbeddingModel()
}
