package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.provider.OpenAIResponsesFixtures.*
import org.llm4s.testkit.{ Applicability, ContractCase, LocalProviderTestServer, RecordedReply }

/**
 * The Responses API's replies for each RFC 0009 [[ContractCase]], for `assertModelCallContract`: a `response` object
 * for `complete`, an event stream for `streamComplete`. Hand-written in the documented shape (no live capture yet).
 * A stream ends on `response.completed` or `response.incomplete`, with no `[DONE]`; a failure after the 200 status is
 * an `error` event.
 */
object OpenAIResponsesContractFixtures:

  private def cutBy(reason: String): String = s""""incomplete_details":{"reason":"$reason"}"""

  private def applies(complete: String, stream: String): Applicability =
    Applicability.Applies(
      RecordedReply(
        LocalProviderTestServer.sendJsonResponse(_, 200, complete),
        LocalProviderTestServer.sendSseResponse(_, stream)
      )
    )

  private def streamOnly(stream: String): Applicability =
    Applicability.Applies(RecordedReply.streamOnly(LocalProviderTestServer.sendSseResponse(_, stream)))

  /** A call streamed: opened with no arguments, then one delta per fragment. */
  private def callEvents(name: String, fragments: String*): Seq[String] =
    callAdded("call_1", name, "fc_call_1", 0) +: fragments.map(argumentsDelta(_, "fc_call_1", 0))

  /** Every case, as the Responses API answers it. */
  val replies: Map[ContractCase, Applicability] = Map(
    ContractCase.CleanText -> applies(
      reply(message("The answer is 4.")),
      sse(
        created() +: Seq(textDelta("The answer"), textDelta(" is 4."), completed(reply(message("The answer is 4."))))*
      )
    ),
    ContractCase.TextCutByLimit -> applies(
      reply(message("The answer is"), status = "incomplete", extra = cutBy("max_output_tokens")),
      sse(
        created(),
        textDelta("The answer"),
        textDelta(" is"),
        incomplete(reply(message("The answer is"), status = "incomplete", extra = cutBy("max_output_tokens")))
      )
    ),
    ContractCase.ToolCall -> applies(
      reply(functionCall("call_1", "get_weather", """{"city":"Paris"}""")),
      sse(
        created() +: callEvents("get_weather", "{\"city\":", "\"Paris\"}") :+
          completed(reply(functionCall("call_1", "get_weather", """{"city":"Paris"}""")))*
      )
    ),
    // a tool with no parameters: the arguments are "{}", streamed as one fragment after the opening ""
    ContractCase.NoArgumentToolCall -> applies(
      reply(functionCall("call_1", "get_time", "{}")),
      sse(
        created() +: callEvents("get_time", "{}") :+ completed(reply(functionCall("call_1", "get_time", "{}")))*
      )
    ),
    // the limit cut the call's arguments
    ContractCase.ToolCallCutByLimit -> applies(
      reply(
        functionCall("call_1", "get_weather", """{"city":"Par"""),
        status = "incomplete",
        extra = cutBy("max_output_tokens")
      ),
      sse(
        created() +: callEvents("get_weather", "{\"city\":", "\"Par") :+
          incomplete(
            reply(
              functionCall("call_1", "get_weather", """{"city":"Par"""),
              status = "incomplete",
              extra = cutBy("max_output_tokens")
            )
          )*
      )
    ),
    // arguments that are not JSON, in a reply that otherwise ended cleanly
    ContractCase.MalformedArguments -> applies(
      reply(functionCall("call_1", "get_weather", "{city: Paris")),
      sse(
        created() +: callEvents("get_weather", "{city: ", "Paris") :+
          completed(reply(functionCall("call_1", "get_weather", "{city: Paris")))*
      )
    ),
    ContractCase.ContentFiltered -> applies(
      reply(message("Here is how"), status = "incomplete", extra = cutBy("content_filter")),
      sse(
        created(),
        textDelta("Here is how"),
        incomplete(reply(message("Here is how"), status = "incomplete", extra = cutBy("content_filter")))
      )
    ),
    // text, then the connection closes: no terminal event
    ContractCase.StreamEndsEarly -> streamOnly(sse(created(), textDelta("The answer"), textDelta(" is"))),
    ContractCase.ErrorEvent -> streamOnly(
      sse(created(), textDelta("The answer"), errorEvent("server_error", ContractCase.ProviderErrorMessage))
    )
  )
