package org.llm4s.rag

import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.model.{ EmbeddingRequest, EmbeddingResponse }
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.model.ModelRegistryService
import org.llm4s.rag.loader._
import org.llm4s.types.Result
import org.llm4s.vectorstore.HybridSearcher
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*
import scala.util.Using

/**
 * A pluggable [[DocumentRegistry]] and source-scoped sync (#2714): a registry from the config is the one sync uses,
 * one that outlives the pipeline makes the next pipeline's sync incremental, and a sync deletes only its own
 * source's documents.
 */
class RAGDocumentRegistrySpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  /** Counts the texts it embeds, so a test can tell a re-embed from a skip. */
  private class CountingEmbeddingProvider(val embedded: AtomicInteger = new AtomicInteger()) extends EmbeddingProvider {
    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = {
      embedded.addAndGet(request.input.size)
      Right(EmbeddingResponse(embeddings = request.input.map(t => Seq(t.length % 7 / 7.0, 0.5, 0.25))))
    }
  }

  private class ListLoader(docs: (String, String)*) extends DocumentLoader {
    override def load(): Iterator[LoadResult] =
      docs.iterator.map { case (id, content) => LoadResult.Success(Document(id, content)) }
    override def description: String = s"list of ${docs.size}"
  }

  /** A pipeline over its own in-memory stores, or the given ones, that sync can share between pipelines. */
  private def pipeline(
    config: RAGConfig,
    provider: CountingEmbeddingProvider = new CountingEmbeddingProvider(),
    searcher: Option[HybridSearcher] = None
  ): RAG =
    RAG
      .buildWithClient(config, EmbeddingClient(provider), hybridSearcher = searcher)
      .fold(e => fail(e.formatted), identity)

  "RAGConfig.withDocumentRegistry" should "be the registry sync records into" in {
    val registry = InMemoryDocumentRegistry()
    val rag      = pipeline(RAGConfig.default.withDocumentRegistry(registry))
    try {
      rag.sync(new ListLoader("a" -> "Alpha text.", "b" -> "Beta text.")) shouldBe Right(SyncStats(2, 0, 0, 0))
      registry.allDocumentIds() shouldBe Right(Set("a", "b"))
    } finally rag.close()
  }

  it should "make a new pipeline's sync incremental, re-embedding only what changed and deleting what went" in {
    val registry = InMemoryDocumentRegistry()
    val searcher = HybridSearcher.inMemory().fold(e => fail(e.formatted), identity)
    val config   = RAGConfig.default.withDocumentRegistry(registry)

    val first = pipeline(config, searcher = Some(searcher))
    first.sync(new ListLoader("a" -> "Alpha text.", "b" -> "Beta text.", "c" -> "Gamma text.")) shouldBe
      Right(SyncStats(3, 0, 0, 0))

    // A "restart": a new pipeline over the same stores and registry
    val provider = new CountingEmbeddingProvider()
    val second   = pipeline(config, provider, Some(searcher))
    try {
      second.sync(new ListLoader("a" -> "Alpha text.", "b" -> "Beta text, edited.")) shouldBe
        Right(SyncStats(added = 0, updated = 1, deleted = 1, unchanged = 1))
      provider.embedded.get shouldBe 1 // only b's one chunk
      registry.allDocumentIds() shouldBe Right(Set("a", "b"))
      searcher.vectorStore.get("c-chunk-0") shouldBe Right(None)
    } finally second.close()
  }

  "RAG.sync with a sourceId" should "delete only the documents of its own source" in {
    val registry = InMemoryDocumentRegistry()
    val rag      = pipeline(RAGConfig.default.withDocumentRegistry(registry))
    try {
      rag.sync(new ListLoader("a1" -> "From A.", "a2" -> "Also from A."), "source-a") shouldBe Right(
        SyncStats(2, 0, 0, 0)
      )
      rag.sync(new ListLoader("b1" -> "From B."), "source-b") shouldBe Right(SyncStats(1, 0, 0, 0))

      // Syncing B again, with b1 gone, removes b1 and leaves A's documents alone
      rag.sync(new ListLoader(), "source-b") shouldBe Right(SyncStats(0, 0, 1, 0))
      registry.allDocumentIds() shouldBe Right(Set("a1", "a2"))

      // A's next sync finds its documents unchanged: nothing to re-embed
      rag.sync(new ListLoader("a1" -> "From A.", "a2" -> "Also from A."), "source-a") shouldBe
        Right(SyncStats(0, 0, 0, 2))
    } finally rag.close()
  }

  it should "leave sourced documents alone in a sync of the default source" in {
    val registry = InMemoryDocumentRegistry()
    val rag      = pipeline(RAGConfig.default.withDocumentRegistry(registry))
    try {
      rag.sync(new ListLoader("a1" -> "From A."), "source-a") shouldBe Right(SyncStats(1, 0, 0, 0))
      rag.sync(new ListLoader("d1" -> "Default.")) shouldBe Right(SyncStats(1, 0, 0, 0))
      rag.sync(new ListLoader()) shouldBe Right(SyncStats(0, 0, 1, 0))
      registry.documentIds("source-a") shouldBe Right(Set("a1"))
      registry.documentIds(DocumentRegistry.DefaultSourceId) shouldBe Right(Set.empty)
    } finally rag.close()
  }

  it should "scope syncAsync the same way" in {
    val registry = InMemoryDocumentRegistry()
    val rag      = pipeline(RAGConfig.default.withDocumentRegistry(registry))
    try {
      Await.result(rag.syncAsync(new ListLoader("a1" -> "From A."), "source-a"), 10.seconds) shouldBe
        Right(SyncStats(1, 0, 0, 0))
      Await.result(rag.syncAsync(new ListLoader("b1" -> "From B."), "source-b"), 10.seconds) shouldBe
        Right(SyncStats(1, 0, 0, 0))
      Await.result(rag.syncAsync(new ListLoader(), "source-b"), 10.seconds) shouldBe Right(SyncStats(0, 0, 1, 0))
      registry.allDocumentIds() shouldBe Right(Set("a1"))
    } finally rag.close()
  }

  "A SQLiteDocumentRegistry" should "serve as the pipeline's registry" in {
    Using.resource(SQLiteDocumentRegistry.inMemory().fold(e => fail(e.formatted), identity)) { registry =>
      val rag = pipeline(RAGConfig.default.withDocumentRegistry(registry))
      try {
        rag.sync(new ListLoader("a" -> "Alpha text."), "docs") shouldBe Right(SyncStats(1, 0, 0, 0))
        rag.sync(new ListLoader("a" -> "Alpha text."), "docs") shouldBe Right(SyncStats(0, 0, 0, 1))
        registry.documentIds("docs") shouldBe Right(Set("a"))
      } finally rag.close()
    }
  }
}
