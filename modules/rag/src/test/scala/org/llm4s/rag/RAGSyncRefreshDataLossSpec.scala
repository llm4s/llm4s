package org.llm4s.rag

import org.llm4s.error.{ LLMError, NetworkError, ProcessingError }
import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.model.{ EmbeddingRequest, EmbeddingResponse }
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.model.ModelRegistryService
import org.llm4s.rag.loader._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.{ Await, Future }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.Using

/**
 * `refresh` must not destroy the index when the loader fails, and `sync` must not delete a
 * document whose read failed (#1236 follow-up). Everything runs in memory: a mock embedding
 * provider, the default in-memory stores and scripted loaders.
 */
class RAGSyncRefreshDataLossSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private class MockEmbeddingProvider extends EmbeddingProvider {
    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] =
      Right(EmbeddingResponse(embeddings = request.input.map(t => Seq(0.1, 0.2, (t.hashCode.abs % 100) / 100.0))))
  }

  private def withRag[A](config: RAGConfig = RAGConfig.default)(f: RAG => A): A =
    Using.resource(RAG.buildWithClient(config, new EmbeddingClient(new MockEmbeddingProvider)).toOption.get)(f)

  private val failFast = RAGConfig.default.copy(loadingConfig = LoadingConfig(failFast = true))

  private def doc(id: String): Document = Document(id = id, content = s"Content of $id.")

  private def docs(ids: String*): TextLoader = TextLoader(ids.map(doc))

  /** A loader that yields exactly the given results, every time it is loaded. */
  private def scripted(results: LoadResult*): DocumentLoader = new DocumentLoader {
    def load(): Iterator[LoadResult] = results.iterator
    def description: String          = "Scripted"
  }

  private val listingError: LLMError = NetworkError("listing failed", None, "s3")
  private val readError: LLMError    = NetworkError("read timed out", None, "s3")

  private def vectorCount(rag: RAG): Long = rag.stats.toOption.get.vectorCount

  private def await[A](f: Future[A]): A = Await.result(f, 10.seconds)

  /** Index doc-1 and doc-2, and check the index holds both. */
  private def seed(rag: RAG): Long = {
    rag.sync(docs("doc-1", "doc-2")).map(_.added) shouldBe Right(2)
    val count = vectorCount(rag)
    count should be > 0L
    count
  }

  /** A sync with the seeded documents finds both still indexed, at the same version. */
  private def assertSeedIntact(rag: RAG, before: Long): Unit = {
    vectorCount(rag) shouldBe before
    rag.sync(docs("doc-1", "doc-2")) shouldBe Right(SyncStats(added = 0, updated = 0, deleted = 0, unchanged = 2))
  }

  // ========== refresh ==========

  private def refreshBehaviour(name: String, refresh: (RAG, DocumentLoader) => Result[SyncStats]): Unit = {
    s"RAG.$name" should "leave the index untouched when the loader cannot list its documents" in
      withRag() { rag =>
        val before = seed(rag)

        val result = refresh(rag, scripted(LoadResult.listingFailure("S3(s3://bucket/docs/)", listingError)))

        result shouldBe Left(listingError)
        assertSeedIntact(rag, before)
      }

    it should "leave the index untouched when the listing fails part-way through" in
      withRag() { rag =>
        val before = seed(rag)

        val result = refresh(
          rag,
          scripted(
            LoadResult.success(doc("doc-3")),
            LoadResult.success(doc("doc-4")),
            LoadResult.listingFailure("S3(s3://bucket/docs/)", listingError)
          )
        )

        result shouldBe Left(listingError)
        // Neither cleared nor half-rebuilt: doc-3 and doc-4 were not ingested either
        assertSeedIntact(rag, before)
      }

    it should "leave the index untouched when a document fails to read under failFast" in
      withRag(failFast) { rag =>
        val before = seed(rag)

        val result = refresh(
          rag,
          scripted(
            LoadResult.success(doc("doc-1")),
            LoadResult.Failure("docs/doc-2.txt", readError, recoverable = true)
          )
        )

        result shouldBe Left(readError)
        assertSeedIntact(rag, before)
      }

    it should "rebuild the index from the loader when it reads cleanly" in
      withRag() { rag =>
        seed(rag)

        refresh(rag, docs("doc-3")) shouldBe Right(SyncStats(added = 1, updated = 0, deleted = 0, unchanged = 0))

        // A clean slate: doc-1 and doc-2 are gone, doc-3 is indexed
        rag.sync(docs("doc-3")) shouldBe Right(SyncStats(added = 0, updated = 0, deleted = 0, unchanged = 1))
      }

    it should "still rebuild, without the unreadable document, when failFast is off" in
      withRag() { rag =>
        seed(rag)

        val result = refresh(
          rag,
          scripted(
            LoadResult.success(doc("doc-1")),
            LoadResult.Failure("docs/doc-2.txt", readError, recoverable = true)
          )
        )

        result shouldBe Right(SyncStats(added = 1, updated = 0, deleted = 0, unchanged = 0))
      }
  }

  refreshBehaviour("refresh", (rag, loader) => rag.refresh(loader))
  refreshBehaviour("refreshAsync", (rag, loader) => await(rag.refreshAsync(loader)))

  // ========== sync ==========

  private def syncBehaviour(name: String, sync: (RAG, DocumentLoader) => Result[SyncStats]): Unit = {
    s"RAG.$name" should "keep a previously indexed document whose read fails" in
      withRag() { rag =>
        val before = seed(rag)

        val result = sync(
          rag,
          scripted(
            LoadResult.success(doc("doc-1")),
            LoadResult.Failure("docs/doc-2.txt", readError, recoverable = true, documentId = Some("doc-2"))
          )
        )

        // doc-2 is neither deleted nor counted as updated
        result shouldBe Right(SyncStats(added = 0, updated = 0, deleted = 0, unchanged = 1))
        assertSeedIntact(rag, before)
      }

    it should "still delete a document that is genuinely gone" in
      withRag() { rag =>
        rag.sync(docs("doc-1", "doc-2", "doc-3")).map(_.added) shouldBe Right(3)

        val result = sync(
          rag,
          scripted(
            LoadResult.success(doc("doc-1")),
            LoadResult.Failure("docs/doc-2.txt", readError, recoverable = true, documentId = Some("doc-2"))
          )
        )

        // doc-3 is no longer listed; doc-2 was listed but unreadable
        result shouldBe Right(SyncStats(added = 0, updated = 0, deleted = 1, unchanged = 1))
        rag.sync(docs("doc-1", "doc-2")) shouldBe Right(SyncStats(added = 0, updated = 0, deleted = 0, unchanged = 2))
      }

    it should "delete nothing when a failed read does not name its document" in
      withRag() { rag =>
        rag.sync(docs("doc-1", "doc-2", "doc-3")).map(_.added) shouldBe Right(3)

        // Which document failed is unknown, so none of the unlisted ones can safely be deleted
        val result = sync(
          rag,
          scripted(
            LoadResult.success(doc("doc-1")),
            LoadResult.failure("somewhere", ProcessingError("load", "unreadable"))
          )
        )

        result shouldBe Right(SyncStats(added = 0, updated = 0, deleted = 0, unchanged = 1))
        rag.sync(docs("doc-1", "doc-2", "doc-3")) shouldBe
          Right(SyncStats(added = 0, updated = 0, deleted = 0, unchanged = 3))
      }

    it should "not invent a document that was never indexed and fails to read" in
      withRag() { rag =>
        sync(
          rag,
          scripted(LoadResult.Failure("docs/new.txt", readError, recoverable = true, documentId = Some("new")))
        ) shouldBe Right(SyncStats.empty)
        vectorCount(rag) shouldBe 0L
      }
  }

  syncBehaviour("sync", (rag, loader) => rag.sync(loader))
  syncBehaviour("syncAsync", (rag, loader) => await(rag.syncAsync(loader)))
}
