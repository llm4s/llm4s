package org.llm4s.agent.memory

import com.zaxxer.hikari.HikariDataSource
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.agent.memory.PostgresMemoryStore.SqlParam._

import java.sql.{ Connection, PreparedStatement, ResultSet, SQLException, Timestamp }
import java.time.Instant

class MockableHikariDataSource extends HikariDataSource
class PostgresMemoryStoreUnitSpec extends AnyFlatSpec with Matchers with MockFactory {

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

  it should "stringify non-string JSON values instead of throwing" in {
    val json = """{"count": 3, "flag": true}"""
    val res  = PostgresMemoryStore.jsonToMetadata(json)

    res("count") shouldBe "3"
    res("flag") shouldBe "true"
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
    val result = PostgresMemoryStore.filterToSql(
      MemoryFilter.ByType(MemoryType.Task)
    )

    result.isRight shouldBe true
    val (sql, params) = result.toOption.get

    sql shouldBe "memory_type = ?"
    params shouldBe Seq(PString("task"))
  }

  it should "generate SQL for ByTypes filter with deterministic order" in {
    val result = PostgresMemoryStore.filterToSql(
      MemoryFilter.ByTypes(Set(MemoryType.Task, MemoryType.Conversation))
    )
    result.isRight shouldBe true
    val (sql, params) = result.toOption.get

    sql shouldBe "memory_type IN (?,?)"
    params shouldBe Seq(PString("conversation"), PString("task"))
  }

  it should "generate safe interpolated SQL for ByMetadata filter" in {
    val result = PostgresMemoryStore.filterToSql(
      MemoryFilter.ByMetadata("session_id", "123")
    )
    result.isRight shouldBe true
    val (sql, params) = result.toOption.get
    sql shouldBe "metadata->>'session_id' = ?"
    params shouldBe Seq(PString("123"))
  }

  it should "reject invalid keys in ByMetadata filter" in {
    val result = PostgresMemoryStore.filterToSql(
      MemoryFilter.ByMetadata("invalid-key; --", "value")
    )
    result.isLeft shouldBe true
  }

  it should "generate SQL for MinImportance filter" in {
    val result = PostgresMemoryStore.filterToSql(
      MemoryFilter.MinImportance(0.8)
    )
    result.isRight shouldBe true
    val (sql, params) = result.toOption.get

    sql shouldBe "importance >= ?"
    params shouldBe Seq(PDouble(0.8))
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

  behavior.of("PostgresMemoryStore class execution")

  val mockDataSource = mock[MockableHikariDataSource]
  val mockConn       = mock[Connection]
  val mockStmt       = mock[PreparedStatement]

  // Helper to simulate a DB connection
  def setupFailingExecution(): Unit = {
    (mockDataSource.getConnection _).expects().returning(mockConn)
    (mockConn.prepareStatement(_: String)).expects(*).returning(mockStmt)
    // Allow any parameter setting
    (mockStmt.setString(_: Int, _: String)).expects(*, *).anyNumberOfTimes()
    (mockStmt.setTimestamp(_: Int, _: Timestamp)).expects(*, *).anyNumberOfTimes()
    (mockStmt.setDouble(_: Int, _: Double)).expects(*, *).anyNumberOfTimes()
    (mockStmt.setInt(_: Int, _: Int)).expects(*, *).anyNumberOfTimes()
    (mockStmt.setBoolean(_: Int, _: Boolean)).expects(*, *).anyNumberOfTimes()
    (mockStmt.setNull(_: Int, _: Int)).expects(*, *).anyNumberOfTimes()
    (mockStmt.setNull(_: Int, _: Int, _: String)).expects(*, *, *).anyNumberOfTimes()

    (mockStmt.close _).expects()
    (mockConn.close _).expects()
  }

  it should "execute store() logic and handle DB failure" in {
    setupFailingExecution()
    (mockStmt.executeUpdate _).expects().throws(new SQLException("Mock DB Error"))

    val store = new PostgresMemoryStore(mockDataSource, "test_table")
    val mem   = Memory(MemoryId("1"), "test", MemoryType.Task, Map.empty, Instant.now(), None, None)

    // It runs all the lines inside store()
    val result = store.store(mem)
    result.isLeft shouldBe true
    result.left.map(_.message should include("Mock DB Error"))
  }

  it should "execute get() logic and handle DB failure" in {
    setupFailingExecution()
    (mockStmt.executeQuery _).expects().throws(new SQLException("Mock DB Error"))

    val store  = new PostgresMemoryStore(mockDataSource, "test_table")
    val result = store.get(MemoryId("1"))

    result.isLeft shouldBe true
  }

  it should "execute recall() logic and handle DB failure" in {
    setupFailingExecution()
    (mockStmt.executeQuery _).expects().throws(new SQLException("Mock DB Error"))

    val store  = new PostgresMemoryStore(mockDataSource, "test_table")
    val result = store.recall(MemoryFilter.ByType(MemoryType.Task), 10)

    result.isLeft shouldBe true
  }

  it should "execute delete() logic and handle DB failure" in {
    setupFailingExecution()
    (mockStmt.executeUpdate _).expects().throws(new SQLException("Mock DB Error"))

    val store  = new PostgresMemoryStore(mockDataSource, "test_table")
    val result = store.delete(MemoryId("1"))

    result.isLeft shouldBe true
  }

  it should "execute count() logic and handle DB failure" in {
    setupFailingExecution()
    (mockStmt.executeQuery _).expects().throws(new SQLException("Mock DB Error"))

    val store  = new PostgresMemoryStore(mockDataSource, "test_table")
    val result = store.count(MemoryFilter.All)

    result.isLeft shouldBe true
  }

  it should "execute clear() logic and handle DB failure" in {
    // clear uses createStatement, not prepareStatement
    (mockDataSource.getConnection _).expects().returning(mockConn)
    (mockConn.createStatement _).expects().returning(mockStmt)
    (mockStmt.execute(_: String)).expects(*).throws(new SQLException("Mock DB Error"))
    (mockStmt.close _).expects()
    (mockConn.close _).expects()

    val store  = new PostgresMemoryStore(mockDataSource, "test_table")
    val result = store.clear()

    result.isLeft shouldBe true
  }

  it should "handle schema initialization failure in factory" in {
    (mockDataSource.getConnection _).expects().returning(mockConn)
    (mockConn.createStatement _).expects().returning(mockStmt)
    (mockStmt.execute(_: String)).expects(*).throws(new SQLException("Init Error"))
    (mockStmt.close _).expects()
    (mockConn.close _).expects()

    val store = new PostgresMemoryStore(mockDataSource, "test_table")

    an[SQLException] should be thrownBy store.initializeSchema()
  }
}
