package org.llm4s.llmconnect.provider

import org.llm4s.error.ProcessingError
import org.llm4s.llmconnect.config.OpenAICompatibleConfig
import org.llm4s.llmconnect.model._
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer.{ sendJsonResponse, withServer }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * RFC 0009, Part 3, on a non-streaming chat-completions reply: blank arguments are `{}` only after `tool_calls` or
 * `stop` (rule 2), and a reply the output limit cut runs no call, even one whose arguments parse (rule 3). The
 * contract suite covers the common cases; these are the edges it does not. The stream's rules are the accumulator's.
 */
class OpenAICompatibleToolCallRulesSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val client = new OpenAICompatibleClient(
    OpenAICompatibleClient.settings(OpenAICompatibleConfig("m", "http://localhost:1/v1", None)),
    OpenAICompatibleDialect.Standard
  )

  private def reply(arguments: String, finish: String): ujson.Value =
    ujson.read(
      s"""{"id":"c1","created":1,"model":"m","choices":[{"index":0,"message":{"role":"assistant","content":null,
         |"tool_calls":[{"id":"call_1","type":"function","function":{"name":"lookup"$arguments}}]}$finish}]}""".stripMargin
    )

  private def malformed(result: Result[?]): Boolean = result match {
    case Left(e: ProcessingError) => e.message.contains("malformed tool call") && e.message.contains("'lookup'")
    case _                        => false
  }

  private type Result[A] = org.llm4s.types.Result[A]

  "parseCompletion" should "read blank or absent arguments as {} after tool_calls or stop" in {
    for {
      arguments <- Seq("", ""","arguments":""""", ""","arguments":null""")
      finish    <- Seq("tool_calls", "stop")
    } withClue(s"$arguments, $finish: ") {
      client
        .parseCompletion(reply(arguments, s""","finish_reason":"$finish""""))
        .map(_.toolCalls.map(_.arguments)) shouldBe
        Right(Seq(ujson.Obj()))
    }
  }

  it should "fail blank arguments in a reply that ended any other way, or sent no reason" in {
    Seq(""","finish_reason":"brand_new"""", "").foreach { finish =>
      withClue(finish) {
        malformed(client.parseCompletion(reply(""","arguments":""""", finish))) shouldBe true
      }
    }
  }

  "complete" should "fail a reply the output limit cut while it made a call, even one whose arguments parse" in {
    val cut = ujson.write(reply(""","arguments":"{\"q\":\"x\"}"""", ""","finish_reason":"length""""))
    withServer("/chat/completions")(sendJsonResponse(_, 200, cut)) { baseUrl =>
      val at = new OpenAICompatibleClient(
        OpenAICompatibleClient.settings(OpenAICompatibleConfig("m", baseUrl, None)),
        OpenAICompatibleDialect.Standard
      )
      val result = at.complete(Conversation(Seq(UserMessage("Hi"))))
      malformed(result) shouldBe true
      result.left.map(_.message.contains("FinishReason.Length")) shouldBe Left(true)
    }
  }

  "OpenRouter's parseToolCalls" should "fail a call missing its id or name, and read its arguments by the shared rules" in {
    def calls(json: String) = OpenRouterDialect.parseToolCalls(ujson.read(json))
    calls("""[{"function":{"name":"lookup","arguments":"{}"}}]""").left.map(_.message.contains("OpenRouter")) shouldBe
      Left(true)
    calls("""[{"id":"c1","function":{"name":"lookup","arguments":""}}]""") shouldBe
      Right(Seq(ToolCall(ToolCallId("c1"), "lookup", ujson.Obj())))
    malformed(calls("""[{"id":"c1","function":{"name":"lookup","arguments":"{bad"}}]""")) shouldBe true
  }
}
