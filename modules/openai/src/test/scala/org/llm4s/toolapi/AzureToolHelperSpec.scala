package org.llm4s.toolapi

import com.azure.ai.openai.models.{ ChatCompletionsFunctionToolDefinition, ChatCompletionsOptions, ChatRequestMessage }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.*

import scala.jdk.CollectionConverters.*

/**
 * `AzureToolHelper` converts a `ToolRegistry` into the Azure SDK's tool definitions.
 *
 * It moved to `llm4s-openai` with `OpenAIClient` (#1132), and it replaces the removed
 * `ToolRegistry.addToAzureOptions`, so it is the helper that migration note points users to.
 */
class AzureToolHelperSpec extends AnyFlatSpec with Matchers {

  case class Sum(result: Double)
  implicit val sumRW: ReadWriter[Sum] = macroRW

  private val addTool =
    ToolBuilder[Map[String, Any], Sum](
      "add",
      "Adds two numbers",
      Schema
        .`object`[Map[String, Any]]("Addition parameters")
        .withProperty(Schema.property("a", Schema.number("First number")))
        .withProperty(Schema.property("b", Schema.number("Second number")))
    ).withHandler(extractor => extractor.getDouble("a").flatMap(a => extractor.getDouble("b").map(b => Sum(a + b))))
      .buildSafe()
      .fold(e => fail(e.formatted), identity)

  "AzureToolHelper.convertToolRegistryToAzureTools" should "produce one function definition per tool" in {
    val tools = AzureToolHelper.convertToolRegistryToAzureTools(new ToolRegistry(Seq(addTool))).asScala

    tools should have size 1
    tools.head match
      case function: ChatCompletionsFunctionToolDefinition =>
        function.getFunction.getName shouldBe "add"
        function.getFunction.getDescription shouldBe "Adds two numbers"
      case other => fail(s"Expected a function tool definition, got $other")
  }

  it should "produce no definitions for an empty registry" in {
    AzureToolHelper.convertToolRegistryToAzureTools(new ToolRegistry(Seq.empty)).asScala shouldBe empty
  }

  "AzureToolHelper.addToolsToOptions" should "set the registry's tools on the options it returns" in {
    val options = new ChatCompletionsOptions(java.util.List.of[ChatRequestMessage]())
    val result  = AzureToolHelper.addToolsToOptions(new ToolRegistry(Seq(addTool)), options)

    result shouldBe theSameInstanceAs(options)
    result.getTools.asScala.map(_.asInstanceOf[ChatCompletionsFunctionToolDefinition].getFunction.getName) shouldBe
      Seq("add")
  }
}
