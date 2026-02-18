package org.llm4s.toolapi.builtin.core

import org.llm4s.toolapi._
import org.llm4s.types.Result
import upickle.default._

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

  /**
   * The JSON tool instance.
   *
   * @throws IllegalStateException if tool initialization fails
   */
  @deprecated("Use toolSafe which returns Result[ToolFunction] for safe error handling", "0.2.9")
  lazy val tool: ToolFunction[Map[String, Any], JSONResult] =
    toolSafe match {
      case Right(t) => t
      case Left(e)  => throw new IllegalStateException(s"JSONTool.tool lazy initialization failed: ${e.formatted}")
    }

  private def processJSON(
    operation: String,
    jsonStr: String,
    pathOpt: Option[String]
  ): Either[String, JSONResult] =
    operation.toLowerCase match {
      case "parse" =>
        Try(ujson.read(jsonStr)).toEither.left
          .map(e => s"Invalid JSON: ${e.getMessage}")
          .map { parsed =>
            JSONResult(
              success = true,
              result = parsed,
              formatted = ujson.write(parsed, indent = 2)
            )
          }

      case "format" =>
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

      case "query" =>
        pathOpt match {
          case None =>
            Left("Query operation requires a 'path' parameter")
          case Some(path) =>
            Try(ujson.read(jsonStr)).toEither.left
              .map(e => s"Invalid JSON: ${e.getMessage}")
              .flatMap { parsed =>
                queryPath(parsed, path).map { value =>
                  JSONResult(
                    success = true,
                    result = value,
                    formatted = value match {
                      case s: ujson.Str => s.value
                      case other        => ujson.write(other, indent = 2)
                    }
                  )
                }
              }
        }

      case "validate" =>
        val isValid = Try(ujson.read(jsonStr)).isSuccess
        Right(
          JSONResult(
            success = isValid,
            result = ujson.Bool(isValid),
            formatted = if (isValid) "Valid JSON" else "Invalid JSON"
          )
        )

      case other =>
        Left(s"Unknown operation: $other. Supported: parse, format, query, validate")
    }

  /**
   * Query a JSON value using path notation.
   * Supports: object.field, array[0], nested paths like data.users[0].name
   */
  private def queryPath(json: ujson.Value, path: String): Either[String, ujson.Value] = {
    val pathParts = parsePathParts(path)

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
  }

  sealed private trait PathPart
  private case class ArrayIndex(index: Int) extends PathPart
  private case class ObjectKey(key: String) extends PathPart

  private def parsePathParts(path: String): Seq[PathPart] = {
    // Split on dots, but handle array notation
    val parts   = scala.collection.mutable.ArrayBuffer[PathPart]()
    var current = path

    while (current.nonEmpty) {
      // Check for array index
      val arrayPattern = """^\[(\d+)\](.*)""".r
      val keyPattern   = """^\.?([^\.\[\]]+)(.*)""".r

      current match {
        case arrayPattern(idx, rest) =>
          parts += ArrayIndex(idx.toInt)
          current = rest

        case keyPattern(key, rest) =>
          parts += ObjectKey(key)
          current = rest

        case _ =>
          current = "" // End parsing
      }
    }

    parts.toSeq
  }
}
