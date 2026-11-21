package org.llm4s.samples.toolapi

// The build compiles with -source:3.3, which turns off named tuple field access by name; Scala 3.7's default source
// level has it, so a project on the default needs no such import.
import scala.language.`3.7`

import org.llm4s.schema.*
import org.llm4s.schema.namedtuples.given
import org.llm4s.toolapi.{ ToolCallRequest, ToolFunction, ToolRegistry }
import org.llm4s.toolapi.builtin.core.{ CalculatorResult, CalculatorTool }
import org.llm4s.types.Result
import upickle.default.ReadWriter

/**
 * The built-in calculator tool two ways, side by side: as `llm4s-agent-tools` builds it by hand, and as a typed tool
 * whose parameter schema is derived from a case class (`Tool.of`, in `llm4s-schema-derivation`).
 *
 * The old way writes every parameter twice, once in the schema and once in the handler, which reads it by name:
 *
 * {{{
 * val schema = Schema.`object`[Map[String, Any]]("Calculator parameters")
 *   .withProperty(Schema.property("operation", Schema.string("Mathematical operation to perform").withEnum(...)))
 *   .withProperty(Schema.property("a", Schema.number("First operand (required for all operations)")))
 *   .withProperty(Schema.property("b", Schema.number("Second operand ...")))
 *
 * ToolBuilder[Map[String, Any], CalculatorResult]("calculator", description, schema)
 *   .withHandler { extractor =>
 *     for {
 *       operation <- extractor.getString("operation")
 *       a         <- extractor.getDouble("a")
 *       bOpt = extractor.getDouble("b").toOption
 *       result <- calculate(operation, a, bOpt)
 *     } yield result
 *   }
 *   .buildSafe()
 * }}}
 *
 * The typed way writes them once, in [[TypedCalculator.CalculatorArgs]], and the function receives them decoded.
 * The two send the same schema, except that the typed one says what the handler always did: `b` may be left out.
 *
 * A third version, [[TypedCalculator.namedTupleTool]], takes its arguments as a named tuple,
 * `(operation: Operation, a: Double, b: Option[Double])`, with no case class to declare: the same schema, except that
 * each parameter is described by its name, since a named tuple's elements cannot carry a `@description`.
 *
 * A fourth, [[TypedCalculator.scopedTool]], is built with `Tool.scoped`: its function returns the result itself and
 * ends the call early with `reject`, `ensure` or `?`, where the others return an `Either`.
 *
 * The sample needs no model; it calls the tools with the same arguments and prints what each returns.
 *
 * {{{
 * sbt "samples/runMain org.llm4s.samples.toolapi.TypedCalculatorExample"
 * }}}
 */
object TypedCalculatorExample {

  def main(args: Array[String]): Unit = {
    val calls = List(
      ujson.Obj("operation" -> "add", "a"        -> 15, "b"        -> 27),
      ujson.Obj("operation" -> "percentage", "a" -> 15, "b"        -> 250),
      ujson.Obj("operation" -> "sqrt", "a"       -> 144),
      ujson.Obj("operation" -> "divide", "a"     -> 1, "b"         -> 0),
      ujson.Obj("operation" -> "add", "a"        -> "fifteen", "b" -> 27),
      ujson.Obj("operation" -> "add", "b"        -> 27)
    )

    val outcome = for {
      handBuilt  <- CalculatorTool.toolSafe
      typed      <- TypedCalculator.tool
      namedTuple <- TypedCalculator.namedTupleTool
      scoped     <- TypedCalculator.scopedTool
    } yield {
      println("Schema, hand-built:")
      println(handBuilt.toOpenAITool().render(indent = 2))
      println("Schema, typed:")
      println(typed.toOpenAITool().render(indent = 2))
      println("Schema, named tuple:")
      println(namedTuple.toOpenAITool().render(indent = 2))

      val handBuiltTools  = new ToolRegistry(Seq(handBuilt))
      val typedTools      = new ToolRegistry(Seq(typed))
      val namedTupleTools = new ToolRegistry(Seq(namedTuple))
      val scopedTools     = new ToolRegistry(Seq(scoped))
      calls.foreach { arguments =>
        val request = ToolCallRequest("calculator", arguments)
        println(s"\ncalculator(${arguments.render()})")
        println(s"  hand-built:  ${show(handBuiltTools.execute(request))}")
        println(s"  typed:       ${show(typedTools.execute(request))}")
        println(s"  named tuple: ${show(namedTupleTools.execute(request))}")
        println(s"  scoped:      ${show(scopedTools.execute(request))}")
      }
    }

    outcome.left.foreach(error => println(s"Could not build the tools: ${error.formatted}"))
  }

  private def show(result: Either[org.llm4s.toolapi.ToolCallError, ujson.Value]): String =
    result.fold(_.getFormattedMessage, _.render())
}

/** The built-in calculator as a typed tool. */
object TypedCalculator {

  /** The operations, written as their names: the derived schema lists them as the hand-built one does. */
  enum Operation derives SchemaOf, ReadWriter {
    case add, subtract, multiply, divide, power, sqrt, percentage, abs, min, max, modulo
  }

  /** The calculator's parameters: the schema the model sees is derived from this type. */
  @description("Calculator parameters")
  case class CalculatorArgs(
    @description("Mathematical operation to perform") operation: Operation,
    @description("First operand (required for all operations)") a: Double,
    @description("Second operand (required for binary operations, optional for unary like sqrt/abs)")
    b: Option[Double]
  ) derives SchemaOf,
        ReadWriter

  /** The calculator: the same name, description and result type as the built-in one. */
  val tool: Result[ToolFunction[CalculatorArgs, CalculatorResult]] =
    CalculatorTool.toolSafe.flatMap { builtin =>
      Tool.of[CalculatorArgs, CalculatorResult]("calculator", builtin.description)(calculate)
    }

  /** The calculator's parameters as a named tuple: no case class to declare, each parameter described by its name. */
  type CalculatorTuple = (operation: Operation, a: Double, b: Option[Double])

  /** The same calculator, its arguments a named tuple. */
  val namedTupleTool: Result[ToolFunction[CalculatorTuple, CalculatorResult]] =
    CalculatorTool.toolSafe.flatMap { builtin =>
      Tool.of[CalculatorTuple, CalculatorResult]("calculator", builtin.description) { args =>
        calculate(CalculatorArgs(args.operation, args.a, args.b))
      }
    }

  /** The same calculator as a scoped tool: it returns the result, and ends the call early with `reject` or `?`. */
  val scopedTool: Result[ToolFunction[CalculatorArgs, CalculatorResult]] =
    CalculatorTool.toolSafe.flatMap { builtin =>
      Tool.scoped[CalculatorArgs, CalculatorResult]("calculator", builtin.description) { args =>
        val a         = args.a
        def b: Double = args.b.toRight(s"Operation '${args.operation}' requires two operands (a and b)").?

        val r = args.operation match {
          case Operation.add      => a + b
          case Operation.subtract => a - b
          case Operation.multiply => a * b
          case Operation.divide =>
            val divisor = b
            if (divisor == 0) reject("Division by zero") else a / divisor
          case Operation.power => math.pow(a, b)
          case Operation.sqrt =>
            ensure(a >= 0, "Cannot compute square root of negative number")
            math.sqrt(a)
          case Operation.percentage => a / 100.0 * b
          case Operation.abs        => math.abs(a)
          case Operation.min        => math.min(a, b)
          case Operation.max        => math.max(a, b)
          case Operation.modulo =>
            val divisor = b
            if (divisor == 0) reject("Modulo by zero") else a % divisor
        }
        ensure(!r.isNaN && !r.isInfinite, "Result is not a finite number (overflow or an undefined operation)")
        CalculatorResult(expression(args), r, formatted(r))
      }
    }

  private def calculate(args: CalculatorArgs): Either[String, CalculatorResult] = {
    val a = args.a
    def withB(f: Double => Either[String, Double]): Either[String, Double] =
      args.b.toRight(s"Operation '${args.operation}' requires two operands (a and b)").flatMap(f)

    val value: Either[String, Double] = args.operation match {
      case Operation.add      => withB(b => Right(a + b))
      case Operation.subtract => withB(b => Right(a - b))
      case Operation.multiply => withB(b => Right(a * b))
      case Operation.divide   => withB(b => if (b == 0) Left("Division by zero") else Right(a / b))
      case Operation.power    => withB(b => Right(math.pow(a, b)))
      case Operation.sqrt => if (a < 0) Left("Cannot compute square root of negative number") else Right(math.sqrt(a))
      case Operation.percentage => withB(b => Right(a / 100.0 * b))
      case Operation.abs        => Right(math.abs(a))
      case Operation.min        => withB(b => Right(math.min(a, b)))
      case Operation.max        => withB(b => Right(math.max(a, b)))
      case Operation.modulo     => withB(b => if (b == 0) Left("Modulo by zero") else Right(a % b))
    }

    value
      .filterOrElse(
        r => !r.isNaN && !r.isInfinite,
        "Result is not a finite number (overflow or an undefined operation)"
      )
      .map(r => CalculatorResult(expression(args), r, formatted(r)))
  }

  private def expression(args: CalculatorArgs): String = {
    val a = args.a
    val b = args.b.getOrElse(0)
    args.operation match {
      case Operation.add        => s"$a + $b"
      case Operation.subtract   => s"$a - $b"
      case Operation.multiply   => s"$a * $b"
      case Operation.divide     => s"$a / $b"
      case Operation.power      => s"$a ^ $b"
      case Operation.sqrt       => s"sqrt($a)"
      case Operation.percentage => s"$a% of $b"
      case Operation.abs        => s"abs($a)"
      case Operation.min        => s"min($a, $b)"
      case Operation.max        => s"max($a, $b)"
      case Operation.modulo     => s"$a mod $b"
    }
  }

  private def formatted(r: Double): String =
    if (r == r.toLong) r.toLong.toString
    else String.format(java.util.Locale.ROOT, "%.6f", Double.box(r)).replaceAll("0+$", "").replaceAll("\\.$", "")
}
