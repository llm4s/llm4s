package org.llm4s.assistant

import scala.util.Using
import scala.util.chaining.scalaUtilChainingOps

import org.llm4s.testutil.TestTimeLimit

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
class SessionManagerSpec
    extends AnyFlatSpec
    with TestTimeLimit
    with Matchers
    with BeforeAndAfterEach
    with org.scalatest.EitherValues {

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

    manager.saveSession(createTestState("s1"), Some("Same Title"), overwrite = false)
    val second = manager.saveSession(createTestState("s2"), Some("Same Title"), overwrite = false)

    calls shouldBe 1
    Files.exists(tempDir.resolve("Same_Title-stub-1.json")) shouldBe true
    // the name it reports is one loadSession finds
    second.map(_.title) shouldBe Right("Same Title-stub-1")
    manager.loadSession("Same Title-stub-1").map(_.sessionId.value) shouldBe Right("s2")
  }

  it should "replace an earlier save under the same title, so loading it restores the latest (#3277)" in {
    val dir     = Files.createTempDirectory("sessions-overwrite")
    val manager = new SessionManager(DirectoryPath(dir.toString))
    manager.saveSession(createTestState("first"), Some("notes")).map(_.title) shouldBe Right("notes")
    manager.saveSession(createTestState("second"), Some("notes")).map(_.title) shouldBe Right("notes")
    manager.loadSession("notes").map(_.sessionId.value) shouldBe Right("second")
    Using.resource(Files.list(dir))(_.toArray.length) shouldBe 2 // notes.json and notes.md, no temporary file left
  }

  it should "keep titles in other scripts apart, and never name a file by a dot, nothing or a device (#3277)" in {
    val manager = new SessionManager(DirectoryPath(tempDir.toString))
    manager.sanitizeFilename("日本語") should not be manager.sanitizeFilename("中文版")
    manager.sanitizeFilename("日本語") shouldBe "日本語"
    manager.sanitizeFilename("") shouldBe "_"
    manager.sanitizeFilename("..") shouldBe "_.."
    manager.sanitizeFilename("CON") shouldBe "_CON"
    manager.sanitizeFilename("con.txt") shouldBe "_con.txt"
    manager.sanitizeFilename("a/b:c") shouldBe "a_b_c"
  }

  it should "load a session a release before #3277 saved under its ASCII-only name" in {
    val dir     = Files.createTempDirectory("sessions-legacy")
    val manager = new SessionManager(DirectoryPath(dir.toString))
    // the format before #3277: no stored title, the name with every non-ASCII character as `_`
    manager.saveSession(createTestState("legacy"), Some("___"))
    val json = dir.resolve("___.json")
    Files.writeString(json, ujson.write(ujson.read(Files.readString(json)).obj.tap(_.remove("title"))))
    manager.loadSession("日本語").map(_.sessionId.value) shouldBe Right("legacy")
  }

  it should "load every title a save returns or the listing shows, for titles of 1 to 60 characters (#3277)" in {
    val dir     = Files.createTempDirectory("sessions-roundtrip")
    var n       = 0
    val manager = new SessionManager(DirectoryPath(dir.toString), uniqueSuffix = () => { n += 1; s"s$n" })
    val saved   = scala.collection.mutable.Map.empty[String, String] // title -> session id last saved under it
    for {
      length <- 1 to 60
      stem = "t" * (length - 1)
      title     <- Seq(stem + "/", stem + ":", stem + "é") // three titles that sanitise alike, or nearly
      overwrite <- Seq(true, false)
    } {
      val id       = s"$title|$overwrite"
      val returned = manager.saveSession(createTestState(id), Some(title), overwrite).map(_.title).value
      saved(returned) = id
      withClue(s"'$returned' (length $length, overwrite $overwrite)") {
        manager.loadSession(returned).map(_.sessionId.value) shouldBe Right(id)
      }
    }
    saved.foreach { case (title, id) =>
      withClue(title)(manager.loadSession(title).map(_.sessionId.value) shouldBe Right(id))
    }
    manager.listRecentSessions(Int.MaxValue).value.foreach { listed =>
      withClue(listed)(manager.loadSession(listed).isRight shouldBe true)
    }
  }

  it should "never replace a save of a different title whose file name is the same (#3277)" in {
    val dir     = Files.createTempDirectory("sessions-collide")
    var n       = 0
    val manager = new SessionManager(DirectoryPath(dir.toString), uniqueSuffix = () => { n += 1; s"s$n" })
    manager.saveSession(createTestState("slash"), Some("project/a")).map(_.title) shouldBe Right("project/a")
    manager.saveSession(createTestState("colon"), Some("project:a")).map(_.title) shouldBe Right("project:a")
    manager.loadSession("project/a").map(_.sessionId.value) shouldBe Right("slash")
    manager.loadSession("project:a").map(_.sessionId.value) shouldBe Right("colon")
    // a save of the same title still replaces its own file, whichever name it has
    manager.saveSession(createTestState("colon-2"), Some("project:a"))
    manager.loadSession("project:a").map(_.sessionId.value) shouldBe Right("colon-2")
    manager.loadSession("project/a").map(_.sessionId.value) shouldBe Right("slash")
    // titles that agree in their first 50 sanitised characters
    val prefix = "x" * 50
    manager.saveSession(createTestState("one"), Some(prefix + "one"))
    manager.saveSession(createTestState("two"), Some(prefix + "two"))
    manager.loadSession(prefix + "one").map(_.sessionId.value) shouldBe Right("one")
    manager.loadSession(prefix + "two").map(_.sessionId.value) shouldBe Right("two")
    // a suffixed title the sanitised name cuts differently is still found by the title the save reported
    val long = "y" * 49
    manager.saveSession(createTestState("first"), Some(long), overwrite = false)
    val second = manager.saveSession(createTestState("second"), Some(long), overwrite = false).map(_.title)
    second.flatMap(manager.loadSession).map(_.sessionId.value) shouldBe Right("second")
    manager.loadSession(long).map(_.sessionId.value) shouldBe Right("first")
    manager.listRecentSessions(10).map(_.toSet -- Set(long) -- second.toOption) shouldBe Right(
      Set("project/a", "project:a", prefix + "one", prefix + "two")
    )
  }

  it should "write the session files owner-only where the file system has POSIX permissions" in {
    val dir = Files.createTempDirectory("sessions-private")
    assume(dir.getFileSystem.supportedFileAttributeViews.contains("posix"), "POSIX permissions")
    new SessionManager(DirectoryPath(dir.toString)).saveSession(createTestState("p"), Some("private"))
    Seq("private.json", "private.md").foreach { name =>
      java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve(name))) shouldBe
        "rw-------"
    }
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
    val call    = ToolCall(ToolCallId("call-1"), "lookup", ujson.Obj("q" -> "weather"))
    val messages = Vector(
      UserMessage("What is the weather?"),
      AssistantMessage(None, Seq(call)),
      ToolMessage("""{"forecast":"sun"}""", ToolCallId("call-1")),
      AssistantMessage("Sunny.")
    )

    manager.saveSession(stateWith(messages, "tools-test"), Some("ToolsTest"))
    val loaded = manager.loadSession("ToolsTest").toOption.get

    loaded.history shouldBe messages
  }

  it should "write each message, and each tool call, to the markdown companion" in {
    val manager = new SessionManager(DirectoryPath(tempDir.toString))
    val call    = ToolCall(ToolCallId("call-1"), "lookup", ujson.Obj("q" -> "weather"))
    val state = stateWith(
      Vector(
        UserMessage("What is the weather?"),
        AssistantMessage(None, Seq(call)),
        ToolMessage("sun", ToolCallId("call-1"))
      ),
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

  "A saved session" should "name a message's media in its markdown, and keep the media in its JSON" in {
    val chart = ContentPart
      .image(
        org.llm4s.media.MediaSource.Url(java.net.URI.create("https://e.com/chart.png")),
        org.llm4s.media.MediaType.Png
      )
      .toOption
      .get
    val manager = new SessionManager(DirectoryPath(tempDir.toString))
    manager.saveSession(
      stateWith(Vector(UserMessage(Vector(ContentPart.Text("see"), chart)), AssistantMessage("ok")), "media"),
      Some("Media")
    )
    val markdown = new String(Files.readAllBytes(tempDir.resolve("Media.md")), "UTF-8")
    markdown should include("see [image (url, image/png)]")
    val json = new String(Files.readAllBytes(tempDir.resolve("Media.json")), "UTF-8")
    json should include("https://e.com/chart.png")
  }
}
