package org.llm4s.toolapi

import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default._

// Result types defined at module level to avoid Scala 2.13 macroRW issues (SI-7567)
case class InventoryResult(items: List[String], count: Int)
case class StatusResult(health: Int, mana: Int)
case class TimeResult(hour: Int, minute: Int, dayOfWeek: String)
case class SimpleResult(message: String)
case class TestResult(value: String)

object ZeroParameterToolTest {
  implicit val inventoryResultRW: ReadWriter[InventoryResult] = macroRW[InventoryResult]
  implicit val statusResultRW: ReadWriter[StatusResult]       = macroRW[StatusResult]
  implicit val timeResultRW: ReadWriter[TimeResult]           = macroRW[TimeResult]
  implicit val simpleResultRW: ReadWriter[SimpleResult]       = macroRW[SimpleResult]
  implicit val testResultRW: ReadWriter[TestResult]           = macroRW[TestResult]
}

class ZeroParameterToolTest extends AnyFlatSpec with Matchers {
  import ZeroParameterToolTest._

  /**
   * Create a zero-parameter tool (like a game inventory system)
   * that takes no arguments and returns the current inventory
   */
  def createZeroParameterTool(
    name: String = "list_inventory"
  ): Result[ToolFunction[Map[String, Any], InventoryResult]] = {
    // Schema with no properties = zero parameters
    val schema = Schema
      .`object`[Map[String, Any]]("Tool with no parameters")
    // Note: No .withProperty() calls - this tool takes no parameters

    ToolBuilder[Map[String, Any], InventoryResult](
      name,
      "Returns the current inventory items",
      schema
    ).withHandler { _ =>
      // Handler doesn't extract any parameters
      // Just returns the inventory
      Right(InventoryResult(List("sword", "shield", "potion"), 3))
    }.buildSafe()
  }

  "Zero-parameter tool" should "work with empty object arguments" in {
    createZeroParameterTool().fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => {
        val result = tool.execute(ujson.Obj())
        (result should be).a(Symbol("right"))
        val jsonResult = result.fold(e => fail(s"Expected Right but got Left: ${e.getMessage}"), identity)
        val inventory  = read[InventoryResult](jsonResult)
        inventory.items should contain theSameElementsAs List("sword", "shield", "potion")
        inventory.count shouldBe 3
      }
    )
  }

  "Zero-parameter tool" should "accept null arguments and treat them as empty object" in {
    createZeroParameterTool().fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => {
        val result = tool.execute(ujson.Null)
        // For zero-parameter tools, null should be treated as {}
        (result should be).a(Symbol("right"))
        val jsonResult = result.fold(e => fail(s"Expected Right but got Left: ${e.getMessage}"), identity)
        val inventory  = read[InventoryResult](jsonResult)
        inventory.items should contain theSameElementsAs List("sword", "shield", "potion")
        inventory.count shouldBe 3
      }
    )
  }

  "Zero-parameter tool schema" should "generate correct JSON schema" in {
    createZeroParameterTool().fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => {
        val openAITool  = tool.toOpenAITool(strict = true)
        val functionObj = openAITool("function").obj
        functionObj("name").str shouldBe "list_inventory"
        functionObj("description").str shouldBe "Returns the current inventory items"

        val parameters = functionObj("parameters").obj
        parameters("type").str shouldBe "object"
        parameters("properties").obj shouldBe empty
        parameters("required").arr shouldBe empty // No required parameters
      }
    )
  }

  "Multiple zero-parameter tools" should "work correctly" in {
    // Define a few different zero-parameter tools via for-comprehension
    val result = for {
      statusTool <- ToolBuilder[Map[String, Any], StatusResult](
        "get_status",
        "Returns the player's current status",
        Schema.`object`[Map[String, Any]]("No parameters required")
      ).withHandler(_ => Right(StatusResult(health = 100, mana = 50))).buildSafe()

      timeTool <- ToolBuilder[Map[String, Any], TimeResult](
        "get_game_time",
        "Returns the current in-game time",
        Schema.`object`[Map[String, Any]]("No parameters")
      ).withHandler(_ => Right(TimeResult(hour = 14, minute = 30, dayOfWeek = "Monday"))).buildSafe()
    } yield {
      // Both should work with empty objects
      (statusTool.execute(ujson.Obj()) should be).a(Symbol("right"))
      (timeTool.execute(ujson.Obj()) should be).a(Symbol("right"))
    }
    result.left.foreach(e => fail(s"Tool creation failed: ${e.formatted}"))
  }

  "Zero-parameter tool handler" should "not attempt to extract any parameters" in {
    ToolBuilder[Map[String, Any], SimpleResult](
      "simple_tool",
      "A simple tool with no parameters",
      Schema.`object`[Map[String, Any]]("No parameters needed")
    ).withHandler { _ =>
      // The handler should not try to extract anything
      // Just return a result directly
      Right(SimpleResult("Success"))
    }.buildSafe()
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tool => {
          val result = tool.execute(ujson.Obj())
          (result should be).a(Symbol("right"))
          val output =
            read[SimpleResult](result.fold(e => fail(s"Expected Right but got Left: ${e.getMessage}"), identity))
          output.message shouldBe "Success"
        }
      )
  }

  "Tool with required parameters" should "still reject null arguments" in {
    ToolBuilder[Map[String, Any], TestResult](
      "tool_with_params",
      "A tool that requires parameters",
      Schema
        .`object`[Map[String, Any]]("Required parameters")
        .withProperty(Schema.property("name", Schema.string("Required name parameter")))
    ).withHandler(extractor => extractor.getString("name").map(name => TestResult(name)))
      .buildSafe()
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tool => {
          val result = tool.execute(ujson.Null)
          // Should fail with null arguments error since this tool has required parameters
          (result should be).a(Symbol("left"))
          val error   = result.fold(identity, v => fail(s"Expected Left but got Right: $v"))
          val message = error.getFormattedMessage
          message should include("tool_with_params")
          message should include("null arguments")
        }
      )
  }
}
