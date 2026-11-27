package org.llm4s.vectorstore

import org.llm4s.vectorstore.MetadataFilter._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * `SQLiteKeywordIndex` selects the same records as the reference backend, `SQLiteVectorStore`, for every filter of
 * a matrix (#2996): `Contains` is a literal, case-sensitive substring test (no `LIKE` wildcards), and `Not` keeps a
 * record that lacks the key.
 */
class SQLiteKeywordIndexFilterParitySpec extends AnyWordSpec with Matchers {

  private val records: Seq[(String, Map[String, String])] = Seq(
    "r1" -> Map("lang" -> "en", "path" -> "a_b", "type" -> "doc"),
    "r2" -> Map("lang" -> "fr", "path" -> "axb"),
    "r3" -> Map("path" -> "A_B", "type" -> "code"),
    "r4" -> Map("lang" -> "en", "tag" -> "50%"),
    "r5" -> Map.empty
  )

  private val filters: Seq[MetadataFilter] = Seq(
    All,
    Equals("lang", "en"),
    Not(Equals("lang", "en")),
    Contains("path", "a_b"),
    Contains("path", "_"),
    Contains("path", "A_B"),
    Contains("tag", "%"),
    Not(Contains("path", "x")),
    HasKey("type"),
    Not(HasKey("lang")),
    In("lang", Set.empty),
    Not(In("lang", Set.empty)),
    In("lang", Set("en", "fr")),
    Not(In("lang", Set("fr"))),
    Not(All),
    Or(Equals("lang", "fr"), Not(HasKey("lang"))),
    And(HasKey("path"), Not(Contains("path", "x")))
  )

  "SQLiteKeywordIndex" should {
    "select the same records as SQLiteVectorStore for every filter" in {
      val reference = SQLiteVectorStore.inMemory().toOption.get
      val index     = SQLiteKeywordIndex.inMemory().toOption.get
      try {
        reference.upsertBatch(records.map { case (id, m) => VectorRecord(id, Array(1.0f), metadata = m) }) shouldBe
          Right(())
        index.indexBatch(records.map { case (id, m) => KeywordDocument(id, "common words", m) }) shouldBe Right(())

        filters.foreach { filter =>
          withClue(filter) {
            val expected = reference.list(filter = Some(filter)).map(_.map(_.id).toSet)
            index.search("common", 10, Some(filter)).map(_.map(_.id).toSet) shouldBe expected
          }
        }
      } finally {
        reference.close()
        index.close()
      }
    }

    "pin the reference answers" in {
      val reference = SQLiteVectorStore.inMemory().toOption.get
      try {
        reference.upsertBatch(records.map { case (id, m) => VectorRecord(id, Array(1.0f), metadata = m) })
        def ids(f: MetadataFilter) = reference.list(filter = Some(f)).map(_.map(_.id).toSet)
        ids(Not(Equals("lang", "en"))) shouldBe Right(Set("r2", "r3", "r5"))
        ids(Contains("path", "a_b")) shouldBe Right(Set("r1"))
        ids(Contains("tag", "%")) shouldBe Right(Set("r4"))
        ids(In("lang", Set.empty)) shouldBe Right(Set.empty)
        ids(Not(All)) shouldBe Right(Set.empty)
      } finally reference.close()
    }
  }
}
