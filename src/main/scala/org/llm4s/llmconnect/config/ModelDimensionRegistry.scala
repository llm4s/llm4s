package org.llm4s.llmconnect.config

import org.slf4j.LoggerFactory

/**
 * Small registry for known (provider, model) -> dimensions.
 * Falls back to env-provided dims when unknown.
 */
object ModelDimensionRegistry {
  private val logger = LoggerFactory.getLogger(getClass)

  private val table: Map[(String, String), Int] = Map(
    // OpenAI
    ("openai", "text-embedding-3-small") -> 1536,
    ("openai", "text-embedding-3-large") -> 3072,
    ("openai", "text-embedding-ada-002") -> 1536,
    // Voyage
    ("voyage", "voyage-3")      -> 1024,
    ("voyage", "voyage-3-lite") -> 1024
  )

  def getDimension(provider: String, model: String, fallback: Int): Int = {
    val p = Option(provider).map(_.toLowerCase).getOrElse("unknown")
    val m = Option(model).getOrElse("")
    table.get(p -> m).getOrElse {
      logger.debug(s"[ModelDimensionRegistry] Unknown (provider=$p, model=$m). Using fallback=$fallback")
      fallback
    }
  }
}
