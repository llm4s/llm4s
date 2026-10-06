package org.llm4s.assistant

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach
import org.llm4s.agent.{ AgentResultFixture, AgentStatus }
import org.llm4s.llmconnect.model._
import org.llm4s.types.{ DirectoryPath, SessionId }

import java.nio.file.{ Files, Path }
import java.time.LocalDateTime
import scala.util.Try

/**
 * Tests for SessionManager functionality.
 *
 * Tests session save/load operations using temporary directories
 * to avoid file system side effects.
 */
class SessionManagerSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private var tempDir: Path = _

  override def beforeEach(): Unit =
    tempDir = Files.createTempDirectory("session-manager-test")

  override def afterEach(): Unit =
    // Clean up temp directory
    Try {
      Files.walk(tempDir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete(_))
    }

  private def stateWith(messages: Vector[Message], sessionId: String): SessionState =
    SessionState(
      threadId = None,
      last = None,
      sessionId = SessionId(sessionId),
      sessionDir = DirectoryPath(tempDir.toString),
      created = LocalDateTime.now()
    ).withResult(AgentResultFixture(AgentStatus.Completed("done"), messages))

  private def createTestState(sessionId: String = "test-session"): SessionState =
    stateWith(Vector(UserMessage("Hello"), AssistantMessage("Hi there!")), sessionId)

  // ==========================================================================
  // Session Save Tests
  // ==========================================================================

  "SessionManager.saveSession" should "save session with default title" in {
    val manager = new SessionManager(DirectoryPath(tempDir.toString))
    val state   = createTestState()

    val result = manager.saveSession(state, Some("Test Session"))

    result.isRight shouldBe true
    val info = result.toOption.get
    info.title shouldBe "Test Session"
    info.messageCount shouldBe 2
  }

  it should "create both JSON and markdown files" in {
    val manager = new SessionManager(DirectoryPath(tempDir.toString))
    val state   = createTestState()

    manager.saveSession(state, Some("My Session"))

    val jsonExists     = Files.exists(tempDir.resolve("My_Session.json"))
    val markdownExists = Files.exists(tempDir.resolve("My_Session.md"))

    jsonExists shouldBe true
    markdownExists shouldBe true
  }

  it should "return error when there is no conversation to save" in {
    val manager = new SessionManager(DirectoryPath(tempDir.toString))
    val state = SessionState(
      threadId = None,
      last = None,
      sessionId = SessionId("empty"),
      sessionDir = DirectoryPath(tempDir.toString),
      created = LocalDateTime.now()
    )

    val result = manager.saveSession(state, Some("Empty Session"))

    result.isLeft shouldBe true
  }

  it should "sanitize special characters in title" in {
    val manager = new SessionManager(DirectoryPath(tempDir.toString))
    val state   = createTestState()

    manager.saveSession(state, Some("Test/Session:With*Special?Chars"))

    // Filename should be sanitized
    val stream = Files.list(tempDir)
    val files =
      try stream.toArray.map(_.asInstanceOf[Path]).toSeq
      finally stream.close()
    files.foreach { file =>
      (file.getFileName.toString should not).include("/")
      (file.getFileName.toString should not).include(":")
      (file.getFileName.toString should not).include("*")
      (file.getFileName.toString should not).include("?")
    }
  }

  it should "use the injected suffix generator to disambiguate a filename collision" in {
    var calls = 0
    val manager = new SessionManager(
      DirectoryPath(tempDir.toString),
      uniqueSuffix = () => { calls += 1; s"stub-$calls" }
    )

    manager.saveSession(createTestState("s1"), Some("Same Title"))
    manager.saveSession(createTestState("s2"), Some("Same Title"))

    calls shouldBe 1
    Files.exists(tempDir.resolve("Same_Title-stub-1.json")) shouldBe true
  }

  // ==========================================================================
  // Session Load Tests
  // ==========================================================================

  "SessionManager.loadSession" should "load previously saved session" in {
    val manager = new SessionManager(DirectoryPath(tempDir.toString))
    val state   = createTestState("original-session")

    manager.saveSession(state, Some("LoadTest"))

    val loadResult = manager.loadSession("LoadTest")

    loadResult.isRight shouldBe true
    val loaded = loadResult.toOption.get
    loaded.sessionId.value shouldBe "original-session"
    loaded.threadId shouldBe None
    loaded.last shouldBe None
    loaded.history should have size 2
    loaded.messages shouldBe loaded.history
  }

  it should "preserve conversation content" in {
    val manager = new SessionManager(DirectoryPath(tempDir.toString))
    val state   = createTestState()

    manager.saveSession(state, Some("ContentTest"))
    val loadResult = manager.loadSession("ContentTest")

    val loaded   = loadResult.toOption.get
    val messages = loaded.history

    messages.head.content shouldBe "Hello"
    messages(1).content shouldBe "Hi there!"
  }

  it should "return error for non-existent session" in {
    val manager = new SessionManager(DirectoryPath(tempDir.toString))

    val result = manager.loadSession("NonExistent")

    result.isLeft shouldBe true
  }

  // ==========================================================================
  // List Sessions Tests
  // ==========================================================================

  "SessionManager.listRecentSessions" should "list saved sessions" in {
    val manager = new SessionManager(DirectoryPath(tempDir.toString))

    // Save multiple sessions
    manager.saveSession(createTestState("s1"), Some("Session1"))
    Thread.sleep(10) // Ensure different timestamps
    manager.saveSession(createTestState("s2"), Some("Session2"))
    Thread.sleep(10)
    manager.saveSession(createTestState("s3"), Some("Session3"))

    val result = manager.listRecentSessions(limit = 5)

    result.isRight shouldBe true
    val sessions = result.toOption.get
    sessions should have size 3
  }

  it should "limit number of results" in {
    val manager = new SessionManager(DirectoryPath(tempDir.toString))

    // Save multiple sessions
    (1 to 10).foreach { i =>
      manager.saveSession(createTestState(s"s$i"), Some(s"Session$i"))
      Thread.sleep(5)
    }

    val result = manager.listRecentSessions(limit = 3)

    result.isRight shouldBe true
    result.toOption.get should have size 3
  }

  it should "return most recent sessions first" in {
    val manager = new SessionManager(DirectoryPath(tempDir.toString))

    manager.saveSession(createTestState("s1"), Some("OldSession"))
    Thread.sleep(50)
    manager.saveSession(createTestState("s2"), Some("NewSession"))

    val result = manager.listRecentSessions(limit = 5)

    result.isRight shouldBe true
    val sessions = result.toOption.get
    sessions.head shouldBe "NewSession"
  }

  it should "return empty list for empty directory" in {
    val manager = new SessionManager(DirectoryPath(tempDir.toString))

    val result = manager.listRecentSessions()

    result.isRight shouldBe true
    result.toOption.get shouldBe empty
  }

  // ==========================================================================
  // Round-trip Tests
  // ==========================================================================

  "SessionManager" should "round-trip tool calls and their results" in {
    val manager = new SessionManager(DirectoryPath(tempDir.toString))
    val call    = ToolCall("call-1", "lookup", ujson.Obj("q" -> "weather"))
    val messages = Vector(
      UserMessage("What is the weather?"),
      AssistantMessage(None, Seq(call)),
      ToolMessage("""{"forecast":"sun"}""", "call-1"),
      AssistantMessage("Sunny.")
    )

    manager.saveSession(stateWith(messages, "tools-test"), Some("ToolsTest"))
    val loaded = manager.loadSession("ToolsTest").toOption.get

    loaded.history shouldBe messages
  }

  it should "write each message, and each tool call, to the markdown companion" in {
    val manager = new SessionManager(DirectoryPath(tempDir.toString))
    val call    = ToolCall("call-1", "lookup", ujson.Obj("q" -> "weather"))
    val state = stateWith(
      Vector(UserMessage("What is the weather?"), AssistantMessage(None, Seq(call)), ToolMessage("sun", "call-1")),
      "markdown-test"
    )

    manager.saveSession(state, Some("Markdown"))
    val markdown = Files.readString(tempDir.resolve("Markdown.md"))

    markdown should include("# Markdown")
    markdown should include("**Messages:** 3")
    markdown should include("What is the weather?")
    markdown should include("Tool call `lookup` (call-1)")
    markdown should include("_Result of tool call call-1_")
  }
}
