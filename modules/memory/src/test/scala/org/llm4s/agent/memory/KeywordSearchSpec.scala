package org.llm4s.agent.memory

import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.DriverManager
import java.util.Locale
import scala.util.Using

/**
 * Keyword search matches whole words (#1594).
 *
 * `InMemoryStore` used to split the query on whitespace only and test each piece with `String.contains`, so short
 * words matched inside longer ones (`i` in "Berlin", `or` in "works") and punctuation stayed on the query's words
 * (`java?` never matched "Java"). It now splits both sides into words the way `SQLiteMemoryStore`'s FTS5 index does
 * (`unicode61`, `remove_diacritics=1`): the last sections check `KeywordTokens` against FTS5 for every code point, and
 * the two stores against each other query by query.
 */
class KeywordSearchSpec extends AnyFlatSpec with Matchers {

  private def right[A](result: Result[A]): A = result.fold(e => fail(e.message), identity)

  private def fact(id: String, content: String): Memory = Memory.userFact(content).copy(id = MemoryId(id))

  private def storeOf(memories: Memory*): InMemoryStore = right(InMemoryStore.withMemories(memories))

  private def found(store: MemoryStore, query: String): Set[String] =
    right(store.search(query, topK = 100)).map(_.memory.id.value).toSet

  private val scala  = fact("scala", "Prefers Scala over Java")
  private val berlin = fact("berlin", "Works in the Berlin office")

  /** Memories and queries both kinds of store answer, for the parity checks at the end. */
  private val parityMemories: Seq[Memory] = Seq(
    scala,
    berlin,
    fact("doing", "Doing laundry on Sundays"),
    fact("me", "I like tea"),
    fact("p", "Likes: Scala, Haskell (and OCaml)."),
    fact("w", "Works with scalability tooling"),
    fact("fr", "L'ÉCOLE du soir"),
    fact("ru", "Живёт в Москве"),
    fact("v", "Upgraded to Scala 3.7 in 2026"),
    fact("el", "Αθήνα, Ελλάδα"),
    fact("ru2", "Мой дом"),
    fact("vi", "Tiếng Việt"),
    fact("hi", "मुझे चाय पसंद है"),
    fact("sigma", "Ο κόσμος"),
    fact("pua", "Status \uE001 green"),
    fact("emoji", "love😀you"),
    fact("q", "\"Berlin\"-based team"),
    fact("in", "Based in Berlin"),
    fact("nfd", "Cafe\u0301 au lait")
  )

  private val parityQueries: Seq[String] = Seq(
    "Which language do I prefer, Scala or Java?",
    "What do I like?",
    "i",
    "or",
    "do",
    "java?",
    "(haskell)",
    "ocaml!",
    "work",
    "WORKS",
    "scala",
    "ecole",
    "école",
    "école",
    "москве",
    "2026",
    "202",
    // Accents are removed only from a Latin letter that carries exactly one
    "ελλαδα",
    "αθηνα",
    "ελλάδα",
    "живет",
    "живёт",
    "мои",
    "мой",
    "viet",
    "tieng",
    "việt",
    "tiê\u0301ng",
    "cafe",
    "café",
    // Combining marks other than those accents separate words; a query word is a phrase of its words
    "मुझ",
    "मुझे",
    "चा",
    "चम",
    // Greek final sigma, private-use and code points newer than SQLite's Unicode tables
    "κοσμοσ",
    "ΚΌΣΜΟΣ",
    "κοσμος",
    "\uE001",
    "love",
    "love😀you",
    "berlin-based",
    "based-berlin",
    "?!, ..."
  )

  // ===== The issue's reproducer =====

  "InMemoryStore keyword search" should "return only the Scala memory for the issue's language question" in {
    found(storeOf(scala, berlin), "Which language do I prefer, Scala or Java?") shouldBe Set("scala")
  }

  it should "return nothing for a question that shares no word with any memory" in {
    found(storeOf(scala, berlin), "What do I like?") shouldBe empty
  }

  it should "keep unrelated memories out of the context SimpleMemoryManager assembles" in {
    val manager = right(
      SimpleMemoryManager.empty
        .recordUserFact("Prefers Scala over Java")
        .flatMap(_.recordUserFact("Works in the Berlin office"))
    )

    val language = right(manager.getRelevantContext("Which language do I prefer, Scala or Java?"))
    language should include("Prefers Scala over Java")
    (language should not).include("Berlin")

    val unrelated = right(manager.getRelevantContext("What do I like?"))
    (unrelated should not).include("Scala")
    (unrelated should not).include("Berlin")
  }

  // ===== Consequence 1: short words no longer match almost everything =====

  it should "not match a one- or two-letter query word inside longer words" in {
    val store = storeOf(berlin, fact("doing", "Doing laundry on Sundays"), fact("arena", "Visited a large arena"))
    found(store, "i") shouldBe empty
    found(store, "or") shouldBe empty
    found(store, "do") shouldBe empty
    found(store, "on") shouldBe Set("doing")
    found(store, "a") shouldBe Set("arena")
  }

  it should "still match a short word that is a whole word of the memory" in {
    val store = storeOf(fact("me", "I like tea"), berlin)
    found(store, "i") shouldBe Set("me")
    found(store, "in") shouldBe Set("berlin")
  }

  // ===== Consequence 2: punctuation no longer sticks to query words =====

  it should "match java? and java to the same memories" in {
    val store = storeOf(scala, berlin, fact("js", "JavaScript in the browser"))
    found(store, "java?") shouldBe found(store, "java")
    found(store, "java?") shouldBe Set("scala")
  }

  it should "ignore punctuation on either side" in {
    val store = storeOf(fact("p", "Likes: Scala, Haskell (and OCaml)."), fact("q", "\"Berlin\"-based team"))
    found(store, "scala") shouldBe Set("p")
    found(store, "ocaml!") shouldBe Set("p")
    found(store, "(haskell)") shouldBe Set("p")
    found(store, "berlin") shouldBe Set("q")
    found(store, "based") shouldBe Set("q")
  }

  it should "return nothing for a query of punctuation only" in {
    found(storeOf(scala, berlin), "?!, ...") shouldBe empty
  }

  // ===== Consequence 3: matching respects word boundaries =====

  it should "not match a word inside a longer word, at the start, middle or end" in {
    val store = storeOf(fact("w", "Works with scalability tooling"))
    found(store, "work") shouldBe empty
    found(store, "scala") shouldBe empty
    found(store, "ability") shouldBe empty
    found(store, "tool") shouldBe empty
    found(store, "works") shouldBe Set("w")
  }

  // ===== Case and Unicode =====

  it should "match regardless of case" in {
    val store = storeOf(scala)
    found(store, "SCALA") shouldBe Set("scala")
    found(store, "sCaLa jAvA") shouldBe Set("scala")
  }

  it should "split and fold words in any script" in {
    val store = storeOf(
      fact("ru", "Живёт в Москве"),
      fact("fr", "L'ÉCOLE du soir"),
      fact("el", "Αθήνα, Ελλάδα"),
      fact("hi", "मुझे चाय पसंद है")
    )
    found(store, "москве") shouldBe Set("ru")
    found(store, "école") shouldBe Set("fr")
    found(store, "ecole") shouldBe Set("fr")
    found(store, "e\u0301cole") shouldBe Set("fr")
    found(store, "ελλάδα") shouldBe Set("el")
    found(store, "चाय?") shouldBe Set("hi")
  }

  it should "remove an accent only from a Latin letter that carries exactly one, as FTS5 does" in {
    val store = storeOf(
      fact("el", "Αθήνα, Ελλάδα"),
      fact("ru", "Живёт в Москве"),
      fact("ru2", "Мой дом"),
      fact("vi", "Tiếng Việt"),
      fact("fr", "Café au lait")
    )
    found(store, "cafe") shouldBe Set("fr")
    found(store, "CAFÉ") shouldBe Set("fr")
    // Greek and Cyrillic letters keep their accents: ά, ё and й are letters of their own here
    found(store, "ελλαδα") shouldBe empty
    found(store, "αθηνα") shouldBe empty
    found(store, "живет") shouldBe empty
    found(store, "живёт") shouldBe Set("ru")
    found(store, "мои") shouldBe empty
    found(store, "мой") shouldBe Set("ru2")
    // ế and ệ carry two accents, so they keep both
    found(store, "viet") shouldBe empty
    found(store, "tieng") shouldBe empty
    found(store, "việt") shouldBe Set("vi")
  }

  it should "fold the Greek final sigma like any other sigma" in {
    val store = storeOf(fact("sigma", "Ο κόσμος"))
    found(store, "κόσμοσ") shouldBe Set("sigma")
    found(store, "ΚΌΣΜΟΣ") shouldBe Set("sigma")
  }

  it should "split words at combining marks other than Latin accents, and match a query word's parts in order" in {
    // FTS5 counts letters, digits and private-use characters as word characters, not vowel signs and viramas:
    // मुझे is the words म and झ, and चाय the words च and य
    val store = storeOf(fact("hi", "मुझे चाय पसंद है"))
    found(store, "मुझ") shouldBe Set("hi")
    found(store, "चा") shouldBe Set("hi")
    // च and म both occur, but not in that order next to each other
    found(store, "चम") shouldBe empty
  }

  it should "match a query word with punctuation inside only where its parts are adjacent" in {
    val store = storeOf(fact("q", "\"Berlin\"-based team"), fact("in", "Based in Berlin"))
    found(store, "berlin-based") shouldBe Set("q")
    found(store, "based-berlin") shouldBe empty
    found(store, "berlin based") shouldBe Set("q", "in")
  }

  it should "count private-use characters as word characters" in {
    found(storeOf(fact("pua", "Status \uE001 green")), "\uE001") shouldBe Set("pua")
  }

  it should "match digits as words" in {
    val store = storeOf(fact("v", "Upgraded to Scala 3.7 in 2026"))
    found(store, "2026") shouldBe Set("v")
    found(store, "3") shouldBe Set("v")
    found(store, "202") shouldBe empty
  }

  it should "fold case the same whatever the default locale" in {
    val saved = Locale.getDefault
    Using.resource(new AutoCloseable { override def close(): Unit = Locale.setDefault(saved) }) { _ =>
      Locale.setDefault(Locale.forLanguageTag("tr"))
      // In a Turkish locale "TITLE".toLowerCase is "tıtle" (dotless i), which would never match "title"
      found(storeOf(fact("t", "Book TITLE list")), "title") shouldBe Set("t")
    }
  }

  // ===== Scoring =====

  it should "score by the share of distinct query words found, best first" in {
    val store  = storeOf(fact("both", "Scala runs on the JVM"), fact("one", "Scala is functional"))
    val scored = right(store.search("Scala scala JVM?", topK = 10))
    scored.map(_.memory.id.value) shouldBe Seq("both", "one")
    scored.map(_.score) shouldBe Seq(1.0, 0.5)
  }

  // ===== Parity with the SQLite stores' FTS5 index =====

  /** The words FTS5 `unicode61` (SQLiteMemoryStore's tokenizer) splits each text into, in order. */
  private def fts5Words(texts: IndexedSeq[String]): IndexedSeq[Vector[String]] =
    Using
      .Manager { use =>
        val connection = use(DriverManager.getConnection("jdbc:sqlite::memory:"))
        val statement  = use(connection.createStatement())
        statement.execute("CREATE VIRTUAL TABLE t USING fts5(id UNINDEXED, content)")
        statement.execute("CREATE VIRTUAL TABLE v USING fts5vocab(t, instance)")
        connection.setAutoCommit(false)
        val insert = use(connection.prepareStatement("INSERT INTO t(rowid, id, content) VALUES (?, '', ?)"))
        texts.zipWithIndex.foreach { case (text, i) =>
          insert.setInt(1, i)
          insert.setString(2, text)
          insert.addBatch()
          if (i % 10000 == 0) insert.executeBatch()
        }
        insert.executeBatch()
        connection.commit()
        val words = Array.fill(texts.size)(Vector.newBuilder[String])
        val rows  = use(statement.executeQuery("SELECT doc, term FROM v ORDER BY doc, offset"))
        while (rows.next()) words(rows.getInt(1)) += rows.getString(2)
        words.toIndexedSeq.map(_.result())
      }
      .fold(e => fail(e), identity)

  "KeywordTokens" should "split and fold every code point the way FTS5 unicode61 does" in {
    // Each code point between two ASCII letters shows whether FTS5 counts it as part of a word, drops it, or
    // separates words at it, and what it folds to
    val codePoints = (1 to Character.MAX_CODE_POINT).filterNot(cp => cp >= 0xd800 && cp <= 0xdfff)
    val texts      = codePoints.map(cp => "a" + new String(Character.toChars(cp)) + "b")
    val expected   = fts5Words(texts)
    val mismatches = texts.indices.filter(i => KeywordTokens.words(texts(i)) != expected(i))
    withClue(mismatches.take(10).map(i => f"U+${codePoints(i)}%04X: FTS5 ${expected(i)}").mkString("\n")) {
      mismatches shouldBe empty
    }
  }

  it should "split text the way FTS5 does where a code point's neighbours matter" in {
    val texts = Vector(
      "́abc x ́y",                    // a Latin accent outside a word separates
      "école à̀b",                  // inside a word it is dropped, however many
      "Tiếng Việt",               // decomposed, so each accent is dropped
      "Tiếng Việt Αθήνα ΚΌΣΜΟΣ ὅλος", // precomposed, kept unless Latin with one accent
      "İstanbul ǅ ß ẞ ﬁ Ⅻ ①",
      "love😀you 𐐀 x",
      "मुझे चाय पसंद है 中文 \uFEFF日本語"
    )
    texts.map(KeywordTokens.words) shouldBe fts5Words(texts)
  }

  for (query <- parityQueries)
    s"InMemoryStore and SQLiteMemoryStore, searching for '$query'," should "find the same memories" in {
      val sqlite = right(SQLiteMemoryStore.inMemory())
      Using.resource(new AutoCloseable { override def close(): Unit = sqlite.close() }) { _ =>
        val loaded = right(sqlite.storeAll(parityMemories))
        found(storeOf(parityMemories*), query) shouldBe found(loaded, query)
      }
    }
}
