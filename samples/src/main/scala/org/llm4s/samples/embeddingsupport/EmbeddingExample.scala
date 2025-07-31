package org.llm4s.samples.embeddingsupport

import org.llm4s.llmconnect.config.EmbeddingConfig
import org.llm4s.llmconnect.extractors.UniversalExtractor
import org.llm4s.llmconnect.model.{EmbeddingRequest, ExtractorError}
import org.llm4s.llmconnect.utils.{ChunkingUtils, SimilarityUtils, LoggerUtils}
import org.llm4s.llmconnect.EmbeddingClient

object EmbeddingExample {

  def main(args: Array[String]): Unit = {
    LoggerUtils.info("Starting embedding example...")

    val inputPath = EmbeddingConfig.inputPath
    val query     = EmbeddingConfig.query

    LoggerUtils.info(s"Extracting from: $inputPath")
    val extractedEither = UniversalExtractor.extract(inputPath)

    extractedEither match {
      case Left(error: ExtractorError) =>
        LoggerUtils.error(s"[ExtractorError] ${error.message} (type: ${error.`type`}, path: ${error.path})")
        return

      case Right(text) =>
        val inputs: Seq[String] = if (EmbeddingConfig.chunkingEnabled) {
          LoggerUtils.info(s"Chunking enabled. Using size=${EmbeddingConfig.chunkSize}, overlap=${EmbeddingConfig.chunkOverlap}")
          ChunkingUtils.chunkText(text, EmbeddingConfig.chunkSize, EmbeddingConfig.chunkOverlap)
        } else {
          LoggerUtils.info("Chunking disabled. Proceeding with full text.")
          Seq(text)
        }

        LoggerUtils.info(s"Generating embedding for ${inputs.size} input(s)...")

        val request = EmbeddingRequest(
          input = inputs :+ query,  // include query for similarity
          model = org.llm4s.llmconnect.utils.ModelSelector.selectModel()
        )

        val client = EmbeddingClient.fromConfig()
        val response = client.embed(request)

        response match {
          case Right(result) =>
            LoggerUtils.info(s"Embedding response metadata:\n${result.metadata}")

            // Log each embedding vector (first 10 dims only for brevity)
            result.embeddings.zipWithIndex.foreach { case (vec, idx) =>
              val label = if (idx < inputs.size) s"Chunk ${idx + 1}" else "Query"
              LoggerUtils.info(s"[$label] Embedding: ${vec.take(10).mkString(", ")} ... [${vec.length} dims]")
            }

            // Log cosine similarity between first chunk and query
            val similarity = SimilarityUtils.cosineSimilarity(
              result.embeddings.head,
              result.embeddings.last
            )
            LoggerUtils.info(f"Cosine similarity between first doc chunk and query: $similarity%.4f")

          case Left(err) =>
            LoggerUtils.error(s"[EmbeddingError] ${err.provider}: ${err.message}")
        }
    }
  }
}
