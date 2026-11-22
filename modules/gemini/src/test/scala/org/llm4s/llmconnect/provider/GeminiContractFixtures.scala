package org.llm4s.llmconnect.provider

import org.llm4s.testkit.{ Applicability, ContractCase, LocalProviderTestServer, RecordedReply }

/**
 * `generateContent` and `streamGenerateContent?alt=sse` replies for each RFC 0009 [[ContractCase]], for
 * `assertModelCallContract`: the shape the Gemini API and Vertex AI share. Hand-written in the documented shape; the
 * `MAX_TOKENS` chunk with no `parts` follows the live capture in `GeminiClientHttpSpec`. A function call finishes
 * `STOP`, and a filter stop is a `finishReason` such as `SAFETY`. A call's `args` is a JSON object in the envelope, so a
 * malformed call never reaches the client as one: Gemini drops it and ends the reply `MALFORMED_FUNCTION_CALL`. A stream ends on a chunk whose candidate states a
 * `finishReason`; a failure after the 200 status is a chunk carrying an `error` object, as Google's APIs report one.
 */
object GeminiContractFixtures {

  private val Usage = """"usageMetadata":{"promptTokenCount":9,"candidatesTokenCount":4,"totalTokenCount":13}"""

  private def candidate(parts: String, finish: Option[String]): String = {
    val content = if (parts.isEmpty) """{"role":"model"}""" else s"""{"parts":$parts,"role":"model"}"""
    val reason  = finish.fold("")(f => s""","finishReason":"$f"""")
    s"""{"content":$content$reason,"index":0}"""
  }

  private def reply(parts: String, finish: String): String =
    s"""{"candidates":[${candidate(parts, Some(finish))}],$Usage,"modelVersion":"gemini-2.0-flash"}"""

  private def chunk(parts: String, finish: Option[String] = None): String = {
    val usage = if (finish.isDefined) s",$Usage" else ""
    s"""data: {"candidates":[${candidate(parts, finish)}]$usage,"modelVersion":"gemini-2.0-flash"}"""
  }

  private def sse(chunks: String*): String = chunks.mkString("", "\r\n\r\n", "\r\n\r\n")

  private def text(t: String): String = s"""[{"text":${ujson.Str(t).render()}}]"""

  private def applies(complete: String, stream: String): Applicability =
    Applicability.Applies(
      RecordedReply(
        LocalProviderTestServer.sendJsonResponse(_, 200, complete),
        LocalProviderTestServer.sendSseResponse(_, stream)
      )
    )

  private def streamOnly(stream: String): Applicability =
    Applicability.Applies(RecordedReply.streamOnly(LocalProviderTestServer.sendSseResponse(_, stream)))

  private val call = """[{"functionCall":{"name":"get_weather","args":{"city":"Paris"}}}]"""

  // a function with no parameters: Gemini leaves `args` out
  private val noArgs = """[{"functionCall":{"name":"get_time"}}]"""

  /** Every case, as the Gemini API and Vertex AI answer it. */
  val replies: Map[ContractCase, Applicability] = Map(
    ContractCase.CleanText -> applies(
      reply(text("The answer is 4."), "STOP"),
      sse(chunk(text("The answer")), chunk(text(" is 4."), Some("STOP")))
    ),
    ContractCase.TextCutByLimit -> applies(
      reply(text("The answer is"), "MAX_TOKENS"),
      sse(chunk(text("The answer")), chunk(text(" is")), chunk("", Some("MAX_TOKENS")))
    ),
    ContractCase.ToolCall -> applies(
      reply(call, "STOP"),
      sse(chunk(call), chunk("", Some("STOP")))
    ),
    ContractCase.NoArgumentToolCall -> applies(
      reply(noArgs, "STOP"),
      sse(chunk(noArgs), chunk("", Some("STOP")))
    ),
    // MAX_TOKENS ended the reply while it made the call: whatever args arrived, no call runs
    ContractCase.ToolCallCutByLimit -> applies(
      reply(call, "MAX_TOKENS"),
      sse(chunk(call), chunk("", Some("MAX_TOKENS")))
    ),
    // Gemini's report of a call it could not read: the call dropped, after some text
    ContractCase.MalformedArguments -> applies(
      reply(text("Let me check."), "MALFORMED_FUNCTION_CALL"),
      sse(chunk(text("Let me check.")), chunk("", Some("MALFORMED_FUNCTION_CALL")))
    ),
    ContractCase.ContentFiltered -> applies(
      reply(text("Here is how"), "SAFETY"),
      sse(chunk(text("Here is how")), chunk("", Some("SAFETY")))
    ),
    // text, then the connection closes before a chunk states a finishReason
    ContractCase.StreamEndsEarly -> streamOnly(sse(chunk(text("The answer")), chunk(text(" is")))),
    ContractCase.ErrorEvent -> streamOnly(
      sse(
        chunk(text("The answer")),
        // a quota hit mid-stream: a RateLimitError, which must keep the provider's message
        """data: {"error":{"code":429,"message":""" + ujson.Str(ContractCase.ProviderErrorMessage).render() +
          ""","status":"RESOURCE_EXHAUSTED"}}"""
      )
    )
  )
}
