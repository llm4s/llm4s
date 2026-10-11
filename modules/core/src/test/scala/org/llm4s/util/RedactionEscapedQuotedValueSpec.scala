package org.llm4s.util

import org.llm4s.testutil.LinearTime
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.{ Random, Try }

/**
 * A follow-up of #1694 (#1707): a credential in quotes written behind backslashes (`password=\\'p&ss=QZXJ\\'`,
 * which stands for `password=\'p&ss=QZXJ\'`) that holds `&word=` kept the part after the `&` readable. It is closed by
 * a reading that only adds to what the passes replace, so every character redacted before is redacted still.
 */
class RedactionEscapedQuotedValueSpec extends AnyFlatSpec with Matchers {

  private val R  = Redaction.RedactionPlaceholder
  private val BS = "\\"

  private def parses(json: String): Boolean = Try(ujson.read(json)).isSuccess

  "Redaction.redact" should "redact a credential in escaped quotes that holds &word= whole (#1707)" in {
    // `\\'` is a quote behind an even run of backslashes: the passes stopped at the `&`, and `ss=QZXJ` stayed.
    Redaction.redact("""x=1&password=\\'p&ss=QZXJ\\' n=1""") shouldBe s"""x=1&password=$R'$R&$R\\\\' n=1"""
    Redaction.redact("""{"q":"x=1&password=\\'p&ss=QZXJ\\' n=1"}""") shouldBe
      s"""{"q":"x=1&password=$R'$R&$R\\\\' n=1"}"""
    Redaction.redact("""x=1&token=\\"p&ss=QZXJ\\" n=1""") shouldBe s"""x=1&token=$R"$R&$R\\\\" n=1"""
    Redaction.redact("""https://x.test/cb?a=1&token=\\'p&q=QZXJ\\'&page=2""") shouldBe
      s"""https://x.test/cb?a=1&token=$R'$R&$R\\\\'&page=2"""
    // after a space rather than in a query string, and one string deeper
    Redaction.redact("""login password=\\'p&user=QZXJ\\' n=1""") shouldBe s"""login password=$R'$R\\\\' n=1"""
    val depth2 = """{"p":"{\"q\":\"x=1&password=\\\\'p&ss=QZXJ\\\\' n=1\"}"}"""
    val out    = Redaction.redact(depth2)
    (out should not).include("QZXJ")
    (ujson.read(ujson.read(out)("p").str)("q").str should not).include("QZXJ")
    // a key sensitive only as a query parameter, after `&`
    (Redaction.redact("""x=1&apikey_id=\\'p&ss=QZXJ\\' n=1""") should not).include("QZXJ")
  }

  it should "keep what it already redacted in the issue's other shapes (#1707)" in {
    Redaction.redact("x=1&password='p&ss=QZXJ' n=1") shouldBe s"x=1&password='$R' n=1"
    Redaction.redact(s"x=1${BS}u0026password=${BS}u0027p${BS}u0026ss=QZXJ${BS}u0027 n=1") shouldBe
      s"x=1${BS}u0026password=$R n=1"
    Redaction.redact("login password='p&user=x' n=1") shouldBe s"login password='$R' n=1"
    (Redaction.redact("Server=db;Password='p&ss=X';Database=d") should not).include("ss=X")
  }

  it should "leave a value in escaped quotes as the passes read it where it does not close (#1707)" in {
    // a quote behind a different run of backslashes, a `"` that ends the string, the end of the input, a line break
    Seq(
      """x=1&password=\\'p&ss=QZXJ\\\' n=1""",
      """{"q":"x=1&password=\\'p&ss=Q","n":"x"}""",
      """x=1&password=\\'p&ss=QZXJ""",
      "x=1&password=\\\\'p&ss=QZXJ\n\\\\' n=1"
    ).foreach { input =>
      Redaction.redact(input) shouldBe Redaction.redactedWithRanges(input, singleQuotedFromInput = true)._1
      Redaction.redactedWithRanges(input, singleQuotedFromInput = true)._2 shouldBe
        Redaction.redactedWithRanges(input, singleQuotedFromInput = true, addedReadings = false)._2
    }
    // a key that is not sensitive
    Redaction.redact("""x=1&user=\\'p&ss=QZXJ\\' n=1""") shouldBe """x=1&user=\\'p&ss=QZXJ\\' n=1"""
  }

  it should "redact as before where an empty query value ends a JSON string (#1707)" in {
    // The `,` after such a string is still replaced, as before: what keeps the document valid there is #1800.
    Redaction.redact("""{"q":"a=1&token=","n":"x"}""") shouldBe s"""{"q":"a=1&token="$R"n":"x"}"""
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
      // as on main: the value after `token=` is read as running to the next string's opening quote
      val expected = input.replaceFirst("=\",\"", s"=\"$R\"")
      Redaction.redact(input) shouldBe expected
      Redaction.safeBody(input) shouldBe expected
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

  it should "replace all it replaces without the added readings, and more (#1707)" in {
    // The reading of #1707 adds edits only where no edit already is: every range the passes and the readings of #1695
    // replace is replaced as before, nothing they redact is written out, and JSON that parses without it still
    // parses. Deterministic.
    val docs   = new Docs(1707)
    var closed = 0
    (1 to 2000).foreach { _ =>
      val (input, secrets) = docs.next()
      val (out, added)     = Redaction.redactedWithRanges(input, singleQuotedFromInput = true)
      val (base, kept)     = Redaction.redactedWithRanges(input, singleQuotedFromInput = true, addedReadings = false)
      withClue(s"input $input, output $out, without $base: ") {
        Redaction.redact(input) shouldBe out
        kept.filterNot(added.contains) shouldBe empty
        added.filterNot(kept.contains).foreach { case (s, e) =>
          kept.exists { case (ks, ke) => ke > s && ks < e } shouldBe false
        }
        secrets.filterNot(base.contains).filter(out.contains) shouldBe empty
        if (parses(base)) parses(out) shouldBe true
      }
      closed += secrets.count(s => base.contains(s) && !out.contains(s))
    }
    // and it is the reading that redacts what the passes left
    closed should be > 300
  }

  it should "read values in escaped quotes in time linear in the input (#1707)" in {
    val shapes: Seq[(String, Int => String)] = Seq(
      "values in escaped quotes"    -> (n => "x=1&password=\\\\'p&ss=QZXJ\\\\' " * n),
      "escaped quotes never closed" -> (n => "&password=\\\\'p&ss=QZXJ " * n),
      "one open quote, many &"      -> (n => "token=\\\\'" + "a&b=c" * n),
      "a long run after an open"    -> (n => "password=\\\\\\'" + "\\" * (n * 4)),
      "escaped quotes, one by one"  -> (n => "password=" + "\\'" * n),
      "many keys in one open value" -> (n => "password=\\\\'x" + "&password=\\\\'x" * n),
      "values in a JSON string"     -> (n => "{\"q\":\"" + "x=1&password=\\\\'p&ss=Q\\\\' " * n + "\"}")
    )
    shapes.foreach { case (name, build) =>
      LinearTime.assertLinear(name, build(2500), build(10000))(Redaction.redact(_))
    }
  }
}
