package org.llm4s.samples.toolapi

import org.llm4s.toolapi._
import upickle.default._

/**
 * Demonstration of improved error messages for tool parameter validation
 */
object ErrorMessageDemonstration extends App {

  // Define a simple result type
  case class Result(message: String)
  implicit val resultRW: ReadWriter[Result] = macroRW[Result]

  // Create a test tool with various parameter requirements
  val inventoryTool = {
    val schema = Schema
      .`object`[Map[String, Any]]("Inventory management parameters")
      .withProperty(Schema.property("item", Schema.string("Item name to add")))
      .withProperty(Schema.property("quantity", Schema.number("Quantity to add")))
      .withProperty(Schema.property("location", Schema.string("Storage location")))

    ToolBuilder[Map[String, Any], Result](
      "add_inventory_item",
      "Adds an item to the inventory system",
      schema
    ).withHandler { extractor =>
      for {
        item <- extractor.getString("item")
        qty  <- extractor.getDouble("quantity")
        loc  <- extractor.getString("location")
      } yield Result(s"Added $qty units of $item to $loc")
    }.build()
  }

  println("=" * 60)
  println("Tool Parameter Validation - Error Message Examples")
  println("=" * 60)

  // Example 1: Null arguments
  println("\n1. Null arguments:")
  val nullResult = inventoryTool.execute(ujson.Null)
  nullResult match {
    case Left(error) =>
      println(s"   Error: ${error.getMessage}")
    case Right(_) =>
      println("   Unexpected success")
  }

  // Example 2: Missing required parameter
  println("\n2. Missing 'quantity' parameter:")
  val missingParamResult = inventoryTool.execute(
    ujson.Obj(
      "item"     -> "Apple",
      "location" -> "Warehouse A"
      // Missing: "quantity"
    )
  )
  missingParamResult match {
    case Left(error) =>
      println(s"   Error: ${error.getMessage}")
    case Right(_) =>
      println("   Unexpected success")
  }

  // Example 3: Type mismatch
  println("\n3. Type mismatch (string instead of number for quantity):")
  val typeMismatchResult = inventoryTool.execute(
    ujson.Obj(
      "item"     -> "Apple",
      "quantity" -> "five", // Should be a number
      "location" -> "Warehouse A"
    )
  )
  typeMismatchResult match {
    case Left(error) =>
      println(s"   Error: ${error.getMessage}")
    case Right(_) =>
      println("   Unexpected success")
  }

  // Example 4: Null value for required field
  println("\n4. Null value for required 'location' field:")
  val nullFieldResult = inventoryTool.execute(
    ujson.Obj(
      "item"     -> "Apple",
      "quantity" -> 10,
      "location" -> ujson.Null
    )
  )
  nullFieldResult match {
    case Left(error) =>
      println(s"   Error: ${error.getMessage}")
    case Right(_) =>
      println("   Unexpected success")
  }

  // Example 5: Successful execution
  println("\n5. Successful execution with all parameters:")
  val successResult = inventoryTool.execute(
    ujson.Obj(
      "item"     -> "Apple",
      "quantity" -> 10,
      "location" -> "Warehouse A"
    )
  )
  successResult match {
    case Left(error) =>
      println(s"   Error: ${error.getMessage}")
    case Right(result) =>
      println(s"   Success: ${result.render()}")
  }

  println("\n" + "=" * 60)
  println("The improved error messages now clearly indicate:")
  println("- Which tool failed")
  println("- What parameter is problematic")
  println("- What the actual issue is (null, missing, wrong type)")
  println("- What was expected")
  println("- Available alternatives when applicable")
  println("=" * 60)
}
