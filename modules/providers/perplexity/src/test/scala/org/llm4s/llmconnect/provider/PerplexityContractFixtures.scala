package org.llm4s.llmconnect.provider

import org.llm4s.testkit.{ Applicability, ContractCase, LocalProviderTestServer, RecordedReply }

/**
 * Agent API (`/v1/agent`) replies for each RFC 0009 [[ContractCase]], for `assertModelCallContract`, built from
 * [[PerplexityFixtures]]: a response's `status` (`completed`, or `incomplete` at a limit), and a stream's
 * `response.output_text.delta` events ending on `response.completed`. A failure after the 200 status is a
 * `response.failed` event carrying an `error`.
 */
object PerplexityContractFixtures {

  import PerplexityFixtures.*

  private def applies(complete: String, stream: String): Applicability =
    Applicability.Applies(
      RecordedReply(
        LocalProviderTestServer.sendJsonResponse(_, 200, complete),
        LocalProviderTestServer.sendSseResponse(_, stream)
      )
    )

  private def streamOnly(stream: String): Applicability =
    Applicability.Applies(RecordedReply.streamOnly(LocalProviderTestServer.sendSseResponse(_, stream)))

  private def answered(text: String, status: String, deltas: String*): Applicability = {
    val done = response(s"[${textMessage(text)}]", status = status)
    applies(done, sseBody((created +: deltas.map(delta)) :+ completedEvent(done)*))
  }

  /** Every case, as the Agent API answers it. */
  val replies: Map[ContractCase, Applicability] = {
    val called = response(s"[${functionCall("call_1", "get_weather", """{"city":"Paris"}""")}]")
    // a function with no parameters: blank arguments
    val noArgs = response(s"[${functionCall("call_1", "get_time", "")}]")
    // `incomplete` at the output limit while the reply made the call
    val cut       = response(s"[${functionCall("call_1", "get_weather", """{"city":"Par""")}]", status = "incomplete")
    val malformed = response(s"[${functionCall("call_1", "get_weather", "{city: Paris")}]")
    Map(
      ContractCase.CleanText          -> answered("The answer is 4.", "completed", "The answer", " is 4."),
      ContractCase.TextCutByLimit     -> answered("The answer is", "incomplete", "The answer", " is"),
      ContractCase.ToolCall           -> applies(called, sseBody(created, completedEvent(called))),
      ContractCase.NoArgumentToolCall -> applies(noArgs, sseBody(created, completedEvent(noArgs))),
      ContractCase.ToolCallCutByLimit -> applies(cut, sseBody(created, completedEvent(cut))),
      ContractCase.MalformedArguments -> applies(malformed, sseBody(created, completedEvent(malformed))),
      ContractCase.ContentFiltered -> Applicability.NotApplicable(
        "the Agent API reports no content-filter status: a refused request is `failed`, an error"
      ),
      // text, then the connection closes before `response.completed`
      ContractCase.StreamEndsEarly -> streamOnly(sseBody(created, delta("The answer"), delta(" is"))),
      ContractCase.ErrorEvent -> streamOnly(
        sseBody(
          created,
          delta("The answer"),
          ujson
            .Obj(
              "type"            -> "response.failed",
              "sequence_number" -> 4,
              "response" -> ujson.Obj(
                "id"     -> "resp_s",
                "status" -> "failed",
                "error"  -> ujson.Obj("code" -> "server_error", "message" -> ContractCase.ProviderErrorMessage)
              )
            )
            .render()
        )
      )
    )
  }
}
