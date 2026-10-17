package org.llm4s.llmconnect.provider

import org.llm4s.error.UnsupportedContentPartError
import org.llm4s.llmconnect.model.*
import org.llm4s.media.{ MediaSource, MediaType }
import org.llm4s.model.ModelRegistryService
import org.scalatest.EitherValues
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.net.URI

/** Content parts in watsonx.ai chat requests: text and inline images in a user message, text in a tool result. */
class WatsonXContentPartsSpec extends AnyFunSuite with Matchers with EitherValues:
  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  import StubHttp.*
  import WatsonXTestConfig.config

  private def client = new WatsonXClient(config, httpClient = routed(_ => Right(iamToken()), _ => Right(chatReply)))

  private val bytes = IArray[Byte](1, 2, 3)
  private val image = ContentPart.image(MediaSource.Inline(bytes), MediaType.Jpeg).value

  private def body(messages: Message*) = client.createRequestBody(Conversation(messages), CompletionOptions())

  test("a one-text user message is sent as a string, as before") {
    body(UserMessage("hi")).value("messages")(0) shouldBe ujson.Obj("role" -> "user", "content" -> "hi")
  }

  test("text and inline images are sent as a content array, images as data URLs") {
    body(UserMessage(Vector(ContentPart.Text("what is"), image))).value("messages")(0) shouldBe ujson.Obj(
      "role" -> "user",
      "content" -> ujson.Arr(
        ujson.Obj("type" -> "text", "text"           -> "what is"),
        ujson.Obj("type" -> "image_url", "image_url" -> ujson.Obj("url" -> "data:image/jpeg;base64,AQID"))
      )
    )
  }

  test("an image by URL, a provider file and a document are refused, naming the part") {
    val url  = ContentPart.image(MediaSource.Url(URI.create("https://example.com/a.png")), MediaType.Png).value
    val file = ContentPart.image(MediaSource.ProviderFile("watsonx", "f"), MediaType.Png).value
    val pdf  = ContentPart.document(MediaSource.Inline(bytes), MediaType.Pdf).value
    Seq(url, file, pdf).foreach { part =>
      body(UserMessage(Vector(part))).left.value shouldBe UnsupportedContentPartError("watsonx", part.kind, "user")
    }
  }

  test("a tool result is its text parts, and media in one is refused") {
    val assistant = AssistantMessage(None, Seq(ToolCall("c1", "fetch", ujson.Obj())))
    body(UserMessage("go"), assistant, ToolMessage(Vector(ContentPart.Text("a"), ContentPart.Text("b")), "c1"))
      .value("messages")(2)("content") shouldBe ujson.Str("a\nb")
    body(UserMessage("go"), assistant, ToolMessage(Vector(image), "c1")).left.value shouldBe
      UnsupportedContentPartError("watsonx", image.kind, "tool")
  }

  test("complete refuses the conversation before any request") {
    val pdf = ContentPart.document(MediaSource.Inline(bytes), MediaType.Pdf).value
    client.complete(Conversation(Seq(UserMessage(Vector(pdf)))), CompletionOptions()).left.value shouldBe
      a[UnsupportedContentPartError]
  }
