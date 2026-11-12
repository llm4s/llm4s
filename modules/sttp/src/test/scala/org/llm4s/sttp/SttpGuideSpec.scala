package org.llm4s.sttp

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.{ LLMConnect, LlmClientOptions }
import org.llm4s.llmconnect.config.OpenAICompatibleConfig
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, StreamedChunk, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.client4.testing.{ RecordingBackend, RichTestingRequest, SyncBackendStub }

import scala.collection.mutable.ListBuffer

/**
 * `docs/guide/http-transport.md`'s stub-backend example, run as written (DocSnippets only compiles it), plus the
 * streamed call the guide mentions: a provider client given an [[SttpLlm4sHttpClient]] over sttp's stub backend sends
 * its `complete` and `streamComplete` requests through it, with no network. Change the guide and this spec together.
 */
class SttpGuideSpec extends AnyFlatSpec with Matchers {

  // The events the streamed call is answered with; declared first, as every test closure sees them.
  private val events =
    Seq("A JVM", " language.").zipWithIndex
      .map { (text, i) =>
        val finish = if i == 1 then "\"stop\"" else "null"
        s"""data: {"id":"c-1","created":0,"model":"my-model","choices":[{"index":0,"delta":{"content":${ujson
            .Str(text)
            .render()}},"finish_reason":$finish}]}"""
      }
      .appended("data: [DONE]")
      .mkString("", "\n\n", "\n\n")

  // ---- the guide's example, as written

  private val reply =
    """{"id":"c-1","object":"chat.completion","created":1700000000,"model":"my-model",
      |"choices":[{"index":0,"message":{"role":"assistant","content":"A JVM language."},"finish_reason":"stop"}],
      |"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}""".stripMargin

  "the chat call" should "send the user's question in the request body" in {
    val backend = RecordingBackend(
      SyncBackendStub
        .whenRequestMatches(_.uri.path.endsWith(Seq("chat", "completions")))
        .thenRespondAdjust(reply)
    )
    given ModelRegistryService = Llm4sConfig.modelRegistryService().fold(e => fail(e.message), identity)

    val answer = for {
      client <- LLMConnect.getClient(
        OpenAICompatibleConfig("my-model", "http://llm.test/v1"),
        LlmClientOptions.default.withHttpClient(new SttpLlm4sHttpClient(backend))
      )
      completion <- client.complete(Conversation(Seq(UserMessage("What is Scala?"))), CompletionOptions())
    } yield completion.content

    answer shouldBe Right("A JVM language.")
    backend.allInteractions should have size 1
    val (request, _) = backend.allInteractions.head
    request.uri.toString shouldBe "http://llm.test/v1/chat/completions"
    val body = ujson.read(request.forceBodyAsString)
    body("model").str shouldBe "my-model"
    body("messages")(0)("content").str shouldBe "What is Scala?"
  }

  // ---- the streamed call the guide mentions

  "a streamed chat call" should "send its request through the stub and read the events it answers" in {
    val backend = RecordingBackend(
      SyncBackendStub
        .whenRequestMatches(_.uri.path.endsWith(Seq("chat", "completions")))
        .thenRespondAdjust(events)
    )
    given ModelRegistryService = Llm4sConfig.modelRegistryService().fold(e => fail(e.message), identity)
    val chunks                 = ListBuffer.empty[StreamedChunk]

    val streamed = for {
      client <- LLMConnect.getClient(
        OpenAICompatibleConfig("my-model", "http://llm.test/v1"),
        LlmClientOptions.default.withHttpClient(new SttpLlm4sHttpClient(backend))
      )
      completion <- client.streamComplete(
        Conversation(Seq(UserMessage("What is Scala?"))),
        CompletionOptions(),
        chunk => chunks += chunk
      )
    } yield completion.content

    streamed shouldBe Right("A JVM language.")
    chunks.flatMap(_.content).mkString shouldBe "A JVM language."
    backend.allInteractions should have size 1
    val body = ujson.read(backend.allInteractions.head._1.forceBodyAsString)
    body("stream").bool shouldBe true
    body("messages")(0)("content").str shouldBe "What is Scala?"
  }
}
