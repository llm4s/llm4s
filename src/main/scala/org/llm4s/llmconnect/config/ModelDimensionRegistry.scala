package org.llm4s.llmconnect.config

object ModelDimensionRegistry {

  private val dimensions: Map[String, Map[String, Int]] = Map(
    "openai" -> Map(
      "text-embedding-3-small" -> 1536,
      "text-embedding-3-large" -> 3072
    ),
    "voyage" -> Map(
      "voyage-2"       -> 1024,
      "voyage-3-large" -> 1536
    )
    // Add more providers and models here
  )

  def getDimension(provider: String, model: String): Int =
    dimensions
      .getOrElse(provider.toLowerCase, Map.empty)
      .getOrElse(
        model,
        throw new IllegalArgumentException(
          s"\n[ModelDimensionRegistry] Unknown model '$model' for provider '$provider'"
        )
      )
}
