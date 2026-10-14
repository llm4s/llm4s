package org.llm4s.util

import org.llm4s.testutil.LinearTime
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Random

/**
 * `safeBody` redacts a body up to 128 KiB whole, as it always did, and a longer one only in a prefix of at most 64 KiB
 * (#1680). Since #1932 the prefix's redaction is told that the text was cut, and decides nothing still open at the
 * cut in a value's favour, so it shows nothing the whole body's redaction would hide: the probe tails, the 16 KiB
 * margin and the cap on the quotes a prefix may hold are gone.
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

  /** A quote written behind `run` backslashes: `'`, `"`, or the JSON escape of either (`u0027`, `u0022`). */
  private def quoted(run: Int, quote: String): String = "\\" * run + quote

  /**
   * A value of a sensitive key opened by a quote escaped `run` backslashes deep, in a few shapes, closed by the same
   * quote some 80 KiB on, past the cut.
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

  /** The secret markers, `Q<n>q`, that `out` shows. */
  private def shown(out: String): Set[String] = "Q[0-9]+q".r.findAllIn(out).toSet

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
        val cut = Redaction.safeBodyCut(b, max).getOrElse(fail("no cut"))
        cut should be <= PrefixLimit
        cut should be > PrefixLimit / 2
        b.charAt(cut - 1).isLetterOrDigit shouldBe true
        isSeparator(b.charAt(cut)) shouldBe true
        Redaction.safeBody(b, max) shouldBe Redaction.redactCut(b.substring(0, cut)).take(max) + suffix(b, cut)
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
    // #1932: a value open where its string closes, on a short line, ends there whatever lies past the cut, so what is
    // shown is what the whole body's redaction shows (`detail` names no credential, and both keep it), where nothing
    // was shown.
    val lines = logLines(150000)
    val b3 = s"""{"error": "config {'api_key': 'sk-live-part1", "detail": "SECOND-PART-VISIBLE"}\n""" + lines + "\n" +
      """{"password": "don't"}""" + "\n" + lines
    val out3 = Redaction.safeBody(b3, 2048)
    (out3 should not).include("sk-live")
    out3 should startWith(Redaction.redact(b3).take(2048))
  }

  it should "not show a value opened by a quote escaped to any depth, in any shape (#1680)" in {
    // Four literal backslashes before the quotes, two before a `"`, JSON nested three and four deep (`2^k - 1`
    // backslashes), a deep `'` and a deep escape of one: wherever redacting the whole body hides the value, the prefix
    // shows none of it.
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

  it should "show the redaction of a prefix however many quotes, at distinct depths, it holds (#1680, #1932)" in {
    // A prefix showed nothing where it held more than eight distinct quotes, too many for its probes to close each.
    // With no probes, a prefix of any quotes is shown, redacted.
    val head = (0 to 9).map(run => s"a=${quoted(run, "'")}x${quoted(run, "'")} ").mkString
    val b    = head + logLines(300000)
    val cut  = Redaction.safeBodyCut(b, 2048).getOrElse(fail("no cut"))
    Redaction.safeBody(b, 2048) shouldBe Redaction.redactCut(b.substring(0, cut)).take(2048) + suffix(b, cut)
  }

  it should "show what a prefix that one credential runs into leaves, and nothing of a body with no place to cut (#1680)" in {
    // One credential value runs from early in the prefix past the cut: it collapses to the placeholder, which is what
    // is shown. #1932: nothing was shown, as a prefix too short to show `maxLength` past a 16 KiB margin.
    val words = (0 until 60000).map(i => s"w$i").mkString(" ")
    val b     = s"""{"password": "$words"}"""
    val cut   = Redaction.safeBodyCut(b, 2048).getOrElse(fail("no cut"))
    Redaction.safeBody(b, 2048) shouldBe s"""{"password": "[REDACTED]""" + suffix(b, cut)
    // A value with no separator in it at all: no place to cut, so nothing is shown.
    val x = s"""{"password": "${"x" * (600 * 1024)}"}"""
    Redaction.safeBodyCut(x, 2048) shouldBe None
    Redaction.safeBody(x, 2048) shouldBe Redaction.safeBodyOmitted(x)
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

  it should "show of a prefix, cut anywhere, no secret the whole body's redaction hides (#1680, #1932)" in {
    // Bodies with a secret in every shape, each a unique marker, cut at random places a prefix can be cut at - after a
    // letter or a digit, before a separator - which may fall inside a value, a string, a container, a URL or a
    // header. What the cut prefix's redaction shows of the markers, the whole body's redaction shows too.
    val rnd = new Random(19320)
    (0 until 300).foreach { n =>
      val sb = new StringBuilder
      while (sb.length < 4000) {
        val i = rnd.nextInt(100000)
        sb ++= (rnd.nextInt(10) match {
          case 0 => s"""{"api_key": "Q${i}q value", "n": 1} """
          case 1 => s"Authorization: Bearer Q${i}q\n"
          case 2 => s"GET /v1?key=Q${i}q&page=1 "
          case 3 => s"{'password': 'Q${i}q', 'user': 'bob'} "
          case 4 => s"""{"m": "x password=\\"Q${i}q tail\\" y"} """
          case 5 => s"https://u:Q${i}q@db.example.com/x "
          case 6 => s"""{"assertion": "a b Q${i}q"} """
          case 7 => s"""{"secret": ["Q${i}q", "v"], "a": 1} """
          case 8 => s"Settings(token=SecretStr('Q${i}q x'), a=1) "
          case _ => "lorem ipsum, dolor; sit (amet) "
        })
      }
      val b       = sb.toString
      val visible = shown(Redaction.redact(b))
      val cuts    = (1 until b.length).filter(c => b.charAt(c - 1).isLetterOrDigit && isSeparator(b.charAt(c)))
      cuts.filter(_ => rnd.nextInt(8) == 0).foreach { cut =>
        val prefix = Redaction.redactCut(b.substring(0, cut))
        withClue(s"body $n, cut $cut: ${b.substring(0, cut).takeRight(80)}\n") {
          (shown(prefix) -- visible) shouldBe empty
        }
      }
    }
  }

  it should "redact a body of a megabyte without hanging (#1680)" in {
    // An ordinary hang guard on a body well over the 128 KiB a body is redacted whole up to; how its cost compares with
    // a 128 KiB body's is RedactionScalingSpec's.
    val b   = body(16, 1024 * 1024)
    var out = ""
    LinearTime.timed("safeBody of a 1 MiB body", LinearTime.MegabyteHangGuard) {
      out = Redaction.safeBody(b, 2048)
    }
    out should endWith(s"redacted from its first ${Redaction.safeBodyCut(b, 2048).get} chars)")
  }
}
