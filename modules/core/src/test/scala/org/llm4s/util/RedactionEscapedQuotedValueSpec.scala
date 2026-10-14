package org.llm4s.util

import org.llm4s.testutil.LinearTime
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.{ Random, Try }

/**
 * A follow-up of #1694 (#1707): a credential in quotes written behind backslashes (`password=\\'p&ss=QZXJ\\'`,
 * which stands for `password=\'p&ss=QZXJ\'`) that holds `&word=` kept the part after the `&` readable. Since #1932 the
 * escapes in plain text are decoded a level at a time, so such a value is a quoted value at the level its quotes
 * stand for, replaced whole between them.
 */
class RedactionEscapedQuotedValueSpec extends AnyFlatSpec with Matchers {

  private val R  = Redaction.RedactionPlaceholder
  private val BS = "\\"

  private def parses(json: String): Boolean = Try(ujson.read(json)).isSuccess

  "Redaction.redact" should "redact a credential in escaped quotes that holds &word= whole (#1707)" in {
    // `\\'` is a quote behind an even run of backslashes: the passes stopped at the `&`, and `ss=QZXJ` stayed. #1932: the
    // value is replaced between its quotes, which are kept as written, where it was replaced in three pieces.
    Redaction.redact("""x=1&password=\\'p&ss=QZXJ\\' n=1""") shouldBe s"""x=1&password=\\\\'$R\\\\' n=1"""
    Redaction.redact("""{"q":"x=1&password=\\'p&ss=QZXJ\\' n=1"}""") shouldBe
      s"""{"q":"x=1&password=\\\\'$R\\\\' n=1"}"""
    Redaction.redact("""x=1&token=\\"p&ss=QZXJ\\" n=1""") shouldBe s"""x=1&token=\\\\"$R\\\\" n=1"""
    Redaction.redact("""https://x.test/cb?a=1&token=\\'p&q=QZXJ\\'&page=2""") shouldBe
      s"""https://x.test/cb?a=1&token=\\\\'$R\\\\'&page=2"""
    // after a space rather than in a query string, and one string deeper
    Redaction.redact("""login password=\\'p&user=QZXJ\\' n=1""") shouldBe
      s"""login password=\\\\'$R\\\\' n=1"""
    val depth2 = """{"p":"{\"q\":\"x=1&password=\\\\'p&ss=QZXJ\\\\' n=1\"}"}"""
    val out    = Redaction.redact(depth2)
    out shouldBe s"""{"p":"{\\"q\\":\\"x=1&password=\\\\\\\\'$R\\\\\\\\' n=1\\"}"}"""
    (ujson.read(ujson.read(out)("p").str)("q").str should not).include("QZXJ")
    // a key sensitive only as a query parameter, after `&`
    (Redaction.redact("""x=1&apikey_id=\\'p&ss=QZXJ\\' n=1""") should not).include("QZXJ")
  }

  it should "keep what it already redacted in the issue's other shapes (#1707)" in {
    Redaction.redact("x=1&password='p&ss=QZXJ' n=1") shouldBe s"x=1&password='$R' n=1"
    // #1932: the escaped quotes are kept around the placeholder, as bare ones are
    Redaction.redact(s"x=1${BS}u0026password=${BS}u0027p${BS}u0026ss=QZXJ${BS}u0027 n=1") shouldBe
      s"x=1${BS}u0026password=${BS}u0027$R${BS}u0027 n=1"
    Redaction.redact("login password='p&user=x' n=1") shouldBe s"login password='$R' n=1"
    (Redaction.redact("Server=db;Password='p&ss=X';Database=d") should not).include("ss=X")
  }

  it should "redact a value in escaped quotes that does not close, to where its line or string ends (#1707, #1932)" in {
    // a quote behind a different run of backslashes, a `"` that ends the string, the end of the input, a line break:
    // #1932 reads each as a value whose quote is open, replaced to the end of its line or of the string it sits in,
    // where they were left as the passes read them
    Redaction.redact("""x=1&password=\\'p&ss=QZXJ\\\' n=1""") shouldBe s"""x=1&password=\\\\'$R\\\\\\' n=1"""
    Redaction.redact("""{"q":"x=1&password=\\'p&ss=Q","n":"x"}""") shouldBe s"""{"q":"x=1&password=\\\\'$R","n":"x"}"""
    Redaction.redact("""x=1&password=\\'p&ss=QZXJ""") shouldBe s"""x=1&password=\\\\'$R"""
    Redaction.redact("x=1&password=\\\\'p&ss=QZXJ\n\\\\' n=1") shouldBe s"x=1&password=\\\\'$R\n\\\\' n=1"
    // a key that is not sensitive
    Redaction.redact("""x=1&user=\\'p&ss=QZXJ\\' n=1""") shouldBe """x=1&user=\\'p&ss=QZXJ\\' n=1"""
  }

  it should "leave an empty query value at the end of a JSON string as it is (#1707, #1800)" in {
    // #1800, closed by #1932: the string's content is a level of its own, so the empty value ends with it, and the `,`
    // after the string, which was replaced and broke nothing but the document's shape, is kept.
    val empty = """{"q":"a=1&token=","n":"x"}"""
    Redaction.redact(empty) shouldBe empty
    // a credential that is itself punctuation, or a JSON document of nothing but punctuation, is redacted
    Seq(
      """{"password":"[]"}"""               -> s"""{"password":"$R"}""",
      """{"password":"{}"}"""               -> s"""{"password":"$R"}""",
      """{"password":"[ ]"}"""              -> s"""{"password":"$R"}""",
      """{"password": "[[],{}]", "n": 1}""" -> s"""{"password": "$R", "n": 1}""",
      """{"api_key":"[ , ]"}"""             -> s"""{"api_key":"$R"}""",
      """{"token":"[[[]]]"}"""              -> s"""{"token":"$R"}"""
    ).foreach { case (input, expected) => Redaction.redact(input) shouldBe expected }
    Redaction.redact("""{"m":"{\"password\":\"[]\"}"}""") shouldBe s"""{"m":"{\\"password\\":\\"$R\\"}"}"""
  }

  it should "not throw on an invalid escape after an empty query value (#1707)" in {
    // A reading of these as JSON documents once decoded `\\uZZZZ` and `\\users1` as escapes and threw.
    Seq(
      s"""{"q":"a=1&token=","path":"C:${BS}users1"}""",
      s"""{"q":"https://x.test/cb?a=1&api_key=","note":"${BS}uZZZZ"}""",
      s"""{"messages":[{"content":"[\\"$BS${BS}uZZZZ\\"]"}],"q":"a=1&token=","n":1}"""
    ).foreach { input =>
      // #1932: the empty value after `token=` ends with its string; nothing is replaced
      Redaction.redact(input) shouldBe input
      Redaction.safeBody(input) shouldBe input
    }
  }

  it should "never throw, whatever the input (#1707)" in {
    // Pieces that make escapes, quotes, keys and JSON punctuation, valid or not, joined at random. Deterministic.
    val rnd = new Random(17071)
    val pieces = Seq(
      BS,
      BS * 2,
      BS * 3,
      s"${BS}u",
      s"${BS}uZZZZ",
      s"${BS}users1",
      s"${BS}u002",
      s"${BS}u0027",
      s"${BS}u0022",
      s"${BS}u0026",
      s"${BS}u003d",
      "\"",
      "'",
      "&",
      "=",
      "?",
      "token=",
      "password=",
      "api_key=",
      "a=1&",
      "{",
      "}",
      "[",
      "]",
      ",",
      ":",
      " ",
      "\n",
      "\r",
      "\t",
      "{\"q\":\"",
      "\",\"n\":\"",
      "\"}",
      "Bearer ",
      "Cookie: ",
      "https://x.test/cb?",
      "x",
      "QZ",
      "\u0000",
      "\uFFFF"
    )
    (1 to 2000).foreach { _ =>
      val noise = Seq.fill(1 + rnd.nextInt(30))(pieces(rnd.nextInt(pieces.length))).mkString
      // half of them inside a JSON document that holds an empty query value
      val input = if (rnd.nextBoolean()) noise else s"""{"q":"a=1&token=","n":"$noise"}"""
      withClue(s"input $input: ") {
        Try(Redaction.redact(input)).isSuccess shouldBe true
        Try(Redaction.safeBody(input)).isSuccess shouldBe true
      }
    }
  }

  /** Documents of #1707's shapes, each with its credentials, unique words that appear nowhere else. */
  final private class Docs(seed: Long) {
    private val rnd                    = new Random(seed)
    private var n                      = 0
    private def pick[A](xs: Seq[A]): A = xs(rnd.nextInt(xs.length))
    private def word(): String         = { n += 1; s"Qz${n}w" }
    private val keys                   = Seq("password", "token", "api_key", "client_secret", "access_token", "passwd")

    private def jsonString(s: String, gson: Boolean): String =
      ujson.Str(s).render().flatMap {
        case '=' if gson  => s"${BS}u003d"
        case '&' if gson  => s"${BS}u0026"
        case '\'' if gson => s"${BS}u0027"
        case c            => c.toString
      }

    private def carried(raw: String): String =
      rnd.nextInt(3) match {
        case 0 => raw
        case 1 => s"""{"q":${jsonString(raw, gson = rnd.nextBoolean())},"n":1}"""
        case _ =>
          val inner = s"""{"q":${jsonString(raw, gson = rnd.nextBoolean())}}"""
          s"""{"payload":${jsonString(inner, gson = rnd.nextBoolean())},"ok":true}"""
      }

    private def quote(): String =
      pick(Seq("'", "\"")) match {
        case q => pick(Seq(q, BS + q, BS * 2 + q, BS * 3 + q, s"${BS}u002${if (q == "'") "7" else "2"}"))
      }

    def next(): (String, Seq[String]) =
      if (rnd.nextInt(4) == 0) {
        val raw = s"a=1${pick(Seq("&", s"${BS}u0026"))}${pick(keys)}=${pick(Seq("", "'", "x"))}"
        (s"""{"q":${jsonString(raw, gson = false)},"n":"x"}""", Nil)
      } else {
        val (a, b, c) = (word(), word(), word())
        val q         = quote()
        val raw =
          s"${pick(Seq("x=1&", "GET /p?a=1&", "login ", ""))}${pick(keys)}=$q$a&${pick(Seq("ss", "user", "q"))}=$b" +
            s"${pick(Seq("", s"&n=$c"))}$q${pick(Seq("", " n=1", "&page=2"))}"
        (carried(raw), Seq(a, b) ++ (if (raw.contains(s"n=$c")) Seq(c) else Nil))
      }
  }

  it should "redact every credential of #1707's shapes, keep JSON valid and redact its own output to itself" in {
    // #1932: the reading of #1707 is part of the one pass, so the check is no longer that it only adds to the passes
    // but that no credential is left. Deterministic.
    val docs = new Docs(1707)
    (1 to 2000).foreach { _ =>
      val (input, secrets) = docs.next()
      val out              = Redaction.redact(input)
      withClue(s"input $input, output $out: ") {
        secrets.filter(out.contains) shouldBe empty
        if (parses(input)) parses(out) shouldBe true
        Redaction.redact(out) shouldBe out
      }
    }
  }

  // The timing backstop is RedactionScalingSpec (`sbt testPerformance`); this one stays in `sbt test` and fails on
  // a catastrophic regression in one run of a megabyte, under a generous hang guard.
  it should "read a megabyte of values in escaped quotes in a JSON string well inside the hang guard (#1707)" in {
    val input = "{\"q\":\"" + ("x=1&password=\\\\'p&ss=Q\\\\' " * 40000) + "\"}"
    input.length should be > 1000000
    LinearTime.timed("a megabyte of values in escaped quotes", LinearTime.MegabyteHangGuard)(Redaction.redact(input))
  }
}
