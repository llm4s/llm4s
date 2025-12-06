package org.llm4s.model

import org.llm4s.config.ConfigReader
import org.llm4s.error.{ ConfigurationError, ValidationError }
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import scala.io.Source
import scala.util.{ Try, Using }

/**
 * Central registry for LLM model metadata.
 *
 * This provides a singleton lookup service for model capabilities, pricing,
 * and constraints. It loads data from the embedded litellm metadata file
 * and supports runtime updates from external sources.
 *
 * Example usage:
 * {{{
 *   val metadata = ModelRegistry.lookup("gpt-4o")
 *   metadata.foreach { m =>
 *     println(s"Context window: ${m.contextWindow}")
 *     println(s"Supports vision: ${m.supports("vision")}")
 *   }
 * }}}
 */
object ModelRegistry {
  private val logger = LoggerFactory.getLogger(getClass)

  // Embedded metadata file location
  private val EmbeddedMetadataPath = "/modeldata/litellm_model_metadata.json"

  // Environment variable for custom metadata file
  private val CustomMetadataEnvVar = "LLM4S_MODEL_METADATA_FILE"

  // Mutable cache of model metadata (lazy-loaded)
  @volatile private var metadataCache: Map[String, ModelMetadata] = Map.empty

  // Mutable cache of custom/override metadata
  @volatile private var customMetadataCache: Map[String, ModelMetadata] = Map.empty

  // Track if we've loaded the embedded data
  @volatile private var initialized: Boolean = false

  /**
   * Initialize the registry by loading embedded metadata.
   * This is called automatically on first access, but can be called explicitly.
   *
   * Also loads custom metadata from environment-specified file if available.
   *
   * @return Result indicating success or failure
   */
  def initialize(): Result[Unit] =
    synchronized {
      if (!initialized) {
        logger.info("Initializing ModelRegistry with embedded metadata")
        val result = for {
          metadata <- loadEmbeddedMetadata()
          _ = {
            metadataCache = metadata
            initialized = true
            logger.info(s"ModelRegistry initialized with ${metadata.size} models")
          }
          _ <- loadCustomMetadataIfConfigured()
        } yield ()

        result match {
          case Right(_) => Right(())
          case Left(error) =>
            logger.error(s"Failed to initialize ModelRegistry: $error")
            Left(error)
        }
      } else {
        Right(())
      }
    }

  /**
   * Lookup model metadata by model identifier.
   *
   * The lookup supports several formats:
   * - Exact match: "gpt-4o", "claude-3-7-sonnet-latest"
   * - Provider/model: "openai/gpt-4o", "anthropic/claude-3-7-sonnet-latest"
   * - Fuzzy match: partial model names
   *
   * @param modelId The model identifier to lookup
   * @return Model metadata if found
   */
  def lookup(modelId: String): Result[ModelMetadata] =
    for {
      _        <- ensureInitialized()
      metadata <- findModel(modelId)
    } yield metadata

  /**
   * Lookup model metadata by provider and model name.
   *
   * @param provider The provider name (e.g., "openai", "anthropic")
   * @param modelName The model name (e.g., "gpt-4o")
   * @return Model metadata if found
   */
  def lookup(provider: String, modelName: String): Result[ModelMetadata] =
    ensureInitialized().flatMap { _ =>
      val cache = getMergedCache()
      findModel(s"$provider/$modelName")
        .orElse(findModel(modelName))
        .orElse(
          cache.values
            .find(m => m.provider.equalsIgnoreCase(provider) && m.modelId.equalsIgnoreCase(modelName))
            .toRight(ValidationError(s"Model not found: $provider/$modelName", "modelId"))
        )
    }

  /**
   * Get all models for a specific provider.
   *
   * @param provider The provider name
   * @return List of models for that provider
   */
  def listByProvider(provider: String): Result[List[ModelMetadata]] =
    ensureInitialized().flatMap { _ =>
      val cache  = getMergedCache()
      val models = cache.values.filter(_.provider.equalsIgnoreCase(provider)).toList
      if (models.nonEmpty) Right(models)
      else Left(ValidationError(s"No models found for provider: $provider", "provider"))
    }

  /**
   * Get all models of a specific mode (chat, embedding, etc.).
   *
   * @param mode The model mode
   * @return List of models for that mode
   */
  def listByMode(mode: ModelMode): Result[List[ModelMetadata]] =
    ensureInitialized().flatMap { _ =>
      val cache  = getMergedCache()
      val models = cache.values.filter(_.mode == mode).toList
      if (models.nonEmpty) Right(models)
      else Left(ValidationError(s"No models found for mode: ${mode.name}", "mode"))
    }

  /**
   * Find models that support a specific capability.
   *
   * @param capability The capability name (e.g., "vision", "function_calling")
   * @return List of models supporting that capability
   */
  def findByCapability(capability: String): Result[List[ModelMetadata]] =
    ensureInitialized().flatMap { _ =>
      val cache  = getMergedCache()
      val models = cache.values.filter(_.supports(capability)).toList
      if (models.nonEmpty) Right(models)
      else Left(ValidationError(s"No models found with capability: $capability", "capability"))
    }

  /**
   * Get all available providers.
   *
   * @return List of unique provider names
   */
  def listProviders(): Result[List[String]] =
    ensureInitialized().map { _ =>
      val cache = getMergedCache()
      cache.values.map(_.provider).toSet.toList.sorted
    }

  /**
   * Update the registry with fresh metadata from an external source.
   *
   * @param url URL to fetch metadata from (defaults to litellm GitHub)
   * @return Result indicating success or failure
   */
  def updateFromUrl(
    url: String = "https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json"
  ): Result[Unit] =
    synchronized {
      logger.info(s"Updating ModelRegistry from $url")
      Try {
        val source  = Source.fromURL(url)
        val content = source.mkString
        source.close()
        content
      }.toEither.left
        .map(e => ConfigurationError(s"Failed to fetch metadata from $url: ${e.getMessage}"))
        .flatMap { content =>
          parseMetadataJson(content) match {
            case Right(metadata) =>
              metadataCache = metadata
              initialized = true
              logger.info(s"ModelRegistry updated with ${metadata.size} models")
              Right(())
            case Left(error) =>
              Left(error)
          }
        }
    }

  /**
   * Load custom model metadata from a file.
   * Custom metadata takes precedence over embedded metadata.
   *
   * @param filePath Path to the custom metadata JSON file
   * @return Result indicating success or failure
   */
  def loadCustomMetadata(filePath: String): Result[Unit] =
    synchronized {
      logger.info(s"Loading custom model metadata from $filePath")
      Try {
        Using.resource(Source.fromFile(filePath))(source => source.mkString)
      }.toEither.left
        .map(e => ConfigurationError(s"Failed to load custom metadata from $filePath: ${e.getMessage}"))
        .flatMap { content =>
          parseMetadataJson(content) match {
            case Right(metadata) =>
              customMetadataCache = metadata
              logger.info(s"Loaded ${metadata.size} custom models from $filePath")
              Right(())
            case Left(error) =>
              logger.error(s"Failed to parse custom metadata: $error")
              Left(error)
          }
        }
    }

  /**
   * Load custom metadata from a JSON string.
   *
   * @param jsonContent JSON string containing model metadata
   * @return Result indicating success or failure
   */
  def loadCustomMetadataFromString(jsonContent: String): Result[Unit] =
    synchronized {
      logger.info("Loading custom model metadata from JSON string")
      parseMetadataJson(jsonContent) match {
        case Right(metadata) =>
          customMetadataCache = metadata
          logger.info(s"Loaded ${metadata.size} custom models")
          Right(())
        case Left(error) =>
          logger.error(s"Failed to parse custom metadata: $error")
          Left(error)
      }
    }

  /**
   * Register custom model metadata.
   * This allows adding or overriding individual models.
   *
   * @param metadata The model metadata to register
   */
  def register(metadata: ModelMetadata): Unit =
    synchronized {
      customMetadataCache = customMetadataCache + (metadata.modelId -> metadata)
      logger.info(s"Registered custom model: ${metadata.modelId}")
    }

  /**
   * Register multiple custom models.
   *
   * @param models List of model metadata to register
   */
  def registerAll(models: List[ModelMetadata]): Unit =
    synchronized {
      customMetadataCache = customMetadataCache ++ models.map(m => m.modelId -> m)
      logger.info(s"Registered ${models.size} custom models")
    }

  /**
   * Clear all metadata and reset the registry.
   * Mainly useful for testing.
   */
  def reset(): Unit =
    synchronized {
      metadataCache = Map.empty
      customMetadataCache = Map.empty
      initialized = false
      logger.info("ModelRegistry reset")
    }

  /**
   * Get the merged metadata cache (custom overrides embedded).
   */
  private def getMergedCache(): Map[String, ModelMetadata] =
    metadataCache ++ customMetadataCache

  /**
   * Get statistics about the loaded metadata.
   *
   * @return Map of statistics
   */
  def statistics(): Map[String, Any] = {
    val cache = getMergedCache()
    Map(
      "totalModels"           -> cache.size,
      "embeddedModels"        -> metadataCache.size,
      "customModels"          -> customMetadataCache.size,
      "providers"             -> cache.values.map(_.provider).toSet.size,
      "chatModels"            -> cache.values.count(_.mode == ModelMode.Chat),
      "embeddingModels"       -> cache.values.count(_.mode == ModelMode.Embedding),
      "imageGenerationModels" -> cache.values.count(_.mode == ModelMode.ImageGeneration),
      "deprecatedModels"      -> cache.values.count(_.isDeprecated)
    )
  }

  // Private helper methods

  private def ensureInitialized(): Result[Unit] =
    if (initialized) Right(())
    else initialize()

  private def findModel(modelId: String): Result[ModelMetadata] = {
    val normalized = modelId.trim
    val cache      = getMergedCache()

    // Try exact match first
    cache.get(normalized) match {
      case Some(metadata) => Right(metadata)
      case None           =>
        // Try case-insensitive match
        cache.find(_._1.equalsIgnoreCase(normalized)) match {
          case Some((_, metadata)) => Right(metadata)
          case None                =>
            // Try stripping provider prefix if present
            val withoutProvider = if (normalized.contains("/")) normalized.split("/", 2).last else normalized
            cache.find(_._1.equalsIgnoreCase(withoutProvider)) match {
              case Some((_, metadata)) => Right(metadata)
              case None                =>
                // Try fuzzy match (contains)
                val fuzzyMatches = cache.filter { case (id, _) =>
                  id.toLowerCase.contains(normalized.toLowerCase)
                }
                if (fuzzyMatches.size == 1) Right(fuzzyMatches.head._2)
                else if (fuzzyMatches.size > 1)
                  Left(
                    ValidationError(
                      s"Ambiguous model identifier '$normalized'. Matches: ${fuzzyMatches.keys.take(5).mkString(", ")}",
                      "modelId"
                    )
                  )
                else
                  Left(ValidationError(s"Model not found: $normalized", "modelId"))
            }
        }
    }
  }

  private def loadCustomMetadataIfConfigured(): Result[Unit] =
    ConfigReader.LLMConfig().flatMap { cfg =>
      cfg.get(CustomMetadataEnvVar) match {
        case Some(path) if path.trim.nonEmpty =>
          logger.info(s"Custom metadata file specified: $path")
          loadCustomMetadata(path)
        case _ =>
          logger.debug(s"No custom metadata file configured (set $CustomMetadataEnvVar to specify)")
          Right(())
      }
    }

  private def loadEmbeddedMetadata(): Result[Map[String, ModelMetadata]] =
    Try {
      val stream = getClass.getResourceAsStream(EmbeddedMetadataPath)
      if (stream == null) {
        throw new IllegalStateException(s"Embedded metadata not found: $EmbeddedMetadataPath")
      }
      Using.resource(Source.fromInputStream(stream))(source => source.mkString)
    }.toEither.left
      .map(e => ConfigurationError(s"Failed to load embedded metadata: ${e.getMessage}"))
      .flatMap(parseMetadataJson)

  private def parseMetadataJson(content: String): Result[Map[String, ModelMetadata]] =
    Try {
      val json = ujson.read(content).obj

      // Filter out the sample_spec entry
      val modelEntries = json.view.filterKeys(_ != "sample_spec").toMap

      // Parse each model entry
      val parsed = modelEntries.flatMap { case (modelId, data) =>
        ModelMetadata.fromJson(modelId, data) match {
          case Right(metadata) => Some(modelId -> metadata)
          case Left(error) =>
            logger.warn(s"Skipping model $modelId: $error")
            None
        }
      }

      parsed.toMap
    }.toEither.left.map(e => ConfigurationError(s"Failed to parse metadata JSON: ${e.getMessage}"))
}
