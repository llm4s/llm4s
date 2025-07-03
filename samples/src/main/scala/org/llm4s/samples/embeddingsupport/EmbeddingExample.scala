package org.llm4s.samples.embeddingsupport

import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.{EmbeddingConfig, EmbeddingModelConfig}
import org.llm4s.llmconnect.model.EmbeddingRequest

import java.io.{File, PrintWriter}

object EmbeddingExample extends App {

  // Load model name from active provider config (no hardcoding here)
  val activeProvider = EmbeddingConfig.activeProvider.toLowerCase
  val model = activeProvider match {
    case "openai" =>
      EmbeddingModelConfig(EmbeddingConfig.openAI.model, 1536)
    case "voyage" =>
      EmbeddingModelConfig(EmbeddingConfig.voyage.model, 1024)
    case other =>
      throw new RuntimeException(s"Unsupported provider: $other")
  }

  // Input to embed
  val inputText = Seq("G")

  val request = EmbeddingRequest(inputText, model)
  val provider = EmbeddingClient.fromConfig()

  provider.embed(request) match {
    case Right(response) =>
      println(s"Embedding received from [$activeProvider]:")

      // Print preview (first 10 values) to console
      response.vectors.zipWithIndex.foreach { case (vec, i) =>
        val preview = vec.take(10).mkString(", ")
        println(s"[$i] -> [$preview ...] (total length: ${vec.length})")
      }


    case Left(error) =>
      println(s"Embedding failed from [${error.provider}]: ${error.message}")
      error.code.foreach(code => println(s"Status code: $code"))
  }
}
