package org.llm4s.toolapi

import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach
import upickle.default._
import scala.collection.mutable

/**
 * Integration test for game inventory tools, verifying:
 * 1. Zero-parameter tools (list_inventory) accept null arguments
 * 2. Required-parameter tools (add/remove) properly validate arguments
 * 3. Tool schemas are correctly formatted for LLM consumption
 * 4. Parameter handling works correctly in both success and error cases
 *
 * This test replicates the structure of the szork game tools to test tool API functionality
 * without requiring external dependencies.
 */
class GameToolsIntegrationTest extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  // ========== Test Game Tools Implementation ==========
  // Replicating the structure from szork/src/main/scala/org/llm4s/szork/game/TestGameTools.scala

  object TestGameTools {
    // Mutable inventory storage
    private val playerInventory = mutable.ListBuffer[String]()

    // Define result types
    case class InventoryListResult(
      inventory: List[String],
      count: Int,
      message: String
    )

    case class InventoryModifyResult(
      success: Boolean,
      message: String,
      item: String,
      inventory: List[String]
    )

    // Provide implicit reader/writers
    implicit val inventoryListResultRW: ReadWriter[InventoryListResult]     = macroRW
    implicit val inventoryModifyResultRW: ReadWriter[InventoryModifyResult] = macroRW

    /** Tool to list the player's current inventory (zero-parameter tool) */
    val listInventorySchema: ObjectSchema[Map[String, Any]] = Schema
      .`object`[Map[String, Any]]("Simple returns the list of items in your inventory")

    def listInventoryHandler(params: SafeParameterExtractor): Either[String, InventoryListResult] = {
      val _ = params // No parameters needed for list operation

      val result = if (playerInventory.isEmpty) {
        InventoryListResult(
          inventory = List.empty,
          count = 0,
          message = "Your inventory is empty"
        )
      } else {
        InventoryListResult(
          inventory = playerInventory.toList,
          count = playerInventory.size,
          message = s"You have ${playerInventory.size} item(s) in your inventory"
        )
      }

      Right(result)
    }

    val listInventoryTool: Result[ToolFunction[Map[String, Any], InventoryListResult]] =
      ToolBuilder[Map[String, Any], InventoryListResult](
        "list_inventory",
        "List all items currently in the player's inventory",
        listInventorySchema
      ).withHandler(listInventoryHandler).buildSafe()

    /** Tool to add an item to the player's inventory (required parameter: item) */
    val addInventorySchema: ObjectSchema[Map[String, Any]] = Schema
      .`object`[Map[String, Any]]("Add inventory item parameters")
      .withProperty(
        Schema.property(
          "item",
          Schema.string("The name of the item to add to inventory")
        )
      )

    def addInventoryHandler(params: SafeParameterExtractor): Either[String, InventoryModifyResult] =
      for {
        item <- params.getString("item")
      } yield
        if (playerInventory.contains(item)) {
          InventoryModifyResult(
            success = false,
            message = s"You already have '$item' in your inventory",
            item = item,
            inventory = playerInventory.toList
          )
        } else {
          playerInventory += item
          InventoryModifyResult(
            success = true,
            message = s"Added '$item' to your inventory",
            item = item,
            inventory = playerInventory.toList
          )
        }

    val addInventoryItemTool: Result[ToolFunction[Map[String, Any], InventoryModifyResult]] =
      ToolBuilder[Map[String, Any], InventoryModifyResult](
        "add_inventory_item",
        "Add a new item to the player's inventory",
        addInventorySchema
      ).withHandler(addInventoryHandler).buildSafe()

    /** Tool to remove an item from the player's inventory (required parameter: item) */
    val removeInventorySchema: ObjectSchema[Map[String, Any]] = Schema
      .`object`[Map[String, Any]]("Remove inventory item parameters")
      .withProperty(
        Schema.property(
          "item",
          Schema.string("The name of the item to remove from inventory")
        )
      )

    def removeInventoryHandler(params: SafeParameterExtractor): Either[String, InventoryModifyResult] =
      for {
        item <- params.getString("item")
      } yield
        if (playerInventory.contains(item)) {
          playerInventory -= item
          InventoryModifyResult(
            success = true,
            message = s"Removed '$item' from your inventory",
            item = item,
            inventory = playerInventory.toList
          )
        } else {
          InventoryModifyResult(
            success = false,
            message = s"You don't have '$item' in your inventory",
            item = item,
            inventory = playerInventory.toList
          )
        }

    val removeInventoryItemTool: Result[ToolFunction[Map[String, Any], InventoryModifyResult]] =
      ToolBuilder[Map[String, Any], InventoryModifyResult](
        "remove_inventory_item",
        "Remove an item from the player's inventory",
        removeInventorySchema
      ).withHandler(removeInventoryHandler).buildSafe()

    /** Get all game tools for the ToolRegistry */
    def allTools: Result[Seq[ToolFunction[_, _]]] = for {
      list   <- listInventoryTool
      add    <- addInventoryItemTool
      remove <- removeInventoryItemTool
    } yield Seq(list, add, remove)

    /** Clear the inventory (useful for test cleanup) */
    def clearInventory(): Unit =
      playerInventory.clear()

    /** Set inventory state (for test setup) */
    def setInventory(items: List[String]): Unit = {
      playerInventory.clear()
      playerInventory ++= items
    }
  }

  override def beforeEach(): Unit =
    // Clear inventory before each test
    TestGameTools.clearInventory()

  override def afterEach(): Unit =
    // Clean up after each test
    TestGameTools.clearInventory()

  // ========== Schema Validation Tests ==========

  "list_inventory tool schema" should "have no required parameters" in {
    TestGameTools.listInventoryTool.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => {
        val schema = tool.schema

        schema shouldBe a[ObjectSchema[_]]
        val objSchema = schema.asInstanceOf[ObjectSchema[_]]

        // Should have no required properties
        objSchema.properties.filter(_.required) shouldBe empty
      }
    )
  }

  "add_inventory_item tool schema" should "have 'item' as required parameter" in {
    TestGameTools.addInventoryItemTool.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => {
        val schema = tool.schema

        schema shouldBe a[ObjectSchema[_]]
        val objSchema = schema.asInstanceOf[ObjectSchema[_]]

        // Should have exactly one required property: 'item'
        val requiredProps = objSchema.properties.filter(_.required)
        requiredProps should have size 1
        requiredProps.head.name shouldBe "item"
      }
    )
  }

  "remove_inventory_item tool schema" should "have 'item' as required parameter" in {
    TestGameTools.removeInventoryItemTool.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => {
        val schema = tool.schema

        schema shouldBe a[ObjectSchema[_]]
        val objSchema = schema.asInstanceOf[ObjectSchema[_]]

        // Should have exactly one required property: 'item'
        val requiredProps = objSchema.properties.filter(_.required)
        requiredProps should have size 1
        requiredProps.head.name shouldBe "item"
      }
    )
  }

  "list_inventory OpenAI schema" should "have empty required array" in {
    TestGameTools.listInventoryTool.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => {
        val openAITool = tool.toOpenAITool(strict = true)

        openAITool("type").str shouldBe "function"
        val function = openAITool("function").obj
        function("name").str shouldBe "list_inventory"

        val parameters = function("parameters").obj
        val required   = parameters("required").arr

        // Zero-parameter tool should have empty required array
        required shouldBe empty
      }
    )
  }

  "add_inventory_item OpenAI schema" should "have 'item' in required array" in {
    TestGameTools.addInventoryItemTool.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => {
        val openAITool = tool.toOpenAITool(strict = true)

        openAITool("type").str shouldBe "function"
        val function = openAITool("function").obj
        function("name").str shouldBe "add_inventory_item"

        val parameters = function("parameters").obj
        val required   = parameters("required").arr

        // Should have 'item' as required
        required should have size 1
        required.head.str shouldBe "item"

        // Should have 'item' in properties
        val properties = parameters("properties").obj
        (properties should contain).key("item")
      }
    )
  }

  "remove_inventory_item OpenAI schema" should "have 'item' in required array" in {
    TestGameTools.removeInventoryItemTool.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => {
        val openAITool = tool.toOpenAITool(strict = true)

        openAITool("type").str shouldBe "function"
        val function = openAITool("function").obj
        function("name").str shouldBe "remove_inventory_item"

        val parameters = function("parameters").obj
        val required   = parameters("required").arr

        // Should have 'item' as required
        required should have size 1
        required.head.str shouldBe "item"

        // Should have 'item' in properties
        val properties = parameters("properties").obj
        (properties should contain).key("item")
      }
    )
  }

  // ========== Null Argument Handling Tests ==========

  "list_inventory" should "accept null arguments (zero-parameter tool)" in {
    TestGameTools.listInventoryTool.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => {
        val result = tool.execute(ujson.Null)

        result shouldBe a[Right[_, _]]
        val json = result.fold(e => fail(s"Expected successful execution: ${e.getMessage}"), identity)

        json("inventory").arr shouldBe empty
        json("count").num shouldBe 0
        json("message").str shouldBe "Your inventory is empty"
      }
    )
  }

  "list_inventory" should "accept empty object arguments" in {
    TestGameTools.listInventoryTool.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => {
        val result = tool.execute(ujson.Obj())

        result shouldBe a[Right[_, _]]
        val json = result.fold(e => fail(s"Expected successful execution: ${e.getMessage}"), identity)

        json("inventory").arr shouldBe empty
        json("count").num shouldBe 0
      }
    )
  }

  "add_inventory_item" should "reject null arguments" in {
    TestGameTools.addInventoryItemTool.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => {
        val result = tool.execute(ujson.Null)

        result shouldBe a[Left[_, _]]
        val error   = result.fold(identity, v => fail(s"Expected error but got: $v"))
        val message = error.getFormattedMessage

        message should include("add_inventory_item")
        message should include("null arguments")
        message should include("expected an object")
      }
    )
  }

  "add_inventory_item" should "reject empty object (missing required 'item')" in {
    TestGameTools.addInventoryItemTool.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => {
        val result = tool.execute(ujson.Obj())

        result shouldBe a[Left[_, _]]
        val error   = result.fold(identity, v => fail(s"Expected error but got: $v"))
        val message = error.getMessage

        message should include("item")
        message should include("missing")
      }
    )
  }

  "remove_inventory_item" should "reject null arguments" in {
    TestGameTools.removeInventoryItemTool.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => {
        val result = tool.execute(ujson.Null)

        result shouldBe a[Left[_, _]]
        val error   = result.fold(identity, v => fail(s"Expected error but got: $v"))
        val message = error.getFormattedMessage

        message should include("remove_inventory_item")
        message should include("null arguments")
        message should include("expected an object")
      }
    )
  }

  "remove_inventory_item" should "reject empty object (missing required 'item')" in {
    TestGameTools.removeInventoryItemTool.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => {
        val result = tool.execute(ujson.Obj())

        result shouldBe a[Left[_, _]]
        val error   = result.fold(identity, v => fail(s"Expected error but got: $v"))
        val message = error.getMessage

        message should include("item")
        message should include("missing")
      }
    )
  }

  // ========== Successful Execution Tests ==========

  "add_inventory_item" should "successfully add an item with proper arguments" in {
    TestGameTools.addInventoryItemTool.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => {
        val result = tool.execute(ujson.Obj("item" -> "emergency beacon"))

        result shouldBe a[Right[_, _]]
        val json = result.fold(e => fail(s"Expected successful execution: ${e.getMessage}"), identity)

        json("success").bool shouldBe true
        json("item").str shouldBe "emergency beacon"
        json("message").str shouldBe "Added 'emergency beacon' to your inventory"
        json("inventory").arr should have size 1
        json("inventory").arr.head.str shouldBe "emergency beacon"
      }
    )
  }

  "add_inventory_item" should "detect duplicate items" in {
    TestGameTools.addInventoryItemTool.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => {
        // Add first time - should succeed
        val result1 = tool.execute(ujson.Obj("item" -> "supply manifest"))
        result1 shouldBe a[Right[_, _]]

        // Add second time - should fail with helpful message
        val result2 = tool.execute(ujson.Obj("item" -> "supply manifest"))
        result2 shouldBe a[Right[_, _]]

        val json = result2.fold(e => fail(s"Expected successful execution: ${e.getMessage}"), identity)
        json("success").bool shouldBe false
        json("message").str shouldBe "You already have 'supply manifest' in your inventory"
      }
    )
  }

  "remove_inventory_item" should "successfully remove an item" in {
    // Setup: add an item first
    TestGameTools.setInventory(List("emergency beacon", "supply manifest"))

    TestGameTools.removeInventoryItemTool.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => {
        val result = tool.execute(ujson.Obj("item" -> "emergency beacon"))

        result shouldBe a[Right[_, _]]
        val json = result.fold(e => fail(s"Expected successful execution: ${e.getMessage}"), identity)

        json("success").bool shouldBe true
        json("item").str shouldBe "emergency beacon"
        json("message").str shouldBe "Removed 'emergency beacon' from your inventory"
        json("inventory").arr should have size 1
        json("inventory").arr.head.str shouldBe "supply manifest"
      }
    )
  }

  "remove_inventory_item" should "fail gracefully when item not in inventory" in {
    TestGameTools.removeInventoryItemTool.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => {
        val result = tool.execute(ujson.Obj("item" -> "nonexistent item"))

        result shouldBe a[Right[_, _]]
        val json = result.fold(e => fail(s"Expected successful execution: ${e.getMessage}"), identity)

        json("success").bool shouldBe false
        json("message").str shouldBe "You don't have 'nonexistent item' in your inventory"
      }
    )
  }

  "list_inventory" should "return current inventory state" in {
    // Setup: add some items
    TestGameTools.setInventory(List("emergency beacon", "supply manifest", "medkit"))

    TestGameTools.listInventoryTool.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => {
        val result = tool.execute(ujson.Null) // null is OK for zero-param tool

        result shouldBe a[Right[_, _]]
        val json = result.fold(e => fail(s"Expected successful execution: ${e.getMessage}"), identity)

        json("count").num shouldBe 3
        json("inventory").arr should have size 3
        json("message").str shouldBe "You have 3 item(s) in your inventory"

        val items = json("inventory").arr.map(_.str).toSet
        items shouldBe Set("emergency beacon", "supply manifest", "medkit")
      }
    )
  }

  // ========== Integration Workflow Tests ==========

  "Game tools" should "work together in a complete workflow" in {
    val setup = for {
      listTool   <- TestGameTools.listInventoryTool
      addTool    <- TestGameTools.addInventoryItemTool
      removeTool <- TestGameTools.removeInventoryItemTool
    } yield (listTool, addTool, removeTool)

    setup.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      { case (listTool, addTool, removeTool) =>
        // Start with empty inventory
        val listResult1 = listTool.execute(ujson.Null)
        listResult1.fold(e => fail(s"Expected Right but got Left: ${e.getMessage}"), identity)("count").num shouldBe 0

        // Add first item
        val addResult1 = addTool.execute(ujson.Obj("item" -> "emergency beacon"))
        addResult1
          .fold(e => fail(s"Expected Right but got Left: ${e.getMessage}"), identity)("success")
          .bool shouldBe true

        // Add second item
        val addResult2 = addTool.execute(ujson.Obj("item" -> "supply manifest"))
        addResult2
          .fold(e => fail(s"Expected Right but got Left: ${e.getMessage}"), identity)("success")
          .bool shouldBe true

        // List inventory - should have 2 items
        val listResult2 = listTool.execute(ujson.Obj())
        listResult2.fold(e => fail(s"Expected Right but got Left: ${e.getMessage}"), identity)("count").num shouldBe 2

        // Remove one item
        val removeResult = removeTool.execute(ujson.Obj("item" -> "emergency beacon"))
        removeResult
          .fold(e => fail(s"Expected Right but got Left: ${e.getMessage}"), identity)("success")
          .bool shouldBe true

        // List inventory - should have 1 item left
        val listResult3 = listTool.execute(ujson.Null)
        val finalJson   = listResult3.fold(e => fail(s"Expected Right but got Left: ${e.getMessage}"), identity)
        finalJson("count").num shouldBe 1
        finalJson("inventory").arr.head.str shouldBe "supply manifest"
      }
    )
  }

  // ========== ToolRegistry Integration Tests ==========

  "ToolRegistry with game tools" should "execute tools correctly" in {
    TestGameTools.allTools.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tools => {
        val registry = new ToolRegistry(tools)

        // List inventory via registry
        val listRequest = ToolCallRequest("list_inventory", ujson.Null)
        val listResult  = registry.execute(listRequest)
        listResult shouldBe a[Right[_, _]]

        // Add item via registry
        val addRequest = ToolCallRequest("add_inventory_item", ujson.Obj("item" -> "medkit"))
        val addResult  = registry.execute(addRequest)
        addResult shouldBe a[Right[_, _]]
        addResult
          .fold(e => fail(s"Expected Right but got Left: ${e.getMessage}"), identity)("success")
          .bool shouldBe true

        // Verify via list
        val verifyResult = registry.execute(listRequest)
        verifyResult.fold(e => fail(s"Expected Right but got Left: ${e.getMessage}"), identity)("count").num shouldBe 1
      }
    )
  }

  "ToolRegistry with game tools" should "return proper errors for unknown tools" in {
    TestGameTools.allTools.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tools => {
        val registry = new ToolRegistry(tools)

        val request = ToolCallRequest("unknown_tool", ujson.Obj())
        val result  = registry.execute(request)

        result shouldBe a[Left[_, _]]
        val error = result.fold(identity, v => fail(s"Expected error but got: $v"))

        error shouldBe a[ToolCallError.UnknownFunction]
        error.toolName shouldBe "unknown_tool"
      }
    )
  }

  "ToolRegistry with game tools" should "provide all tools in OpenAI format" in {
    TestGameTools.allTools.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tools => {
        val registry    = new ToolRegistry(tools)
        val openAITools = registry.getOpenAITools(strict = true)

        openAITools.arr should have size 3

        val toolNames = openAITools.arr.map(tool => tool("function")("name").str).toSet

        toolNames shouldBe Set("list_inventory", "add_inventory_item", "remove_inventory_item")
      }
    )
  }
}
