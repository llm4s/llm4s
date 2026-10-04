package org.llm4s.rag

import org.llm4s.chunking.{ ChunkerFactory, ChunkingConfig }
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.model.EmbeddingError
import org.llm4s.model.{ ModelRegistryService, ModelRegistryTestSupport }
import org.llm4s.rag.loader.{ Document, TextLoader }
import org.llm4s.testutil.{ MockEmbeddingProviders, MockLLMClients }
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Covers the full RAG pipeline end to end - document loading, chunking, embedding, indexing,
 * hybrid search, reranking and answer generation - where the suites around it cover one
 * method at a time.
 *
 * Everything is in-process: [[MockEmbeddingProviders.BagOfWordsMock]] for embeddings, an
 * in-memory store, and a mock LLM client. No HTTP, no database, no API key.
 *
 * The mock embeds by term overlap rather than by hashing the whole string, which is what
 * makes the assertions here about *which* document came back rather than merely that one
 * did: a query about functional programming genuinely ranks the Scala and Haskell documents
 * above the Java one, so a retrieval regression has somewhere to show up.
 */
class RAGPipelineIntegrationSpec extends AnyFlatSpec with Matchers with OptionValues with EitherValues {

  private given ModelRegistryService = ModelRegistryTestSupport.defaultService()

  /** Five documents on distinct topics, two of them about functional programming. */
  private val corpus = Seq(
    "doc-scala" -> "Scala is a statically typed programming language that combines object oriented and functional programming.",
    "doc-python" -> "Python is a dynamically typed interpreted language popular for data science and machine learning.",
    "doc-java"   -> "Java is a class based object oriented language designed to be write once run anywhere.",
    "doc-rust"   -> "Rust is a systems programming language focused on memory safety without a garbage collector.",
    "doc-haskell" -> "Haskell is a purely functional programming language with strong static typing and lazy evaluation."
  )

  /** Build a RAG pipeline over an in-memory store with term-overlap embeddings. */
  private def buildRAG(
    config: RAGConfig = RAGConfig.default,
    llm: Option[MockLLMClients.SimpleMock] = None
  ): RAG = {
    val embeddingClient = new EmbeddingClient(new MockEmbeddingProviders.BagOfWordsMock())
    val effectiveConfig = llm.fold(config)(config.withLLM)
    RAG
      .buildWithClient(effectiveConfig, embeddingClient)
      .fold(err => fail(s"Failed to build RAG pipeline: ${err.message}"), identity)
  }

  private def ingestAll(rag: RAG, documents: Seq[(String, String)] = corpus): Unit =
    documents.foreach { case (id, text) =>
      rag.ingestText(text, id).fold(err => fail(err.message), _ => ())
    }

  /** Chunk ids are `<docId>-chunk-<n>`; tests assert on the document, not the chunk. */
  private def docIdOf(chunkId: String): String = chunkId.replaceFirst("-chunk-\\d+$", "")

  // -----------------------------------------------------------------------
  // 1. Full happy path
  // -----------------------------------------------------------------------

  "RAGPipelineIntegration full happy path" should
    "load documents, chunk, embed, index and rank the relevant ones first" in {

      val rag = buildRAG()

      val stats = rag.ingest(TextLoader.fromPairs(corpus: _*)).fold(err => fail(err.message), identity)
      stats.successful shouldBe 5
      stats.failed shouldBe 0
      rag.documentCount shouldBe 5
      rag.chunkCount shouldBe 5

      val results = rag
        .query("functional programming language", topK = Some(5))
        .fold(err => fail(err.message), identity)

      // Both functional-programming documents must outrank all three that are not about it.
      val ranking = results.map(r => docIdOf(r.id))
      ranking should contain theSameElementsAs corpus.map(_._1)
      ranking.take(2) should contain theSameElementsAs Seq("doc-scala", "doc-haskell")
      results.head.score should be > results.last.score
    }

  it should "return the chunk text that matched, not just an id" in {
    val rag = buildRAG()
    rag
      .ingestText("Scala supports higher-order functions and immutable data structures.", "doc-functional")
      .fold(err => fail(err.message), identity)

    val results = rag.query("immutable data").fold(err => fail(err.message), identity)
    results should not be empty
    results.head.id shouldBe "doc-functional-chunk-0"
    results.head.content should include("immutable data structures")
  }

  // -----------------------------------------------------------------------
  // 2. Hybrid search
  //
  // Each strategy is pinned by which score channels it populates, so a config that is
  // silently ignored fails here rather than returning a plausible-looking result set.
  // -----------------------------------------------------------------------

  "RAGPipelineIntegration hybrid search" should
    "merge results carrying both a vector and a keyword score" in {

      val rag = buildRAG()
      ingestAll(rag)

      val results = rag
        .query("functional programming language", topK = Some(5))
        .fold(err => fail(err.message), identity)

      // A merged result is one the same chunk reached through both channels.
      val merged = results.filter(r => r.vectorScore.isDefined && r.keywordScore.isDefined)
      merged should not be empty
      (merged.map(r => docIdOf(r.id)) should contain).allOf("doc-scala", "doc-haskell")

      // Documents only the vector channel found are still present, ranked below.
      val vectorOnlyHits = results.filter(r => r.vectorScore.isDefined && r.keywordScore.isEmpty)
      vectorOnlyHits should not be empty
      merged.map(_.score).min should be > vectorOnlyHits.map(_.score).max
    }

  it should "produce different fused scores under RRF and weighted fusion" in {
    val query = "functional programming language"

    val rrf = buildRAG()
    ingestAll(rrf)
    val rrfResults = rrf.query(query, topK = Some(5)).fold(err => fail(err.message), identity)

    val weighted = buildRAG(RAGConfig.default.withWeightedScore(vectorWeight = 0.7, keywordWeight = 0.3))
    ingestAll(weighted)
    val weightedResults = weighted.query(query, topK = Some(5)).fold(err => fail(err.message), identity)

    // Same corpus, same query, same underlying channel scores - only the fusion differs.
    rrfResults.map(r => docIdOf(r.id)).head shouldBe weightedResults.map(r => docIdOf(r.id)).head
    rrfResults.map(_.vectorScore) shouldBe weightedResults.map(_.vectorScore)

    // Reciprocal rank fusion scores by rank (1/(k+rank)), so they stay small and are
    // unrelated to the channel scores; weighted fusion normalises into [0, 1].
    rrfResults.map(_.score).max should be < 0.1
    weightedResults.map(_.score).max shouldBe 1.0 +- 1e-9
  }

  it should "fuse by reciprocal rank: 1/(60+vectorRank) + 1/(60+keywordRank) per chunk" in {
    val query = "functional programming language"

    def ranks(config: RAGConfig): Map[String, Int] = {
      val rag = buildRAG(config)
      ingestAll(rag)
      rag.query(query, topK = Some(10)).fold(err => fail(err.message), identity).map(_.id).zipWithIndex.toMap.map {
        case (id, zeroBased) => id -> (zeroBased + 1)
      }
    }
    val vectorRanks  = ranks(RAGConfig.default.vectorOnly)
    val keywordRanks = ranks(RAGConfig.default.keywordOnly)

    val fused = buildRAG()
    ingestAll(fused)
    val results = fused.query(query, topK = Some(5)).fold(err => fail(err.message), identity)

    results should not be empty
    results.foreach { r =>
      val expected =
        vectorRanks.get(r.id).fold(0.0)(rank => 1.0 / (60 + rank)) +
          keywordRanks.get(r.id).fold(0.0)(rank => 1.0 / (60 + rank))
      r.score shouldBe expected +- 1e-12
    }
  }

  it should "consult only the vector channel under vectorOnly" in {
    val rag = buildRAG(RAGConfig.default.vectorOnly)
    ingestAll(rag)

    val results = rag
      .query("functional programming language", topK = Some(5))
      .fold(err => fail(err.message), identity)

    results should have size 5
    all(results.map(_.vectorScore)) should be(defined)
    all(results.map(_.keywordScore)) shouldBe empty
    // Every result's fused score is exactly its vector score - nothing else contributed.
    results.foreach(r => r.score shouldBe r.vectorScore.value +- 1e-9)
  }

  it should "consult only the keyword channel under keywordOnly" in {
    val rag = buildRAG(RAGConfig.default.keywordOnly)
    ingestAll(rag)

    val results = rag
      .query("functional programming language", topK = Some(5))
      .fold(err => fail(err.message), identity)

    results should not be empty
    all(results.map(_.keywordScore)) should be(defined)
    all(results.map(_.vectorScore)) shouldBe empty
    // Keyword search only matches documents sharing a term, so unlike the vector channel
    // it does not return the whole corpus.
    results.size should be < corpus.size
    (results.map(r => docIdOf(r.id)) should contain).allOf("doc-scala", "doc-haskell")
  }

  // -----------------------------------------------------------------------
  // 3. Reranking
  // -----------------------------------------------------------------------

  "RAGPipelineIntegration reranking" should
    "reorder retrieved chunks according to the reranker's scores" in {

      val documents = Seq(
        "doc-scala"   -> "Scala is a functional programming language.",
        "doc-java"    -> "Java is an object oriented programming language.",
        "doc-haskell" -> "Haskell is a purely functional language."
      )
      val query = "functional programming"

      // Retrieval order without a reranker, to have something to be reordered.
      val baseline = buildRAG()
      ingestAll(baseline, documents)
      val baselineOrder = baseline
        .query(query, topK = Some(3))
        .fold(err => fail(err.message), _.map(r => docIdOf(r.id)))
      baselineOrder shouldBe Seq("doc-scala", "doc-haskell", "doc-java")

      // LLMReranker asks the model for a JSON array of scores, one per document, in the
      // order it was given them. These invert the retrieval order.
      val judge    = new MockLLMClients.SimpleMock("[0.1, 0.9, 0.5]")
      val reranked = buildRAG(RAGConfig.default.withLLMReranking, llm = Some(judge))
      ingestAll(reranked, documents)

      val results = reranked.query(query, topK = Some(3)).fold(err => fail(err.message), identity)

      results.map(r => docIdOf(r.id)) shouldBe Seq("doc-haskell", "doc-java", "doc-scala")
      results.map(_.score) shouldBe Seq(0.9, 0.5, 0.1)
      judge.lastConversation should not be None
    }

  // -----------------------------------------------------------------------
  // 4. RAG + Agent integration
  // -----------------------------------------------------------------------

  "RAGPipelineIntegration agent answering" should
    "put the retrieved chunk text into the prompt the LLM receives" in {

      val llm = new MockLLMClients.SimpleMock("Context-based answer.")
      val rag = buildRAG(llm = Some(llm))

      rag
        .ingestText("Scala was created by Martin Odersky and first released in 2004.", "doc-scala-history")
        .fold(err => fail(err.message), identity)

      val answer = rag.queryWithAnswer("Who created Scala?").fold(err => fail(err.message), identity)

      llm.lastConversation should not be None
      val prompt = llm.lastConversation.value.messages.map(_.content).mkString("\n")

      // "Martin Odersky" appears only in the indexed document, never in the question - so
      // unlike asserting on "Scala", this fails if the context is not wired into the prompt.
      prompt should include("Martin Odersky")
      prompt should include("Who created Scala?")
      answer.contexts.map(_.content).foreach(content => prompt should include(content))
      answer.answer shouldBe "Context-based answer."
    }

  it should "carry the question, contexts and token usage back to the caller" in {
    val rag = buildRAG(llm = Some(new MockLLMClients.SimpleMock("The answer derived from context.")))
    ingestAll(rag)

    val answer = rag
      .queryWithAnswer("What is a functional programming language?", topK = Some(2))
      .fold(err => fail(err.message), identity)

    answer.question shouldBe "What is a functional programming language?"
    answer.answer shouldBe "The answer derived from context."
    answer.contexts should have size 2
    answer.contexts.map(c => docIdOf(c.id)) should contain theSameElementsAs Seq("doc-scala", "doc-haskell")
    answer.usage.map(_.totalTokens) should not be None
  }

  // -----------------------------------------------------------------------
  // 5. Edge case - empty corpus
  // -----------------------------------------------------------------------

  "RAGPipelineIntegration empty corpus" should
    "return empty results without error when no documents are indexed" in {
      val rag = buildRAG()
      rag.query("any query").fold(err => fail(err.message), identity) shouldBe empty
    }

  it should "return zero stats for an empty pipeline" in {
    val stats = buildRAG().stats.fold(err => fail(err.message), identity)
    stats.documentCount shouldBe 0
    stats.chunkCount shouldBe 0
    stats.vectorCount shouldBe 0L
  }

  // -----------------------------------------------------------------------
  // 6. Duplicate indexing
  // -----------------------------------------------------------------------

  "RAGPipelineIntegration duplicate indexing" should
    "replace a document's chunks when the same id is ingested again" in {

      val rag = buildRAG()

      rag.ingestText("Original content about aardvarks.", "doc-a").fold(err => fail(err.message), identity)
      rag.ingestText("Updated content about zebras.", "doc-a").fold(err => fail(err.message), identity)

      // The store upserts on chunk id, so re-ingesting replaces rather than duplicates.
      rag.stats.fold(err => fail(err.message), _.vectorCount) shouldBe 1L

      // documentCount and chunkCount are ingestion counters, not store cardinality: they
      // count calls. Pinned rather than corrected here so the discrepancy is visible.
      rag.documentCount shouldBe 2
      rag.chunkCount shouldBe 2

      val results = rag.query("aardvarks zebras", topK = Some(5)).fold(err => fail(err.message), identity)
      results should have size 1
      results.head.content shouldBe "Updated content about zebras."
    }

  // -----------------------------------------------------------------------
  // 7. Chunk boundaries
  // -----------------------------------------------------------------------

  "RAGPipelineIntegration chunk boundaries" should
    "split a long document into chunks that respect the configured maximum size" in {

      val chunking = ChunkingConfig(targetSize = 150, maxSize = 200, overlap = 20, minChunkSize = 20)
      val rag      = buildRAG(RAGConfig.default.withChunking(ChunkerFactory.Strategy.Simple, chunking))

      val longDocument = ("The quick brown fox jumps over the lazy dog. " * 30).trim
      rag.ingestText(longDocument, "doc-long").fold(err => fail(err.message), identity)

      rag.chunkCount should be > 1

      // Retrieve every chunk and check the sizes actually indexed, rather than only the count.
      val indexed = rag.query("quick brown fox", topK = Some(100)).fold(err => fail(err.message), identity)
      indexed should have size rag.chunkCount.toLong
      // SimpleChunker windows by targetSize; maxSize is only a ceiling for the other strategies.
      every(indexed.map(_.content.length)) should be <= chunking.targetSize
      indexed.map(_.content.length).max should be > chunking.minChunkSize
    }

  // -----------------------------------------------------------------------
  // 8. Full pipeline in a single test (acceptance requirement of #1000)
  // -----------------------------------------------------------------------

  "RAGPipelineIntegration full end-to-end pipeline" should
    "run document loading -> chunking -> embedding -> indexing -> search -> agent answer" in {

      val llmResponse = "Scala and Haskell are the functional languages in the corpus."
      val llm         = new MockLLMClients.SimpleMock(llmResponse)
      val rag         = buildRAG(llm = Some(llm))

      // 1. Load and ingest
      val ingestStats = rag
        .ingest(TextLoader.fromPairs(corpus: _*))
        .fold(err => fail(err.message), identity)
      ingestStats.successful shouldBe 5

      // 2. Indexing statistics
      rag.documentCount shouldBe 5
      rag.chunkCount shouldBe 5
      rag.stats.fold(err => fail(err.message), _.vectorCount) shouldBe 5L

      // 3. Search
      val searchResults = rag
        .query("functional programming language", topK = Some(3))
        .fold(err => fail(err.message), identity)
      searchResults should have size 3
      searchResults.map(r => docIdOf(r.id)).take(2) should contain theSameElementsAs
        Seq("doc-scala", "doc-haskell")

      // 4. Result structure
      searchResults.foreach { result =>
        result.id should endWith("-chunk-0")
        result.content should not be empty
        result.score should be > 0.0
      }

      // 5. Answer generated from the retrieved context
      val answer = rag
        .queryWithAnswer("Which languages support functional programming?", topK = Some(3))
        .fold(err => fail(err.message), identity)

      answer.answer shouldBe llmResponse
      answer.question shouldBe "Which languages support functional programming?"
      answer.contexts should have size 3
      answer.usage.map(_.totalTokens) should not be None

      val prompt = llm.lastConversation.value.messages.map(_.content).mkString("\n")
      answer.contexts.foreach(ctx => prompt should include(ctx.content))
    }

  // -----------------------------------------------------------------------
  // 9. Retrieval limits
  // -----------------------------------------------------------------------

  "RAGPipelineIntegration topK" should "return the whole corpus when topK exceeds it" in {
    val rag = buildRAG()
    ingestAll(rag)
    rag.query("programming language", topK = Some(50)).fold(err => fail(err.message), identity) should have size 5
  }

  it should "honour a per-query topK and fall back to the configured one" in {
    val rag = buildRAG(RAGConfig.default.withTopK(2))
    ingestAll(rag)

    rag.query("programming language").fold(err => fail(err.message), identity) should have size 2
    rag.query("programming language", topK = Some(1)).fold(err => fail(err.message), identity) should have size 1
    rag.query("programming language", topK = Some(4)).fold(err => fail(err.message), identity) should have size 4
  }

  it should "return the top-ranked chunk first and in descending score order" in {
    val rag = buildRAG()
    ingestAll(rag)
    val results =
      rag.query("purely functional lazy evaluation", topK = Some(5)).fold(err => fail(err.message), identity)
    docIdOf(results.head.id) shouldBe "doc-haskell"
    results.map(_.score) shouldBe results.map(_.score).sorted.reverse
  }

  it should "find one specific document in a larger corpus" in {
    val embeddings = new EmbeddingClient(new MockEmbeddingProviders.BagOfWordsMock(1024))
    val big        = RAG.buildWithClient(RAGConfig.default, embeddings).fold(err => fail(err.message), identity)
    val docs       = (0 until 200).map(i => f"doc-$i%03d" -> f"filler text number zq$i%04d about topic ${i % 7}%d")
    ingestAll(big, docs)

    big.stats.fold(err => fail(err.message), _.vectorCount) shouldBe 200L
    val results = big.query("zq0137", topK = Some(3)).fold(err => fail(err.message), identity)
    docIdOf(results.head.id) shouldBe "doc-137"
    results.head.keywordScore should be(defined)
  }

  // -----------------------------------------------------------------------
  // 10. Fusion extremes
  // -----------------------------------------------------------------------

  "RAGPipelineIntegration weighted fusion extremes" should
    "rank like the vector channel when the keyword weight is zero" in {
      val query      = "functional programming language"
      val vectorOnly = buildRAG(RAGConfig.default.vectorOnly)
      ingestAll(vectorOnly)
      val expected =
        vectorOnly.query(query, topK = Some(5)).fold(err => fail(err.message), _.map(r => docIdOf(r.id)))

      val weighted = buildRAG(RAGConfig.default.withWeightedScore(vectorWeight = 1.0, keywordWeight = 0.0))
      ingestAll(weighted)
      val got = weighted.query(query, topK = Some(5)).fold(err => fail(err.message), identity)
      got.map(r => docIdOf(r.id)) shouldBe expected
    }

  it should "rank like the keyword channel when the vector weight is zero" in {
    val query       = "functional programming language"
    val keywordOnly = buildRAG(RAGConfig.default.keywordOnly)
    ingestAll(keywordOnly)
    val keywordHits =
      keywordOnly.query(query, topK = Some(5)).fold(err => fail(err.message), identity)

    val weighted = buildRAG(RAGConfig.default.withWeightedScore(vectorWeight = 0.0, keywordWeight = 1.0))
    ingestAll(weighted)
    val got = weighted.query(query, topK = Some(5)).fold(err => fail(err.message), identity)

    // Min-max normalisation: the best keyword hit scores 1.0, and a chunk only the vector
    // channel found contributes nothing, so it scores 0.0. (Which of several tied chunks comes
    // next is not specified, so only the top and the zero floor are pinned.)
    docIdOf(got.head.id) shouldBe docIdOf(keywordHits.head.id)
    got.head.score shouldBe 1.0 +- 1e-9
    val keywordIds = keywordHits.map(_.id).toSet
    got.filterNot(r => keywordIds.contains(r.id)).foreach(_.score shouldBe 0.0)
  }

  // -----------------------------------------------------------------------
  // 11. Reranker degradation
  // -----------------------------------------------------------------------

  private def rerankDocs = Seq(
    "doc-scala"   -> "Scala is a functional programming language.",
    "doc-java"    -> "Java is an object oriented programming language.",
    "doc-haskell" -> "Haskell is a purely functional language."
  )

  "RAGPipelineIntegration reranking degradation" should
    "keep the retrieval order with neutral scores when the judge answers with garbage" in {
      val rag = buildRAG(RAGConfig.default.withLLMReranking, llm = Some(new MockLLMClients.SimpleMock("I cannot help")))
      ingestAll(rag, rerankDocs)

      val results = rag.query("functional programming", topK = Some(3)).fold(err => fail(err.message), identity)
      results.map(r => docIdOf(r.id)) shouldBe Seq("doc-scala", "doc-haskell", "doc-java")
      results.map(_.score) shouldBe Seq(0.5, 0.5, 0.5)
    }

  it should "pad missing scores with the neutral score when the judge returns too few" in {
    val rag = buildRAG(RAGConfig.default.withLLMReranking, llm = Some(new MockLLMClients.SimpleMock("[0.9]")))
    ingestAll(rag, rerankDocs)

    val results = rag.query("functional programming", topK = Some(3)).fold(err => fail(err.message), identity)
    results.map(_.score) shouldBe Seq(0.9, 0.5, 0.5)
    docIdOf(results.head.id) shouldBe "doc-scala"
  }

  it should "truncate to topK after reranking, keeping the best-scored chunks" in {
    val rag = buildRAG(
      RAGConfig.default.withLLMReranking,
      llm = Some(new MockLLMClients.SimpleMock("[0.1, 0.2, 0.9]"))
    )
    ingestAll(rag, rerankDocs)

    val results = rag.query("functional programming", topK = Some(1)).fold(err => fail(err.message), identity)
    results should have size 1
    // Retrieval order is scala, haskell, java; the judge scores the third candidate (java) highest.
    docIdOf(results.head.id) shouldBe "doc-java"
    results.head.score shouldBe 0.9
  }

  // -----------------------------------------------------------------------
  // 12. Ingestion edge cases
  // -----------------------------------------------------------------------

  "RAGPipelineIntegration ingestion edge cases" should "index nothing for an empty document" in {
    val rag = buildRAG()
    rag.ingestText("", "doc-empty") shouldBe Right(0)
    rag.documentCount shouldBe 0
    rag.stats.fold(err => fail(err.message), _.vectorCount) shouldBe 0L
  }

  it should "not fail on a whitespace-only document" in {
    val rag = buildRAG()
    rag.ingestText("   \n\t  ", "doc-blank").isRight shouldBe true
    rag.query("anything here").isRight shouldBe true
  }

  it should "skip empty documents in a loader batch without failing the batch" in {
    val rag = buildRAG()
    val stats = rag
      .ingest(TextLoader.fromPairs("doc-empty" -> "", "doc-real" -> "Real content about Scala."))
      .fold(err => fail(err.message), identity)
    stats.failed shouldBe 0
    rag.stats.fold(err => fail(err.message), _.vectorCount) shouldBe 1L
  }

  it should "carry user metadata and the docId/chunkIndex bookkeeping onto results" in {
    val rag = buildRAG()
    rag
      .ingestText("Scala has case classes.", "doc-meta", Map("author" -> "ada"))
      .fold(err => fail(err.message), identity)

    val hit = rag.query("case classes").fold(err => fail(err.message), _.head)
    (hit.metadata should contain).allOf("author" -> "ada", "docId" -> "doc-meta", "chunkIndex" -> "0")
  }

  it should "chunk contiguously: reassembling the chunks, minus the overlap, gives back the document" in {
    val chunking = ChunkingConfig(targetSize = 100, maxSize = 150, overlap = 25, minChunkSize = 10)
    val rag      = buildRAG(RAGConfig.default.withChunking(ChunkerFactory.Strategy.Simple, chunking))

    val text = (1 to 80).map(i => f"w$i%03d").mkString(" ")
    rag.ingestText(text, "doc-seq").fold(err => fail(err.message), identity)

    val chunks = rag
      .query("w001", topK = Some(100))
      .fold(err => fail(err.message), identity)
      .sortBy(_.metadata("chunkIndex").toInt)
    chunks.map(_.metadata("chunkIndex").toInt) shouldBe chunks.indices

    chunks.head.content shouldBe text.take(100)
    val rebuilt = chunks.tail.foldLeft(chunks.head.content) { (acc, c) =>
      // Each chunk begins `overlap` characters before the end of the previous one. (The final
      // window can be shorter than the overlap, in which case it lies wholly inside the
      // previous chunk - ChunkingUtilsSpec pins that behaviour - and adds nothing.)
      if (c.content.length >= chunking.overlap)
        c.content.take(chunking.overlap) shouldBe acc.takeRight(chunking.overlap)
      else acc should endWith(c.content)
      acc + c.content.drop(chunking.overlap)
    }
    rebuilt shouldBe text
  }

  it should "produce exactly one chunk for a document of exactly targetSize and two for one more" in {
    val chunking = ChunkingConfig(targetSize = 50, maxSize = 80, overlap = 0, minChunkSize = 5)
    val cfg      = RAGConfig.default.withChunking(ChunkerFactory.Strategy.Simple, chunking)

    buildRAG(cfg).ingestText("a" * 50, "doc-exact") shouldBe Right(1)
    buildRAG(cfg).ingestText("a" * 51, "doc-over") shouldBe Right(2)
    buildRAG(cfg).ingestText("a" * 100, "doc-double") shouldBe Right(2)
  }

  // -----------------------------------------------------------------------
  // 13. Deletion and lifecycle
  // -----------------------------------------------------------------------

  "RAGPipelineIntegration lifecycle" should "remove a document from both channels when it is deleted" in {
    val rag = buildRAG()
    ingestAll(rag)

    rag.deleteDocument("doc-haskell").isRight shouldBe true

    rag.stats.fold(err => fail(err.message), _.vectorCount) shouldBe 4L
    val hybrid =
      rag.query("purely functional lazy evaluation", topK = Some(10)).fold(err => fail(err.message), identity)
    hybrid.map(r => docIdOf(r.id)) should not contain "doc-haskell"
    hybrid should have size 4

    val keywordOnly = buildRAG(RAGConfig.default.keywordOnly)
    ingestAll(keywordOnly)
    keywordOnly.deleteDocument("doc-haskell").isRight shouldBe true
    val kw = keywordOnly.query("purely functional lazy evaluation").fold(err => fail(err.message), identity)
    kw.map(r => docIdOf(r.id)) should not contain "doc-haskell"
  }

  it should "leave other documents alone when deleting one whose id is a prefix of theirs" in {
    val rag = buildRAG()
    ingestAll(rag, Seq("doc-1" -> "Alpha content about aardvarks.", "doc-10" -> "Beta content about badgers."))

    rag.deleteDocument("doc-1").isRight shouldBe true

    val remaining = rag.query("content", topK = Some(10)).fold(err => fail(err.message), identity)
    remaining.map(r => docIdOf(r.id)) shouldBe Seq("doc-10")
    val keywordOnly = buildRAG(RAGConfig.default.keywordOnly)
    ingestAll(keywordOnly, Seq("doc-1" -> "Alpha content about aardvarks.", "doc-10" -> "Beta content about badgers."))
    keywordOnly.deleteDocument("doc-1").isRight shouldBe true
    keywordOnly.query("content").fold(err => fail(err.message), _.map(r => docIdOf(r.id))) shouldBe Seq("doc-10")
  }

  it should "keep a prefix-sharing document when sync re-ingests a changed one" in {
    val rag = buildRAG()
    val v1 = TextLoader(
      Seq(Document("doc-1", "Alpha content about aardvarks."), Document("doc-10", "Beta content about badgers."))
    )
    val v2 = TextLoader(
      Seq(Document("doc-1", "Alpha updated about armadillos."), Document("doc-10", "Beta content about badgers."))
    )
    rag.sync(v1).fold(err => fail(err.message), identity)

    val stats = rag.sync(v2).fold(err => fail(err.message), identity)
    stats.updated shouldBe 1

    val docs = rag.query("content updated", topK = Some(10)).fold(err => fail(err.message), _.map(r => docIdOf(r.id)))
    docs should contain theSameElementsAs Seq("doc-1", "doc-10")
  }

  it should "empty both channels on clear and reset the counters" in {
    val rag = buildRAG()
    ingestAll(rag)
    rag.clear().isRight shouldBe true

    rag.documentCount shouldBe 0
    rag.chunkCount shouldBe 0
    rag.stats.fold(err => fail(err.message), _.vectorCount) shouldBe 0L
    rag.query("functional programming language", topK = Some(5)).fold(err => fail(err.message), identity) shouldBe empty

    val keywordOnly = buildRAG(RAGConfig.default.keywordOnly)
    ingestAll(keywordOnly)
    keywordOnly.clear().isRight shouldBe true
    keywordOnly.query("functional programming language").fold(err => fail(err.message), identity) shouldBe empty
  }

  // -----------------------------------------------------------------------
  // 14. Failure propagation
  // -----------------------------------------------------------------------

  "RAGPipelineIntegration failures" should "surface an embedding failure on ingest and index nothing" in {
    val client = new EmbeddingClient(new MockEmbeddingProviders.FailingMock("provider down"))
    val rag    = RAG.buildWithClient(RAGConfig.default, client).fold(err => fail(err.message), identity)

    val result = rag.ingestText("Some content about Scala.", "doc-x")
    result.left.value shouldBe a[EmbeddingError]
    result.left.value.message should include("provider down")
    rag.documentCount shouldBe 0
    rag.stats.fold(err => fail(err.message), _.vectorCount) shouldBe 0L
  }

  it should "surface an embedding failure on query" in {
    val client = new EmbeddingClient(new MockEmbeddingProviders.FailingMock("provider down"))
    val rag    = RAG.buildWithClient(RAGConfig.default, client).fold(err => fail(err.message), identity)
    rag.query("anything").left.value shouldBe a[EmbeddingError]
  }

  it should "reject queryWithAnswer when no LLM is configured" in {
    val rag = buildRAG()
    ingestAll(rag)
    rag.queryWithAnswer("What is Scala?").left.value shouldBe a[ConfigurationError]
  }

  it should "reject an LLM-reranking config when no LLM is configured" in {
    val client = new EmbeddingClient(new MockEmbeddingProviders.BagOfWordsMock())
    RAG.buildWithClient(RAGConfig.default.withLLMReranking, client).left.value shouldBe a[ConfigurationError]
  }

  it should "still answer, with no contexts, when nothing is indexed" in {
    val llm    = new MockLLMClients.SimpleMock("I do not know.")
    val rag    = buildRAG(llm = Some(llm))
    val answer = rag.queryWithAnswer("Who created Scala?").fold(err => fail(err.message), identity)
    answer.contexts shouldBe empty
    answer.answer shouldBe "I do not know."
    llm.lastConversation.value.messages.map(_.content).mkString should include("Who created Scala?")
  }

  it should "not fail on a query whose terms the embedder cannot represent" in {
    val rag = buildRAG()
    ingestAll(rag)
    // Every token is shorter than three characters, so BagOfWordsMock yields the zero vector.
    val results = rag.query("a to be", topK = Some(3)).fold(err => fail(err.message), identity)
    results.foreach(_.score.isNaN shouldBe false)
  }
}
