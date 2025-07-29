package org.llm4s.llmconnect.config

case class EmbeddingProviderConfig(
  baseUrl: String,
  model: String,
  apiKey: String
)

object EmbeddingConfig {
  def loadEnv(name: String): String =
    sys.env.getOrElse(name, throw new RuntimeException(s"Missing env variable: $name"))

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
}
