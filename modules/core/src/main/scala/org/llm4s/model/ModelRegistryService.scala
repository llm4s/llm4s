package org.llm4s.model

import org.llm4s.annotation.Stable
import org.llm4s.error.{ ConfigurationError, ValidationError }
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import java.util.concurrent.ConcurrentHashMap

import scala.io.Source
import scala.util.{ Try, Using }

/**
 * Read-only service interface for model registry operations.
 *
 * Implementations are immutable snapshots of model metadata that can be passed
 * through the application without relying on global process state.
 */
@Stable
trait ModelRegistryService:
  /**
   * Finds models whose [[ModelMetadata.supports]] accepts `capability`.
   *
   * Capability names are case-insensitive but are not trimmed. Supported names include `vision`, `tools`,
   * `reasoning` and their aliases documented by [[ModelMetadata.supports]]. Result order is unspecified.
   *
   * @param capability a capability name understood by [[ModelMetadata.supports]]
   * @return matching models, or a [[org.llm4s.error.ValidationError]] on `capability` when none match,
   *         including when the capability name is unknown
   */
  def findByCapability(capability: String): Result[List[ModelMetadata]]

  /**
   * Lists distinct provider names in sorted order, preserving their stored spelling.
   *
   * @return `Right` with the names, or an empty list for an empty registry; no validation error is returned
   */
  def listProviders(): Result[List[String]]

  /**
   * Finds models with exactly the given [[ModelMode]]. Result order is unspecified.
   *
   * @param mode the mode to match
   * @return matching models, or a [[org.llm4s.error.ValidationError]] on `mode` when none match
   */
  def listByMode(mode: ModelMode): Result[List[ModelMetadata]]

  /**
   * Finds models by a case-insensitive provider name, without trimming it. Result order is unspecified.
   *
   * @param provider the provider name to match
   * @return matching models, or a [[org.llm4s.error.ValidationError]] on `provider` when none match
   */
  def listByProvider(provider: String): Result[List[ModelMetadata]]

  /**
   * Returns snapshot counts under these keys: `totalModels`, `embeddedModels`, `customModels`, `providers`,
   * `chatModels`, `embeddingModels`, `imageGenerationModels` and `deprecatedModels`.
   *
   * All values are integers. `embeddedModels` equals `totalModels` and `customModels` is zero, even for a
   * registry loaded from an explicit source; these counts do not track the provenance of individual entries.
   * `providers` counts distinct stored provider names, and `deprecatedModels` uses [[ModelMetadata.isDeprecated]].
   *
   * @return `Right` with the counts, including zero counts for an empty registry
   */
  def statistics(): Result[Map[String, Any]]

  /**
   * Looks up a model after trimming `modelId`. Matching tries, in order: an exact registry key, a
   * case-insensitive key, a case-insensitive key with the first `provider/` prefix removed from the query,
   * and a unique case-insensitive substring match using the full trimmed query.
   *
   * @param modelId a registry identifier or a substring identifying a single model
   * @return the model, or a [[org.llm4s.error.ValidationError]] on `modelId` when no key matches or the
   *         substring matches multiple keys (an "Ambiguous model identifier" error)
   */
  def lookup(modelId: String): Result[ModelMetadata]

  /**
   * Tries [[lookup(modelId:String)* lookup]] with `provider/modelName`, then with `modelName` alone.
   * If both fail, tries a model whose stored provider and `modelId` equal the two arguments ignoring case,
   * without trimming those arguments. The unprefixed fallback can resolve a model from another provider.
   *
   * @param provider the provider to try first
   * @param modelName the model identifier to resolve
   * @return the first successful match, or a [[org.llm4s.error.ValidationError]] on `modelId` saying
   *         "Model not found" if all attempts fail, including ambiguous earlier attempts
   */
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

  // The snapshot never changes, so an id that is not an exact key resolves the same way every time: a client asks
  // for its one model on every reply, and an unknown one would otherwise rescan the registry each time (#2632).
  // Bounded, because the ids come from callers: past the bound a lookup is computed and not kept.
  private val resolved = new ConcurrentHashMap[String, Result[ModelMetadata]]()

  private def memoized(key: String)(compute: => Result[ModelMetadata]): Result[ModelMetadata] =
    val known = resolved.get(key)
    if known != null then known
    else
      val result = compute
      if resolved.size < DefaultModelRegistryService.MaxMemoized then resolved.putIfAbsent(key, result): Unit
      result

  override def lookup(modelId: String): Result[ModelMetadata] =
    metadata.get(modelId.trim) match
      case Some(modelMetadata) => Right(modelMetadata)
      case None                => memoized(modelId)(DefaultModelRegistryService.findModel(metadata, modelId))

  override def lookup(provider: String, modelName: String): Result[ModelMetadata] =
    memoized(s"$provider\u0000$modelName") {
      DefaultModelRegistryService
        .findModel(metadata, s"$provider/$modelName")
        .orElse(DefaultModelRegistryService.findModel(metadata, modelName))
        .orElse(
          metadata.values
            .find(m => m.provider.equalsIgnoreCase(provider) && m.modelId.equalsIgnoreCase(modelName))
            .toRight(ValidationError(s"Model not found: $provider/$modelName", "modelId"))
        )
    }

object ModelRegistryService:
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * The registry of the model catalogue bundled with llm4s ([[ModelRegistryConfig.default]]), read from the
   * classpath, with curated llm4s overrides taking precedence; missing or invalid overrides are logged and leave
   * the base snapshot usable. No configuration file is consulted. This is how code that builds a provider config
   * from values satisfies the `using ModelRegistryService` of `LLMConnect.getClient` (and, through
   * `ContextWindowResolver.fromModelRegistry`, a config's `fromValues`) without an `application.conf`:
   *
   * {{{
   * for {
   *   registry <- ModelRegistryService.default()
   *   given ModelRegistryService = registry
   *   config <- OllamaConfig.fromValues("llama3", "http://localhost:11434")
   *   client <- LLMConnect.getClient(config)
   * } yield client
   * }}}
   *
   * `Llm4sConfig.modelRegistryService()` reads the `llm4s.modelRegistry` block instead, for an
   * application that points the registry at its own catalogue.
   *
   * @return the registry, or a [[org.llm4s.error.ConfigurationError]] if the base resource cannot be read or parsed
   */
  def default(): Result[ModelRegistryService] =
    fromConfig(ModelRegistryConfig.default)

  /**
   * Loads exactly one metadata source from [[ModelRegistryConfig]]. Source strings are trimmed and blank
   * strings count as absent. A file or URL does not implicitly replace a configured resource: clear the other
   * source fields explicitly. The default classpath resource receives curated overrides as in [[fromResource]].
   *
   * @param config the resource, file or URL source
   * @return the registry, or a [[org.llm4s.error.ConfigurationError]] if zero or multiple sources remain,
   *         or the selected source cannot be loaded or parsed
   */
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

  /**
   * Builds a snapshot directly from [[ModelMetadata]] values, keyed by `modelId`, without reading external data
   * or applying overrides. For duplicate identifiers the last value wins; an empty iterable is valid.
   *
   * @param models the metadata to retain
   * @return the registry directly, without additional metadata validation or a `Result` wrapper
   */
  def fromModels(models: Iterable[ModelMetadata]): ModelRegistryService =
    DefaultModelRegistryService(models.iterator.map(model => model.modelId -> model).toMap)

  /**
   * Parses a JSON object mapping identifiers to [[ModelMetadata]] entries, without applying overrides.
   * The `sample_spec` key is ignored. Invalid individual model entries are logged and skipped, so a parsed
   * object can produce an empty registry.
   *
   * @param jsonContent the metadata JSON document
   * @return the registry, or a [[org.llm4s.error.ConfigurationError]] if parsing the document as an object fails
   */
  def fromJsonString(jsonContent: String): Result[ModelRegistryService] =
    parseMetadataJson(jsonContent).map(metadata => DefaultModelRegistryService(metadata))

  /**
   * Loads metadata JSON from a classpath resource. Curated llm4s overrides are applied only when the path
   * exactly equals [[ModelRegistryConfig.DefaultResourcePath]]; other resources are loaded without overrides.
   * Missing or invalid override resources are logged and ignored. The supplied path is not trimmed here.
   *
   * @param resourcePath the classpath resource path, resolved relative to this object's class
   * @return the registry, or a [[org.llm4s.error.ConfigurationError]] when the base resource is missing,
   *         cannot be read or cannot be parsed as a metadata object
   */
  def fromResource(resourcePath: String): Result[ModelRegistryService] =
    loadResource(resourcePath).flatMap(parseMetadataJson).map { base =>
      val metadata =
        if resourcePath == ModelRegistryConfig.DefaultResourcePath then
          mergeOverrides(base, ModelRegistryConfig.DefaultOverridesResourcePath)
        else base
      DefaultModelRegistryService(metadata)
    }

  /**
   * Layers the curated llm4s corrections at `overridesResourcePath` over a base
   * snapshot, with override entries taking precedence. The overlay is optional:
   * if the resource is absent or unparseable the base snapshot is returned
   * unchanged so a packaging slip can never make the registry unloadable.
   */
  private[model] def mergeOverrides(
    base: Map[String, ModelMetadata],
    overridesResourcePath: String
  ): Map[String, ModelMetadata] =
    loadResource(overridesResourcePath).flatMap(parseMetadataJson) match
      case Right(overrides) => base ++ overrides
      case Left(error) =>
        logger.warn(s"Skipping embedded model metadata overrides: ${error.message}")
        base

  /**
   * Reads a filesystem metadata JSON file and parses it as in [[fromJsonString]], without applying overrides.
   * The supplied path is not trimmed here.
   *
   * @param filePath the file to read
   * @return the registry, or a [[org.llm4s.error.ConfigurationError]] if reading or parsing the document fails
   */
  def fromFile(filePath: String): Result[ModelRegistryService] =
    loadFile(filePath).flatMap(fromJsonString)

  /**
   * Reads metadata JSON synchronously from a URL and parses it as in [[fromJsonString]], without applying overrides.
   * The supplied URL is not trimmed here.
   *
   * @param url the URL accepted by `scala.io.Source.fromURL`
   * @return the registry, or a [[org.llm4s.error.ConfigurationError]] if opening, reading or parsing the document fails
   */
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

  /** The most lookups of ids that are not exact keys one registry remembers (#2632). */
  val MaxMemoized: Int = 1024

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
