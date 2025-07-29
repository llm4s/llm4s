package org.llm4s.samples.embeddingsupport

import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.{EmbeddingConfig, EmbeddingModelConfig}
import org.llm4s.llmconnect.extractors.UniversalExtractor
import org.llm4s.llmconnect.model.EmbeddingRequest
import org.llm4s.llmconnect.utils.SimilarityUtils

object EmbeddingExample extends App {

  val activeProvider = EmbeddingConfig.activeProvider.toLowerCase
  val model = activeProvider match {
    case "openai"  => EmbeddingModelConfig(EmbeddingConfig.openAI.model, 1536)
    case "voyage"  => EmbeddingModelConfig(EmbeddingConfig.voyage.model, 1024)
    case other     => throw new RuntimeException(s"Unsupported provider: $other")
  }

  val extractedText = UniversalExtractor.extract(EmbeddingConfig.inputPath)
  val query = EmbeddingConfig.query

  val request = EmbeddingRequest(Seq(extractedText, query), model)
  val provider = EmbeddingClient.fromConfig()

  provider.embed(request) match {
    case Right(response) =>
      val docVec = response.vectors.head
      val queryVec = response.vectors.last
      val score = SimilarityUtils.cosineSimilarity(docVec, queryVec)

      println(s"Similarity Score: $score")
      println(s"Top 10 values of docVec: ${docVec.take(10).mkString(", ")}")

    case Left(error) =>
      println(s"Embedding failed from [${error.provider}]: ${error.message}")
      error.code.foreach(code => println(s"Status code: $code"))
  }
}
