package org.llm4s.llmconnect.provider

import org.llm4s.testkit.{ Applicability, ContractCase, LocalProviderTestServer, RecordedReply }

/**
 * Agent API (`/v1/agent`) replies for each RFC 0009 [[ContractCase]], for `assertModelCallContract`, built from
 * [[PerplexityFixtures]]: a response's `status` (`completed`, or `incomplete` at a limit), and a stream's
 * `response.output_text.delta` events ending on `response.completed`.
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

  private def answered(text: String, status: String, deltas: String*): Applicability = {
    val done = response(s"[${textMessage(text)}]", status = status)
    applies(done, sseBody((created +: deltas.map(delta)) :+ completedEvent(done)*))
  }

  /** Every case, as the Agent API answers it. */
  val replies: Map[ContractCase, Applicability] = {
    val called = response(s"[${functionCall("call_1", "get_weather", """{"city":"Paris"}""")}]")
    Map(
      ContractCase.CleanText      -> answered("The answer is 4.", "completed", "The answer", " is 4."),
      ContractCase.TextCutByLimit -> answered("The answer is", "incomplete", "The answer", " is"),
      ContractCase.ToolCall       -> applies(called, sseBody(created, completedEvent(called))),
      ContractCase.ContentFiltered -> Applicability.NotApplicable(
        "the Agent API reports no content-filter status: a refused request is `failed`, an error"
      )
    )
  }
}
