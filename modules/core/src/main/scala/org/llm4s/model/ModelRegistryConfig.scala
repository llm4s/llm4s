package org.llm4s.model

import org.llm4s.annotation.Stable

/** Where the model registry's metadata JSON is read from: exactly one place. */
@Stable
enum ModelRegistrySource:

  /** A classpath resource; [[ModelRegistryConfig.DefaultResourcePath]] also gets llm4s's curated overrides. */
  case Resource(path: String)

  /** A file on the filesystem. */
  case File(path: String)

  /** An HTTP(S) or other `java.net.URL`, fetched synchronously. */
  case Url(url: String)

/**
 * Configuration controlling how the model registry data is loaded.
 *
 * @param source where the metadata is read from; by default the bundled LiteLLM resource
 *
 * Build one with [[ModelRegistryConfig.apply]] (or start from [[ModelRegistryConfig.default]]) and change it with the
 * `with*` setters; each replaces the source, so a config always names exactly one. The constructor and `copy` are
 * private, so a new field never breaks callers.
 */
@Stable
final case class ModelRegistryConfig private (source: ModelRegistrySource):

  /** Sets where the metadata is read from. */
  def withSource(source: ModelRegistrySource): ModelRegistryConfig = copy(source = source)

  /** Reads the metadata from a classpath resource. */
  def withResourcePath(resourcePath: String): ModelRegistryConfig = withSource(
    ModelRegistrySource.Resource(resourcePath)
  )

  /** Reads the metadata from a file. */
  def withFilePath(filePath: String): ModelRegistryConfig = withSource(ModelRegistrySource.File(filePath))

  /** Fetches the metadata from a URL. */
  def withUrl(url: String): ModelRegistryConfig = withSource(ModelRegistrySource.Url(url))

/** Companion object providing defaults for `ModelRegistryConfig`. */
object ModelRegistryConfig:

  /**
   * Creates a [[ModelRegistryConfig]]; with no arguments, it loads the bundled classpath resource.
   *
   * @param source where the metadata is read from; defaults to the resource at [[DefaultResourcePath]]
   */
  def apply(source: ModelRegistrySource = ModelRegistrySource.Resource(DefaultResourcePath)): ModelRegistryConfig =
    new ModelRegistryConfig(source)

  /** Classpath resource path to the bundled LiteLLM model metadata JSON file. */
  val DefaultResourcePath = "/modeldata/litellm_model_metadata.json"

  /**
   * Curated llm4s corrections layered on top of the vendored LiteLLM snapshot
   * (see [[DefaultResourcePath]]). The snapshot is a verbatim upstream copy and
   * occasionally drops or mis-shapes entries llm4s relies on (e.g. a chat model
   * regressing to an `embedding` row, or a canonical alias being removed). Entries here
   * take precedence over the snapshot and survive future refreshes of it.
   *
   * Only applied when loading the embedded default resource; explicit
   * file/url/resource sources are loaded verbatim with no overlay.
   */
  val DefaultOverridesResourcePath = "/modeldata/llm4s_model_overrides.json"

  /** A `ModelRegistryConfig` that loads from the bundled classpath resource. */
  val default: ModelRegistryConfig = ModelRegistryConfig()
