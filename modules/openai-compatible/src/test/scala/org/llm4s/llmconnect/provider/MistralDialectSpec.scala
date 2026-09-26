package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.MistralConfig
import org.llm4s.llmconnect.model._
import org.llm4s.model.ModelRegistryService
import org.llm4s.toolapi.{ Schema, ToolBuilder }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * What `MistralDialect` changes about the standard chat-completions format, and what it
 * deliberately leaves alone (#1132, #925).
 */
class MistralDialectSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val client =
    new MistralClient(MistralConfig("k", "mistral-small-latest", "http://localhost:1", 128000, 4096))

  "MistralDialect.encodeToolCallId" should "pass one of Mistral's own ids through unchanged" in {
    MistralDialect.encodeToolCallId("D681PevKs") shouldBe "D681PevKs"
  }

  it should "map any other id to nine letters or digits, the same way every time" in {
    Seq("call_0fypS1hVX", "toolu_01A09q90qw90lq917835lq9", "call-123", "", "abcdefghij").foreach { id =>
      withClue(id) {
        val encoded = MistralDialect.encodeToolCallId(id)
        (encoded should fullyMatch).regex("[a-zA-Z0-9]{9}")
        MistralDialect.encodeToolCallId(id) shouldBe encoded
      }
    }
    MistralDialect.encodeToolCallId("call_a") should not be MistralDialect.encodeToolCallId("call_b")
  }

  "a Mistral request" should "carry a foreign tool call and its answer under the same Mistral-format id" in {
    val body = client.createRequestBody(
      Conversation(
        Seq(
          UserMessage("Weather?"),
          AssistantMessage(None, List(ToolCall("call_0fypS1hVX", "get_weather", ujson.Obj("city" -> "Paris")))),
          ToolMessage("""{"temp":21}""", "call_0fypS1hVX")
        )
      ),
      CompletionOptions()
    )
    val callId   = body("messages")(1)("tool_calls")(0)("id").str
    val answerId = body("messages")(2)("tool_call_id").str

    (callId should fullyMatch).regex("[a-zA-Z0-9]{9}")
    answerId shouldBe callId
    body("messages")(1).obj.contains("content") shouldBe false
  }

  it should "send tools and a JSON-schema response format in the standard shape" in {
    val tool = ToolBuilder[Map[String, Any], String](
      "get_weather",
      "Weather for a city",
      Schema.`object`[Map[String, Any]]("args").withProperty(Schema.property("city", Schema.string("City")))
    ).withHandler(_ => Right("sunny"))
      .buildSafe()
      .fold(e => fail(e.formatted), identity)
    val schema = ujson.Obj("type" -> "object", "properties" -> ujson.Obj("a" -> ujson.Obj("type" -> "string")))

    val body = client.createRequestBody(
      Conversation(Seq(SystemMessage("Be brief."), UserMessage("hi"))),
      CompletionOptions(tools = Seq(tool), responseFormat = Some(ResponseFormat.JsonSchema(schema, "answer")))
    )

    body("messages")(0)("role").str shouldBe "system"
    body("tools")(0)("function")("name").str shouldBe "get_weather"
    body("response_format")("type").str shouldBe "json_schema"
    body("response_format")("json_schema")("name").str shouldBe "answer"
    body("response_format")("json_schema")("schema") shouldBe schema
  }

  it should "not send reasoning_effort, which only some Mistral models accept" in {
    val body = client.createRequestBody(
      Conversation(Seq(UserMessage("hi"))),
      CompletionOptions().withReasoning(ReasoningEffort.High)
    )
    body.obj.keySet should contain noneOf ("reasoning_effort", "thinking", "reasoning")
  }

  it should "leave out an assistant turn with neither text nor tool calls, which Mistral rejects" in {
    val body = client.createRequestBody(
      Conversation(Seq(UserMessage("a"), AssistantMessage(Some(""), Seq.empty), UserMessage("b"))),
      CompletionOptions()
    )
    body("messages").arr.map(_("role").str) shouldBe Seq("user", "user")
  }

  "a Mistral reply" should "read a reasoning model's content chunks as text and thinking" in {
    val completion = client.parseCompletion(
      ujson.read(
        """{"id":"r","created":1,"model":"magistral-medium-latest","choices":[{"message":{"role":"assistant",
          |"content":[{"type":"thinking","thinking":[{"type":"text","text":"Add them."}]},
          |{"type":"text","text":"4"}]}}],"usage":{"prompt_tokens":5,"completion_tokens":9,"total_tokens":14}}""".stripMargin
      )
    )

    completion.content shouldBe "4"
    completion.message.contentOpt shouldBe Some("4")
    completion.thinking shouldBe Some("Add them.")
    completion.usage shouldBe Some(TokenUsage(5, 9, 14))
  }

  it should "read a plain string reply with no thinking" in {
    val completion = client.parseCompletion(
      ujson.read("""{"id":"r","created":1,"model":"m","choices":[{"message":{"content":"plain"}}]}""")
    )
    completion.content shouldBe "plain"
    completion.thinking shouldBe None
  }

  it should "treat content made only of thinking as no text" in {
    MistralDialect.decodeContent(
      ujson.read("""[{"type":"thinking","thinking":[{"type":"text","text":"hmm"}]}]""")
    ) shouldBe None
    MistralDialect.thinking(ujson.read("""{"content":"plain"}""")) shouldBe None
    MistralDialect.thinking(ujson.read("""{"content":[{"type":"thinking","thinking":"flat"}]}""")) shouldBe
      Some("flat")
  }
}
