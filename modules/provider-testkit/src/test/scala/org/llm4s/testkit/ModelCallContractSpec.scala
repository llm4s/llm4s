package org.llm4s.testkit

import org.llm4s.error.ContentFilteredError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.types.Result
import org.scalatest.exceptions.TestFailedException
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.net.http.{ HttpClient, HttpRequest, HttpResponse }
import java.time.Instant

class ModelCallContractSpec extends AnyFlatSpec with Matchers with ProviderModuleChecks:

  /**
   * A client for a made-up wire format: `complete` reads `{"text","finish","calls":[...]}`, a stream one such object
   * per line, and a `finish` of `filter` is a content-filter stop. `mapFinish` maps the wire's reason, so a test can
   * give it a wrong mapping; `deliver` decides whether a stream chunk reaches `onChunk`.
   */
  private class Reference(
    baseUrl: String,
    mapFinish: String => FinishReason = Reference.finish,
    deliver: StreamedChunk => Boolean = _ => true
  ) extends LLMClient:
    private def fetch(): String =
      HttpClient
        .newHttpClient()
        .send(HttpRequest.newBuilder(URI.create(baseUrl)).GET().build(), HttpResponse.BodyHandlers.ofString())
        .body()

    private def callsOf(json: ujson.Value): List[ToolCall] =
      json.obj.get("calls").toList.flatMap(_.arr).map(c => ToolCall(c("id").str, c("name").str, c("args")))

    private def completion(text: String, calls: List[ToolCall], finish: FinishReason): Completion =
      Completion("r", Instant.EPOCH, text, "m", AssistantMessage(Option(text).filter(_.nonEmpty), calls))
        .withToolCalls(calls)
        .withFinishReason(finish)

    def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      val json = ujson.read(fetch())
      json("finish").str match
        case "filter" => Left(ContentFilteredError.reply("Reference", "filter"))
        case finish   => Right(completion(json("text").str, callsOf(json), mapFinish(finish)))

    def streamComplete(c: Conversation, o: CompletionOptions, onChunk: StreamedChunk => Unit): Result[Completion] =
      val events = fetch().linesIterator.filter(_.nonEmpty).map(ujson.read(_)).toList
      if events.exists(_.obj.get("finish").exists(_.str == "filter")) then
        Left(ContentFilteredError.reply("Reference", "filter"))
      else
        val chunks = events.flatMap { e =>
          val text   = e.obj.get("text").map(_.str).filter(_.nonEmpty)
          val finish = e.obj.get("finish").map(f => mapFinish(f.str))
          val calls  = callsOf(e)
          StreamedChunk("r", text, calls.headOption, finish) +: calls
            .drop(1)
            .map(tc => StreamedChunk("r", None, Some(tc)))
        }
        chunks.filter(deliver).foreach(onChunk)
        Right(
          completion(
            chunks.flatMap(_.content).mkString,
            chunks.flatMap(_.toolCall),
            chunks.flatMap(_.finishReason).lastOption.getOrElse(FinishReason.Other(""))
          )
        )

    def getContextWindow(): Int     = 1000
    def getReserveCompletion(): Int = 100

  private object Reference:
    def finish(raw: String): FinishReason = raw match
      case "stop"   => FinishReason.Stop
      case "length" => FinishReason.Length
      case "tool"   => FinishReason.ToolCalls
      case other    => FinishReason.Other(other)

  private def reply(complete: String, stream: String*): Applicability =
    Applicability.Applies(
      RecordedReply(
        LocalProviderTestServer.sendJsonResponse(_, 200, complete),
        LocalProviderTestServer.sendJsonResponse(_, 200, stream.mkString("\n"))
      )
    )

  private val call = """{"id":"c1","name":"lookup","args":{"q":"x"}}"""

  private val cases: Map[ContractCase, Applicability] = Map(
    ContractCase.CleanText -> reply(
      """{"text":"Hello there","finish":"stop"}""",
      """{"text":"Hello "}""",
      """{"text":"there"}""",
      """{"finish":"stop"}"""
    ),
    ContractCase.TextCutByLimit -> reply(
      """{"text":"Hello th","finish":"length"}""",
      """{"text":"Hello "}""",
      """{"text":"th","finish":"length"}"""
    ),
    ContractCase.ToolCall -> reply(
      s"""{"text":"","finish":"tool","calls":[$call]}""",
      s"""{"calls":[$call]}""",
      """{"finish":"tool"}"""
    ),
    ContractCase.ContentFiltered -> reply(
      """{"text":"Hel","finish":"filter"}""",
      """{"text":"Hel"}""",
      """{"finish":"filter"}"""
    )
  )

  private def failure(check: => Any): String =
    intercept[TestFailedException](check).getMessage

  "assertModelCallContract" should "pass a client that reports each ending as RFC 0009 says" in {
    assertModelCallContract(cases)(new Reference(_))
  }

  it should "accept a case declared NotApplicable with a reason, without running it" in {
    val noFilter = cases.updated(ContractCase.ContentFiltered, Applicability.NotApplicable("no content filter"))
    assertModelCallContract(noFilter)(new Reference(_))
  }

  it should "fail when a case is not declared" in {
    failure(assertModelCallContract(cases - ContractCase.ToolCall)(new Reference(_))) should include("ToolCall")
  }

  it should "fail when a case is NotApplicable without a reason" in {
    val blank = cases.updated(ContractCase.ContentFiltered, Applicability.NotApplicable(" "))
    failure(assertModelCallContract(blank)(new Reference(_))) should include("without a reason")
  }

  it should "fail a client that reports a cut reply as Stop" in {
    val stopOnly = (raw: String) => if raw == "length" then FinishReason.Stop else Reference.finish(raw)
    failure(assertModelCallContract(cases)(new Reference(_, stopOnly))) should include("TextCutByLimit, complete")
  }

  it should "fail a client that returns a filtered reply as a Right" in {
    val passFilter = cases.updated(
      ContractCase.ContentFiltered,
      reply("""{"text":"Hel","finish":"content_filter"}""", """{"text":"Hel","finish":"content_filter"}""")
    )
    failure(assertModelCallContract(passFilter)(new Reference(_))) should include("Left(ContentFilteredError)")
  }

  it should "fail tool call arguments that are not a JSON object" in {
    val stringArgs = """{"id":"c1","name":"lookup","args":"{\"q\":"}"""
    val broken = cases.updated(
      ContractCase.ToolCall,
      reply(s"""{"text":"","finish":"tool","calls":[$stringArgs]}""", s"""{"calls":[$stringArgs],"finish":"tool"}""")
    )
    failure(assertModelCallContract(broken)(new Reference(_))) should include("not a JSON object")
  }

  it should "fail a stream whose chunks do not add up to the Completion it returned" in {
    val dropsText = (chunk: StreamedChunk) => !chunk.content.contains("there")
    failure(assertModelCallContract(cases)(new Reference(_, deliver = dropsText))) should include(
      "CleanText, streamComplete: the chunks delivered"
    )
  }

  it should "fail a stream that delivers no finish reason" in {
    // the chunk that carries only the reason is never delivered
    val noReason = (chunk: StreamedChunk) => chunk.finishReason.isEmpty || chunk.content.nonEmpty
    failure(assertModelCallContract(cases)(new Reference(_, deliver = noReason))) should include(
      "the last finish reason a chunk carried is None"
    )
  }
