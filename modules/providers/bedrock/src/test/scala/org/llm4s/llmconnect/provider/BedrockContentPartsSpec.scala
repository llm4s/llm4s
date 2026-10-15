package org.llm4s.llmconnect.provider

import com.sun.net.httpserver.HttpExchange
import org.llm4s.error.UnsupportedContentPartError
import org.llm4s.llmconnect.model.*
import org.llm4s.llmconnect.provider.BedrockTestSupport.*
import org.llm4s.media.{ DocumentMediaType, ImageMediaType, MediaSource, MediaType }
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer.{ sendJsonResponse, withServer }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

/** How the Bedrock client sends content parts on Converse, and the typed error for those it cannot. */
class BedrockContentPartsSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val bytes = MediaSource.Inline(IArray[Byte](1, 2, 3))
  private val s3    = MediaSource.Url(URI.create("s3://bucket/key/cat.png"))
  private val https = MediaSource.Url(URI.create("https://example.com/cat.png"))

  private def image(source: MediaSource, mt: ImageMediaType = MediaType.Png)  = ContentPart.image(source, mt).value
  private def doc(source: MediaSource, mt: DocumentMediaType = MediaType.Pdf) = ContentPart.document(source, mt).value

  /** The request body the client sends for `conv`, or None when it sent nothing; and its result. */
  private def send(conv: Conversation): (Option[ujson.Value], Either[org.llm4s.error.LLMError, Completion]) = {
    val seen   = new AtomicReference[Option[ujson.Value]](None)
    var result = Option.empty[Either[org.llm4s.error.LLMError, Completion]]
    withServer("/") { (ex: HttpExchange) =>
      seen.set(Some(ujson.read(new String(ex.getRequestBody.readAllBytes(), StandardCharsets.UTF_8))))
      sendJsonResponse(ex, 200, converseResponse("ok"))
    } { url =>
      val client = new BedrockClient(config(url))
      result = Some(client.complete(conv, CompletionOptions()))
      client.close()
    }
    (seen.get, result.get)
  }

  private def userBlocks(parts: ContentPart*): Seq[ujson.Value] =
    send(Conversation(Seq(UserMessage(parts.toVector))))._1.get("messages").arr.head("content").arr.toSeq

  "A user message" should "send inline images and documents as bytes, with their formats" in {
    val blocks = userBlocks(
      ContentPart.Text("look"),
      image(bytes, MediaType.Jpeg),
      doc(bytes),
      doc(bytes, MediaType.Csv),
      doc(bytes, MediaType.Html),
      doc(bytes, MediaType.PlainText),
      doc(bytes, MediaType.Markdown)
    )
    blocks.head shouldBe ujson.Obj("text" -> "look")
    blocks(1)("image") shouldBe ujson.Obj("format" -> "jpeg", "source" -> ujson.Obj("bytes" -> "AQID"))
    blocks.drop(2).map(_("document")("format").str) shouldBe Seq("pdf", "csv", "html", "txt", "md")
    blocks.drop(2).map(_("document")("name").str) shouldBe Seq(
      "document-1",
      "document-2",
      "document-3",
      "document-4",
      "document-5"
    )
    blocks(2)("document")("source") shouldBe ujson.Obj("bytes" -> "AQID")
  }

  it should "send an s3:// URL as an S3 location" in {
    val blocks =
      userBlocks(
        image(s3, MediaType.WebP),
        doc(MediaSource.Url(URI.create("s3://bucket/report.pdf"))),
        ContentPart.Text("?")
      )
    blocks.head("image") shouldBe
      ujson.Obj(
        "format" -> "webp",
        "source" -> ujson.Obj("s3Location" -> ujson.Obj("uri" -> "s3://bucket/key/cat.png"))
      )
    blocks(1)("document")("source") shouldBe ujson.Obj("s3Location" -> ujson.Obj("uri" -> "s3://bucket/report.pdf"))
  }

  "A tool result" should "carry text, images and documents inside toolResult" in {
    val conv = Conversation(
      Seq(
        UserMessage("go"),
        AssistantMessage(None, Seq(ToolCall("c1", "fetch", ujson.Obj()))),
        ToolMessage(Vector(ContentPart.Text("here"), image(bytes), doc(bytes)), "c1")
      )
    )
    val result = send(conv)._1.get("messages").arr.last("content").arr.head("toolResult")
    result("content").arr.map(_.obj.keys.head).toSeq shouldBe Seq("text", "image", "document")
  }

  it should "go as text and then its media when there is no call to answer" in {
    val conv = Conversation(Seq(UserMessage("go"), ToolMessage(Vector(ContentPart.Text("orphan"), image(bytes)), "c9")))
    val blocks = send(conv)._1.get("messages").arr.head("content").arr
    blocks.map(_.obj.keys.head).toSeq shouldBe Seq("text", "text", "text", "image")
    blocks(1)("text").str shouldBe "[Tool result for c9]:"
    blocks(2)("text").str shouldBe "orphan"
  }

  it should "keep an unanswered result's parts in their order, each caption beside its image" in {
    val parts =
      Vector(ContentPart.Text("before"), image(bytes), ContentPart.Text("after"), image(bytes, MediaType.Gif))
    val conv   = Conversation(Seq(UserMessage("go"), ToolMessage(parts, "c9")))
    val blocks = send(conv)._1.get("messages").arr.head("content").arr
    blocks.map(_.obj.keys.head).toSeq shouldBe Seq("text", "text", "text", "image", "text", "image")
    blocks.drop(2).flatMap(_.obj.get("text").map(_.str)).toSeq shouldBe Seq("before", "after")
  }

  it should "send no empty text block for a blank text part" in {
    val blocks = userBlocks(ContentPart.Text(""), image(bytes), ContentPart.Text("caption"), ContentPart.Text("  "))
    blocks.map(_.obj.keys.head).toSeq shouldBe Seq("image", "text")
    blocks(1)("text").str shouldBe "caption"
  }

  "complete" should "refuse null parts as invalid, also on its logging path, and never throw" in {
    Seq(UserMessage(null: Vector[ContentPart]), UserMessage(Vector(ContentPart.Text("x"), null))).foreach { message =>
      val (sent, result) = send(Conversation(Seq(message)))
      sent shouldBe None
      result.left.value.message should include("null part")
    }
  }

  it should "refuse, before sending, each part Converse cannot take" in {
    val refused = Seq(
      image(https)                                        -> "image (url, image/png)",
      image(bytes, MediaType.Bmp)                         -> "image (inline, image/bmp)",
      image(MediaSource.ProviderFile("bedrock", "f1"))    -> "image (provider file bedrock, image/png)",
      doc(MediaSource.Url(URI.create("https://e.com/a"))) -> "document (url, application/pdf)"
    )
    refused.foreach { case (part, kind) =>
      val (sent, result) = send(Conversation(Seq(UserMessage(Vector(part)))))
      sent shouldBe None
      result.left.value match {
        case UnsupportedContentPartError(_, "bedrock", `kind`, "user") => ()
        case other => fail(s"expected UnsupportedContentPartError for $kind, got $other")
      }
    }
    val tool = Conversation(
      Seq(
        UserMessage("go"),
        AssistantMessage(None, Seq(ToolCall("c1", "fetch", ujson.Obj()))),
        ToolMessage(Vector(image(bytes, MediaType.Tiff)), "c1")
      )
    )
    val (sent, result) = send(tool)
    sent shouldBe None
    result.left.value shouldBe UnsupportedContentPartError("bedrock", "image (inline, image/tiff)", "tool")
  }
}
