package org.llm4s.config
import org.llm4s.config.ConfigKeys._

trait DefaultConfig extends ConfigReader {
  val DEFAULT_OPENAI_BASE_URL           = "https://api.openai.com/v1"
  val DEFAULT_ANTHROPIC_BASE_URL        = "https://api.anthropic.com"
  val DEFAULT_LANGFUSE_URL              = "https://cloud.langfuse.com/api/public/ingestion"
  val DEFAULT_LANGFUSE_ENV              = "production"
  val DEFAULT_LANGFUSE_RELEASE          = "1.0.0"
  val DEFAULT_LANGFUSE_VERSION          = "1.0.0"
  val DEFAULT_AZURE_V2025_01_01_PREVIEW = "V2025_01_01_PREVIEW"
}

object DefaultConfig extends DefaultConfig {
  private val defaults: Map[String, String] = Map(
    OPENAI_BASE_URL    -> DEFAULT_OPENAI_BASE_URL,
    ANTHROPIC_BASE_URL -> DEFAULT_ANTHROPIC_BASE_URL,
    LANGFUSE_URL       -> DEFAULT_LANGFUSE_URL,
    LANGFUSE_ENV       -> DEFAULT_LANGFUSE_ENV,
    LANGFUSE_RELEASE   -> DEFAULT_LANGFUSE_RELEASE,
    LANGFUSE_VERSION   -> DEFAULT_LANGFUSE_VERSION
  )

  override def get(key: String): Option[String] = defaults.get(key)
}
