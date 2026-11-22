package org.llm4s.llmconnect.provider

import org.llm4s.testkit.{ Applicability, ContractCase, LocalProviderTestServer, RecordedReply }

/**
 * OpenAI's chat-completions replies for each RFC 0009 [[ContractCase]], for `assertModelCallContract`: the shapes
 * OpenAI, Azure OpenAI and Requesty send. A stream with `stream_options.include_usage` ends on a usage-only
 * event with no choices. Hand-written in the documented shape (no live capture yet); a stream ends with
 * `finish_reason`, then usage, then `[DONE]`. A failure after the 200 status is an event carrying an `error` object.
 */
object OpenAIContractFixtures {

  private val Usage = """"usage":{"prompt_tokens":9,"completion_tokens":4,"total_tokens":13}"""

  private def reply(message: String, finish: String): String =
    s"""{"id":"chatcmpl-1","object":"chat.completion","created":1700000000,"model":"test-model",
       |"choices":[{"index":0,"message":$message,"finish_reason":"$finish"}],$Usage}""".stripMargin

  private def event(delta: String, finish: String = "null"): String = {
    val reason = if (finish == "null") "null" else s""""$finish""""
    s"""data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1700000000,"model":"test-model","choices":[{"index":0,"delta":$delta,"finish_reason":$reason}]}"""
  }

  private val usageOnly =
    s"""data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1700000000,"model":"test-model","choices":[],$Usage}"""

  private def sse(events: String*): String = (events :+ usageOnly :+ "data: [DONE]").mkString("", "\n\n", "\n\n")

  private def applies(complete: String, stream: String): Applicability =
    Applicability.Applies(
      RecordedReply(
        LocalProviderTestServer.sendJsonResponse(_, 200, complete),
        LocalProviderTestServer.sendSseResponse(_, stream)
      )
    )

  private def streamOnly(stream: String): Applicability =
    Applicability.Applies(RecordedReply.streamOnly(LocalProviderTestServer.sendSseResponse(_, stream)))

  private def callWith(name: String, arguments: String): String =
    s"""{"id":"call_1","type":"function","function":{"name":"$name","arguments":${ujson.Str(arguments).render()}}}"""

  private val call = callWith("get_weather", """{"city":"Paris"}""")

  /** The first event of a streamed call: its id and name, arguments `""`. */
  private def callOpens(name: String): String =
    event(
      s"""{"role":"assistant","content":null,"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"$name","arguments":""}}]}"""
    )

  private def argumentDelta(fragment: String): String =
    event(s"""{"tool_calls":[{"index":0,"function":{"arguments":${ujson.Str(fragment).render()}}}]}""")

  /** Every case, as a chat-completions server answers it. */
  val replies: Map[ContractCase, Applicability] = Map(
    ContractCase.CleanText -> applies(
      reply("""{"role":"assistant","content":"The answer is 4."}""", "stop"),
      sse(
        event("""{"role":"assistant","content":"The answer"}"""),
        event("""{"content":" is 4."}"""),
        event("{}", "stop")
      )
    ),
    ContractCase.TextCutByLimit -> applies(
      reply("""{"role":"assistant","content":"The answer is"}""", "length"),
      sse(
        event("""{"role":"assistant","content":"The answer"}"""),
        event("""{"content":" is"}"""),
        event("{}", "length")
      )
    ),
    ContractCase.ToolCall -> applies(
      reply(s"""{"role":"assistant","content":null,"tool_calls":[$call]}""", "tool_calls"),
      sse(
        event(
          """{"role":"assistant","content":null,"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_weather","arguments":""}}]}"""
        ),
        event("""{"tool_calls":[{"index":0,"function":{"arguments":"{\"city\":"}}]}"""),
        event("""{"tool_calls":[{"index":0,"function":{"arguments":"\"Paris\"}"}}]}"""),
        event("{}", "tool_calls")
      )
    ),
    // a tool with no parameters: OpenAI sends "{}", streamed as one fragment after the opening ""
    ContractCase.NoArgumentToolCall -> applies(
      reply(s"""{"role":"assistant","content":null,"tool_calls":[${callWith("get_time", "{}")}]}""", "tool_calls"),
      sse(callOpens("get_time"), argumentDelta("{}"), event("{}", "tool_calls"))
    ),
    // the limit cut the call's arguments
    ContractCase.ToolCallCutByLimit -> applies(
      reply(
        s"""{"role":"assistant","content":null,"tool_calls":[${callWith("get_weather", """{"city":"Par""")}]}""",
        "length"
      ),
      sse(callOpens("get_weather"), argumentDelta("""{"city":"""), argumentDelta(""""Par"""), event("{}", "length"))
    ),
    // arguments that are not JSON, in a reply that otherwise ended cleanly
    ContractCase.MalformedArguments -> applies(
      reply(
        s"""{"role":"assistant","content":null,"tool_calls":[${callWith("get_weather", "{city: Paris")}]}""",
        "tool_calls"
      ),
      sse(callOpens("get_weather"), argumentDelta("{city: "), argumentDelta("Paris"), event("{}", "tool_calls"))
    ),
    ContractCase.ContentFiltered -> applies(
      reply("""{"role":"assistant","content":"Here is how"}""", "content_filter"),
      sse(
        event("""{"role":"assistant","content":"Here is how"}"""),
        event("{}", "content_filter")
      )
    ),
    // text, then the connection closes: no finish_reason, no usage, no [DONE]
    ContractCase.StreamEndsEarly -> streamOnly(
      Seq(event("""{"role":"assistant","content":"The answer"}"""), event("""{"content":" is"}"""))
        .mkString("", "\n\n", "\n\n")
    ),
    ContractCase.ErrorEvent -> streamOnly(
      Seq(
        event("""{"role":"assistant","content":"The answer"}"""),
        """data: {"error":{"message":""" + ujson.Str(ContractCase.ProviderErrorMessage).render() +
          ""","type":"server_error","param":null,"code":null}}"""
      ).mkString("", "\n\n", "\n\n")
    )
  )
}
