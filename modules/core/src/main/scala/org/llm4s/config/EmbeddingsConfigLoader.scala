// scalafix:off DisableSyntax.NoPureConfigDefault
package org.llm4s.config

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.{ EmbeddingProviderConfig, LocalEmbeddingModels }
import org.llm4s.types.Result
import pureconfig.{ ConfigReader => PureConfigReader, ConfigSource }

/**
 * Internal PureConfig-based loader for embeddings provider configuration.
 *
 * This is kept separate from Llm4sConfig to keep that façade slim; external
 * code should use Llm4sConfig.embeddings() and Llm4sConfig.textEmbeddingModel()
 * rather than this object directly.
 */
private[config] object EmbeddingsConfigLoader {

  final private case class EmbeddingsOpenAISection(
    baseUrl: Option[String],
    model: Option[String]
  )

  final private case class EmbeddingsVoyageSection(
    apiKey: Option[String],
    baseUrl: Option[String],
    model: Option[String]
  )

  final private case class EmbeddingsOllamaSection(
    apiKey: Option[String],
    baseUrl: Option[String],
    model: Option[String]
  )

  final private case class EmbeddingsSection(
    provider: Option[String],
    openai: Option[EmbeddingsOpenAISection],
    voyage: Option[EmbeddingsVoyageSection],
    ollama: Option[EmbeddingsOllamaSection]
  )

  final private case class EmbeddingsRoot(embeddings: Option[EmbeddingsSection])

  // ---- PureConfig readers for internal shapes ----

  implicit private val embeddingsOpenAISectionReader: PureConfigReader[EmbeddingsOpenAISection] =
    PureConfigReader.forProduct2("baseUrl", "model")(EmbeddingsOpenAISection.apply)

  implicit private val embeddingsVoyageSectionReader: PureConfigReader[EmbeddingsVoyageSection] =
    PureConfigReader.forProduct3("apiKey", "baseUrl", "model")(EmbeddingsVoyageSection.apply)

  implicit private val embeddingsOllamaSectionReader: PureConfigReader[EmbeddingsOllamaSection] =
    PureConfigReader.forProduct3("apiKey", "baseUrl", "model")(EmbeddingsOllamaSection.apply)

  implicit private val embeddingsSectionReader: PureConfigReader[EmbeddingsSection] =
    PureConfigReader.forProduct4("provider", "openai", "voyage", "ollama")(EmbeddingsSection.apply)

  implicit private val embeddingsRootReader: PureConfigReader[EmbeddingsRoot] =
    PureConfigReader.forProduct1("embeddings")(EmbeddingsRoot.apply)

  // ---- Public API used by Llm4sConfig ----

  /** Load active embeddings provider and its config from the given source under llm4s.embeddings.*. */
  def loadProvider(source: ConfigSource): Result[(String, EmbeddingProviderConfig)] = {
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
    source: ConfigSource,
  ): Result[(String, EmbeddingProviderConfig)] = {
    val emb = root.embeddings.getOrElse(EmbeddingsSection(None, None, None, None))

    emb.provider.map(_.trim.toLowerCase) match {
      case None =>
        Left(ConfigurationError("Missing embeddings provider (llm4s.embeddings.provider / EMBEDDING_PROVIDER)"))
      case Some(provider) =>
        provider match {
          case "openai" =>
            buildOpenAIEmbeddings(emb.openai, source).map("openai" -> _)
          case "voyage" =>
            buildVoyageEmbeddings(emb.voyage).map("voyage" -> _)
          case "ollama" =>
            buildOllamaEmbeddings(emb.ollama).map("ollama" -> _)
          case other =>
            Left(ConfigurationError(s"Unknown embedding provider: $other"))
        }
    }
  }

  private def buildOpenAIEmbeddings(
    section: Option[EmbeddingsOpenAISection],
    source: ConfigSource,
  ): Result[EmbeddingProviderConfig] =
    section match {
      case Some(openai) =>
        val baseUrlOpt = openai.baseUrl.map(_.trim).filter(_.nonEmpty)
        val modelOpt   = openai.model.map(_.trim).filter(_.nonEmpty)

        val baseUrlResult: Result[String] =
          baseUrlOpt.toRight(
            ConfigurationError(
              "Missing OpenAI embeddings baseUrl (llm4s.embeddings.openai.baseUrl / OPENAI_EMBEDDING_BASE_URL)"
            )
          )
        val modelResult: Result[String] =
          modelOpt.toRight(
            ConfigurationError(
              "Missing OpenAI embeddings model (llm4s.embeddings.openai.model / OPENAI_EMBEDDING_MODEL)"
            )
          )

        for {
          baseUrl <- baseUrlResult
          model   <- modelResult
          apiKey  <- ProviderConfigLoader.loadOpenAISharedApiKey(source)
        } yield EmbeddingProviderConfig(baseUrl = baseUrl, model = model, apiKey = apiKey)

      case None =>
        Left(
          ConfigurationError(
            "OpenAI embeddings provider selected but llm4s.embeddings.openai section is missing"
          )
        )
    }

  private def buildOllamaEmbeddings(
    section: Option[EmbeddingsOllamaSection]
  ): Result[EmbeddingProviderConfig] =
    section match {
      case Some(ollama) =>
        val baseUrl = ollama.baseUrl.map(_.trim).filter(_.nonEmpty).getOrElse("http://localhost:11434")
        val model   = ollama.model.map(_.trim).filter(_.nonEmpty).getOrElse("nomic-embed-text")
        val apiKey  = ollama.apiKey.getOrElse("not-required")
        Right(EmbeddingProviderConfig(baseUrl = baseUrl, model = model, apiKey = apiKey))
      case None =>
        Left(
          ConfigurationError(
            "Ollama embeddings provider selected but llm4s.embeddings.ollama section is missing"
          )
        )
    }

  private def buildVoyageEmbeddings(section: Option[EmbeddingsVoyageSection]): Result[EmbeddingProviderConfig] =
    section match {
      case Some(voyage) =>
        val apiKeyOpt  = voyage.apiKey.map(_.trim).filter(_.nonEmpty)
        val baseUrlOpt = voyage.baseUrl.map(_.trim).filter(_.nonEmpty)
        val modelOpt   = voyage.model.map(_.trim).filter(_.nonEmpty)

        val apiKeyResult: Result[String] =
          apiKeyOpt.toRight(
            ConfigurationError("Missing Voyage embeddings apiKey (llm4s.embeddings.voyage.apiKey / VOYAGE_API_KEY)")
          )
        val baseUrlResult: Result[String] =
          baseUrlOpt.toRight(
            ConfigurationError(
              "Missing Voyage embeddings baseUrl (llm4s.embeddings.voyage.baseUrl / VOYAGE_EMBEDDING_BASE_URL)"
            )
          )
        val modelResult: Result[String] =
          modelOpt.toRight(
            ConfigurationError(
              "Missing Voyage embeddings model (llm4s.embeddings.voyage.model / VOYAGE_EMBEDDING_MODEL)"
            )
          )

        for {
          apiKey  <- apiKeyResult
          baseUrl <- baseUrlResult
          model   <- modelResult
        } yield EmbeddingProviderConfig(baseUrl = baseUrl, model = model, apiKey = apiKey)

      case None =>
        Left(
          ConfigurationError(
            "Voyage embeddings provider selected but llm4s.embeddings.voyage section is missing"
          )
        )
    }
}
// scalafix:on DisableSyntax.NoPureConfigDefault
