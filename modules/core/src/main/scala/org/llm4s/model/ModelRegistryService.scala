package org.llm4s.model

import org.llm4s.annotation.Stable
import org.llm4s.error.{ ConfigurationError, ValidationError }
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import java.util.concurrent.ConcurrentHashMap

import scala.io.Source
import scala.util.{ Try, Using }

/**
 * The model catalogue: a [[ModelMetadata]] per model id - its provider, mode, input and output token limits,
 * [[ModelCapabilities]], pricing and deprecation date - looked up by id, by provider, by mode or by capability.
 *
 * llm4s reads it wherever a decision depends on the model rather than the provider: `LLMConnect.getClient` and
 * `EmbeddingClient` take it as a `using` parameter, for the context window (`ContextWindowResolver`), the
 * capabilities a request is checked against (`RequestTransformer`) and cost estimates (`CostEstimator`). An
 * instance is an immutable snapshot, passed explicitly rather than read from global state, so two registries can
 * coexist in one process.
 *
 * Obtain one from:
 *  - `Llm4sConfig.modelRegistryService()`: the catalogue the application's `llm4s.modelRegistry` block selects;
 *  - [[ModelRegistryService.default]]: the catalogue bundled with llm4s, with no configuration read;
 *  - [[ModelRegistryService.fromConfig]]: a resource, file or URL of your own;
 *  - [[ModelRegistryService.fromModels]]: a fixed set of entries, as in a test.
 *
 * {{{
 * for {
 *   registry <- Llm4sConfig.modelRegistryService()
 *   given ModelRegistryService = registry
 *   config <- Llm4sConfig.defaultProvider()
 *   client <- LLMConnect.getClient(config)
 * } yield client
 * }}}
 *
 * A lookup that finds nothing is a `Left`; [[search]] and [[listProviders]] return a possibly empty list.
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

  /** Lists distinct provider names in sorted order, preserving their stored spelling; empty for an empty registry. */
  def listProviders(): List[String]

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
   * Looks up a model by its id, after trimming `modelId`. Matching tries, in order: an exact registry key, a
   * case-insensitive key, and a case-insensitive key with the first `provider/` prefix removed from the query.
   * It never returns a model whose id merely contains `modelId`: use [[search]] for that.
   *
   * @param modelId a registry identifier, optionally `provider/`-prefixed
   * @return the model, or a [[org.llm4s.error.ValidationError]] on `modelId` when no key matches
   */
  def lookup(modelId: String): Result[ModelMetadata]

  /**
   * Tries [[lookup(modelId:String)* lookup]] with `provider/modelName`, then with `modelName` alone, then a model
   * whose stored provider and `modelId` equal the two arguments ignoring case. The unprefixed attempt is an exact
   * id match, so it can resolve the same model id listed under another provider (an `azure` deployment of an
   * `openai` model, say), but never a different model.
   *
   * @param provider the provider to try first
   * @param modelName the model identifier to resolve
   * @return the first successful match, or a [[org.llm4s.error.ValidationError]] on `modelId` saying
   *         "Model not found" if all attempts fail
   */
  def lookup(provider: String, modelName: String): Result[ModelMetadata]

  /**
   * The models whose id contains `query`, ignoring case and surrounding whitespace, sorted by id; empty when none
   * does. For discovery (a picker, a "did you mean"), never for resolving the model a request is sent to.
   */
  def search(query: String): List[ModelMetadata]

final private[model] class DefaultModelRegistryService(
  private val metadata: Map[String, ModelMetadata]
) extends ModelRegistryService:

  override def findByCapability(capability: String): Result[List[ModelMetadata]] =
    val models = metadata.values.filter(_.supports(capability)).toList
    if models.nonEmpty then Right(models)
    else Left(ValidationError(s"No models found with capability: $capability", "capability"))

  override def listProviders(): List[String] =
    metadata.values.map(_.provider).toSet.toList.sorted

  override def listByMode(mode: ModelMode): Result[List[ModelMetadata]] =
    val models = metadata.values.filter(_.mode == mode).toList
    if models.nonEmpty then Right(models)
    else Left(ValidationError(s"No models found for mode: ${mode.name}", "mode"))

  override def listByProvider(provider: String): Result[List[ModelMetadata]] =
    val models = metadata.values.filter(_.provider.equalsIgnoreCase(provider)).toList
    if models.nonEmpty then Right(models)
    else Left(ValidationError(s"No models found for provider: $provider", "provider"))

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

  override def search(query: String): List[ModelMetadata] =
    val needle = query.trim.toLowerCase
    metadata.collect { case (id, model) if id.toLowerCase.contains(needle) => model }.toList.sortBy(_.modelId)

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
   * Loads the registry from the config's [[ModelRegistrySource]]. The location is trimmed; a blank one is an error.
   * The default classpath resource receives curated overrides as in [[fromResource]].
   *
   * @param config the resource, file or URL source
   * @return the registry, or a [[org.llm4s.error.ConfigurationError]] if the location is blank or the source
   *         cannot be loaded or parsed
   */
  def fromConfig(config: ModelRegistryConfig): Result[ModelRegistryService] =
    def located(location: String)(load: String => Result[ModelRegistryService]) =
      val trimmed = location.trim
      if trimmed.isEmpty then Left(ConfigurationError("ModelRegistryConfig's metadata source is blank"))
      else load(trimmed)

    config.source match
      case ModelRegistrySource.Resource(path) => located(path)(fromResource)
      case ModelRegistrySource.File(path)     => located(path)(fromFile)
      case ModelRegistrySource.Url(url)       => located(url)(fromUrl)

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
    loadResource(resourcePath).flatMap(readMetadataJson).flatMap { base =>
      val merged =
        if resourcePath == ModelRegistryConfig.DefaultResourcePath then
          mergeOverrides(base, ModelRegistryConfig.DefaultOverridesResourcePath)
        else base
      parseMetadataEntries(merged).map(DefaultModelRegistryService(_))
    }

  /**
   * The key that marks an entry in the overrides file as a patch rather than a replacement.
   *
   * An override entry replaces the base entry of the same id whole, which is how a correction drops a key the base
   * should not have. An entry with `"llm4s_patch": true` instead sets only the keys it lists on the base entry, so a
   * flag litellm does not carry (such as `supports_budget_thinking`) can be added without copying, and freezing, the
   * rest of the entry. A patch for an id the base does not have is skipped with a warning: a patch cannot make a model.
   */
  private[model] val PatchKey = "llm4s_patch"

  /**
   * Layers the curated llm4s corrections at `overridesResourcePath` over a base
   * snapshot, with override entries taking precedence (see [[PatchKey]] for entries
   * that patch rather than replace). The overlay is optional: if the resource is
   * absent or unparseable the base snapshot is returned unchanged so a packaging
   * slip can never make the registry unloadable.
   */
  private[model] def mergeOverrides(base: ujson.Obj, overridesResourcePath: String): ujson.Obj =
    loadResource(overridesResourcePath).flatMap(readMetadataJson) match
      case Right(overrides) => applyOverrides(base, overrides)
      case Left(error) =>
        logger.warn(s"Skipping embedded model metadata overrides: ${error.message}")
        base

  private[model] def applyOverrides(base: ujson.Obj, overrides: ujson.Obj): ujson.Obj =
    val merged = ujson.Obj.from(base.value)
    overrides.value.foreach {
      case (modelId, patch: ujson.Obj) if patch.value.get(PatchKey).contains(ujson.True) =>
        merged.value.get(modelId) match
          case Some(entry: ujson.Obj) =>
            merged(modelId) = ujson.Obj.from(entry.value ++ patch.value.view.filterKeys(_ != PatchKey))
          case _ =>
            logger.warn(s"Skipping model metadata patch for $modelId: the base snapshot has no such model")
      case (modelId, entry) =>
        merged(modelId) = entry
    }
    merged

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
    readMetadataJson(content).flatMap(parseMetadataEntries)

  private def readMetadataJson(content: String): Result[ujson.Obj] =
    Try(ujson.read(content).obj).toEither.left
      .map(e => ConfigurationError(s"Failed to parse metadata JSON: ${e.getMessage}"))
      .map(ujson.Obj.from(_))

  private def parseMetadataEntries(json: ujson.Obj): Result[Map[String, ModelMetadata]] =
    Try {
      val modelEntries = json.value.view.filterKeys(_ != "sample_spec").toMap

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

            metadata
              .find(_._1.equalsIgnoreCase(withoutProvider))
              .map(_._2)
              .toRight(ValidationError(s"Model not found: $normalized", "modelId"))
