package org.llm4s.agent.memory

import org.llm4s.error.{ ConfigurationError, NotFoundError, ProcessingError, ValidationError }
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * The contract of a [[MemoryStore]] (#3038): one answer, on every store, for each call the stores used to answer
 * differently - writes over an existing id, updates, limits, blank queries, scores - and, for a store that searches by
 * embedding, the shared score rule (`org.llm4s.vectorstore.SearchScores`, #3037): `(1 + cos) / 2`, a vector that cannot
 * be compared left out, a store of another dimension a `ConfigurationError`.
 *
 * Mixed into one spec per store: this module's are in `MemoryStoreContractSpecs.scala`, Postgres's in `modules/it`. It
 * uses only ScalaTest and main classes, because `modules/it` compiles this one file into its own tests
 * (`Test / unmanagedSources`, #3217). Timestamps are whole milliseconds, the precision every store keeps.
 */
trait MemoryStoreContract extends AnyFlatSpecLike with Matchers with EitherValues with OptionValues {

  /** Names the store in the suite's output. */
  protected def storeName: String

  /** True if the store searches by embedding, with the service `withStore` hands it; false for a keyword store. */
  protected def semantic: Boolean

  /** What `durable` must answer for the store under test. */
  protected def expectDurable: Boolean = false

  /** Runs `body` on a fresh, empty store - built with `embeddings`, if it is semantic - then releases it. */
  protected def withStore[A](embeddings: EmbeddingService)(body: MemoryStore => A): A

  import MemoryStoreContract._

  private val t0 = Instant.ofEpochMilli(1_700_000_000_000L)

  private def memory(id: String, content: String, at: Long = 0L): Memory =
    Memory(MemoryId(id), content, MemoryType.Knowledge, timestamp = t0.plusSeconds(at))

  private def run[A](body: (MemoryStore, ContractEmbeddings) => A): A = {
    val embeddings = new ContractEmbeddings
    withStore(embeddings)(store => body(store, embeddings))
  }

  private def put(store: MemoryStore, memories: Memory*): MemoryStore = store.storeAll(memories).value

  private def scoresOf(found: Seq[ScoredMemory]): Seq[Double] = found.map(_.score)

  behavior.of(s"A memory store ($storeName)")

  // ===== Writes =====

  it should "replace every field, the timestamp included, when an id is stored again" in run { (s0, _) =>
    val first  = memory("m", "first text", at = 1).withMetadata(Map("k" -> "v")).withImportance(0.2)
    val second = Memory(MemoryId("m"), "second text", MemoryType.Task, Map("k2" -> "v2"), t0.plusSeconds(99), None)
    val s1     = put(put(s0, first), second)
    val got    = s1.get(MemoryId("m")).value.value
    (got.content, got.memoryType, got.metadata, got.timestamp, got.importance) shouldBe
      (("second text", MemoryType.Task, Map("k2" -> "v2"), t0.plusSeconds(99), None))
    s1.count().value shouldBe 1L
  }

  it should "store a memory whose id appears twice in a batch as its last occurrence" in run { (s0, _) =>
    val s1 = put(s0, memory("m", "one"), memory("m", "two"))
    s1.get(MemoryId("m")).value.map(_.content) shouldBe Some("two")
    s1.count().value shouldBe 1L
  }

  it should "apply an update to every field, the timestamp included" in run { (s0, _) =>
    val s1  = put(s0, memory("m", "text", at = 1))
    val s2  = s1.update(MemoryId("m"), _.copy(timestamp = t0.plusSeconds(50)).withImportance(0.7)).value
    val got = s2.get(MemoryId("m")).value.value
    (got.timestamp, got.importance) shouldBe ((t0.plusSeconds(50), Some(0.7)))
  }

  it should "refuse an update that changes the id, and change nothing" in run { (s0, _) =>
    val s1     = put(s0, memory("m", "text"))
    val result = s1.update(MemoryId("m"), _.copy(id = MemoryId("other")))
    result.left.value shouldBe a[ValidationError]
    s1.get(MemoryId("m")).value.map(_.content) shouldBe Some("text")
    s1.get(MemoryId("other")).value shouldBe None
    s1.count().value shouldBe 1L
  }

  it should "answer an update of a missing id with NotFoundError" in run { (s0, _) =>
    s0.update(MemoryId("missing"), identity).left.value shouldBe a[NotFoundError]
  }

  it should "not keep an embedding computed for the old content when an update changes the content" in run {
    (s0, embeddings) =>
      val stale = Array(1f, 0f, 0f)
      val s1    = put(s0, memory("m", "old content").withEmbedding(stale))
      val s2    = s1.update(MemoryId("m"), m => m.copy(content = "new content")).value
      val got   = s2.get(MemoryId("m")).value.value
      got.content shouldBe "new content"
      got.embedding.exists(_.sameElements(stale)) shouldBe false
      if (semantic) got.embedding.map(_.toSeq) shouldBe Some(embeddings.vectorOf("new content").toSeq)
  }

  it should "keep an embedding the update supplies with new content" in run { (s0, _) =>
    val supplied = Array(0f, 1f, 0f)
    val s1       = put(s0, memory("m", "old content").withEmbedding(Array(1f, 0f, 0f)))
    val s2       = s1.update(MemoryId("m"), _.copy(content = "new content").withEmbedding(supplied)).value
    s2.get(MemoryId("m")).value.value.embedding.map(_.toSeq) shouldBe Some(supplied.toSeq)
  }

  it should "replace: store what is added, delete what is removed, and skip a removal that is not there" in run {
    (s0, _) =>
      val s1 = put(s0, memory("a", "alpha"), memory("b", "beta"))
      val s2 = s1.replace(Seq(memory("c", "gamma")), Seq(MemoryId("a"), MemoryId("absent"))).value
      s2.recall(MemoryFilter.All, 10).value.map(_.id.value).sorted shouldBe Seq("b", "c")
  }

  // Codex on #3289: store, then delete, on every store
  it should "replace: delete a memory whose id is both added and removed" in run { (s0, _) =>
    val s1 = put(s0, memory("a", "alpha"), memory("b", "beta"))
    val s2 = s1.replace(Seq(memory("a", "alpha again"), memory("c", "gamma")), Seq(MemoryId("a"))).value
    s2.recall(MemoryFilter.All, 10).value.map(_.id.value).sorted shouldBe Seq("b", "c")
  }

  it should "delete one memory, and treat deleting a missing one as done" in run { (s0, _) =>
    val s1 = put(s0, memory("a", "alpha"), memory("b", "beta"))
    val s2 = s1.delete(MemoryId("a")).value.delete(MemoryId("absent")).value
    s2.recall(MemoryFilter.All, 10).value.map(_.id.value) shouldBe Seq("b")
    s2.search("alpha", 10, MemoryFilter.All).value.map(_.memory.id.value) should not contain "a"
  }

  it should "clear every memory, from the search index too" in run { (s0, _) =>
    val s1 = put(s0, memory("a", "alpha"), memory("b", "beta")).clear().value
    s1.count().value shouldBe 0L
    s1.search("alpha", 10, MemoryFilter.All).value shouldBe empty
  }

  it should "report stats that agree with what recall reads, without reading it (#3040)" in run { (s0, _) =>
    s0.stats.value shouldBe MemoryStore.statsOf(Seq.empty)
    val s1 = put(
      s0,
      memory("a", "alpha", at = 1).withMetadata(Map("entity_id" -> "e1", "conversation_id" -> "c1")),
      memory("b", "beta", at = 2).copy(memoryType = MemoryType.Task).withMetadata(Map("entity_id" -> "e1")),
      memory("c", "gamma", at = 3).copy(memoryType = MemoryType.Custom("Report")).withEmbedding(Array(1f, 0f, 0f))
    )
    s1.stats.value shouldBe MemoryStore.statsOf(s1.recall(MemoryFilter.All, 10).value)
  }

  it should "recall memories of one time by id, descending, and page past them with Preceding (#3040)" in run {
    (s0, _) =>
      val s1 = put(
        s0,
        memory("b", "two", at = 5),
        memory("c", "three", at = 5),
        memory("a", "one", at = 5),
        memory("z", "old", at = 1)
      )
      s1.recall(MemoryFilter.All, 10).value.map(_.id.value) shouldBe Seq("c", "b", "a", "z")
      val t    = t0.plusSeconds(5)
      val rest = s1.recall(MemoryFilter.Preceding(t, MemoryId("b")), 10).value.map(_.id.value)
      rest shouldBe Seq("a", "z")
      s1.count(MemoryFilter.Preceding(t, MemoryId("b"))).value shouldBe 2L
  }

  it should "say whether what it holds outlives it" in run((s0, _) => s0.durable shouldBe expectDurable)

  // ===== Limits and blank queries =====

  it should "return nothing for a limit or topK below 1, with any filter" in run { (s0, _) =>
    val s1     = put(s0, memory("a", "alpha"), memory("b", "alpha beta"))
    val custom = MemoryFilter.Custom(_ => true)
    for (n <- Seq(0, -1, Int.MinValue)) {
      s1.recall(MemoryFilter.All, n).value shouldBe empty
      s1.recall(custom, n).value shouldBe empty
      s1.recent(n, MemoryFilter.All).value shouldBe empty
      s1.search("alpha", n, MemoryFilter.All).value shouldBe empty
      s1.search("alpha", n, custom).value shouldBe empty
    }
  }

  it should "find nothing for a blank query, without calling the embedding service" in run { (s0, embeddings) =>
    val s1 = put(s0, memory("a", "alpha"))
    embeddings.calls.set(0)
    for (q <- Seq("", "   ", "\t\n")) s1.search(q, 10, MemoryFilter.All).value shouldBe empty
    embeddings.calls.get shouldBe 0
  }

  // ===== Scores =====

  it should "score the better match higher, scores within [0, 1] and non-increasing in result order" in run {
    (s0, embeddings) =>
      embeddings.define("scala functional programming", Array(1f, 0f, 0f))
      embeddings.define("scala functional programming language", Array(1f, 0f, 0f))
      embeddings.define("scala tooling", Array(1f, 1f, 0f))
      embeddings.define("cooking pasta at home", Array(-1f, 0f, 0f))
      val s1 = put(
        s0,
        memory("strong", "scala functional programming language"),
        memory("weak", "scala tooling"),
        memory("none", "cooking pasta at home")
      )
      val found = s1.search("scala functional programming", 10, MemoryFilter.All).value
      found.map(_.memory.id.value).take(2) shouldBe Seq("strong", "weak")
      found.head.score should be > found(1).score
      scoresOf(found).foreach(score => score should ((be >= 0.0).and(be <= 1.0)))
      scoresOf(found) shouldBe scoresOf(found).sorted(Ordering[Double].reverse)
  }

  // ===== Filters at the edges =====

  it should "honour a sub-millisecond time bound exactly" in run { (s0, _) =>
    val at = t0.plusSeconds(10)
    val s1 = put(s0, memory("m", "text", at = 10))
    def ids(after: Option[Instant], before: Option[Instant]) =
      s1.recall(MemoryFilter.ByTimeRange(after, before), 10).value.map(_.id.value)
    ids(Some(at.plusNanos(500_000)), None) shouldBe empty
    ids(Some(at.minusNanos(500_000)), None) shouldBe Seq("m")
    ids(None, Some(at.plusNanos(500_000))) shouldBe Seq("m")
    ids(None, Some(at.minusNanos(500_000))) shouldBe empty
    s1.count(MemoryFilter.ByTimeRange(Some(at.plusNanos(1)), None)).value shouldBe 0L
  }

  it should "match a metadata key exactly, never a key that ends in it after a quote" in run { (s0, _) =>
    val s1 = put(s0, memory("m", "text").withMetadata(Map("say \"a" -> "plain")))
    s1.recall(MemoryFilter.ByMetadata("a", "plain"), 10).value shouldBe empty
    s1.recall(MemoryFilter.HasMetadata("a"), 10).value shouldBe empty
    s1.count(MemoryFilter.ByMetadata("say \"a", "plain")).value shouldBe 1L
  }

  // ===== The vector score rule (semantic stores) =====

  it should "score (1 + cos) / 2: identical 1.0, orthogonal 0.5, opposite 0.0" in run { (s0, embeddings) =>
    assume(semantic, "a keyword store has no vector scores")
    embeddings.define("query", Array(1f, 0f))
    val s1 = put(
      s0,
      memory("same", "same").withEmbedding(Array(2f, 0f)),
      memory("orthogonal", "orthogonal").withEmbedding(Array(0f, 3f)),
      memory("opposite", "opposite").withEmbedding(Array(-1f, 0f))
    )
    val found = s1.search("query", 10, MemoryFilter.All).value.map(sm => sm.memory.id.value -> sm.score).toMap
    found("same") shouldBe 1.0 +- 1e-6
    found("orthogonal") shouldBe 0.5 +- 1e-6
    found("opposite") shouldBe 0.0 +- 1e-6
  }

  it should "leave out a memory whose embedding is all zeros, and answer a zero query without failing" in run {
    (s0, embeddings) =>
      assume(semantic, "a keyword store compares no vectors")
      embeddings.define("query", Array(1f, 0f))
      embeddings.define("zero query", Array(0f, 0f))
      val s1 = put(
        s0,
        memory("zero", "zero vector").withEmbedding(Array(0f, 0f)),
        memory("real", "real vector").withEmbedding(Array(1f, 1f))
      )
      s1.search("query", 10, MemoryFilter.All).value.map(_.memory.id.value) shouldBe Seq("real")
      val zero = s1.search("zero query", 10, MemoryFilter.All).value
      scoresOf(zero).foreach(score => score should ((be >= 0.0).and(be <= 1.0)))
  }

  it should "rank only the memories of the query's dimension, and refuse a store of none" in run { (s0, embeddings) =>
    assume(semantic, "a keyword store compares no vectors")
    embeddings.define("query", Array(1f, 0f, 0f))
    val s1 = put(
      s0,
      memory("three", "three dimensions").withEmbedding(Array(1f, 0f, 0f)),
      memory("four", "four dimensions").withEmbedding(Array(1f, 0f, 0f, 0f))
    )
    s1.search("query", 10, MemoryFilter.All).value.map(_.memory.id.value) shouldBe Seq("three")
    val s2 = s1.delete(MemoryId("three")).value
    s2.search("query", 10, MemoryFilter.All).left.value shouldBe a[ConfigurationError]
  }

  it should "store nothing when embedding fails" in run { (s0, embeddings) =>
    assume(semantic, "a keyword store embeds nothing")
    embeddings.failOn("refused text")
    val s1 = put(s0, memory("kept", "kept text"))
    s1.storeAll(Seq(memory("new", "fine text"), memory("bad", "refused text"))).isLeft shouldBe true
    s1.replace(Seq(memory("bad", "refused text")), Seq(MemoryId("kept"))).isLeft shouldBe true
    s1.recall(MemoryFilter.All, 10).value.map(_.id.value) shouldBe Seq("kept")
  }
}

object MemoryStoreContract {

  /**
   * An embedding service for the contract: a text it was given a vector for embeds as that vector, any other as a
   * deterministic 3-dimensional one; a text it was told to refuse fails. It counts its calls.
   */
  final class ContractEmbeddings extends EmbeddingService {
    private val vectors = scala.collection.concurrent.TrieMap.empty[String, Array[Float]]
    private val refused = scala.collection.concurrent.TrieMap.empty[String, Unit]
    val calls           = new AtomicInteger()

    def define(text: String, vector: Array[Float]): Unit = vectors.put(text, vector): Unit
    def failOn(text: String): Unit                       = refused.put(text, ()): Unit

    def vectorOf(text: String): Array[Float] =
      vectors.getOrElse(
        text, {
          val h = text.hashCode
          Array(1f + (h & 0xff) / 256f, ((h >> 8) & 0xff) / 256f, ((h >> 16) & 0xff) / 256f)
        }
      )

    override def embed(text: String): Result[Array[Float]] = {
      calls.incrementAndGet()
      if (refused.contains(text)) Left(ProcessingError("contract-embeddings", s"refused: $text"))
      else Right(vectorOf(text))
    }

    override def embedBatch(texts: Seq[String]): Result[Seq[Array[Float]]] =
      texts.foldLeft[Result[Vector[Array[Float]]]](Right(Vector.empty))((acc, t) =>
        acc.flatMap(v => embed(t).map(v :+ _))
      )

    override def dimensions: Int = 3
  }

  /** `body`, then `release`, even if `body` throws. */
  def after[A](release: => Unit)(body: => A): A = {
    val result = scala.util.Try(body)
    release
    result.get
  }
}
