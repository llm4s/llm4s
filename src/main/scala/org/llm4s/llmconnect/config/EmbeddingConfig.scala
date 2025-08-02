package org.llm4s.llmconnect.config

import org.llm4s.config.EnvLoader

case class EmbeddingProviderConfig(
  baseUrl: String,
  model: String,
  apiKey: String
)

object EmbeddingConfig {

  def loadEnv(name: String): String =
    EnvLoader.get(name).getOrElse(throw new RuntimeException(s"Missing env variable: $name"))

  def loadOptionalEnv(name: String, default: String): String =
    EnvLoader.get(name).getOrElse(default)

  val openAI: EmbeddingProviderConfig = EmbeddingProviderConfig(
    baseUrl = loadEnv("OPENAI_EMBEDDING_BASE_URL"),
    model = loadEnv("OPENAI_EMBEDDING_MODEL"),
    apiKey = loadEnv("OPENAI_API_KEY")
  )

  val voyage: EmbeddingProviderConfig = EmbeddingProviderConfig(
    baseUrl = loadEnv("VOYAGE_EMBEDDING_BASE_URL"),
    model = loadEnv("VOYAGE_EMBEDDING_MODEL"),
    apiKey = loadEnv("VOYAGE_API_KEY")
  )

  val activeProvider: String = loadEnv("EMBEDDING_PROVIDER")
  val inputPath: String      = loadEnv("EMBEDDING_INPUT_PATH")
  val query: String          = loadEnv("EMBEDDING_QUERY")

  // 🔁 Chunking support (with default fallbacks)
  val chunkSize: Int           = loadOptionalEnv("CHUNK_SIZE", "1000").toInt
  val chunkOverlap: Int        = loadOptionalEnv("CHUNK_OVERLAP", "100").toInt
  val chunkingEnabled: Boolean = loadOptionalEnv("CHUNKING_ENABLED", "true").toBoolean
}
