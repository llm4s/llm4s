package org.llm4s.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Random

/**
 * `safeBody` on a body longer than its whole-redaction limit redacts a prefix instead of the whole body, where the
 * prefix shows exactly what redacting the whole body shows (#1680); a body up to the limit is redacted whole, as
 * before.
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

  /** What `safeBody` keeps of `body`: its output without the truncation suffix. */
  private def shown(out: String): String = {
    val at = out.lastIndexOf("... (truncated, original length: ")
    if (at >= 0) out.substring(0, at) else out
  }

  /** `safeBody` keeps what redacting the whole body and truncating it keeps. */
  private def keepsWhatWholeKeeps(b: String, max: Int): Unit =
    shown(Redaction.safeBody(b, max)) shouldBe shown(Redaction.truncateForLog(Redaction.redact(b), max))

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
      val b = body(i.toLong, 300 * 1024) + Seq("\\u0026x=1", " 'q", "\\u0027", " bearer z")(i % 4)
      val whole = Redaction.redact(b)
      Seq(1, 300, 2048).foreach(max => Redaction.safeBody(b, max).take(max) shouldBe whole.take(max))
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

  it should "end the prefix after a letter or a digit and before a separator (#1680)" in {
    (1 to 6).foreach { i =>
      val b = body(i.toLong + 100, 600 * 1024)
      Redaction.safeBodyWindow(b, 2048).foreach { case (cut, _) =>
        b.charAt(cut - 1).isLetterOrDigit shouldBe true
        (Character.isWhitespace(b.charAt(cut)) || "\"',;<>{}[]()".indexOf(b.charAt(cut).toInt) >= 0) shouldBe true
      }
    }
  }

  it should "not show a value in escaped quotes that the cut leaves open and the body closes (#1680)" in {
    // `api_key=\\'...\\'` (#1707) is taken only where it closes: here past the first prefix, so that prefix reads
    // the value as one that does not close, and showed the credential.
    val words = (0 until 12000).map(i => s"w$i").mkString(" ")
    val b     = "log api_key=\\\\'hunter2-REAL-SECRET " + words + " \\\\' end\n" + logLines(150000)
    keepsWhatWholeKeeps(b, 2048)
    (Redaction.safeBody(b, 2048) should not).include("hunter2")
    val close =
      s"""{"m": "cmd: login password=\\\\'hunter2-REAL-SECRET """ + "w " * 30000 + "\\\\' done\"}\n" + logLines(200000)
    keepsWhatWholeKeeps(close, 2048)
  }

  it should "read a single-quoted value in a JSON string as the whole body does, past the cut (#1680)" in {
    // Where such a value ends depends on whether the passes before kept every `'` of the body; the `'` of `don't`,
    // past the cut, is replaced, which a prefix cannot see.
    val lines = logLines(150000)
    val b = s"""{"error": "config {'api_key': 'sk-live-part1", "detail": "SECOND-PART-VISIBLE"}\n""" + lines + "\n" +
      """{"password": "don't"}""" + "\n" + lines
    (Redaction.safeBody(b, 2048) should not).include("SECOND-PART")
    keepsWhatWholeKeeps(b, 2048)
    Redaction.safeBodyWindow(b, 2048) shouldBe None
  }

  it should "keep what redacting the whole body keeps, for any value opened in the kept characters (#1680)" in {
    // A property test: each body opens a value of a shape some reading takes only once it closes, or replaces up to
    // where it closes, in the characters kept; the value closes before the first cut, between the two prefixes, past
    // both or never; and a character that changes how the body is read as a whole follows, past every cut.
    val secret = "QQsecretVALUEqq"
    def text(n: Int, rnd: Random): String = {
      val sb = new StringBuilder
      while (sb.length < n) sb ++= s"${secret.take(4)}${rnd.nextInt(1000)}" += (if (rnd.nextBoolean()) ' ' else ',')
      sb.toString
    }
    def head(kind: Int, v: String): String = kind match {
      case 0  => s"""{"password": "$secret $v", "x": "y"}"""
      case 1  => s"""{"msg": "user said 'password': '$secret $v", "o": "v"}"""
      case 2  => s"""{'password': '$secret $v', 'a': 'b'}"""
      case 3  => s"""Settings(password='$secret $v', a=1)"""
      case 4  => s"""Settings(token=SecretStr('$secret $v'), a=1)"""
      case 5  => s"""{"body": "{\\"api_key\\": \\"$secret $v\\", \\"n\\": 1}"}"""
      case 6  => s"""log password=\\\\'$secret $v\\\\' end"""
      case 7  => s"""{"m": "password=\\'$secret $v\\' end"}"""
      case 8  => s"""GET /x?token=$secret${v.filter(_.isLetterOrDigit)}&a=b HTTP"""
      case 9  => s"""\nCookie: sid=$secret $v; theme=dark\n"""
      case 10 => s"""{"text/plain": ["{'password': '$secret $v'}"]}"""
      case 11 => s"""{"secret": ["$secret", "$v"], "a": 1}"""
      case 12 => s"""{'password': "$secret $v", 'a': 'b'}"""
      case 13 => s"""{"token": [$secret ${v.filter(_.isLetter)}], "a": 1}"""
      case _  => s"""{"msg": "{'token': [\\"$secret $v\\"]}"}"""
    }
    val triggers = Seq(
      "",
      """{"password": "it's"}""",
      " it's ",
      " ' ",
      " \\u0026 ",
      " \\\" ",
      " a,{\"b\": 1} ",
      " Bearer x ",
      " \\n ",
      " & ",
      " a=b ",
      "\" ",
      "'} ",
      "', a=1) ",
      " \\u0027 "
    )
    val rnd      = new Random(1680)
    var windowed = 0
    (0 until 40).foreach { _ =>
      val kind = rnd.nextInt(15)
      val len  = Seq(20, 3000, 30000, 60000, 90000, 200000)(rnd.nextInt(6))
      val pre  = if (rnd.nextBoolean()) "" else logLines(rnd.nextInt(1500))
      val h    = pre + head(kind, text(len, rnd))
      val size = 160000 + rnd.nextInt(240000)
      val b    = h + logLines(math.max(0, size - h.length)) + triggers(rnd.nextInt(triggers.length)) + logLines(500)
      val max  = Seq(1, 300, 2048)(rnd.nextInt(3))
      // What `safeBody` keeps of a prefix is its first `max` characters (the case above checks `safeBody` itself).
      Redaction.safeBodyWindow(b, max).foreach { case (_, redacted) =>
        windowed += 1
        redacted.take(max) shouldBe Redaction.redact(b).take(max)
      }
    }
    windowed should be > 5
  }
}
