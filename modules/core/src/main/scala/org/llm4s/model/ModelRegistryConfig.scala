package org.llm4s.model

import org.llm4s.annotation.Stable

/**
 * Configuration controlling how the model registry data is loaded.
 *
 * @param resourcePath Optional classpath resource path to load model metadata from; defaults to the bundled LiteLLM metadata file.
 * @param filePath     Optional filesystem path to a model metadata JSON file, overrides `resourcePath` when set.
 * @param url          Optional HTTP URL to fetch model metadata from, overrides both `resourcePath` and `filePath` when set.
 *
 * Build one with [[ModelRegistryConfig.apply]] (or start from [[ModelRegistryConfig.default]]) and change it with the
 * `with*` setters, each of which takes the value or an `Option`; the constructor and `copy` are private, so a new field
 * never breaks callers.
 */
@Stable
final case class ModelRegistryConfig private (
  resourcePath: Option[String],
  filePath: Option[String],
  url: Option[String]
):
  /** Sets the classpath resource to load model metadata from. */
  def withResourcePath(resourcePath: String): ModelRegistryConfig = copy(resourcePath = Some(resourcePath))

  /** Sets, or with `None` clears, the classpath resource to load model metadata from. */
  def withResourcePath(resourcePath: Option[String]): ModelRegistryConfig = copy(resourcePath = resourcePath)

  /** Sets the filesystem path to load model metadata from; it overrides `resourcePath`. */
  def withFilePath(filePath: String): ModelRegistryConfig = copy(filePath = Some(filePath))

  /** Sets, or with `None` clears, the filesystem path to load model metadata from. */
  def withFilePath(filePath: Option[String]): ModelRegistryConfig = copy(filePath = filePath)

  /** Sets the HTTP URL to fetch model metadata from; it overrides `resourcePath` and `filePath`. */
  def withUrl(url: String): ModelRegistryConfig = copy(url = Some(url))

  /** Sets, or with `None` clears, the HTTP URL to fetch model metadata from. */
  def withUrl(url: Option[String]): ModelRegistryConfig = copy(url = url)

/** Companion object providing defaults for `ModelRegistryConfig`. */
object ModelRegistryConfig:

  /**
   * Creates a [[ModelRegistryConfig]]; with no arguments, it loads the bundled classpath resource.
   *
   * @param resourcePath classpath resource path; defaults to [[DefaultResourcePath]]
   * @param filePath     filesystem path; overrides `resourcePath` when set
   * @param url          HTTP URL; overrides both `resourcePath` and `filePath` when set
   */
  def apply(
    resourcePath: Option[String] = Some(DefaultResourcePath),
    filePath: Option[String] = None,
    url: Option[String] = None
  ): ModelRegistryConfig =
    new ModelRegistryConfig(resourcePath, filePath, url)

  /** Classpath resource path to the bundled LiteLLM model metadata JSON file. */
  val DefaultResourcePath = "/modeldata/litellm_model_metadata.json"

  /**
   * Curated llm4s corrections layered on top of the vendored LiteLLM snapshot
   * (see [[DefaultResourcePath]]). The snapshot is a verbatim upstream copy and
   * occasionally drops or mis-shapes entries llm4s relies on (e.g. a chat model
   * regressing to an `embedding` row, or a canonical alias being removed so a
   * substring fallback resolves to a third-party-hosted variant). Entries here
   * take precedence over the snapshot and survive future refreshes of it.
   *
   * Only applied when loading the embedded default resource; explicit
   * file/url/resource sources are loaded verbatim with no overlay.
   */
  val DefaultOverridesResourcePath = "/modeldata/llm4s_model_overrides.json"

  /** A `ModelRegistryConfig` that loads from the bundled classpath resource. */
  val default: ModelRegistryConfig = ModelRegistryConfig()
