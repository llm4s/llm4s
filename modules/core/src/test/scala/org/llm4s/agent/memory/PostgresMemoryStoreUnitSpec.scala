package org.llm4s.agent.memory

import com.zaxxer.hikari.HikariDataSource
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.agent.memory.PostgresMemoryStore.SqlParam._
import org.llm4s.error.{ NotFoundError, OptimisticLockFailure }

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

  it should "handle malformed embedding string gracefully" in {
    PostgresMemoryStore.stringToEmbedding("[not,valid,floats]") shouldBe Array.empty[Float]
    PostgresMemoryStore.stringToEmbedding("[1.0,abc,3.0]") shouldBe Array.empty[Float]
    PostgresMemoryStore.stringToEmbedding("garbage") shouldBe Array.empty[Float]
  }

  it should "generate SQL for None filter" in {
    val result = PostgresMemoryStore.filterToSql(MemoryFilter.None)
    result.isRight shouldBe true
    val (sql, params) = result.toOption.get
    sql shouldBe "FALSE"
    params shouldBe Seq.empty
  }

  behavior.of("PostgresMemoryStore class execution")

  val mockDataSource = mock[MockableHikariDataSource]
  val mockConn       = mock[Connection]
  val mockStmt       = mock[PreparedStatement]
  val mockRs         = mock[ResultSet]

  // Helper to simulate a DB connection
  def setupMockExecution(): Unit = {
    (() => mockDataSource.getConnection()).expects().returning(mockConn)
    (mockConn.prepareStatement(_: String)).expects(*).returning(mockStmt)
    // Allow any parameter setting
    (mockStmt.setString(_: Int, _: String)).expects(*, *).anyNumberOfTimes()
    (mockStmt.setTimestamp(_: Int, _: Timestamp)).expects(*, *).anyNumberOfTimes()
    (mockStmt.setDouble(_: Int, _: Double)).expects(*, *).anyNumberOfTimes()
    (mockStmt.setInt(_: Int, _: Int)).expects(*, *).anyNumberOfTimes()
    (mockStmt.setBoolean(_: Int, _: Boolean)).expects(*, *).anyNumberOfTimes()
    (mockStmt.setNull(_: Int, _: Int)).expects(*, *).anyNumberOfTimes()
    (mockStmt.setNull(_: Int, _: Int, _: String)).expects(*, *, *).anyNumberOfTimes()

    (() => mockStmt.close()).expects()
    (() => mockConn.close()).expects()
  }

  it should "store a memory successfully" in {
    setupMockExecution()
    (() => mockStmt.executeUpdate()).expects().returning(1)

    val store = new PostgresMemoryStore(mockDataSource, "test_table")
    val mem   = Memory(MemoryId("1"), "test", MemoryType.Task, Map.empty, Instant.now(), None, None)

    val result = store.store(mem)
    result.isRight shouldBe true
  }

  it should "retrieve a memory successfully" in {
    setupMockExecution()
    (() => mockStmt.executeQuery()).expects().returning(mockRs)

    (() => mockRs.next()).expects().returning(true)
    (mockRs.getString(_: String)).expects("id").returning("1")
    (mockRs.getString(_: String)).expects("content").returning("test content")
    (mockRs.getString(_: String)).expects("memory_type").returning("task")
    (mockRs.getString(_: String)).expects("metadata").returning("""{"key":"val"}""")
    (mockRs.getTimestamp(_: String)).expects("created_at").returning(Timestamp.from(Instant.now()))
    (mockRs.getDouble(_: String)).expects("importance").returning(0.5)
    (() => mockRs.wasNull()).expects().returning(false)
    (mockRs.getString(_: String)).expects("embedding").returning("[0.1,0.2]")

    (() => mockRs.close()).expects()

    val store  = new PostgresMemoryStore(mockDataSource, "test_table")
    val result = store.get(MemoryId("1"))

    result.isRight shouldBe true
    result.map { opt =>
      opt shouldBe defined
      opt.get.content shouldBe "test content"
      opt.get.metadata shouldBe Map("key" -> "val")
    }
  }

  it should "execute store() logic and handle DB failure" in {
    setupMockExecution()
    (() => mockStmt.executeUpdate()).expects().throws(new SQLException("Mock DB Error"))

    val store = new PostgresMemoryStore(mockDataSource, "test_table")
    val mem   = Memory(MemoryId("1"), "test", MemoryType.Task, Map.empty, Instant.now(), None, None)

    // It runs all the lines inside store()
    val result = store.store(mem)
    result.isLeft shouldBe true
  }

  it should "execute get() logic and handle DB failure" in {
    setupMockExecution()
    (() => mockStmt.executeQuery()).expects().throws(new SQLException("Mock DB Error"))

    val store  = new PostgresMemoryStore(mockDataSource, "test_table")
    val result = store.get(MemoryId("1"))

    result.isLeft shouldBe true
  }

  it should "execute recall() logic and handle DB failure" in {
    setupMockExecution()
    (() => mockStmt.executeQuery()).expects().throws(new SQLException("Mock DB Error"))

    val store  = new PostgresMemoryStore(mockDataSource, "test_table")
    val result = store.recall(MemoryFilter.ByType(MemoryType.Task), 10)

    result.isLeft shouldBe true
  }

  it should "execute delete() logic and handle DB failure" in {
    setupMockExecution()
    (() => mockStmt.executeUpdate()).expects().throws(new SQLException("Mock DB Error"))

    val store  = new PostgresMemoryStore(mockDataSource, "test_table")
    val result = store.delete(MemoryId("1"))

    result.isLeft shouldBe true
  }

  it should "execute count() logic and handle DB failure" in {
    setupMockExecution()
    (() => mockStmt.executeQuery()).expects().throws(new SQLException("Mock DB Error"))

    val store  = new PostgresMemoryStore(mockDataSource, "test_table")
    val result = store.count(MemoryFilter.All)

    result.isLeft shouldBe true
  }

  it should "execute clear() logic and handle DB failure" in {
    (() => mockDataSource.getConnection()).expects().returning(mockConn)
    (() => mockConn.createStatement()).expects().returning(mockStmt)
    (mockStmt.execute(_: String)).expects(*).throws(new SQLException("Mock DB Error"))
    (() => mockStmt.close()).expects()
    (() => mockConn.close()).expects()

    val store  = new PostgresMemoryStore(mockDataSource, "test_table")
    val result = store.clear()

    result.isLeft shouldBe true
  }

  it should "handle schema initialization failure in factory" in {
    (() => mockDataSource.getConnection()).expects().returning(mockConn)
    (() => mockConn.createStatement()).expects().returning(mockStmt)
    (mockStmt.execute(_: String)).expects(*).throws(new SQLException("Init Error"))
    (() => mockStmt.close()).expects()
    (() => mockConn.close()).expects()

    val store = new PostgresMemoryStore(mockDataSource, "test_table")

    an[SQLException] should be thrownBy store.initializeSchema()
  }

  it should "return NotFoundError when updating non-existent memory" in {
    setupMockExecution()
    (() => mockStmt.executeQuery()).expects().returning(mockRs)
    (() => mockRs.next()).expects().returning(false)
    (() => mockRs.close()).expects()

    val store  = new PostgresMemoryStore(mockDataSource, "test_table")
    val result = store.update(MemoryId("non-existent"), identity)

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[NotFoundError]
    result.left.toOption.get.message should include("Memory not found")
  }

  behavior.of("PostgresMemoryStore optimistic locking (Issue #528)")

  it should "detect concurrent updates and return OptimisticLockFailure" in {
    // Setup: Mock successful read with version
    (() => mockDataSource.getConnection()).expects().returning(mockConn).twice()
    (mockConn.prepareStatement(_: String)).expects(*).returning(mockStmt).twice()

    // First call: getVersioned reads memory with version 1
    (mockStmt.setString(_: Int, _: String)).expects(1, "test-id")
    (() => mockStmt.executeQuery()).expects().returning(mockRs)
    (() => mockRs.next()).expects().returning(true)
    (mockRs.getString(_: String)).expects("id").returning("test-id")
    (mockRs.getString(_: String)).expects("content").returning("original content")
    (mockRs.getString(_: String)).expects("memory_type").returning("task")
    (mockRs.getString(_: String)).expects("metadata").returning("{}")
    (mockRs.getTimestamp(_: String)).expects("created_at").returning(Timestamp.from(Instant.now()))
    (mockRs.getDouble(_: String)).expects("importance").returning(0.5)
    (() => mockRs.wasNull()).expects().returning(false)
    (mockRs.getString(_: String)).expects("embedding").returning(null)
    (mockRs.getLong(_: String)).expects("version").returning(1L)
    (() => mockRs.wasNull()).expects().returning(false)
    (() => mockRs.close()).expects()
    (() => mockStmt.close()).expects()
    (() => mockConn.close()).expects()

    // Second call: update fails because version changed (0 rows affected)
    (mockStmt.setString(_: Int, _: String)).expects(*, *).anyNumberOfTimes()
    (mockStmt.setTimestamp(_: Int, _: Timestamp)).expects(*, *).anyNumberOfTimes()
    (mockStmt.setDouble(_: Int, _: Double)).expects(*, *).anyNumberOfTimes()
    (mockStmt.setNull(_: Int, _: Int, _: String)).expects(*, *, *).anyNumberOfTimes()
    (mockStmt.setLong(_: Int, _: Long)).expects(8, 1L)
    (() => mockStmt.executeUpdate()).expects().returning(0) // Conflict: 0 rows affected
    (() => mockStmt.close()).expects()
    (() => mockConn.close()).expects()

    val store = new PostgresMemoryStore(mockDataSource, "test_table")
    val result = store.update(
      MemoryId("test-id"),
      mem => mem.copy(content = "updated content")
    )

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[OptimisticLockFailure]
    val error = result.left.toOption.get.asInstanceOf[OptimisticLockFailure]
    error.memoryId shouldBe "test-id"
    error.attemptedVersion shouldBe 1L
    error.code shouldBe Some("OPTIMISTIC_LOCK_CONFLICT")
  }

  it should "successfully update when version matches" in {
    // Setup: Mock successful read and update
    (() => mockDataSource.getConnection()).expects().returning(mockConn).twice()
    (mockConn.prepareStatement(_: String)).expects(*).returning(mockStmt).twice()

    // First call: getVersioned
    (mockStmt.setString(_: Int, _: String)).expects(1, "test-id")
    (() => mockStmt.executeQuery()).expects().returning(mockRs)
    (() => mockRs.next()).expects().returning(true)
    (mockRs.getString(_: String)).expects("id").returning("test-id")
    (mockRs.getString(_: String)).expects("content").returning("original")
    (mockRs.getString(_: String)).expects("memory_type").returning("task")
    (mockRs.getString(_: String)).expects("metadata").returning("{}")
    (mockRs.getTimestamp(_: String)).expects("created_at").returning(Timestamp.from(Instant.now()))
    (mockRs.getDouble(_: String)).expects("importance").returning(0.5)
    (() => mockRs.wasNull()).expects().returning(false)
    (mockRs.getString(_: String)).expects("embedding").returning(null)
    (mockRs.getLong(_: String)).expects("version").returning(2L)
    (() => mockRs.wasNull()).expects().returning(false)
    (() => mockRs.close()).expects()
    (() => mockStmt.close()).expects()
    (() => mockConn.close()).expects()

    // Second call: update succeeds (1 row affected)
    (mockStmt.setString(_: Int, _: String)).expects(*, *).anyNumberOfTimes()
    (mockStmt.setTimestamp(_: Int, _: Timestamp)).expects(*, *).anyNumberOfTimes()
    (mockStmt.setDouble(_: Int, _: Double)).expects(*, *).anyNumberOfTimes()
    (mockStmt.setNull(_: Int, _: Int, _: String)).expects(*, *, *).anyNumberOfTimes()
    (mockStmt.setLong(_: Int, _: Long)).expects(8, 2L)
    (() => mockStmt.executeUpdate()).expects().returning(1)
    (() => mockStmt.close()).expects()
    (() => mockConn.close()).expects()

    val store = new PostgresMemoryStore(mockDataSource, "test_table")
    val result = store.update(
      MemoryId("test-id"),
      mem => mem.copy(content = "updated")
    )

    result.isRight shouldBe true
  }

  it should "handle missing version column gracefully (backward compatibility)" in {
    // Simulate reading from table without version column
    setupMockExecution()
    (() => mockStmt.executeQuery()).expects().returning(mockRs)
    (() => mockRs.next()).expects().returning(true)
    (mockRs.getString(_: String)).expects("id").returning("test-id")
    (mockRs.getString(_: String)).expects("content").returning("content")
    (mockRs.getString(_: String)).expects("memory_type").returning("task")
    (mockRs.getString(_: String)).expects("metadata").returning("{}")
    (mockRs.getTimestamp(_: String)).expects("created_at").returning(Timestamp.from(Instant.now()))
    (mockRs.getDouble(_: String)).expects("importance").returning(0.5)
    (() => mockRs.wasNull()).expects().returning(false)
    (mockRs.getString(_: String)).expects("embedding").returning(null)
    // Simulate SQLException when trying to get non-existent version column
    (mockRs.getLong(_: String)).expects("version").throws(new SQLException("Column 'version' not found"))
    (() => mockRs.close()).expects()

    val store  = new PostgresMemoryStore(mockDataSource, "test_table")
    val result = store.getVersioned(MemoryId("test-id"))

    result.isRight shouldBe true
    result.map { opt =>
      opt shouldBe defined
      opt.get.version shouldBe 0L // Should default to 0
    }
  }

  it should "handle null version column gracefully" in {
    setupMockExecution()
    (() => mockStmt.executeQuery()).expects().returning(mockRs)
    (() => mockRs.next()).expects().returning(true)
    (mockRs.getString(_: String)).expects("id").returning("test-id")
    (mockRs.getString(_: String)).expects("content").returning("content")
    (mockRs.getString(_: String)).expects("memory_type").returning("task")
    (mockRs.getString(_: String)).expects("metadata").returning("{}")
    (mockRs.getTimestamp(_: String)).expects("created_at").returning(Timestamp.from(Instant.now()))
    (mockRs.getDouble(_: String)).expects("importance").returning(0.5)
    (() => mockRs.wasNull()).expects().returning(false)
    (mockRs.getString(_: String)).expects("embedding").returning(null)
    (mockRs.getLong(_: String)).expects("version").returning(0L)
    (() => mockRs.wasNull()).expects().returning(true) // NULL version
    (() => mockRs.close()).expects()

    val store  = new PostgresMemoryStore(mockDataSource, "test_table")
    val result = store.getVersioned(MemoryId("test-id"))

    result.isRight shouldBe true
    result.map { opt =>
      opt shouldBe defined
      opt.get.version shouldBe 0L // NULL should default to 0
    }
  }

  it should "retry update on OptimisticLockFailure with retryingUpdate" in {
    // Setup connection and statement mocks to be reused
    (() => mockDataSource.getConnection()).expects().returning(mockConn).anyNumberOfTimes()
    (mockConn.prepareStatement(_: String)).expects(*).returning(mockStmt).anyNumberOfTimes()

    // Mock all the database interaction methods to be called multiple times
    (mockStmt.setString(_: Int, _: String)).expects(*, *).anyNumberOfTimes()
    (() => mockStmt.executeQuery()).expects().returning(mockRs).anyNumberOfTimes()
    (() => mockRs.next()).expects().returning(true).anyNumberOfTimes()
    (mockRs
      .getString(_: String))
      .expects(*)
      .onCall { (arg: String) =>
        arg match {
          case "id"          => "test-id"
          case "content"     => "v1" // Will cause the update function to try "v1-updated"
          case "memory_type" => "task"
          case "metadata"    => "{}"
          case "embedding"   => null
          case _             => ""
        }
      }
      .anyNumberOfTimes()
    (mockRs.getTimestamp(_: String)).expects(*).returning(Timestamp.from(Instant.now())).anyNumberOfTimes()
    (mockRs.getDouble(_: String)).expects(*).returning(0.5).anyNumberOfTimes()
    (() => mockRs.wasNull()).expects().returning(false).anyNumberOfTimes()
    (mockRs
      .getLong(_: String))
      .expects("version")
      .returning(1L)
      .twice() // First attempt gets version 1, retry gets version 1 again (or 2)
    (() => mockRs.close()).expects().anyNumberOfTimes()
    (() => mockStmt.close()).expects().anyNumberOfTimes()
    (() => mockConn.close()).expects().anyNumberOfTimes()
    (mockStmt.setTimestamp(_: Int, _: Timestamp)).expects(*, *).anyNumberOfTimes()
    (mockStmt.setNull(_: Int, _: Int, _: String)).expects(*, *, *).anyNumberOfTimes()
    (mockStmt.setDouble(_: Int, _: Double)).expects(*, *).anyNumberOfTimes()
    (mockStmt.setLong(_: Int, _: Long)).expects(*, *).anyNumberOfTimes()

    // First update attempt returns 0 (conflict), second returns 1 (success)
    (() => mockStmt.executeUpdate()).expects().returning(0).once()
    (() => mockStmt.executeUpdate()).expects().returning(1).once()

    val store = new PostgresMemoryStore(mockDataSource, "test_table")
    val result = store.retryingUpdate(
      MemoryId("test-id"),
      mem => mem.copy(content = mem.content + "-updated"),
      maxRetries = 5
    )

    result.isRight shouldBe true
  }
}
