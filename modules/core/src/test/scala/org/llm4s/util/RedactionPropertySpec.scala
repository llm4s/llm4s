package org.llm4s.util

import org.scalacheck.{ Gen, Shrink }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import scala.util.Try

/**
 * The properties that replace most of the shape-by-shape examples (#1932): over generated documents - JSON, Python
 * reprs and calls, query strings, `key=value` prose, header and YAML lines, mixed with prose that holds apostrophes,
 * stray quotes and question marks, and carried raw or inside one to three layers of escaped strings - redaction
 *
 *  - leaves no planted secret under a sensitive key readable,
 *  - is idempotent,
 *  - keeps a JSON document a JSON document,
 *  - leaves a document with no sensitive key unchanged,
 *  - and never throws, whatever the input.
 */
class RedactionPropertySpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 2000)

  // Shrinking a document cuts it anywhere, into text no generator writes: a failure is reported as generated.
  implicit val noShrink: Shrink[String]           = Shrink.shrinkAny
  implicit val noShrinkLayers: Shrink[(Doc, Int)] = Shrink.shrinkAny

  /** A rendered document and the secrets planted in it. */
  final case class Doc(text: String, secrets: Seq[String])

  private val sensitiveKeys = Seq(
    "password",
    "api_key",
    "apiKey",
    "token",
    "client_secret",
    "access_token",
    "secret",
    "x-api-key",
    "Authorization",
    "passwd",
    "refresh_token",
    "db_password",
    "api_keys",
    "secrets",
    "credentials",
    "private_key",
    "auth_token",
    "client_assertion",
    "Proxy-Authorization",
    "Set-Cookie"
  )

  /** Keys that name no credential: counts, settings and the near misses the lists leave alone. */
  private val plainKeys =
    Seq("model", "max_tokens", "tokens", "user", "note", "cookie_policy", "token_count", "monkey", "passwordless")

  /** A secret: a unique marker, alone (`plain`) or with characters a quoted value may hold around it. */
  private def genSecret(id: Int, quoted: Boolean): Gen[String] = {
    val marker = s"Zx${id}xZ"
    if (!quoted) Gen.oneOf(marker, s"q_$marker", s"$marker-9", s"a.$marker")
    else
      Gen.oneOf(
        marker,
        s"it's $marker",
        s"$marker\"q",
        s"a&b=$marker",
        s"$marker, $marker",
        s"p\\$marker",
        s"]}$marker",
        s"$marker'}"
      )
  }

  private def markerOf(id: Int): String = s"Zx${id}xZ"

  /** Python's repr of a str. */
  private def pyRepr(s: String): String = {
    val q = if (s.contains('\'') && !s.contains('"')) '"' else '\''
    q.toString + s.flatMap {
      case '\\'        => "\\\\"
      case c if c == q => "\\" + c
      case '\n'        => "\\n"
      case '\r'        => "\\r"
      case '\t'        => "\\t"
      case c           => c.toString
    } + q
  }

  private def json(s: String): String = ujson.Str(s).render()

  /**
   * Whitespace at a structural position of a container or a call: none, blanks, a tab, or a line break (`\n` or
   * `\r\n`) with or without indentation - pretty-printed JSON, a Python repr, keyword arguments one per line.
   */
  private val genWs: Gen[String] =
    Gen.frequency(
      4 -> Gen.const(""),
      3 -> Gen.const(" "),
      1 -> Gen.const("\t"),
      2 -> Gen.const("\n"),
      2 -> Gen.const("\n  "),
      2 -> Gen.const("\r\n"),
      1 -> Gen.const("\r\n\t"),
      1 -> Gen.const(" \n\n    ")
    )

  /** Blanks outside a bracket, where a line break would end the line's key: none, spaces or a tab. */
  private val genBlanks: Gen[String] = Gen.oneOf("", " ", "\t", "  ", " \t ")

  /**
   * The blanks before and after a `key=` outside a bracket. A blank after a glued `=` (`token= see the docs`) reads
   * as prose there unless the value looks like a credential, so the markers, too short to, are planted only with a
   * blank before the `=` too.
   */
  private val genPairBlanks: Gen[(String, String)] =
    Gen.oneOf(("", ""), (" ", ""), (" ", " "), ("\t", "\t"), ("  ", " "))

  /** One field under a sensitive key, in one of the shapes a credential reaches a log in. */
  private def genField(id: Int): Gen[(String, String)] =
    for {
      key    <- Gen.oneOf(sensitiveKeys)
      quoted <- genSecret(id, quoted = true)
      plain  <- genSecret(id, quoted = false)
      // the whitespace at the structural positions of a container or call: after an opening bracket, around a key's
      // `:` or `=`, between elements and before a closing bracket
      (a, b, c, d, e, f, g, h, i) <- Gen.zip(genWs, genWs, genWs, genWs, genWs, genWs, genWs, genWs, genWs)
      bb                          <- genBlanks
      (pb, pa)                    <- genPairBlanks
      ident = key.replace('-', '_')
      shape <- Gen.oneOf[String](
        s"""{$a"$key"$b:$c${json(quoted)}$d,$e"n":${f}1$g}""",
        s"""{$a"$key"$b:$c[$d${json(quoted)}$e,$f${json(plain)}$g]$h,$i"user": "bob"}""",
        s"""{$a"$key"$b:$c{$d"value"$e:$f${json(quoted)}$g, "on": true$h}$i}""",
        s"""{$a"$key"$b:$c${90000000 + id}$d}""",
        s"{$a'$key'$b:$c${pyRepr(quoted)}$d,$e'user'$f:$g'bob'$h}",
        s"{$a'$key'$b:$c[$d${pyRepr(quoted)}$e,${f}2$g],$h'n': 1$i}",
        s"{$a'$key'$b:$c($d${pyRepr(quoted)}$e,$f),$g'n': 1$h}",
        s"Settings($a$ident$b=$c${pyRepr(quoted)}$d,${e}retries=3$f)",
        s"Settings($a$ident$b=${c}SecretStr($d${pyRepr(quoted)}$e)$f,${g}user='bob'$h)",
        s"{$a'$key'$b:${c}SecretStr($d${pyRepr(quoted)}$e)$f}",
        s"$key=$plain next=1",
        s"$ident=${json(quoted)} user=bob",
        s"$ident = ${json(quoted)}",
        s"https://h.example/p?a=1&$key=${URLEncoder.encode(quoted, StandardCharsets.UTF_8)}&b=2",
        s"\n$key: $plain\nnext: 1",
        s"\nconfig:\n  $key: $plain\n  port: 5432",
        s"https://user:${URLEncoder.encode(plain, StandardCharsets.UTF_8)}@db.example.com:5432/x",
        // a header is a line of its own: its value runs to the end of the line, over whatever a separator put after it
        s"\nAuthorization: Bearer $plain\n",
        s"\nCookie: sid=$plain; theme=dark\nAccept: */*",
        // whitespace, line breaks among it, between a key and its `:` or `=` and after it, inside a bracket: a bare
        // key's `:` (a JavaScript object, YAML flow), a quoted key's `=`, a leaf's `=` in a container under a sensitive
        // key, and a call's name before its `(`
        s"{$a$ident$b:$c${pyRepr(quoted)}$d,${e}n$f:${g}1$h}",
        s"{$a$ident$b:$c$plain$d}",
        s"(${a}retries=3,$b$ident$c=$d$plain$e)",
        s"""{$a"$key"$b=$c${json(quoted)}$d}""",
        s"""{"$key":$a[${b}note$c=$d$plain$e]}""",
        s"Settings($a$ident$b=${c}SecretStr$d(${pyRepr(quoted)}))",
        // outside a bracket, blanks: before a header's `:`, around a pair's `=`, a quoted key's `=` and before a
        // call's `(`; a line break ends the line's key there
        s"\n$key$bb: $plain\nnext: 1",
        s"$key$pb=$pa$plain next=1",
        s"$ident$pb=${pa}SecretStr$bb(${pyRepr(quoted)})",
        s"""\n"$key"$bb=$pa${json(quoted)}\n"""
      )
    } yield {
      val secret = if (shape.contains(s"${90000000 + id}")) s"${90000000 + id}" else markerOf(id)
      (shape, secret)
    }

  /** Prose and data that carry no credential: apostrophes, stray quotes, questions, mentions of keys. */
  private val genNoise: Gen[String] = Gen.oneOf(
    "it's fine",
    "O'Brien said",
    "the users' list",
    "Is this right?",
    "size 5\" disk",
    "see 'token': [ for details",
    "a=1&b=2",
    "max_tokens=100",
    """{"model": "gpt-4o"}""",
    "{'a': 'b'}",
    "[1, 2]",
    "http://x.test/?q=1&lang=en",
    "(a, b)",
    "#tag",
    "Tom &amp; Jerry",
    "Reset your password: the link expires."
  )

  /** Ways to carry a document: raw, in a JSON string (one to three deep), a Python repr, Gson's escapes, a log line. */
  private def wraps: Seq[String => String] = Seq(
    identity,
    d => ujson.Obj("msg" -> d, "n" -> 1).render(),
    d => ujson.Obj("o" -> ujson.Obj("m" -> d).render()).render(),
    d => ujson.Obj("a" -> ujson.Obj("b" -> ujson.Obj("c" -> d).render()).render()).render(),
    d => "{'body': " + pyRepr(d) + "}",
    d =>
      "{\"log\":" + json(d).flatMap {
        case '=' => "\\u003d"
        case '&' => "\\u0026"
        case '<' => "\\u003c"
        case c   => c.toString
      } + "}",
    d => "level=info msg=" + json(d) + " status=200",
    d => "INFO payload: " + d
  )

  private val genDoc: Gen[Doc] =
    for {
      count  <- Gen.choose(1, 4)
      fields <- Gen.sequence[List[(String, String)], (String, String)]((0 until count).map(genField))
      noise  <- Gen.listOfN(count, Gen.option(genNoise))
      sep    <- Gen.oneOf(" ", "\n", ", ", " | ")
      wrap   <- Gen.oneOf(wraps)
    } yield {
      val parts = fields.zip(noise).flatMap { case ((f, _), n) => n.toList :+ f }
      Doc(wrap(parts.mkString(sep)), fields.map(_._2))
    }

  /** Documents of keys that name no credential, in the same shapes and carriers. */
  private val genPlainDoc: Gen[String] =
    for {
      count <- Gen.choose(1, 4)
      parts <- Gen.listOfN(
        count,
        for {
          key   <- Gen.oneOf(plainKeys)
          value <- Gen.oneOf("gpt-4o", "it's fine", "a b", "42", "x=y")
          noise <- genNoise
          shape <- Gen.oneOf(
            s"""{"$key": ${json(value)}}""",
            s"{'$key': ${pyRepr(value)}}",
            s"$key=${URLEncoder.encode(value, StandardCharsets.UTF_8)}",
            // a query parameter is sensitive by substring too (`?max_tokens=` holds `token`): a key that holds none
            s"https://h.example/p?${if (key == "model" || key == "user" || key == "note") key else "lang"}=" +
              URLEncoder.encode(value, StandardCharsets.UTF_8),
            s"\n$key: $value",
            noise
          )
        } yield shape
      )
      sep  <- Gen.oneOf(" ", "\n", ", ")
      wrap <- Gen.oneOf(wraps)
    } yield wrap(parts.mkString(sep))

  private val genFuzz: Gen[String] =
    Gen
      .listOf(
        Gen.oneOf(
          Gen.oneOf(
            "\\",
            "\\\\",
            "\\u",
            "\\u0027",
            "\\u0022",
            "\\u0026",
            "\\uZZZZ",
            "\\n",
            "\"",
            "'",
            "&",
            "=",
            "?",
            "#",
            ":",
            ",",
            "[",
            "]",
            "{",
            "}",
            "(",
            ")",
            " ",
            "\n",
            "\r",
            "password",
            "token=",
            "'secret': ",
            "Bearer ",
            "Cookie: ",
            "https://u:p@h",
            ";jsessionid=",
            "[REDACTED]",
            "x",
            "Zq"
          ),
          Gen.alphaNumStr.map(_.take(4))
        )
      )
      .map(_.mkString)

  /** The ids of the fields are local to a document, so each document's markers are unique within it. */
  "Redaction.redact" should "leave no planted secret readable under a sensitive key, whatever the shape and nesting" in {
    forAll(genDoc) { doc =>
      val out = Redaction.redact(doc.text)
      doc.secrets.foreach(s => withClue(s"input: ${doc.text}\noutput: $out\n")((out should not).include(s)))
    }
  }

  /** `doc` put `layers` times inside a JSON string, as a log that is logged again is. */
  private def encoded(doc: String, layers: Int): String =
    (1 to layers).foldLeft(doc)((inner, k) => ujson.Obj(s"m$k" -> inner, "n" -> k).render())

  /**
   * A string at any depth of encoding: nested JSON is decoded up to `RedactionScanner.MaxDepth` encodings, and a string
   * that still holds an escape past that is replaced whole, so nothing past the limit is read undecoded. The deep cases
   * are few: each encoding doubles the backslashes before a quote.
   */
  it should "leave no planted secret readable at any depth of string encoding, past the decoding limit too" in {
    val genDeep: Gen[(Doc, Int)] =
      for {
        field  <- genField(0)
        layers <- Gen.frequency(6 -> Gen.choose(0, 8), 4 -> Gen.choose(9, 16), 2 -> Gen.choose(17, 20))
      } yield (Doc(encoded(field._1, layers), Seq(field._2)), layers)
    forAll(genDeep, minSuccessful(120)) { case (doc, layers) =>
      val out = Redaction.redact(doc.text)
      doc.secrets.foreach(s =>
        withClue(s"layers: $layers\ninput: ${doc.text.take(4000)}\noutput: ${out.take(4000)}\n")(
          (out should not).include(s)
        )
      )
    }
  }

  it should "leave no secret readable at the decoding limit's edges: 8, 9, 16, 17 and 20 encodings" in {
    Seq(1, 2, 8, 9, 15, 16, 17, 18, 20).foreach { layers =>
      Seq(
        """{"password":"hunter2Zq","user":"bob"}""",
        "{'password': 'hunter2Zq'}",
        "login password=hunter2Zq ok",
        "{password : 'hunter2Zq'}"
      ).foreach { doc =>
        val in  = encoded(doc, layers)
        val out = Redaction.redact(in)
        withClue(s"layers $layers, $doc: ") {
          (out should not).include("hunter2Zq")
          Try(ujson.read(out)).isSuccess shouldBe Try(ujson.read(in)).isSuccess
          Redaction.redact(out) shouldBe out
        }
      }
    }
  }

  it should "be idempotent" in {
    forAll(genDoc) { doc =>
      val once = Redaction.redact(doc.text)
      withClue(s"input: ${doc.text}\nonce: $once\n")(Redaction.redact(once) shouldBe once)
    }
  }

  it should "keep a JSON document a JSON document" in {
    forAll(genDoc) { doc =>
      whenever(Try(ujson.read(doc.text)).isSuccess) {
        val out = Redaction.redact(doc.text)
        withClue(s"input: ${doc.text}\noutput: $out\n")(Try(ujson.read(out)).isSuccess shouldBe true)
      }
    }
  }

  it should "leave a document with no sensitive key unchanged" in {
    forAll(genPlainDoc)(doc => Redaction.redact(doc) shouldBe doc)
  }

  it should "never throw, whatever the input" in {
    // Idempotence is a property of the documents the generators above write. Over syntax soup a second redaction may
    // pair a quote the first wrote around a placeholder with a stray one, and replace more: never less.
    forAll(genFuzz) { s =>
      val once = Redaction.redact(s)
      Try(Redaction.redact(once)).isSuccess shouldBe true
      Try(Redaction.safeBody(s)).isSuccess shouldBe true
    }
  }

}
