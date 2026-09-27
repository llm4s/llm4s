package org.llm4s.toolapi

import com.openai.models.chat.completions.{ ChatCompletionCreateParams, ChatCompletionUserMessageParam }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.*

import scala.jdk.CollectionConverters.*

/**
 * `OpenAIToolHelper` converts a `ToolRegistry` into `openai-java`'s tool definitions.
 *
 * It replaced `AzureToolHelper` when `llm4s-openai` moved off the deprecated Azure SDK (#1132),
 * so it is the helper the migration note points users to. These are `AzureToolHelperSpec`'s
 * three cases, asserting the same things of the new SDK's types.
 */
class OpenAIToolHelperSpec extends AnyFlatSpec with Matchers {

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

  "OpenAIToolHelper.convertToolRegistryToOpenAITools" should "produce one function definition per tool" in {
    val tools = OpenAIToolHelper.convertToolRegistryToOpenAITools(new ToolRegistry(Seq(addTool))).asScala

    tools should have size 1
    tools.head.isFunction shouldBe true
    val function = tools.head.asFunction().function()
    function.name() shouldBe "add"
    function.description().get() shouldBe "Adds two numbers"
    function.parameters().get()._additionalProperties().asScala.keySet should contain("properties")
  }

  it should "produce no definitions for an empty registry" in {
    OpenAIToolHelper.convertToolRegistryToOpenAITools(new ToolRegistry(Seq.empty)).asScala shouldBe empty
  }

  "OpenAIToolHelper.addToolsToParams" should "set the registry's tools on the builder it returns" in {
    val builder = ChatCompletionCreateParams
      .builder()
      .model("gpt-4o")
      .addMessage(ChatCompletionUserMessageParam.builder().content("hi").build())
    val result = OpenAIToolHelper.addToolsToParams(new ToolRegistry(Seq(addTool)), builder)

    result shouldBe theSameInstanceAs(builder)
    result.build().tools().get().asScala.map(_.asFunction().function().name()) shouldBe Seq("add")
  }
}
