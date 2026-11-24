package org.llm4s.llmconnect.spi

import org.llm4s.annotation.Stable
import org.llm4s.security.Secret

import com.typesafe.config.ConfigUtil
import org.llm4s.config.SharedCredentials
import org.llm4s.error.{ ConfigurationError, TroubleshootingLinks }
import org.llm4s.llmconnect.config.ProviderTimeouts
import org.llm4s.types.ProviderModelTypes.{ BaseUrl, ModelName, ProviderId }
import org.llm4s.types.Result
import org.llm4s.util.Redaction

import scala.jdk.CollectionConverters._

/**
 * One embedding provider's `llm4s.embeddings.<id>` section, as read from config.
 *
 * The shape is the same for every provider, which is what lets `llm4s-core`
 * parse it without knowing which providers exist: the provider-specific part is
 * what [[EmbeddingProviderDescriptor.buildConfig]] makes of it.
 *
 * @param apiKey  `llm4s.embeddings.<id>.apiKey`.
 * @param baseUrl `llm4s.embeddings.<id>.baseUrl`, typed as a chat section's is.
 * @param model   `llm4s.embeddings.<id>.model`, typed as a chat section's is.
 * @param timeouts `llm4s.embeddings.<id>.timeouts`: how long an embedding request may take, as the same
 *                 `timeouts` block a chat section has. Only `request` applies, since an embedding call
 *                 does not stream. Absent means the provider's own default.
 * @param extras  the provider-specific keys the provider declares in [[EmbeddingConfigSpec.extras]] that have
 *                a value, defaults applied: what `NamedProviderConfig.extras` is to a chat section. A key the
 *                provider does not declare is not in here. Redacted in `toString`, since a value may be a
 *                credential.
 */
@Stable
final case class EmbeddingProviderSection private (
  apiKey: Option[Secret],
  baseUrl: Option[BaseUrl],
  model: Option[ModelName],
  timeouts: ProviderTimeouts,
  extras: Map[String, String]
) {
  def withApiKey(apiKey: Secret): EmbeddingProviderSection               = copy(apiKey = Some(apiKey))
  def withApiKey(apiKey: Option[Secret]): EmbeddingProviderSection       = copy(apiKey = apiKey)
  def withBaseUrl(baseUrl: BaseUrl): EmbeddingProviderSection            = copy(baseUrl = Some(baseUrl))
  def withBaseUrl(baseUrl: Option[BaseUrl]): EmbeddingProviderSection    = copy(baseUrl = baseUrl)
  def withModel(model: ModelName): EmbeddingProviderSection              = copy(model = Some(model))
  def withModel(model: Option[ModelName]): EmbeddingProviderSection      = copy(model = model)
  def withTimeouts(timeouts: ProviderTimeouts): EmbeddingProviderSection = copy(timeouts = timeouts)
  def withExtras(extras: Map[String, String]): EmbeddingProviderSection  = copy(extras = extras)

  /**
   * The value of a provider-specific key, as the loader left it.
   *
   * @param key a key the provider declares in [[EmbeddingConfigSpec.extras]]
   */
  def extra(key: String): Option[String] = extras.get(key)

  // Extra values may be credentials, so they are redacted and their names kept, as a chat section's are.
  override def toString: String =
    s"EmbeddingProviderSection(${apiKey.map(_ => "***")},${baseUrl.map(url => Redaction.url(url.asUrl))},$model," +
      s"$timeouts,${extras.keys.toSeq.sorted.map(k => s"$k -> ***").mkString("Map(", ", ", ")")})"
}

object EmbeddingProviderSection:

  /** Creates an [[EmbeddingProviderSection]]. Named arguments are the supported way to construct one. */
  def apply(
    apiKey: Option[Secret] = None,
    baseUrl: Option[BaseUrl] = None,
    model: Option[ModelName] = None,
    timeouts: ProviderTimeouts = ProviderTimeouts.default,
    extras: Map[String, String] = Map.empty
  ): EmbeddingProviderSection =
    new EmbeddingProviderSection(apiKey, baseUrl, model, timeouts, extras)

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
 * == The API key ==
 * The key is the section's own `apiKey` when it sets one, and otherwise the vendor's shared
 * key, `llm4s.credentials.<id>.apiKey`, which the module's `reference.conf` binds to the
 * vendor's variable (`llm4s.credentials.openai.apiKey = ${?OPENAI_API_KEY}`). The same shared
 * key serves the vendor's chat sections, so one variable configures both. `org.llm4s.config`
 * resolves that fallback before calling the descriptor; nothing here reads configuration.
 *
 * @param requiresApiKey the provider cannot work without a key; a missing one is an error.
 * @param defaultBaseUrl base URL used when the section omits one.
 * @param defaultModel   model used when neither `EMBEDDING_MODEL` nor the section names one.
 * @param defaultApiKey  stand-in for a provider that takes a key but does not need a real
 *                       one - Ollama running locally.
 * @param apiKeyEnv      the variables the module's `reference.conf` binds to
 *                       `llm4s.credentials.<id>.apiKey`, highest precedence first, named in the
 *                       error when the key is missing. A declaration for the message only:
 *                       the binding itself is the `reference.conf` line, and each module's
 *                       round-trip spec proves the two agree. Empty when nothing binds the
 *                       shared key, and the error then names the shared path instead.
 * @param modelEnv       the variable the module binds to the model, named likewise.
 * @param extras         provider-specific keys accepted in `llm4s.embeddings.<id>`, each with its own
 *                       description, default and requiredness - a region, an API version, a task. The loader
 *                       reads the declared keys, applies defaults and reports a required one that is missing,
 *                       and the descriptor reads them from [[EmbeddingProviderSection.extras]]; the default
 *                       `buildConfig` carries them to `build` in `EmbeddingProviderConfig.extras`. The same
 *                       [[ProviderConfigKey]] a chat provider declares. A name in [[EmbeddingConfigSpec.BuiltinKeys]]
 *                       is refused.
 */
@Stable
final case class EmbeddingConfigSpec private (
  requiresApiKey: Boolean,
  defaultBaseUrl: Option[String],
  defaultModel: Option[String],
  defaultApiKey: Option[String],
  apiKeyEnv: Seq[String],
  modelEnv: Option[String],
  extras: Seq[ProviderConfigKey]
) {
  def withExtras(extras: Seq[ProviderConfigKey]): EmbeddingConfigSpec  = copy(extras = extras)
  def withRequiresApiKey(requiresApiKey: Boolean): EmbeddingConfigSpec = copy(requiresApiKey = requiresApiKey)
  def withDefaultBaseUrl(defaultBaseUrl: String): EmbeddingConfigSpec  = copy(defaultBaseUrl = Some(defaultBaseUrl))
  def withDefaultBaseUrl(defaultBaseUrl: Option[String]): EmbeddingConfigSpec = copy(defaultBaseUrl = defaultBaseUrl)
  def withDefaultModel(defaultModel: String): EmbeddingConfigSpec             = copy(defaultModel = Some(defaultModel))
  def withDefaultModel(defaultModel: Option[String]): EmbeddingConfigSpec     = copy(defaultModel = defaultModel)
  def withDefaultApiKey(defaultApiKey: String): EmbeddingConfigSpec         = copy(defaultApiKey = Some(defaultApiKey))
  def withDefaultApiKey(defaultApiKey: Option[String]): EmbeddingConfigSpec = copy(defaultApiKey = defaultApiKey)
  def withApiKeyEnv(apiKeyEnv: Seq[String]): EmbeddingConfigSpec            = copy(apiKeyEnv = apiKeyEnv)
  def withModelEnv(modelEnv: String): EmbeddingConfigSpec                   = copy(modelEnv = Some(modelEnv))
  def withModelEnv(modelEnv: Option[String]): EmbeddingConfigSpec           = copy(modelEnv = modelEnv)
}

object EmbeddingConfigSpec:

  /** Creates a [[EmbeddingConfigSpec]]. Named arguments are the supported way to construct one. */
  def apply(
    requiresApiKey: Boolean = false,
    defaultBaseUrl: Option[String] = None,
    defaultModel: Option[String] = None,
    defaultApiKey: Option[String] = None,
    apiKeyEnv: Seq[String] = Seq.empty,
    modelEnv: Option[String] = None,
    extras: Seq[ProviderConfigKey] = Seq.empty
  ): EmbeddingConfigSpec =
    new EmbeddingConfigSpec(requiresApiKey, defaultBaseUrl, defaultModel, defaultApiKey, apiKeyEnv, modelEnv, extras)

  /**
   * The keys of `llm4s.embeddings.<id>` that every provider's section has, which a provider therefore cannot
   * declare in [[EmbeddingConfigSpec.extras]].
   */
  val BuiltinKeys: Set[String] = Set("apiKey", "apiKeyFile", "baseUrl", "model", "timeouts")

  /**
   * The config path of a provider's section, e.g. `llm4s.embeddings.openai`.
   *
   * Built by joining literal segments rather than by interpolation, because
   * `ProviderId` canonicalises but does not restrict: `ProviderId("acme.embeddings")`
   * is a legal id, and interpolating it would name the nested path
   * `llm4s.embeddings.acme.embeddings` rather than the key the user quoted.
   * `ConfigUtil.joinPath` quotes each segment that needs it, so the path this
   * returns is also the path to write in HOCON.
   */
  def sectionPath(id: ProviderId): String =
    ConfigUtil.joinPath(List("llm4s", "embeddings", id.asString).asJava)

  /** The config path of one field in a provider's section, quoted as [[sectionPath]] is. */
  def fieldPath(id: ProviderId, field: String): String =
    ConfigUtil.joinPath(List("llm4s", "embeddings", id.asString, field).asJava)

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
      .orElse(nonEmpty(section.model.map(_.asString)))
      .orElse(spec.defaultModel)
      .toRight {
        val env = spec.modelEnv.fold("")(name => s" or $name")
        ConfigurationError(
          s"Missing ${id.asString} embeddings model " +
            s"(set EMBEDDING_MODEL=${id.asString}/<model>, ${fieldPath(id, "model")}$env)"
        )
      }

  /** Resolves the base URL: the section first, then the spec's default. */
  def resolveBaseUrl(id: ProviderId, section: EmbeddingProviderSection, spec: EmbeddingConfigSpec): Result[String] =
    nonEmpty(section.baseUrl.map(_.asUrl))
      .orElse(spec.defaultBaseUrl)
      .toRight(
        ConfigurationError(
          s"Missing ${id.asString} embeddings base URL (${fieldPath(id, "baseUrl")})"
        )
      )

  /**
   * Resolves the API key: the section first, then the spec's stand-in.
   *
   * The section's `apiKey` already accounts for the shared `llm4s.credentials.<id>.apiKey`:
   * the loader falls back to it and fills it in before calling the descriptor, so no
   * configuration is read from here.
   *
   * A provider whose spec neither requires a key nor supplies a default gets
   * the empty string, which is what a local provider that ignores the field
   * expects.
   */
  def resolveApiKey(
    id: ProviderId,
    section: EmbeddingProviderSection,
    spec: EmbeddingConfigSpec
  ): Result[Secret] =
    nonEmpty(section.apiKey.map(_.reveal))
      .orElse(spec.defaultApiKey)
      .map(Secret(_)) match
      case Some(key)                    => Right(key)
      case None if !spec.requiresApiKey => Right(Secret(""))
      case None =>
        Left(
          ConfigurationError(
            TroubleshootingLinks.configuration(
              s"Missing ${id.asString} embeddings apiKey: " +
                SharedCredentials.missingKeyHint(spec.apiKeyEnv, id, sectionPath(id)),
              TroubleshootingLinks.Entry.MissingApiKey
            )
          )
        )

  private def nonEmpty(value: Option[String]): Option[String] =
    value.map(_.trim).filter(_.nonEmpty)
