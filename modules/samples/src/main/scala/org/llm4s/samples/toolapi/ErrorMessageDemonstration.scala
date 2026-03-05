package org.llm4s.samples.toolapi

import org.llm4s.toolapi._
import upickle.default._
import org.slf4j.LoggerFactory

/**
 * Demonstration of improved error messages for tool parameter validation
 */
object ErrorMessageDemonstration extends App {
  private val logger = LoggerFactory.getLogger(getClass)

  // Define a simple result type
  case class Result(message: String)
  implicit val resultRW: ReadWriter[Result] = macroRW[Result]

  // Create a test tool with various parameter requirements
  val schema = Schema
    .`object`[Map[String, Any]]("Inventory management parameters")
    .withProperty(Schema.property("item", Schema.string("Item name to add")))
    .withProperty(Schema.property("quantity", Schema.number("Quantity to add")))
    .withProperty(Schema.property("location", Schema.string("Storage location")))

  val result = for {
    inventoryTool <- ToolBuilder[Map[String, Any], Result](
      "add_inventory_item",
      "Adds an item to the inventory system",
      schema
    ).withHandler { extractor =>
      for {
        item <- extractor.getString("item")
        qty  <- extractor.getDouble("quantity")
        loc  <- extractor.getString("location")
      } yield Result(s"Added $qty units of $item to $loc")
    }.buildSafe()
  } yield {
    logger.info("=" * 60)
    logger.info("Tool Parameter Validation - Error Message Examples")
    logger.info("=" * 60)

    // Example 1: Null arguments
    logger.info("")
    logger.info("1. Null arguments:")
    val nullResult = inventoryTool.execute(ujson.Null)
    nullResult match {
      case Left(error) =>
        logger.info("   Error: {}", error.getMessage)
      case Right(_) =>
        logger.info("   Unexpected success")
    }

    // Example 2: Missing required parameter
    logger.info("")
    logger.info("2. Missing 'quantity' parameter:")
    val missingParamResult = inventoryTool.execute(
      ujson.Obj(
        "item"     -> "Apple",
        "location" -> "Warehouse A"
        // Missing: "quantity"
      )
    )
    missingParamResult match {
      case Left(error) =>
        logger.info("   Error: {}", error.getMessage)
      case Right(_) =>
        logger.info("   Unexpected success")
    }

    // Example 3: Type mismatch
    logger.info("")
    logger.info("3. Type mismatch (string instead of number for quantity):")
    val typeMismatchResult = inventoryTool.execute(
      ujson.Obj(
        "item"     -> "Apple",
        "quantity" -> "five", // Should be a number
        "location" -> "Warehouse A"
      )
    )
    typeMismatchResult match {
      case Left(error) =>
        logger.info("   Error: {}", error.getMessage)
      case Right(_) =>
        logger.info("   Unexpected success")
    }

    // Example 4: Null value for required field
    logger.info("")
    logger.info("4. Null value for required 'location' field:")
    val nullFieldResult = inventoryTool.execute(
      ujson.Obj(
        "item"     -> "Apple",
        "quantity" -> 10,
        "location" -> ujson.Null
      )
    )
    nullFieldResult match {
      case Left(error) =>
        logger.info("   Error: {}", error.getMessage)
      case Right(_) =>
        logger.info("   Unexpected success")
    }

    // Example 5: Successful execution
    logger.info("")
    logger.info("5. Successful execution with all parameters:")
    val successResult = inventoryTool.execute(
      ujson.Obj(
        "item"     -> "Apple",
        "quantity" -> 10,
        "location" -> "Warehouse A"
      )
    )
    successResult match {
      case Left(error) =>
        logger.info("   Error: {}", error.getMessage)
      case Right(r) =>
        logger.info("   Success: {}", r.render())
    }

    logger.info("")
    logger.info("=" * 60)
    logger.info("The improved error messages now clearly indicate:")
    logger.info("- Which tool failed")
    logger.info("- What parameter is problematic")
    logger.info("- What the actual issue is (null, missing, wrong type)")
    logger.info("- What was expected")
    logger.info("- Available alternatives when applicable")
    logger.info("=" * 60)
  }

  result.left.foreach(err => logger.error("Failed to build tool: {}", err.formatted))
}
