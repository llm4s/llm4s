package org.llm4s.samples.embeddingsupport

import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.{EmbeddingConfig, EmbeddingModelConfig}
import org.llm4s.llmconnect.model.EmbeddingRequest

object EmbeddingExample extends App {

  val activeProvider = EmbeddingConfig.activeProvider.toLowerCase
  val model = activeProvider match {
    case "openai" =>
      EmbeddingModelConfig(EmbeddingConfig.openAI.model, 1536) 
      EmbeddingModelConfig(EmbeddingConfig.voyage.model, 1024)
    case other =>
      throw new RuntimeException(s"Unsupported provider: $other")
  }

  val inputText = Seq("Gopi is contributing to Google Summer of Code 2025.")

  val request = EmbeddingRequest(inputText, model)
  val provider = EmbeddingClient.fromConfig()

  provider.embed(request) match {
    case Right(response) =>
      println(s"Embedding received from [$activeProvider]:")
      response.vectors.zipWithIndex.foreach { case (vec, i) =>
        println(s"[$i] -> [${vec.mkString(", ")}]")
      }

    case Left(error) =>
      println(s"Embedding failed from [${error.provider}]: ${error.message}")
      error.code.foreach(code => println(s"Status code: $code"))
  }
}
