package org.llm4s.llmconnect.provider

import org.llm4s.testkit.{ Applicability, ContractCase, LocalProviderTestServer, RecordedReply }

/**
 * Ollama `/api/chat` replies for each RFC 0009 [[ContractCase]], for `assertModelCallContract`: one JSON object, or
 * NDJSON lines ending on `"done":true` with its `done_reason`. Hand-written in the documented shape. Ollama has no
 * content filter.
 */
object OllamaContractFixtures {

  private val Counts = """"prompt_eval_count":9,"eval_count":4"""

  private def message(content: String, calls: String = ""): String = {
    val toolCalls = if (calls.isEmpty) "" else s""","tool_calls":$calls"""
    s"""{"role":"assistant","content":${ujson.Str(content).render()}$toolCalls}"""
  }

  private def reply(msg: String, doneReason: String): String =
    s"""{"model":"llama3.1","created_at":"2026-11-21T00:00:00Z","message":$msg,"done":true,"done_reason":"$doneReason",$Counts}"""

  private def line(msg: String): String =
    s"""{"model":"llama3.1","created_at":"2026-11-21T00:00:00Z","message":$msg,"done":false}"""

  private def ndjson(lines: String*): String = lines.mkString("", "\n", "\n")

  private def applies(complete: String, stream: String): Applicability =
    Applicability.Applies(
      RecordedReply(
        LocalProviderTestServer.sendJsonResponse(_, 200, complete),
        LocalProviderTestServer.sendJsonResponse(_, 200, stream)
      )
    )

  private val call = """[{"function":{"name":"get_weather","arguments":{"city":"Paris"}}}]"""

  /** Every case, as Ollama answers it. */
  val replies: Map[ContractCase, Applicability] = Map(
    ContractCase.CleanText -> applies(
      reply(message("The answer is 4."), "stop"),
      ndjson(line(message("The answer")), line(message(" is 4.")), reply(message(""), "stop"))
    ),
    ContractCase.TextCutByLimit -> applies(
      reply(message("The answer is"), "length"),
      ndjson(line(message("The answer")), line(message(" is")), reply(message(""), "length"))
    ),
    ContractCase.ToolCall -> applies(
      reply(message("", call), "stop"),
      ndjson(line(message("", call)), reply(message(""), "stop"))
    ),
    ContractCase.ContentFiltered -> Applicability.NotApplicable("Ollama has no content filter")
  )
}
