package org.llm4s.samples.embeddingsupport

import org.llm4s.llmconnect.config.EmbeddingConfig
import org.llm4s.llmconnect.extractors.UniversalExtractor
import org.llm4s.llmconnect.model.{EmbeddingRequest, ExtractorError}
import org.llm4s.llmconnect.utils.{ChunkingUtils, SimilarityUtils}
import org.llm4s.llmconnect.EmbeddingClient
import org.slf4j.LoggerFactory
import org.llm4s.config.ConfigReader.LLMConfig

object EmbeddingExample {

  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    logger.info("Starting embedding example...")

    val config = LLMConfig()
    val inputPath = EmbeddingConfig.inputPath(config)
    val query     = EmbeddingConfig.query(config)

    logger.info(s"Extracting from: $inputPath")
    val extractedEither = UniversalExtractor.extract(inputPath)

    extractedEither match {
      case Left(error: ExtractorError) =>
        logger.error(s"[ExtractorError] ${error.message} (type: ${error.`type`}, path: ${error.path})")
        return

      case Right(text) =>
        val inputs: Seq[String] = if (EmbeddingConfig.chunkingEnabled(config)) {
          logger.info(s"\nChunking enabled. Using size=${EmbeddingConfig.chunkSize(config)}, overlap=${EmbeddingConfig.chunkOverlap(config)}")
          ChunkingUtils.chunkText(text, EmbeddingConfig.chunkSize(config), EmbeddingConfig.chunkOverlap(config))
        } else {
          logger.info("\nChunking disabled. Proceeding with full text.")
          Seq(text)
        }

        logger.info(s"\nGenerating embedding for ${inputs.size} input(s)...")

        val request = EmbeddingRequest(
          input = inputs :+ query,  // include query for similarity
          model = org.llm4s.llmconnect.utils.ModelSelector.selectModel(config)
        )

        val client = EmbeddingClient.fromConfig(config)
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
