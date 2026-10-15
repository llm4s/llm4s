package org.llm4s.llmconnect.provider

import com.anthropic.core.ObjectMappers
import com.anthropic.models.messages.MessageCreateParams
import org.llm4s.error.UnsupportedContentPartError
import org.llm4s.llmconnect.config.AnthropicConfig
import org.llm4s.llmconnect.model.*
import org.llm4s.media.{ MediaSource, MediaType }
import org.llm4s.model.ModelRegistryService
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.nio.charset.StandardCharsets

/** How the Anthropic client sends content parts, and the typed error for those it cannot. */
class AnthropicContentPartsSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val client = new AnthropicClient(
    AnthropicConfig(
      apiKey = "test-key",
      model = "claude-3-5-sonnet-latest",
      baseUrl = "http://localhost:1",
      contextWindow = 200000,
      reserveCompletion = 4096
    )
  )

  private val bytes = IArray[Byte](1, 2, 3)
  private val url   = MediaSource.Url(URI.create("https://example.com/a"))

  private def image(source: MediaSource, mt: org.llm4s.media.ImageMediaType = MediaType.Png) =
    ContentPart.image(source, mt).value
  private def doc(source: MediaSource, mt: org.llm4s.media.DocumentMediaType = MediaType.Pdf) =
    ContentPart.document(source, mt).value

  private def body(messages: Message*): ujson.Value = {
    val builder = MessageCreateParams.builder().model("claude-3-5-sonnet-latest").maxTokens(1024)
    client.addMessagesToParams(Conversation(messages), builder, CompletionOptions())
    ujson.read(ObjectMappers.jsonMapper().writeValueAsString(builder.build()._body()))
  }

  private def userContent(messages: Message*): Seq[ujson.Value] =
    body(messages*)("messages").arr.head("content").arr.toSeq

  "A user message" should "send a one-text message as a plain string, as before" in {
    body(UserMessage("hi"))("messages").arr.head("content") shouldBe ujson.Str("hi")
  }

  it should "refuse, before sending, a plain-text document that is not valid UTF-8" in {
    val malformed = doc(MediaSource.Inline(IArray[Byte](0x61, 0xc3.toByte, 0x28)), MediaType.PlainText)
    client
      .complete(Conversation(Seq(UserMessage(Vector(ContentPart.Text("read"), malformed)))), CompletionOptions())
      .left
      .value
      .message should include("is not valid UTF-8 text")
    val valid = doc(MediaSource.Inline(IArray.from("café".getBytes("UTF-8"))), MediaType.PlainText)
    AnthropicClient.checkParts("anthropic", Seq(UserMessage(Vector(ContentPart.Text("read"), valid)))) shouldBe Right(
      ()
    )
  }

  it should "send no empty text block for a blank text part" in {
    val content = userContent(UserMessage(Vector(ContentPart.Text(""), image(url), ContentPart.Text("caption"))))
    content.map(_("type").str) shouldBe Seq("image", "text")
  }

  it should "send images by URL and inline, with their media type" in {
    val blocks = userContent(
      UserMessage(Vector(ContentPart.Text("look"), image(url), image(MediaSource.Inline(bytes), MediaType.WebP)))
    )
    blocks.head shouldBe ujson.Obj("type" -> "text", "text" -> "look")
    blocks(1)("type").str shouldBe "image"
    blocks(1)("source") shouldBe ujson.Obj("type" -> "url", "url" -> "https://example.com/a")
    blocks(2)("source") shouldBe ujson.Obj("type" -> "base64", "media_type" -> "image/webp", "data" -> "AQID")
  }

  it should "send PDFs by URL and inline, and a plain-text document as its text" in {
    val text = MediaSource.Inline(IArray.from("hello doc".getBytes(StandardCharsets.UTF_8)))
    val blocks =
      userContent(UserMessage(Vector(doc(url), doc(MediaSource.Inline(bytes)), doc(text, MediaType.PlainText))))
    blocks.map(_("type").str) shouldBe Seq("document", "document", "document")
    blocks.head("source") shouldBe ujson.Obj("type" -> "url", "url" -> "https://example.com/a")
    blocks(1)("source") shouldBe ujson.Obj("type" -> "base64", "media_type" -> "application/pdf", "data" -> "AQID")
    blocks(2)("source") shouldBe ujson.Obj("type" -> "text", "media_type" -> "text/plain", "data" -> "hello doc")
  }

  "A tool result" should "carry its media blocks inside tool_result" in {
    val call = ToolCall("c1", "fetch", ujson.Obj())
    val messages = body(
      UserMessage("go"),
      AssistantMessage(None, Seq(call)),
      ToolMessage(Vector(ContentPart.Text("here"), image(MediaSource.Inline(bytes)), doc(url)), "c1")
    )("messages").arr
    val result = messages.last("content").arr.head
    result("type").str shouldBe "tool_result"
    result("content").arr.map(_("type").str) shouldBe Seq("text", "image", "document")
  }

  it should "keep a one-text result as a string" in {
    val call = ToolCall("c1", "fetch", ujson.Obj())
    val result =
      body(UserMessage("go"), AssistantMessage(None, Seq(call)), ToolMessage("done", "c1"))("messages").arr.last
    result("content").arr.head("content") shouldBe ujson.Str("done")
  }

  it should "go as text and then its media when there is no call to answer" in {
    val blocks =
      body(ToolMessage(Vector(ContentPart.Text("orphan"), image(url)), "c9"))("messages").arr.head("content").arr
    blocks.head("text").str shouldBe "[Tool result for c9]:"
    blocks(1)("text").str shouldBe "orphan"
    blocks(2)("type").str shouldBe "image"
  }

  it should "keep an unanswered result's parts in their order, each caption beside its image" in {
    val parts =
      Vector(ContentPart.Text("before"), image(url), ContentPart.Text("after"), image(MediaSource.Inline(bytes)))
    val blocks = body(ToolMessage(parts, "c9"))("messages").arr.head("content").arr
    blocks.map(_("type").str).toSeq shouldBe Seq("text", "text", "image", "text", "image")
    blocks.map(b => b.obj.get("text").map(_.str)).flatten.toSeq shouldBe Seq("[Tool result for c9]:", "before", "after")
    body(ToolMessage("plain", "c9"))("messages").arr.head("content").arr.head("text").str shouldBe
      "[Tool result for c9]: plain"
  }

  it should "take up to 600 images for a model with a context window over 200k" in {
    val many = UserMessage(Vector(ContentPart.Text("x")) ++ Vector.fill(150)(image(url)))
    AnthropicClient.checkParts("anthropic", Seq(many), contextWindow = 1000000) shouldBe Right(())
    AnthropicClient.checkParts("anthropic", Seq(many), contextWindow = 200000).isLeft shouldBe true
    AnthropicClient.maxImages(1000000) shouldBe 600
    AnthropicClient.maxImages(200000) shouldBe 100
  }

  it should "refuse, before sending, a request with more than 100 images across its messages" in {
    val fifty = UserMessage(Vector(ContentPart.Text("x")) ++ Vector.fill(50)(image(url)))
    AnthropicClient.checkParts("anthropic", Seq(fifty, AssistantMessage("ok"), fifty)) shouldBe Right(())
    val more = UserMessage(Vector(ContentPart.Text("x"), image(url)))
    client
      .complete(
        Conversation(Seq(fifty, AssistantMessage("ok"), fifty, AssistantMessage("ok"), more)),
        CompletionOptions()
      )
      .left
      .value
      .message should include("at most 100 images")
  }

  // the direct API takes an image of up to 10 MB base64-encoded, more than MaxInlineBytes encodes to: no
  // per-image limit applies beyond the part's own
  it should "accept any inline image llm4s accepts, up to MaxInlineBytes" in {
    val largest = image(MediaSource.Inline(IArray.fill[Byte](ContentPart.MaxInlineBytes)(1)))
    AnthropicClient.checkParts("anthropic", Seq(UserMessage(Vector(ContentPart.Text("x"), largest)))) shouldBe Right(())
  }

  "complete" should "refuse, before sending, each part Anthropic cannot take" in {
    val refused: Seq[(Message, String)] = Seq(
      UserMessage(Vector(image(MediaSource.Inline(bytes), MediaType.Bmp))) -> "image (inline, image/bmp)",
      UserMessage(
        Vector(image(MediaSource.ProviderFile("anthropic", "file_1")))
      )                                                  -> "image (provider file anthropic, image/png)",
      UserMessage(Vector(doc(url, MediaType.PlainText))) -> "document (url, text/plain)",
      UserMessage(Vector(image(MediaSource.Url(URI.create("gs://b/a.png")))))     -> "image (url, image/png)",
      UserMessage(Vector(doc(MediaSource.Url(URI.create("s3://b/r.pdf")))))       -> "document (url, application/pdf)",
      UserMessage(Vector(doc(MediaSource.Inline(bytes), MediaType.Csv)))          -> "document (inline, text/csv)",
      ToolMessage(Vector(image(MediaSource.Inline(bytes), MediaType.Tiff)), "c1") -> "image (inline, image/tiff)"
    )
    refused.foreach { case (message, kind) =>
      val conversation = message match {
        case t: ToolMessage =>
          Conversation(Seq(UserMessage("go"), AssistantMessage(None, Seq(ToolCall("c1", "f", ujson.Obj()))), t))
        case other => Conversation(Seq(other))
      }
      client.complete(conversation, CompletionOptions()).left.value match {
        case UnsupportedContentPartError(_, "anthropic", `kind`, role) => role shouldBe message.role.name
        case other => fail(s"expected UnsupportedContentPartError for $kind, got $other")
      }
      client
        .streamComplete(conversation, CompletionOptions(), _ => ())
        .left
        .value shouldBe a[UnsupportedContentPartError]
    }
  }

  "complete" should "refuse, before sending, a request over the Messages API's 32 MB as rendered" in {
    // five images at MaxInlineBytes are each allowed, and together about 35 MB base64-encoded
    val atLimit = image(MediaSource.Inline(IArray.fill[Byte](ContentPart.MaxInlineBytes)(1)))
    val message = UserMessage(Vector(ContentPart.Text("x")) ++ Vector.fill(5)(atLimit))
    AnthropicClient.checkParts("anthropic", Seq(message)) shouldBe Right(())
    client.complete(Conversation(Seq(message)), CompletionOptions()).left.value.message should
      include(s"a request of at most ${AnthropicClient.MaxRequestBytes} bytes")
    client.streamComplete(Conversation(Seq(message)), CompletionOptions(), _ => ()).left.value.message should
      include("a request of at most")
  }
}
