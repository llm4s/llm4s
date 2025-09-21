package org.llm4s.llmconnect.config

object ModelDimensionRegistry {

  private val dimensions: Map[String, Map[String, Int]] = Map(
    "openai" -> Map(
      "text-embedding-3-small" -> 1536,
      "text-embedding-3-large" -> 3072
    ),
    "voyage" -> Map(
      "voyage-2"         -> 1024,
      "voyage-3-large"   -> 1536,
      "voyage-3.5"       -> 1024,
      "voyage-3.5-lite"  -> 1024,
      "voyage-code-3"    -> 1024,
      "voyage-finance-2" -> 1024,
      "voyage-law-2"     -> 1024,
      "voyage-code-2"    -> 1536,
      "voyage-context-3" -> 1024
    ),
    // NEW: local (non-text) "model" dims used by our stubs or future local encoders
    "local" -> Map(
      "openclip-vit-b32" -> 512,
      "wav2vec2-base"    -> 768,
      "timesformer-base" -> 768
    )
  )

  def getDimension(provider: String, model: String): Int =
    dimensions
      .getOrElse(provider.toLowerCase, Map.empty)
      .getOrElse(
        model,
        throw new IllegalArgumentException(
          s"[ModelDimensionRegistry] Unknown model '$model' for provider '$provider'"
        )
      )
}
