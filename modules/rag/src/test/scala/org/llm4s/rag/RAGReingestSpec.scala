package org.llm4s.rag

import org.llm4s.chunking.{ ChunkerFactory, ChunkingConfig }
import org.llm4s.error.ProcessingError
import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.model.{ EmbeddingRequest, EmbeddingResponse }
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.model.{ ModelRegistryService, ModelRegistryTestSupport }
import org.llm4s.rag.loader.TextLoader
import org.llm4s.testutil.MockEmbeddingProviders
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Ingesting a document id that is already indexed must replace what is indexed under it, not add to it
 * (#1318, item 1). The store upserts on chunk id, so a document that comes back with the same number of
 * chunks or more is overwritten correctly; one that comes back with FEWER used to keep its old tail.
 *
 * Everything is in-process: term-overlap embeddings and the in-memory stores.
 */
class RAGReingestSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = ModelRegistryTestSupport.defaultService()

  // Small windows: a sentence is one chunk, a paragraph is several.
  private val chunking = ChunkingConfig(targetSize = 60, maxSize = 80, overlap = 0, minChunkSize = 10)

  private def config: RAGConfig = RAGConfig.default.withChunking(ChunkerFactory.Strategy.Simple, chunking)

  /** An embedding provider that can be told to fail, to check a failed embedding leaves the index alone. */
  final private class FlakyEmbeddings extends EmbeddingProvider {
    private val delegate           = new MockEmbeddingProviders.BagOfWordsMock()
    @volatile var failing: Boolean = false

    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] =
      if (failing) Left(ProcessingError("embedding", "the provider is down")) else delegate.embed(request)
  }

  private def build(
    cfg: RAGConfig = config,
    provider: EmbeddingProvider = new MockEmbeddingProviders.BagOfWordsMock()
  ) =
    RAG.buildWithClient(cfg, new EmbeddingClient(provider)).fold(e => fail(e.message), identity)

  private def storedChunks(rag: RAG): Long = rag.stats.fold(e => fail(e.message), _.vectorCount)

  /** Every chunk id the pipeline can return for a broad query: the vector channel returns whatever is stored. */
  private def indexedIds(rag: RAG): Set[String] =
    rag.query("the", topK = Some(200)).fold(e => fail(e.message), _.map(_.id).toSet)

  /** Five chunks of 60 characters, each with a word no other chunk has. */
  private val fiveChunks: String =
    Seq("alpha", "bravo", "charlie", "delta", "echo").map(w => s"$w ${"x" * (59 - w.length)}").mkString

  private val oneChunk: String = "just one short sentence"

  "RAG re-ingest" should "drop the old tail when a document comes back with fewer chunks" in {
    val rag = build()

    rag.ingestText(fiveChunks, "doc-a").fold(e => fail(e.message), identity) shouldBe 5
    storedChunks(rag) shouldBe 5L

    rag.ingestText(oneChunk, "doc-a").fold(e => fail(e.message), identity) shouldBe 1

    storedChunks(rag) shouldBe 1L
    indexedIds(rag) shouldBe Set("doc-a-chunk-0")
  }

  it should "drop the old tail from the keyword index too" in {
    val rag = build(config.keywordOnly)

    rag.ingestText(fiveChunks, "doc-a").fold(e => fail(e.message), identity)
    rag.ingestText(oneChunk, "doc-a").fold(e => fail(e.message), identity)

    // "echo" only ever appeared in the fifth chunk.
    rag.query("echo", topK = Some(10)).fold(e => fail(e.message), identity) shouldBe empty
    rag.query("short sentence", topK = Some(10)).fold(e => fail(e.message), _.map(_.id)) shouldBe Seq("doc-a-chunk-0")
  }

  it should "remove every chunk when the document comes back empty" in {
    val rag = build()
    rag.ingestText(fiveChunks, "doc-a").fold(e => fail(e.message), identity)

    rag.ingestText("", "doc-a").fold(e => fail(e.message), identity) shouldBe 0

    storedChunks(rag) shouldBe 0L
    indexedIds(rag) shouldBe empty
  }

  it should "replace through ingestChunks as well" in {
    val rag = build()
    rag.ingestChunks("doc-a", Seq("one", "two", "three", "four")).fold(e => fail(e.message), identity)
    storedChunks(rag) shouldBe 4L

    rag.ingestChunks("doc-a", Seq("only")).fold(e => fail(e.message), identity)

    indexedIds(rag) shouldBe Set("doc-a-chunk-0")
  }

  it should "replace through the plain loader ingest as well" in {
    val rag = build()
    rag.ingest(TextLoader.fromPairs("doc-a" -> fiveChunks)).fold(e => fail(e.message), identity)
    storedChunks(rag) shouldBe 5L

    rag.ingest(TextLoader.fromPairs("doc-a" -> oneChunk)).fold(e => fail(e.message), identity)

    indexedIds(rag) shouldBe Set("doc-a-chunk-0")
  }

  it should "leave documents whose ids merely start with the same text alone" in {
    val rag = build()
    rag.ingestText(fiveChunks, "doc-1").fold(e => fail(e.message), identity)
    rag.ingestText(fiveChunks, "doc-1-appendix").fold(e => fail(e.message), identity)
    rag.ingestText(fiveChunks, "doc-10").fold(e => fail(e.message), identity)

    rag.ingestText(oneChunk, "doc-1").fold(e => fail(e.message), identity)

    val ids = indexedIds(rag)
    ids.filter(_.startsWith("doc-1-chunk-")) shouldBe Set("doc-1-chunk-0")
    ids.filter(_.startsWith("doc-1-appendix-chunk-")) should have size 5
    ids.filter(_.startsWith("doc-10-chunk-")) should have size 5
  }

  it should "treat _ and % in a document id literally" in {
    val rag = build()
    // "a_b" would match "axb" and "a%" would match "abc" if the id were used as a LIKE pattern.
    Seq("a_b", "axb", "a%", "abc").foreach(id => rag.ingestText(fiveChunks, id).fold(e => fail(e.message), identity))

    rag.ingestText(oneChunk, "a_b").fold(e => fail(e.message), identity)
    rag.ingestText(oneChunk, "a%").fold(e => fail(e.message), identity)

    val ids = indexedIds(rag)
    ids.filter(_.startsWith("a_b-chunk-")) shouldBe Set("a_b-chunk-0")
    ids.filter(_.startsWith("a%-chunk-")) shouldBe Set("a%-chunk-0")
    ids.filter(_.startsWith("axb-chunk-")) should have size 5
    ids.filter(_.startsWith("abc-chunk-")) should have size 5
  }

  it should "keep the previous version when the new one cannot be embedded" in {
    val provider = new FlakyEmbeddings
    val rag      = build(provider = provider)
    rag.ingestText(fiveChunks, "doc-a").fold(e => fail(e.message), identity)

    provider.failing = true
    rag.ingestText(oneChunk, "doc-a").isLeft shouldBe true
    provider.failing = false

    storedChunks(rag) shouldBe 5L
    indexedIds(rag) should have size 5
  }
}
