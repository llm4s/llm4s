package org.llm4s.llmconnect.provider

import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.config.CohereConfig
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, SystemMessage, UserMessage }
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolFunction }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default._

/**
 * Tests for CohereClient request validation.
 */
class CohereClientValidationTest extends AnyFlatSpec with Matchers {

  case class TestResult(message: String)
  implicit val testResultRW: ReadWriter[TestResult] = macroRW[TestResult]

  private def createTestTool(): ToolFunction[Map[String, Any], TestResult] = {
    val schema = Schema
      .`object`[Map[String, Any]]("Test parameters")
      .withProperty(Schema.property("input", Schema.string("Input value")))

    ToolBuilder[Map[String, Any], TestResult](
      "test_tool",
      "Tool for Cohere validation tests",
      schema
    ).withHandler(extractor => extractor.getString("input").map(v => TestResult(v))).build()
  }

  private def createTestConfig: CohereConfig =
    CohereConfig.fromValues(
      modelName = "command-r",
      apiKey = "test-api-key-for-validation-testing",
      baseUrl = "https://example.invalid"
    )

  "CohereClient" should "reject conversations without a final user message" in {
    val client       = new CohereClient(createTestConfig)
    val conversation = Conversation(Seq(SystemMessage("System only")))

    val result = client.complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[ValidationError]
    result.left.toOption.get.message should include("user prompt")
  }

  it should "reject tool usage until tool calling is supported" in {
    val client       = new CohereClient(createTestConfig)
    val conversation = Conversation(Seq(UserMessage("Hello")))
    val options      = CompletionOptions(tools = Seq(createTestTool()))

    val result = client.complete(conversation, options)

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[ValidationError]
    result.left.toOption.get.message should include("tool calling is not supported")
  }
}
