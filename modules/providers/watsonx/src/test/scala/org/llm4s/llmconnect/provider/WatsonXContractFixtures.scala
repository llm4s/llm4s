package org.llm4s.llmconnect.provider

import com.sun.net.httpserver.HttpExchange
import org.llm4s.testkit.{ Applicability, ContractCase, LocalProviderTestServer, RecordedReply }

/**
 * watsonx.ai `/ml/v1/text/chat` and `/ml/v1/text/chat_stream` replies for each RFC 0009 [[ContractCase]], for
 * `assertModelCallContract`: chat-completions bodies, and a stream of `id`/`event: message`/`data:` frames with
 * cumulative usage on each. Hand-written in the documented shape; the client was never run against the live service.
 * A failure after the 200 status is an `event: error` frame whose `data:` holds IBM's error, as IBM's SDK reads it.
 */
object WatsonXContractFixtures {

  private val Usage = """"usage":{"prompt_tokens":9,"completion_tokens":4,"total_tokens":13}"""

  private def reply(message: String, finish: String): String =
    s"""{"id":"chat-1","model_id":"ibm/granite-13b-instruct-v2","choices":[{"index":0,"message":$message,"finish_reason":"$finish"}],$Usage}"""

  private def event(delta: String, finish: String = "null"): String = {
    val reason = if (finish == "null") "null" else s""""$finish""""
    s"""id: 1\nevent: message\ndata: {"id":"chat-1","choices":[{"index":0,"delta":$delta,"finish_reason":$reason}],$Usage}\n\n"""
  }

  private val call =
    """{"id":"chatcmpl-tool-1","type":"function","function":{"name":"get_weather","arguments":"{\"city\":\"Paris\"}"}}"""

  /** Every case, as watsonx.ai answers it; `route` wraps each reply so the IAM token endpoint still answers. */
  def replies(route: (HttpExchange => Unit) => HttpExchange => Unit): Map[ContractCase, Applicability] = {
    def applies(complete: String, stream: String): Applicability =
      Applicability.Applies(
        RecordedReply(
          route(LocalProviderTestServer.sendJsonResponse(_, 200, complete)),
          route(LocalProviderTestServer.sendSseResponse(_, stream))
        )
      )
    def streamOnly(stream: String): Applicability =
      Applicability.Applies(RecordedReply.streamOnly(route(LocalProviderTestServer.sendSseResponse(_, stream))))
    Map(
      ContractCase.CleanText -> applies(
        reply("""{"role":"assistant","content":"The answer is 4."}""", "stop"),
        event("""{"role":"assistant","content":"The answer"}""") + event("""{"content":" is 4."}""", "stop")
      ),
      ContractCase.TextCutByLimit -> applies(
        reply("""{"role":"assistant","content":"The answer is"}""", "length"),
        event("""{"role":"assistant","content":"The answer"}""") + event("""{"content":" is"}""", "length")
      ),
      ContractCase.ToolCall -> applies(
        reply(s"""{"role":"assistant","tool_calls":[$call]}""", "tool_calls"),
        event(
          """{"role":"assistant","tool_calls":[{"index":0,"id":"chatcmpl-tool-1","type":"function","function":{"name":"get_weather","arguments":"{\"city\":"}}]}"""
        ) +
          event("""{"tool_calls":[{"index":0,"function":{"arguments":"\"Paris\"}"}}]}""") +
          event("{}", "tool_calls")
      ),
      ContractCase.ContentFiltered -> Applicability.NotApplicable(
        "watsonx.ai documents no content-filter finish_reason; its guardrails are not wired into llm4s"
      ),
      // text, then the connection closes before a finish_reason
      ContractCase.StreamEndsEarly -> streamOnly(
        event("""{"role":"assistant","content":"The answer"}""") + event("""{"content":" is"}""")
      ),
      ContractCase.ErrorEvent -> streamOnly(
        event("""{"role":"assistant","content":"The answer"}""") +
          "id: 2\nevent: error\ndata: " +
          ujson
            .Obj(
              "errors" -> ujson.Arr(
                ujson.Obj("code" -> "internal_error", "message" -> ContractCase.ProviderErrorMessage)
              ),
              "status_code" -> 500
            )
            .render() + "\n\n"
      )
    )
  }
}
