package org.llm4s.agent.memory

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PostgresMemoryStoreUnitSpec extends AnyFlatSpec with Matchers {

  behavior.of("PostgresMemoryStore helper methods")

  // JSON Tests
  it should "safely round-trip metadata map including special characters" in {
    val original = Map("key" -> """value with "quotes" and \backslash""", "simple" -> "test")

    val json     = PostgresMemoryStore.metadataToJson(original)
    val restored = PostgresMemoryStore.jsonToMetadata(json)

    restored shouldBe original
  }

  it should "handle empty metadata map" in {
    PostgresMemoryStore.metadataToJson(Map.empty) shouldBe "{}"
  }

  it should "parse JSON string back to metadata map" in {
    val json   = """{"role":"assistant","id":"123"}"""
    val result = PostgresMemoryStore.jsonToMetadata(json)

    result shouldBe Map("role" -> "assistant", "id" -> "123")
  }

  it should "handle empty or null JSON" in {
    PostgresMemoryStore.jsonToMetadata("{}") shouldBe Map.empty
    PostgresMemoryStore.jsonToMetadata("") shouldBe Map.empty
    PostgresMemoryStore.jsonToMetadata(null) shouldBe Map.empty
  }

  // Config Validation Tests
  it should "reject invalid table names in Config" in {
    val badNames = Seq(
      "foo; DROP TABLE--",
      "123invalid",
      "",
      "a" * 100
    )

    badNames.foreach { name =>
      an[IllegalArgumentException] should be thrownBy {
        PostgresMemoryStore.Config(tableName = name)
      }
    }
  }

  it should "accept valid table names" in {
    noException should be thrownBy PostgresMemoryStore.Config(tableName = "valid_table_1")
    noException should be thrownBy PostgresMemoryStore.Config(tableName = "agent_memories")
  }

  // SQL Filter Tests
  it should "generate SQL for ByType filter" in {
    val filter        = MemoryFilter.ByType(MemoryType.Task)
    val (sql, params) = PostgresMemoryStore.filterToSql(filter)

    sql shouldBe "memory_type = ?"
    params shouldBe Seq("task")
  }

  it should "generate SQL for ByTypes filter with deterministic order" in {
    val filter        = MemoryFilter.ByTypes(Set(MemoryType.Task, MemoryType.Conversation))
    val (sql, params) = PostgresMemoryStore.filterToSql(filter)
    sql shouldBe "memory_type IN (?,?)"
    params shouldBe Seq("conversation", "task")
  }

  it should "generate safe interpolated SQL for ByMetadata filter" in {
    val filter        = MemoryFilter.ByMetadata("session_id", "123")
    val (sql, params) = PostgresMemoryStore.filterToSql(filter)
    sql shouldBe "metadata->>'session_id' = ?"
    params shouldBe Seq("123")
  }

  it should "reject invalid keys in ByMetadata filter" in {
    val filter = MemoryFilter.ByMetadata("invalid-key; --", "value")
    an[IllegalArgumentException] should be thrownBy {
      PostgresMemoryStore.filterToSql(filter)
    }
  }

  it should "generate SQL for MinImportance filter" in {
    val filter        = MemoryFilter.MinImportance(0.8)
    val (sql, params) = PostgresMemoryStore.filterToSql(filter)

    sql shouldBe "importance >= ?"
    params shouldBe Seq(0.8)
  }

  // Embedding Tests
  it should "convert embedding array to vector string" in {
    val vec = Array(0.1f, 0.2f, 0.3f)
    PostgresMemoryStore.embeddingToString(vec) shouldBe "[0.1,0.2,0.3]"
  }

  it should "parse vector string back to array" in {
    val vec = PostgresMemoryStore.stringToEmbedding("[0.5, 0.6, 0.7]")
    vec shouldBe Array(0.5f, 0.6f, 0.7f)
  }

  it should "handle empty embedding string" in {
    PostgresMemoryStore.stringToEmbedding("[]") shouldBe Array.empty[Float]
    PostgresMemoryStore.stringToEmbedding("") shouldBe Array.empty[Float]
    PostgresMemoryStore.stringToEmbedding(null) shouldBe Array.empty[Float]
  }
}
