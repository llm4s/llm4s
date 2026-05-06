package org.llm4s.model

final case class ModelRegistryConfig(
  resourcePath: Option[String] = Some(ModelRegistryConfig.DefaultResourcePath),
  filePath: Option[String] = None,
  url: Option[String] = None
)

object ModelRegistryConfig:
  val DefaultResourcePath = "/modeldata/litellm_model_metadata.json"

  val default: ModelRegistryConfig = ModelRegistryConfig()
