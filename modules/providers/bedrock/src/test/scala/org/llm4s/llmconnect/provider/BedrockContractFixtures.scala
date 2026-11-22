package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.provider.BedrockTestSupport.{ eventFrame, exceptionFrame, sendEventStream, textDelta }
import org.llm4s.testkit.{ Applicability, ContractCase, LocalProviderTestServer, RecordedReply }

/**
 * Converse and ConverseStream replies for each RFC 0009 [[ContractCase]], for `assertModelCallContract`, in the shape
 * the Bedrock Runtime API documents: a reply's `stopReason`, and a stream's on `messageStop` before the `metadata`
 * event that carries the usage. Hand-written (no live capture yet). A guardrail that intervened stops the reply with
 * `guardrail_intervened`. A failure after the 200 status is an exception frame such as `modelStreamErrorException`.
 */
object BedrockContractFixtures {

  private val Usage = ujson.Obj("inputTokens" -> 9, "outputTokens" -> 4, "totalTokens" -> 13)

  private def reply(content: ujson.Value, stopReason: String): String =
    ujson
      .Obj(
        "output"     -> ujson.Obj("message" -> ujson.Obj("role" -> "assistant", "content" -> content)),
        "stopReason" -> stopReason,
        "usage"      -> Usage
      )
      .render()

  private def text(t: String): ujson.Value = ujson.Arr(ujson.Obj("text" -> t))

  private def stream(body: Seq[Array[Byte]], stopReason: String): Seq[Array[Byte]] =
    (eventFrame("messageStart", """{"role":"assistant"}""") +: body) ++ Seq(
      eventFrame("messageStop", ujson.Obj("stopReason" -> stopReason).render()),
      eventFrame("metadata", ujson.Obj("usage" -> Usage, "metrics" -> ujson.Obj("latencyMs" -> 42)).render())
    )

  private def textBlock(texts: String*): Seq[Array[Byte]] =
    texts.map(textDelta(_)) :+ eventFrame("contentBlockStop", """{"contentBlockIndex":0}""")

  private def applies(complete: String, frames: Seq[Array[Byte]]): Applicability =
    Applicability.Applies(
      RecordedReply(LocalProviderTestServer.sendJsonResponse(_, 200, complete), sendEventStream(_, frames))
    )

  private def streamOnly(frames: Seq[Array[Byte]]): Applicability =
    Applicability.Applies(RecordedReply.streamOnly(sendEventStream(_, frames)))

  private val toolUse = ujson.Arr(
    ujson.Obj(
      "toolUse" -> ujson.Obj(
        "toolUseId" -> "tooluse_1",
        "name"      -> "get_weather",
        "input"     -> ujson.Obj("city" -> "Paris")
      )
    )
  )

  private def toolUseOf(name: String, input: ujson.Value): ujson.Value =
    ujson.Arr(ujson.Obj("toolUse" -> ujson.Obj("toolUseId" -> "tooluse_1", "name" -> name, "input" -> input)))

  /** A streamed `toolUse` block: its start, then each `input` fragment. */
  private def toolBlock(name: String, fragments: String*): Seq[Array[Byte]] =
    eventFrame(
      "contentBlockStart",
      s"""{"contentBlockIndex":0,"start":{"toolUse":{"toolUseId":"tooluse_1","name":"$name"}}}"""
    ) +: fragments.map(f =>
      eventFrame(
        "contentBlockDelta",
        ujson.Obj("contentBlockIndex" -> 0, "delta" -> ujson.Obj("toolUse" -> ujson.Obj("input" -> f))).render()
      )
    ) :+ eventFrame("contentBlockStop", """{"contentBlockIndex":0}""")

  /** Every case, as Converse and ConverseStream answer it. */
  val replies: Map[ContractCase, Applicability] = Map(
    ContractCase.CleanText -> applies(
      reply(text("The answer is 4."), "end_turn"),
      stream(textBlock("The answer", " is 4."), "end_turn")
    ),
    ContractCase.TextCutByLimit -> applies(
      reply(text("The answer is"), "max_tokens"),
      stream(textBlock("The answer", " is"), "max_tokens")
    ),
    ContractCase.ToolCall -> applies(
      reply(toolUse, "tool_use"),
      stream(
        Seq(
          eventFrame(
            "contentBlockStart",
            """{"contentBlockIndex":0,"start":{"toolUse":{"toolUseId":"tooluse_1","name":"get_weather"}}}"""
          ),
          eventFrame("contentBlockDelta", """{"contentBlockIndex":0,"delta":{"toolUse":{"input":"{\"city\":"}}}"""),
          eventFrame("contentBlockDelta", """{"contentBlockIndex":0,"delta":{"toolUse":{"input":"\"Paris\"}"}}}"""),
          eventFrame("contentBlockStop", """{"contentBlockIndex":0}""")
        ),
        "tool_use"
      )
    ),
    // a tool with no parameters: `input` `{}`, and no input fragment on the stream
    ContractCase.NoArgumentToolCall -> applies(
      reply(toolUseOf("get_time", ujson.Obj()), "tool_use"),
      stream(toolBlock("get_time"), "tool_use")
    ),
    // `max_tokens` ended the reply while it made the call: no call runs
    ContractCase.ToolCallCutByLimit -> applies(
      reply(toolUseOf("get_weather", ujson.Obj("city" -> "Par")), "max_tokens"),
      stream(toolBlock("get_weather", """{"city":""", """"Par"""), "max_tokens")
    ),
    // an input that is not a JSON object, and streamed fragments that never make one
    ContractCase.MalformedArguments -> applies(
      reply(toolUseOf("get_weather", ujson.Str("{city: Paris")), "tool_use"),
      stream(toolBlock("get_weather", "{city: ", "Paris"), "tool_use")
    ),
    ContractCase.ContentFiltered -> applies(
      reply(text("Here is how"), "guardrail_intervened"),
      stream(textBlock("Here is how"), "guardrail_intervened")
    ),
    // text, then the connection closes before `messageStop`
    ContractCase.StreamEndsEarly -> streamOnly(
      eventFrame("messageStart", """{"role":"assistant"}""") +: textBlock("The answer", " is")
    ),
    ContractCase.ErrorEvent -> streamOnly(
      Seq(
        eventFrame("messageStart", """{"role":"assistant"}"""),
        textDelta("The answer"),
        exceptionFrame("modelStreamErrorException", ContractCase.ProviderErrorMessage)
      )
    )
  )
}
