package org.llm4s.toolapi.builtin.core

import org.llm4s.toolapi.{ SafeParameterExtractor, ToolCallError }
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.{ Instant, ZoneId, ZonedDateTime }

/**
 * Tests for [[DateTimeTool]].
 *
 * The tool reads the current time directly (`ZonedDateTime.now`) and has no clock seam, so nothing here asserts a
 * wall-clock value. Each result is instead checked against ITSELF and against `java.time`: the `timestamp` has to be
 * close to the moment the test called the tool, and everything else (`components`, `iso8601`, the offset) has to agree
 * with that timestamp in the requested zone. That holds whenever the suite runs, in any default timezone or locale.
 */
class DateTimeToolSpec extends AnyFlatSpec with Matchers with OptionValues {

  private val ToolName = "get_current_datetime"

  /** Generous: this only has to tell milliseconds from seconds or from zero, never to measure latency. */
  private val ClockToleranceMillis = 10000L

  // A def, not a val: `fail` during construction would trip the compiler's strict-initialisation check.
  private def tool = DateTimeTool.toolSafe.fold(e => fail(s"Tool creation failed: ${e.formatted}"), identity)

  private def call(params: (String, ujson.Value)*): Either[String, DateTimeResult] =
    tool.handler(SafeParameterExtractor(ujson.Obj.from(params)))

  private def callOk(params: (String, ujson.Value)*): DateTimeResult =
    call(params: _*).fold(err => fail(s"Expected Right but got Left: $err"), identity)

  /** The result's own instant, read back in the zone the caller asked for. */
  private def local(result: DateTimeResult): ZonedDateTime =
    Instant.ofEpochMilli(result.timestamp).atZone(ZoneId.of(result.timezone))

  // Zones in the forms ZoneId.of accepts, none of which depends on the host: regions with and without daylight saving
  // (America/New_York, Europe/Berlin, Australia/Sydney), a half-hour offset, a 13 hour offset, and the fixed forms.
  private val zones = Seq(
    "UTC",
    "America/New_York",
    "Europe/Berlin",
    "Asia/Kolkata",
    "Australia/Sydney",
    "Pacific/Auckland",
    "+05:30",
    "-09:30",
    "UTC+2",
    "GMT",
    "Z"
  )

  private val invalidTimezones = Seq(
    "Invalid/Timezone",
    "Europe/Atlantis",
    "utc", // zone ids are case-sensitive
    "EST", // the short ids (EST, PST, ...) are not accepted
    "",
    "   ",
    "A" * 300,
    "UTC+99:99",
    "Mars/Olympus_Mons"
  )

  // ---- the shape of the returned JSON

  "DateTimeTool" should "return exactly the documented JSON fields, typed as documented" in {
    val json = tool.execute(ujson.Obj("timezone" -> "Europe/Berlin")).fold(e => fail(s"Expected Right: $e"), identity)

    json.obj.keySet shouldBe Set("datetime", "timezone", "timestamp", "iso8601", "components")
    json("timezone").str shouldBe "Europe/Berlin"
    json("datetime").str.nonEmpty shouldBe true
    json("iso8601").str.nonEmpty shouldBe true
    json("timestamp").num should be > 0.0

    val components = json("components").obj
    components.keySet shouldBe Set("year", "month", "day", "hour", "minute", "second", "dayOfWeek")
    Seq("year", "month", "day", "hour", "minute", "second").foreach(k => components(k).num.isWhole shouldBe true)
    (components("dayOfWeek").str should fullyMatch).regex("[A-Z]+DAY")
  }

  it should "report the weekday as the upper-case English java.time name, whatever the host locale" in {
    val result = callOk("timezone" -> "Asia/Tokyo")
    result.components.dayOfWeek shouldBe local(result).getDayOfWeek.toString
  }

  // ---- the current time, without asserting a wall-clock value

  it should "stamp the result with the moment it was called, in epoch milliseconds" in {
    val before = System.currentTimeMillis()
    val result = callOk()
    val after  = System.currentTimeMillis()

    result.timestamp should be >= (before - ClockToleranceMillis)
    result.timestamp should be <= (after + ClockToleranceMillis)
  }

  it should "give a timestamp that does not depend on the requested timezone" in {
    val before  = System.currentTimeMillis()
    val results = zones.map(z => z -> callOk("timezone" -> z))
    val after   = System.currentTimeMillis()

    results.foreach { case (_, r) =>
      r.timestamp should be >= (before - ClockToleranceMillis)
      r.timestamp should be <= (after + ClockToleranceMillis)
    }
  }

  // ---- the fields agree with each other and with java.time, in every zone

  zones.foreach { zone =>
    it should s"keep timestamp, components, iso8601 and offset consistent for '$zone'" in {
      val result = callOk("timezone" -> zone)
      val zoned  = local(result)

      result.timezone shouldBe zone

      val c = result.components
      (c.year, c.month, c.day) shouldBe ((zoned.getYear, zoned.getMonthValue, zoned.getDayOfMonth))
      (c.hour, c.minute, c.second) shouldBe ((zoned.getHour, zoned.getMinute, zoned.getSecond))
      c.dayOfWeek shouldBe zoned.getDayOfWeek.toString

      val parsed = ZonedDateTime.parse(result.iso8601)
      parsed.toInstant.toEpochMilli shouldBe result.timestamp
      parsed.getOffset shouldBe zoned.getOffset
      parsed.getOffset shouldBe ZoneId.of(zone).getRules.getOffset(Instant.ofEpochMilli(result.timestamp))
    }
  }

  // Zones without daylight saving, and the fixed-offset forms, have one offset all year: the expectation is a constant of
  // the zone, not something derived from the clock.
  Seq(
    "Asia/Kolkata" -> (5 * 3600 + 30 * 60),
    "Asia/Tokyo"   -> 9 * 3600,
    "+05:30"       -> (5 * 3600 + 30 * 60),
    "-09:30"       -> -(9 * 3600 + 30 * 60),
    "UTC+2"        -> 2 * 3600,
    "GMT"          -> 0,
    "Z"            -> 0,
    "UTC"          -> 0
  ).foreach { case (zone, offsetSeconds) =>
    it should s"report the fixed offset of '$zone' ($offsetSeconds seconds)" in {
      ZonedDateTime.parse(callOk("timezone" -> zone).iso8601).getOffset.getTotalSeconds shouldBe offsetSeconds
    }
  }

  // ---- defaults

  it should "default to UTC and the ISO format when given no parameters" in {
    val result = callOk()
    result.timezone shouldBe "UTC"
    result.datetime shouldBe result.iso8601
    // `[UTC]` and a zero offset: not the host's default zone, which this suite does not control.
    result.iso8601 should endWith("Z[UTC]")
    ZonedDateTime.parse(result.iso8601).getOffset.getTotalSeconds shouldBe 0
  }

  it should "ignore parameters it does not know" in {
    val result = callOk("unknown" -> "x", "timezone" -> "Asia/Tokyo")
    result.timezone shouldBe "Asia/Tokyo"
  }

  // ---- formats

  it should "return the ISO-8601 string as `datetime` for the explicit 'iso' format" in {
    val result = callOk("format" -> "iso", "timezone" -> "America/New_York")
    result.datetime shouldBe result.iso8601
    ZonedDateTime.parse(result.datetime).toInstant.toEpochMilli shouldBe result.timestamp
  }

  Seq("human", "HUMAN", "Human").foreach { format =>
    it should s"return a human-readable `datetime` for the format '$format', leaving iso8601 as ISO" in {
      // The month and weekday names, AM/PM and the zone name follow the host's default locale, so only the parts that
      // do not are asserted: the digits (`DateTimeFormatter.ofPattern` always writes ASCII digits, even under an
      // Arabic default locale), their order, and the literal " at ".
      val result = callOk("format" -> format, "timezone" -> "Europe/Berlin")
      val zoned  = local(result)
      val hour12 = if (zoned.getHour % 12 == 0) 12 else zoned.getHour % 12
      // Not `f"%02d"`: that formatter follows the default locale too, and would write Arabic-Indic digits under `ar`.
      def pad2(n: Int): String = if (n < 10) s"0$n" else n.toString
      val clock                = s"$hour12:${pad2(zoned.getMinute)}:${pad2(zoned.getSecond)}"

      result.datetime should not be result.iso8601
      result.datetime should include(s"${zoned.getDayOfMonth}, ${zoned.getYear} at $clock")
      // `iso8601` stays the ISO string whichever format was asked for.
      ZonedDateTime.parse(result.iso8601).toInstant.toEpochMilli shouldBe result.timestamp
      result.iso8601 should include("[Europe/Berlin]")
    }
  }

  // ---- timezones

  "DateTimeTool.commonTimezones" should "list only identifiers the tool itself accepts" in {
    DateTimeTool.commonTimezones should not be empty
    DateTimeTool.commonTimezones.distinct shouldBe DateTimeTool.commonTimezones
    DateTimeTool.commonTimezones should contain("UTC")

    DateTimeTool.commonTimezones.foreach { zone =>
      val result = callOk("timezone" -> zone)
      result.timezone shouldBe zone
      ZonedDateTime.parse(result.iso8601).getZone shouldBe ZoneId.of(zone)
    }
  }

  it should "contain region identifiers that exist in this JVM's time-zone database" in {
    val available = ZoneId.getAvailableZoneIds
    DateTimeTool.commonTimezones.filterNot(_ == "UTC").foreach(zone => available should contain(zone))
  }

  // ---- invalid timezones

  "DateTimeTool with an invalid timezone" should "return a Left that echoes the timezone it refused, without throwing" in {
    invalidTimezones.foreach { zone =>
      val result = call("timezone" -> zone)
      withClue(s"timezone '${zone.take(20)}': ") {
        result.isLeft shouldBe true
        // The tool's own quoted echo of what the caller sent: that is how a model learns which value was refused. The
        // JDK's wording after the colon is deliberately not asserted (it already repeats the id, so asserting it would
        // not prove the tool names the timezone).
        result.left.toOption.value should startWith(s"Invalid timezone '$zone'")
      }
    }
  }

  it should "surface as a HandlerError of this tool when executed" in {
    invalidTimezones.foreach { zone =>
      tool.execute(ujson.Obj("timezone" -> zone)) match {
        case Left(ToolCallError.HandlerError(name, message)) =>
          withClue(s"timezone '${zone.take(20)}': ") {
            name shouldBe ToolName
            message should include("Invalid timezone")
          }
        case other => fail(s"Expected a HandlerError for '${zone.take(20)}', got: $other")
      }
    }
  }

  it should "still accept a valid timezone after a failed call (no state is kept)" in {
    call("timezone" -> "Invalid/Timezone").isLeft shouldBe true
    callOk("timezone" -> "Asia/Kolkata").timezone shouldBe "Asia/Kolkata"
  }

  // ---- parameters of the wrong type

  it should "reject a timezone that is not a string, rather than silently answering in UTC" in {
    // Today a non-string `timezone` is treated as if it were absent and the call answers in UTC: a model that sends
    // `5` is told the UTC time without any hint that its argument was ignored. This pins the behaviour that SHOULD
    // hold; `pendingUntilFixed` keeps the gap visible and fails, asking for this to become a normal test, once fixed.
    pendingUntilFixed {
      call("timezone" -> ujson.Num(5)).isLeft shouldBe true
    }
  }
}
