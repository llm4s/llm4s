package org.llm4s.llmconnect.config

object ModelDimensionRegistry {

  private val OpenAIModels: Map[String, Int] = Map(
    "text-embedding-3-small" -> 1536,
    "text-embedding-3-large" -> 3072
  )

  private val VoyageModels: Map[String, Int] = Map(
    "voyage-3-large"   -> 1024,
    "voyage-3.5"       -> 1024,
    "voyage-3.5-lite"  -> 1024,
    "voyage-code-3"    -> 1024,
    "voyage-finance-2" -> 1024,
    "voyage-law-2"     -> 1024,
    "voyage-code-2"    -> 1536,
    "voyage-context-3" -> 1024
  )

  def getDimensions(provider: String, model: String): Int =
    provider match {
      case "openai" =>
        OpenAIModels.getOrElse(
          model,
          throw new RuntimeException(s"Unknown model: [$model] for provider: [$provider]")
        )
      case "voyage" =>
        VoyageModels.getOrElse(
          model,
          throw new RuntimeException(s"Unknown model: [$model] for provider: [$provider]")
        )
      case other =>
        throw new RuntimeException(s"Unsupported provider: $other")
    }
}
