package org.llm4s.vectorstore.testkit

import org.llm4s.annotation.Experimental
import org.llm4s.vectorstore.{ KeywordDocument, KeywordIndex, KeywordSearchResult }
import org.llm4s.vectorstore.MetadataFilter._

/**
 * The contract every [[org.llm4s.vectorstore.KeywordIndex]] must meet (#3000), as a ScalaTest suite: mix it into a
 * spec, implement [[newStore]] and [[releaseStore]], and every case below runs against your index:
 *
 * {{{
 * class SQLiteKeywordIndexContractSpec extends AnyFunSuite with KeywordIndexContract {
 *   protected def newStore(): KeywordIndex               = SQLiteKeywordIndex.inMemory().toOption.get
 *   protected def releaseStore(index: KeywordIndex): Unit = index.close()
 * }
 * }}}
 *
 * The cases:
 *
 *  - '''Reads and writes.''' `index`, `indexBatch`, `get`, `delete`, `deleteBatch`, `deleteByPrefix` (a literal
 *    prefix), `count` and `clear`, on an empty index, a populated one and after `clear`; re-indexing an existing id,
 *    through `index`, `indexBatch` or `update`, leaving none of its old terms searchable and the document found once.
 *  - '''Queries.''' A term finding the documents that hold it, best first, cut at `topK`; quotes, operator words and
 *    symbols, hyphens and colons searched as text rather than failing as syntax; a blank query answered with nothing;
 *    a query of only stopwords answered, not failed; a natural-language question finding a document that lacks some
 *    of its words; non-Latin text, both space-separated and unsegmented.
 *  - '''Filters.''' [[MetadataFilterMatrix]] in `search` and `searchWithHighlights`; filters on keys holding `.`, a
 *    space or `:`.
 *  - '''Metadata''' and content read back exactly as written; highlights that show the matched term.
 *
 * A backend with a known defect lists the case in [[knownFailures]] with its issue; one that cannot run a case lists
 * it in [[unsupported]] (see [[StoreContractCases]]).
 */
@Experimental
trait KeywordIndexContract extends StoreContractCases[KeywordIndex] {

  protected def contractName: String = "KeywordIndexContract"

  private def doc(id: String, content: String, meta: (String, String)*): KeywordDocument =
    KeywordDocument(id, content, meta.toMap)

  private def ids(results: Seq[KeywordSearchResult]): Set[String] = results.map(_.id).toSet

  private val corpus = Seq(
    doc("scala", "Scala is a functional and object oriented language for the JVM"),
    doc("scala2", "Scala Scala Scala: the scala compiler compiles scala code"),
    doc("java", "Java is an object oriented language for the JVM"),
    doc("paris", "Paris is the capital of France"),
    doc("quote", "He said \"hi\" and left the 5\" pipe behind; it's fine"),
    doc("hyphen", "A state-of-the-art compiler and the title: Scala in depth, with C++ notes"),
    doc("cyrillic", "привет мир и добрый день"),
    doc("greek", "καλημέρα κόσμε"),
    doc("cjk", "日本語のテキストを検索する")
  )

  private def withCorpus(index: KeywordIndex): Unit = ok(index.indexBatch(corpus))

  // ---------------------------------------------------------------- empty indexes and clear

  contractCase("an empty index answers every read with nothing") { index =>
    ok(index.get("missing")) shouldBe None
    ok(index.count()) shouldBe 0L
    ok(index.search("scala", 5)) shouldBe empty
    ok(index.searchWithHighlights("scala", 5)) shouldBe empty
  }

  contractCase("every delete succeeds on an empty index") { index =>
    ok(index.delete("missing")) shouldBe (())
    ok(index.deleteBatch(Seq("a", "b"))) shouldBe (())
    ok(index.deleteByPrefix("a")) shouldBe 0L
    ok(index.clear()) shouldBe (())
  }

  contractCase("after clear the index is empty, every delete succeeds and writes are accepted") { index =>
    withCorpus(index)
    ok(index.clear())
    ok(index.count()) shouldBe 0L
    ok(index.get("scala")) shouldBe None
    ok(index.search("scala", 5)) shouldBe empty
    ok(index.delete("scala")) shouldBe (())
    ok(index.deleteByPrefix("s")) shouldBe 0L
    ok(index.index(doc("again", "scala again")))
    ok(index.search("scala", 5)).map(_.id) shouldBe Seq("again")
  }

  // ---------------------------------------------------------------- reads and writes

  contractCase("index and get round-trip a document, its metadata whatever it holds") { index =>
    val meta = Map(
      "quote"      -> "say \"hi\"",
      "backslash"  -> "C:\\path",
      "newline"    -> "one\ntwo",
      "tab"        -> "a\tb",
      "unicode"    -> "日本語 ünï 😀",
      "percent"    -> "50%",
      "underscore" -> "a_b",
      "a.b"        -> "dotted key",
      "with space" -> "spaced key",
      "ns:key"     -> "colon key"
    )
    val d = KeywordDocument("m1", "content with \"quotes\", \\ and\nnewlines", meta)
    ok(index.index(d))
    ok(index.get("m1")) shouldBe Some(d)
    ok(index.search("content", 5)).map(r => (r.id, r.content, r.metadata)) shouldBe Seq(("m1", d.content, meta))
  }

  contractCase("indexBatch indexes every document and count counts them") { index =>
    withCorpus(index)
    ok(index.indexBatch(Seq.empty)) shouldBe (())
    ok(index.count()) shouldBe corpus.size.toLong
    ok(index.get("java")).map(_.content) shouldBe Some(corpus.find(_.id == "java").get.content)
  }

  contractCase("delete and deleteBatch remove only the named documents") { index =>
    withCorpus(index)
    ok(index.delete("scala"))
    ok(index.deleteBatch(Seq("java", "missing")))
    ok(index.deleteBatch(Seq.empty)) shouldBe (())
    ok(index.get("scala")) shouldBe None
    ok(index.get("java")) shouldBe None
    ids(ok(index.search("scala", 10))) shouldBe Set("scala2", "hyphen")
    ok(index.count()) shouldBe (corpus.size - 2).toLong
  }

  contractCase("deleteByPrefix removes the ids with that literal prefix and says how many") { index =>
    val all = Seq("doc-1", "doc-10", "doc-2", "docs", "a_1", "ab1", "a%1", "axx")
    ok(index.indexBatch(all.map(id => doc(id, "common words"))))
    ok(index.deleteByPrefix("doc-1")) shouldBe 2L
    ok(index.deleteByPrefix("a_")) shouldBe 1L
    ok(index.deleteByPrefix("a%")) shouldBe 1L
    ok(index.deleteByPrefix("zzz")) shouldBe 0L
    ids(ok(index.search("common", 20))) shouldBe Set("doc-2", "docs", "ab1", "axx")
  }

  contractCase("re-indexing an id through index leaves none of its old terms searchable") { index =>
    ok(index.index(doc("d1", "alpha beta gamma")))
    ok(index.index(doc("d1", "delta epsilon gamma")))
    ok(index.search("alpha", 5)) shouldBe empty
    ok(index.search("gamma", 5)).map(_.id) shouldBe Seq("d1")
    ok(index.search("delta", 5)).map(_.content) shouldBe Seq("delta epsilon gamma")
    ok(index.count()) shouldBe 1L
  }

  contractCase("re-indexing ids through indexBatch or update leaves none of their old terms searchable") { index =>
    ok(index.indexBatch(Seq(doc("d1", "alpha gamma"), doc("d2", "alpha gamma"))))
    ok(index.indexBatch(Seq(doc("d1", "delta gamma"))))
    ok(index.update(doc("d2", "delta gamma")))
    ok(index.search("alpha", 5)) shouldBe empty
    ok(index.search("gamma", 5)).map(_.id).sorted shouldBe Seq("d1", "d2")
    ok(index.count()) shouldBe 2L
  }

  // ---------------------------------------------------------------- queries

  contractCase("a term finds the documents that hold it, best first, cut at topK") { index =>
    withCorpus(index)
    val all = ok(index.search("scala", 10))
    ids(all) shouldBe Set("scala", "scala2", "hyphen")
    all.map(_.score) shouldBe all.map(_.score).sorted(Ordering[Double].reverse)
    all.head.id shouldBe "scala2"
    ok(index.search("scala", 2)).size shouldBe 2
    ok(index.search("nothinglikethis", 5)) shouldBe empty
  }

  contractCase("a query with quotes is searched as text, not failed as syntax") { index =>
    withCorpus(index)
    ids(ok(index.search("he said \"hi", 10))) should contain("quote")
    ids(ok(index.search("5\" pipe", 10))) should contain("quote")
    ids(ok(index.search("\"it's\" fine", 10))) should contain("quote")
    ok(index.search("\"", 10))
  }

  contractCase("operator words and symbols are searched, not failed as syntax") { index =>
    withCorpus(index)
    Seq(
      "scala AND java",
      "scala OR java",
      "NOT scala",
      "scala NEAR java",
      "scala*",
      "+scala -java",
      "(scala)",
      "^scala"
    )
      .foreach(q => withClue(q)(ok(index.search(q, 10))))
    (ids(ok(index.search("scala OR java", 10))) should contain).allOf("java", "scala")
  }

  contractCase("hyphenated and colon terms are searched, not failed as syntax") { index =>
    withCorpus(index)
    ids(ok(index.search("state-of-the-art", 10))) should contain("hyphen")
    ids(ok(index.search("title:", 10))) should contain("hyphen")
    ok(index.search("title:scala", 10))
    ok(index.search("C++", 10))
  }

  contractCase("a blank query is answered with nothing") { index =>
    withCorpus(index)
    ok(index.search("", 10)) shouldBe empty
    ok(index.search("   ", 10)) shouldBe empty
    ok(index.searchWithHighlights("", 10)) shouldBe empty
  }

  contractCase("a query of only stopwords is answered, not failed") { index =>
    withCorpus(index)
    ok(index.search("the of and is", 10))
  }

  contractCase("a natural-language question finds a document that lacks some of its words") { index =>
    withCorpus(index)
    ok(index.search("what is the capital of France", 10)).map(_.id) should contain("paris")
  }

  contractCase("space-separated non-Latin text is found") { index =>
    withCorpus(index)
    ok(index.search("привет", 10)).map(_.id) shouldBe Seq("cyrillic")
    ok(index.search("κόσμε", 10)).map(_.id) shouldBe Seq("greek")
  }

  contractCase("unsegmented text is found by a word inside it") { index =>
    withCorpus(index)
    ok(index.search("日本語", 10)).map(_.id) shouldBe Seq("cjk")
  }

  // ---------------------------------------------------------------- filters and highlights

  private def matrixDocs: Seq[KeywordDocument] =
    MetadataFilterMatrix.records.map { case (id, m) => KeywordDocument(id, "common words", m) }

  contractCase("the filter matrix selects the same documents in search") { index =>
    ok(index.indexBatch(matrixDocs))
    MetadataFilterMatrix.cases.foreach(c =>
      withClue(c.filter)(ids(ok(index.search("common", 10, Some(c.filter)))) shouldBe c.expected)
    )
  }

  contractCase("the filter matrix selects the same documents in searchWithHighlights") { index =>
    ok(index.indexBatch(matrixDocs))
    MetadataFilterMatrix.cases.foreach { c =>
      withClue(c.filter)(ids(ok(index.searchWithHighlights("common", 10, filter = Some(c.filter)))) shouldBe c.expected)
    }
  }

  contractCase("filters select by keys holding a dot, a space or a colon") { index =>
    ok(
      index.indexBatch(
        Seq(
          doc("k1", "common", "a.b" -> "x", "with space" -> "y", "ns:key" -> "z"),
          doc("k2", "common", "a"   -> "x", "with"       -> "y", "ns"     -> "z")
        )
      )
    )
    Seq(Equals("a.b", "x"), Equals("with space", "y"), Equals("ns:key", "z"), HasKey("a.b"))
      .foreach(f => withClue(f)(ok(index.search("common", 10, Some(f))).map(_.id) shouldBe Seq("k1")))
  }

  contractCase("searchWithHighlights shows the matched term in each result") { index =>
    withCorpus(index)
    val results = ok(index.searchWithHighlights("compiler", 10))
    ids(results) shouldBe Set("scala2", "hyphen")
    results.foreach(r => withClue(r.id)(r.highlights.exists(_.toLowerCase.contains("compiler")) shouldBe true))
  }

  registerConsistencyCheck()
}
