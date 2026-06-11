package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.Schema
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import com.anthropic.models.messages.MessageCreateParams

class AnthropicStructuredOutputSpec extends AnyFlatSpec with Matchers {

  val dummyConfig: AnthropicConfig = AnthropicConfig.fromValues(
    "claude-3-5-sonnet-20241022",
    "sk-ant-dummy",
    "https://api.anthropic.com"
  )
  val client: AnthropicClient = new AnthropicClient(dummyConfig)

  val invoiceSchema = Schema
    .`object`[AnyRef]("Invoice schema")
    .withRequiredField("vendor", Schema.string("Vendor name"))
    .withRequiredField("amount", Schema.number("Amount"))

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

  // ---- addMessagesToParams ----

  def freshBuilder(): MessageCreateParams.Builder =
    MessageCreateParams.builder().model("claude-3-5-sonnet-20241022").maxTokens(1024)

  "addMessagesToParams" should "inject default system message when conversation has no system message" in {
    val conv    = Conversation(Seq(UserMessage("hi")))
    val builder = freshBuilder()
    client.addMessagesToParams(conv, builder)
    val params  = builder.build()
    params.system().isPresent shouldBe true
    params.system().get().asText().get().text() should include("Claude")
  }

  it should "use the explicit system message from conversation" in {
    val conv    = Conversation(Seq(SystemMessage("Custom instructions."), UserMessage("hi")))
    val builder = freshBuilder()
    client.addMessagesToParams(conv, builder)
    val params  = builder.build()
    params.system().get().asText().get().text() shouldBe "Custom instructions."
  }

  it should "append JSON instruction to explicit system message when ResponseFormat.Json is set" in {
    val opts    = CompletionOptions().withResponseFormat(ResponseFormat.Json)
    val conv    = Conversation(Seq(SystemMessage("Custom instructions."), UserMessage("hi")))
    val builder = freshBuilder()
    client.addMessagesToParams(conv, builder, opts)
    val params  = builder.build()
    val system  = params.system().get().asText().get().text()
    system should include("Custom instructions.")
    system should include("valid JSON only")
  }

  it should "append JSON instruction to default system message when ResponseFormat.Json is set and no system message" in {
    val opts    = CompletionOptions().withResponseFormat(ResponseFormat.Json)
    val conv    = Conversation(Seq(UserMessage("hi")))
    val builder = freshBuilder()
    client.addMessagesToParams(conv, builder, opts)
    val params  = builder.build()
    val system  = params.system().get().asText().get().text()
    system should include("Claude")
    system should include("valid JSON only")
  }

  it should "not modify system message when responseFormat is None" in {
    val opts    = CompletionOptions()
    val conv    = Conversation(Seq(SystemMessage("Custom."), UserMessage("hi")))
    val builder = freshBuilder()
    client.addMessagesToParams(conv, builder, opts)
    val params  = builder.build()
    params.system().get().asText().get().text() shouldBe "Custom."
  }
}
