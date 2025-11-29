package org.llm4s.samples.embeddingsupport

import org.llm4s.config.Llm4sConfig
import org.llm4s.types.Result

final case class EmbeddingRuntimeSettings(
  provider: String,
  chunkingEnabled: Boolean,
  chunkSize: Int,
  chunkOverlap: Int
)

object EmbeddingRuntimeSettings {

  def apply(): Result[EmbeddingRuntimeSettings] =
    for {
      emb      <- Llm4sConfig.embeddings()
      chunking <- Llm4sConfig.embeddingsChunking()
      (prov, _) = emb
    } yield EmbeddingRuntimeSettings(
      provider = prov,
      chunkingEnabled = chunking.enabled,
      chunkSize = chunking.size,
      chunkOverlap = chunking.overlap
    )
}
