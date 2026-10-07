package org.llm4s.toolapi.builtin.core

import org.llm4s.toolapi._
import org.llm4s.types.Result
import upickle.default._

import scala.annotation.tailrec
import scala.util.Try

/**
 * Result from JSON operations.
 */
case class JSONResult(
  success: Boolean,
  result: ujson.Value,
  formatted: String
)

object JSONResult {
  // ujson.Value has built-in serialization support via upickle
  implicit val jsonResultRW: ReadWriter[JSONResult] = macroRW[JSONResult]
}

/**
 * Tool for JSON parsing, formatting, and querying.
 *
 * Operations:
 * - parse: Parse JSON string into structured data
 * - format: Pretty-print JSON
 * - query: Extract value using path (e.g., "data.users[0].name")
 * - validate: Check if string is valid JSON
 *
 * Limits and errors: a document nested more than 512 levels deep (arrays and objects counted together) is
 * refused by every operation with an error instead of being read or written, because pretty-printing recurses
 * once per level. A query path must be read completely: an empty segment (`a..b`, `a.`), an index that is not a
 * non-negative whole number (`a[x]`, `a[-1]`), or any other text the path syntax does not cover is an error
 * naming the text it stopped at, and an array index too large for an `Int` is an error naming the index.
 *
 * @example
 * {{{
 * import org.llm4s.toolapi.builtin.core.JSONTool
 *
 * val tools = new ToolRegistry(Seq(JSONTool.tool))
 * agent.run("Parse this JSON and extract the user's email", tools)
 * }}}
 */
object JSONTool {

  private val schema = Schema
    .`object`[Map[String, Any]]("JSON operation parameters")
    .withProperty(
      Schema.property(
        "operation",
        Schema
          .string("Operation to perform on JSON")
          .withEnum(Seq("parse", "format", "query", "validate"))
      )
    )
    .withProperty(
      Schema.property(
        "json",
        Schema.string("JSON string to process")
      )
    )
    .withProperty(
      Schema.property(
        "path",
        Schema.string(
          "JSON path for query operation (e.g., 'data.users[0].name'). " +
            "Use dot notation for objects, brackets for arrays."
        )
      )
    )

  /**
   * The JSON tool instance, returning a Result for safe error handling.
   */
  val toolSafe: Result[ToolFunction[Map[String, Any], JSONResult]] =
    ToolBuilder[Map[String, Any], JSONResult](
      name = "json_tool",
      description = "Parse, format, query, or validate JSON data. " +
        "Use 'parse' to convert JSON string to structured data, " +
        "'format' to pretty-print JSON, " +
        "'query' to extract values using path notation (e.g., 'data.users[0].name'), " +
        "'validate' to check if a string is valid JSON.",
      schema = schema
    ).withHandler { extractor =>
      for {
        operation <- extractor.getString("operation")
        json      <- extractor.getString("json")
        pathOpt = extractor.getString("path").toOption
        result <- processJSON(operation, json, pathOpt)
      } yield result
    }.buildSafe()

  private def processJSON(
    operation: String,
    jsonStr: String,
    pathOpt: Option[String]
  ): Either[String, JSONResult] =
    operation.toLowerCase match {
      case "parse" =>
        withinDepthLimit(jsonStr).flatMap { _ =>
          Try(ujson.read(jsonStr)).toEither.left
            .map(e => s"Invalid JSON: ${e.getMessage}")
            .map { parsed =>
              JSONResult(
                success = true,
                result = parsed,
                formatted = ujson.write(parsed, indent = 2)
              )
            }
        }

      case "format" =>
        withinDepthLimit(jsonStr).flatMap { _ =>
          Try(ujson.read(jsonStr)).toEither.left
            .map(e => s"Invalid JSON: ${e.getMessage}")
            .map { parsed =>
              val formatted = ujson.write(parsed, indent = 2)
              JSONResult(
                success = true,
                result = parsed,
                formatted = formatted
              )
            }
        }

      case "query" =>
        pathOpt match {
          case None =>
            Left("Query operation requires a 'path' parameter")
          case Some(path) =>
            for {
              _     <- withinDepthLimit(jsonStr)
              parts <- parsePathParts(path)
              parsed <- Try(ujson.read(jsonStr)).toEither.left
                .map(e => s"Invalid JSON: ${e.getMessage}")
              value <- queryPath(parsed, parts)
            } yield JSONResult(
              success = true,
              result = value,
              formatted = value match {
                case s: ujson.Str => s.value
                case other        => ujson.write(other, indent = 2)
              }
            )
        }

      case "validate" =>
        withinDepthLimit(jsonStr).map { _ =>
          val isValid = Try(ujson.read(jsonStr)).isSuccess
          JSONResult(
            success = isValid,
            result = ujson.Bool(isValid),
            formatted = if (isValid) "Valid JSON" else "Invalid JSON"
          )
        }

      case other =>
        Left(s"Unknown operation: $other. Supported: parse, format, query, validate")
    }

  /** Deepest nesting (arrays and objects together) any operation accepts; see the object's Scaladoc. */
  private val MaxNestingDepth = 512

  /**
   * Refuse a document nested deeper than [[MaxNestingDepth]] before it is read or written.
   *
   * The depth is measured on the raw text, ignoring brackets inside string literals, so a document that would
   * overflow the stack is never handed to the parser or the writer. Malformed text is not judged here: the
   * parser reports it.
   */
  private def withinDepthLimit(jsonStr: String): Either[String, Unit] =
    if (maxNesting(jsonStr) > MaxNestingDepth)
      Left(s"JSON is nested more than $MaxNestingDepth levels deep, which is more than this tool reads or writes")
    else Right(())

  /** The deepest nesting in `text`, stopping as soon as it passes [[MaxNestingDepth]]. */
  private def maxNesting(text: String): Int = {
    @tailrec
    def scan(i: Int, depth: Int, deepest: Int, inString: Boolean, escaped: Boolean): Int =
      if (i >= text.length || deepest > MaxNestingDepth) deepest
      else {
        val c = text.charAt(i)
        if (inString) {
          if (escaped) scan(i + 1, depth, deepest, inString = true, escaped = false)
          else if (c == '\\') scan(i + 1, depth, deepest, inString = true, escaped = true)
          else if (c == '"') scan(i + 1, depth, deepest, inString = false, escaped = false)
          else scan(i + 1, depth, deepest, inString = true, escaped = false)
        } else if (c == '"') scan(i + 1, depth, deepest, inString = true, escaped = false)
        else if (c == '[' || c == '{')
          scan(i + 1, depth + 1, math.max(deepest, depth + 1), inString = false, escaped = false)
        else if (c == ']' || c == '}') scan(i + 1, math.max(0, depth - 1), deepest, inString = false, escaped = false)
        else scan(i + 1, depth, deepest, inString = false, escaped = false)
      }
    scan(0, 0, 0, inString = false, escaped = false)
  }

  /**
   * Query a JSON value using path notation.
   * Supports: object.field, array[0], nested paths like data.users[0].name
   */
  private def queryPath(json: ujson.Value, pathParts: Seq[PathPart]): Either[String, ujson.Value] =
    pathParts.foldLeft[Either[String, ujson.Value]](Right(json)) { case (current, part) =>
      current.flatMap { value =>
        part match {
          case ArrayIndex(idx) =>
            Try(value.arr(idx)).toEither.left
              .map(_ => s"Array index $idx out of bounds or not an array")

          case ObjectKey(key) =>
            Try(value.obj(key)).toEither.left
              .map(_ => s"Key '$key' not found or not an object")
        }
      }
    }

  sealed private trait PathPart
  private case class ArrayIndex(index: Int) extends PathPart
  private case class ObjectKey(key: String) extends PathPart

  private val ArrayPattern = """(?s)^\[(\d+)\](.*)""".r
  private val KeyPattern   = """(?s)^\.?([^\.\[\]]+)(.*)""".r

  /**
   * Read the whole path, or say where it stops making sense.
   *
   * A path that cannot be read to the end is an error naming the text left over (`..b` for `a..b`, `[x]` for
   * `a[x]`), and an index that does not fit in an `Int` is an error naming the index.
   */
  private def parsePathParts(path: String): Either[String, Seq[PathPart]] = {
    @tailrec
    def loop(remaining: String, acc: Vector[PathPart]): Either[String, Seq[PathPart]] =
      if (remaining.isEmpty) Right(acc)
      else
        remaining match {
          case ArrayPattern(idx, rest) =>
            Try(idx.toInt).toOption match {
              case Some(i) => loop(rest, acc :+ ArrayIndex(i))
              case None    => Left(s"Array index $idx is too large")
            }
          case KeyPattern(key, rest) =>
            loop(rest, acc :+ ObjectKey(key))
          case _ =>
            Left(s"Invalid path: cannot read '$remaining' (use key.key[0] notation)")
        }

    loop(path, Vector.empty)
  }
}
