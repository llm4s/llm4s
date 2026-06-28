package org.llm4s.assistant

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach
import org.llm4s.agent.{ Agent, AgentState, AgentStatus }
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.ToolRegistry
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
  private val mockClient    = null.asInstanceOf[org.llm4s.llmconnect.LLMClient]
  private val emptyTools    = new ToolRegistry(Seq.empty)

  override def beforeEach(): Unit =
    tempDir = Files.createTempDirectory("session-manager-test")

  override def afterEach(): Unit =
    // Clean up temp directory
    Try {
      Files.walk(tempDir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete(_))
    }

  private def createTestAgent(): Agent = new Agent(mockClient)

  private def createTestState(sessionId: String = "test-session"): SessionState = {
    val agentState = AgentState(
      conversation = Conversation(
        Seq(
          UserMessage("Hello"),
          AssistantMessage("Hi there!")
        )
      ),
      tools = emptyTools,
      initialQuery = Some("Hello"),
      status = AgentStatus.Complete,
      logs = Seq("Started", "Completed")
    )

    SessionState(
      agentState = Some(agentState),
      sessionId = SessionId(sessionId),
      sessionDir = DirectoryPath(tempDir.toString),
      created = LocalDateTime.now()
    )
  }

  // ==========================================================================
  // Session Save Tests
  // ==========================================================================

  "SessionManager.saveSession" should "save session with default title" in {
    val agent   = createTestAgent()
    val manager = new SessionManager(DirectoryPath(tempDir.toString), agent)
    val state   = createTestState()

    val result = manager.saveSession(state, Some("Test Session"))

    result.isRight shouldBe true
    val info = result.toOption.get
    info.title shouldBe "Test Session"
    info.messageCount shouldBe 2
  }

  it should "create both JSON and markdown files" in {
    val agent   = createTestAgent()
    val manager = new SessionManager(DirectoryPath(tempDir.toString), agent)
    val state   = createTestState()

    manager.saveSession(state, Some("My Session"))

    val jsonExists     = Files.exists(tempDir.resolve("My_Session.json"))
    val markdownExists = Files.exists(tempDir.resolve("My_Session.md"))

    jsonExists shouldBe true
    markdownExists shouldBe true
  }

  it should "return error when no agent state to save" in {
    val agent   = createTestAgent()
    val manager = new SessionManager(DirectoryPath(tempDir.toString), agent)
    val state = SessionState(
      agentState = None,
      sessionId = SessionId("empty"),
      sessionDir = DirectoryPath(tempDir.toString),
      created = LocalDateTime.now()
    )

    val result = manager.saveSession(state, Some("Empty Session"))

    result.isLeft shouldBe true
  }

  it should "sanitize special characters in title" in {
    val agent   = createTestAgent()
    val manager = new SessionManager(DirectoryPath(tempDir.toString), agent)
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

  // ==========================================================================
  // Session Load Tests
  // ==========================================================================

  "SessionManager.loadSession" should "load previously saved session" in {
    val agent   = createTestAgent()
    val manager = new SessionManager(DirectoryPath(tempDir.toString), agent)
    val state   = createTestState("original-session")

    manager.saveSession(state, Some("LoadTest"))

    val loadResult = manager.loadSession("LoadTest", emptyTools)

    loadResult.isRight shouldBe true
    val loaded = loadResult.toOption.get
    loaded.sessionId.value shouldBe "original-session"
    loaded.agentState.isDefined shouldBe true
    loaded.agentState.get.conversation.messages should have size 2
  }

  it should "preserve conversation content" in {
    val agent   = createTestAgent()
    val manager = new SessionManager(DirectoryPath(tempDir.toString), agent)
    val state   = createTestState()

    manager.saveSession(state, Some("ContentTest"))
    val loadResult = manager.loadSession("ContentTest", emptyTools)

    val loaded   = loadResult.toOption.get
    val messages = loaded.agentState.get.conversation.messages

    messages.head.content shouldBe "Hello"
    messages(1).content shouldBe "Hi there!"
  }

  it should "return error for non-existent session" in {
    val agent   = createTestAgent()
    val manager = new SessionManager(DirectoryPath(tempDir.toString), agent)

    val result = manager.loadSession("NonExistent", emptyTools)

    result.isLeft shouldBe true
  }

  // ==========================================================================
  // List Sessions Tests
  // ==========================================================================

  "SessionManager.listRecentSessions" should "list saved sessions" in {
    val agent   = createTestAgent()
    val manager = new SessionManager(DirectoryPath(tempDir.toString), agent)

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
    val agent   = createTestAgent()
    val manager = new SessionManager(DirectoryPath(tempDir.toString), agent)

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
    val agent   = createTestAgent()
    val manager = new SessionManager(DirectoryPath(tempDir.toString), agent)

    manager.saveSession(createTestState("s1"), Some("OldSession"))
    Thread.sleep(50)
    manager.saveSession(createTestState("s2"), Some("NewSession"))

    val result = manager.listRecentSessions(limit = 5)

    result.isRight shouldBe true
    val sessions = result.toOption.get
    sessions.head shouldBe "NewSession"
  }

  it should "return empty list for empty directory" in {
    val agent   = createTestAgent()
    val manager = new SessionManager(DirectoryPath(tempDir.toString), agent)

    val result = manager.listRecentSessions()

    result.isRight shouldBe true
    result.toOption.get shouldBe empty
  }

  // ==========================================================================
  // Round-trip Tests
  // ==========================================================================

  "SessionManager" should "round-trip session with logs" in {
    val agent   = createTestAgent()
    val manager = new SessionManager(DirectoryPath(tempDir.toString), agent)

    val agentState = AgentState(
      conversation = Conversation(Seq(UserMessage("Test"))),
      tools = emptyTools,
      logs = Seq("Log entry 1", "Log entry 2", "Log entry 3")
    )

    val state = SessionState(
      agentState = Some(agentState),
      sessionId = SessionId("log-test"),
      sessionDir = DirectoryPath(tempDir.toString),
      created = LocalDateTime.now()
    )

    manager.saveSession(state, Some("LogTest"))
    val loaded = manager.loadSession("LogTest", emptyTools).toOption.get

    loaded.agentState.get.logs shouldBe Seq("Log entry 1", "Log entry 2", "Log entry 3")
  }

  it should "round-trip session with initial query" in {
    val agent   = createTestAgent()
    val manager = new SessionManager(DirectoryPath(tempDir.toString), agent)

    val agentState = AgentState(
      conversation = Conversation(Seq(UserMessage("What is 2+2?"))),
      tools = emptyTools,
      initialQuery = Some("What is 2+2?")
    )

    val state = SessionState(
      agentState = Some(agentState),
      sessionId = SessionId("query-test"),
      sessionDir = DirectoryPath(tempDir.toString),
      created = LocalDateTime.now()
    )

    manager.saveSession(state, Some("QueryTest"))
    val loaded = manager.loadSession("QueryTest", emptyTools).toOption.get

    loaded.agentState.get.initialQuery shouldBe Some("What is 2+2?")
  }

  it should "round-trip session with different statuses" in {
    val agent   = createTestAgent()
    val manager = new SessionManager(DirectoryPath(tempDir.toString), agent)

    val statuses = Seq(
      AgentStatus.Complete,
      AgentStatus.InProgress,
      AgentStatus.WaitingForTools,
      AgentStatus.Failed("Test error")
    )

    statuses.zipWithIndex.foreach { case (status, idx) =>
      val agentState = AgentState(
        conversation = Conversation(Seq(UserMessage("Test"))),
        tools = emptyTools,
        status = status
      )

      val state = SessionState(
        agentState = Some(agentState),
        sessionId = SessionId(s"status-test-$idx"),
        sessionDir = DirectoryPath(tempDir.toString),
        created = LocalDateTime.now()
      )

      manager.saveSession(state, Some(s"StatusTest$idx"))
      val loaded = manager.loadSession(s"StatusTest$idx", emptyTools).toOption.get

      status match {
        case AgentStatus.Failed(error) =>
          loaded.agentState.get.status shouldBe a[AgentStatus.Failed]
          loaded.agentState.get.status.asInstanceOf[AgentStatus.Failed].error shouldBe error
        case _ =>
          loaded.agentState.get.status shouldBe status
      }
    }
  }
}
