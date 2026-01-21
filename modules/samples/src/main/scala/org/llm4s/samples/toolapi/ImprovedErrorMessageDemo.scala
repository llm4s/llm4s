package org.llm4s.samples.toolapi

import org.llm4s.toolapi._
import org.slf4j.LoggerFactory

/**
 * Demonstration of the improved error messages with consistent formatting
 */
object ImprovedErrorMessageDemo extends App {
  private val logger = LoggerFactory.getLogger(getClass)

  logger.info("=" * 70)
  logger.info("IMPROVED ERROR MESSAGE DEMONSTRATION")
  logger.info("=" * 70)

  // Example 1: Unknown tool
  logger.info("")
  logger.info("1. Unknown tool:")
  val unknownTool = ToolCallError.UnknownFunction("calculate_tax")
  logger.info("   {}", unknownTool.getFormattedMessage)

  // Example 2: Null arguments
  logger.info("")
  logger.info("2. Null arguments:")
  val nullArgs = ToolCallError.NullArguments("add_inventory_item")
  logger.info("   {}", nullArgs.getFormattedMessage)

  // Example 3: Missing required parameter
  logger.info("")
  logger.info("3. Missing required parameter:")
  val missingParam = ToolCallError.InvalidArguments(
    "add_inventory_item",
    List(ToolParameterError.MissingParameter("quantity", "number", List("item", "location")))
  )
  logger.info("   {}", missingParam.getFormattedMessage)

  // Example 4: Null value for required parameter
  logger.info("")
  logger.info("4. Parameter is null:")
  val nullParam = ToolCallError.InvalidArguments(
    "add_inventory_item",
    List(ToolParameterError.NullParameter("quantity", "number"))
  )
  logger.info("   {}", nullParam.getFormattedMessage)

  // Example 5: Type mismatch
  logger.info("")
  logger.info("5. Type mismatch:")
  val typeMismatch = ToolCallError.InvalidArguments(
    "add_inventory_item",
    List(ToolParameterError.TypeMismatch("quantity", "number", "string"))
  )
  logger.info("   {}", typeMismatch.getFormattedMessage)

  // Example 6: Multiple parameter errors
  logger.info("")
  logger.info("6. Multiple parameter errors:")
  val multipleErrors = ToolCallError.InvalidArguments(
    "submit_order",
    List(
      ToolParameterError.MissingParameter("customer_id", "string"),
      ToolParameterError.TypeMismatch("quantity", "number", "string"),
      ToolParameterError.NullParameter("product_id", "string")
    )
  )
  logger.info("   {}", multipleErrors.getFormattedMessage)

  // Example 7: Nested parameter error
  logger.info("")
  logger.info("7. Nested parameter error:")
  val nestedError = ToolCallError.InvalidArguments(
    "update_profile",
    List(ToolParameterError.InvalidNesting("email", "user", "string"))
  )
  logger.info("   {}", nestedError.getFormattedMessage)

  // Example 8: Execution error
  logger.info("")
  logger.info("8. Execution error (after validation):")
  val execError = ToolCallError.ExecutionError(
    "process_payment",
    new RuntimeException("Network timeout while contacting payment gateway")
  )
  logger.info("   {}", execError.getFormattedMessage)

  // Example 9: Handler error (business logic failure)
  logger.info("")
  logger.info("9. Handler error (business logic):")
  val handlerError = ToolCallError.HandlerError(
    "divide_numbers",
    "cannot divide by zero"
  )
  logger.info("   {}", handlerError.getFormattedMessage)

  logger.info("")
  logger.info("=" * 70)
  logger.info("KEY IMPROVEMENTS:")
  logger.info("=" * 70)
  logger.info("- Consistent 'Tool call <name>' prefix for all errors")
  logger.info("- Clear distinction between missing, null, and wrong type")
  logger.info("- Parameter types included in error messages")
  logger.info("- Available parameters shown when one is missing")
  logger.info("- Execution vs validation errors clearly separated")
  logger.info("- Multi-line formatting for multiple errors")
  logger.info("=" * 70)

  // Show JSON format as it would appear in Agent responses
  logger.info("")
  logger.info("=" * 70)
  logger.info("JSON FORMAT (as returned to LLM):")
  logger.info("=" * 70)

  def toJsonError(error: ToolCallError): String = {
    val message = error.getFormattedMessage
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
    s"""{ "isError": true, "error": "$message" }"""
  }

  logger.info("")
  logger.info("Example JSON responses:")
  logger.info("")
  logger.info("1. Missing parameter:")
  logger.info("{}", toJsonError(missingParam))

  logger.info("")
  logger.info("2. Multiple errors:")
  logger.info("{}", toJsonError(multipleErrors))

  logger.info("")
  logger.info("3. Execution error:")
  logger.info("{}", toJsonError(execError))

  logger.info("")
  logger.info("=" * 70)
}
