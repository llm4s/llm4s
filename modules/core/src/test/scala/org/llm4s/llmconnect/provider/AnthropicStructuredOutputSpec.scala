package org.llm4s.llmconnect.provider

import com.anthropic.core.ObjectMappers
import com.anthropic.models.messages.MessageCreateParams
import org.llm4s.llmconnect.config.AnthropicConfig
import org.llm4s.llmconnect.model._
import org.llm4s.model.ModelRegistryService
import org.llm4s.toolapi.Schema
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AnthropicStructuredOutputSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val testConfig = AnthropicConfig(
    apiKey            = "sk-ant-dummy",
    model             = "claude-3-5-sonnet-20241022",
    baseUrl           = "https://api.anthropic.com",
    contextWindow     = 200000,
    reserveCompletion = 4096
  )

  private val client = new AnthropicClient(testConfig)

  val invoiceSchema = Schema
    .`object`[AnyRef]("Invoice schema")
    .withRequiredField("vendor", Schema.string("Vendor name"))
    .withRequiredField("amount", Schema.number("Amount"))

  private def buildParamsJson(conversation: Conversation, opts: CompletionOptions = CompletionOptions()): String = {
    val builder = MessageCreateParams
      .builder()
      .model("claude-3-5-sonnet-20241022")
      .maxTokens(1024)
    client.addMessagesToParams(conversation, builder, opts)
    val params = builder.build()
    ObjectMappers.jsonMapper().writeValueAsString(params._body())
  }

  // ---- appendJsonInstruction ----

  "appendJsonInstruction" should "return the system prompt unchanged when responseFormat is None" in {
    val opts   = CompletionOptions()
    val result = client.appendJsonInstruction("Be helpful.", opts)
    result shouldBe "Be helpful."
  }

  it should "append JSON-only instruction for ResponseFormat.Json" in {
    val opts   = CompletionOptions().withResponseFormat(ResponseFormat.Json)
    val result = client.appendJsonInstruction("Be helpful.", opts)
    result should include("Be helpful.")
    result should include("valid JSON only")
  }

  it should "append schema-specific instruction for ResponseFormat.JsonSchema" in {
    val schema = ResponseFormat.JsonSchema(invoiceSchema.toJsonSchema(strict = true))
    val opts   = CompletionOptions().withResponseFormat(schema)
    val result = client.appendJsonInstruction("Be helpful.", opts)
    result should include("Be helpful.")
    result should include("conforming exactly to this schema")
    result should include("vendor")
  }

  it should "embed the schema JSON in the instruction" in {
    val schema = ResponseFormat.JsonSchema(invoiceSchema.toJsonSchema(strict = true))
    val opts   = CompletionOptions().withResponseFormat(schema)
    val result = client.appendJsonInstruction("sys", opts)
    result should include("\"vendor\"")
  }

  // ---- addMessagesToParams via JSON serialisation ----

  "addMessagesToParams" should "inject default system message when conversation has no SystemMessage" in {
    val json = buildParamsJson(Conversation(Seq(UserMessage("hi"))))
    json should include("You are Claude, a helpful AI assistant.")
  }

  it should "use the explicit system message from conversation" in {
    val json = buildParamsJson(Conversation(Seq(SystemMessage("Custom instructions."), UserMessage("hi"))))
    json should include("Custom instructions.")
    (json should not).include("You are Claude, a helpful AI assistant.")
  }

  it should "append JSON instruction to explicit system message when ResponseFormat.Json is set" in {
    val opts = CompletionOptions().withResponseFormat(ResponseFormat.Json)
    val json = buildParamsJson(Conversation(Seq(SystemMessage("Custom instructions."), UserMessage("hi"))), opts)
    json should include("Custom instructions.")
    json should include("valid JSON only")
  }

  it should "append JSON instruction to default system message when no explicit SystemMessage and ResponseFormat.Json" in {
    val opts = CompletionOptions().withResponseFormat(ResponseFormat.Json)
    val json = buildParamsJson(Conversation(Seq(UserMessage("hi"))), opts)
    json should include("Claude")
    json should include("valid JSON only")
  }

  it should "not modify system message when responseFormat is None" in {
    val json = buildParamsJson(Conversation(Seq(SystemMessage("Custom."), UserMessage("hi"))))
    json should include("Custom.")
    (json should not).include("valid JSON only")
    (json should not).include("conforming exactly")
  }
}
