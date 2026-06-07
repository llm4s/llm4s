package org.llm4s.model

import org.llm4s.error.{ ConfigurationError, ValidationError }
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import scala.io.Source
import scala.util.{ Try, Using }

/**
 * Read-only service interface for model registry operations.
 *
 * Implementations are immutable snapshots of model metadata that can be passed
 * through the application without relying on global process state.
 */
trait ModelRegistryService:
  def findByCapability(capability: String): Result[List[ModelMetadata]]
  def listProviders(): Result[List[String]]
  def listByMode(mode: ModelMode): Result[List[ModelMetadata]]
  def listByProvider(provider: String): Result[List[ModelMetadata]]
  def statistics(): Result[Map[String, Any]]
  def lookup(modelId: String): Result[ModelMetadata]
  def lookup(provider: String, modelName: String): Result[ModelMetadata]

final private[model] class DefaultModelRegistryService(
  private val metadata: Map[String, ModelMetadata]
) extends ModelRegistryService:

  override def findByCapability(capability: String): Result[List[ModelMetadata]] =
    val models = metadata.values.filter(_.supports(capability)).toList
    if models.nonEmpty then Right(models)
    else Left(ValidationError(s"No models found with capability: $capability", "capability"))

  override def listProviders(): Result[List[String]] =
    Right(metadata.values.map(_.provider).toSet.toList.sorted)

  override def listByMode(mode: ModelMode): Result[List[ModelMetadata]] =
    val models = metadata.values.filter(_.mode == mode).toList
    if models.nonEmpty then Right(models)
    else Left(ValidationError(s"No models found for mode: ${mode.name}", "mode"))

  override def listByProvider(provider: String): Result[List[ModelMetadata]] =
    val models = metadata.values.filter(_.provider.equalsIgnoreCase(provider)).toList
    if models.nonEmpty then Right(models)
    else Left(ValidationError(s"No models found for provider: $provider", "provider"))

  override def statistics(): Result[Map[String, Any]] =
    Right(
      Map(
        "totalModels"           -> metadata.size,
        "embeddedModels"        -> metadata.size,
        "customModels"          -> 0,
        "providers"             -> metadata.values.map(_.provider).toSet.size,
        "chatModels"            -> metadata.values.count(_.mode == ModelMode.Chat),
        "embeddingModels"       -> metadata.values.count(_.mode == ModelMode.Embedding),
        "imageGenerationModels" -> metadata.values.count(_.mode == ModelMode.ImageGeneration),
        "deprecatedModels"      -> metadata.values.count(_.isDeprecated)
      )
    )

  override def lookup(modelId: String): Result[ModelMetadata] =
    DefaultModelRegistryService.findModel(metadata, modelId)

  override def lookup(provider: String, modelName: String): Result[ModelMetadata] =
    DefaultModelRegistryService
      .findModel(metadata, s"$provider/$modelName")
      .orElse(DefaultModelRegistryService.findModel(metadata, modelName))
      .orElse(
        metadata.values
          .find(m => m.provider.equalsIgnoreCase(provider) && m.modelId.equalsIgnoreCase(modelName))
          .toRight(ValidationError(s"Model not found: $provider/$modelName", "modelId"))
      )

object ModelRegistryService:
  private val logger = LoggerFactory.getLogger(getClass)

  def default(): Result[ModelRegistryService] =
    fromConfig(ModelRegistryConfig.default)

  def fromConfig(config: ModelRegistryConfig): Result[ModelRegistryService] =
    val resourcePath = config.resourcePath.map(_.trim).filter(_.nonEmpty)
    val filePath     = config.filePath.map(_.trim).filter(_.nonEmpty)
    val url          = config.url.map(_.trim).filter(_.nonEmpty)

    (resourcePath, filePath, url) match
      case (Some(resourcePath), None, None) =>
        fromResource(resourcePath)
      case (None, Some(filePath), None) =>
        fromFile(filePath)
      case (None, None, Some(url)) =>
        fromUrl(url)
      case (None, None, None) =>
        Left(ConfigurationError("ModelRegistryConfig must define one metadata source"))
      case _ =>
        Left(ConfigurationError("ModelRegistryConfig must define exactly one of resourcePath, filePath, or url"))

  def fromModels(models: Iterable[ModelMetadata]): ModelRegistryService =
    DefaultModelRegistryService(models.iterator.map(model => model.modelId -> model).toMap)

  def fromJsonString(jsonContent: String): Result[ModelRegistryService] =
    parseMetadataJson(jsonContent).map(metadata => DefaultModelRegistryService(metadata))

  def fromResource(resourcePath: String): Result[ModelRegistryService] =
    loadResource(resourcePath).flatMap(fromJsonString)

  def fromFile(filePath: String): Result[ModelRegistryService] =
    loadFile(filePath).flatMap(fromJsonString)

  def fromUrl(url: String): Result[ModelRegistryService] =
    loadUrl(url).flatMap(fromJsonString)

  private def loadResource(resourcePath: String): Result[String] =
    val stream = getClass.getResourceAsStream(resourcePath)
    if stream == null then Left(ConfigurationError(s"Embedded metadata not found: $resourcePath"))
    else
      Try {
        Using.resource(Source.fromInputStream(stream))(source => source.mkString)
      }.toEither.left
        .map(e => ConfigurationError(s"Failed to load embedded metadata: ${e.getMessage}"))

  private def loadFile(filePath: String): Result[String] =
    Try {
      Using.resource(Source.fromFile(filePath))(source => source.mkString)
    }.toEither.left
      .map(e => ConfigurationError(s"Failed to load model metadata from $filePath: ${e.getMessage}"))

  private def loadUrl(url: String): Result[String] =
    Try {
      Using.resource(Source.fromURL(url))(source => source.mkString)
    }.toEither.left
      .map(e => ConfigurationError(s"Failed to load model metadata from $url: ${e.getMessage}"))

  private[model] def parseMetadataJson(content: String): Result[Map[String, ModelMetadata]] =
    Try {
      val json         = ujson.read(content).obj
      val modelEntries = json.view.filterKeys(_ != "sample_spec").toMap

      modelEntries.flatMap { case (modelId, data) =>
        ModelMetadata.fromJson(modelId, data) match
          case Right(modelMetadata) => Some(modelId -> modelMetadata)
          case Left(error) =>
            logger.warn(s"Skipping model $modelId: ${error.message}")
            None
      }.toMap
    }.toEither.left.map(e => ConfigurationError(s"Failed to parse metadata JSON: ${e.getMessage}"))

private object DefaultModelRegistryService:
  def findModel(
    metadata: Map[String, ModelMetadata],
    modelId: String
  ): Result[ModelMetadata] =
    val normalized = modelId.trim

    metadata.get(normalized) match
      case Some(modelMetadata) => Right(modelMetadata)
      case None =>
        metadata.find(_._1.equalsIgnoreCase(normalized)) match
          case Some((_, modelMetadata)) => Right(modelMetadata)
          case None =>
            val withoutProvider =
              if normalized.contains("/") then normalized.split("/", 2).last
              else normalized

            metadata.find(_._1.equalsIgnoreCase(withoutProvider)) match
              case Some((_, modelMetadata)) => Right(modelMetadata)
              case None =>
                val fuzzyMatches = metadata.filter { case (id, _) =>
                  id.toLowerCase.contains(normalized.toLowerCase)
                }

                if fuzzyMatches.size == 1 then Right(fuzzyMatches.head._2)
                else if fuzzyMatches.size > 1 then
                  Left(
                    ValidationError(
                      s"Ambiguous model identifier '$normalized'. Matches: ${fuzzyMatches.keys.take(5).mkString(", ")}",
                      "modelId"
                    )
                  )
                else Left(ValidationError(s"Model not found: $normalized", "modelId"))
