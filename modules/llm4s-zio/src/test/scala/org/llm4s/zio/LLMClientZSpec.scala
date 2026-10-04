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
import zio.{ Chunk, Ref, ZIO }
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
      },
      test("streamComplete delivers the first chunk before the underlying call finishes") {
        val released = new java.util.concurrent.CountDownLatch(1)
        val client = new LLMClient {
          def complete(c: Conversation, o: CompletionOptions): Result[Completion] = Right(testCompletion)
          def streamComplete(
            c: Conversation,
            o: CompletionOptions,
            onChunk: StreamedChunk => Unit
          ): Result[Completion] = {
            onChunk(StreamedChunk(id = "c1", content = Some("a")))
            // Only returns normally if the consumer observed c1 while this call is still running.
            if (released.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
              onChunk(StreamedChunk(id = "c2", content = Some("b")))
              Right(testCompletion)
            } else Left(SimpleError("first chunk was not delivered incrementally"))
          }
          def getContextWindow(): Int     = 4096
          def getReserveCompletion(): Int = 256
        }
        LLMClientZ(client)
          .streamComplete(testConversation)
          .tap(c => ZIO.succeed(if (c.id == "c1") released.countDown()))
          .runCollect
          .map(emitted => assertTrue(emitted.map(_.id) == Chunk("c1", "c2")))
      },
      test("streamComplete emits chunks received before a mid-stream error, then fails") {
        val client = new LLMClient {
          def complete(c: Conversation, o: CompletionOptions): Result[Completion] = Right(testCompletion)
          def streamComplete(
            c: Conversation,
            o: CompletionOptions,
            onChunk: StreamedChunk => Unit
          ): Result[Completion] = {
            onChunk(StreamedChunk(id = "c1", content = Some("a")))
            onChunk(StreamedChunk(id = "c2", content = Some("b")))
            Left(SimpleError("mid-stream"))
          }
          def getContextWindow(): Int     = 4096
          def getReserveCompletion(): Int = 256
        }
        for {
          seen <- Ref.make(Chunk.empty[String])
          err <- LLMClientZ(client)
            .streamComplete(testConversation)
            .tap(c => seen.update(_ :+ c.id))
            .runDrain
            .flip
          ids <- seen.get
        } yield assertTrue(ids == Chunk("c1", "c2")) && assertTrue(err == SimpleError("mid-stream"))
      },
      test("streamComplete interrupts the underlying call when the consumer stops early") {
        val interrupted = new java.util.concurrent.CountDownLatch(1)
        val client = new LLMClient {
          def complete(c: Conversation, o: CompletionOptions): Result[Completion] = Right(testCompletion)
          def streamComplete(
            c: Conversation,
            o: CompletionOptions,
            onChunk: StreamedChunk => Unit
          ): Result[Completion] = {
            onChunk(StreamedChunk(id = "c1", content = Some("a")))
            // Park (not sleep) so an interrupt ends the wait without an exception.
            while (!Thread.currentThread().isInterrupted) java.util.concurrent.locks.LockSupport.parkNanos(10000000L)
            interrupted.countDown()
            Left(SimpleError("interrupted"))
          }
          def getContextWindow(): Int     = 4096
          def getReserveCompletion(): Int = 256
        }
        for {
          taken <- LLMClientZ(client).streamComplete(testConversation).take(1).runCollect
          ok    <- ZIO.attemptBlocking(interrupted.await(10, java.util.concurrent.TimeUnit.SECONDS)).orDie
        } yield assertTrue(taken.map(_.id) == Chunk("c1")) && assertTrue(ok)
      }
    )
}
