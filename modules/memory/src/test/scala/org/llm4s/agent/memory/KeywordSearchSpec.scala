package org.llm4s.agent.memory

import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.Locale
import scala.util.Using

/**
 * Keyword search matches whole words (#1594).
 *
 * `InMemoryStore` used to split the query on whitespace only and test each piece with `String.contains`, so short
 * words matched inside longer ones (`i` in "Berlin", `or` in "works") and punctuation stayed on the query's words
 * (`java?` never matched "Java"). It now splits both sides into words the way the SQLite stores' FTS5 index does,
 * and the last section checks the two agree.
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
    fact("v", "Upgraded to Scala 3.7 in 2026")
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
    "202"
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
    found(store, "école") shouldBe Set("fr")
    found(store, "ελλάδα") shouldBe Set("el")
    found(store, "चाय?") shouldBe Set("hi")
    found(store, "चा") shouldBe empty
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

  for (query <- parityQueries)
    it should s"find the same memories as SQLiteMemoryStore for '$query'" in {
      val sqlite = right(SQLiteMemoryStore.inMemory())
      Using.resource(new AutoCloseable { override def close(): Unit = sqlite.close() }) { _ =>
        val loaded = right(sqlite.storeAll(parityMemories))
        found(storeOf(parityMemories*), query) shouldBe found(loaded, query)
      }
    }
}
