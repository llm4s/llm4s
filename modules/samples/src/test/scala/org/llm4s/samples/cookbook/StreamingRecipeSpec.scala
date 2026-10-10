package org.llm4s.samples.cookbook

import org.llm4s.error.ServiceError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Runs the streaming recipe against the scripted client, and against a client that streams more than text. */
class StreamingRecipeSpec extends AnyFlatSpec with Matchers with EitherValues {

  "StreamingRecipe.stream" should "hand each piece to the caller as it arrives, and the pieces make the whole reply" in {
    val printed = Vector.newBuilder[String]

    val streamed = StreamingRecipe.stream(StreamingRecipe.script, "Why stream?", printed += _).value

    streamed.pieces.size should be > 10
    printed.result() shouldBe streamed.pieces
    streamed.pieces.mkString shouldBe streamed.text
    streamed.text should startWith("Streaming sends the reply in pieces")
  }

  it should "call streamComplete, with the question and a token limit" in {
    val client = StreamingRecipe.script

    StreamingRecipe.stream(client, "Why stream?", _ => ()).value

    val (conversation, options) = client.calls.head
    conversation.messages.last.content shouldBe "Why stream?"
    options.maxTokens shouldBe Some(200)
  }

  it should "print only text: not empty pieces, tool-call chunks, finish reasons or reasoning" in {
    val client = new LLMClient {
      def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
        Left(ServiceError(500, "test", "the recipe should stream"))
      def streamComplete(conversation: Conversation, options: CompletionOptions, onChunk: StreamedChunk => Unit) = {
        onChunk(StreamedChunk("s", None, thinkingDelta = Some("thinking...")))
        onChunk(StreamedChunk("s", Some("Hello")))
        onChunk(StreamedChunk("s", Some("")))
        onChunk(StreamedChunk("s", Some(" world")))
        onChunk(StreamedChunk("s", None, finishReason = Some("stop")))
        Right(
          Completion(
            id = "s",
            created = 0L,
            content = "Hello world",
            model = "m",
            message = AssistantMessage("Hello world")
          )
        )
      }
      def getContextWindow(): Int     = 1000
      def getReserveCompletion(): Int = 100
    }
    val printed = Vector.newBuilder[String]

    StreamingRecipe.stream(client, "Hi", printed += _).value.text shouldBe "Hello world"
    printed.result() shouldBe Vector("Hello", " world")
  }

  it should "return the client's error" in {
    val failing = new ScriptedClient((_, _) => Left(ServiceError(503, "scripted", "unavailable")))

    StreamingRecipe.stream(failing, "Hi", _ => ()).left.value shouldBe a[ServiceError]
  }
}
