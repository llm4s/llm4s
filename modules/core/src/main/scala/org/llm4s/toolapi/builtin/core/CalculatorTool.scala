package org.llm4s.toolapi.builtin.core

import org.llm4s.toolapi._
import org.llm4s.types.Result
import upickle.default._

/**
 * Result from calculator operations.
 */
case class CalculatorResult(
  expression: String,
  result: Double,
  formatted: String
)

object CalculatorResult {
  implicit val calculatorResultRW: ReadWriter[CalculatorResult] = macroRW[CalculatorResult]
}

/**
 * Tool for performing mathematical calculations.
 *
 * Supports:
 * - Basic arithmetic: add, subtract, multiply, divide
 * - Power and square root
 * - Percentage calculations
 * - Absolute value, min, max
 *
 * @example
 * {{{
 * import org.llm4s.toolapi.builtin.core.CalculatorTool
 *
 * val tools = new ToolRegistry(Seq(CalculatorTool.tool))
 * agent.run("What is 15% of 250?", tools)
 * }}}
 */
object CalculatorTool {

  private val schema = Schema
    .`object`[Map[String, Any]]("Calculator parameters")
    .withProperty(
      Schema.property(
        "operation",
        Schema
          .string("Mathematical operation to perform")
          .withEnum(
            Seq("add", "subtract", "multiply", "divide", "power", "sqrt", "percentage", "abs", "min", "max", "modulo")
          )
      )
    )
    .withProperty(
      Schema.property(
        "a",
        Schema.number("First operand (required for all operations)")
      )
    )
    .withProperty(
      Schema.property(
        "b",
        Schema.number("Second operand (required for binary operations, optional for unary like sqrt/abs)")
      )
    )

  /**
   * The calculator tool instance, returning a Result for safe error handling.
   */
  val toolSafe: Result[ToolFunction[Map[String, Any], CalculatorResult]] =
    ToolBuilder[Map[String, Any], CalculatorResult](
      name = "calculator",
      description = "Perform mathematical calculations. Supports: add, subtract, multiply, divide, " +
        "power (a^b), sqrt (square root of a), percentage (a% of b), abs (absolute value of a), " +
        "min (minimum of a and b), max (maximum of a and b), modulo (a mod b).",
      schema = schema
    ).withHandler { extractor =>
      for {
        operation <- extractor.getString("operation")
        a         <- extractor.getDouble("a")
        bOpt = extractor.getDouble("b").toOption
        result <- calculate(operation, a, bOpt)
      } yield result
    }.buildSafe()

  /**
   * The calculator tool instance.
   *
   * @throws IllegalStateException if tool initialization fails
   */
  @deprecated("Use toolSafe which returns Result[ToolFunction] for safe error handling", "0.2.9")
  lazy val tool: ToolFunction[Map[String, Any], CalculatorResult] =
    toolSafe match {
      case Right(t) => t
      case Left(e) => throw new IllegalStateException(s"CalculatorTool.tool lazy initialization failed: ${e.formatted}")
    }

  private def calculate(operation: String, a: Double, bOpt: Option[Double]): Either[String, CalculatorResult] = {
    def requireB: Either[String, Double] =
      bOpt.toRight(s"Operation '$operation' requires two operands (a and b)")

    val result: Either[String, Double] = operation.toLowerCase match {
      case "add" =>
        requireB.map(b => a + b)

      case "subtract" =>
        requireB.map(b => a - b)

      case "multiply" =>
        requireB.map(b => a * b)

      case "divide" =>
        requireB.flatMap { b =>
          if (b == 0) Left("Division by zero")
          else Right(a / b)
        }

      case "power" =>
        requireB.map(b => math.pow(a, b))

      case "sqrt" =>
        if (a < 0) Left("Cannot compute square root of negative number")
        else Right(math.sqrt(a))

      case "percentage" =>
        // Calculate a% of b (e.g., 15% of 200 = 30)
        requireB.map(b => (a / 100.0) * b)

      case "abs" =>
        Right(math.abs(a))

      case "min" =>
        requireB.map(b => math.min(a, b))

      case "max" =>
        requireB.map(b => math.max(a, b))

      case "modulo" =>
        requireB.flatMap { b =>
          if (b == 0) Left("Modulo by zero")
          else Right(a % b)
        }

      case other =>
        Left(
          s"Unknown operation: $other. Supported: add, subtract, multiply, divide, power, sqrt, percentage, abs, min, max, modulo"
        )
    }

    result.map { r =>
      val expression = operation.toLowerCase match {
        case "add"        => s"$a + ${bOpt.getOrElse(0)}"
        case "subtract"   => s"$a - ${bOpt.getOrElse(0)}"
        case "multiply"   => s"$a * ${bOpt.getOrElse(0)}"
        case "divide"     => s"$a / ${bOpt.getOrElse(0)}"
        case "power"      => s"$a ^ ${bOpt.getOrElse(0)}"
        case "sqrt"       => s"sqrt($a)"
        case "percentage" => s"$a% of ${bOpt.getOrElse(0)}"
        case "abs"        => s"abs($a)"
        case "min"        => s"min($a, ${bOpt.getOrElse(0)})"
        case "max"        => s"max($a, ${bOpt.getOrElse(0)})"
        case "modulo"     => s"$a mod ${bOpt.getOrElse(0)}"
        case _            => s"$operation($a${bOpt.map(b => s", $b").getOrElse("")})"
      }

      val formatted = if (r == r.toLong) {
        r.toLong.toString
      } else {
        f"$r%.6f".replaceAll("0+$", "").replaceAll("\\.$", "")
      }

      CalculatorResult(
        expression = expression,
        result = r,
        formatted = formatted
      )
    }
  }
}
