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
import org.llm4s.vectorstore.*
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

  /** A vector store whose batch writes or deletes can be told to fail, to check a failed write is rolled back. */
  final private class FlakyVectorStore(underlying: VectorStore) extends VectorStore {
    @volatile var failingWrites: Boolean  = false
    @volatile var failingDeletes: Boolean = false

    private def down = Left(ProcessingError("vector-store", "the store is down"))

    override def upsert(record: VectorRecord): Result[Unit] = upsertBatch(Seq(record))
    override def upsertBatch(records: Seq[VectorRecord]): Result[Unit] =
      if (failingWrites) down else underlying.upsertBatch(records)
    override def search(queryVector: Array[Float], topK: Int, filter: Option[MetadataFilter]) =
      underlying.search(queryVector, topK, filter)
    override def get(id: String)                        = underlying.get(id)
    override def getBatch(ids: Seq[String])             = underlying.getBatch(ids)
    override def delete(id: String)                     = deleteBatch(Seq(id))
    override def deleteBatch(ids: Seq[String])          = if (failingDeletes) down else underlying.deleteBatch(ids)
    override def deleteByPrefix(prefix: String)         = underlying.deleteByPrefix(prefix)
    override def deleteByFilter(filter: MetadataFilter) = underlying.deleteByFilter(filter)
    override def count(filter: Option[MetadataFilter])  = underlying.count(filter)
    override def list(limit: Int, offset: Int, filter: Option[MetadataFilter]) = underlying.list(limit, offset, filter)
    override def clear()                                                       = underlying.clear()
    override def stats()                                                       = underlying.stats()
    override def close(): Unit                                                 = underlying.close()
  }

  /** A keyword index whose batch writes can be told to fail: the second store, written after the vectors. */
  final private class FlakyKeywordIndex(underlying: KeywordIndex) extends KeywordIndex {
    @volatile var failingWrites: Boolean = false

    override def index(doc: KeywordDocument): Result[Unit] = indexBatch(Seq(doc))
    override def indexBatch(docs: Seq[KeywordDocument]): Result[Unit] =
      if (failingWrites) Left(ProcessingError("keyword-index", "the index is down")) else underlying.indexBatch(docs)
    override def search(query: String, topK: Int, filter: Option[MetadataFilter]) =
      underlying.search(query, topK, filter)
    override def searchWithHighlights(query: String, topK: Int, snippetLength: Int, filter: Option[MetadataFilter]) =
      underlying.searchWithHighlights(query, topK, snippetLength, filter)
    override def get(id: String)                = underlying.get(id)
    override def delete(id: String)             = underlying.delete(id)
    override def deleteBatch(ids: Seq[String])  = underlying.deleteBatch(ids)
    override def deleteByPrefix(prefix: String) = underlying.deleteByPrefix(prefix)
    override def count()                        = underlying.count()
    override def clear()                        = underlying.clear()
    override def close(): Unit                  = underlying.close()
    override def stats()                        = underlying.stats()
  }

  private def flakyStores(): (FlakyVectorStore, FlakyKeywordIndex, HybridSearcher) = {
    val store    = new FlakyVectorStore(VectorStoreFactory.inMemory().fold(e => fail(e.message), identity))
    val keywords = new FlakyKeywordIndex(KeywordIndex.inMemory().fold(e => fail(e.message), identity))
    (store, keywords, HybridSearcher(store, keywords))
  }

  /** The chunk ids and contents each store holds, read directly rather than through a query. */
  private def contents(store: VectorStore, keywords: KeywordIndex, ids: Seq[String]) = {
    val vectors = store.getBatch(ids).fold(e => fail(e.message), _.map(r => r.id -> r.content.getOrElse("")).toMap)
    val keyword = ids.flatMap(id => keywords.get(id).fold(e => fail(e.message), _.map(d => d.id -> d.content))).toMap
    (vectors, keyword)
  }

  private def build(
    cfg: RAGConfig = config,
    provider: EmbeddingProvider = new MockEmbeddingProviders.BagOfWordsMock(),
    searcher: Option[HybridSearcher] = None
  ) =
    RAG
      .buildWithClient(cfg, new EmbeddingClient(provider), hybridSearcher = searcher)
      .fold(e => fail(e.message), identity)

  private def storedChunks(rag: RAG): Long = rag.stats.fold(e => fail(e.message), _.vectorCount)

  /** Every chunk id the pipeline can return for a broad query: the vector channel returns whatever is stored. */
  private def indexedIds(rag: RAG): Set[String] =
    rag.query("the", topK = Some(200)).fold(e => fail(e.message), _.map(_.id).toSet)

  /** Five chunks of 60 characters, each with a word no other chunk has. */
  private val fiveChunks: String =
    Seq("alpha", "bravo", "charlie", "delta", "echo").map(w => s"$w ${"x" * (59 - w.length)}").mkString

  private val oneChunk: String = "just one short sentence"

  private val fiveIds: Seq[String] = (0 until 5).map(i => s"doc-a-chunk-$i")

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

  it should "keep the previous version when the new one cannot be stored" in {
    val (store, keywords, searcher) = flakyStores()
    val rag                         = build(searcher = Some(searcher))
    rag.ingestText(fiveChunks, "doc-a").fold(e => fail(e.message), identity)
    val before = contents(store, keywords, fiveIds)

    store.failingWrites = true
    rag.ingestText(oneChunk, "doc-a").isLeft shouldBe true
    store.failingWrites = false

    contents(store, keywords, fiveIds) shouldBe before
    rag.chunkCount shouldBe 5
  }

  it should "restore the vectors when the keyword index fails after them" in {
    val (store, keywords, searcher) = flakyStores()
    val rag                         = build(searcher = Some(searcher))
    rag.ingestText(fiveChunks, "doc-a").fold(e => fail(e.message), identity)
    val before = contents(store, keywords, fiveIds)
    before._1 should have size 5

    keywords.failingWrites = true
    rag.ingestText(oneChunk, "doc-a").isLeft shouldBe true
    keywords.failingWrites = false

    // Both stores describe the same, previous version - not new vectors over old keyword entries.
    contents(store, keywords, fiveIds) shouldBe before
    storedChunks(rag) shouldBe 5L
  }

  it should "restore the previous version when removing its tail fails" in {
    val (store, keywords, searcher) = flakyStores()
    val rag                         = build(searcher = Some(searcher))
    rag.ingestText(fiveChunks, "doc-a").fold(e => fail(e.message), identity)
    val before = contents(store, keywords, fiveIds)

    store.failingDeletes = true
    rag.ingestText(oneChunk, "doc-a").isLeft shouldBe true
    store.failingDeletes = false

    contents(store, keywords, fiveIds) shouldBe before
  }

  it should "leave nothing behind when a new document cannot be stored" in {
    val (_, keywords, searcher) = flakyStores()
    val rag                     = build(searcher = Some(searcher))

    keywords.failingWrites = true
    rag.ingestText(fiveChunks, "doc-a").isLeft shouldBe true
    keywords.failingWrites = false

    storedChunks(rag) shouldBe 0L
    (rag.documentCount, rag.chunkCount) shouldBe (0, 0)
  }

  it should "re-ingest on the next sync a document whose loader ingest failed" in {
    // With versioning on, a failed ingest used to register the new version anyway, so the next sync
    // saw it as unchanged and kept the old chunks for good.
    val provider = new FlakyEmbeddings
    val rag      = build(provider = provider)
    rag.ingest(TextLoader.fromPairs("doc-a" -> fiveChunks)).fold(e => fail(e.message), identity)

    provider.failing = true
    rag.ingest(TextLoader.fromPairs("doc-a" -> oneChunk))
    provider.failing = false

    rag.sync(TextLoader.fromPairs("doc-a" -> oneChunk)).fold(e => fail(e.message), _.updated) shouldBe 1
    indexedIds(rag) shouldBe Set("doc-a-chunk-0")
  }

  it should "keep the previous version when sync cannot embed a changed document" in {
    val provider = new FlakyEmbeddings
    val rag      = build(provider = provider)
    rag.sync(TextLoader.fromPairs("doc-a" -> fiveChunks)).fold(e => fail(e.message), _.added) shouldBe 1

    provider.failing = true
    rag.sync(TextLoader.fromPairs("doc-a" -> oneChunk)).fold(e => fail(e.message), _.updated) shouldBe 0
    provider.failing = false

    storedChunks(rag) shouldBe 5L
  }

  it should "replace a changed document through sync" in {
    val rag = build()
    rag.sync(TextLoader.fromPairs("doc-a" -> fiveChunks)).fold(e => fail(e.message), identity)

    rag.sync(TextLoader.fromPairs("doc-a" -> oneChunk)).fold(e => fail(e.message), _.updated) shouldBe 1

    indexedIds(rag) shouldBe Set("doc-a-chunk-0")
  }

  it should "count a re-ingested document once, with its new chunks" in {
    val rag = build()
    rag.ingestText(fiveChunks, "doc-a").fold(e => fail(e.message), identity)
    rag.ingestText(fiveChunks, "doc-b").fold(e => fail(e.message), identity)

    rag.ingestText(oneChunk, "doc-a").fold(e => fail(e.message), identity)
    (rag.documentCount, rag.chunkCount) shouldBe (2, 6)

    rag.ingestText("", "doc-a").fold(e => fail(e.message), identity)
    (rag.documentCount, rag.chunkCount) shouldBe (1, 5)

    rag.deleteDocument("doc-b").fold(e => fail(e.message), identity)
    (rag.documentCount, rag.chunkCount) shouldBe (0, 0)
    rag.stats.fold(e => fail(e.message), s => (s.documentCount, s.chunkCount, s.vectorCount)) shouldBe (0, 0, 0L)
  }

  it should "leave a document whose id differs only in case alone" in {
    // SQLite's LIKE folds ASCII case, so a LIKE prefix delete of "Doc-A" took "doc-a" with it.
    val rag = build()
    rag.ingestText(fiveChunks, "doc-a").fold(e => fail(e.message), identity)
    rag.ingestText(fiveChunks, "Doc-A").fold(e => fail(e.message), identity)

    rag.ingestText(oneChunk, "Doc-A").fold(e => fail(e.message), identity)
    rag.deleteDocument("Doc-A").fold(e => fail(e.message), identity)

    indexedIds(rag) should have size 5
    indexedIds(rag).forall(_.startsWith("doc-a-chunk-")) shouldBe true
  }

  it should "treat GLOB wildcards in a document id literally when deleting it" in {
    val rag = build()
    Seq("a*", "a?", "a[b]", "abc", "ab]").foreach(id =>
      rag.ingestText(fiveChunks, id).fold(e => fail(e.message), identity)
    )

    Seq("a*", "a?", "a[b]").foreach(id => rag.deleteDocument(id).fold(e => fail(e.message), identity))

    indexedIds(rag).map(_.takeWhile(_ != '-')) shouldBe Set("abc", "ab]")
  }
}
