package org.llm4s.util

import org.llm4s.testutil.LinearTime
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.{ Random, Try }

/**
 * Plural credential keys (`secrets`, `api_keys`, `passwords`) in every shape, and the query parameters that carry a
 * short-lived credential (`sig`, `X-Amz-Signature`, `;jsessionid=`, `code`, `session`) (#1646). `tokens` stays a
 * count.
 */
class RedactionPluralKeysSpec extends AnyFlatSpec with Matchers {

  private val R = Redaction.RedactionPlaceholder

  private def parses(json: String): Boolean = Try(ujson.read(json)).isSuccess

  "Redaction.redact" should "redact a plural credential key's array, the issue's repros (#1646)" in {
    Redaction.redact("""{"secrets": ["hunter2value"]}""") shouldBe s"""{"secrets": ["$R"]}"""
    Redaction.redact("""{"api_keys": ["hunter2value", "hunter3value"]}""") shouldBe s"""{"api_keys": ["$R", "$R"]}"""
    Redaction.redact("""{"passwords": ["hunter2value"]}""") shouldBe s"""{"passwords": ["$R"]}"""
  }

  it should "redact a plural credential key in every shape: string, number, object, key=value, header (#1646)" in {
    Redaction.redact("""{"secrets": "hunter2value"}""") shouldBe s"""{"secrets": "$R"}"""
    Redaction.redact("""{"passwords": 123456, "user": "bob"}""") shouldBe s"""{"passwords": "$R", "user": "bob"}"""
    Redaction.redact("""{"client_secrets": {"a": "hunter2value"}}""") shouldBe s"""{"client_secrets": {"a": "$R"}}"""
    Redaction.redact("secrets=hunter2value") shouldBe s"secrets=$R"
    Redaction.redact("apiKeys=hunter2value&user=bob") shouldBe s"apiKeys=$R&user=bob"
    Redaction.redact("X-Api-Keys: hunter2value") shouldBe s"X-Api-Keys: $R"
    Redaction.redact("{'passwords': ['hunter2value'], 'user': 'bob'}") shouldBe s"{'passwords': ['$R'], 'user': 'bob'}"
    // every spelling `apikeys` normalises from, and a compound name
    Seq("api_keys", "apiKeys", "api-keys", "API_KEYS", "x-api-keys", "db_passwords", "Secrets").foreach { key =>
      Redaction.redact(s"""{"$key": "hunter2value"}""") shouldBe s"""{"$key": "$R"}"""
    }
  }

  it should "redact a plural credential key in JSON that sits inside a string, and keep both valid (#1646)" in {
    val out = Redaction.redact("""{"body":"{\"secrets\": [\"hunter2value\"], \"n\": 1}"}""")
    out shouldBe s"""{"body":"{\\"secrets\\": [\\"$R\\"], \\"n\\": 1}"}"""
    parses(out) shouldBe true
    parses(ujson.read(out)("body").str) shouldBe true
  }

  it should "keep tokens, and keys that only contain a plural, as they were (#1646)" in {
    Seq(
      "monkey=banana tokens=5 passwordless=yes",
      """{"tokens": {"a": "b"}, "max_tokens": 5, "usage": {"tokens": 12}}""",
      """{"secrets_count": 3, "password_policy": "strict", "secrets_manager": "vault"}""",
      "the secrets of scala and my passwords",
      "api_keys_count=2"
    ).foreach(input => Redaction.redact(input) shouldBe input)
  }

  it should "redact the URL parameters that carry a short-lived credential (#1646)" in {
    Redaction.redact("https://acct.blob.core.windows.net/c/b?sv=2021&se=2026&sig=abc%2Bdef&sp=r") shouldBe
      s"https://acct.blob.core.windows.net/c/b?sv=2021&se=2026&sig=$R&sp=r"
    Redaction.redact("https://b.s3.amazonaws.com/k?X-Amz-Credential=AK&X-Amz-Signature=deadbeef01") shouldBe
      s"https://b.s3.amazonaws.com/k?X-Amz-Credential=$R&X-Amz-Signature=$R"
    Redaction.redact("https://storage.googleapis.com/b/o?X-Goog-Signature=abc123&X-Goog-Date=2026") shouldBe
      s"https://storage.googleapis.com/b/o?X-Goog-Signature=$R&X-Goog-Date=2026"
    Redaction.redact("https://app.example.com/cb?code=AUTHCODE123&state=xyz") shouldBe
      s"https://app.example.com/cb?code=$R&state=xyz"
    Redaction.redact("https://app.example.com/x?session=SESS123&user=bob") shouldBe
      s"https://app.example.com/x?session=$R&user=bob"
    Redaction.redact("/login?sessionid=SESS123&signature=SIG456") shouldBe s"/login?sessionid=$R&signature=$R"
    // the matrix parameter, whose value ends at `?`, `;`, `/` or `#`
    Redaction.redact("https://app.example.com/home;jsessionid=ABCDEF0123?lang=en") shouldBe
      s"https://app.example.com/home;jsessionid=$R?lang=en"
    Redaction.redact("/a;jsessionid=ABCDEF0123/b") shouldBe s"/a;jsessionid=$R/b"
    // inside JSON, also with `&` and `=` escaped as Gson writes them
    val json = Redaction.redact("""{"url":"https://app.example.com/cb?code=AUTHCODE123&state=xyz"}""")
    json shouldBe s"""{"url":"https://app.example.com/cb?code=$R&state=xyz"}"""
    val (amp, eq) = ("\\u0026", "\\u003d")
    val gson      = Redaction.redact(s"""{"url":"https://x.io/cb?a=1${amp}code${eq}AUTHCODE123${amp}state${eq}xyz"}""")
    (gson should not).include("AUTHCODE123")
    gson should include(s"state${eq}xyz")
    parses(gson) shouldBe true
  }

  it should "leave those names alone where they are no query parameter's key (#1646)" in {
    Seq(
      """{"code": 400, "message": "bad"}""",
      """{"error": {"code": "invalid_api_key"}}""",
      "?zipcode=94110&error_code=E1&signal=1&design=flat",
      "?codec=h264&sigma=2&session_timeout=30",
      "code=1 sig=2 session=3",
      "?q=code&lang=en"
    ).foreach(input => Redaction.redact(input) shouldBe input)
  }

  /** Documents with plural keys and credential parameters in mixed shapes, with the words that must not survive. */
  private def docs(seed: Long, count: Int): Seq[(String, String)] = {
    val rnd                    = new Random(seed)
    def pick[A](xs: Seq[A]): A = xs(rnd.nextInt(xs.length))
    (1 to count).map { i =>
      val w   = s"Qp${i}z"
      val key = pick(Seq("secrets", "api_keys", "apiKeys", "passwords", "client_secrets"))
      val doc = rnd.nextInt(8) match {
        case 0 => s"""{"$key": ["$w"], "user": "bob"}"""
        case 1 => ujson.write(ujson.Obj("body" -> s"""{"$key": "$w", "max_tokens": 5}"""))
        case 2 => s"$key=$w&tokens=5"
        case 3 => s"{'$key': ['$w'], 'user': 'bob'}"
        case 4 => s"https://x.io/a?${pick(Seq("sig", "code", "session", "X-Amz-Signature"))}=$w&page=2"
        case 5 => s"https://x.io/a;jsessionid=$w?page=2"
        case 6 => ujson.write(ujson.Obj("url" -> s"https://x.io/a?sv=1&sig=$w", "code" -> 400))
        case _ => s"""$key: $w"""
      }
      (doc, w)
    }
  }

  it should "replace all it replaced before, and the plural keys' values too (#1646)" in {
    docs(1646, 3000).foreach { case (input, secret) =>
      val (out, added) = Redaction.redactedWithRanges(input, singleQuotedFromInput = true)
      val (base, kept) = Redaction.redactedWithRanges(input, singleQuotedFromInput = true, addedReadings = false)
      withClue(s"input $input, output $out, without $base: ") {
        Redaction.redact(input) shouldBe out
        kept.filterNot(added.contains) shouldBe empty
        (out should not).include(secret)
        if (parses(input)) parses(out) shouldBe true
      }
    }
  }

  it should "never throw, and read plural keys in time linear in the input (#1646)" in {
    val rnd   = new Random(1646)
    val parts = Seq("secrets", "s", "=", ";jsessionid", "?code", "&", "\\u0026", ":", "\"", "'", "[", " ", "/", "?")
    (1 to 2000).foreach { _ =>
      val input = Iterator.fill(1 + rnd.nextInt(30))(parts(rnd.nextInt(parts.length))).mkString
      withClue(s"input $input: ")(Try(Redaction.redact(input)).isSuccess shouldBe true)
    }
    val shapes: Seq[(String, Int => String)] = Seq(
      "plural words"      -> (n => "secrets " * n),
      "plural pairs"      -> (n => "secrets=a&" * n),
      "plural arrays"     -> (n => "{\"api_keys\": [\"a\", " * n),
      "query names"       -> (n => "&code=x" * n),
      "matrix parameters" -> (n => ";jsessionid=a" * n),
      "one long word"     -> (n => "a" * (8 * n) + "secrets")
    )
    shapes.foreach { case (name, build) =>
      LinearTime.assertLinear(name, build(1000), build(4000))(Redaction.redact(_))
    }
  }
}
