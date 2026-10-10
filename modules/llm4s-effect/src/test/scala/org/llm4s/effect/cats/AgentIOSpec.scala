package org.llm4s.effect.cats

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.llm4s.agent.AgentStatus
import org.llm4s.agent.testkit.Fixtures
import org.llm4s.error.SimpleError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ AssistantMessage, Completion, CompletionOptions, Conversation, StreamedChunk }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AgentIOSpec extends AnyFlatSpec with Matchers {

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

  "AgentIO.run" should "return an AgentResult with Completed status when the agent finishes" in {
    val state = AgentIO[IO](Fixtures.agentOf(successClient("4"))())
      .run("What is 2+2?")
      .unsafeRunSync()
    state.status shouldBe AgentStatus.Completed("4")
  }

  it should "include the query in the conversation" in {
    val state = AgentIO[IO](Fixtures.agentOf(successClient("answer"))())
      .run("my query")
      .unsafeRunSync()
    val messages = state.messages.map(_.content)
    messages should contain("my query")
  }

  it should "raise LLMException when the underlying LLM call fails" in {
    a[LLMException] should be thrownBy {
      AgentIO[IO](Fixtures.agentOf(failingClient)())
        .run("What is 2+2?")
        .unsafeRunSync()
    }
  }

  it should "wrap the original LLMError inside the LLMException" in {
    val result = AgentIO[IO](Fixtures.agentOf(failingClient)())
      .run("query")
      .attempt
      .unsafeRunSync()
    result.isLeft shouldBe true
    val ex = result.left.toOption.get
    ex shouldBe a[LLMException]
    Fixtures.causeOf(ex.asInstanceOf[LLMException].error) shouldBe SimpleError("agent-fail")
  }

  "AgentIO.continueConversation" should "return an AgentResult with Completed status on a follow-up turn" in {
    val agentIO = AgentIO[IO](Fixtures.agentOf(successClient("6"))())
    val state = for {
      s1 <- agentIO.run("What is 2+2?")
      s2 <- agentIO.continueConversation(s1, "And 3+3?")
    } yield s2
    state.unsafeRunSync().status shouldBe AgentStatus.Completed("6")
  }

  it should "append the follow-up question to the conversation history" in {
    val agentIO = AgentIO[IO](Fixtures.agentOf(successClient("6"))())
    val state = for {
      s1 <- agentIO.run("First question")
      s2 <- agentIO.continueConversation(s1, "Second question")
    } yield s2
    val messages = state.unsafeRunSync().messages.map(_.content)
    messages should contain("Second question")
  }

  it should "raise LLMException when the continuation LLM call fails" in {
    val calls = new java.util.concurrent.atomic.AtomicInteger(0)
    val client: LLMClient = new LLMClient {
      def complete(c: Conversation, o: CompletionOptions): Result[Completion] =
        if (calls.getAndIncrement() == 0) Right(completion("4")) else Left(SimpleError("agent-fail"))
      def streamComplete(c: Conversation, o: CompletionOptions, onChunk: StreamedChunk => Unit): Result[Completion] =
        complete(c, o)
      def getContextWindow(): Int     = 4096
      def getReserveCompletion(): Int = 256
    }
    val agentIO = AgentIO[IO](Fixtures.agentOf(client)())
    val s1      = agentIO.run("First").unsafeRunSync()
    a[LLMException] should be thrownBy {
      agentIO.continueConversation(s1, "Follow-up").unsafeRunSync()
    }
  }
}
