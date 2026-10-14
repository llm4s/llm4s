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
  implicit val noShrink: Shrink[String] = Shrink.shrinkAny

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
      case c           => c.toString
    } + q
  }

  private def json(s: String): String = ujson.Str(s).render()

  /** One field under a sensitive key, in one of the shapes a credential reaches a log in. */
  private def genField(id: Int): Gen[(String, String)] =
    for {
      key    <- Gen.oneOf(sensitiveKeys)
      quoted <- genSecret(id, quoted = true)
      plain  <- genSecret(id, quoted = false)
      ident = key.replace('-', '_')
      shape <- Gen.oneOf[String](
        s"""{"$key": ${json(quoted)}, "n": 1}""",
        s"""{"$key": [${json(quoted)}, ${json(plain)}], "user": "bob"}""",
        s"""{"$key": {"value": ${json(quoted)}, "on": true}}""",
        s"""{"$key": ${90000000 + id}}""",
        s"{'$key': ${pyRepr(quoted)}, 'user': 'bob'}",
        s"{'$key': [${pyRepr(quoted)}, 2], 'n': 1}",
        s"{'$key': (${pyRepr(quoted)},), 'n': 1}",
        s"Settings($ident=${pyRepr(quoted)}, retries=3)",
        s"Settings($ident=SecretStr(${pyRepr(quoted)}), user='bob')",
        s"$key=$plain next=1",
        s"$ident=${json(quoted)} user=bob",
        s"$ident = ${json(quoted)}",
        s"https://h.example/p?a=1&$key=${URLEncoder.encode(quoted, StandardCharsets.UTF_8)}&b=2",
        s"\n$key: $plain\nnext: 1",
        s"\nconfig:\n  $key: $plain\n  port: 5432",
        s"https://user:${URLEncoder.encode(plain, StandardCharsets.UTF_8)}@db.example.com:5432/x",
        s"\nAuthorization: Bearer $plain",
        s"\nCookie: sid=$plain; theme=dark\nAccept: */*"
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
