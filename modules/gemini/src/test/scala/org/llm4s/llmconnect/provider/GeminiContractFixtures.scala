package org.llm4s.llmconnect.provider

import org.llm4s.testkit.{ Applicability, ContractCase, LocalProviderTestServer, RecordedReply }

/**
 * `generateContent` and `streamGenerateContent?alt=sse` replies for each RFC 0009 [[ContractCase]], for
 * `assertModelCallContract`: the shape the Gemini API and Vertex AI share. Hand-written in the documented shape; the
 * `MAX_TOKENS` chunk with no `parts` follows the live capture in `GeminiClientHttpSpec`. A function call finishes
 * `STOP`, and a filter stop is a `finishReason` such as `SAFETY`.
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

  private val call = """[{"functionCall":{"name":"get_weather","args":{"city":"Paris"}}}]"""

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
    ContractCase.ContentFiltered -> applies(
      reply(text("Here is how"), "SAFETY"),
      sse(chunk(text("Here is how")), chunk("", Some("SAFETY")))
    )
  )
}
