package org.llm4s.samples.rag.modular

import org.llm4s.rag.{ RAG, RAGAnswerResult, RAGSearchResult }
import org.llm4s.types.Result

trait IngestionModule {
  def ingestPath(path: String, metadata: Map[String, String] = Map.empty): Result[Int]

  def ingestText(
    documentId: String,
    content: String,
    metadata: Map[String, String] = Map.empty
  ): Result[Int]
}

final class DefaultIngestionModule(rag: RAG) extends IngestionModule {
  override def ingestPath(path: String, metadata: Map[String, String]): Result[Int] =
    rag.ingest(path, metadata)

  override def ingestText(
    documentId: String,
    content: String,
    metadata: Map[String, String]
  ): Result[Int] =
    rag.ingestText(content = content, documentId = documentId, metadata = metadata)
}

trait RetrievalModule {
  def retrieve(query: String, topK: Int): Result[Seq[RAGSearchResult]]
}

final class DefaultRetrievalModule(rag: RAG) extends RetrievalModule {
  override def retrieve(query: String, topK: Int): Result[Seq[RAGSearchResult]] =
    rag.query(query, topK = Some(topK))
}

trait GenerationModule {
  def answer(question: String, topK: Int): Result[RAGAnswerResult]
}

final class DefaultGenerationModule(rag: RAG) extends GenerationModule {
  override def answer(question: String, topK: Int): Result[RAGAnswerResult] =
    rag.queryWithAnswer(question, topK = Some(topK))
}
