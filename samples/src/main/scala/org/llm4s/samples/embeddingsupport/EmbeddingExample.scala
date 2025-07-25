package org.llm4s.samples.embeddingsupport

import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.EmbeddingConfig
import org.llm4s.llmconnect.extractors.UniversalExtractor
import org.llm4s.llmconnect.model.EmbeddingRequest
import org.llm4s.llmconnect.utils.{ ModelSelector, SimilarityUtils }

object EmbeddingExample extends App {

  val provider = EmbeddingConfig.activeProvider.toLowerCase

  // Step 1: Extract input and query text
  val extractedText = UniversalExtractor.extract(EmbeddingConfig.inputPath)
  val query = EmbeddingConfig.query

  // Step 2: Dynamically select the model based on input text and provider
  val selectedModel = ModelSelector.selectModel(provider, extractedText)

  // Step 3: Create embedding request
  val request = EmbeddingRequest(Seq(extractedText, query), model = selectedModel)

  // Step 4: Load embedding provider and get response
  val embeddingProvider = EmbeddingClient.fromConfig()

  embeddingProvider.embed(request) match {
    case Right(response) =>
      val docVec = response.vectors.head
      val queryVec = response.vectors.last
      val score = SimilarityUtils.cosineSimilarity(docVec, queryVec)

      println(s"\nProvider: $provider")
      println(s"Model Used: ${selectedModel.name}")
      println(f"Similarity Score: $score%.4f")
      println(s"Top 10 values of docVec: ${docVec.take(10).mkString(", ")}")

    case Left(error) =>
      println(s"\nEmbedding failed from [${error.provider}]: ${error.message}")
      error.code.foreach(code => println(s"Status code: $code"))
  }
}
