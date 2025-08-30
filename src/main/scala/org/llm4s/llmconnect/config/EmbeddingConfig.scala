package org.llm4s.llmconnect.config

import org.llm4s.config.{ ConfigKeys, ConfigReader }
import ConfigKeys._

case class EmbeddingProviderConfig(
  baseUrl: String,
  model: String,
  apiKey: String
)

object EmbeddingConfig {

  def loadEnv(name: String)(config: ConfigReader): String =
    config.get(name).getOrElse(throw new RuntimeException(s"Missing env variable: $name"))

  def loadOptionalEnv(name: String, default: String)(config: ConfigReader): String =
    config.get(name).getOrElse(default)

  def openAI(config: ConfigReader): EmbeddingProviderConfig = EmbeddingProviderConfig(
    baseUrl = loadEnv(OPENAI_EMBEDDING_BASE_URL)(config),
    model = loadEnv(OPENAI_EMBEDDING_MODEL)(config),
    apiKey = loadEnv(OPENAI_API_KEY)(config)
  )

  def voyage(config: ConfigReader): EmbeddingProviderConfig = EmbeddingProviderConfig(
    baseUrl = loadEnv(VOYAGE_EMBEDDING_BASE_URL)(config),
    model = loadEnv(VOYAGE_EMBEDDING_MODEL)(config),
    apiKey = loadEnv(VOYAGE_API_KEY)(config)
  )

  def activeProvider(config: ConfigReader): String = loadEnv(EMBEDDING_PROVIDER)(config)
  def inputPath(config: ConfigReader): String      = loadEnv(EMBEDDING_INPUT_PATH)(config)
  def query(config: ConfigReader): String          = loadEnv(EMBEDDING_QUERY)(config)

  // 🔁 Chunking support (with default fallbacks)
  def chunkSize(config: ConfigReader): Int           = loadOptionalEnv(CHUNK_SIZE, "1000")(config).toInt
  def chunkOverlap(config: ConfigReader): Int        = loadOptionalEnv(CHUNK_OVERLAP, "100")(config).toInt
  def chunkingEnabled(config: ConfigReader): Boolean = loadOptionalEnv(CHUNKING_ENABLED, "true")(config).toBoolean
}
