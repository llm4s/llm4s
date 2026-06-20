package org.llm4s.model

final case class ModelRegistryConfig(
  resourcePath: Option[String] = Some(ModelRegistryConfig.DefaultResourcePath),
  filePath: Option[String] = None,
  url: Option[String] = None
)

object ModelRegistryConfig:
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

  val default: ModelRegistryConfig = ModelRegistryConfig()
