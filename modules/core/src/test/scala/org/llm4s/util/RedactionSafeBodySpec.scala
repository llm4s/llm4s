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

  /** A quote written behind `run` backslashes: `'`, `"`, or the JSON escape of either (`u0027`, `u0022`). */
  private def quoted(run: Int, quote: String): String = "\\" * run + quote

  /**
   * A value of a sensitive key opened by a quote escaped `run` backslashes deep, in a few shapes, closed by the same
   * quote some 80 KiB on, past the cut. The first two, `key=value` pairs, are hidden by redacting the whole body at
   * every depth.
   */
  private def deepQuotedHeads(secret: String, run: Int, quote: String): Seq[String] = {
    val q     = quoted(run, quote)
    val words = (0 until 12000).map(i => s"w$i").mkString(" ")
    Seq(
      s"log password=$q$secret $words $q end\n",
      s"""{"m": "cmd password=$q$secret $words $q done"}\n""",
      s"Settings(token=SecretStr($q$secret $words$q), a=1)\n",
      s"${q}password$q: $q$secret $words$q, ${q}a$q: 1\n"
    )
  }

  it should "not show a value opened by a quote escaped deeper than the fixed probes close (#1680)" in {
    // Codex's repro: four literal backslashes before the quotes, which no fixed probe spells out; two before a `"`,
    // which the fixed probe never closed either; JSON nested three and four deep (`2^k - 1` backslashes); a deep `'`
    // and a deep escape of one. Redacting the whole body hides each of these key=value values, so nothing may show it.
    val secret = "hunter2SECRETzz"
    Seq(4 -> "'", 2 -> "\"", 3 -> "\"", 7 -> "\"", 6 -> "'", 4 -> "u0027").foreach { case (run, quote) =>
      deepQuotedHeads(secret, run, quote).take(2).foreach { head =>
        withClue(s"run $run, quote $quote, head ${head.take(40)}: ") {
          (Redaction.safeBody(head + logLines(100000), 2048) should not).include(secret)
        }
      }
    }
  }

  it should "not show a value opened by a quote escaped to any depth, in any shape (#1680)" taggedAs PerformanceTest in {
    val secret = "hunter2SECRETzz"
    for {
      run   <- 0 to 9
      quote <- Seq("'", "\"", "u0027", "u0022") if run > 0 || !quote.startsWith("u")
      head  <- deepQuotedHeads(secret, run, quote)
    } {
      val b = head + logLines(150000)
      withClue(s"run $run, quote $quote, head ${head.take(40)}: ") {
        if (!Redaction.redact(b).contains(secret)) (Redaction.safeBody(b, 2048) should not).include(secret)
      }
    }
  }

  it should "show nothing of a prefix that holds more quotes, at distinct depths, than it probes (#1680)" in {
    val head = (0 to 9).map(run => s"a=${quoted(run, "'")}x${quoted(run, "'")} ").mkString
    val b    = head + logLines(300000)
    Redaction.safeBodyCut(b, 2048) shouldBe None
    Redaction.safeBody(b, 2048) shouldBe Redaction.safeBodyOmitted(b)
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
    // A quote escaped to any depth up to nine backslashes, as JSON nested in JSON, a shell line or a dict writes it.
    def deep(rnd: Random): String = {
      val run = rnd.nextInt(10)
      quoted(run, Seq("'", "\"", "u0027", "u0022")(if (run == 0) rnd.nextInt(2) else rnd.nextInt(4)))
    }
    def head(kind: Int, v: String, q: String): String = kind match {
      case 0 => s"""{"password": "$secret $v", "x": "y"}"""
      case 1 => s"""{'password': '$secret $v', 'a': 'b'}"""
      case 2 => s"""Settings(token=SecretStr('$secret $v'), a=1)"""
      case 3 => s"""log password=\\\\'$secret $v\\\\' end"""
      case 4 => s"""{"m": "password=\\'$secret $v\\' end"}"""
      case 5 => s"""https://alice:$secret${v.filterNot(_ == ' ')}@db.example.com/x"""
      case 6 => s"""{"assertion": "$secret $v ${"Z" * 30}", "n": 1}"""
      case 7 => s"""{"secret": ["$secret", "$v"], "a": 1}"""
      case 8 => s"""log password=$q$secret $v$q end"""
      case _ => s"""{"m": "cmd token=$q$secret $v $q done"}"""
    }
    val rnd = new Random(seed)
    (0 until bodies).foreach { _ =>
      val kind = rnd.nextInt(10)
      val len  = Seq(20, 3000, 30000, 60000, 200000)(rnd.nextInt(5))
      val h    = head(kind, text(len, rnd), deep(rnd))
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
    showsPrefixOrNothing(bodies = 6, seed = 1680)
  }

  it should "show what redacting the prefix shows, or nothing, over many bodies (#1680)" taggedAs PerformanceTest in {
    showsPrefixOrNothing(bodies = 40, seed = 16801)
  }

  it should "redact a body of a megabyte without hanging (#1680)" in {
    // An ordinary hang guard on a body well over the 128 KiB a body is redacted whole up to; how the cost compares
    // with redacting the body whole is the PerformanceTest below.
    val b   = body(16, 1024 * 1024)
    var out = ""
    LinearTime.timed("safeBody of a 1 MiB body", LinearTime.MegabyteHangGuard) {
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
