package org.llm4s.testkit

import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.error.{ ContentFilteredError, LLMError, NetworkError, ProcessingError, ServiceError }
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
   * per line, and a `finish` of `filter` is a content-filter stop. A stream with no `finish` ended early, and a line
   * `{"error":"..."}` is an error event. `mapFinish` maps the wire's reason, so a test can give it a wrong mapping;
   * `deliver` decides whether a stream chunk reaches `onChunk`; `early` and `errorEvent` are the errors it returns for
   * a stream that ended early and for an error event, `None` to return the `Completion` anyway. With `toolRules` it
   * fails a call whose `args` is not an object, or any call in a `length` reply, as RFC 0009 says; without, it
   * returns them.
   */
  private class Reference(
    baseUrl: String,
    mapFinish: String => FinishReason = Reference.finish,
    deliver: StreamedChunk => Boolean = _ => true,
    early: Option[LLMError] = Some(NetworkError("the stream ended early", None, "reference")),
    errorEvent: String => Option[LLMError] = message => Some(ServiceError(502, "reference", message)),
    toolRules: Boolean = true
  ) extends LLMClient:
    private def fetch(): String =
      HttpClient
        .newHttpClient()
        .send(HttpRequest.newBuilder(URI.create(baseUrl)).GET().build(), HttpResponse.BodyHandlers.ofString())
        .body()

    private def callsOf(json: ujson.Value): List[ToolCall] =
      json.obj.get("calls").toList.flatMap(_.arr).map(c => ToolCall(c("id").str, c("name").str, c("args")))

    private def completion(text: String, calls: List[ToolCall], finish: FinishReason): Result[Completion] =
      val broken = calls.exists(_.arguments.objOpt.isEmpty) || (calls.nonEmpty && finish == FinishReason.Length)
      if toolRules && broken then Left(ProcessingError("reference-tool-calls", "malformed tool call"))
      else
        Right(
          Completion("r", Instant.EPOCH, ModelName("m"), AssistantMessage(Option(text).filter(_.nonEmpty), calls))
            .withFinishReason(finish)
        )

    def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      val json = ujson.read(fetch())
      json("finish").str match
        case "filter" => Left(ContentFilteredError.reply("Reference", "filter"))
        case finish   => completion(json("text").str, callsOf(json), mapFinish(finish))

    def streamComplete(c: Conversation, o: CompletionOptions)(onChunk: StreamedChunk => Unit): Result[Completion] =
      val events = fetch().linesIterator.filter(_.nonEmpty).map(ujson.read(_)).toList
      val errors = events.flatMap(_.obj.get("error")).flatMap(e => errorEvent(e.str))
      if events.exists(_.obj.get("finish").exists(_.str == "filter")) then
        Left(ContentFilteredError.reply("Reference", "filter"))
      else if errors.nonEmpty then Left(errors.head)
      else if !events.exists(_.obj.contains("finish")) && early.isDefined then Left(early.get)
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
        completion(
          chunks.flatMap(_.content).mkString,
          chunks.flatMap(_.toolCall),
          chunks.flatMap(_.finishReason).lastOption.getOrElse(FinishReason.Unknown)
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

  private def streamOnly(stream: String*): Applicability =
    Applicability.Applies(
      RecordedReply.streamOnly(LocalProviderTestServer.sendJsonResponse(_, 200, stream.mkString("\n")))
    )

  private val call    = """{"id":"c1","name":"lookup","args":{"q":"x"}}"""
  private val noArgs  = """{"id":"c2","name":"now","args":{}}"""
  private val badArgs = """{"id":"c3","name":"lookup","args":"{\"q\":"}"""

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
    ContractCase.NoArgumentToolCall -> reply(
      s"""{"text":"","finish":"tool","calls":[$noArgs]}""",
      s"""{"calls":[$noArgs]}""",
      """{"finish":"tool"}"""
    ),
    ContractCase.ToolCallCutByLimit -> reply(
      s"""{"text":"","finish":"length","calls":[$call]}""",
      s"""{"calls":[$call]}""",
      """{"finish":"length"}"""
    ),
    ContractCase.MalformedArguments -> reply(
      s"""{"text":"","finish":"tool","calls":[$badArgs]}""",
      s"""{"calls":[$badArgs]}""",
      """{"finish":"tool"}"""
    ),
    ContractCase.ContentFiltered -> reply(
      """{"text":"Hel","finish":"filter"}""",
      """{"text":"Hel"}""",
      """{"finish":"filter"}"""
    ),
    ContractCase.StreamEndsEarly -> streamOnly("""{"text":"Hello "}""", """{"text":"th"}"""),
    ContractCase.ErrorEvent -> streamOnly(
      """{"text":"Hello "}""",
      s"""{"error":"${ContractCase.ProviderErrorMessage}"}"""
    )
  )

  private val noEarly = ContractCase.StreamEndsEarly -> Applicability.NotApplicable("tested on its own")

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

  it should "fail when CleanText is NotApplicable: it is what proves the client streams" in {
    val noText = cases.updated(ContractCase.CleanText, Applicability.NotApplicable("no text"))
    failure(assertModelCallContract(noText)(new Reference(_))) should include("CleanText is NotApplicable")
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
    val broken = cases.updated(
      ContractCase.ToolCall,
      reply(s"""{"text":"","finish":"tool","calls":[$badArgs]}""", s"""{"calls":[$badArgs],"finish":"tool"}""")
    )
    failure(assertModelCallContract(broken)(new Reference(_, toolRules = false))) should include(
      "not a JSON object"
    )
  }

  it should "fail a client that runs a tool call the output limit cut" in {
    val lenient = cases - ContractCase.MalformedArguments +
      (ContractCase.MalformedArguments -> Applicability.NotApplicable("tested on its own"))
    failure(assertModelCallContract(lenient)(new Reference(_, toolRules = false))) should include(
      "ToolCallCutByLimit, complete: expected Left(ProcessingError)"
    )
  }

  it should "fail a client that passes on malformed arguments" in {
    val lenient = cases - ContractCase.ToolCallCutByLimit +
      (ContractCase.ToolCallCutByLimit -> Applicability.NotApplicable("tested on its own"))
    failure(assertModelCallContract(lenient)(new Reference(_, toolRules = false))) should include(
      "MalformedArguments, complete: expected Left(ProcessingError)"
    )
  }

  it should "fail a no-argument call whose arguments are not {}" in {
    val someArgs = cases.updated(
      ContractCase.NoArgumentToolCall,
      reply(s"""{"text":"","finish":"tool","calls":[$call]}""", s"""{"calls":[$call],"finish":"tool"}""")
    )
    failure(assertModelCallContract(someArgs)(new Reference(_))) should include("takes no arguments, expected {}")
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

  it should "fail a client that returns a stream with no end marker as a Right" in {
    failure(assertModelCallContract(cases)(new Reference(_, early = None))) should include(
      "StreamEndsEarly, streamComplete: expected Left(NetworkError)"
    )
  }

  it should "accept a ServiceError with status 502 for a stream that ended early, and nothing else" in {
    val badGateway = Some(ServiceError(502, "reference", "the stream ended early"))
    assertModelCallContract(cases)(new Reference(_, early = badGateway))
    val unavailable = Some(ServiceError(503, "reference", "the stream ended early"))
    failure(assertModelCallContract(cases)(new Reference(_, early = unavailable))) should include("StreamEndsEarly")
  }

  it should "fail a client that ignores an error event" in {
    // with no end marker either, the stream comes back a Right
    val lenient = new Reference(_, early = None, errorEvent = _ => None)
    failure(assertModelCallContract(cases - ContractCase.StreamEndsEarly + noEarly)(lenient)) should include(
      "ErrorEvent, streamComplete: expected a Left"
    )
  }

  it should "fail a client whose error loses the provider's message" in {
    val generic = (_: String) => Some(ServiceError(502, "reference", "something went wrong"))
    failure(assertModelCallContract(cases)(new Reference(_, errorEvent = generic))) should include(
      "expected the provider's error"
    )
  }

  it should "send no complete request for a stream-only case" in {
    val completes = new java.util.concurrent.atomic.AtomicInteger
    val counted = cases.updated(
      ContractCase.ErrorEvent,
      Applicability.Applies(
        RecordedReply(
          exchange =>
            completes.incrementAndGet()
            LocalProviderTestServer.sendJsonResponse(exchange, 500, "{}")
          ,
          LocalProviderTestServer.sendJsonResponse(
            _,
            200,
            s"""{"error":"${ContractCase.ProviderErrorMessage}"}"""
          )
        )
      )
    )
    assertModelCallContract(counted)(new Reference(_))
    completes.get shouldBe 0
  }
