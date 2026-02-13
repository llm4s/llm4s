package org.llm4s.llmconnect.provider

import java.io.{ BufferedReader, StringReader }
import org.llm4s.llmconnect.config.CohereConfig
import org.llm4s.llmconnect.model._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CohereClientSpec extends AnyFlatSpec with Matchers {

  private def createClient: CohereClient =
    new CohereClient(
      CohereConfig.fromValues(
        modelName = "command-r",
        apiKey = "test-key",
        baseUrl = "https://api.cohere.ai"
      )
    )

  "buildChatRequest" should "build request with conversation data" in {
    val client = createClient
    val conversation = Conversation(
      Seq(
        SystemMessage("You are a helpful assistant."),
        UserMessage("Hello"),
        AssistantMessage("Hi"),
        UserMessage("How are you?")
      )
    )

    val payload = client.buildChatRequest(
      conversation,
      CompletionOptions(maxTokens = Some(12))
    )

    payload("model").str shouldBe "command-r"
    payload("message").str shouldBe "How are you?"
    payload("chat_history").arr.size shouldBe 2
    payload("preamble").str should include("helpful assistant")
    payload("max_tokens").num.toInt shouldBe 12
  }

  "parseResponse" should "parse response and extract token usage" in {
    val client = createClient
    val json =
      """{
        |  "text": "Hello from Cohere",
        |  "generation_id": "gen-123",
        |  "meta": {
        |    "billed_units": {
        |      "input_tokens": 5,
        |      "output_tokens": 7
        |    }
        |  }
        |}""".stripMargin

    val result = client.parseResponse(json)

    result.isRight shouldBe true
    val completion = result.toOption.get
    completion.content shouldBe "Hello from Cohere"
    completion.id shouldBe "gen-123"
    completion.usage.get.promptTokens shouldBe 5
    completion.usage.get.completionTokens shouldBe 7
  }

  "processStreamingResponse" should "process and accumulate streamed chunks" in {
    val client = createClient
    val sse =
      """data: {"event_type":"stream-start","generation_id":"gen1"}
        |data: {"event_type":"text-generation","text":"Hello"}
        |data: {"event_type":"stream-end"}
        |""".stripMargin

    val reader = new BufferedReader(new StringReader(sse))
    var chunkCount = 0
    val result = client.processStreamingResponse(reader, _ => chunkCount += 1)

    result.isRight shouldBe true
    val completion = result.toOption.get
    completion.id shouldBe "gen1"
    completion.content shouldBe "Hello"
    chunkCount shouldBe 1
  }
}
