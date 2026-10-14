package org.llm4s.llmconnect.provider

import org.llm4s.error.UnsupportedContentPartError
import org.llm4s.llmconnect.config.OpenAICompatibleConfig
import org.llm4s.llmconnect.model._
import org.llm4s.media.{ MediaSource, MediaType }
import org.llm4s.model.ModelRegistryService
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.{ ServerSocket, URI }

/** How the shared client and each dialect map a message's content parts (#1913). */
class OpenAICompatibleContentPartsSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val bytes    = IArray[Byte](1, 2, 3)
  private val imageUrl = ContentPart.image(MediaSource.Url(URI.create("https://x.example/a.png")), MediaType.Png).value
  private val imageInline = ContentPart.image(MediaSource.Inline(bytes), MediaType.Jpeg).value
  private val pdfUrl = ContentPart.document(MediaSource.Url(URI.create("https://x.example/r.pdf")), MediaType.Pdf).value
  private val pdfInline = ContentPart.document(MediaSource.Inline(bytes), MediaType.Pdf).value

  /** A base URL nothing listens on: a request that were sent would fail in transport, not as a part error. */
  private def deadBaseUrl: String = {
    val socket = new ServerSocket(0)
    val port   = socket.getLocalPort
    socket.close()
    s"http://localhost:$port"
  }

  private val client =
    OpenAICompatibleClient(OpenAICompatibleConfig(model = "m", baseUrl = deadBaseUrl, apiKey = None)).value

  private def messagesOf(conversation: Conversation): ujson.Value =
    client.createRequestBody(conversation, CompletionOptions())("messages")

  "The standard dialect" should "send a text-only user message as its text, as before parts" in {
    messagesOf(Conversation(Seq(UserMessage("hello"))))(0) shouldBe ujson.Obj("role" -> "user", "content" -> "hello")
    messagesOf(Conversation(Seq(UserMessage(Vector(ContentPart.Text("a"), ContentPart.Text("b"))))))(0) shouldBe
      ujson.Obj("role" -> "user", "content" -> "a\nb")
  }

  it should "send a user message with images as a content array of image_url parts" in {
    val sent = messagesOf(Conversation(Seq(UserMessage(Vector(ContentPart.Text("what?"), imageUrl, imageInline)))))
    sent(0) shouldBe ujson.Obj(
      "role" -> "user",
      "content" -> ujson.Arr(
        ujson.Obj("type" -> "text", "text"           -> "what?"),
        ujson.Obj("type" -> "image_url", "image_url" -> ujson.Obj("url" -> "https://x.example/a.png")),
        ujson.Obj("type" -> "image_url", "image_url" -> ujson.Obj("url" -> "data:image/jpeg;base64,AQID"))
      )
    )
  }

  it should "send a tool result's text parts as its text" in {
    val conversation = Conversation(
      Seq(
        UserMessage("go"),
        AssistantMessage(contentOpt = None, toolCalls = Seq(ToolCall("c1", "t", ujson.Obj()))),
        ToolMessage(Vector(ContentPart.Text("a"), ContentPart.Text("b")), "c1")
      )
    )
    messagesOf(conversation)(2)("content") shouldBe ujson.Str("a\nb")
  }

  it should "refuse a document, a provider file and media in a tool result, before sending anything" in {
    def refused(conversation: Conversation, part: ContentPart, role: String) =
      client.complete(conversation, CompletionOptions()).left.value shouldBe
        UnsupportedContentPartError("openai-compatible", part.kind, role)
    refused(Conversation(Seq(UserMessage(Vector(ContentPart.Text("x"), pdfUrl)))), pdfUrl, "user")
    val file = ContentPart.image(MediaSource.ProviderFile("openai-compatible", "f-1"), MediaType.Png).value
    refused(Conversation(Seq(UserMessage(Vector(file)))), file, "user")
    val inTool = Conversation(
      Seq(
        UserMessage("go"),
        AssistantMessage(contentOpt = None, toolCalls = Seq(ToolCall("c1", "t", ujson.Obj()))),
        ToolMessage(Vector(imageUrl), "c1")
      )
    )
    refused(inTool, imageUrl, "tool")
  }

  "OpenRouter" should "add a PDF, by URL or inline, as a file part" in {
    OpenRouterDialect.encodeUserPart(pdfUrl) shouldBe ujson.Obj(
      "type" -> "file",
      "file" -> ujson.Obj("filename" -> "document.pdf", "file_data" -> "https://x.example/r.pdf")
    )
    OpenRouterDialect.encodeUserPart(pdfInline)("file")("file_data") shouldBe ujson.Str(
      "data:application/pdf;base64,AQID"
    )
    OpenRouterDialect.encodeUserPart(imageUrl)("type") shouldBe ujson.Str("image_url")
  }

  "Mistral" should "add a PDF by URL as a document_url part, and nothing inline" in {
    MistralDialect.encodeUserPart(pdfUrl) shouldBe
      ujson.Obj("type" -> "document_url", "document_url" -> "https://x.example/r.pdf")
    MistralDialect.encodeUserPart.isDefinedAt(pdfInline) shouldBe false
  }

  "DeepSeek" should "take text only" in {
    DeepSeekDialect.encodeUserPart(ContentPart.Text("t")) shouldBe ujson.Obj("type" -> "text", "text" -> "t")
    DeepSeekDialect.encodeUserPart.isDefinedAt(imageUrl) shouldBe false
  }

  "Z.ai and Cohere" should "take the standard parts" in {
    ZaiDialect.encodeUserPart.isDefinedAt(imageInline) shouldBe true
    CohereDialect.encodeUserPart.isDefinedAt(pdfUrl) shouldBe false
  }
}
