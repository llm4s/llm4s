package org.llm4s.assistant

import org.llm4s.types.{ SessionId, DirectoryPath }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.time.LocalDateTime

/**
 * Tests for SessionState, SessionInfo, and SessionSummary
 */
class SessionStateSpec extends AnyFlatSpec with Matchers {

  // ============ SessionState ============

  "SessionState" should "create with no agent state" in {
    val state = SessionState(
      agentState = None,
      sessionId = SessionId("test-123"),
      sessionDir = DirectoryPath("./sessions")
    )

    state.agentState shouldBe None
    state.sessionId shouldBe SessionId("test-123")
    state.sessionDir shouldBe DirectoryPath("./sessions")
  }

  it should "have a default created timestamp" in {
    val before = LocalDateTime.now()
    val state = SessionState(
      agentState = None,
      sessionId = SessionId("test"),
      sessionDir = DirectoryPath("./")
    )
    val after = LocalDateTime.now()

    state.created should not be null
    state.created.isAfter(before.minusSeconds(1)) shouldBe true
    state.created.isBefore(after.plusSeconds(1)) shouldBe true
  }

  it should "create new session with withNewSession" in {
    val originalId = SessionId("original-123")
    val original = SessionState(
      agentState = None,
      sessionId = originalId,
      sessionDir = DirectoryPath("./sessions")
    )

    // Small delay to ensure timestamp will be different on fast machines
    Thread.sleep(2)
    val newState = original.withNewSession()

    newState.agentState shouldBe None
    newState.sessionId should not be originalId
    newState.sessionDir shouldBe DirectoryPath("./sessions")
    // Verify new session has a fresh timestamp (equal or later than original)
    (newState.created.isEqual(original.created) || newState.created.isAfter(original.created)) shouldBe true
  }

  // ============ SessionInfo ============

  "SessionInfo" should "store all fields correctly" in {
    val created = LocalDateTime.of(2024, 1, 15, 10, 30)
    val info = SessionInfo(
      id = SessionId("session-456"),
      title = "Test Session",
      filePath = org.llm4s.types.FilePath("./sessions/test.json"),
      created = created,
      messageCount = 10,
      fileSize = 1024L
    )

    info.id shouldBe SessionId("session-456")
    info.title shouldBe "Test Session"
    info.filePath shouldBe org.llm4s.types.FilePath("./sessions/test.json")
    info.created shouldBe created
    info.messageCount shouldBe 10
    info.fileSize shouldBe 1024L
  }

  it should "serialize to JSON" in {
    val created = LocalDateTime.of(2024, 1, 15, 10, 30)
    val info = SessionInfo(
      id = SessionId("test"),
      title = "My Session",
      filePath = org.llm4s.types.FilePath("./test.json"),
      created = created,
      messageCount = 5,
      fileSize = 512L
    )

    import upickle.default._
    val json   = write(info)
    val parsed = read[SessionInfo](json)

    parsed.id shouldBe info.id
    parsed.title shouldBe info.title
    parsed.messageCount shouldBe info.messageCount
  }

  // ============ SessionSummary ============

  "SessionSummary" should "store all fields correctly" in {
    val created = LocalDateTime.of(2024, 6, 20, 14, 45)
    val summary = SessionSummary(
      id = SessionId("summary-789"),
      title = "Quick Chat",
      filename = "quick_chat.json",
      created = created
    )

    summary.id shouldBe SessionId("summary-789")
    summary.title shouldBe "Quick Chat"
    summary.filename shouldBe "quick_chat.json"
    summary.created shouldBe created
  }

  it should "serialize to JSON" in {
    val created = LocalDateTime.of(2024, 3, 10, 8, 0)
    val summary = SessionSummary(
      id = SessionId("sum"),
      title = "Test",
      filename = "test.json",
      created = created
    )

    import upickle.default._
    val json   = write(summary)
    val parsed = read[SessionSummary](json)

    parsed.id shouldBe summary.id
    parsed.title shouldBe summary.title
    parsed.filename shouldBe summary.filename
  }

  // ============ LocalDateTime ReadWriter ============

  "SessionState.localDateTimeRW" should "serialize LocalDateTime to string" in {
    import upickle.default._
    import SessionState.localDateTimeRW

    val dt     = LocalDateTime.of(2024, 12, 25, 10, 30, 45)
    val json   = write(dt)
    val parsed = read[LocalDateTime](json)

    parsed shouldBe dt
  }

  it should "handle various datetime formats" in {
    import upickle.default._
    import SessionState.localDateTimeRW

    val dt1 = LocalDateTime.of(2024, 1, 1, 0, 0)
    val dt2 = LocalDateTime.of(2024, 12, 31, 23, 59, 59)

    read[LocalDateTime](write(dt1)) shouldBe dt1
    read[LocalDateTime](write(dt2)) shouldBe dt2
  }
}
