package org.llm4s.zio

import org.llm4s.error.SimpleError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{
  AssistantMessage,
  Completion,
  CompletionOptions,
  Conversation,
  StreamedChunk,
  UserMessage
}
import org.llm4s.types.Result
import zio.test.*

object LLMClientZSpec extends ZIOSpecDefault {

  private val testCompletion = Completion(
    id = "test-id",
    created = 0L,
    content = "hello",
    model = "test-model",
    message = AssistantMessage(Some("hello"))
  )

  private val testConversation = Conversation(Seq(UserMessage("ping")))

  private def mockClient(completion: Completion, chunks: Seq[StreamedChunk] = Seq.empty): LLMClient =
    new LLMClient {
      def complete(c: Conversation, o: CompletionOptions): Result[Completion] = Right(completion)
      def streamComplete(c: Conversation, o: CompletionOptions, onChunk: StreamedChunk => Unit): Result[Completion] = {
        chunks.foreach(onChunk)
        Right(completion)
      }
      def getContextWindow(): Int     = 4096
      def getReserveCompletion(): Int = 256
    }

  private val failingClient: LLMClient = new LLMClient {
    def complete(c: Conversation, o: CompletionOptions): Result[Completion] =
      Left(SimpleError("boom"))
    def streamComplete(c: Conversation, o: CompletionOptions, onChunk: StreamedChunk => Unit): Result[Completion] =
      Left(SimpleError("boom"))
    def getContextWindow(): Int     = 4096
    def getReserveCompletion(): Int = 256
  }

  val spec =
    suite("LLMClientZ")(
      test("complete returns the completion value") {
        for {
          c <- LLMClientZ(mockClient(testCompletion)).complete(testConversation)
        } yield assertTrue(c.content == "hello") && assertTrue(c.id == "test-id")
      },
      test("complete propagates LLMError on failure") {
        LLMClientZ(failingClient)
          .complete(testConversation)
          .flip
          .map(err => assertTrue(err == SimpleError("boom")))
      },
      test("streamComplete emits all chunks") {
        val chunks = Seq(
          StreamedChunk(id = "c1", content = Some("hi")),
          StreamedChunk(id = "c2", content = Some(" there"))
        )
        for {
          emitted <- LLMClientZ(mockClient(testCompletion, chunks))
            .streamComplete(testConversation)
            .runCollect
        } yield assertTrue(emitted.length == 2) && assertTrue(emitted.head.id == "c1")
      },
      test("streamComplete propagates LLMError on failure") {
        LLMClientZ(failingClient)
          .streamComplete(testConversation)
          .runCollect
          .flip
          .map(err => assertTrue(err == SimpleError("boom")))
      }
    )
}
