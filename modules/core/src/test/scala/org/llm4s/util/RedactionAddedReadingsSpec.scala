package org.llm4s.util

import org.llm4s.testutil.LinearTime
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.{ Random, Try }

/**
 * Four places where a credential stayed readable (#1695), each closed by a reading of the input that only adds to
 * what the passes replace: a pair after the JSON escape of a line break in a Gson-escaped log, the tail of a value
 * after a literal `&`, the field after a query value written right before JSON, and the value of a pair after a
 * `Bearer` or `Basic` scheme word.
 */
class RedactionAddedReadingsSpec extends AnyFlatSpec with Matchers {

  private val R = Redaction.RedactionPlaceholder

  private def parses(json: String): Boolean = Try(ujson.read(json)).isSuccess

  /** `=` and `&` as Gson's default HTML-safe escaping writes them. */
  private val Eq  = "\\u003d"
  private val Amp = "\\u0026"

  "Redaction.redact" should "redact Gson-escaped form bodies and log pairs, the issue's repros (#1695)" in {
    // The issue's two inputs, which the escaped `=` reading of #1676 already redacts; kept as regression tests.
    Redaction.redact(s"""{"form":"password${Eq}hunter2${Amp}username${Eq}bob"}""") shouldBe
      s"""{"form":"password$Eq$R${Amp}username${Eq}bob"}"""
    Redaction.redact(s"""{"log":"user${Eq}bob password${Eq}hunter2"}""") shouldBe
      s"""{"log":"user${Eq}bob password$Eq$R"}"""
  }

  it should "redact a pair whose key follows the JSON escape of a line break or a tab (#1695)" in {
    // Read from the letter of the escape, the key was `ntoken`, which names no credential.
    Redaction.redact(s"""{"log":"user${Eq}bob\\ntoken${Eq}abc"}""") shouldBe s"""{"log":"user${Eq}bob\\ntoken$Eq$R"}"""
    Redaction.redact("""{"log":"start\ntoken=abc\tnext"}""") shouldBe s"""{"log":"start\\ntoken=$R\\tnext"}"""
    Redaction.redact("""{"log":"INFO id=2\nrefresh_token=abc done"}""") shouldBe
      s"""{"log":"INFO id=2\\nrefresh_token=$R done"}"""
    Redaction.redact("""line one\u000atoken=abc""") shouldBe s"""line one\\u000atoken=$R"""
    Redaction.redact(s"""{"log":"a\\r\\nAuthorization${Eq}abc\\r\\nx${Eq}1"}""") shouldBe
      s"""{"log":"a\\r\\nAuthorization$Eq$R\\r\\nx${Eq}1"}"""
    // depth 2: the log in a JSON string inside a JSON string
    val depth2 = s"""{"outer":"{\\"log\\":\\"a\\\\ntoken\\\\u003dabc\\"}"}"""
    val out    = Redaction.redact(depth2)
    out shouldBe s"""{"outer":"{\\"log\\":\\"a\\\\ntoken\\\\u003d$R\\"}"}"""
    ujson.read(ujson.read(out)("outer").str)("log").str shouldBe s"a\ntoken=$R"
  }

  it should "redact the rest of a value after a literal & that no other pair follows (#1695)" in {
    Redaction.redact("password=p&ssW0rd") shouldBe s"password=$R&$R"
    Redaction.redact("x password=p&ssW0rd y") shouldBe s"x password=$R&$R y"
    Redaction.redact("password=p&ss&W0rd") shouldBe s"password=$R&$R&$R"
    Redaction.redact("password=&ssW0rd") shouldBe s"password=&$R"
    Redaction.redact("""{"form":"password=p&ssW0rd"}""") shouldBe s"""{"form":"password=$R&$R"}"""
    Redaction.redact(s"""{"form":"password${Eq}p&ssW0rd${Amp}user${Eq}bob"}""") shouldBe
      s"""{"form":"password$Eq$R&$R${Amp}user${Eq}bob"}"""
    // a pair after the `&` is a pair, and its value is read as before
    Redaction.redact("password=p&ssW0rd&user=bob&token=t&ok") shouldBe s"password=$R&$R&user=bob&token=$R&$R"
    Redaction.redact("password=abc&user=bob") shouldBe s"password=$R&user=bob"
    Redaction.redact("user=bob&page=2&lang=en") shouldBe "user=bob&page=2&lang=en"
  }

  it should "redact the field after a query value written right before JSON (#1695)" in {
    Redaction.redact("""?a=1&token=abc,{\"api_key\":\"S3CR3T\"}""") shouldBe
      s"""?a=1&token=$R\\":\\"$R\\"}"""
    Redaction.redact("""?a=1&token=abc,{"api_key":"S3CR3T"}""") shouldBe s"""?a=1&token=$R":"$R"}"""
    Redaction.redact("""GET /x?a=1&token=abc,"api_key": "S3CR3T"""") shouldBe s"""GET /x?a=1&token=$R": "$R""""
    (Redaction.redact("""?token=abc,{'password': 'S3CR3T'}""") should not).include("S3CR3T")
    // no sensitive key in the query, or no JSON after the comma: as before
    Redaction.redact("""?next=/x,{"a":1}""") shouldBe """?next=/x,{"a":1}"""
    Redaction.redact("""?token=a,b""") shouldBe s"""?token=$R"""
  }

  it should "redact the value of a pair after a Bearer or Basic scheme word (#1695)" in {
    Redaction.redact("Bearer password=x") shouldBe s"$R=$R"
    Redaction.redact("using Bearer password=hunter2 more") shouldBe s"using $R=$R more"
    Redaction.redact("bearer api_key=x&token=y") shouldBe s"$R=$R&token=$R"
    (Redaction.redact(s"Bearer password${Eq}hunter2") should not).include("hunter2")
    (Redaction.redact("Basic token=\"hunter 2\"") should not).include("hunter")
    (Redaction.redact("""header Bearer secret='hunter2'""") should not).include("hunter2")
    val inJson = Redaction.redact("""{"h":"Bearer password=hunter2","n":1}""")
    inJson shouldBe s"""{"h":"$R=$R","n":1}"""
    // a token that is no key of a pair, as before
    Redaction.redact("Bearer abc.def-123") shouldBe R
    Redaction.redact("Basic dXNlcjpwYXNz") shouldBe R
  }

  it should "keep JSON valid where a Bearer, query or pair reading meets the end of a string (#1695)" in {
    Seq(
      """{"h":"Bearer password=","n":1}""",
      """{"h":"Basic token=api_key=","n":1}""",
      """{"h":"?a=1&token=,'x","n":1}""",
      """{"m":"a\ntoken=","n":1}""",
      """{"m":"a\ntoken='","n":1}"""
    ).foreach { input =>
      val out = Redaction.redact(input)
      withClue(s"input $input, output $out: ")(parses(out) shouldBe true)
    }
  }

  /** Documents of the four shapes, each with its credentials, which are unique words that appear nowhere else. */
  final private class Docs(seed: Long) {
    private val rnd                    = new Random(seed)
    private var n                      = 0
    private def pick[A](xs: Seq[A]): A = xs(rnd.nextInt(xs.length))
    private def word(): String         = { n += 1; s"Kz${n}q" }
    private val keys                   = Seq("password", "token", "api_key", "client_secret", "access_token", "passwd")

    private def jsonString(s: String, gson: Boolean): String =
      ujson.Str(s).render().flatMap {
        case '=' if gson => Eq
        case '&' if gson => Amp
        case c           => c.toString
      }

    private def carried(raw: String): String =
      rnd.nextInt(3) match {
        case 0 => raw
        case 1 => s"""{"log":${jsonString(raw, gson = rnd.nextBoolean())},"n":1}"""
        case _ =>
          val inner = s"""{"log":${jsonString(raw, gson = true)}}"""
          s"""{"payload":${jsonString(inner, gson = rnd.nextBoolean())},"ok":true}"""
      }

    def next(): (String, Seq[String]) = {
      val (a, b) = (word(), word())
      val (raw, secrets) = rnd.nextInt(4) match {
        case 0 => (s"user=bob\n${pick(keys)}=$a&$b\npage=2", Seq(a, b))
        case 1 => (s"${pick(keys)}=$a&$b${pick(Seq("", "&user=bob", " next", ";"))}", Seq(a, b))
        case 2 => (s"GET /p?q=1&token=$a,{\"${pick(keys)}\":\"$b\"}", Seq(a, b))
        case _ => (s"${pick(Seq("Bearer", "Basic", "bearer"))} ${pick(keys)}=$a", Seq(a))
      }
      (carried(raw), secrets)
    }
  }

  it should "replace all it replaces without the added readings, and more (#1695)" in {
    // Each added reading's edits are made only where the passes made none: every range the passes replace is
    // replaced as before, nothing they redact is written out, and JSON that parses without the readings still
    // parses. Deterministic.
    val docs   = new Docs(1695)
    var closed = 0
    (1 to 2000).foreach { _ =>
      val (input, secrets) = docs.next()
      val (out, ranges)    = Redaction.redactedWithRanges(input, singleQuotedFromInput = true)
      val (base, kept)     = Redaction.redactedWithRanges(input, singleQuotedFromInput = true, addedReadings = false)
      withClue(s"input $input, output $out, without $base: ") {
        Redaction.redact(input) shouldBe out
        kept.filterNot(ranges.contains) shouldBe empty
        val added = ranges.filterNot(kept.contains)
        added.foreach { case (s, e) => kept.exists { case (ks, ke) => ke >= s && ks <= e } shouldBe false }
        secrets.filterNot(base.contains).filter(out.contains) shouldBe empty
        if (parses(base)) parses(out) shouldBe true
      }
      closed += secrets.count(s => base.contains(s) && !out.contains(s))
    }
    // and it is the readings that redact what the passes left
    closed should be > 1000
  }

  it should "read the added readings in time linear in the input (#1695)" in {
    val shapes: Seq[(String, Int => String)] = Seq(
      "values with a literal &"          -> (n => "password=p&ssW0rd " * n),
      "one tail of many &"               -> (n => "password=a" + "&b" * n),
      "pairs after every &"              -> (n => "password=a&b&user=c " * n),
      "empty values before &"            -> (n => "password=&" * n),
      "& before a long backslash run"    -> (n => "password=a&" + "\\" * (n * 4)),
      "Gson log lines"                   -> (n => "{\"log\":\"" + "user\\u003dbob\\ntoken\\u003dabc " * n + "\"}"),
      "escapes of line breaks"           -> (n => "\\n" * n + "token=x"),
      "escaped line breaks before words" -> (n => "\\nabcdefgh" * n),
      "queries before JSON"              -> (n => "?a=1&token=abc,{\\\"api_key\\\":\\\"S\\\"} " * n),
      "commas before quotes"             -> (n => "?token=a" + ",\"" * n),
      "Bearer pairs"                     -> (n => "Bearer password=x " * n),
      "Bearer before a backslash run"    -> (n => "Bearer password" + "\\" * (n * 4)),
      "Basic pairs in a string"          -> (n => "{\"h\":\"" + "Basic token=x " * n + "\"}")
    )
    shapes.foreach { case (name, build) =>
      LinearTime.assertLinear(name, build(2500), build(10000))(Redaction.redact(_))
    }
  }
}
