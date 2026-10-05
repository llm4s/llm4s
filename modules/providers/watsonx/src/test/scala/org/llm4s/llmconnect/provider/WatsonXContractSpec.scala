package org.llm4s.llmconnect.provider

import org.llm4s.error.{ ServiceError, ValidationError }
import org.llm4s.http.HttpResponse
import org.llm4s.llmconnect.model.*
import org.llm4s.model.ModelRegistryService
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolFunction }
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import upickle.default.*

/** Maintainer decisions: tools are rejected before any HTTP call; error stop reasons are failures. */
class WatsonXContractSpec extends AnyFunSuite with Matchers:
  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  import StubHttp.*
  import WatsonXTestConfig.{ config, ApiKey }

  final private case class Pong(ok: Boolean)
  private given ReadWriter[Pong] = macroRW

  private val tool: ToolFunction[Map[String, Any], Pong] =
    ToolBuilder[Map[String, Any], Pong]("ping", "ping", Schema.`object`[Map[String, Any]]("none"))
      .withHandler(_ => Right(Pong(true)))
      .buildSafe()
      .getOrElse(fail("tool"))

  private val hi = Conversation(Seq(UserMessage("Hi")))

  private def generationWith(stop: Option[String]): HttpResponse =
    val field = stop.fold("")(s => s""","stop_reason":"$s"""")
    HttpResponse(
      200,
      s"""{"id":"g","results":[{"generated_text":"Hello","generated_token_count":2,"input_token_count":7$field}]}"""
    )

  test("tools are rejected by complete with a ValidationError naming 'tools' and NO http request at all") {
    val http   = routed(_ => Right(iamToken()), _ => Right(generation))
    val result = new WatsonXClient(config, httpClient = http).complete(hi, CompletionOptions().withTools(Seq(tool)))
    result.left.toOption match
      case Some(e: ValidationError) =>
        e.field shouldBe "tools"
        e.message should include("tool calling")
        (e.message should not).include(ApiKey)
      case other => fail(s"expected ValidationError, got $other")
    http.requests shouldBe empty
  }

  test("tools are rejected by streamComplete with a ValidationError, onChunk is never called, NO http request") {
    val http   = streaming(streamOf(bytes("data: {}\n\n")))
    var chunks = 0
    val result = new WatsonXClient(config, httpClient = http)
      .streamComplete(hi, CompletionOptions().withTools(Seq(tool)), _ => chunks += 1)
    result.left.toOption.exists(_.isInstanceOf[ValidationError]) shouldBe true
    chunks shouldBe 0
    http.requests shouldBe empty
  }

  test("an empty tool list is not tools: the call goes through") {
    val http = routed(_ => Right(iamToken()), _ => Right(generation))
    new WatsonXClient(config, httpClient = http)
      .complete(hi, CompletionOptions().withTools(Seq.empty))
      .isRight shouldBe true
    http.modelRequests should have size 1
  }

  test("the other unsupported options still do not fail a call") {
    val http = routed(_ => Right(iamToken()), _ => Right(generation))
    val opts = CompletionOptions()
      .withPresencePenalty(0.5)
      .withFrequencyPenalty(0.5)
      .withReasoning(ReasoningEffort.High)
      .withResponseFormat(Some(ResponseFormat.Json))
    new WatsonXClient(config, httpClient = http).complete(hi, opts).isRight shouldBe true
  }

  test("the stop_sequences really go over the wire, in complete and in streamComplete") {
    val http = routed(_ => Right(iamToken()), _ => Right(generation))
    new WatsonXClient(config, httpClient = http).complete(hi, CompletionOptions()): Unit
    ujson.read(http.modelRequests.head.body)("parameters")("stop_sequences")(0).str shouldBe "\n[USER]:"

    val sttp = streaming(
      streamOf(bytes("data: " + """{"results":[{"generated_text":"x","stop_reason":"eos_token"}]}""" + "\n\n"))
    )
    new WatsonXClient(config, httpClient = sttp).streamComplete(hi, CompletionOptions(), _ => ()): Unit
    ujson.read(sttp.modelRequests.head.body)("parameters")("stop_sequences").arr.map(_.str) should contain("\n[USER]:")
  }

  test("complete(): stop_reason table, error values are a ServiceError, everything else (or absent) is Right") {
    val table: Seq[(Option[String], Boolean)] = Seq(
      None                  -> true,
      Some("not_finished")  -> true,
      Some("eos_token")     -> true,
      Some("stop_sequence") -> true,
      Some("max_tokens")    -> true,
      Some("token_limit")   -> true,
      Some("brand_new")     -> true,
      Some("")              -> true,
      Some("error")         -> false,
      Some("cancelled")     -> false,
      Some("time_limit")    -> false,
      Some("ERROR")         -> false,
      Some("Time_Limit")    -> false
    )
    table.foreach { case (stop, ok) =>
      val http   = routed(_ => Right(iamToken()), _ => Right(generationWith(stop)))
      val result = new WatsonXClient(config, httpClient = http).complete(hi, CompletionOptions())
      withClue(s"stop_reason=$stop: ") {
        result.isRight shouldBe ok
        if !ok then
          result.left.toOption.exists(_.isInstanceOf[ServiceError]) shouldBe true
          result.left.toOption.map(_.message).getOrElse("") should include(stop.getOrElse("").toLowerCase)
      }
    }
  }

  test("error messages for abnormal endings and rejected tools never contain the API key") {
    val http      = routed(_ => Right(iamToken()), _ => Right(generationWith(Some("error"))))
    val nonStream = new WatsonXClient(config, httpClient = http).complete(hi, CompletionOptions())
    val streamed = new WatsonXClient(config, httpClient = streaming(streamOf(bytes(""))))
      .streamComplete(hi, CompletionOptions(), _ => ())
    val rejected = new WatsonXClient(config, httpClient = http).complete(hi, CompletionOptions().withTools(Seq(tool)))
    Seq(nonStream, streamed, rejected).foreach { r =>
      r.isLeft shouldBe true
      val text = r.left.toOption.map(e => e.toString + e.message).getOrElse("")
      (text should not).include(ApiKey)
    }
  }
