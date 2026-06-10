package org.llm4s.model

/**
 * Configuration controlling how the model registry data is loaded.
 *
 * @param resourcePath Optional classpath resource path to load model metadata from; defaults to the bundled LiteLLM metadata file.
 * @param filePath     Optional filesystem path to a model metadata JSON file, overrides `resourcePath` when set.
 * @param url          Optional HTTP URL to fetch model metadata from, overrides both `resourcePath` and `filePath` when set.
 */
final case class ModelRegistryConfig(
  resourcePath: Option[String] = Some(ModelRegistryConfig.DefaultResourcePath),
  filePath: Option[String] = None,
  url: Option[String] = None
)

/** Companion object providing defaults for `ModelRegistryConfig`. */
object ModelRegistryConfig:
  /** Classpath resource path to the bundled LiteLLM model metadata JSON file. */
  val DefaultResourcePath = "/modeldata/litellm_model_metadata.json"

  /** A `ModelRegistryConfig` that loads from the bundled classpath resource. */
  val default: ModelRegistryConfig = ModelRegistryConfig()
