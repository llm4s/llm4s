package org.llm4s.llmconnect.provider

import org.llm4s.error.UnsupportedContentPartError
import org.llm4s.http.{ HttpResponse, Llm4sHttpClient }
import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.llmconnect.config.GeminiConfig
import org.llm4s.llmconnect.model.*
import org.llm4s.media.{ MediaSource, MediaType }
import org.llm4s.model.ModelRegistryService
import org.scalamock.scalatest.MockFactory
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.util.concurrent.atomic.AtomicReference

class GeminiContentPartsSpec extends AnyFlatSpec with Matchers with EitherValues with MockFactory {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val bytes  = IArray[Byte](1, 2, 3)
  private val inline = ContentPart.image(MediaSource.Inline(bytes), MediaType.Png).value
  private val url  = ContentPart.document(MediaSource.Url(URI.create("https://example.com/r.pdf")), MediaType.Pdf).value
  private val gcs  = ContentPart.document(MediaSource.Url(URI.create("gs://bucket/r.pdf")), MediaType.Pdf).value
  private val s3   = ContentPart.document(MediaSource.Url(URI.create("s3://bucket/r.pdf")), MediaType.Pdf).value
  private val base = "https://generativelanguage.googleapis.com/v1beta"
  private val gemini = GeminiParts.geminiFileUri("gemini", base)
  private val file   = ContentPart.image(MediaSource.ProviderFile("gemini", "files/abc"), MediaType.Jpeg).value
  private val csv    = ContentPart.document(MediaSource.Inline(bytes), MediaType.Csv).value

  private val successBody =
    """{"candidates":[{"content":{"parts":[{"text":"ok"}],"role":"model"},"finishReason":"STOP"}]}"""

  private def client(http: Llm4sHttpClient) = new GeminiClient(
    GeminiConfig(
      apiKey = "k",
      model = "gemini-2.0-flash",
      baseUrl = "https://generativelanguage.googleapis.com/v1beta",
      contextWindow = 1048576,
      reserveCompletion = 8192
    ),
    org.llm4s.metrics.MetricsCollector.noop,
    ProviderExchangeLogging.Disabled,
    http
  )

  "GeminiParts.user" should "encode text, inline media, URLs and the provider's own files" in {
    val encode = GeminiParts.user(gemini)
    encode(ContentPart.Text("hi")) shouldBe ujson.Obj("text" -> "hi")
    encode(inline) shouldBe ujson.Obj("inlineData" -> ujson.Obj("mimeType" -> "image/png", "data" -> "AQID"))
    encode(csv) shouldBe ujson.Obj("inlineData" -> ujson.Obj("mimeType" -> "text/csv", "data" -> "AQID"))
    encode(url) shouldBe
      ujson.Obj("fileData" -> ujson.Obj("mimeType" -> "application/pdf", "fileUri" -> "https://example.com/r.pdf"))
    encode(file) shouldBe
      ujson.Obj("fileData" -> ujson.Obj("mimeType" -> "image/jpeg", "fileUri" -> s"$base/files/abc"))
  }

  it should "send an uploaded file by its URI, never by its bare name" in {
    def uri(id: String) = gemini(MediaSource.ProviderFile("gemini", id))
    uri("files/abc") shouldBe Some(s"$base/files/abc")
    uri("abc") shouldBe Some(s"$base/files/abc")
    uri(s"$base/files/abc") shouldBe Some(s"$base/files/abc")
    GeminiParts.geminiFileUri("gemini", s"$base/")(MediaSource.ProviderFile("gemini", "abc")) shouldBe
      Some(s"$base/files/abc")
  }

  it should "not cover a file uploaded to another provider, or a URL the API cannot fetch" in {
    GeminiParts.user(GeminiParts.geminiFileUri("vertexai", base)).isDefinedAt(file) shouldBe false
    GeminiParts.user(gemini).isDefinedAt(gcs) shouldBe false
    GeminiParts.user(gemini).isDefinedAt(s3) shouldBe false
  }

  "Vertex AI's references" should "be Cloud Storage objects only" in {
    val vertex = GeminiParts.user(GeminiParts.vertexFileUri)
    vertex(gcs) shouldBe
      ujson.Obj("fileData" -> ujson.Obj("mimeType" -> "application/pdf", "fileUri" -> "gs://bucket/r.pdf"))
    vertex.isDefinedAt(url) shouldBe false
    vertex.isDefinedAt(s3) shouldBe false
    vertex.isDefinedAt(
      ContentPart.image(MediaSource.ProviderFile("vertexai", "files/abc"), MediaType.Png).value
    ) shouldBe
      false
    vertex.isDefinedAt(inline) shouldBe true
  }

  "GeminiParts.check" should "refuse media in a tool result and another provider's file, naming the part" in {
    val assistant = AssistantMessage(None, Seq(ToolCall("c1", "fetch", ujson.Obj())))
    GeminiParts
      .check("gemini", Seq(UserMessage("go"), assistant, ToolMessage(Vector(inline), "c1")), gemini)
      .left
      .value shouldBe
      UnsupportedContentPartError("gemini", inline.kind, "tool")
    GeminiParts
      .check("vertexai", Seq(UserMessage(Vector(url))), GeminiParts.vertexFileUri)
      .left
      .value shouldBe
      UnsupportedContentPartError("vertexai", url.kind, "user")
    GeminiParts.check("gemini", Seq(UserMessage(Vector(ContentPart.Text("a"), inline, url, file))), gemini) shouldBe
      Right(())
  }

  "GeminiClient" should "send a user message's parts in order, and a tool result's text" in {
    val sent = new AtomicReference[String]()
    val http = stub[Llm4sHttpClient]
    (http.post _).when(*, *, *, *).onCall { (_, _, body, _) =>
      sent.set(body); Right(HttpResponse(200, successBody, Map.empty))
    }
    val assistant = AssistantMessage(None, Seq(ToolCall("c1", "fetch", ujson.Obj())))
    val conversation = Conversation(
      Seq(
        UserMessage(Vector(ContentPart.Text("look"), inline)),
        assistant,
        ToolMessage(Vector(ContentPart.Text("a"), ContentPart.Text("b")), "c1")
      )
    )
    client(http).complete(conversation, CompletionOptions()).isRight shouldBe true
    val contents = ujson.read(sent.get)("contents")
    contents(0) shouldBe ujson.Obj(
      "role"  -> "user",
      "parts" -> ujson.Arr(ujson.Obj("text" -> "look"), GeminiParts.user(gemini)(inline))
    )
    contents(2)("parts")(0)("functionResponse")("response") shouldBe ujson.Obj("result" -> "a\nb")
  }

  it should "send no empty text part for a blank text part" in {
    val sent = new AtomicReference[String]()
    val http = stub[Llm4sHttpClient]
    (http.post _).when(*, *, *, *).onCall { (_, _, body, _) =>
      sent.set(body); Right(HttpResponse(200, successBody, Map.empty))
    }
    client(http).complete(Conversation(Seq(UserMessage(Vector(ContentPart.Text(" "), inline)))), CompletionOptions())
    ujson.read(sent.get)("contents")(0)("parts").arr.size shouldBe 1
  }

  it should "send a one-text user message exactly as before" in {
    val sent = new AtomicReference[String]()
    val http = stub[Llm4sHttpClient]
    (http.post _).when(*, *, *, *).onCall { (_, _, body, _) =>
      sent.set(body); Right(HttpResponse(200, successBody, Map.empty))
    }
    client(http).complete(Conversation(Seq(UserMessage("hi"))), CompletionOptions()).isRight shouldBe true
    ujson.read(sent.get)("contents")(0) shouldBe
      ujson.Obj("role" -> "user", "parts" -> ujson.Arr(ujson.Obj("text" -> "hi")))
  }

  it should "fail before sending anything for a part it cannot send" in {
    val http  = stub[Llm4sHttpClient]
    val other = ContentPart.image(MediaSource.ProviderFile("openai", "file-1"), MediaType.Png).value
    client(http).complete(Conversation(Seq(UserMessage(Vector(other)))), CompletionOptions()).left.value shouldBe
      UnsupportedContentPartError("gemini", other.kind, "user")
    (http.post _).verify(*, *, *, *).never()
  }

}
