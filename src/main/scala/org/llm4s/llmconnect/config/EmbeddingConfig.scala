package org.llm4s.llmconnect.config

case class EmbeddingProviderConfig(
  baseUrl: String,
  apiKey: String,
  textModel: Option[String]
)

object EmbeddingConfig {
  private def req(name: String): String =
    sys.env.getOrElse(name, throw new RuntimeException(s"Missing env variable: $name"))

  private def opt(name: String): Option[String] = sys.env.get(name).filter(_.nonEmpty)

  // Provider routing
  val activeProvider: String = req("EMBEDDING_PROVIDER").toLowerCase

  // Dimensions (env-driven)
  val textDims: Int = req("EMBEDDING_TEXT_DIMENSIONS").toInt

  // Demo/sample input
  val inputPath: String = req("EMBEDDING_INPUT_PATH")
  val query: String     = sys.env.getOrElse("EMBEDDING_QUERY", "")

  // Provider configs (TEXT ONLY)
  val openAI: EmbeddingProviderConfig = EmbeddingProviderConfig(
    baseUrl = req("OPENAI_EMBEDDING_BASE_URL"),
    apiKey = req("OPENAI_API_KEY"),
    textModel = opt("OPENAI_TEXT_MODEL")
  )

  val voyage: EmbeddingProviderConfig = EmbeddingProviderConfig(
    baseUrl = req("VOYAGE_EMBEDDING_BASE_URL"),
    apiKey = req("VOYAGE_API_KEY"),
    textModel = opt("VOYAGE_TEXT_MODEL")
  )

  // Extractor knobs (for text files)
  val maxBytes: Long     = sys.env.getOrElse("UNIVERSAL_MAX_BYTES", "10485760").toLong // 10MB
  val httpTimeoutMs: Int = sys.env.getOrElse("UNIVERSAL_HTTP_TIMEOUT_MS", "20000").toInt
  val maxPdfPages: Int   = sys.env.getOrElse("UNIVERSAL_MAX_PDF_PAGES", "200").toInt
}
