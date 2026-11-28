package org.llm4s.assistant

import org.llm4s.agent.{ AgentResultFixture, AgentStatus }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.types.{ DirectoryPath, Result, SessionId }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, Path }
import java.util.UUID

/**
 * Saved sessions round-trip by the name the user gave them on a case-sensitive file system, `/quit` and `/new` can
 * go on when the save fails, and the parsed command, not the raw input, decides whether the loop ends (#3277).
 */
class AssistantSessionsSpec extends AnyFlatSpec with Matchers {

  private val client: LLMClient = new LLMClient {
    def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      Left(org.llm4s.error.ValidationError("client", "not called"))
    def streamComplete(conversation: Conversation, options: CompletionOptions)(
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)
    def contextWindow: Int     = 4096
    def reserveCompletion: Int = 512
  }

  private def withContent(dir: Path): SessionState =
    SessionState(None, None, SessionId(UUID.randomUUID().toString), DirectoryPath(dir.toString))
      .withResult(
        AgentResultFixture(AgentStatus.Completed("hello"), Vector(UserMessage("hi"), AssistantMessage("hello")))
      )

  private def assistant(dir: Path) = new AssistantAgent(client, ToolRegistry.empty, dir.toString)

  "/save and /load" should "round-trip a title with capitals, as a case-sensitive file system needs" in {
    val dir   = Files.createTempDirectory("assistant-case")
    val agent = assistant(dir)
    agent.processInput("/save MyChat", withContent(dir)).map(_._2) shouldBe Right("Session saved as: MyChat")
    Files.exists(dir.resolve("MyChat.json")) shouldBe true
    agent.processInput("/LOAD MyChat", withContent(dir)).map(_._2.contains("restored")) shouldBe Right(true)
  }

  it should "replace an explicit save under the same title, and say the name a default save was given" in {
    val dir   = Files.createTempDirectory("assistant-resave")
    val agent = assistant(dir)
    agent.processInput("/save notes", withContent(dir)).map(_._2) shouldBe Right("Session saved as: notes")
    agent.processInput("/save notes", withContent(dir)).map(_._2) shouldBe Right("Session saved as: notes")
    Files.exists(dir.resolve("notes.json")) shouldBe true
    agent.processInput("/save", withContent(dir)).isRight shouldBe true
    val second = agent.processInput("/save", withContent(dir)).map(_._2).toOption.getOrElse(fail("not saved"))
    second should startWith("Session saved as: Saved Session-")
    agent.processInput(s"/load ${second.stripPrefix("Session saved as: ")}", withContent(dir)).isRight shouldBe true
  }

  "/quit" should "end the loop in any case and with words after it, and nothing else should" in {
    val agent = assistant(Files.createTempDirectory("assistant-quit"))
    Seq("/quit", "/QUIT", "/quit now", "  /Quit  ").foreach(i => withClue(i)(agent.isQuit(i) shouldBe true))
    Seq("quit", "/quitter", "/help", "", "what does /quit do?").foreach(i =>
      withClue(i)(agent.isQuit(i) shouldBe false)
    )
  }

  it should "offer to quit without saving when the save fails, and stay when declined" in {
    // a session directory that is a file cannot be created, so every save fails
    val notADir   = Files.createTempFile("assistant-unwritable", ".txt")
    val agent     = assistant(notADir)
    val state     = withContent(notADir)
    val discarded = Console.withIn(new java.io.StringReader("\ny\n"))(agent.processInput("/quit", state))
    discarded.map(_._2) shouldBe Right("Session not saved. Goodbye!")
    val kept = Console.withIn(new java.io.StringReader("\nn\n"))(agent.processInput("/quit", state))
    kept.isLeft shouldBe true
    val fresh = Console.withIn(new java.io.StringReader("\nyes\n"))(agent.processInput("/new", state))
    fresh.map(_._2) shouldBe Right("Previous session discarded. Started new session.")
    fresh.map(_._1.messages) shouldBe Right(Vector.empty)
  }
}
