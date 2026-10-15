package org.llm4s.llmconnect.provider

import org.llm4s.error.UnsupportedContentPartError
import org.llm4s.llmconnect.config.{ ContextWindowResolver, OpenAIConfig }
import org.llm4s.llmconnect.model._
import org.llm4s.media.{ MediaSource, MediaType }
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

/** The request JSON `OpenAIClient` sends for each content part, through the real SDK transport (#1913). */
final class OpenAIClientContentPartsSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given mrs: ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  private val bytes = IArray[Byte](1, 2, 3)
  private val imageUrl =
    ContentPart.image(MediaSource.Url(URI.create("https://x.example/a.png")), MediaType.Png).value
  private val imageInline = ContentPart.image(MediaSource.Inline(bytes), MediaType.Jpeg).value
  private val pdfInline   = ContentPart.document(MediaSource.Inline(bytes), MediaType.Pdf).value
  private val pdfFile     = ContentPart.document(MediaSource.ProviderFile("openai", "file-9"), MediaType.Pdf).value

  /** The `messages` the client sent for `conversation`, or the error it returned without sending. */
  private def sent(conversation: Conversation): Either[org.llm4s.error.LLMError, ujson.Value] = {
    val body = new AtomicReference[Option[ujson.Value]](None)
    var out  = Option.empty[Either[org.llm4s.error.LLMError, ujson.Value]]
    LocalProviderTestServer.withServer("/") { exchange =>
      body.set(Some(ujson.read(new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8))))
      LocalProviderTestServer.sendJsonResponse(exchange, 200, LocalProviderTestServer.openAICompletion("ok", "gpt-4o"))
    } { baseUrl =>
      val client = OpenAIClient(OpenAIConfig.fromValues("gpt-4o", "sk-test", None, s"$baseUrl/v1").value).value
      val result = client.complete(conversation, CompletionOptions())
      client.close()
      out = Some(result.map(_ => body.get.getOrElse(fail("no request"))("messages")))
      if (result.isLeft) body.get shouldBe None
    }
    out.get
  }

  private def toolResult(result: ToolMessage) =
    Conversation(
      Seq(
        UserMessage("go"),
        AssistantMessage(contentOpt = None, toolCalls = Seq(ToolCall("c1", "t", ujson.Obj()))),
        result
      )
    )

  "OpenAIClient" should "send a one-text user message as a string, as before parts" in {
    sent(Conversation(Seq(UserMessage("hello")))).value(0)("content") shouldBe ujson.Str("hello")
  }

  it should "send images and PDFs as content parts" in {
    val messages =
      sent(Conversation(Seq(UserMessage(Vector(ContentPart.Text("what?"), imageUrl, imageInline, pdfInline, pdfFile)))))
    messages.value(0)("content") shouldBe ujson.Arr(
      ujson.Obj("type" -> "text", "text"           -> "what?"),
      ujson.Obj("type" -> "image_url", "image_url" -> ujson.Obj("url" -> "https://x.example/a.png")),
      ujson.Obj("type" -> "image_url", "image_url" -> ujson.Obj("url" -> "data:image/jpeg;base64,AQID")),
      ujson.Obj(
        "type" -> "file",
        "file" -> ujson.Obj("filename" -> "document.pdf", "file_data" -> "data:application/pdf;base64,AQID")
      ),
      ujson.Obj("type" -> "file", "file" -> ujson.Obj("file_id" -> "file-9"))
    )
  }

  it should "send a tool result's text parts as an array of text parts" in {
    val messages = sent(toolResult(ToolMessage(Vector(ContentPart.Text("a"), ContentPart.Text("b")), "c1"))).value
    messages(2)("content") shouldBe
      ujson.Arr(ujson.Obj("type" -> "text", "text" -> "a"), ujson.Obj("type" -> "text", "text" -> "b"))
    sent(toolResult(ToolMessage("r", "c1"))).value(2)("content") shouldBe ujson.Str("r")
  }

  it should "refuse, without sending, a PDF by URL, another provider's file, a text document and media in a tool result" in {
    val pdfUrl  = ContentPart.document(MediaSource.Url(URI.create("https://x.example/r.pdf")), MediaType.Pdf).value
    val foreign = ContentPart.document(MediaSource.ProviderFile("gemini", "files/1"), MediaType.Pdf).value
    val text    = ContentPart.document(MediaSource.Inline(bytes), MediaType.PlainText).value
    Seq(pdfUrl, foreign, text).foreach { part =>
      sent(Conversation(Seq(UserMessage(Vector(part))))).left.value shouldBe
        UnsupportedContentPartError("openai", part.kind, "user")
    }
    sent(toolResult(ToolMessage(Vector(imageInline), "c1"))).left.value shouldBe
      UnsupportedContentPartError("openai", imageInline.kind, "tool")
  }

  it should "refuse an image URL it cannot fetch, a gs:// or s3:// one, before sending" in {
    Seq("gs://bucket/a.png", "s3://bucket/a.png").foreach { u =>
      val part = ContentPart.image(MediaSource.Url(URI.create(u)), MediaType.Png).value
      sent(Conversation(Seq(UserMessage(Vector(part))))).left.value shouldBe
        UnsupportedContentPartError("openai", part.kind, "user")
    }
  }

  it should "return a Left, not throw, when the SDK refuses a malformed part" in {
    // a null text is read as empty when the part is built, so it reaches the SDK as ""
    noException should be thrownBy sent(Conversation(Seq(UserMessage(Vector(ContentPart.Text(null), imageInline)))))
  }

  // the image formats the provider takes; any other is refused before sending (#1913, Codex)
  private def imageOf(mt: org.llm4s.media.ImageMediaType) =
    ContentPart.image(MediaSource.Inline(IArray[Byte](1, 2, 3)), mt).fold(e => fail(e.message), identity)

  it should "refuse, without sending, an image in a format OpenAI does not take" in {
    Seq(MediaType.Bmp, MediaType.Tiff).foreach { mt =>
      val part = imageOf(mt)
      sent(Conversation(Seq(UserMessage(Vector(part))))).left.value shouldBe
        UnsupportedContentPartError("openai", part.kind, "user")
    }
    sent(Conversation(Seq(UserMessage(Vector(imageOf(MediaType.Gif)))))).isRight shouldBe true
  }

  it should "count Chat Completions' 50 MB on the decoded files and images, not on the base64 body" in {
    val pdf =
      ContentPart.document(MediaSource.Inline(IArray.fill[Byte](ContentPart.MaxInlineBytes)(1)), MediaType.Pdf).value
    // eight 5 MiB PDFs are about 42 MB decoded and 56 MB encoded: within the limit
    PartEncoding.checkInlineMediaTotal(
      "openai",
      Seq(UserMessage(Vector(ContentPart.Text("x")) ++ Vector.fill(8)(pdf))),
      OpenAIClient.MaxInlineMediaBytes
    ) shouldBe
      Right(())
    // ten are 52 MB decoded: refused before sending
    val ten = UserMessage(Vector(ContentPart.Text("x")) ++ Vector.fill(10)(pdf))
    sent(Conversation(Seq(ten))).left.value.message should include(
      s"at most ${OpenAIClient.MaxInlineMediaBytes} bytes of files and images"
    )
  }

  it should "refuse, before sending, more than 500 images in a request" in {
    val url = ContentPart.image(MediaSource.Url(URI.create("https://x.example/a.png")), MediaType.Png).value
    sent(
      Conversation(Seq(UserMessage(Vector(ContentPart.Text("x")) ++ Vector.fill(501)(url))))
    ).left.value.message should
      include(s"at most ${OpenAIClient.MaxImages} images in a request")
  }
}
