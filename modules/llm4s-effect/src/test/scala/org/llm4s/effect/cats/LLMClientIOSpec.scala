package org.llm4s.effect.cats

import cats.effect.IO
import cats.effect.unsafe.implicits.global
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
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LLMClientIOSpec extends AnyFlatSpec with Matchers {

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

  "LLMClientIO.complete" should "return the completion from the underlying client" in {
    val c = LLMClientIO[IO](mockClient(testCompletion)).complete(testConversation).unsafeRunSync()
    c.content shouldBe "hello"
    c.id shouldBe "test-id"
  }

  it should "raise LLMException when the underlying client returns Left" in {
    a[LLMException] should be thrownBy {
      LLMClientIO[IO](failingClient).complete(testConversation).unsafeRunSync()
    }
  }

  "LLMClientIO.streamComplete" should "emit all chunks from the underlying streaming call" in {
    val chunks = Seq(
      StreamedChunk(id = "c1", content = Some("hi")),
      StreamedChunk(id = "c2", content = Some(" world"))
    )
    val emitted = LLMClientIO[IO](mockClient(testCompletion, chunks))
      .streamComplete(testConversation)
      .compile
      .toList
      .unsafeRunSync()
    emitted.length shouldBe 2
    emitted.head.id shouldBe "c1"
  }

  it should "raise LLMException on stream error" in {
    a[LLMException] should be thrownBy {
      LLMClientIO[IO](failingClient)
        .streamComplete(testConversation)
        .compile
        .drain
        .unsafeRunSync()
    }
  }
}
