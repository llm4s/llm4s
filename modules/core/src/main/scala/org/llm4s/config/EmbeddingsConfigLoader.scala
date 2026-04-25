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
    model: Option[String],    // Unified format: provider/model (e.g., openai/text-embedding-3-small)
    provider: Option[String], // Legacy fallback
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
    PureConfigReader.forProduct5("model", "provider", "openai", "voyage", "ollama")(EmbeddingsSection.apply)

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
    val emb = root.embeddings.getOrElse(EmbeddingsSection(None, None, None, None, None))

    // Check for unified model format first (e.g., "openai/text-embedding-3-small")
    emb.model.map(_.trim).filter(_.nonEmpty) match {
      case Some(modelSpec) =>
        // Parse provider/model format
        val parts = modelSpec.split("/", 2)
        if (parts.length == 2) {
          val (providerName, modelName) = (parts(0).toLowerCase, parts(1))
          providerName match {
            case "openai" =>
              buildOpenAIEmbeddings(emb.openai, source, Some(modelName)).map("openai" -> _)
            case "voyage" =>
              buildVoyageEmbeddings(emb.voyage, Some(modelName)).map("voyage" -> _)
            case "ollama" =>
              buildOllamaEmbeddings(emb.ollama, Some(modelName)).map("ollama" -> _)
            case other =>
              Left(ConfigurationError(s"Unknown embedding provider: $other in '$modelSpec'"))
          }
        } else {
          Left(
            ConfigurationError(
              s"Invalid embedding model format: '$modelSpec'. Expected 'provider/model' (e.g., openai/text-embedding-3-small)"
            )
          )
        }

      case None =>
        // Fall back to legacy EMBEDDING_PROVIDER approach
        emb.provider.map(_.trim.toLowerCase) match {
          case Some("openai") =>
            buildOpenAIEmbeddings(emb.openai, source, None).map("openai" -> _)
          case Some("voyage") =>
            buildVoyageEmbeddings(emb.voyage, None).map("voyage" -> _)
          case Some("ollama") =>
            buildOllamaEmbeddings(emb.ollama, None).map("ollama" -> _)
          case Some(other) =>
            Left(ConfigurationError(s"Unknown embedding provider: $other"))
          case None =>
            Left(
              ConfigurationError(
                "Missing embedding config: set EMBEDDING_MODEL (e.g., openai/text-embedding-3-small) or EMBEDDING_PROVIDER"
              )
            )
        }
    }
  }

  private val DefaultOpenAIEmbeddingBaseUrl = "https://api.openai.com/v1"
  private val DefaultVoyageEmbeddingBaseUrl = "https://api.voyageai.com/v1"
  private val DefaultOllamaEmbeddingBaseUrl = "http://localhost:11434"

  private def buildOpenAIEmbeddings(
    section: Option[EmbeddingsOpenAISection],
    source: ConfigSource,
    modelOverride: Option[String]
  ): Result[EmbeddingProviderConfig] = {
    // Use model from unified format, or fall back to section model
    val modelOpt = modelOverride.orElse(section.flatMap(_.model)).map(_.trim).filter(_.nonEmpty)

    // Use section baseUrl if provided, otherwise default
    val baseUrl = section
      .flatMap(_.baseUrl)
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse(DefaultOpenAIEmbeddingBaseUrl)

    val modelResult: Result[String] =
      modelOpt.toRight(
        ConfigurationError(
          "Missing OpenAI embeddings model (set EMBEDDING_MODEL=openai/model-name or OPENAI_EMBEDDING_MODEL)"
        )
      )

    for {
      model  <- modelResult
      apiKey <- loadOpenAISharedApiKey(source)
    } yield EmbeddingProviderConfig(baseUrl = baseUrl, model = model, apiKey = apiKey)
  }

  private def loadOpenAISharedApiKey(source: ConfigSource): Result[String] = {
    final case class OpenAISection(apiKey: Option[String])
    final case class Root(openai: Option[OpenAISection])

    given PureConfigReader[OpenAISection] =
      PureConfigReader.forProduct1("apiKey")(OpenAISection.apply)

    given PureConfigReader[Root] =
      PureConfigReader.forProduct1("openai")(Root.apply)

    source
      .at("llm4s")
      .load[Root]
      .left
      .map { failures =>
        val msg = failures.toList.map(_.description).mkString("; ")
        ConfigurationError(
          s"Failed to load llm4s provider config via PureConfig when resolving OpenAI API key: $msg"
        )
      }
      .flatMap { root =>
        root.openai
          .flatMap(_.apiKey)
          .map(_.trim)
          .filter(_.nonEmpty)
          .toRight(ConfigurationError("Missing OpenAI API key (llm4s.openai.apiKey / OPENAI_API_KEY)"))
      }
  }

  private def buildOllamaEmbeddings(
    section: Option[EmbeddingsOllamaSection],
    modelOverride: Option[String]
  ): Result[EmbeddingProviderConfig] = {
    // Use model from unified format, or fall back to section model, or default
    val model = modelOverride
      .orElse(section.flatMap(_.model))
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse("nomic-embed-text")

    // Use section baseUrl if provided, otherwise default
    val baseUrl = section
      .flatMap(_.baseUrl)
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse(DefaultOllamaEmbeddingBaseUrl)

    val apiKey = section.flatMap(_.apiKey).getOrElse("not-required")
    Right(EmbeddingProviderConfig(baseUrl = baseUrl, model = model, apiKey = apiKey))
  }

  private def buildVoyageEmbeddings(
    section: Option[EmbeddingsVoyageSection],
    modelOverride: Option[String]
  ): Result[EmbeddingProviderConfig] = {
    // Use model from unified format, or fall back to section model
    val modelOpt = modelOverride.orElse(section.flatMap(_.model)).map(_.trim).filter(_.nonEmpty)

    // Use section baseUrl if provided, otherwise default
    val baseUrl = section
      .flatMap(_.baseUrl)
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse(DefaultVoyageEmbeddingBaseUrl)

    val apiKeyOpt = section.flatMap(_.apiKey).map(_.trim).filter(_.nonEmpty)

    val apiKeyResult: Result[String] =
      apiKeyOpt.toRight(
        ConfigurationError("Missing Voyage embeddings apiKey (llm4s.embeddings.voyage.apiKey / VOYAGE_API_KEY)")
      )
    val modelResult: Result[String] =
      modelOpt.toRight(
        ConfigurationError(
          "Missing Voyage embeddings model (set EMBEDDING_MODEL=voyage/model-name or VOYAGE_EMBEDDING_MODEL)"
        )
      )

    for {
      apiKey <- apiKeyResult
      model  <- modelResult
    } yield EmbeddingProviderConfig(baseUrl = baseUrl, model = model, apiKey = apiKey)
  }
}
// scalafix:on DisableSyntax.NoPureConfigDefault
