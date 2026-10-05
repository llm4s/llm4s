package org.llm4s.assistant

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.types.DirectoryPath

import java.nio.file.Files

class ConsoleInterfaceMethodsSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val tempDir = Files.createTempDirectory("console-test-sessions")

  override def afterAll(): Unit = {
    // Close the stream to release directory handle
    val stream = Files.list(tempDir)
    try stream.forEach(Files.deleteIfExists(_))
    finally stream.close()
    Files.deleteIfExists(tempDir)
  }

  private val emptyTools = ToolRegistry.empty

  private def makeConsole(tools: ToolRegistry = emptyTools): ConsoleInterface = {
    val sessionManager = new SessionManager(DirectoryPath(tempDir.toString))
    new ConsoleInterface(tools, sessionManager)
  }

  // ========== formatSessionList ==========

  "ConsoleInterface.formatSessionList" should "show message when no sessions" in {
    val console = makeConsole()
    val result  = console.formatSessionList(Seq.empty)
    result shouldBe "No saved sessions found."
  }

  it should "list sessions when present" in {
    val console  = makeConsole()
    val sessions = Seq("Chat about Scala", "Code Review Notes")
    val result   = console.formatSessionList(sessions)

    result should include("Chat about Scala")
    result should include("Code Review Notes")
    result should include("Recent sessions")
  }

  it should "list a single session" in {
    val console = makeConsole()
    val result  = console.formatSessionList(Seq("Only Session"))
    result should include("Only Session")
  }

  // ========== showHelp ==========

  "ConsoleInterface.showHelp" should "return non-empty help text" in {
    val console = makeConsole()
    val help    = console.showHelp()

    help should not be empty
    help should include("/help")
    help should include("/quit")
    help should include("/save")
    help should include("/load")
    help should include("/sessions")
    help should include("/new")
  }

  it should "show Available Tools section even with no tools" in {
    val console = makeConsole()
    val help    = console.showHelp()
    help should include("Available Commands")
  }

  it should "show tips section" in {
    val console = makeConsole()
    val help    = console.showHelp()
    help should include("Tips")
  }

  // ========== displayMessage ==========

  "ConsoleInterface.displayMessage" should "succeed for Info messages" in {
    val console = makeConsole()
    val result  = console.displayMessage("test info", MessageType.Info)
    result shouldBe Right(())
  }

  it should "succeed for Success messages" in {
    val console = makeConsole()
    val result  = console.displayMessage("success!", MessageType.Success)
    result shouldBe Right(())
  }

  it should "succeed for Warning messages" in {
    val console = makeConsole()
    val result  = console.displayMessage("warning!", MessageType.Warning)
    result shouldBe Right(())
  }

  it should "succeed for Error messages" in {
    val console = makeConsole()
    val result  = console.displayMessage("error!", MessageType.Error)
    result shouldBe Right(())
  }

  it should "succeed for AssistantResponse messages" in {
    val console = makeConsole()
    val result  = console.displayMessage("response text", MessageType.AssistantResponse)
    result shouldBe Right(())
  }

  // ========== displayError ==========

  "ConsoleInterface.displayError" should "succeed" in {
    val console = makeConsole()
    val result  = console.displayError("something went wrong")
    result shouldBe Right(())
  }

  // ========== displaySuccess ==========

  "ConsoleInterface.displaySuccess" should "succeed" in {
    val console = makeConsole()
    val result  = console.displaySuccess("operation completed")
    result shouldBe Right(())
  }

  // ========== displayLLMError ==========

  "ConsoleInterface.displayLLMError" should "format and display LLM errors" in {
    val console = makeConsole()
    val error   = org.llm4s.error.SimpleError("Model not found")
    val result  = console.displayLLMError(error)
    result shouldBe Right(())
  }

  // ========== showWelcome ==========

  "ConsoleInterface.showWelcome" should "succeed without errors" in {
    val console = makeConsole()
    val result  = console.showWelcome()
    result shouldBe Right(())
  }
}
