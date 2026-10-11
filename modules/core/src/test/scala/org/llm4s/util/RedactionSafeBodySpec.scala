package org.llm4s.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Random

/**
 * `safeBody` on a body longer than its whole-redaction limit redacts a prefix that ends before a separator and runs
 * well past what it keeps, instead of the whole body (#1680); a body up to the limit is redacted whole, as before.
 */
class RedactionSafeBodySpec extends AnyFlatSpec with Matchers {

  private val Limit = 128 * 1024

  /** Text with credentials in the shapes a provider error body echoes, `size` characters or a little more. */
  private def body(seed: Long, size: Int): String = {
    val rnd = new Random(seed)
    val sb  = new StringBuilder
    var i   = 0
    while (sb.length < size) {
      i += 1
      sb ++= (rnd.nextInt(6) match {
        case 0 => s"""{"error": {"message": "bad request $i", "api_key": "Kq${i}secret"}}, """
        case 1 => s"Authorization: Bearer Tk${i}bearer\n"
        case 2 => s"GET /v1?key=Qk${i}query&page=$i HTTP/1.1\n"
        case 3 => s"{'password': 'Pw${i}py', 'user': 'bob'} "
        case 4 => s"""{"password": "${"p" * rnd.nextInt(9000)}", "n": $i} """
        case _ => "lorem ipsum dolor sit amet " * (1 + rnd.nextInt(20))
      })
    }
    sb.toString
  }

  "Redaction.safeBody" should "redact a body up to the limit whole, exactly as before (#1680)" in {
    Seq(10, 1000, 60000, Limit).foreach { size =>
      val b = body(size.toLong, size).take(size)
      Seq(0, 100, 2048, 1000000).foreach { max =>
        Redaction.safeBodyWindow(b, max) shouldBe None
        Redaction.safeBody(b, max) shouldBe Redaction.truncateForLog(Redaction.redact(b), max)
      }
    }
  }

  it should "redact only a prefix of a multi-megabyte body, and keep what redacting it whole keeps (#1680)" in {
    val b      = body(1680, 3 * 1024 * 1024)
    val window = Redaction.safeBodyWindow(b, 2048)
    window should not be empty
    val (cut, _) = window.get
    cut should be < b.length / 4
    val out = Redaction.safeBody(b, 2048)
    out should endWith(s"... (truncated, original length: ${b.length})")
    val kept = out.stripSuffix(s"... (truncated, original length: ${b.length})")
    kept.length shouldBe 2048
    kept shouldBe Redaction.redact(b).take(2048)
    Seq("secret", "bearer", "query", "py").foreach(word => (kept should not).include(word))
  }

  it should "end the prefix before a separator, so no key is cut in two, the issue's counterexample (#1680)" in {
    // A long password value collapses to the placeholder, so text from far past the kept characters moves into
    // them; a key cut by a fixed-length prefix would be too short for the key pattern and be shown.
    val key    = "sk-proj-" + "A1b2C3d4E5f6G7h8I9j0" * 3
    val window = 2 * (2048 + 16 * 1024)
    Seq(-12, -1, 0, 5, 30).foreach { offset =>
      val head = s"""{"password": "${"p" * 9000}"} """
      val fill = "word " * ((window + offset - head.length) / 5)
      val b    = head + fill.take(window + offset - head.length) + key + " tail" + ("z " * 100000)
      Seq(100, 2048).foreach { max =>
        val out = Redaction.safeBody(b, max)
        (out should not).include("sk-proj-")
        (out should not).include("A1b2C3")
      }
    }
  }

  it should "read the prefix as the whole body is read, an escape or a quote past the cut included (#1680)" in {
    // A JSON escape of a separator, or a `'` that may close a value, past the cut changes how the text before it is
    // read; what is kept matches redacting the whole body.
    (1 to 12).foreach { i =>
      val b = body(i.toLong, 140 * 1024) + Seq("\\u0026x=1", " 'q", "\\u0027", " bearer z")(i % 4)
      Seq(1, 300, 2048).foreach(max => Redaction.safeBody(b, max).take(max) shouldBe Redaction.redact(b).take(max))
    }
  }

  it should "fall back to redacting the body whole where no prefix can show enough (#1680)" in {
    // One credential value runs over the whole body: every prefix collapses to the placeholder.
    val b = s"""{"password": "${"x" * (600 * 1024)}"}"""
    Redaction.safeBodyWindow(b, 2048) shouldBe None
    Redaction.safeBody(b, 2048) shouldBe Redaction.truncateForLog(Redaction.redact(b), 2048)
    // and a body with no separator at all
    val run = "a" * (300 * 1024)
    Redaction.safeBodyWindow(run, 2048) shouldBe None
    Redaction.safeBody(run, 2048) shouldBe Redaction.truncateForLog(run, 2048)
  }

  it should "keep a null body and a negative or oversized limit working (#1680)" in {
    Redaction.safeBody(null) shouldBe "null"
    val b = body(7, 200 * 1024)
    Redaction.safeBodyWindow(b, -1) shouldBe None
    Redaction.safeBodyWindow(b, Int.MaxValue) shouldBe None
    Redaction.safeBody(b, Int.MaxValue) shouldBe Redaction.redact(b)
  }
}
