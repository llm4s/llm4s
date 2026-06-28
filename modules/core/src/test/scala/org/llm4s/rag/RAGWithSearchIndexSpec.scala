package org.llm4s.rag

import org.llm4s.rag.permissions._
import org.llm4s.vectorstore.{ MetadataFilter, ScoredRecord }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

class RAGWithSearchIndexSpec extends AnyFlatSpec with Matchers {

  "RAGConfig.withSearchIndex with non-Pg SearchIndex" should "not set pgVectorConnectionString" in {
    val mockIndex = new SearchIndex {
      override def principals: PrincipalStore   = ???
      override def collections: CollectionStore = ???
      override def query(
        auth: UserAuthorization,
        collectionPattern: CollectionPattern,
        queryVector: Array[Float],
        topK: Int,
        additionalFilter: Option[MetadataFilter]
      ): Result[Seq[ScoredRecord]] = ???
      override def ingest(
        collectionPath: CollectionPath,
        documentId: String,
        chunks: Seq[ChunkWithEmbedding],
        metadata: Map[String, String],
        readableBy: Set[PrincipalId]
      ): Result[Int] = ???
      override def deleteDocument(collectionPath: CollectionPath, documentId: String): Result[Long] = ???
      override def clearCollection(collectionPath: CollectionPath): Result[Long]                    = ???
      override def initializeSchema(): Result[Unit]                                                 = ???
      override def dropSchema(): Result[Unit]                                                       = ???
      override def close(): Unit                                                                    = ()
    }

    val config = RAGConfig.default.withSearchIndex(mockIndex)

    config.searchIndex shouldBe defined
    config.pgVectorConnectionString shouldBe None
  }

  "RAG created with non-Pg SearchIndex" should "use in-memory storage for regular ingest configuration" in {
    val mockIndex = new TestableSearchIndex()

    val config = RAGConfig.default
      .withEmbeddings(EmbeddingProvider.OpenAI)
      .withSearchIndex(mockIndex)

    config.searchIndex shouldBe defined
    config.pgVectorConnectionString shouldBe None
  }

  class TestableSearchIndex extends SearchIndex {
    val ingestCalls: mutable.Buffer[(CollectionPath, String, Seq[ChunkWithEmbedding])] =
      mutable.Buffer.empty
    val queryCalls: mutable.Buffer[(UserAuthorization, CollectionPattern, Array[Float])] =
      mutable.Buffer.empty

    override def principals: PrincipalStore   = ???
    override def collections: CollectionStore = ???

    override def query(
      auth: UserAuthorization,
      collectionPattern: CollectionPattern,
      queryVector: Array[Float],
      topK: Int,
      additionalFilter: Option[MetadataFilter]
    ): Result[Seq[ScoredRecord]] = {
      queryCalls += ((auth, collectionPattern, queryVector))
      Right(Seq.empty)
    }

    override def ingest(
      collectionPath: CollectionPath,
      documentId: String,
      chunks: Seq[ChunkWithEmbedding],
      metadata: Map[String, String],
      readableBy: Set[PrincipalId]
    ): Result[Int] = {
      ingestCalls += ((collectionPath, documentId, chunks))
      Right(chunks.size)
    }

    override def deleteDocument(collectionPath: CollectionPath, documentId: String): Result[Long] =
      Right(0L)

    override def clearCollection(collectionPath: CollectionPath): Result[Long] =
      Right(0L)

    override def initializeSchema(): Result[Unit] = Right(())
    override def dropSchema(): Result[Unit]       = Right(())
    override def close(): Unit                    = ()
  }
}
