package org.llm4s.toolapi.builtin

import org.llm4s.toolapi.builtin.http.HTTPResult
import org.llm4s.toolapi.builtin.shell.ShellResult
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.{ read, write }

import scala.concurrent.duration.*

/** The JSON a model reads from these tools keeps its keys and millisecond values. */
class ToolResultJsonSpec extends AnyFlatSpec with Matchers {

  "ShellResult" should "carry its execution time as executionTimeMs" in {
    val result = ShellResult("ls", 0, "a", "", 1234.millis, truncated = false, timedOut = false)
    val json   = ujson.read(write(result))
    json("executionTimeMs").num shouldBe 1234
    json.obj.contains("executionTime") shouldBe false
    read[ShellResult](write(result)).executionTime shouldBe 1234.millis
  }

  "HTTPResult" should "carry its response time as responseTimeMs" in {
    val result = HTTPResult("https://example.com", "GET", 200, "OK", Map.empty, "", None, 0L, false, 87.millis)
    val json   = ujson.read(write(result))
    json("responseTimeMs").num shouldBe 87
    json.obj.contains("responseTime") shouldBe false
    read[HTTPResult](write(result)).responseTime shouldBe 87.millis
  }
}
