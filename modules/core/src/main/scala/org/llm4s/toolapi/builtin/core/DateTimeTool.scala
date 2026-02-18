package org.llm4s.toolapi.builtin.core

import org.llm4s.toolapi._
import org.llm4s.types.Result
import upickle.default._

import java.time._
import java.time.format.DateTimeFormatter
import scala.util.Try

/**
 * Result from date/time operations.
 */
case class DateTimeResult(
  datetime: String,
  timezone: String,
  timestamp: Long,
  iso8601: String,
  components: DateTimeComponents
)

case class DateTimeComponents(
  year: Int,
  month: Int,
  day: Int,
  hour: Int,
  minute: Int,
  second: Int,
  dayOfWeek: String
)

object DateTimeResult {
  implicit val componentsRW: ReadWriter[DateTimeComponents] = macroRW[DateTimeComponents]
  implicit val dateTimeResultRW: ReadWriter[DateTimeResult] = macroRW[DateTimeResult]
}

/**
 * Tool for getting current date and time information.
 *
 * Features:
 * - Current date/time in any timezone
 * - Multiple output formats (ISO, human-readable)
 * - Timestamp conversion
 * - Date component extraction
 *
 * @example
 * {{{
 * import org.llm4s.toolapi.builtin.core.DateTimeTool
 *
 * val tools = new ToolRegistry(Seq(DateTimeTool.tool))
 * agent.run("What is the current time in Tokyo?", tools)
 * }}}
 */
object DateTimeTool {

  private val schema = Schema
    .`object`[Map[String, Any]]("Date/time query parameters")
    .withProperty(
      Schema.property(
        "timezone",
        Schema
          .string(
            "Timezone identifier (e.g., 'UTC', 'America/New_York', 'Europe/London', 'Asia/Tokyo'). Defaults to UTC."
          )
      )
    )
    .withProperty(
      Schema.property(
        "format",
        Schema
          .string("Output format: 'iso' for ISO-8601, 'human' for human-readable. Defaults to 'iso'.")
          .withEnum(Seq("iso", "human"))
      )
    )

  /**
   * The date/time tool instance, returning a Result for safe error handling.
   */
  val toolSafe: Result[ToolFunction[Map[String, Any], DateTimeResult]] =
    ToolBuilder[Map[String, Any], DateTimeResult](
      name = "get_current_datetime",
      description = "Get the current date and time, optionally in a specific timezone. " +
        "Returns the datetime in ISO-8601 format, Unix timestamp, and broken down components.",
      schema = schema
    ).withHandler { extractor =>
      val timezone = extractor.getString("timezone").fold(_ => "UTC", identity)
      val format   = extractor.getString("format").fold(_ => "iso", identity)

      Try {
        val zoneId = ZoneId.of(timezone)
        val now    = ZonedDateTime.now(zoneId)

        val formattedDateTime = format.toLowerCase match {
          case "human" =>
            val formatter = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm:ss a z")
            now.format(formatter)
          case _ =>
            now.format(DateTimeFormatter.ISO_ZONED_DATE_TIME)
        }

        DateTimeResult(
          datetime = formattedDateTime,
          timezone = timezone,
          timestamp = now.toInstant.toEpochMilli,
          iso8601 = now.format(DateTimeFormatter.ISO_ZONED_DATE_TIME),
          components = DateTimeComponents(
            year = now.getYear,
            month = now.getMonthValue,
            day = now.getDayOfMonth,
            hour = now.getHour,
            minute = now.getMinute,
            second = now.getSecond,
            dayOfWeek = now.getDayOfWeek.toString
          )
        )
      }.toEither.left.map(e => s"Invalid timezone '$timezone': ${e.getMessage}")
    }.buildSafe()

  /**
   * The date/time tool instance.
   *
   * @throws IllegalStateException if tool initialization fails
   */
  @deprecated("Use toolSafe which returns Result[ToolFunction] for safe error handling", "0.2.9")
  lazy val tool: ToolFunction[Map[String, Any], DateTimeResult] =
    toolSafe match {
      case Right(t) => t
      case Left(e)  => throw new IllegalStateException(s"DateTimeTool.tool lazy initialization failed: ${e.formatted}")
    }

  /**
   * Get list of common timezone identifiers.
   */
  val commonTimezones: Seq[String] = Seq(
    "UTC",
    "America/New_York",
    "America/Los_Angeles",
    "America/Chicago",
    "America/Denver",
    "Europe/London",
    "Europe/Paris",
    "Europe/Berlin",
    "Asia/Tokyo",
    "Asia/Shanghai",
    "Asia/Singapore",
    "Australia/Sydney",
    "Pacific/Auckland"
  )
}
