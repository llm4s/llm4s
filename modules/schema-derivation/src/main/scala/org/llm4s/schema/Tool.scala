package org.llm4s.schema

import org.llm4s.annotation.Experimental
import org.llm4s.error.ValidationError
import org.llm4s.toolapi.{
  ArraySchema,
  BooleanSchema,
  IntegerSchema,
  NullableSchema,
  NumberSchema,
  ObjectSchema,
  SchemaDefinition,
  StringSchema,
  ToolFunction,
  ToolParameterError
}
import org.llm4s.types.Result
import upickle.default.{ ReadWriter, Reader }

import scala.util.Try

/**
 * Tools defined from a function over a case class: the parameter schema is derived from the argument type
 * ([[SchemaOf]]), the arguments are decoded into it, and the result is written with its uPickle writer, so the
 * schema the model sees, the argument parsing and the function cannot drift apart.
 *
 * {{{
 * @description("Where to look up the weather")
 * case class WeatherArgs(@description("City name") city: String, @description("Days ahead") days: Int)
 *   derives SchemaOf, ReadWriter
 *
 * val weather: Result[ToolFunction[WeatherArgs, Forecast]] =
 *   Tool.of[WeatherArgs, Forecast]("get_weather", "Gets a forecast")(args => forecast(args.city, args.days))
 * }}}
 *
 * The result is the `ToolFunction` that `ToolBuilder` builds, registered in a `ToolRegistry` like any other.
 */
@Experimental
object Tool {

  /**
   * Builds a tool from a function over its arguments.
   *
   * Before `handler` runs, the call's arguments are checked against the derived schema, every field at once, and
   * each problem is reported as the `ToolParameterError` that `SafeParameterExtractor` reports for a hand-written
   * tool: `MissingParameter` (with the keys that were sent), `NullParameter`, `TypeMismatch`, and `InvalidNesting`
   * for each field when the arguments are not a JSON object. A nested field is named by its path (`address.city`,
   * `items[0].name`). An `Int` must be a whole number in the `Int` range, as for `getInt`; a value outside an enum's
   * cases is a `TypeMismatch` that lists them. Keys the type has no field for are ignored, as a hand-written tool
   * ignores them. A failure reaches the model as the tool's error, as a hand-written handler's `Left` does: one
   * problem as its own message, several as `ToolParameterError.MultipleErrors`'s.
   *
   * An `Option` field may be absent or `null` (`None`); every other field is required, including one with a
   * default value, as the schema says.
   *
   * @param name        the tool's name, as the model calls it
   * @param description what the tool does, for the model
   * @param handler     the tool's logic; a `Left` is the error the model sees, as with `ToolBuilder.withHandler`
   * @tparam A the arguments: a case class with a [[SchemaOf]] (`derives SchemaOf`) and a uPickle reader
   * @tparam R the result, written with its uPickle `ReadWriter` (which `ToolFunction` requires)
   * @return the tool, or a `ValidationError` when `A`'s schema is not a JSON object (an enum, for instance) or cannot
   *         be built (a recursion through hand-written givens; see [[SchemaOf]])
   */
  def of[A, R](name: String, description: String)(
    handler: A => Either[String, R]
  )(using schemaOf: SchemaOf[A], reader: Reader[A], writer: ReadWriter[R]): Result[ToolFunction[A, R]] =
    parametersOf(name, schemaOf).map { parameters =>
      ToolFunction[A, R](
        name,
        description,
        parameters,
        extractor => decode[A](parameters, extractor.params).left.map(message).flatMap(handler)
      )
    }

  private def parametersOf[A](name: String, schemaOf: SchemaOf[A]): Result[ObjectSchema[A]] =
    Try(schemaOf.definition).toEither.left
      .map(e => ValidationError.invalid("tool", s"$name: ${e.getMessage}"))
      .flatMap {
        case parameters: ObjectSchema[?] => Right(parameters.asInstanceOf[ObjectSchema[A]])
        case _ =>
          Left(
            ValidationError.invalid(
              "tool",
              s"$name: ${schemaOf.typeName} is not a JSON object: a tool's arguments must be a case class"
            )
          )
      }

  private def message(errors: List[ToolParameterError]): String = errors match {
    case single :: Nil => single.getMessage
    case several       => ToolParameterError.MultipleErrors(several).getMessage
  }

  /**
   * The arguments of a call decoded into `A`, or every problem with them. `parameters` is `A`'s derived schema.
   * For the module's tests, which compare the problems with a hand-written tool's.
   */
  private[schema] def decode[A](parameters: ObjectSchema[A], arguments: ujson.Value)(using
    reader: Reader[A]
  ): Either[List[ToolParameterError], A] = {
    val (errors, normalised) = arguments match {
      case _: ujson.Obj                       => checkObject(parameters, arguments, "")
      case _ if parameters.properties.isEmpty => (Nil, ujson.Obj())
      case other                              =>
        // what `SafeParameterExtractor` reports for each parameter when the arguments are not an object
        (
          parameters.properties.toList.map(p => ToolParameterError.InvalidNesting(p.name, "root", jsonType(other))),
          other
        )
    }
    if (errors.nonEmpty) Left(errors)
    else
      Try(upickle.default.read[A](normalised, trace = true)).toEither.left.map { e =>
        List(unreadable(parameters, normalised, e))
      }
  }

  // ---- checking the arguments against the schema

  private type Checked = (List[ToolParameterError], ujson.Value)

  private def at(path: String, key: String): String = if (path.isEmpty) key else s"$path.$key"

  // An object's fields, each checked; an absent `Option` field is filled in with `null`, which uPickle reads as
  // `None` (its derived reader refuses a missing `Option` field that has no default).
  private def checkObject(schema: ObjectSchema[?], value: ujson.Value, path: String): Checked = value match {
    case obj: ujson.Obj =>
      val sent    = obj.value.keys.toList.sorted
      val checked = ujson.Obj.from(obj.value)
      val errors = schema.properties.toList.flatMap { property =>
        val name     = at(path, property.name)
        val nullable = isNullable(property.schema)
        obj.value.get(property.name) match {
          case None if property.required =>
            List(ToolParameterError.MissingParameter(name, expectedType(property.schema), sent))
          case None =>
            if (nullable) checked(property.name) = ujson.Null
            Nil
          case Some(ujson.Null) if nullable || !property.required => Nil
          case Some(ujson.Null) => List(ToolParameterError.NullParameter(name, expectedType(property.schema)))
          case Some(field) =>
            val (fieldErrors, fieldValue) = check(property.schema, field, name)
            checked(property.name) = fieldValue
            fieldErrors
        }
      }
      (errors, checked)
    case other => (List(ToolParameterError.TypeMismatch(path, "object", jsonType(other))), other)
  }

  private def check(schema: SchemaDefinition[?], value: ujson.Value, path: String): Checked = {
    def expect(ok: Boolean, expected: String): Checked =
      if (ok) (Nil, value) else (List(ToolParameterError.TypeMismatch(path, expected, jsonType(value))), value)

    schema match {
      case NullableSchema(_) if value == ujson.Null => (Nil, value)
      case NullableSchema(underlying)               => check(underlying, value, path)
      case s: StringSchema =>
        (value.strOpt, s.enumValues) match {
          case (None, _) => expect(ok = false, "string")
          case (Some(text), Some(cases)) if !cases.contains(text) =>
            (List(ToolParameterError.TypeMismatch(path, s"one of ${cases.mkString(", ")}", s"'$text'")), value)
          case _ => (Nil, value)
        }
      case _: IntegerSchema => expect(wholeNumber(value)(d => d >= Int.MinValue && d <= Int.MaxValue), "integer")
      case n: NumberSchema if n.isInteger =>
        expect(wholeNumber(value)(d => d >= LongMin && d < LongMaxExclusive), "integer")
      case _: NumberSchema    => expect(value.numOpt.isDefined, "number")
      case _: BooleanSchema   => expect(value.boolOpt.isDefined, "boolean")
      case a: ArraySchema[?]  => checkArray(a.itemSchema, value, path)
      case o: ObjectSchema[?] => checkObject(o, value, path)
    }
  }

  private def checkArray(item: SchemaDefinition[?], value: ujson.Value, path: String): Checked = value match {
    case arr: ujson.Arr =>
      val checked = arr.value.toList.zipWithIndex.map { case (element, i) =>
        val name = s"$path[$i]"
        if (element == ujson.Null && !isNullable(item))
          (List(ToolParameterError.NullParameter(name, expectedType(item))), element)
        else check(item, element, name)
      }
      (checked.flatMap(_._1), ujson.Arr.from(checked.map(_._2)))
    case other => (List(ToolParameterError.TypeMismatch(path, "array", jsonType(other))), other)
  }

  // A JSON number is a double; `Long.MaxValue.toDouble` is 2^63, one past the largest `Long`.
  private val LongMin          = Long.MinValue.toDouble
  private val LongMaxExclusive = -Long.MinValue.toDouble

  // As `SafeParameterExtractor.getInt`: a number with no fractional part, in range, never truncated or clamped.
  private def wholeNumber(value: ujson.Value)(inRange: Double => Boolean): Boolean =
    value.numOpt.exists(d => inRange(d) && d == Math.rint(d))

  private def isNullable(schema: SchemaDefinition[?]): Boolean = schema.isInstanceOf[NullableSchema[?]]

  // The type names `SafeParameterExtractor` uses.
  private def expectedType(schema: SchemaDefinition[?]): String = schema match {
    case NullableSchema(underlying)     => expectedType(underlying)
    case _: StringSchema                => "string"
    case _: IntegerSchema               => "integer"
    case n: NumberSchema if n.isInteger => "integer"
    case _: NumberSchema                => "number"
    case _: BooleanSchema               => "boolean"
    case _: ArraySchema[?]              => "array"
    case _: ObjectSchema[?]             => "object"
  }

  private def jsonType(value: ujson.Value): String = value match {
    case _: ujson.Str  => "string"
    case _: ujson.Num  => "number"
    case _: ujson.Bool => "boolean"
    case _: ujson.Arr  => "array"
    case _: ujson.Obj  => "object"
    case ujson.Null    => "null"
  }

  // ---- a value that matches the schema but that the reader still refuses

  private enum Step {
    case Key(name: String)
    case Index(i: Int)
  }

  private val StepPattern = """\['((?:[^'\\]|\\.)*)'\]|\[(\d+)\]""".r

  // uPickle's trace names where reading failed as `$['items'][1]['amount']`.
  private def steps(jsonPath: String): List[Step] =
    StepPattern
      .findAllMatchIn(jsonPath)
      .map { m =>
        Option(m.group(1)) match {
          case Some(key) => Step.Key(key.replaceAll("""\\(.)""", "$1"))
          case None      => Step.Index(m.group(2).toInt)
        }
      }
      .toList

  private def name(path: List[Step]): String =
    path.foldLeft("") {
      case (acc, Step.Key(key)) => at(acc, key)
      case (acc, Step.Index(i)) => s"$acc[$i]"
    }

  private def schemaAt(schema: SchemaDefinition[?], path: List[Step]): Option[SchemaDefinition[?]] =
    (schema, path) match {
      case (_, Nil)                        => Some(schema)
      case (NullableSchema(underlying), _) => schemaAt(underlying, path)
      case (o: ObjectSchema[?], Step.Key(k) :: rest) =>
        o.properties.find(_.name == k).flatMap(p => schemaAt(p.schema, rest))
      case (a: ArraySchema[?], Step.Index(_) :: rest) => schemaAt(a.itemSchema, rest)
      case _                                          => None
    }

  private def valueAt(value: ujson.Value, path: List[Step]): Option[ujson.Value] =
    path.foldLeft(Option(value)) {
      case (Some(obj: ujson.Obj), Step.Key(k))   => obj.value.get(k)
      case (Some(arr: ujson.Arr), Step.Index(i)) => arr.value.lift(i)
      case _                                     => None
    }

  // A `BigDecimal` field sent as "abc", or a type with a hand-written `SchemaOf.string` whose reader refuses the
  // text: a `TypeMismatch` at the field uPickle names, with the reader's reason.
  private def unreadable(parameters: ObjectSchema[?], arguments: ujson.Value, e: Throwable): ToolParameterError = {
    val (path, cause) = e match {
      case traced: upickle.core.TraceVisitor.TraceException =>
        (steps(traced.jsonPath), Option(traced.getCause).getOrElse(traced))
      case other => (Nil, other)
    }
    val expected = schemaAt(parameters, path).map(expectedType).getOrElse("object")
    val received = valueAt(arguments, path).map(jsonType).getOrElse("unknown")
    ToolParameterError.TypeMismatch(
      if (path.isEmpty) "arguments" else name(path),
      expected,
      s"$received that cannot be read (${cause.getMessage})"
    )
  }
}
