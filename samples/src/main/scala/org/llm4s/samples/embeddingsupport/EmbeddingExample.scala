package org.llm4s.samples.embeddingsupport

import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.model.EmbeddingRequest

import scala.io.StdIn.readLine

object EmbeddingExample extends App {
  println("Select provider [openai/voyage]:")
  val providerInput = readLine().toLowerCase.trim

  val client = EmbeddingClient.fromProvider(providerInput)

  val model = providerInput match {
    case "openai" => EmbeddingModelConfig("text-embedding-3-small", 1536)
    case "voyage" => EmbeddingModelConfig("voyage-2", 1024)
    case other    => throw new RuntimeException(s"Unsupported provider: $other")
  }

  val input = Seq("Gopi is contributing to GSoC 2025.")
  val request = EmbeddingRequest(input, model)

  client.embed(request) match {
    case Right(response) =>
      println(s"Embeddings from [$providerInput]:")
      response.vectors.zipWithIndex.foreach { case (vec, i) =>
        println(s"[$i]: ${vec.take(10).mkString(", ")} ...") // print partial vector
      }

    case Left(error) =>
      println(s"Embedding failed for [$providerInput]: ${error.message}")
      error.code.foreach(code => println(s"Status code: $code"))
  }
}
