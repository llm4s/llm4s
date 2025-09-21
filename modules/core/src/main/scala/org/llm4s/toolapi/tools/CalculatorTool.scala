package org.llm4s.toolapi.tools

import org.llm4s.toolapi._
import upickle.default._

/**
 * Type-safe enumeration for calculator operations
 */
sealed trait CalculatorOperation {
  def value: String
  def description: String
  def requiresB: Boolean
}

object CalculatorOperation {
  case object Add extends CalculatorOperation {
    val value       = "add"
    val description = "Addition"
    val requiresB   = true
  }
  case object Subtract extends CalculatorOperation {
    val value       = "subtract"
    val description = "Subtraction"
    val requiresB   = true
  }
  case object Multiply extends CalculatorOperation {
    val value       = "multiply"
    val description = "Multiplication"
    val requiresB   = true
  }
  case object Divide extends CalculatorOperation {
    val value       = "divide"
    val description = "Division"
    val requiresB   = true
  }
  case object Power extends CalculatorOperation {
    val value       = "power"
    val description = "Exponentiation"
    val requiresB   = true
  }
  case object Sqrt extends CalculatorOperation {
    val value       = "sqrt"
    val description = "Square root"
    val requiresB   = false
  }

  val values: List[CalculatorOperation] = List(Add, Subtract, Multiply, Divide, Power, Sqrt)

  def fromString(value: String): Either[String, CalculatorOperation] =
    values.find(_.value == value) match {
      case Some(op) => Right(op)
      case None     => Left(s"Unknown operation: $value. Valid operations: ${values.map(_.value).mkString(", ")}")
    }
}

/**
 * Simple calculator tool for demonstrating LLM4S agent capabilities
 */
object CalculatorTool {

  // Define result type
  case class CalculationResult(
    operation: String,
    a: Double,
    b: Option[Double],
    result: Double,
    expression: String
  )

  // Provide implicit reader/writer
  implicit val calculationResultRW: ReadWriter[CalculationResult] = macroRW

  // Define calculator parameter schema
  val calculatorParamsSchema: ObjectSchema[Map[String, Any]] = Schema
    .`object`[Map[String, Any]]("Calculator request parameters")
    .withProperty(
      Schema.property(
        "operation",
        Schema
          .string("The mathematical operation to perform")
          .withEnum(Seq("add", "subtract", "multiply", "divide", "power", "sqrt"))
      )
    )
    .withProperty(
      Schema.property(
        "a",
        Schema.number("First number for the operation")
      )
    )
    .withProperty(
      Schema.property(
        "b",
        Schema.number("Second number for the operation (not needed for sqrt)")
      )
    )

  // Define type-safe handler function
  def calculatorHandler(params: SafeParameterExtractor): Either[String, CalculationResult] = {
    // Get required parameters
    val operation = params.getString("operation")
    val a         = params.getDouble("a")

    // Try to get optional parameter b, but don't fail if it's missing
    val b = params.getDouble("b").toOption

    for {
      op     <- operation
      numA   <- a
      result <- calculateResult(op, numA, b)
    } yield CalculationResult(
      operation = op,
      a = numA,
      b = b,
      result = result._1,
      expression = result._2
    )
  }

  // Helper function to calculate result without non-local returns
  private def calculateResult(operation: String, a: Double, b: Option[Double]): Either[String, (Double, String)] =
    CalculatorOperation.fromString(operation).flatMap(op => calculateWithOperation(op, a, b))

  private def calculateWithOperation(
    op: CalculatorOperation,
    a: Double,
    b: Option[Double]
  ): Either[String, (Double, String)] =
    op match {
      case CalculatorOperation.Add =>
        b match {
          case Some(numB) => Right((a + numB, s"$a + $numB"))
          case None       => Left("Second number 'b' is required for addition")
        }
      case CalculatorOperation.Subtract =>
        b match {
          case Some(numB) => Right((a - numB, s"$a - $numB"))
          case None       => Left("Second number 'b' is required for subtraction")
        }
      case CalculatorOperation.Multiply =>
        b match {
          case Some(numB) => Right((a * numB, s"$a × $numB"))
          case None       => Left("Second number 'b' is required for multiplication")
        }
      case CalculatorOperation.Divide =>
        b match {
          case Some(numB) =>
            if (numB == 0) Left("Division by zero is not allowed")
            else Right((a / numB, s"$a ÷ $numB"))
          case None => Left("Second number 'b' is required for division")
        }
      case CalculatorOperation.Power =>
        b match {
          case Some(numB) => Right((math.pow(a, numB), s"$a^$numB"))
          case None       => Left("Second number 'b' is required for power operation")
        }
      case CalculatorOperation.Sqrt =>
        if (a < 0) Left("Cannot calculate square root of negative number")
        else Right((math.sqrt(a), s"√$a"))
    }

  // Build the calculator tool
  val tool = ToolBuilder[Map[String, Any], CalculationResult](
    "calculator",
    "Performs basic mathematical calculations including addition, subtraction, multiplication, division, and power operations",
    calculatorParamsSchema
  ).withHandler(calculatorHandler).build()
}
