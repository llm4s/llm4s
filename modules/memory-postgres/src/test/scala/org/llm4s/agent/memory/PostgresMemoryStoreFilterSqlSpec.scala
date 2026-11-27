package org.llm4s.agent.memory

import org.llm4s.agent.memory.PostgresMemoryStore.SqlParam._
import org.llm4s.error.ProcessingError
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.Timestamp
import java.time.Instant

/**
 * How `PostgresMemoryStore` translates a `MemoryFilter` (#3031): SQL narrows, `matches` decides. What SQL these
 * conditions select on a real database is `modules/it`'s `PostgresMemoryStoreFilterContractSpec`; this pins the
 * translation itself: every leaf is never NULL, a metadata key is bound rather than interpolated, and what SQL cannot
 * say exactly is left to `matches` rather than refused.
 */
class PostgresMemoryStoreFilterSqlSpec extends AnyFlatSpec with Matchers {

  /** The text of the metadata value at a bound key, as the store compares it. */
  private val KeyText =
    "COALESCE(metadata ->> CAST(? AS text), CASE WHEN jsonb_typeof(metadata -> CAST(? AS text)) = 'null' THEN 'null' END)"

  private def narrowed(filter: MemoryFilter): (String, Seq[Any], Boolean) = {
    val plan = PostgresMemoryStore.narrowing(filter)
    (plan.sql.fold("")(_.clause), plan.params, plan.exact)
  }

  "PostgresMemoryStore.narrowing" should "translate a built-in type to one bound name" in {
    narrowed(MemoryFilter.ByType(MemoryType.Task)) shouldBe (("memory_type = ?", Seq(PString("task")), true))
  }

  it should "match a custom type by its prefixed name and by the bare name an older store wrote" in {
    narrowed(MemoryFilter.ByType(MemoryType.Custom("Report"))) shouldBe
      (("memory_type IN (?,?)", Seq(PString("custom:Report"), PString("Report")), true))
  }

  it should "not match the built-in row an older store wrote for a custom type named like a built-in" in {
    // A bare "task" row reads as Task, so Custom("task") is only ever its prefixed spelling
    narrowed(MemoryFilter.ByType(MemoryType.Custom("task"))) shouldBe
      (("memory_type = ?", Seq(PString("custom:task")), true))
  }

  it should "list every spelling of ByTypes in a deterministic order, and nothing for no types" in {
    narrowed(MemoryFilter.ByTypes(Set(MemoryType.Task, MemoryType.Conversation, MemoryType.Custom("x")))) shouldBe
      ((
        "memory_type IN (?,?,?,?)",
        Seq(PString("conversation"), PString("custom:x"), PString("task"), PString("x")),
        true
      ))
    narrowed(MemoryFilter.ByTypes(Set.empty))._1 shouldBe "1 = 0"
  }

  it should "bind a metadata key of any spelling instead of refusing it" in {
    Seq("session_id", "user-id", "a.b", "x[0]", "say \"hi\"", "it's; DROP TABLE x; --").foreach { key =>
      val (sql, params, exact) = narrowed(MemoryFilter.ByMetadata(key, "v"))
      (sql should not).include(key)
      params shouldBe Seq(PString(key), PString("v"), PString(key), PString(key), PString("v"))
      exact shouldBe true
    }
  }

  it should "narrow ByMetadata by a containment the GIN index serves, for every JSON value its text could be (#3164)" in {
    val Contains         = "metadata @> jsonb_build_object(CAST(? AS text), CAST(? AS %s))"
    def exact(v: String) = Seq(PString("k"), PString("k"), PString(v))
    // a JSON string only
    narrowed(MemoryFilter.ByMetadata("k", "blue")) shouldBe
      ((
        s"(${Contains.format("text")}) AND COALESCE($KeyText = ?, FALSE)",
        Seq(PString("k"), PString("blue")) ++ exact("blue"),
        true
      ))
    // a string, or the number, boolean or null it is the text of
    Seq("3", "-1.50", "0", "true", "false", "null").foreach { v =>
      narrowed(MemoryFilter.ByMetadata("k", v)) shouldBe
        ((
          s"(${Contains.format("text")} OR ${Contains.format("jsonb")}) AND COALESCE($KeyText = ?, FALSE)",
          Seq(PString("k"), PString(v), PString("k"), PString(v)) ++ exact(v),
          true
        ))
    }
    // the text of a nested value: no containment, the text test alone
    Seq("""{"a":1}""", "[1, 2]").foreach { v =>
      narrowed(MemoryFilter.ByMetadata("k", v)) shouldBe ((s"COALESCE($KeyText = ?, FALSE)", exact(v), true))
    }
  }

  it should "cast to jsonb only the text PostgreSQL writes for a number, a boolean or null" in {
    Seq("0", "-0", "12", "-3.25", "1.50", "true", "false", "null", "9" * 131072).foreach(v =>
      withClue(v.take(20))(PostgresMemoryStore.isJsonScalarText(v) shouldBe true)
    )
    Seq(
      "",
      "01",
      "1.",
      ".5",
      "+1",
      "1e3",
      "1E-2",
      "NaN",
      "Infinity",
      "True",
      " 1",
      "1 ",
      "0x1",
      "9" * 131073,
      "1." + "0" * 16384
    ).foreach(v => withClue(v.take(20))(PostgresMemoryStore.isJsonScalarText(v) shouldBe false))
  }

  it should "translate HasMetadata, MetadataContains and a case-sensitive ContentContains" in {
    narrowed(MemoryFilter.HasMetadata("a.b")) shouldBe
      (("(metadata -> CAST(? AS text)) IS NOT NULL", Seq(PString("a.b")), true))
    narrowed(MemoryFilter.MetadataContains("tag", "50%")) shouldBe
      ((
        s"COALESCE(strpos($KeyText, ?) > 0, FALSE)",
        Seq(PString("tag"), PString("tag"), PString("50%")),
        true
      ))
    narrowed(MemoryFilter.ContentContains("100%", caseSensitive = true)) shouldBe
      (("strpos(content, ?) > 0", Seq(PString("100%")), true))
  }

  it should "leave a case-insensitive ContentContains and a Custom predicate to matches" in {
    narrowed(MemoryFilter.ContentContains("upper")) shouldBe (("", Seq.empty, false))
    narrowed(MemoryFilter.Custom(_ => true)) shouldBe (("", Seq.empty, false))
    // And keeps the conjunct SQL can say; Not of an inexact condition narrows nothing
    narrowed(MemoryFilter.And(MemoryFilter.ByType(MemoryType.Task), MemoryFilter.Custom(_ => true))) shouldBe
      (("memory_type = ?", Seq(PString("task")), false))
    narrowed(MemoryFilter.Not(MemoryFilter.ContentContains("x"))) shouldBe (("", Seq.empty, false))
  }

  it should "guard every nullable test, so Not keeps rows without the key or the importance" in {
    val (importance, _, _) = narrowed(MemoryFilter.Not(MemoryFilter.MinImportance(0.5)))
    importance shouldBe "NOT ((importance >= ? AND importance IS NOT NULL AND importance <> 'NaN'::double precision))"

    narrowed(MemoryFilter.Not(MemoryFilter.ByEntity(EntityId("e1")))) shouldBe
      ((
        "NOT ((metadata @> jsonb_build_object(CAST('entity_id' AS text), CAST(? AS text))) AND " +
          "COALESCE(COALESCE(metadata ->> 'entity_id', CASE WHEN jsonb_typeof(metadata -> 'entity_id') = 'null' " +
          "THEN 'null' END) = ?, FALSE))",
        Seq(PString("e1"), PString("e1")),
        true
      ))
    narrowed(MemoryFilter.Not(MemoryFilter.ByConversation("c1"))) shouldBe
      ((
        "NOT (((metadata->>'conversation_id' = ? AND metadata->>'conversation_id' IS NOT NULL) OR " +
          "COALESCE(? = 'null' AND jsonb_typeof(metadata -> 'conversation_id') = 'null', FALSE)))",
        Seq(PString("c1"), PString("c1")),
        true
      ))
  }

  it should "round a time range to the column's microseconds the way matches reads the stored time" in {
    val after  = Instant.parse("2026-01-01T00:00:00.000000400Z")
    val before = Instant.parse("2026-01-02T00:00:00.000000900Z")
    narrowed(MemoryFilter.ByTimeRange(Some(after), Some(before))) shouldBe
      ((
        "created_at >= ? AND created_at <= ?",
        Seq(
          PTimestamp(Timestamp.from(Instant.parse("2026-01-01T00:00:00.000001Z"))),
          PTimestamp(Timestamp.from(Instant.parse("2026-01-02T00:00:00Z")))
        ),
        true
      ))
    narrowed(MemoryFilter.ByTimeRange(None, None))._3 shouldBe true
  }

  it should "translate All, None and composed filters" in {
    narrowed(MemoryFilter.All) shouldBe (("", Seq.empty, true))
    narrowed(MemoryFilter.None)._1 shouldBe "1 = 0"
    narrowed(
      MemoryFilter.Or(MemoryFilter.ByType(MemoryType.Task), MemoryFilter.Not(MemoryFilter.ByType(MemoryType.Entity)))
    ) shouldBe (("(memory_type = ?) OR (NOT (memory_type = ?))", Seq(PString("task"), PString("entity")), true))
  }

  "MemoryTypeCodec" should "write a custom type with a prefix and read it back with its case" in {
    MemoryTypeCodec.encode(MemoryType.Custom("Report")) shouldBe "custom:Report"
    MemoryTypeCodec.decode("custom:Report") shouldBe MemoryType.Custom("Report")
    MemoryTypeCodec.decode(MemoryTypeCodec.encode(MemoryType.Custom("task"))) shouldBe MemoryType.Custom("task")
    MemoryType.builtIn.foreach(t => MemoryTypeCodec.decode(MemoryTypeCodec.encode(t)) shouldBe t)
  }

  it should "read the spelling stores wrote before the prefix" in {
    // Before #3031 a custom type was written as its bare name: "Report" for Custom("Report"), "task" for Task
    MemoryTypeCodec.decode("Report") shouldBe MemoryType.Custom("Report")
    MemoryTypeCodec.decode("task") shouldBe MemoryType.Task
    MemoryTypeCodec.decode("user_fact") shouldBe MemoryType.UserFact
  }

  "PostgresMemoryStore.firstN" should "stop at the first error" in {
    val error = ProcessingError("test", "boom")
    PostgresMemoryStore.firstN(Iterator(Right(1), Left(error), Right(3)), 10) shouldBe Left(error)
    PostgresMemoryStore.firstN(Iterator(Right(1), Right(2), Left(error)), 2) shouldBe Right(Vector(1, 2))
  }

  "PostgresMemoryStore.topScored" should "keep the best topK keyword matches, ties to the newer" in {
    def memory(id: String, content: String) = Memory(MemoryId(id), content, MemoryType.Task)
    val newestFirst = Iterator(
      memory("new-half", "scala only"),
      memory("none", "nothing here"),
      memory("full", "scala and postgres"),
      memory("old-half", "postgres only")
    ).map(Right(_))
    val phrases = KeywordTokens.searchPhrases("Scala postgres")
    val result  = PostgresMemoryStore.topScored(newestFirst, phrases, 2).fold(e => fail(e.message), identity)
    result.map(s => s.memory.id.value -> s.score) shouldBe Seq("full" -> 1.0, "new-half" -> 0.5)
  }
}
