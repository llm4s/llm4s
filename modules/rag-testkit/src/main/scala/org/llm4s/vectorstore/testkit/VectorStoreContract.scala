package org.llm4s.vectorstore.testkit

import org.llm4s.annotation.Experimental
import org.llm4s.vectorstore.{ MetadataFilter, ScoredRecord, VectorRecord, VectorStore }
import org.llm4s.vectorstore.MetadataFilter._

import scala.util.Random

/**
 * The contract every [[org.llm4s.vectorstore.VectorStore]] must meet (#3000), as a ScalaTest suite: mix it into a
 * spec, implement [[newStore]] and [[releaseStore]], and every case below runs against your store:
 *
 * {{{
 * class SQLiteVectorStoreContractSpec extends AnyFunSuite with VectorStoreContract {
 *   protected def newStore(): VectorStore              = SQLiteVectorStore.inMemory().toOption.get
 *   protected def releaseStore(store: VectorStore): Unit = store.close()
 * }
 * }}}
 *
 * The cases:
 *
 *  - '''Reads and writes.''' `upsert`, `upsertBatch`, `get`, `getBatch` (missing ids skipped), `delete`,
 *    `deleteBatch`, `deleteByPrefix` (a literal prefix: `%` and `_` are not wildcards), `deleteByFilter` and `clear`,
 *    on an empty store, on a populated one and after `clear`; an upsert of an existing id replacing its embedding,
 *    content and metadata.
 *  - '''Filters.''' [[MetadataFilterMatrix]] in `list`, `count`, `search` and `deleteByFilter`; filters on keys
 *    holding `.`, a space or `:`.
 *  - '''Metadata''' read back exactly as written: `"`, `\`, newline, tab, unicode, `%`, `_`, and keys with `.`, a
 *    space or `:`.
 *  - '''Search.''' Results best first, cut at `topK`, the filter applied before the cut; scores of `(1 + cos) / 2`
 *    (identical `1`, orthogonal `0.5`, opposite `0`); a zero vector stored or queried never failing a search or
 *    giving a score outside `[0, 1]`; a query of another dimension refused.
 *  - '''Listing.''' `list` in insertion order, and pages that together hold every record once; `count` with and
 *    without a filter.
 *  - '''Batches''' larger than a statement's parameter limit or a server's request limit ([[largeIdBatch]],
 *    [[largeUpsertBatch]]).
 *
 * A backend with a known defect lists the case in [[knownFailures]] with its issue; one that cannot run a case lists
 * it in [[unsupported]] (see [[StoreContractCases]]).
 */
@Experimental
trait VectorStoreContract extends StoreContractCases[VectorStore] {

  protected def contractName: String = "VectorStoreContract"

  /**
   * Whether the backend evaluates `Contains`. A search engine that has no substring query sets this to `false`, and
   * the matrix cases leave out the filters that use it.
   */
  protected def supportsContains: Boolean = true

  /** How many ids the large `getBatch` and `deleteBatch` take: past Postgres's 65,535 bind parameters. */
  protected def largeIdBatch: Int = 70000

  /** How many records, of how many dimensions, the large `upsertBatch` writes: past Qdrant's 32 MB request limit. */
  protected def largeUpsertBatch: (Int, Int) = (5000, 1536)

  private val Tolerance = 1e-3

  /** Values and keys a store must keep exactly: quotes, escapes, control characters, wildcards, unicode. */
  private val Awkward: Map[String, String] = Map(
    "quote"      -> "say \"hi\"",
    "backslash"  -> "C:\\path\\to",
    "newline"    -> "one\ntwo",
    "tab"        -> "a\tb",
    "unicode"    -> "日本語 ünïcödé 😀",
    "percent"    -> "50%",
    "underscore" -> "a_b",
    "apostrophe" -> "it's",
    "json"       -> "{\"k\": [1, 2]}",
    "a.b"        -> "dotted key",
    "with space" -> "spaced key",
    "ns:key"     -> "colon key"
  )

  private def rec(id: String, embedding: Array[Float], meta: (String, String)*): VectorRecord =
    VectorRecord(id, embedding, Some(s"content of $id"), meta.toMap)

  private def unit(degrees: Double): Array[Float] = {
    val r = math.toRadians(degrees)
    Array(math.cos(r).toFloat, math.sin(r).toFloat, 0f)
  }

  /** The record as stored, its embedding compared to within a tolerance: a cosine backend may normalise it. */
  private def assertSameRecord(actual: VectorRecord, expected: VectorRecord): Unit = {
    actual.id shouldBe expected.id
    actual.content shouldBe expected.content
    actual.metadata shouldBe expected.metadata
    actual.embedding.length shouldBe expected.embedding.length
    actual.embedding.zip(expected.embedding).foreach { case (a, e) => a.toDouble shouldBe e.toDouble +- 1e-4 }
  }

  private def ids(records: Seq[VectorRecord]): Set[String] = records.map(_.id).toSet

  private def matrixCases: Seq[MetadataFilterMatrix.Case] =
    MetadataFilterMatrix.cases.filter(c => supportsContains || !c.usesContains)

  private def matrixRecords: Seq[VectorRecord] =
    MetadataFilterMatrix.records.map { case (id, m) => VectorRecord(id, Array(1f, 0f, 0f), Some(id), m) }

  private def assertScoresInRange(results: Seq[ScoredRecord]): Unit =
    results.foreach { r =>
      withClue(s"score of ${r.record.id}: ") {
        r.score.isNaN shouldBe false
        r.score should ((be >= 0.0).and(be <= 1.0))
      }
    }

  // ---------------------------------------------------------------- empty stores and clear

  contractCase("an empty store answers every read with nothing") { store =>
    ok(store.get("missing")) shouldBe None
    ok(store.getBatch(Seq("a", "b"))) shouldBe empty
    ok(store.count()) shouldBe 0L
    ok(store.list()) shouldBe empty
    ok(store.search(unit(0), 5)) shouldBe empty
  }

  contractCase("an empty store answers a filtered count and list with nothing") { store =>
    ok(store.count(Some(Equals("lang", "en")))) shouldBe 0L
    ok(store.list(filter = Some(Equals("lang", "en")))) shouldBe empty
  }

  contractCase("every delete succeeds on an empty store") { store =>
    ok(store.delete("missing")) shouldBe (())
    ok(store.deleteBatch(Seq("a", "b"))) shouldBe (())
    ok(store.deleteByPrefix("a")) shouldBe 0L
    ok(store.deleteByFilter(Equals("lang", "en"))) shouldBe 0L
    ok(store.clear()) shouldBe (())
  }

  contractCase("after clear the store is empty, every delete succeeds and writes are accepted") { store =>
    ok(store.upsertBatch(Seq(rec("a", unit(0)), rec("b", unit(90)))))
    ok(store.clear())
    ok(store.get("a")) shouldBe None
    ok(store.search(unit(0), 5)) shouldBe empty
    ok(store.delete("a")) shouldBe (())
    ok(store.deleteBatch(Seq("a", "b"))) shouldBe (())
    ok(store.deleteByPrefix("a")) shouldBe 0L
    ok(store.deleteByFilter(Equals("lang", "en"))) shouldBe 0L
    ok(store.upsert(rec("c", unit(0))))
    ok(store.get("c")).map(_.id) shouldBe Some("c")
    ok(store.search(unit(0), 5)).map(_.record.id) shouldBe Seq("c")
  }

  // ---------------------------------------------------------------- reads and writes

  contractCase("upsert and get round-trip a record, with and without content") { store =>
    val withContent    = rec("with", unit(0), "lang" -> "en")
    val withoutContent = VectorRecord("without", unit(90), None, Map("lang" -> "fr"))
    ok(store.upsert(withContent))
    ok(store.upsert(withoutContent))
    assertSameRecord(ok(store.get("with")).getOrElse(fail("record 'with' not found")), withContent)
    assertSameRecord(ok(store.get("without")).getOrElse(fail("record 'without' not found")), withoutContent)
  }

  contractCase("upsertBatch and getBatch handle several records, skipping missing ids") { store =>
    val records = (1 to 5).map(i => rec(s"b$i", unit(i * 10.0), "n" -> i.toString))
    ok(store.upsertBatch(records))
    ok(store.upsertBatch(Seq.empty)) shouldBe (())
    val found = ok(store.getBatch(Seq("b1", "b3", "missing", "b5")))
    ids(found) shouldBe Set("b1", "b3", "b5")
    found.foreach(r => assertSameRecord(r, records.find(_.id == r.id).get))
    ok(store.getBatch(Seq.empty)) shouldBe empty
    ok(store.search(unit(0), 10)).map(_.record.id).toSet shouldBe records.map(_.id).toSet
  }

  contractCase("upsert of an existing id replaces its embedding, content and metadata") { store =>
    ok(store.upsert(VectorRecord("x", unit(0), Some("old"), Map("a" -> "1", "b" -> "2"))))
    ok(store.upsert(VectorRecord("x", unit(90), Some("new"), Map("a" -> "3"))))
    assertSameRecord(ok(store.get("x")).get, VectorRecord("x", unit(90), Some("new"), Map("a" -> "3")))
    val hits = ok(store.search(unit(90), 5))
    hits.map(_.record.id) shouldBe Seq("x")
    hits.head.score shouldBe 1.0 +- Tolerance
    ok(store.search(unit(90), 5, Some(HasKey("b")))) shouldBe empty
    ok(store.search(unit(90), 5, Some(Equals("a", "1")))) shouldBe empty
  }

  contractCase("upsertBatch of existing ids replaces them") { store =>
    ok(store.upsertBatch(Seq(rec("p", unit(0), "v" -> "old"), rec("q", unit(0), "v" -> "old"))))
    ok(store.upsertBatch(Seq(rec("p", unit(90), "v" -> "new"), rec("q", unit(90), "v" -> "new"))))
    ok(store.getBatch(Seq("p", "q"))).map(_.metadata("v")).toSet shouldBe Set("new")
    val hits = ok(store.search(unit(90), 5))
    hits.map(_.record.id).sorted shouldBe Seq("p", "q")
    hits.foreach(_.score shouldBe 1.0 +- Tolerance)
  }

  contractCase("delete and deleteBatch remove only the named records") { store =>
    ok(store.upsertBatch((1 to 5).map(i => rec(s"d$i", unit(i * 10.0)))))
    ok(store.delete("d1"))
    ok(store.deleteBatch(Seq("d2", "d3", "missing")))
    ok(store.deleteBatch(Seq.empty)) shouldBe (())
    ids(ok(store.getBatch((1 to 5).map(i => s"d$i")))) shouldBe Set("d4", "d5")
    ok(store.search(unit(0), 10)).map(_.record.id).toSet shouldBe Set("d4", "d5")
  }

  contractCase("deleteByPrefix removes the ids with that literal prefix and says how many") { store =>
    val all = Seq("doc-1", "doc-10", "doc-2", "docs", "a_1", "ab1", "a%1", "axx")
    ok(store.upsertBatch(all.map(id => rec(id, unit(0)))))
    ok(store.deleteByPrefix("doc-1")) shouldBe 2L
    ok(store.deleteByPrefix("a_")) shouldBe 1L
    ok(store.deleteByPrefix("a%")) shouldBe 1L
    ok(store.deleteByPrefix("zzz")) shouldBe 0L
    ids(ok(store.getBatch(all))) shouldBe Set("doc-2", "docs", "ab1", "axx")
  }

  contractCase("deleteByFilter removes the matching records and says how many") { store =>
    ok(store.upsertBatch(matrixRecords))
    ok(store.deleteByFilter(Equals("lang", "en"))) shouldBe 2L
    ids(ok(store.getBatch(MetadataFilterMatrix.allIds.toSeq))) shouldBe Set("r2", "r3", "r5")
  }

  // ---------------------------------------------------------------- filters

  contractCase("the filter matrix selects the same records in list") { store =>
    ok(store.upsertBatch(matrixRecords))
    matrixCases.foreach(c =>
      withClue(c.filter)(ids(ok(store.list(limit = 100, filter = Some(c.filter)))) shouldBe c.expected)
    )
  }

  contractCase("the filter matrix selects the same records in count") { store =>
    ok(store.upsertBatch(matrixRecords))
    matrixCases.foreach(c => withClue(c.filter)(ok(store.count(Some(c.filter))) shouldBe c.expected.size.toLong))
  }

  contractCase("the filter matrix selects the same records in search") { store =>
    ok(store.upsertBatch(matrixRecords))
    matrixCases.foreach { c =>
      withClue(c.filter)(
        ok(store.search(Array(1f, 0f, 0f), 10, Some(c.filter))).map(_.record.id).toSet shouldBe c.expected
      )
    }
  }

  contractCase("the filter matrix selects the same records in deleteByFilter") { store =>
    matrixCases.foreach { c =>
      withClue(c.filter) {
        ok(store.upsertBatch(matrixRecords))
        ok(store.deleteByFilter(c.filter)) shouldBe c.expected.size.toLong
        ids(ok(store.getBatch(MetadataFilterMatrix.allIds.toSeq))) shouldBe MetadataFilterMatrix.allIds.diff(c.expected)
      }
    }
  }

  contractCase("filters select by keys holding a dot, a space or a colon") { store =>
    ok(
      store.upsertBatch(
        Seq(
          rec("k1", unit(0), "a.b" -> "x", "with space" -> "y", "ns:key" -> "z"),
          rec("k2", unit(0), "a"   -> "x", "with"       -> "y", "ns"     -> "z")
        )
      )
    )
    Seq[MetadataFilter](Equals("a.b", "x"), Equals("with space", "y"), Equals("ns:key", "z"), HasKey("a.b"))
      .foreach(f => withClue(f)(ok(store.search(unit(0), 10, Some(f))).map(_.record.id) shouldBe Seq("k1")))
  }

  // ---------------------------------------------------------------- metadata

  contractCase("metadata and content are read back exactly as written, whatever they hold") { store =>
    val record = VectorRecord("m1", unit(0), Some("content with \"quotes\", \\ and\nnewlines 日本語"), Awkward)
    ok(store.upsert(record))
    assertSameRecord(ok(store.get("m1")).get, record)
    assertSameRecord(ok(store.search(unit(0), 1)).head.record, record)
  }

  contractCase("Equals matches metadata values exactly, whatever they hold") { store =>
    ok(store.upsertBatch(Seq(rec("m1", unit(0), Awkward.toSeq*), rec("m2", unit(0), "quote" -> "say hi"))))
    Awkward.foreach { case (k, v) =>
      withClue(s"$k -> $v: ")(ok(store.search(unit(0), 10, Some(Equals(k, v)))).map(_.record.id) shouldBe Seq("m1"))
    }
  }

  // ---------------------------------------------------------------- search

  contractCase("search returns the nearest records best first, cut at topK") { store =>
    val angles = Seq("s0" -> 0.0, "s30" -> 30.0, "s60" -> 60.0, "s90" -> 90.0, "s150" -> 150.0)
    ok(store.upsertBatch(angles.map { case (id, a) => rec(id, unit(a)) }))
    val all = ok(store.search(unit(0), 10))
    all.map(_.record.id) shouldBe Seq("s0", "s30", "s60", "s90", "s150")
    all.map(_.score) shouldBe all.map(_.score).sorted(Ordering[Double].reverse)
    ok(store.search(unit(0), 2)).map(_.record.id) shouldBe Seq("s0", "s30")
    ok(store.search(unit(0), 1)).map(_.record.id) shouldBe Seq("s0")
  }

  contractCase("search applies the filter before the topK cut") { store =>
    val near = (1 to 10).map(i => rec(s"near$i", unit(i.toDouble)))
    val far  = Seq(rec("far1", unit(80), "tag" -> "x"), rec("far2", unit(85), "tag" -> "x"))
    ok(store.upsertBatch(near ++ far))
    ok(store.search(unit(0), 2, Some(Equals("tag", "x")))).map(_.record.id) shouldBe Seq("far1", "far2")
  }

  contractCase("search scores are (1 + cos) / 2: identical 1, orthogonal 0.5, opposite 0") { store =>
    ok(
      store.upsertBatch(Seq(rec("same", unit(0)), rec("cos60", unit(60)), rec("orth", unit(90)), rec("opp", unit(180))))
    )
    val scores = ok(store.search(unit(0), 10)).map(r => r.record.id -> r.score).toMap
    scores("same") shouldBe 1.0 +- Tolerance
    scores("cos60") shouldBe 0.75 +- Tolerance
    scores("orth") shouldBe 0.5 +- Tolerance
    scores("opp") shouldBe 0.0 +- Tolerance
  }

  contractCase("a stored zero vector is refused or never fails a search") { store =>
    ok(store.upsert(rec("normal", unit(0))))
    if (!isLeft(store.upsert(rec("zero", Array(0f, 0f, 0f))))) {
      val results = ok(store.search(unit(0), 10))
      results.map(_.record.id).toSet shouldBe Set("normal", "zero")
      assertScoresInRange(results)
    }
    ok(store.search(unit(0), 10)).map(_.record.id) should contain("normal")
  }

  contractCase("a zero query vector is refused or answered with scores in range") { store =>
    ok(store.upsertBatch(Seq(rec("a", unit(0)), rec("b", unit(90)))))
    val zero = Array(0f, 0f, 0f)
    if (!isLeft(store.search(zero, 10))) assertScoresInRange(ok(store.search(zero, 10)))
  }

  contractCase("a query of another dimension is refused") { store =>
    ok(store.upsertBatch(Seq(rec("a", unit(0)), rec("b", unit(90)))))
    isLeft(store.search(Array(1f, 0f), 10)) shouldBe true
    isLeft(store.search(Array(1f, 0f, 0f, 0f), 10)) shouldBe true
  }

  // ---------------------------------------------------------------- list and count

  contractCase("list returns records in insertion order") { store =>
    val order = Seq("c", "a", "e", "b", "d")
    order.foreach(id => ok(store.upsert(rec(id, unit(0)))))
    ok(store.list()).map(_.id) shouldBe order
  }

  contractCase("list pages hold every record once, without repeats or skips") { store =>
    val all = (1 to 23).map(i => rec(f"p$i%02d", unit(i.toDouble), "half" -> (if (i % 2 == 0) "even" else "odd")))
    ok(store.upsertBatch(all))
    val pages = (0 until 5).map(p => ok(store.list(limit = 5, offset = p * 5)).map(_.id))
    pages.map(_.size) shouldBe Seq(5, 5, 5, 5, 3)
    pages.flatten.distinct.size shouldBe 23
    pages.flatten.toSet shouldBe all.map(_.id).toSet
    ok(store.list(limit = 5, offset = 25)) shouldBe empty
    val even = (0 until 3).flatMap(p =>
      ok(store.list(limit = 5, offset = p * 5, filter = Some(Equals("half", "even")))).map(_.id)
    )
    even.distinct.size shouldBe 11
    even.toSet shouldBe all.filter(_.metadata("half") == "even").map(_.id).toSet
  }

  contractCase("count counts every record, or those a filter selects") { store =>
    ok(store.upsertBatch((1 to 7).map(i => rec(s"c$i", unit(i.toDouble), "lang" -> (if (i <= 3) "en" else "fr")))))
    ok(store.count()) shouldBe 7L
    ok(store.count(Some(Equals("lang", "en")))) shouldBe 3L
    ok(store.count(Some(Equals("lang", "de")))) shouldBe 0L
  }

  // ---------------------------------------------------------------- large batches

  contractCase("getBatch and deleteBatch take more ids than one statement's parameter limit") { store =>
    ok(store.upsertBatch(Seq(rec("keep", unit(0)), rec("hit1", unit(10)), rec("hit2", unit(20)))))
    val many = (0 until largeIdBatch).map(i => s"absent-$i") ++ Seq("hit1", "hit2")
    ids(ok(store.getBatch(many))) shouldBe Set("hit1", "hit2")
    ok(store.deleteBatch(many))
    ids(ok(store.getBatch(Seq("keep", "hit1", "hit2")))) shouldBe Set("keep")
  }

  contractCase("upsertBatch takes a batch larger than one request") { store =>
    val (count, dims) = largeUpsertBatch
    val random        = new Random(3000)
    val records       = (0 until count).map(i => VectorRecord(s"big-$i", Array.fill(dims)(random.nextFloat() - 0.5f)))
    ok(store.upsertBatch(records))
    ids(ok(store.getBatch(records.map(_.id)))).size shouldBe count
  }

  registerConsistencyCheck()
}
