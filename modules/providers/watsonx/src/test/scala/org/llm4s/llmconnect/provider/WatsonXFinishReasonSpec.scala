package org.llm4s.llmconnect.provider

import org.llm4s.error.ServiceError
import org.llm4s.http.HttpResponse
import org.llm4s.llmconnect.model.*
import org.llm4s.model.ModelRegistryService
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ListBuffer

/**
 * `finish_reason` is read case-insensitively, on the stream and the non-stream path alike. The values are the
 * OpenAI-style ones the chat API is assumed to use (IBM's reference could not be read): `stop`, `length` and
 * `tool_calls` end a reply normally; `error`, `cancelled` and `time_limit` do not.
 */
class WatsonXFinishReasonSpec extends AnyFunSuite with Matchers:
  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  import StubHttp.*
  import WatsonXTestConfig.config

  private val hi = Conversation(Seq(UserMessage("Hi")))

  private val assumedValues = Seq("STOP", "LENGTH", "TOOL_CALLS", "CANCELLED", "TIME_LIMIT", "ERROR")
  private val failing       = Set("ERROR", "CANCELLED", "TIME_LIMIT")

  private def mixed(value: String): String =
    value.zipWithIndex.map { case (c, i) => if i % 2 == 0 then c.toUpper else c.toLower }.mkString

  private def variants(value: String): Seq[String] =
    Seq(value.toUpperCase, value.toLowerCase, mixed(value), s"  ${value.toUpperCase}\t").distinct

  /** An SSE `data:` event with a content delta; `finish` is the choice's `finish_reason`, or none yet. */
  private def event(text: String, finish: Option[String]): String =
    val reason = finish.fold("null")(f => ujson.Str(f).render())
    s"""data: {"choices":[{"index":0,"delta":{"content":${ujson
        .Str(text)
        .render()}},"finish_reason":$reason}]}\n\n"""

  private case class Run(result: Either[org.llm4s.error.LLMError, Completion], chunks: List[StreamedChunk])

  /** RFC 0009's chat-completions row, for a value in any case and padding. */
  private def expectedReason(raw: String): FinishReason = raw.trim.toLowerCase match
    case "stop"       => FinishReason.Stop
    case "length"     => FinishReason.Length
    case "tool_calls" => FinishReason.ToolCalls
    case other        => FinishReason.Other(other)

  private def stream(events: String): Run =
    val chunks = ListBuffer.empty[StreamedChunk]
    val result = new WatsonXClient(config, httpClient = streaming(streamOf(bytes(events))))
      .streamComplete(hi, CompletionOptions())(chunks += _)
    Run(result, chunks.toList)

  private def nonStream(finish: Option[String]): Either[org.llm4s.error.LLMError, Completion] =
    val reason = finish.fold("")(f => s""","finish_reason":${ujson.Str(f).render()}""")
    val body =
      s"""{"id":"g","choices":[{"index":0,"message":{"role":"assistant","content":"Hello"}$reason}],"usage":{"prompt_tokens":7,"completion_tokens":2,"total_tokens":9}}"""
    new WatsonXClient(config, httpClient = routed(_ => Right(iamToken()), _ => Right(HttpResponse(200, body))))
      .complete(hi, CompletionOptions())

  test("stream: a null finish_reason on intermediate events is not terminal; text arrives in order") {
    Seq("STOP", "LENGTH", "stop", "Length", "tool_calls", "TOOL_CALLS").foreach { terminal =>
      val run = stream(event("one ", None) + event("two ", None) + event("three", Some(terminal)))
      withClue(s"terminal '$terminal': ") {
        run.result.map(_.content) shouldBe Right("one two three")
        run.chunks.flatMap(_.contentDelta) shouldBe List("one ", "two ", "three")
        run.chunks.map(_.finishReason) shouldBe List(None, None, None, Some(expectedReason(terminal)))
      }
    }
  }

  test("stream: a stream whose every event has a null finish_reason is truncated, so a Left") {
    val run = stream(event("one ", None) + event("two", None))
    run.result.left.toOption.exists(_.isInstanceOf[ServiceError]) shouldBe true
  }

  test("stream: every assumed value in upper, lower and mixed case: errors are Left, the rest Right") {
    assumedValues.foreach { value =>
      variants(value).foreach { reason =>
        val run = stream(event("a", None) + event("b", Some(reason)))
        withClue(s"stream '$reason': ") {
          if failing(value) then
            run.result.left.toOption match
              case Some(e: ServiceError) => e.message should include(s"finish_reason '${value.toLowerCase}'")
              case other                 => fail(s"expected ServiceError, got $other")
          else run.result.map(_.content) shouldBe Right("ab")
        }
      }
    }
  }

  test("stream: an unknown value in any case is a normal stop, delivered normalised") {
    Seq("brand_new", "BRAND_NEW", "Brand_New").foreach { reason =>
      val run = stream(event("a", None) + event("b", Some(reason)))
      withClue(s"'$reason': ") {
        run.result.map(_.content) shouldBe Right("ab")
        run.chunks.last.finishReason shouldBe Some(FinishReason.Other("brand_new"))
      }
    }
  }

  test("stream: a blank finish_reason is no finish_reason") {
    stream(event("a", Some("")) + event("b", Some("  "))).result.isLeft shouldBe true
  }

  test("complete: every assumed value in upper, lower and mixed case: errors are Left, the rest Right") {
    assumedValues.foreach { value =>
      variants(value).foreach { reason =>
        val result = nonStream(Some(reason))
        withClue(s"complete '$reason': ") {
          if failing(value) then
            result.left.toOption match
              case Some(e: ServiceError) => e.message should include(s"finish_reason '${value.toLowerCase}'")
              case other                 => fail(s"expected ServiceError, got $other")
          else result.map(_.content) shouldBe Right("Hello")
        }
      }
    }
    nonStream(Some("BRAND_NEW")).isRight shouldBe true
  }

  test("complete: a missing or blank finish_reason is fine") {
    nonStream(None).map(_.content) shouldBe Right("Hello")
    nonStream(Some("")).map(_.content) shouldBe Right("Hello")
  }

  test("complete: the Completion carries the mapped reason; none read is Unknown") {
    Seq("STOP", "length", " Tool_Calls ", "brand_new").foreach { reason =>
      withClue(s"'$reason': ")(nonStream(Some(reason)).map(_.finishReason) shouldBe Right(expectedReason(reason)))
    }
    nonStream(None).map(_.finishReason) shouldBe Right(FinishReason.Unknown)
    // the older chat-completions name for a tool call (RFC 0009's chat-completions row)
    nonStream(Some("function_call")).map(_.finishReason) shouldBe Right(FinishReason.ToolCalls)
  }

  test("stream: the Completion carries the last chunk's reason") {
    stream(event("a", None) + event("b", Some("LENGTH"))).result.map(_.finishReason) shouldBe Right(FinishReason.Length)
  }
