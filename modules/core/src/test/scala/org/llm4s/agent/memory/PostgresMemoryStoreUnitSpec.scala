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
    val error = result.left.toOption.get
    error.message should include("Invalid metadata key")
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

  // Compound Filter Tests
  it should "generate SQL for And filter" in {
    val filter = MemoryFilter.And(
      MemoryFilter.ByType(MemoryType.Task),
      MemoryFilter.MinImportance(0.5)
    )
    val result = PostgresMemoryStore.filterToSql(filter)

    result.isRight shouldBe true
    val (sql, params) = result.toOption.get
    sql shouldBe "(memory_type = ? AND importance >= ?)"
    params shouldBe Seq(PString("task"), PDouble(0.5))
  }

  it should "generate SQL for Or filter" in {
    val filter = MemoryFilter.Or(
      MemoryFilter.ByType(MemoryType.Task),
      MemoryFilter.ByType(MemoryType.Conversation)
    )
    val result = PostgresMemoryStore.filterToSql(filter)

    result.isRight shouldBe true
    val (sql, params) = result.toOption.get
    sql shouldBe "(memory_type = ? OR memory_type = ?)"
    params shouldBe Seq(PString("task"), PString("conversation"))
  }

  it should "generate SQL for Not filter" in {
    val filter = MemoryFilter.Not(MemoryFilter.ByType(MemoryType.Task))
    val result = PostgresMemoryStore.filterToSql(filter)

    result.isRight shouldBe true
    val (sql, params) = result.toOption.get
    sql shouldBe "NOT (memory_type = ?)"
    params shouldBe Seq(PString("task"))
  }

  it should "generate SQL for nested compound filters" in {
    // And(Or(A, B), Not(C))
    val filter = MemoryFilter.And(
      MemoryFilter.Or(
        MemoryFilter.ByType(MemoryType.Task),
        MemoryFilter.ByType(MemoryType.Conversation)
      ),
      MemoryFilter.Not(MemoryFilter.MinImportance(0.9))
    )
    val result = PostgresMemoryStore.filterToSql(filter)

    result.isRight shouldBe true
    val (sql, params) = result.toOption.get
    sql shouldBe "((memory_type = ? OR memory_type = ?) AND NOT (importance >= ?))"
    params shouldBe Seq(PString("task"), PString("conversation"), PDouble(0.9))
  }

  it should "propagate errors from nested compound filters" in {
    // And with invalid metadata key should fail
    val filter = MemoryFilter.And(
      MemoryFilter.ByType(MemoryType.Task),
      MemoryFilter.ByMetadata("invalid-key; --", "value")
    )
    val result = PostgresMemoryStore.filterToSql(filter)

    result.isLeft shouldBe true
    val error = result.left.toOption.get
    error.message should include("Invalid metadata key")
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

  it should "perform a versioned update successfully when no concurrent modification occurred" in {
    val ts = Timestamp.from(Instant.now())

    // Connection 1: getVersioned() SELECT
    (() => mockDataSource.getConnection()).expects().returning(mockConn)
    (mockConn.prepareStatement(_: String)).expects(*).returning(mockStmt)
    (mockStmt.setString(_: Int, _: String)).expects(*, *).anyNumberOfTimes()
    (() => mockStmt.executeQuery()).expects().returning(mockRs)
    (() => mockRs.next()).expects().returning(true)
    (mockRs.getString(_: String)).expects("embedding").returning(null)
    (mockRs.getString(_: String)).expects("id").returning("mem-cas-1")
    (mockRs.getString(_: String)).expects("content").returning("original content")
    (mockRs.getString(_: String)).expects("memory_type").returning("task")
    (mockRs.getString(_: String)).expects("metadata").returning("{}")
    (mockRs.getTimestamp(_: String)).expects("created_at").returning(ts)
    (mockRs.getDouble(_: String)).expects("importance").returning(0.0)
    (() => mockRs.wasNull()).expects().returning(true)
    (mockRs.getLong(_: String)).expects("version").returning(0L)
    (() => mockRs.close()).expects()
    (() => mockStmt.close()).expects()
    (() => mockConn.close()).expects()

    // Connection 2: CAS UPDATE (1 row affected = success)
    (() => mockDataSource.getConnection()).expects().returning(mockConn)
    (mockConn.prepareStatement(_: String)).expects(*).returning(mockStmt)
    (mockStmt.setString(_: Int, _: String)).expects(*, *).anyNumberOfTimes()
    (mockStmt.setTimestamp(_: Int, _: Timestamp)).expects(*, *).anyNumberOfTimes()
    (mockStmt.setDouble(_: Int, _: Double)).expects(*, *).anyNumberOfTimes()
    (mockStmt.setLong(_: Int, _: Long)).expects(*, *).anyNumberOfTimes()
    (mockStmt.setNull(_: Int, _: Int)).expects(*, *).anyNumberOfTimes()
    (mockStmt.setNull(_: Int, _: Int, _: String)).expects(*, *, *).anyNumberOfTimes()
    (() => mockStmt.executeUpdate()).expects().returning(1)
    (() => mockStmt.close()).expects()
    (() => mockConn.close()).expects()

    val store  = new PostgresMemoryStore(mockDataSource, "test_table")
    val result = store.update(MemoryId("mem-cas-1"), _.copy(content = "updated content"))

    result.isRight shouldBe true
  }

  it should "return OptimisticLockFailure when a concurrent modification is detected" in {
    val ts = Timestamp.from(Instant.now())

    // Connection 1: getVersioned() SELECT
    (() => mockDataSource.getConnection()).expects().returning(mockConn)
    (mockConn.prepareStatement(_: String)).expects(*).returning(mockStmt)
    (mockStmt.setString(_: Int, _: String)).expects(*, *).anyNumberOfTimes()
    (() => mockStmt.executeQuery()).expects().returning(mockRs)
    (() => mockRs.next()).expects().returning(true)
    (mockRs.getString(_: String)).expects("embedding").returning(null)
    (mockRs.getString(_: String)).expects("id").returning("mem-cas-2")
    (mockRs.getString(_: String)).expects("content").returning("original content")
    (mockRs.getString(_: String)).expects("memory_type").returning("task")
    (mockRs.getString(_: String)).expects("metadata").returning("{}")
    (mockRs.getTimestamp(_: String)).expects("created_at").returning(ts)
    (mockRs.getDouble(_: String)).expects("importance").returning(0.0)
    (() => mockRs.wasNull()).expects().returning(true)
    (mockRs.getLong(_: String)).expects("version").returning(5L)
    (() => mockRs.close()).expects()
    (() => mockStmt.close()).expects()
    (() => mockConn.close()).expects()

    // Connection 2: CAS UPDATE (0 rows affected = concurrent modification)
    (() => mockDataSource.getConnection()).expects().returning(mockConn)
    (mockConn.prepareStatement(_: String)).expects(*).returning(mockStmt)
    (mockStmt.setString(_: Int, _: String)).expects(*, *).anyNumberOfTimes()
    (mockStmt.setTimestamp(_: Int, _: Timestamp)).expects(*, *).anyNumberOfTimes()
    (mockStmt.setDouble(_: Int, _: Double)).expects(*, *).anyNumberOfTimes()
    (mockStmt.setLong(_: Int, _: Long)).expects(*, *).anyNumberOfTimes()
    (mockStmt.setNull(_: Int, _: Int)).expects(*, *).anyNumberOfTimes()
    (mockStmt.setNull(_: Int, _: Int, _: String)).expects(*, *, *).anyNumberOfTimes()
    (() => mockStmt.executeUpdate()).expects().returning(0)
    (() => mockStmt.close()).expects()
    (() => mockConn.close()).expects()

    val store  = new PostgresMemoryStore(mockDataSource, "test_table")
    val result = store.update(MemoryId("mem-cas-2"), _.copy(content = "updated content"))

    result.isLeft shouldBe true
    val error = result.left.toOption.get
    error shouldBe an[OptimisticLockFailure]
    error.asInstanceOf[OptimisticLockFailure].memoryId shouldBe "mem-cas-2"
    error.asInstanceOf[OptimisticLockFailure].attemptedVersion shouldBe 5L
  }
}
