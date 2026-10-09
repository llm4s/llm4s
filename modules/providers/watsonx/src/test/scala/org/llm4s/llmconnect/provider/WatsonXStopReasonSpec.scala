package org.llm4s.llmconnect.provider

import org.llm4s.error.ServiceError
import org.llm4s.http.HttpResponse
import org.llm4s.llmconnect.model.*
import org.llm4s.model.ModelRegistryService
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ListBuffer

/**
 * IBM documents `stop_reason` in UPPER case (`NOT_FINISHED`, `EOS_TOKEN`, ...). Every value must be
 * read case-insensitively, on the stream and the non-stream path alike.
 */
class WatsonXStopReasonSpec extends AnyFunSuite with Matchers:
  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  import StubHttp.*
  import WatsonXTestConfig.config

  private val hi = Conversation(Seq(UserMessage("Hi")))

  private val documented = Seq(
    "NOT_FINISHED"  -> "not_finished",
    "MAX_TOKENS"    -> "max_tokens",
    "EOS_TOKEN"     -> "eos_token",
    "CANCELLED"     -> "cancelled",
    "TIME_LIMIT"    -> "time_limit",
    "STOP_SEQUENCE" -> "stop_sequence",
    "TOKEN_LIMIT"   -> "token_limit",
    "ERROR"         -> "error"
  ).map(_._1)

  private def mixed(value: String): String =
    value.zipWithIndex.map { case (c, i) => if i % 2 == 0 then c.toUpper else c.toLower }.mkString

  private def variants(value: String): Seq[String] =
    Seq(value.toUpperCase, value.toLowerCase, mixed(value), s"  ${value.toUpperCase}\t").distinct

  private val failing = Set("ERROR", "CANCELLED", "TIME_LIMIT")

  /** An SSE `data:` event. */
  private def event(text: String, stop: String): String =
    val body = s"""{"results":[{"generated_text":${ujson
        .Str(text)
        .render()},"generated_token_count":1,"input_token_count":3,"stop_reason":${ujson
        .Str(stop)
        .render()}}]}"""
    s"data: $body\n\n"

  private case class Run(result: Either[org.llm4s.error.LLMError, Completion], chunks: List[StreamedChunk])

  private def stream(events: String): Run =
    val chunks = ListBuffer.empty[StreamedChunk]
    val result = new WatsonXClient(config, httpClient = streaming(streamOf(bytes(events))))
      .streamComplete(hi, CompletionOptions(), chunks += _)
    Run(result, chunks.toList)

  private def nonStream(stop: String): Either[org.llm4s.error.LLMError, Completion] =
    val body =
      s"""{"id":"g","results":[{"generated_text":"Hello","generated_token_count":2,"input_token_count":7,"stop_reason":${ujson
          .Str(stop)
          .render()}}]}"""
    new WatsonXClient(config, httpClient = routed(_ => Right(iamToken()), _ => Right(HttpResponse(200, body))))
      .complete(hi, CompletionOptions())

  test("stream: NOT_FINISHED on intermediate events in any case is not terminal; text arrives in order") {
    val terminals = Seq("EOS_TOKEN", "MAX_TOKENS", "eos_token", "Max_Tokens", "STOP_SEQUENCE", "TOKEN_LIMIT")
    for
      intermediate <- variants("NOT_FINISHED")
      terminal     <- terminals
    do
      val run = stream(event("one ", intermediate) + event("two ", intermediate) + event("three", terminal))
      withClue(s"intermediate '$intermediate', terminal '$terminal': ") {
        run.result.map(_.content) shouldBe Right("one two three")
        run.chunks.flatMap(_.content) shouldBe List("one ", "two ", "three")
        run.chunks.map(_.finishReason) shouldBe List(None, None, Some(terminal.trim.toLowerCase))
      }
  }

  test("stream: a stream that ends on a NOT_FINISHED event (any case) is truncated, so a Left") {
    variants("NOT_FINISHED").foreach { nf =>
      val run = stream(event("one ", nf) + event("two", nf))
      withClue(s"'$nf': ")(run.result.left.toOption.exists(_.isInstanceOf[ServiceError]) shouldBe true)
    }
  }

  test("stream: every documented value in upper, lower and mixed case: errors are Left, the rest Right") {
    documented.foreach { value =>
      variants(value).foreach { reason =>
        val run = stream(event("a", "NOT_FINISHED") + event("b", reason))
        withClue(s"stream '$reason': ") {
          if failing(value) then
            run.result.left.toOption match
              case Some(e: ServiceError) => e.message should include(s"stop_reason '${value.toLowerCase}'")
              case other                 => fail(s"expected ServiceError, got $other")
          else if value == "NOT_FINISHED" then run.result.isLeft shouldBe true
          else run.result.map(_.content) shouldBe Right("ab")
        }
      }
    }
  }

  test("stream: an unknown value in any case is a normal stop, delivered normalised") {
    Seq("brand_new", "BRAND_NEW", "Brand_New").foreach { reason =>
      val run = stream(event("a", "NOT_FINISHED") + event("b", reason))
      withClue(s"'$reason': ") {
        run.result.map(_.content) shouldBe Right("ab")
        run.chunks.last.finishReason shouldBe Some("brand_new")
      }
    }
  }

  test("complete: every documented value in upper, lower and mixed case: errors are Left, the rest Right") {
    documented.foreach { value =>
      variants(value).foreach { reason =>
        val result = nonStream(reason)
        withClue(s"complete '$reason': ") {
          if failing(value) then
            result.left.toOption match
              case Some(e: ServiceError) => e.message should include(s"stop_reason '${value.toLowerCase}'")
              case other                 => fail(s"expected ServiceError, got $other")
          else result.map(_.content) shouldBe Right("Hello")
        }
      }
    }
    nonStream("BRAND_NEW").isRight shouldBe true
  }
