package org.llm4s.rag.loader

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests for DocumentLoaders combinator functions.
 * Supplements the existing DocumentLoaderSpec with more thorough combinator testing.
 */
class DocumentLoadersCombinatorsSpec extends AnyFlatSpec with Matchers {

  private def simpleDoc(id: String, content: String): Document =
    Document(id = id, content = content)

  private def textLoader(docs: Document*): DocumentLoader =
    TextLoader(docs)

  // ==========================================================================
  // combine
  // ==========================================================================

  "DocumentLoaders.combine" should "merge results from multiple loaders" in {
    val loader1 = textLoader(simpleDoc("a", "A content"))
    val loader2 = textLoader(simpleDoc("b", "B content"))
    val loader3 = textLoader(simpleDoc("c", "C content"))

    val combined = DocumentLoaders.combine(Seq(loader1, loader2, loader3))
    val results  = combined.load().collect { case LoadResult.Success(d) => d.id }.toSeq
    results should contain theSameElementsAs Seq("a", "b", "c")
  }

  it should "handle empty sequence of loaders" in {
    val combined = DocumentLoaders.combine(Seq.empty)
    combined.load().toSeq shouldBe empty
  }

  it should "handle single loader" in {
    val loader   = textLoader(simpleDoc("x", "X"))
    val combined = DocumentLoaders.combine(Seq(loader))
    val results  = combined.load().collect { case LoadResult.Success(d) => d.id }.toSeq
    results shouldBe Seq("x")
  }

  it should "report combined estimated count" in {
    val loader1 = textLoader(simpleDoc("a", "A"))
    val loader2 = textLoader(simpleDoc("b", "B"), simpleDoc("c", "C"))

    val combined = DocumentLoaders.combine(Seq(loader1, loader2))
    combined.estimatedCount shouldBe Some(3)
  }

  // ==========================================================================
  // map
  // ==========================================================================

  "DocumentLoaders.map" should "transform documents" in {
    val loader  = textLoader(simpleDoc("a", "hello"))
    val mapped  = DocumentLoaders.map(loader)(d => d.copy(content = d.content.toUpperCase))
    val results = mapped.load().collect { case LoadResult.Success(d) => d.content }.toSeq
    results shouldBe Seq("HELLO")
  }

  it should "pass through failures unchanged" in {
    val loader = new DocumentLoader {
      override def load(): Iterator[LoadResult] = Iterator(
        LoadResult.failure("src", org.llm4s.error.ProcessingError("test", "err"))
      )
      override def estimatedCount: Option[Int] = Some(1)
      override def description: String         = "test"
    }

    val mapped  = DocumentLoaders.map(loader)(d => d.copy(content = "transformed"))
    val results = mapped.load().toSeq
    results should have size 1
    results.head shouldBe a[LoadResult.Failure]
  }

  // ==========================================================================
  // filter
  // ==========================================================================

  "DocumentLoaders.filter" should "remove documents not matching predicate" in {
    val loader   = textLoader(simpleDoc("a", "short"), simpleDoc("b", "this is a longer content"))
    val filtered = DocumentLoaders.filter(loader)(d => d.content.length > 10)
    val results  = filtered.load().collect { case LoadResult.Success(d) => d.id }.toSeq
    results shouldBe Seq("b")
  }

  it should "return empty when nothing matches" in {
    val loader   = textLoader(simpleDoc("a", "hi"))
    val filtered = DocumentLoaders.filter(loader)(_ => false)
    val results  = filtered.load().toSeq
    results.collect { case LoadResult.Success(d) => d }.toSeq shouldBe empty
  }

  // ==========================================================================
  // successesOnly
  // ==========================================================================

  "DocumentLoaders.successesOnly" should "filter out failures and skipped" in {
    val loader = new DocumentLoader {
      override def load(): Iterator[LoadResult] = Iterator(
        LoadResult.success(simpleDoc("a", "good")),
        LoadResult.failure("bad", org.llm4s.error.ProcessingError("test", "err")),
        LoadResult.skipped("skip", "reason"),
        LoadResult.success(simpleDoc("b", "also good"))
      )
      override def estimatedCount: Option[Int] = Some(4)
      override def description: String         = "mixed loader"
    }

    val cleaned = DocumentLoaders.successesOnly(loader)
    val results = cleaned.load().collect { case LoadResult.Success(d) => d.id }.toSeq
    results shouldBe Seq("a", "b")
  }

  // ==========================================================================
  // empty
  // ==========================================================================

  "DocumentLoaders.empty" should "return no results" in {
    DocumentLoaders.empty.load().toSeq shouldBe empty
  }

  it should "report zero estimated count" in {
    DocumentLoaders.empty.estimatedCount shouldBe Some(0)
  }

  // ==========================================================================
  // fromDocuments
  // ==========================================================================

  "DocumentLoaders.fromDocuments" should "wrap document sequence" in {
    val docs    = Seq(simpleDoc("a", "A"), simpleDoc("b", "B"))
    val loader  = DocumentLoaders.fromDocuments(docs)
    val results = loader.load().collect { case LoadResult.Success(d) => d.id }.toSeq
    results shouldBe Seq("a", "b")
    loader.estimatedCount shouldBe Some(2)
  }

  // ==========================================================================
  // take and drop
  // ==========================================================================

  "DocumentLoaders.take" should "limit the number of documents" in {
    val loader  = textLoader(simpleDoc("a", "A"), simpleDoc("b", "B"), simpleDoc("c", "C"))
    val taken   = DocumentLoaders.take(loader, 2)
    val results = taken.load().collect { case LoadResult.Success(d) => d.id }.toSeq
    results shouldBe Seq("a", "b")
  }

  it should "return all if n exceeds count" in {
    val loader  = textLoader(simpleDoc("a", "A"))
    val taken   = DocumentLoaders.take(loader, 10)
    val results = taken.load().collect { case LoadResult.Success(d) => d.id }.toSeq
    results shouldBe Seq("a")
  }

  "DocumentLoaders.drop" should "skip the first n documents" in {
    val loader  = textLoader(simpleDoc("a", "A"), simpleDoc("b", "B"), simpleDoc("c", "C"))
    val dropped = DocumentLoaders.drop(loader, 1)
    val results = dropped.load().collect { case LoadResult.Success(d) => d.id }.toSeq
    results shouldBe Seq("b", "c")
  }

  it should "return empty when n >= count" in {
    val loader  = textLoader(simpleDoc("a", "A"))
    val dropped = DocumentLoaders.drop(loader, 5)
    val results = dropped.load().toSeq
    results shouldBe empty
  }

  // ==========================================================================
  // defer
  // ==========================================================================

  "DocumentLoaders.defer" should "delay loader creation until first access" in {
    var created = false
    val deferred = DocumentLoaders.defer {
      created = true
      textLoader(simpleDoc("a", "A"))
    }

    created shouldBe false
    deferred.load().toSeq // trigger
    created shouldBe true
  }

  // ==========================================================================
  // withMetadata
  // ==========================================================================

  "DocumentLoaders.withMetadata" should "add metadata to all documents" in {
    val loader   = textLoader(simpleDoc("a", "A"))
    val enriched = DocumentLoaders.withMetadata(loader, Map("team" -> "eng"))
    val results  = enriched.load().collect { case LoadResult.Success(d) => d }.toSeq
    results.head.metadata should contain("team" -> "eng")
  }

  // ==========================================================================
  // withHints
  // ==========================================================================

  "DocumentLoaders.withHints" should "add hints to all documents" in {
    val loader  = textLoader(simpleDoc("a", "A"))
    val hinted  = DocumentLoaders.withHints(loader, DocumentHints.markdown)
    val results = hinted.load().collect { case LoadResult.Success(d) => d }.toSeq
    results.head.hints shouldBe Some(DocumentHints.markdown)
  }

  // ==========================================================================
  // fromIterator
  // ==========================================================================

  "DocumentLoaders.fromIterator" should "wrap an iterator of documents" in {
    val iter    = Iterator(simpleDoc("a", "A"), simpleDoc("b", "B"))
    val loader  = DocumentLoaders.fromIterator(iter, "test iter")
    val results = loader.load().collect { case LoadResult.Success(d) => d.id }.toSeq
    results shouldBe Seq("a", "b")
  }

  // ==========================================================================
  // ++ operator
  // ==========================================================================

  "DocumentLoader.++" should "compose two loaders" in {
    val loader1  = textLoader(simpleDoc("a", "A"))
    val loader2  = textLoader(simpleDoc("b", "B"))
    val combined = loader1 ++ loader2
    val results  = combined.load().collect { case LoadResult.Success(d) => d.id }.toSeq
    results should contain theSameElementsAs Seq("a", "b")
  }

  // ==========================================================================
  // LoadResult
  // ==========================================================================

  "LoadResult.toOption" should "return Some for Success" in {
    val doc    = simpleDoc("a", "A")
    val result = LoadResult.success(doc)
    result.toOption shouldBe Some(doc)
  }

  it should "return None for Failure" in {
    val result = LoadResult.failure("src", org.llm4s.error.ProcessingError("test", "err"))
    result.toOption shouldBe None
  }

  it should "return None for Skipped" in {
    val result = LoadResult.skipped("src", "reason")
    result.toOption shouldBe None
  }

  // ==========================================================================
  // LoadStats
  // ==========================================================================

  "LoadStats" should "calculate success rate" in {
    val stats = LoadStats(totalAttempted = 10, successful = 7, failed = 2, skipped = 1)
    stats.successRate shouldBe 0.7 +- 0.01
  }

  it should "handle zero total attempted" in {
    val stats = LoadStats(totalAttempted = 0, successful = 0, failed = 0, skipped = 0)
    stats.successRate shouldBe 0.0
  }

  it should "report hasErrors" in {
    LoadStats(10, 10, 0, 0).hasErrors shouldBe false
    LoadStats(10, 8, 2, 0).hasErrors shouldBe true
  }

  // ==========================================================================
  // SyncStats
  // ==========================================================================

  "SyncStats" should "compute total and changed" in {
    val stats = SyncStats(added = 3, updated = 2, deleted = 1, unchanged = 5)
    stats.total shouldBe 11
    stats.changed shouldBe 6
    stats.hasChanges shouldBe true
  }

  it should "detect no changes" in {
    val stats = SyncStats(added = 0, updated = 0, deleted = 0, unchanged = 10)
    stats.hasChanges shouldBe false
    stats.changed shouldBe 0
  }
}
