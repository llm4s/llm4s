package org.llm4s.llmconnect.provider

import org.llm4s.error.UnsupportedContentPartError
import org.llm4s.llmconnect.config.OllamaConfig
import org.llm4s.llmconnect.model.*
import org.llm4s.media.{ MediaSource, MediaType }
import org.llm4s.model.ModelRegistryService
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI

class OllamaContentPartsSpec extends AnyFlatSpec with Matchers with EitherValues {
  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val client =
    new OllamaClient(
      OllamaConfig(model = "llava", baseUrl = "http://localhost:11434", contextWindow = 4096, reserveCompletion = 512)
    )

  private val bytes = IArray[Byte](1, 2, 3)
  private val image = ContentPart.image(MediaSource.Inline(bytes), MediaType.Png).value

  private def body(messages: Message*) =
    client.createRequestBody(Conversation(messages), CompletionOptions(), stream = false)

  "A user message" should "send a single text part exactly as before" in {
    body(UserMessage("hi")).value("messages")(0) shouldBe ujson.Obj("role" -> "user", "content" -> "hi")
  }

  it should "join its text parts into content and send inline images as raw base64" in {
    body(UserMessage(Vector(ContentPart.Text("what"), image, ContentPart.Text("is this?"))))
      .value("messages")(0) shouldBe
      ujson.Obj("role" -> "user", "content" -> "what\nis this?", "images" -> ujson.Arr("AQID"))
  }

  it should "refuse an image by URL, a provider file and a document, naming the part" in {
    val url  = ContentPart.image(MediaSource.Url(URI.create("https://example.com/a.png")), MediaType.Png).value
    val file = ContentPart.image(MediaSource.ProviderFile("ollama", "f"), MediaType.Png).value
    val pdf  = ContentPart.document(MediaSource.Inline(bytes), MediaType.Pdf).value
    Seq(url, file, pdf).foreach { part =>
      body(UserMessage(Vector(part))).left.value shouldBe UnsupportedContentPartError("ollama", part.kind, "user")
    }
  }

  "A tool result" should "join its text parts and refuse media" in {
    val call      = ToolCall("c1", "fetch", ujson.Obj())
    val assistant = AssistantMessage(None, Seq(call))
    body(UserMessage("go"), assistant, ToolMessage(Vector(ContentPart.Text("a"), ContentPart.Text("b")), "c1"))
      .value("messages")(2)("content") shouldBe ujson.Str("a\nb")
    body(UserMessage("go"), assistant, ToolMessage(Vector(image), "c1")).left.value shouldBe
      UnsupportedContentPartError("ollama", image.kind, "tool")
  }

  "complete" should "fail before sending anything" in {
    client
      .complete(
        Conversation(Seq(UserMessage(Vector(ContentPart.document(MediaSource.Inline(bytes), MediaType.Csv).value)))),
        CompletionOptions()
      )
      .left
      .value shouldBe a[UnsupportedContentPartError]
  }
}
