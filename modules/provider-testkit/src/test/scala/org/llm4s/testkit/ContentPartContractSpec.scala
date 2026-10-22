package org.llm4s.testkit

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.llmconnect.provider.{ HttpErrorMapper, PartEncoding }
import org.llm4s.media.MediaSource
import org.llm4s.types.Result
import org.scalatest.exceptions.TestFailedException
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.net.http.{ HttpClient, HttpRequest, HttpResponse }
import java.time.Instant

class ContentPartContractSpec extends AnyFlatSpec with Matchers with ProviderModuleChecks:

  private val respond = LocalProviderTestServer.sendJsonResponse(_, 200, LocalProviderTestServer.openAICompletion("ok"))

  /**
   * A client that posts its conversation as JSON, encoding user parts with `user` and tool parts with `tool`; a
   * part neither covers fails the request before it is sent, as a real client must.
   */
  private class Reference(
    baseUrl: String,
    user: PartialFunction[ContentPart, ujson.Value],
    tool: PartialFunction[ContentPart, ujson.Value]
  ) extends LLMClient:
    def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      for messages <- conversation.messages.foldLeft[Result[Vector[ujson.Value]]](Right(Vector.empty)) { (acc, m) =>
          acc.flatMap { done =>
            m match
              case u: UserMessage =>
                PartEncoding.encodeAll("reference", u.role, u.parts)(user).map(done :+ ujson.Arr.from(_))
              case t: ToolMessage =>
                PartEncoding.encodeAll("reference", t.role, t.parts)(tool).map(done :+ ujson.Arr.from(_))
              case other => Right(done :+ ujson.Str(other.content))
          }
        }
      yield
        val request = HttpRequest
          .newBuilder(URI.create(baseUrl))
          .POST(HttpRequest.BodyPublishers.ofString(ujson.Arr.from(messages).render()))
          .build()
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()): Unit
        Completion(id = "x", created = Instant.EPOCH, content = "ok", model = "m", message = AssistantMessage("ok"))
    def streamComplete(c: Conversation, o: CompletionOptions, onChunk: StreamedChunk => Unit): Result[Completion] =
      complete(c, o)
    def getContextWindow(): Int     = 1000
    def getReserveCompletion(): Int = 100

  private val text: PartialFunction[ContentPart, ujson.Value] = { case ContentPart.Text(t) => ujson.Str(t) }
  private val inlineImages: PartialFunction[ContentPart, ujson.Value] = {
    case ContentPart.Image(inline: MediaSource.Inline, _) => ujson.Str(PartEncoding.base64(inline))
  }

  "assertContentParts" should "pass a client that sends what it declares and refuses the rest" in {
    assertContentParts("reference", respond)(ContentPartCase.UserImageInline) { url =>
      new Reference(url, text.orElse(inlineImages), text)
    }
  }

  it should "fail a client that drops a part instead of refusing it" in {
    val dropping: PartialFunction[ContentPart, ujson.Value] = { case _ => ujson.Null }
    val failure = intercept[TestFailedException] {
      assertContentParts("reference", respond)()(url => new Reference(url, text.orElse(dropping), text))
    }
    failure.getMessage should include("a request was sent for a part the client should refuse")
  }

  it should "fail a client that refuses a part it declares" in {
    val failure = intercept[TestFailedException] {
      assertContentParts("reference", respond)(ContentPartCase.UserPdfUrl)(url => new Reference(url, text, text))
    }
    failure.getMessage should include("'UserPdfUrl': complete returned Left")
  }

  it should "fail a client that sends a part without its payload" in {
    val empty: PartialFunction[ContentPart, ujson.Value] = { case _: ContentPart.Image => ujson.Str("image") }
    val failure = intercept[TestFailedException] {
      assertContentParts("reference", respond)(ContentPartCase.ToolImageInline)(url =>
        new Reference(url, text, text.orElse(empty))
      )
    }
    failure.getMessage should include("does not carry the part")
  }

  it should "fail a client that sends only the first of two text parts" in {
    val first: PartialFunction[ContentPart, ujson.Value] = { case ContentPart.Text(t) =>
      ujson.Str(if t == ContentPartContract.SecondText then "" else t)
    }
    val failure = intercept[TestFailedException] {
      assertContentParts("reference", respond)()(url => new Reference(url, first, text))
    }
    failure.getMessage should include("two text parts in a user message")
  }

  "Every case" should "build its part, carry it in its role, and name a marker" in {
    ContentPartCase.values.foreach { c =>
      val messages = c.conversation("acme").messages
      c.role match
        case MessageRole.Tool => messages.last.asInstanceOf[ToolMessage].parts.last shouldBe c.part("acme")
        case _                => messages.last.asInstanceOf[UserMessage].parts.last shouldBe c.part("acme")
      c.markers should not be empty
    }
  }

  /** Posts a request and maps a non-2xx answer with `map`. */
  private class Rejected(baseUrl: String, map: (Int, String) => org.llm4s.error.LLMError) extends LLMClient:
    def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      val request  = HttpRequest.newBuilder(URI.create(baseUrl)).POST(HttpRequest.BodyPublishers.ofString("{}")).build()
      val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
      Left(map(response.statusCode(), response.body()))
    def streamComplete(c: Conversation, o: CompletionOptions, onChunk: StreamedChunk => Unit): Result[Completion] =
      complete(c, o)
    def getContextWindow(): Int     = 1000
    def getReserveCompletion(): Int = 100

  private def openAIError: (com.sun.net.httpserver.HttpExchange, Int, String) => Unit = (exchange, status, message) =>
    LocalProviderTestServer.sendJsonResponse(
      exchange,
      status,
      ujson.Obj("error" -> ujson.Obj("message" -> message)).render()
    )

  "assertProviderRejectionIsTyped" should "pass a client that maps a 400 and a 413 with the provider's message" in {
    assertProviderRejectionIsTyped(openAIError) { url =>
      new Rejected(url, (status, body) => HttpErrorMapper.mapHttpError(status, body, "reference").left.toOption.get)
    }
  }

  it should "fail a client that maps the refusal to an UnknownError, or loses its message" in {
    intercept[TestFailedException] {
      assertProviderRejectionIsTyped(openAIError)(url =>
        new Rejected(url, (_, body) => org.llm4s.error.UnknownError(body, new RuntimeException(body)))
      )
    }.getMessage should include("UnknownError")
    intercept[TestFailedException] {
      assertProviderRejectionIsTyped(openAIError)(url =>
        new Rejected(url, (status, _) => org.llm4s.error.ServiceError(status, "reference", "failed"))
      )
    }.getMessage should include("without the provider's message")
  }
