package org.llm4s.util

import org.llm4s.testutil.{ LinearTime, PerformanceTest }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Random

/**
 * `safeBody` redacts a body up to 128 KiB whole, as it always did, and a longer one only in a prefix of at most 64 KiB,
 * shown only where nothing past the cut could change what is shown, and otherwise not at all (#1680).
 */
class RedactionSafeBodySpec extends AnyFlatSpec with Matchers {

  private val WholeLimit  = 128 * 1024
  private val PrefixLimit = 64 * 1024

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
        case 4 => s"""{"password": "${"p" * rnd.nextInt(900)}", "n": $i} """
        case _ => "lorem ipsum dolor sit amet " * (1 + rnd.nextInt(20))
      })
    }
    sb.toString
  }

  /** JSON log lines, `size` characters or a little more. */
  private def logLines(size: Int): String = {
    val sb = new StringBuilder
    var i  = 0
    while (sb.length < size) {
      sb ++= s"""{"log": "request $i served in 12ms"}\n"""
      i += 1
    }
    sb.toString
  }

  private def isSeparator(c: Char): Boolean = Character.isWhitespace(c) || "\"',;<>{}[]()".indexOf(c.toInt) >= 0

  private def suffix(b: String, cut: Int): String =
    s"... (truncated, original length: ${b.length}, redacted from its first $cut chars)"

  "Redaction.safeBody" should "redact a body up to 128 KiB whole, exactly as before (#1680)" in {
    Seq(1000, WholeLimit).foreach { size =>
      val b     = body(size.toLong, size).take(size)
      val whole = Redaction.redact(b)
      Seq(-1, 0, 2048, 1000000).foreach { max =>
        Redaction.safeBodyCut(b, max) shouldBe None
        Redaction.safeBody(b, max) shouldBe Redaction.truncateForLog(whole, max)
      }
    }
  }

  it should "redact a longer body only in a prefix, cut after a letter or digit and before a separator (#1680)" in {
    (1 to 2).foreach { i =>
      val b = body(i.toLong, 140 * 1024 + i * 200 * 1024)
      Seq(1, 2048).foreach { max =>
        Redaction.safeBodyCut(b, max) match {
          case Some(cut) =>
            cut should be <= PrefixLimit
            cut should be > PrefixLimit / 2
            b.charAt(cut - 1).isLetterOrDigit shouldBe true
            isSeparator(b.charAt(cut)) shouldBe true
            Redaction.safeBody(b, max) shouldBe Redaction.redact(b.substring(0, cut)).take(max) + suffix(b, cut)
          case None =>
            Redaction.safeBody(b, max) shouldBe Redaction.safeBodyOmitted(b)
        }
      }
    }
    val big = body(1680, 1024 * 1024)
    val cut = Redaction.safeBodyCut(big, 2048)
    cut should not be empty
    val out = Redaction.safeBody(big, 2048)
    out should endWith(suffix(big, cut.get))
    Seq("secret", "bearer", "query", "py").foreach(word => (out should not).include(word))
  }

  it should "never cut a key in two, the issue's counterexample (#1680)" in {
    // A long password value collapses to the placeholder, so text from far past the kept characters would move into
    // them; a key cut by a fixed-length prefix would be too short for the key pattern and be shown.
    val key = "sk-proj-" + "A1b2C3d4E5f6G7h8I9j0" * 3
    Seq(-1, 0, 5).foreach { offset =>
      Seq(PrefixLimit, PrefixLimit / 2).foreach { at =>
        val head = s"""{"password": "${"p" * 9000}"} """
        val fill = "word " * ((at + offset - head.length) / 5)
        val b    = head + fill.take(at + offset - head.length) + key + " tail" + ("z " * 70000)
        val out  = Redaction.safeBody(b, 2048)
        (out should not).include("sk-proj-")
        (out should not).include("A1b2C3")
      }
    }
  }

  it should "not show a URL's password whose @ lies past the cut (#1680, #1816)" in {
    Seq(";k", ",k").foreach { run =>
      val b =
        "connect failed: https://alice:hunter2REALSECRET" + run * 150000 + "@db.example.com/x\n" + logLines(400000)
      val out = Redaction.safeBody(b, 2048)
      (out should not).include("hunter2")
      (out should not).include("REALSECRET")
    }
  }

  it should "not show the head of an assertion that a token past the cut makes a credential (#1680, #1838)" in {
    val prose = (0 until 40000).map(i => s"w$i").mkString(" ")
    val token = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9xyz"
    Seq(
      s"""{"assertion": "secret part one $prose $token", "n": 1}\n""",
      s"""{'assertion': 'secret part one $prose $token', 'n': 1}\n"""
    ).foreach { head =>
      val out = Redaction.safeBody(head + logLines(500000), 2048)
      (out should not).include("secret part")
      (out should not).include("w1 w2")
    }
  }

  it should "not show a value whose closing quote, or what decides how it ends, lies past the cut (#1680)" in {
    // The two counterexamples of the review of #1841: a value in escaped quotes (#1707), and a single-quoted value
    // inside a JSON string.
    val words = (0 until 12000).map(i => s"w$i").mkString(" ")
    val b1    = "log api_key=\\\\'hunter2-REAL-SECRET " + words + " \\\\' end\n" + logLines(150000)
    (Redaction.safeBody(b1, 2048) should not).include("hunter2")
    val b2 = s"""{"m": "cmd: login password=\\\\'hunter2-REAL-SECRET """ + "w " * 30000 + "\\\\' done\"}\n" +
      logLines(200000)
    (Redaction.safeBody(b2, 2048) should not).include("hunter2")
    val lines = logLines(150000)
    val b3 = s"""{"error": "config {'api_key': 'sk-live-part1", "detail": "SECOND-PART-VISIBLE"}\n""" + lines + "\n" +
      """{"password": "don't"}""" + "\n" + lines
    val out3 = Redaction.safeBody(b3, 2048)
    (out3 should not).include("sk-live")
    (out3 should not).include("SECOND-PART")
  }

  it should "show nothing of a body whose prefix cannot show enough, or that has no place to cut (#1680)" in {
    // One credential value runs over the whole prefix: it collapses to the placeholder.
    val b = s"""{"password": "${"x" * (600 * 1024)}"}"""
    Redaction.safeBodyCut(b, 2048) shouldBe None
    Redaction.safeBody(b, 2048) shouldBe Redaction.safeBodyOmitted(b)
    (Redaction.safeBody(b, 2048) should not).include("xxxx")
    // No separator at all.
    val run = "a" * (300 * 1024)
    Redaction.safeBodyCut(run, 2048) shouldBe None
    Redaction.safeBody(run, 2048) shouldBe Redaction.safeBodyOmitted(run)
    Redaction.safeBodyOmitted(run) should include((300 * 1024).toString)
  }

  it should "never show a credential that lies wholly past the cut (#1680)" in {
    val b   = logLines(PrefixLimit + 1000) + """{"api_key": "sk-proj-PASTTHECUT0123456789abcdef"} """ + logLines(400000)
    val out = Redaction.safeBody(b, Int.MaxValue / 2)
    (out should not).include("PASTTHECUT")
  }

  it should "keep a null body and a negative or oversized limit working (#1680)" in {
    Redaction.safeBody(null) shouldBe "null"
    val b = body(7, 200 * 1024)
    Redaction.safeBodyCut(b, -1) shouldBe None
    Redaction.safeBody(b, -1) shouldBe Redaction.truncateForLog(Redaction.redact(b), -1)
    // A limit no prefix can serve: the caller asked for the body redacted whole.
    Redaction.safeBodyCut(b, Int.MaxValue) shouldBe None
    Redaction.safeBody(b, Int.MaxValue) shouldBe Redaction.redact(b)
  }

  /**
   * Each body opens a value in the kept characters, which closes before the cut, past it, or never; what is shown is
   * the redaction of the prefix, or nothing, and no secret marker inside the value is shown.
   */
  private def showsPrefixOrNothing(bodies: Int, seed: Long): Unit = {
    val secret = "QQsecretVALUEqq"
    def text(n: Int, rnd: Random): String = {
      val sb = new StringBuilder
      while (sb.length < n) sb ++= s"${secret.take(4)}${rnd.nextInt(1000)}" += (if (rnd.nextBoolean()) ' ' else ',')
      sb.toString
    }
    def head(kind: Int, v: String): String = kind match {
      case 0 => s"""{"password": "$secret $v", "x": "y"}"""
      case 1 => s"""{'password': '$secret $v', 'a': 'b'}"""
      case 2 => s"""Settings(token=SecretStr('$secret $v'), a=1)"""
      case 3 => s"""log password=\\\\'$secret $v\\\\' end"""
      case 4 => s"""{"m": "password=\\'$secret $v\\' end"}"""
      case 5 => s"""https://alice:$secret${v.filterNot(_ == ' ')}@db.example.com/x"""
      case 6 => s"""{"assertion": "$secret $v ${"Z" * 30}", "n": 1}"""
      case _ => s"""{"secret": ["$secret", "$v"], "a": 1}"""
    }
    val rnd = new Random(seed)
    (0 until bodies).foreach { _ =>
      val kind = rnd.nextInt(8)
      val len  = Seq(20, 3000, 30000, 60000, 200000)(rnd.nextInt(5))
      val h    = head(kind, text(len, rnd))
      val b    = h + logLines(math.max(0, 200000 + rnd.nextInt(200000) - h.length))
      val max  = Seq(1, 300, 2048)(rnd.nextInt(3))
      val out  = Redaction.safeBody(b, max)
      Redaction.safeBodyCut(b, max) match {
        case Some(cut) => out shouldBe Redaction.redact(b.substring(0, cut)).take(max) + suffix(b, cut)
        case None      => out shouldBe Redaction.safeBodyOmitted(b)
      }
      (out should not).include(secret)
      (out should not).include("QQse")
    }
  }

  it should "show what redacting the prefix shows, or nothing, for any value opened in the kept characters (#1680)" in {
    showsPrefixOrNothing(bodies = 4, seed = 1680)
  }

  it should "show what redacting the prefix shows, or nothing, over many bodies (#1680)" taggedAs PerformanceTest in {
    showsPrefixOrNothing(bodies = 40, seed = 16801)
  }

  it should "redact a body of many megabytes without hanging (#1680)" in {
    // An ordinary hang guard; how the cost compares with redacting the body whole is the PerformanceTest below.
    val b   = body(16, 4 * 1024 * 1024)
    var out = ""
    LinearTime.timed("safeBody of a 4 MiB body", LinearTime.MegabyteHangGuard) {
      out = Redaction.safeBody(b, 2048)
    }
    out should endWith(s"redacted from its first ${Redaction.safeBodyCut(b, 2048).get} chars)")
  }

  it should "cost about what a 128 KiB body costs, whatever the body's size (#1680)" taggedAs PerformanceTest in {
    // Redacting 16 MiB whole costs about 128 times redacting 128 KiB; the prefix and its probes, a small multiple.
    val big   = body(16, 16 * 1024 * 1024)
    val small = body(16, WholeLimit).take(WholeLimit)
    def best(f: => Any): Long = (0 until 3).map { _ =>
      val start = System.nanoTime(); f; System.nanoTime() - start
    }.min
    (0 until 2).foreach { _ =>
      Redaction.safeBody(big, 2048); Redaction.safeBody(small, 2048)
    }
    val prefix = best(Redaction.safeBody(big, 2048))
    val whole  = best(Redaction.safeBody(small, 2048))
    withClue(s"safeBody of 16 MiB took ${prefix / 1000000} ms, of 128 KiB ${whole / 1000000} ms: ") {
      prefix should be < 8 * whole
    }
  }
}
