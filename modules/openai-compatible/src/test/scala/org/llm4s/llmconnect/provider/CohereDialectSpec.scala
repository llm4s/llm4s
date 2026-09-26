package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.{ CohereConfig, ContextWindowResolver }
import org.llm4s.llmconnect.model._
import org.llm4s.model.ModelRegistryService
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * What `CohereDialect` and `CohereConfig` change about the standard format, and how a base
 * URL configured for the native API reaches the Compatibility API (#1132, #925).
 */
class CohereDialectSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  private given ContextWindowResolver =
    ContextWindowResolver(org.llm4s.model.ModelRegistryTestSupport.defaultService())

  private val client =
    new CohereClient(CohereConfig("k", "command-a-03-2025", CohereConfig.DEFAULT_BASE_URL, 256000, 4096))

  private val schema =
    ujson.Obj("type" -> "object", "properties" -> ujson.Obj("title" -> ujson.Obj("type" -> "string")))

  "a Cohere request" should "send a JSON schema as a json_object response format carrying the schema" in {
    val body = client.createRequestBody(
      Conversation(Seq(UserMessage("A book, as JSON"))),
      CompletionOptions(responseFormat = Some(ResponseFormat.JsonSchema(schema, "book")))
    )
    body("response_format") shouldBe ujson.Obj("type" -> "json_object", "schema" -> schema)
  }

  it should "send plain JSON mode as a bare json_object" in {
    val body = client.createRequestBody(
      Conversation(Seq(UserMessage("JSON please"))),
      CompletionOptions(responseFormat = Some(ResponseFormat.Json))
    )
    body("response_format") shouldBe ujson.Obj("type" -> "json_object")
  }

  it should "send a tool round trip in the standard shape, and no reasoning fields" in {
    val body = client.createRequestBody(
      Conversation(
        Seq(
          SystemMessage("Today is April 30th"),
          UserMessage("Next flight from Miami to Seattle?"),
          AssistantMessage(None, List(ToolCall("get_flight_info0", "get_flight_info", ujson.Obj("a" -> 1)))),
          ToolMessage("Miami to Seattle, May 1st, 10 AM.", "get_flight_info0")
        )
      ),
      CompletionOptions().withReasoning(ReasoningEffort.High)
    )
    val messages = body("messages").arr
    messages.map(_("role").str) shouldBe Seq("developer", "user", "assistant", "tool")
    messages(2)("tool_calls")(0)("id").str shouldBe "get_flight_info0"
    messages(3)("tool_call_id").str shouldBe "get_flight_info0"
    body.obj.keySet should contain noneOf ("reasoning_effort", "thinking", "reasoning")
  }

  "CohereConfig.DEFAULT_BASE_URL" should "be the Compatibility API" in {
    CohereConfig.DEFAULT_BASE_URL shouldBe "https://api.cohere.ai/compatibility/v1"
  }

  "CohereConfig.compatibilityBaseUrl" should "map a native API root, with or without a version, to the Compatibility API" in {
    CohereConfig.compatibilityBaseUrl("https://api.cohere.com") shouldBe "https://api.cohere.com/compatibility/v1"
    CohereConfig.compatibilityBaseUrl("https://api.cohere.com/") shouldBe "https://api.cohere.com/compatibility/v1"
    CohereConfig.compatibilityBaseUrl("https://api.cohere.com/v2") shouldBe "https://api.cohere.com/compatibility/v1"
    CohereConfig.compatibilityBaseUrl("https://api.cohere.ai/v1") shouldBe "https://api.cohere.ai/compatibility/v1"
    CohereConfig.compatibilityBaseUrl("https://proxy.example/cohere") shouldBe
      "https://proxy.example/cohere/compatibility/v1"
  }

  it should "leave a Compatibility API base as it is, and be idempotent" in {
    CohereConfig.compatibilityBaseUrl(CohereConfig.DEFAULT_BASE_URL) shouldBe CohereConfig.DEFAULT_BASE_URL
    CohereConfig.compatibilityBaseUrl(s"${CohereConfig.DEFAULT_BASE_URL}/") shouldBe CohereConfig.DEFAULT_BASE_URL
    val once = CohereConfig.compatibilityBaseUrl("https://api.cohere.com/v2")
    CohereConfig.compatibilityBaseUrl(once) shouldBe once
  }

  "CohereConfig.fromValues" should "store the Compatibility API base, so endpointUrl says where requests go" in {
    val cfg = CohereConfig.fromValues("command-a-03-2025", "key", "https://api.cohere.com").value
    cfg.baseUrl shouldBe "https://api.cohere.com/compatibility/v1"
    cfg.endpointUrl shouldBe Some("https://api.cohere.com/compatibility/v1")
    CohereConfig.fromValues("command-a-03-2025", "key", CohereConfig.DEFAULT_BASE_URL).value.baseUrl shouldBe
      CohereConfig.DEFAULT_BASE_URL
  }

  it should "redact the API key" in {
    val shown = CohereConfig("co-secret-key", "command-r", CohereConfig.DEFAULT_BASE_URL, 128000, 4096).toString
    (shown should not).include("co-secret-key")
    shown should include("model=command-r")
  }
}
