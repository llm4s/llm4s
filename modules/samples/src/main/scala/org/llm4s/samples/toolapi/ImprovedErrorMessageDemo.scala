package org.llm4s.samples.toolapi

import org.llm4s.toolapi._

/**
 * Demonstration of the improved error messages with consistent formatting
 */
object ImprovedErrorMessageDemo extends App {

  println("=" * 70)
  println("IMPROVED ERROR MESSAGE DEMONSTRATION")
  println("=" * 70)

  // Example 1: Unknown tool
  println("\n1. Unknown tool:")
  val unknownTool = ToolCallError.UnknownFunction("calculate_tax")
  println(s"   ${unknownTool.getFormattedMessage}")

  // Example 2: Null arguments
  println("\n2. Null arguments:")
  val nullArgs = ToolCallError.NullArguments("add_inventory_item")
  println(s"   ${nullArgs.getFormattedMessage}")

  // Example 3: Missing required parameter
  println("\n3. Missing required parameter:")
  val missingParam = ToolCallError.InvalidArguments(
    "add_inventory_item",
    List(ToolParameterError.MissingParameter("quantity", "number", List("item", "location")))
  )
  println(s"   ${missingParam.getFormattedMessage}")

  // Example 4: Null value for required parameter
  println("\n4. Parameter is null:")
  val nullParam = ToolCallError.InvalidArguments(
    "add_inventory_item",
    List(ToolParameterError.NullParameter("quantity", "number"))
  )
  println(s"   ${nullParam.getFormattedMessage}")

  // Example 5: Type mismatch
  println("\n5. Type mismatch:")
  val typeMismatch = ToolCallError.InvalidArguments(
    "add_inventory_item",
    List(ToolParameterError.TypeMismatch("quantity", "number", "string"))
  )
  println(s"   ${typeMismatch.getFormattedMessage}")

  // Example 6: Multiple parameter errors
  println("\n6. Multiple parameter errors:")
  val multipleErrors = ToolCallError.InvalidArguments(
    "submit_order",
    List(
      ToolParameterError.MissingParameter("customer_id", "string"),
      ToolParameterError.TypeMismatch("quantity", "number", "string"),
      ToolParameterError.NullParameter("product_id", "string")
    )
  )
  println(s"   ${multipleErrors.getFormattedMessage}")

  // Example 7: Nested parameter error
  println("\n7. Nested parameter error:")
  val nestedError = ToolCallError.InvalidArguments(
    "update_profile",
    List(ToolParameterError.InvalidNesting("email", "user", "string"))
  )
  println(s"   ${nestedError.getFormattedMessage}")

  // Example 8: Execution error
  println("\n8. Execution error (after validation):")
  val execError = ToolCallError.ExecutionError(
    "process_payment",
    new RuntimeException("Network timeout while contacting payment gateway")
  )
  println(s"   ${execError.getFormattedMessage}")

  // Example 9: Handler error (business logic failure)
  println("\n9. Handler error (business logic):")
  val handlerError = ToolCallError.HandlerError(
    "divide_numbers",
    "cannot divide by zero"
  )
  println(s"   ${handlerError.getFormattedMessage}")

  println("\n" + "=" * 70)
  println("KEY IMPROVEMENTS:")
  println("=" * 70)
  println("✓ Consistent 'Tool call <name>' prefix for all errors")
  println("✓ Clear distinction between missing, null, and wrong type")
  println("✓ Parameter types included in error messages")
  println("✓ Available parameters shown when one is missing")
  println("✓ Execution vs validation errors clearly separated")
  println("✓ Multi-line formatting for multiple errors")
  println("=" * 70)

  // Show JSON format as it would appear in Agent responses
  println("\n" + "=" * 70)
  println("JSON FORMAT (as returned to LLM):")
  println("=" * 70)

  def toJsonError(error: ToolCallError): String = {
    val message = error.getFormattedMessage
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
    s"""{ "isError": true, "error": "$message" }"""
  }

  println("\nExample JSON responses:")
  println("\n1. Missing parameter:")
  println(toJsonError(missingParam))

  println("\n2. Multiple errors:")
  println(toJsonError(multipleErrors))

  println("\n3. Execution error:")
  println(toJsonError(execError))

  println("\n" + "=" * 70)
}
