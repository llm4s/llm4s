package org.llm4s.samples.embeddingsupport

import org.llm4s.llmconnect.config.EmbeddingConfig
import org.llm4s.llmconnect.extractors.UniversalExtractor
import org.llm4s.llmconnect.model.{EmbeddingRequest, ExtractorError}
import org.llm4s.llmconnect.utils.{ChunkingUtils, SimilarityUtils}
import org.llm4s.llmconnect.EmbeddingClient
import org.slf4j.LoggerFactory

object EmbeddingExample {

  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    logger.info("Starting embedding example...")

    val inputPath = EmbeddingConfig.inputPath
    val query     = EmbeddingConfig.query

    logger.info(s"Extracting from: $inputPath")
    val extractedEither = UniversalExtractor.extract(inputPath)

    extractedEither match {
      case Left(error: ExtractorError) =>
        logger.error(s"[ExtractorError] ${error.message} (type: ${error.`type`}, path: ${error.path})")
        return

      case Right(text) =>
        val inputs: Seq[String] = if (EmbeddingConfig.chunkingEnabled) {
          logger.info(s"\nChunking enabled. Using size=${EmbeddingConfig.chunkSize}, overlap=${EmbeddingConfig.chunkOverlap}")
          ChunkingUtils.chunkText(text, EmbeddingConfig.chunkSize, EmbeddingConfig.chunkOverlap)
        } else {
          logger.info("\nChunking disabled. Proceeding with full text.")
          Seq(text)
        }

        logger.info(s"\nGenerating embedding for ${inputs.size} input(s)...")

        val request = EmbeddingRequest(
          input = inputs :+ query,  // include query for similarity
          model = org.llm4s.llmconnect.utils.ModelSelector.selectModel()
        )

        val client = EmbeddingClient.fromConfig()
        val response = client.embed(request)

        response match {
          case Right(result) =>
            logger.info(s"\nEmbedding response metadata:\n${result.metadata}")

            // Log each embedding vector (first 10 dims only for brevity)
            result.embeddings.zipWithIndex.foreach { case (vec, idx) =>
              val label = if (idx < inputs.size) s"Chunk ${idx + 1}" else "Query"
              logger.info(s"\n[$label] Embedding: ${vec.take(10).mkString(", ")} ... [${vec.length} dims]")
            }

            // Log cosine similarity between first chunk and query
            val similarity = SimilarityUtils.cosineSimilarity(
              result.embeddings.head,
              result.embeddings.last
            )
            logger.info(f"\nCosine similarity between first doc chunk and query: $similarity%.4f")

          case Left(err) =>
            logger.error(s"\n[EmbeddingError] ${err.provider}: ${err.message}")
        }
    }
  }
}
