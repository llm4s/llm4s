package org.llm4s.toolapi.builtin

import org.llm4s.toolapi.ToolFunction
import org.llm4s.toolapi.builtin.core.{ UUIDResult, UUIDTool }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.{ read, write }

import java.util.UUID
import java.util.concurrent.{ CountDownLatch, Executors, ThreadFactory }
import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future }

/**
 * Contract of the `generate_uuid` tool.
 *
 * The output is random, so these tests assert invariants of each UUID (read back from the string itself, not only from
 * the tool's own `version` and `variant` fields) and never exact values. Uniqueness is checked over 10,000 version 4
 * UUIDs; a collision there has a probability of about 1e-30.
 */
class UUIDToolSpec extends AnyFlatSpec with Matchers {

  private val tool: ToolFunction[Map[String, Any], UUIDTool.UUIDsResult] =
    UUIDTool.toolSafe.fold(e => fail(s"Tool creation failed: ${e.formatted}"), identity)

  /** Runs the tool through `execute`, the entry point providers use, and reads the JSON result back. */
  private def generate(args: ujson.Value): Seq[UUIDResult] =
    tool
      .execute(args)
      .fold(e => fail(s"Expected a result but got $e"), json => read[UUIDTool.UUIDsResult](json).uuids)

  private val Standard = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
  private val Compact  = "^[0-9a-f]{32}$"

  /** Inserts the dashes of the standard form into a compact UUID. */
  private def dashed(compact: String): String =
    s"${compact.substring(0, 8)}-${compact.substring(8, 12)}-${compact.substring(12, 16)}-" +
      s"${compact.substring(16, 20)}-${compact.substring(20)}"

  "UUIDTool" should "be registered under the name providers call it by" in {
    tool.name shouldBe "generate_uuid"
  }

  it should "expose count as an integer and format as an enum of exactly standard and compact" in {
    val properties = tool.toOpenAITool(strict = false)("function")("parameters")("properties")
    properties("count")("type").str shouldBe "integer"
    properties("format")("type").str shouldBe "string"
    properties("format")("enum").arr.map(_.str).toSet shouldBe Set("standard", "compact")
  }

  it should "generate exactly one standard-format UUID when called with no parameters" in {
    val uuids = generate(ujson.Obj())
    uuids should have size 1
    uuids.head.uuid should fullyMatch.regex(Standard)
  }

  it should "generate UUIDs that java.util.UUID parses back to the same text" in {
    generate(ujson.Obj("count" -> 10)).foreach(u => UUID.fromString(u.uuid).toString shouldBe u.uuid)
  }

  it should "generate version 4 UUIDs, judged from the UUID text and not only from the reported field" in {
    generate(ujson.Obj("count" -> 10)).foreach { u =>
      // the version is the first hex digit of the third group: index 14 of the standard text
      u.uuid.charAt(14) shouldBe '4'
      UUID.fromString(u.uuid).version() shouldBe 4
      u.version shouldBe 4
    }
  }

  it should "generate RFC 4122 variant UUIDs, judged from the UUID text, and report that variant" in {
    generate(ujson.Obj("count" -> 10)).foreach { u =>
      // the variant is the top bits of the first hex digit of the fourth group: index 19; 10xx means 8, 9, a or b
      "89ab" should include(u.uuid.charAt(19).toString)
      UUID.fromString(u.uuid).variant() shouldBe 2
      u.variant shouldBe "Leach-Salz (standard)"
    }
  }

  it should "generate lowercase hexadecimal text in the standard format" in {
    generate(ujson.Obj("count" -> 10, "format" -> "standard")).foreach(_.uuid should fullyMatch.regex(Standard))
  }

  it should "generate 32 lowercase hexadecimal characters, with no dashes, in the compact format" in {
    val uuids = generate(ujson.Obj("count" -> 10, "format" -> "compact"))
    uuids should have size 10
    uuids.foreach(_.uuid should fullyMatch.regex(Compact))
  }

  it should "generate a compact UUID that is the standard form without its dashes (version and variant intact)" in {
    generate(ujson.Obj("count" -> 10, "format" -> "compact")).foreach { u =>
      val parsed = UUID.fromString(dashed(u.uuid))
      parsed.version() shouldBe 4
      parsed.variant() shouldBe 2
      parsed.toString.replace("-", "") shouldBe u.uuid
      u.version shouldBe 4
    }
  }

  it should "read the format name without regard to case" in {
    generate(ujson.Obj("format" -> "COMPACT")).head.uuid should fullyMatch.regex(Compact)
    generate(ujson.Obj("format" -> "Compact")).head.uuid should fullyMatch.regex(Compact)
    generate(ujson.Obj("format" -> "STANDARD")).head.uuid should fullyMatch.regex(Standard)
  }

  it should "fall back to the standard format for an unknown, empty or wrong-typed format" in {
    generate(ujson.Obj("format" -> "weird")).head.uuid should fullyMatch.regex(Standard)
    generate(ujson.Obj("format" -> "")).head.uuid should fullyMatch.regex(Standard)
    generate(ujson.Obj("format" -> 7)).head.uuid should fullyMatch.regex(Standard)
    generate(ujson.Obj("format" -> ujson.Null)).head.uuid should fullyMatch.regex(Standard)
  }

  it should "generate exactly as many UUIDs as count asks for, from 1 to 10" in {
    (1 to 10).foreach(n => withClue(s"count = $n: ")(generate(ujson.Obj("count" -> n)) should have size n.toLong))
  }

  it should "raise a count of zero or less to one UUID" in {
    generate(ujson.Obj("count" -> 0)) should have size 1
    generate(ujson.Obj("count" -> -3)) should have size 1
    generate(ujson.Obj("count" -> Int.MinValue)) should have size 1
  }

  it should "cap a count above ten at ten UUIDs, however large" in {
    generate(ujson.Obj("count" -> 11)) should have size 10
    generate(ujson.Obj("count" -> 100000)) should have size 10
    generate(ujson.Obj("count" -> Int.MaxValue)) should have size 10
  }

  it should "accept a whole number written with a fraction, such as 3.0, as a count" in {
    generate(ujson.Obj("count" -> 3.0)) should have size 3
  }

  it should "use the default count of one when count is not a whole number or not a number" in {
    // The handler falls back to the documented default instead of reporting a type mismatch.
    generate(ujson.Obj("count" -> 3.5)) should have size 1
    generate(ujson.Obj("count" -> "5")) should have size 1
    generate(ujson.Obj("count" -> ujson.Null)) should have size 1
    generate(ujson.Obj("count" -> 2147483648.0)) should have size 1
  }

  it should "return the number of UUIDs asked for in the requested format when both parameters are given" in {
    val uuids = generate(ujson.Obj("count" -> 4, "format" -> "compact"))
    uuids should have size 4
    uuids.foreach(_.uuid should fullyMatch.regex(Compact))
  }

  it should "generate distinct UUIDs within a single call" in {
    val uuids = generate(ujson.Obj("count" -> 10)).map(_.uuid)
    uuids.distinct should have size 10
  }

  it should "generate distinct UUIDs across 10,000 UUIDs from repeated calls" in {
    val all = (1 to 1000).flatMap(_ => generate(ujson.Obj("count" -> 10)).map(_.uuid))
    all should have size 10000
    all.toSet should have size 10000
  }

  it should "write the result as the uuids array that providers read, with uuid, version and variant per entry" in {
    val json = tool.execute(ujson.Obj("count" -> 2)).fold(e => fail(s"Expected a result but got $e"), identity)
    json.obj.keySet shouldBe Set("uuids")
    json("uuids").arr should have size 2
    json("uuids").arr.foreach { entry =>
      entry.obj.keySet shouldBe Set("uuid", "version", "variant")
      entry("uuid").str should fullyMatch.regex(Standard)
      entry("version").num shouldBe 4.0
      entry("variant").str shouldBe "Leach-Salz (standard)"
    }
  }

  it should "round-trip its result through the uPickle ReadWriter unchanged" in {
    val result = UUIDTool.UUIDsResult(generate(ujson.Obj("count" -> 5)))
    read[UUIDTool.UUIDsResult](write(result)) shouldBe result
    result.uuids.foreach(u => read[UUIDResult](write(u)) shouldBe u)
  }

  it should "round-trip a hand-built result, so the check does not depend on the generator" in {
    val handBuilt = UUIDResult("123e4567-e89b-42d3-a456-426614174000", 4, "Leach-Salz (standard)")
    read[UUIDResult](write(handBuilt)) shouldBe handBuilt
    ujson.read(write(handBuilt)) shouldBe ujson.Obj(
      "uuid"    -> "123e4567-e89b-42d3-a456-426614174000",
      "version" -> 4,
      "variant" -> "Leach-Salz (standard)"
    )
  }

  it should "stay correct and distinct when called from many threads at once" in {
    val threads = 8
    val calls   = 50
    val pool = Executors.newFixedThreadPool(
      threads,
      new ThreadFactory {
        override def newThread(r: Runnable): Thread = {
          val t = new Thread(r, "uuid-tool-spec")
          t.setDaemon(true)
          t
        }
      }
    )
    val ec: ExecutionContext = ExecutionContext.fromExecutor(pool)
    val start                = new CountDownLatch(1)

    val workers = (1 to threads).map { _ =>
      Future {
        start.await()
        (1 to calls).flatMap(_ => generate(ujson.Obj("count" -> 10)))
      }(ec)
    }
    start.countDown()
    val all = Await.result(Future.sequence(workers)(implicitly, ec), 60.seconds).flatten
    pool.shutdown()

    all should have size (threads * calls * 10).toLong
    all.map(_.uuid).toSet should have size (threads * calls * 10).toLong
    all.foreach { u =>
      u.uuid should fullyMatch.regex(Standard)
      UUID.fromString(u.uuid).version() shouldBe 4
    }
  }
}
