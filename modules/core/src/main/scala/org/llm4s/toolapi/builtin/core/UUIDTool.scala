package org.llm4s.toolapi.builtin.core

import org.llm4s.toolapi._
import org.llm4s.types.Result
import upickle.default._

import java.util.UUID

/**
 * Result from UUID generation.
 */
case class UUIDResult(
  uuid: String,
  version: Int,
  variant: String
)

object UUIDResult {
  implicit val uuidResultRW: ReadWriter[UUIDResult] = macroRW[UUIDResult]
}

/**
 * Tool for generating UUIDs.
 *
 * Generates standard UUID v4 (random) identifiers.
 *
 * @example
 * {{{
 * import org.llm4s.toolapi.builtin.core.UUIDTool
 *
 * val tools = new ToolRegistry(Seq(UUIDTool.tool))
 * agent.run("Generate a unique ID for this transaction", tools)
 * }}}
 */
object UUIDTool {

  private val schema = Schema
    .`object`[Map[String, Any]]("UUID generation parameters")
    .withProperty(
      Schema.property(
        "count",
        Schema.integer("Number of UUIDs to generate (default: 1, max: 10)")
      )
    )
    .withProperty(
      Schema.property(
        "format",
        Schema
          .string("Output format: 'standard' with dashes, 'compact' without dashes")
          .withEnum(Seq("standard", "compact"))
      )
    )

  /**
   * Result containing multiple UUIDs.
   */
  case class UUIDsResult(uuids: Seq[UUIDResult])

  object UUIDsResult {
    implicit val uuidsResultRW: ReadWriter[UUIDsResult] = macroRW[UUIDsResult]
  }

  /**
   * The UUID generator tool instance, returning a Result for safe error handling.
   */
  val toolSafe: Result[ToolFunction[Map[String, Any], UUIDsResult]] =
    ToolBuilder[Map[String, Any], UUIDsResult](
      name = "generate_uuid",
      description = "Generate one or more universally unique identifiers (UUIDs). " +
        "Useful for creating unique IDs for transactions, records, or entities.",
      schema = schema
    ).withHandler { extractor =>
      val count  = extractor.getInt("count").fold(_ => 1, identity).min(10).max(1)
      val format = extractor.getString("format").fold(_ => "standard", identity)

      val uuids = (1 to count).map { _ =>
        val uuid = UUID.randomUUID()
        val uuidStr = format.toLowerCase match {
          case "compact" => uuid.toString.replace("-", "")
          case _         => uuid.toString
        }

        UUIDResult(
          uuid = uuidStr,
          version = uuid.version(),
          variant = uuid.variant() match {
            case 0 => "NCS"
            case 2 => "Leach-Salz (standard)"
            case 6 => "Microsoft"
            case 7 => "Future"
            case _ => "Unknown"
          }
        )
      }

      Right(UUIDsResult(uuids))
    }.buildSafe()

  /**
   * The UUID generator tool instance.
   *
   * @throws IllegalStateException if tool initialization fails
   */
  @deprecated("Use toolSafe which returns Result[ToolFunction] for safe error handling", "0.2.9")
  lazy val tool: ToolFunction[Map[String, Any], UUIDsResult] =
    toolSafe match {
      case Right(t) => t
      case Left(e)  => throw new IllegalStateException(s"UUIDTool.tool lazy initialization failed: ${e.formatted}")
    }
}
