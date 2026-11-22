package org.llm4s.llmconnect.provider

import org.llm4s.testkit.{ Applicability, ContractCase, LocalProviderTestServer, RecordedReply }

/**
 * The Messages API's replies for each RFC 0009 [[ContractCase]], for `assertModelCallContract`, in the shape Anthropic
 * documents: a reply's `stop_reason`, and a stream's on `message_delta` before `message_stop`. Hand-written (no live
 * capture yet). `refusal` is the stop reason of a reply Anthropic's safety classifiers stopped. A stream ends with
 * `message_stop`, and reports a failure after the 200 status as an `error` event.
 */
object AnthropicContractFixtures {

  private def message(content: String, stopReason: String): String =
    s"""{"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-4-5","content":$content,
       |"stop_reason":"$stopReason","stop_sequence":null,"usage":{"input_tokens":9,"output_tokens":4}}""".stripMargin

  private def sse(events: (String, String)*): String =
    events.map { case (event, data) => s"event: $event\ndata: $data\n\n" }.mkString

  private val start = "message_start" -> (
    """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","content":[],""" +
      """"model":"claude-sonnet-4-5","stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":9,"output_tokens":0}}}"""
  )

  private def textBlock(texts: String*): Seq[(String, String)] =
    ("content_block_start" -> """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""") +:
      texts.map(t =>
        "content_block_delta" ->
          s"""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":${ujson.Str(t).render()}}}"""
      ) :+ ("content_block_stop" -> """{"type":"content_block_stop","index":0}""")

  private def end(stopReason: String): Seq[(String, String)] = Seq(
    "message_delta" -> (
      s"""{"type":"message_delta","delta":{"stop_reason":"$stopReason","stop_sequence":null},""" +
        """"usage":{"output_tokens":4}}"""
    ),
    "message_stop" -> """{"type":"message_stop"}"""
  )

  /** A streamed `tool_use` block: its start, with `input` `{}`, then each `partial_json` fragment. */
  private def toolBlock(name: String, fragments: String*): Seq[(String, String)] =
    ("content_block_start" ->
      s"""{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"$name","input":{}}}""") +:
      fragments.map(f =>
        "content_block_delta" ->
          s"""{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":${ujson.Str(f).render()}}}"""
      ) :+ ("content_block_stop" -> """{"type":"content_block_stop","index":0}""")

  private def toolUse(name: String, input: String): String =
    s"""[{"type":"tool_use","id":"toolu_1","name":"$name","input":$input}]"""

  private def applies(complete: String, stream: String): Applicability =
    Applicability.Applies(
      RecordedReply(
        LocalProviderTestServer.sendJsonResponse(_, 200, complete),
        LocalProviderTestServer.sendSseResponse(_, stream)
      )
    )

  private def streamOnly(stream: String): Applicability =
    Applicability.Applies(RecordedReply.streamOnly(LocalProviderTestServer.sendSseResponse(_, stream)))

  /** Every case, as the Messages API answers it. */
  val replies: Map[ContractCase, Applicability] = Map(
    ContractCase.CleanText -> applies(
      message("""[{"type":"text","text":"The answer is 4."}]""", "end_turn"),
      sse((start +: textBlock("The answer", " is 4.")) ++ end("end_turn")*)
    ),
    ContractCase.TextCutByLimit -> applies(
      message("""[{"type":"text","text":"The answer is"}]""", "max_tokens"),
      sse((start +: textBlock("The answer", " is")) ++ end("max_tokens")*)
    ),
    ContractCase.ToolCall -> applies(
      message(
        """[{"type":"tool_use","id":"toolu_1","name":"get_weather","input":{"city":"Paris"}}]""",
        "tool_use"
      ),
      sse(
        Seq(
          start,
          "content_block_start" ->
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"get_weather","input":{}}}""",
          "content_block_delta" ->
            """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"city\":"}}""",
          "content_block_delta" ->
            """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"\"Paris\"}"}}""",
          "content_block_stop" -> """{"type":"content_block_stop","index":0}"""
        ) ++ end("tool_use")*
      )
    ),
    // a tool with no parameters: `input` `{}`, and on the stream an empty `partial_json`
    ContractCase.NoArgumentToolCall -> applies(
      message(toolUse("get_time", "{}"), "tool_use"),
      sse((start +: toolBlock("get_time", "")) ++ end("tool_use")*)
    ),
    // `max_tokens` cut the call: whatever input arrived, no call runs
    ContractCase.ToolCallCutByLimit -> applies(
      message(toolUse("get_weather", """{"city":"Par"}"""), "max_tokens"),
      sse((start +: toolBlock("get_weather", """{"city":""", """"Par""")) ++ end("max_tokens")*)
    ),
    // an input that is not a JSON object, and streamed fragments that never make one
    ContractCase.MalformedArguments -> applies(
      message(toolUse("get_weather", ujson.Str("{city: Paris").render()), "tool_use"),
      sse((start +: toolBlock("get_weather", "{city: ", "Paris")) ++ end("tool_use")*)
    ),
    ContractCase.ContentFiltered -> applies(
      message("""[{"type":"text","text":"Here is how"}]""", "refusal"),
      sse((start +: textBlock("Here is how")) ++ end("refusal")*)
    ),
    // the text block, and no `message_delta` or `message_stop`
    ContractCase.StreamEndsEarly -> streamOnly(sse((start +: textBlock("The answer", " is"))*)),
    // Anthropic's documented mid-stream error: an `error` event, after the 200 status
    ContractCase.ErrorEvent -> streamOnly(
      sse(
        start +: textBlock("The answer") :+ ("error" -> (
          """{"type":"error","error":{"type":"overloaded_error","message":""" +
            ujson.Str(ContractCase.ProviderErrorMessage).render() + "}}"
        ))*
      )
    )
  )
}
