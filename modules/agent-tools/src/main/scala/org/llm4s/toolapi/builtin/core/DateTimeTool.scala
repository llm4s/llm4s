package org.llm4s.toolapi.builtin.core

import org.llm4s.toolapi._
import org.llm4s.types.Result
import upickle.default._

import java.time._
import java.time.format.DateTimeFormatter
import java.util.Locale
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

  private enum Format {
    case Iso, Human
  }

  /**
   * The `human` format. The locale is fixed, not the host's default, so the same call reads the same on every host: a
   * model sees English month and weekday names and an `AM`/`PM` marker whatever `-Duser.language` the JVM runs with.
   */
  private val HumanFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm:ss a z", Locale.US)

  private val SupportedFormats: Seq[String] = Seq("iso", "human")

  /** Case-insensitive, like the parameter always was; anything else is an error rather than a silent ISO fallback. */
  private def parseFormat(value: String): Either[String, Format] =
    value.toLowerCase(Locale.ROOT) match {
      case "iso"   => Right(Format.Iso)
      case "human" => Right(Format.Human)
      case _ =>
        Left(s"Unsupported format '$value': supported formats are ${SupportedFormats.map(f => s"'$f'").mkString(", ")}")
    }

  private val schema = Schema
    .`object`[Map[String, Any]]("Date/time query parameters")
    .withProperty(
      Schema.property(
        "timezone",
        Schema.nullable(
          Schema
            .string(
              "Timezone identifier (e.g., 'UTC', 'America/New_York', 'Europe/London', 'Asia/Tokyo'). Defaults to UTC when omitted or null."
            )
        ),
        required = false
      )
    )
    .withProperty(
      Schema.property(
        "format",
        Schema.nullable(
          Schema
            .string(
              "Output format: 'iso' for ISO-8601, 'human' for human-readable. Defaults to 'iso' when omitted or null."
            )
            .withEnum(SupportedFormats)
        ),
        required = false
      )
    )

  /**
   * The date/time tool instance, returning a Result for safe error handling.
   */
  val toolSafe: Result[ToolFunction[Map[String, Any], DateTimeResult]] = toolSafe(Clock.systemUTC())

  /**
   * The tool reading the time from `clock`, so tests can pin the instant (a daylight-saving transition, a year end).
   * Only the clock's instant is used; its zone is ignored, the `timezone` parameter decides that.
   */
  private[core] def toolSafe(clock: Clock): Result[ToolFunction[Map[String, Any], DateTimeResult]] =
    ToolBuilder[Map[String, Any], DateTimeResult](
      name = "get_current_datetime",
      description = "Get the current date and time, optionally in a specific timezone. " +
        "Returns the datetime in ISO-8601 format, Unix timestamp, and broken down components.",
      schema = schema
    ).withHandler { extractor =>
      for {
        timezoneParam <- extractor.getOptionalString("timezone").left.map(_.getMessage)
        formatParam   <- extractor.getOptionalString("format").left.map(_.getMessage)
        timezone = timezoneParam.getOrElse("UTC")
        format <- parseFormat(formatParam.getOrElse("iso"))
        zoneId <- Try(ZoneId.of(timezone)).toEither.left.map(e => s"Invalid timezone '$timezone': ${e.getMessage}")
      } yield describe(ZonedDateTime.ofInstant(clock.instant(), zoneId), timezone, format)
    }.buildSafe()

  private def describe(now: ZonedDateTime, timezone: String, format: Format): DateTimeResult = {
    val iso = now.format(DateTimeFormatter.ISO_ZONED_DATE_TIME)
    DateTimeResult(
      datetime = format match {
        case Format.Human => now.format(HumanFormatter)
        case Format.Iso   => iso
      },
      timezone = timezone,
      timestamp = now.toInstant.toEpochMilli,
      iso8601 = iso,
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
