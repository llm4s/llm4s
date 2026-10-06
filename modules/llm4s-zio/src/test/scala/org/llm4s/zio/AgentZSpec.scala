package org.llm4s.zio

import org.llm4s.agent.AgentStatus
import org.llm4s.error.SimpleError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ AssistantMessage, Completion, CompletionOptions, Conversation, StreamedChunk }
import org.llm4s.types.Result
import zio.ZIO
import zio.test.*

object AgentZSpec extends ZIOSpecDefault {

  private def completion(text: String) = Completion(
    id = "test-id",
    created = 0L,
    content = text,
    model = "test-model",
    message = AssistantMessage(contentOpt = Some(text))
  )

  private def successClient(text: String): LLMClient = new LLMClient {
    def complete(c: Conversation, o: CompletionOptions): Result[Completion] =
      Right(completion(text))
    def streamComplete(c: Conversation, o: CompletionOptions, onChunk: StreamedChunk => Unit): Result[Completion] =
      Right(completion(text))
    def getContextWindow(): Int     = 4096
    def getReserveCompletion(): Int = 256
  }

  private val failingClient: LLMClient = new LLMClient {
    def complete(c: Conversation, o: CompletionOptions): Result[Completion] =
      Left(SimpleError("agent-fail"))
    def streamComplete(c: Conversation, o: CompletionOptions, onChunk: StreamedChunk => Unit): Result[Completion] =
      Left(SimpleError("agent-fail"))
    def getContextWindow(): Int     = 4096
    def getReserveCompletion(): Int = 256
  }

  val spec = suite("AgentZ")(
    test("run returns an AgentResult with Completed status when the agent finishes") {
      for {
        state <- AgentZ(Fixtures.agentOf(successClient("4"))()).run("What is 2+2?")
      } yield assertTrue(state.status == AgentStatus.Completed("4"))
    },
    test("run includes the query in the conversation history") {
      for {
        state <- AgentZ(Fixtures.agentOf(successClient("answer"))()).run("my query")
      } yield {
        val messages = state.messages.map(_.content)
        assertTrue(messages.contains("my query"))
      }
    },
    test("run propagates LLMError on failure") {
      AgentZ(Fixtures.agentOf(failingClient)())
        .run("What is 2+2?")
        .flip
        .map(err => assertTrue(Fixtures.causeOf(err) == SimpleError("agent-fail")))
    },
    test("continueConversation returns an AgentResult with Completed status") {
      for {
        s1 <- AgentZ(Fixtures.agentOf(successClient("4"))()).run("What is 2+2?")
        s2 <- AgentZ(Fixtures.agentOf(successClient("6"))()).continueConversation(s1, "And 3+3?")
      } yield assertTrue(s2.status == AgentStatus.Completed("6"))
    },
    test("continueConversation appends follow-up to conversation history") {
      for {
        agent <- ZIO.succeed(AgentZ(Fixtures.agentOf(successClient("6"))()))
        s1    <- agent.run("First question")
        s2    <- agent.continueConversation(s1, "Second question")
      } yield {
        val messages = s2.messages.map(_.content)
        assertTrue(messages.contains("Second question"))
      }
    },
    test("continueConversation propagates LLMError on failure") {
      val calls = new java.util.concurrent.atomic.AtomicInteger(0)
      val client: LLMClient = new LLMClient {
        def complete(c: Conversation, o: CompletionOptions): Result[Completion] =
          if (calls.getAndIncrement() == 0) Right(completion("4")) else Left(SimpleError("agent-fail"))
        def streamComplete(c: Conversation, o: CompletionOptions, onChunk: StreamedChunk => Unit): Result[Completion] =
          complete(c, o)
        def getContextWindow(): Int     = 4096
        def getReserveCompletion(): Int = 256
      }
      val agent = AgentZ(Fixtures.agentOf(client)())
      for {
        s1  <- agent.run("First")
        err <- agent.continueConversation(s1, "Follow-up").flip
      } yield assertTrue(Fixtures.causeOf(err) == SimpleError("agent-fail"))
    }
  )
}
