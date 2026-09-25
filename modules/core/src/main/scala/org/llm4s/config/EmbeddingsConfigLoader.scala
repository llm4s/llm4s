package org.llm4s.config

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.{ EmbeddingProviderConfig, LocalEmbeddingModels }
import org.llm4s.llmconnect.spi.{
  EmbeddingConfigSpec,
  EmbeddingProviderDescriptor,
  EmbeddingProviderSection,
  ProviderRegistry
}
import org.llm4s.types.Result
import pureconfig.{ ConfigReader => PureConfigReader, ConfigSource }

import scala.util.Try

/**
 * Internal PureConfig-based loader for embeddings provider configuration.
 *
 * This is kept separate from Llm4sConfig to keep that façade slim; external
 * code should use Llm4sConfig.embeddings() and Llm4sConfig.textEmbeddingModel()
 * rather than this object directly.
 *
 * It knows the ''shape'' of an embedding provider's section - `apiKey`,
 * `baseUrl`, `model` - and nothing about which providers exist. Selecting one,
 * and making sense of its section, is the provider's own job, reached through
 * [[org.llm4s.llmconnect.spi.EmbeddingProviderDescriptor]]. Before that it held
 * a typed case class, a PureConfig reader, a builder and two `match` arms per
 * provider, so a third-party embedding provider was unreachable however it was
 * registered.
 */
private[config] object EmbeddingsConfigLoader {

  /** The part of `llm4s.embeddings` that is not a provider section. */
  final private case class EmbeddingsSection(
    model: Option[String],   // Unified format: provider/model (e.g., openai/text-embedding-3-small)
    provider: Option[String] // Legacy fallback
  )

  final private case class EmbeddingsRoot(embeddings: Option[EmbeddingsSection])

  // ---- PureConfig readers for internal shapes ----

  implicit private val embeddingsSectionReader: PureConfigReader[EmbeddingsSection] =
    PureConfigReader.forProduct2("model", "provider")(EmbeddingsSection.apply)

  implicit private val embeddingsRootReader: PureConfigReader[EmbeddingsRoot] =
    PureConfigReader.forProduct1("embeddings")(EmbeddingsRoot.apply)

  /** The uniform `llm4s.embeddings.<id>` shape, read for whichever provider was selected. */
  implicit private val providerSectionReader: PureConfigReader[EmbeddingProviderSection] =
    PureConfigReader.forProduct3("apiKey", "baseUrl", "model")(EmbeddingProviderSection.apply)

  // ---- Public API used by Llm4sConfig ----

  /** Load active embeddings provider and its config from the given source under llm4s.embeddings.*. */
  def loadProvider(source: ConfigSource)(using ProviderRegistry): Result[(String, EmbeddingProviderConfig)] = {
    val rootEither = source.at("llm4s").load[EmbeddingsRoot]

    rootEither.left
      .map { failures =>
        val msg = failures.toList.map(_.description).mkString("; ")
        ConfigurationError(s"Failed to load llm4s embeddings config via PureConfig: $msg")
      }
      .flatMap(buildEmbeddingsConfig(_, source))
  }

  /** Load configured local model names for non-text modalities from llm4s.embeddings.localModels. */
  def loadLocalModels(source: ConfigSource): Result[LocalEmbeddingModels] = {
    final case class LocalModelsSection(
      imageModel: String,
      audioModel: String,
      videoModel: String
    )

    implicit val localModelsSectionReader: PureConfigReader[LocalModelsSection] =
      PureConfigReader.forProduct3("imageModel", "audioModel", "videoModel")(LocalModelsSection.apply)

    source
      .at("llm4s.embeddings.localModels")
      .load[LocalModelsSection]
      .left
      .map { failures =>
        val msg = failures.toList.map(_.description).mkString("; ")
        ConfigurationError(s"Failed to load llm4s embeddings localModels via PureConfig: $msg")
      }
      .map(s => LocalEmbeddingModels(imageModel = s.imageModel, audioModel = s.audioModel, videoModel = s.videoModel))
  }

  // ---- Internal helpers ----

  private def buildEmbeddingsConfig(
    root: EmbeddingsRoot,
    source: ConfigSource
  )(using registry: ProviderRegistry): Result[(String, EmbeddingProviderConfig)] = {
    val emb = root.embeddings.getOrElse(EmbeddingsSection(None, None))

    // Unified `EMBEDDING_MODEL=provider/model` first, then the legacy EMBEDDING_PROVIDER.
    val selection: Result[Selection] =
      trimmed(emb.model) match {
        case Some(modelSpec) =>
          modelSpec.split("/", 2) match {
            case Array(provider, model) if provider.nonEmpty && model.nonEmpty =>
              Right(Selection(provider.toLowerCase, Some(model), UnifiedModelPath))
            case _ =>
              Left(
                ConfigurationError(
                  s"Invalid embedding model format: '$modelSpec'. Expected 'provider/model' (e.g., openai/text-embedding-3-small)"
                )
              )
          }

        case None =>
          trimmed(emb.provider)
            .map(provider => Right(Selection(provider.toLowerCase, None, LegacyProviderPath)))
            .getOrElse(
              Left(
                ConfigurationError(
                  "Missing embedding config: set EMBEDDING_MODEL (e.g., openai/text-embedding-3-small) or EMBEDDING_PROVIDER"
                )
              )
            )
      }

    // Bound whole rather than destructured in the generator: `Either` has no
    // `withFilter`, so a pattern-matching generator does not compile.
    for {
      selected   <- selection
      descriptor <- resolve(selected, registry)
      id = descriptor.id.asString
      section <- readSection(source, descriptor).map(withSharedApiKey(_, descriptor, source))
      config  <- descriptor.buildConfig(section, selected.modelOverride)
    } yield id -> config
  }

  private val UnifiedModelPath   = "llm4s.embeddings.model"
  private val LegacyProviderPath = "llm4s.embeddings.provider"

  /**
   * Which provider was asked for, and which setting asked for it.
   *
   * @param provider      the configured provider name, lowercased; not yet canonical.
   * @param modelOverride the `<model>` half of a unified `EMBEDDING_MODEL`, when that form was used.
   * @param origin        the config path the selection came from. Carried rather than assumed
   *                      because an unregistered provider is reported against it, and pointing
   *                      a legacy `EMBEDDING_PROVIDER` user at `llm4s.embeddings.model` names a
   *                      key they never set.
   */
  final private case class Selection(provider: String, modelOverride: Option[String], origin: String)

  /**
   * The descriptor for a configured provider name, or an error naming what is registered.
   *
   * The message is the registry's own, which carries the discovery summary: an embedding
   * provider that was never added to the classpath and one whose module failed to load
   * look identical from here otherwise.
   */
  private def resolve(selection: Selection, registry: ProviderRegistry): Result[EmbeddingProviderDescriptor] =
    registry.resolveEmbedding(registry.canonicalEmbeddingId(selection.provider), Some(selection.origin))

  /**
   * Reads `llm4s.embeddings.<id>`, treating an absent section as an empty one.
   *
   * A provider whose defaults cover everything needs no section at all, and a
   * missing section must not be reported as a config failure.
   */
  private def readSection(
    source: ConfigSource,
    descriptor: EmbeddingProviderDescriptor
  ): Result[EmbeddingProviderSection] = {
    // Built by `sectionPath`, which quotes a provider id containing a dot rather than
    // letting it read as a nested path.
    val path = EmbeddingConfigSpec.sectionPath(descriptor.id)
    val at   = source.at(path)
    if (!at.value().isRight) Right(EmbeddingProviderSection())
    else
      at.load[EmbeddingProviderSection].left.map { failures =>
        val msg = failures.toList.map(_.description).mkString("; ")
        ConfigurationError(s"Failed to load $path via PureConfig: $msg")
      }
  }

  /**
   * Fills in a credential the provider keeps outside its own section.
   *
   * A descriptor declares ''where'' with `EmbeddingConfigSpec.apiKeyPath`; the read itself
   * happens here, because `org.llm4s.config` is the only package allowed to touch raw
   * configuration - everywhere else consumes typed settings handed to it. OpenAI is the
   * case: its embeddings use the chat client's `llm4s.openai.apiKey`.
   */
  private def withSharedApiKey(
    section: EmbeddingProviderSection,
    descriptor: EmbeddingProviderDescriptor,
    source: ConfigSource
  ): EmbeddingProviderSection =
    if (trimmed(section.apiKey).isDefined) section
    else section.copy(apiKey = descriptor.configSpec.apiKeyPath.flatMap(readString(source, _)))

  /**
   * The string at an absolute config path, or `None`.
   *
   * A declared fallback that is absent, blank, or not a string must not fail the load -
   * the descriptor's own "missing key" error is the better report, and it names the path.
   */
  private def readString(source: ConfigSource, path: String): Option[String] =
    Try(source.at(path).load[String].toOption).toOption.flatten.map(_.trim).filter(_.nonEmpty)

  private def trimmed(value: Option[String]): Option[String] =
    value.map(_.trim).filter(_.nonEmpty)

}
