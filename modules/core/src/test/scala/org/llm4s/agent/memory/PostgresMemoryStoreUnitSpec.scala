package org.llm4s.agent.memory

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PostgresMemoryStoreUnitSpec extends AnyFlatSpec with Matchers {

  behavior.of("PostgresMemoryStore utility methods")

  // JSON Tests
  it should "convert metadata map to JSON string" in {
    val metadata = Map("role" -> "user", "context" -> "test")
    val json     = PostgresMemoryStore.metadataToJson(metadata)

    json should include(""""role":"user"""")
    json should include(""""context":"test"""")
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

  // SQL Filter Tests
  it should "generate SQL for ByType filter" in {
    val filter        = MemoryFilter.ByType(MemoryType.Task)
    val (sql, params) = PostgresMemoryStore.filterToSql(filter)

    sql shouldBe "memory_type = ?"
    params shouldBe Seq("task")
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
    vec.sameElements(Array(0.5f, 0.6f, 0.7f)) shouldBe true
  }

  it should "handle empty embedding string" in {
    PostgresMemoryStore.stringToEmbedding("[]").isEmpty shouldBe true
    PostgresMemoryStore.stringToEmbedding("").isEmpty shouldBe true
    PostgresMemoryStore.stringToEmbedding(null).isEmpty shouldBe true
  }
}
