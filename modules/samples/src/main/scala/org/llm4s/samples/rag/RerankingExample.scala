package org.llm4s.samples.rag

import org.llm4s.agent.memory.*
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.model.ModelRegistryService
import org.llm4s.reranker.{ LLMReranker, RerankRequest }

import java.nio.file.Files

/**
 * Reranking Example
 *
 * Demonstrates how to:
 * 1. Store documents in a vector database
 * 2. Retrieve documents using vector similarity search
 * 3. Re-rank retrieved results using LLMReranker
 * 4. Compare document ordering before and after reranking
 *
 * Usage:
 *
 * export LLM_MODEL=openai/gpt-4o
 * export OPENAI_API_KEY=sk-...
 *
 * sbt "samples/runMain org.llm4s.samples.rag.RerankingExample"
 */
object RerankingExample extends App {

  val result = for {
    service <- Llm4sConfig.modelRegistryService()
    _       <- runExample(service)
  } yield ()

  result match {
    case Right(_) =>
      println("Reranking example completed successfully.")
    case Left(err) =>
      println(s"Error: ${err.message}")
      System.exit(1)
  }

  def runExample(service: ModelRegistryService) = {
    given ModelRegistryService = service

    val dbPath = Files.createTempFile("llm4s-rerank-", ".db")

    val embeddingService = MockEmbeddingService(dimensions = 1536)

    for {
      store <- VectorMemoryStore(
        dbPath.toString,
        embeddingService,
        MemoryStoreConfig.default
      )

      // --------------------------------------------------
      // Step 1: Store sample documents
      // --------------------------------------------------

      _ <- store.store(
        Memory.fromKnowledge(
          "Scala supports functional programming with immutable data structures.",
          "scala-docs"
        )
      )

      _ <- store.store(
        Memory.fromKnowledge(
          "Scala runs on the JVM and interoperates seamlessly with Java.",
          "scala-docs"
        )
      )

      _ <- store.store(
        Memory.fromKnowledge(
          "Pattern matching is one of Scala's most powerful language features.",
          "scala-docs"
        )
      )

      _ <- store.store(
        Memory.fromKnowledge(
          "Scala provides advanced type system capabilities.",
          "scala-docs"
        )
      )

      _ <- store.store(
        Memory.fromKnowledge(
          "Functional programming emphasizes pure functions and immutability.",
          "scala-docs"
        )
      )

      _ <- store.store(
        Memory.fromKnowledge(
          "Java is a widely used object-oriented programming language.",
          "java-docs"
        )
      )

      // --------------------------------------------------
      // Step 2: Vector Search
      // --------------------------------------------------

      query = "What Scala features support functional programming?"

      retrieved <- store.search(
        query,
        5,
        MemoryFilter.ByType(MemoryType.Knowledge)
      )

      _ = println("\n===== BEFORE RERANKING =====")

      _ = retrieved.zipWithIndex.foreach { case (result, idx) =>
        println(
          s"${idx + 1}. score=${result.score} | ${result.memory.content.take(80)}"
        )
      }

      // --------------------------------------------------
      // Step 3: Create LLM client
      // --------------------------------------------------

      providerCfg <- Llm4sConfig.defaultProvider()
      client      <- LLMConnect.getClient(providerCfg)

      // --------------------------------------------------
      // Step 4: Re-rank
      // --------------------------------------------------

      documents = retrieved.map(_.memory.content)

      reranker = LLMReranker(client)

      rerankResponse <- reranker.rerank(
        RerankRequest(
          query = query,
          documents = documents,
          topK = Some(5)
        )
      )

      // --------------------------------------------------
      // Step 5: Compare results
      // --------------------------------------------------

      _ = println("\n===== AFTER RERANKING =====")

      _ = rerankResponse.results.zipWithIndex.foreach { case (result, idx) =>
        println(
          s"${idx + 1}. score=${result.score} | ${result.document.take(80)}"
        )
      }

      _ = store.close()

    } yield ()
  }
}
