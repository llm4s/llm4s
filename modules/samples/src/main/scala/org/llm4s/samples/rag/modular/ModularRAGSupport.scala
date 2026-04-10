package org.llm4s.samples.rag.modular

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.EmbeddingProviderConfig
import org.llm4s.rag.EmbeddingProvider
import org.llm4s.types.Result

object ModularRAGSupport {

  def toEmbeddingProvider(providerName: String): Result[EmbeddingProvider] =
    EmbeddingProvider
      .fromString(providerName)
      .toRight(
        ConfigurationError(
          s"Unsupported embedding provider '$providerName'. Supported: ${EmbeddingProvider.values.map(_.name).mkString(", ")}"
        )
      )

  def resolveEmbeddingProviderConfig(
    requestedProvider: String,
    configuredProvider: String,
    embeddingCfg: EmbeddingProviderConfig
  ): Result[EmbeddingProviderConfig] =
    if (requestedProvider.equalsIgnoreCase(configuredProvider)) {
      Right(embeddingCfg)
    } else {
      Left(
        ConfigurationError(
          s"RAG requested embedding provider '$requestedProvider', but sample is configured for '$configuredProvider'."
        )
      )
    }

  def seedCorpus(ingestion: IngestionModule): Result[Int] = {
    val docs = Seq(
      (
        "rag-intro",
        "Retrieval-Augmented Generation combines retrieval with generation to ground answers in trusted context.",
        Map("source" -> "seed/rag-intro.txt")
      ),
      (
        "llm4s-reliability",
        "In production, RAG improves reliability by forcing answers to reference retrieved passages instead of model priors.",
        Map("source" -> "seed/llm4s-reliability.txt")
      ),
      (
        "architecture",
        "A modular RAG architecture usually separates ingestion, retrieval, and generation so each stage can evolve independently.",
        Map("source" -> "seed/architecture.txt")
      )
    )

    docs.foldLeft[Result[Int]](Right(0)) { case (acc, (docId, content, metadata)) =>
      for {
        total <- acc
        added <- ingestion.ingestText(docId, content, metadata)
      } yield total + added
    }
  }
}
