package org.llm4s.samples.rag.modular

import org.llm4s.chunking.ChunkerFactory
import org.llm4s.config.Llm4sConfig
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.rag.{ RAG, RAGSearchResult }
import org.llm4s.rag.RAG.RAGConfigOps
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import java.io.File

/**
 * Modular RAG Example
 *
 * Demonstrates a practical RAG architecture split into independent modules:
 * 1) IngestionModule
 * 2) RetrievalModule
 * 3) GenerationModule
 *
 * Usage:
 *   sbt "samples/runMain org.llm4s.samples.rag.modular.ModularRAGExample"
 *
 *   # Use your own directory (supports .txt/.md/.pdf/.docx via RAG.ingest)
 *   sbt "samples/runMain org.llm4s.samples.rag.modular.ModularRAGExample ./docs \"What does this system do?\""
 */
object ModularRAGExample extends App {
  private val logger        = LoggerFactory.getLogger(getClass)
  private val defaultTopK   = 4
  private val defaultPrompt = "How does this RAG pipeline reduce hallucinations?"

  private val docsPathArg = args.headOption
  private val queryArg    = args.lift(1).getOrElse(defaultPrompt)

  val outcome = buildModules().flatMap { case (rag, ingestion, retrieval, generation) =>
    try runDemo(ingestion, retrieval, generation, docsPathArg, queryArg)
    finally rag.close()
  }

  outcome match {
    case Right(_) =>
      logger.info("Modular RAG example finished successfully.")
    case Left(err) =>
      logger.error("Modular RAG example failed: {}", err.formatted)
      System.exit(1)
  }

  private def runDemo(
    ingestion: IngestionModule,
    retrieval: RetrievalModule,
    generation: Option[GenerationModule],
    docsPath: Option[String],
    question: String
  ): Result[Unit] =
    for {
      chunksIngested <- ingestKnowledge(ingestion, docsPath)
      _ = logger.info("Ingested {} chunks into the RAG index.", chunksIngested)
      retrieved <- retrieval.retrieve(question, defaultTopK)
      _ = printRetrievedContexts(retrieved)
      _ <- generation match {
        case Some(generationModule) =>
          generationModule.answer(question, defaultTopK).map { answer =>
            logger.info("Grounded answer:\n{}", answer.answer)
          }
        case None =>
          logger.info("No LLM provider configured. Retrieval succeeded; answer generation skipped.")
          Right(())
      }
    } yield ()

  private[modular] def ingestKnowledge(ingestion: IngestionModule, docsPath: Option[String]): Result[Int] =
    docsPath match {
      case Some(path) if new File(path).exists() =>
        logger.info("Ingesting documents from path: {}", path)
        ingestion.ingestPath(path, metadata = Map("sourceType" -> "filesystem"))
      case Some(path) =>
        Left(ConfigurationError(s"Document path does not exist: $path"))
      case None =>
        logger.info("No path provided; ingesting bundled sample corpus.")
        ModularRAGSupport.seedCorpus(ingestion)
    }

  private def printRetrievedContexts(contexts: Seq[RAGSearchResult]): Unit =
    if (contexts.isEmpty) {
      logger.info("No contexts were retrieved for the query.")
      ()
    } else {
      logger.info("Retrieved {} context chunks:", contexts.size)
      contexts.zipWithIndex.foreach { case (ctx, index) =>
        val source = ctx.metadata.getOrElse("source", "unknown")
        logger.info(
          "[{}] score={} source={} text={}...",
          Int.box(index + 1),
          Double.box(ctx.score),
          source,
          ctx.content.take(100)
        )
      }
    }

  private def buildModules(): Result[(RAG, IngestionModule, RetrievalModule, Option[GenerationModule])] = {
    val maybeLlm = loadOptionalLlmClient()

    for {
      providerTuple <- Llm4sConfig.embeddings()
      (providerName, embeddingCfg) = providerTuple
      embeddingProvider <- ModularRAGSupport.toEmbeddingProvider(providerName)
      rag <- {
        val base = RAG
          .builder()
          .withEmbeddings(embeddingProvider, embeddingCfg.model)
          .withChunking(ChunkerFactory.Strategy.Sentence, 800, 150)
          .withTopK(defaultTopK)
          .inMemory

        val config = maybeLlm match {
          case Some(client) => base.withLLM(client)
          case None         => base
        }

        config.build(_ => Right(embeddingCfg))
      }
    } yield {
      val ingestion  = new DefaultIngestionModule(rag)
      val retrieval  = new DefaultRetrievalModule(rag)
      val generation = Option.when(maybeLlm.isDefined)(new DefaultGenerationModule(rag))
      (rag, ingestion, retrieval, generation)
    }
  }

  private def loadOptionalLlmClient(): Option[LLMClient] =
    Llm4sConfig.defaultProvider().flatMap(LLMConnect.getClient) match {
      case Right(client) =>
        logger.info("LLM provider detected; generation module will run.")
        Some(client)
      case Left(_) =>
        logger.info("LLM provider not configured; running ingestion + retrieval only.")
        None
    }

}
