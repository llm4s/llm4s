package org.llm4s.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.Locale
import scala.util.Try

/**
 * The shapes in which a credential reaches a log, beyond the plain `"api_key": "value"` that `RedactionSpec`
 * covers: JSON inside a string, a value with an escaped quote, a number, `key=value` outside a URL, header-style
 * lines, compound key names and single quotes. Each shape is paired with the keys that must be left alone, because
 * `max_tokens` and its relatives appear in every provider exchange.
 */
class RedactionShapesSpec extends AnyFlatSpec with Matchers {

  private val R = Redaction.RedactionPlaceholder

  // A value that no pattern in SecretPatterns recognises, so only the key can make it a secret.
  private val secretText = "hunter2value"

  // Sizes for the large-value tests at the end of the file. Declared here, before the first test is registered.
  private val SmallStackBytes = 256L * 1024
  private val MegaChars       = 1000000

  // ---------------------------------------------------------------------------------------------
  // JSON inside a string: a prompt or a response body carries the credential with escaped quotes
  // ---------------------------------------------------------------------------------------------

  "Redaction.redact" should "redact a field of JSON that sits inside a string" in {
    val input = """{"content": "config: {\"api_key\": \"hunter2value\"}"}"""
    Redaction.redact(input) shouldBe s"""{"content": "config: {\\"api_key\\": \\"$R\\"}"}"""
  }

  it should "redact every credential of an embedded document and keep the rest" in {
    val input = """{"content": "{\"user\": \"ann\", \"password\": \"p1\", \"client_secret\": \"s2\", \"n\": \"x\"}"}"""
    val out   = Redaction.redact(input)
    out should include(s"""\\"password\\": \\"$R\\"""")
    out should include(s"""\\"client_secret\\": \\"$R\\"""")
    out should include("""\"user\": \"ann\"""")
    out should include("""\"n\": \"x\"""")
    (out should not).include("p1")
    (out should not).include("s2")
  }

  it should "leave embedded JSON without a sensitive key unchanged" in {
    val input = """{"content": "{\"city\": \"Oslo\", \"max_tokens\": \"100\"}"}"""
    Redaction.redact(input) shouldBe input
  }

  // ---------------------------------------------------------------------------------------------
  // A value that contains an escaped quote
  // ---------------------------------------------------------------------------------------------

  it should "redact the whole of a value that contains an escaped quote" in {
    val input = """{"password": "ab\"cd12345", "user": "ann"}"""
    val out   = Redaction.redact(input)
    out shouldBe s"""{"password": "$R", "user": "ann"}"""
    (out should not).include("cd12345")
  }

  it should "redact a value that ends with an escaped backslash" in {
    val input = """{"password": "tail\\", "user": "ann"}"""
    Redaction.redact(input) shouldBe s"""{"password": "$R", "user": "ann"}"""
  }

  // ---------------------------------------------------------------------------------------------
  // Numbers
  // ---------------------------------------------------------------------------------------------

  it should "redact a value that is cut off before its closing quote, as a truncated payload is" in {
    Redaction.redact("""{"user": "ann", "password": "hunter2va""") shouldBe s"""{"user": "ann", "password": "$R"""
    Redaction.redact("""{'api_key': 'hunter2va""") shouldBe s"""{'api_key': '$R"""
    Redaction.redact("""{"content": "{\"password\": \"hunter2va""") shouldBe s"""{"content": "{\\"password\\": \\"$R"""
    Redaction.redact("""{"password": "tail\""") shouldBe s"""{"password": "$R"""
  }

  it should "end a value inside a string at the quote that ends the enclosing string" in {
    // The embedded document is cut off by the end of the string that holds it: the value ends there.
    Redaction.redact("""{"content": "{\"password\": \"hunter2va", "n": 1}""") shouldBe
      s"""{"content": "{\\"password\\": \\"$R", "n": 1}"""
  }

  it should "redact a number when the key names a credential, and keep the JSON valid" in {
    val out = Redaction.redact("""{"password": 12345678, "user": "ann"}""")
    (out should not).include("12345678")
    val parsed = ujson.read(out)
    parsed("password").str shouldBe R
    parsed("user").str shouldBe "ann"
  }

  it should "redact a decimal and a negative number under a sensitive key" in {
    val out = Redaction.redact("""{"secret": -12.5, "pin_token": 7}""")
    (out should not).include("12.5")
    ujson.read(out)("secret").str shouldBe R
  }

  it should "leave a number under a key that is not a credential" in {
    val input = """{"max_tokens": 100, "temperature": 0.7, "count": 12}"""
    Redaction.redact(input) shouldBe input
  }

  it should "leave true, false and null alone, since they are not secrets" in {
    val input = """{"token": true, "password": false, "secret": null}"""
    Redaction.redact(input) shouldBe input
  }

  // ---------------------------------------------------------------------------------------------
  // key=value outside a URL query string
  // ---------------------------------------------------------------------------------------------

  it should "redact key=value pairs in a log line" in {
    Redaction.redact("login password=hunter2value ok") shouldBe s"login password=$R ok"
    Redaction.redact("token=hunter2value next") shouldBe s"token=$R next"
    Redaction.redact("api_key=hunter2value") shouldBe s"api_key=$R"
  }

  it should "redact the last segment of a dotted property name" in {
    Redaction.redact("spring.datasource.password=hunter2value") shouldBe s"spring.datasource.password=$R"
    Redaction.redact(
      "app.llm.api_key=hunter2value\napp.llm.model=gpt"
    ) shouldBe s"app.llm.api_key=$R\napp.llm.model=gpt"
  }

  it should "stop a key=value value at an ampersand, a comma, a semicolon or a quote" in {
    Redaction.redact("a=1&password=hunter2value&b=2") shouldBe s"a=1&password=$R&b=2"
    Redaction.redact("password=hunter2value, user=ann") shouldBe s"password=$R, user=ann"
    Redaction.redact("password=hunter2value; user=ann") shouldBe s"password=$R; user=ann"
    Redaction.redact("""env "PASSWORD=hunter2value" run""") shouldBe s"""env "PASSWORD=$R" run"""
  }

  it should "leave key=value pairs whose key is not a credential" in {
    val input = "max_tokens=100 temperature=0.2 user=ann"
    Redaction.redact(input) shouldBe input
  }

  it should "not treat the tail of a longer word as a key" in {
    // `monkey` ends in `key`, `tokens` is not `token`: neither is a credential.
    val input = "monkey=banana tokens=5 passwordless=yes"
    Redaction.redact(input) shouldBe input
  }

  // ---------------------------------------------------------------------------------------------
  // Header-style lines
  // ---------------------------------------------------------------------------------------------

  it should "redact a header-style line at the start of a line" in {
    Redaction.redact("x-api-key: hunter2value\nother: 1") shouldBe s"x-api-key: $R\nother: 1"
    Redaction.redact("secret: hunter2value") shouldBe s"secret: $R"
    Redaction.redact(
      "Accept: json\nX-Auth-Token: hunter2value\nHost: a"
    ) shouldBe s"Accept: json\nX-Auth-Token: $R\nHost: a"
  }

  it should "leave a header-style line whose key is not a credential" in {
    val input = "content-type: application/json\nx-request-id: 42"
    Redaction.redact(input) shouldBe input
  }

  it should "not redact the middle of a sentence that mentions a credential word" in {
    val input = "Reset your password: the link expires. Your token: is valid for an hour."
    Redaction.redact(input) shouldBe input
  }

  // ---------------------------------------------------------------------------------------------
  // Key names: compounds, casing, hyphens, single quotes
  // ---------------------------------------------------------------------------------------------

  it should "redact a JSON key that is a compound name, whatever its spelling" in {
    val keys = Seq(
      "clientSecret",
      "client_secret",
      "x-api-key",
      "X-API-KEY",
      "refresh_token",
      "refreshToken",
      "id_token",
      "access-token",
      "db_password",
      "dbPassword",
      "private_key",
      "session_token"
    )
    keys.foreach { key =>
      withClue(s"key $key: ") {
        Redaction.redact(s"""{"$key": "$secretText"}""") shouldBe s"""{"$key": "$R"}"""
      }
    }
  }

  it should "leave JSON keys that merely contain a credential word" in {
    val keys = Seq(
      "max_tokens",
      "prompt_tokens",
      "completion_tokens",
      "total_tokens",
      "tokens",
      "token_count",
      "token_type",
      "next_page_token",
      "cache_key",
      "idempotency_key",
      "monkey",
      "keyboard",
      "passwordless",
      "password_hint",
      "secretary"
    )
    keys.foreach { key =>
      withClue(s"key $key: ") {
        val input = s"""{"$key": "$secretText"}"""
        Redaction.redact(input) shouldBe input
      }
    }
  }

  it should "redact single-quoted JSON" in {
    Redaction.redact("{'api_key': 'hunter2value', 'user': 'ann'}") shouldBe s"{'api_key': '$R', 'user': 'ann'}"
  }

  it should "redact a key written in a different case" in {
    Redaction.redact(s"""{"API_KEY": "$secretText", "Password": "$secretText"}""") shouldBe
      s"""{"API_KEY": "$R", "Password": "$R"}"""
  }

  it should "not depend on the default locale to recognise a key" in {
    // Under a Turkish default locale "API_KEY".toLowerCase gives "apı_key", which names no credential.
    // No finally (scalafix NoKeywordFinally): Try runs the body, then the locale is restored.
    val previous = Locale.getDefault
    val outcome = Try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"))
      Redaction.redact(s"""{"API_KEY": "$secretText"}""") shouldBe s"""{"API_KEY": "$R"}"""
      Redaction.redact(s"API_KEY=$secretText") shouldBe s"API_KEY=$R"
      Redaction.redact(
        s"https://x.test/v1?API_KEY=$secretText&user=ann"
      ) shouldBe s"https://x.test/v1?API_KEY=$R&user=ann"
    }
    Locale.setDefault(previous)
    outcome.get
  }

  // ---------------------------------------------------------------------------------------------
  // The placeholder and the replacement text
  // ---------------------------------------------------------------------------------------------

  it should "use the placeholder it is given, whatever characters it holds" in {
    // A replacement string treats `$` and `\` specially: the placeholder and the matched text must not.
    val placeholder = "<$1\\redacted>"
    Redaction.redact("""{"password": "a$1b\\c"}""", placeholder) shouldBe s"""{"password": "$placeholder"}"""
    Redaction.redact("password=hunter2value", placeholder) shouldBe s"password=$placeholder"
    Redaction.redact("""{\"password\": \"hunter2value\"}""", placeholder) shouldBe
      s"""{\\"password\\": \\"$placeholder\\"}"""
  }

  it should "keep a value that contains $ or backslashes out of the output" in {
    val out = Redaction.redact("""{"token": "$2a$10$abcdef\\ghi"}""")
    (out should not).include("abcdef")
  }

  // ---------------------------------------------------------------------------------------------
  // Idempotence, and what is left alone
  // ---------------------------------------------------------------------------------------------

  it should "give the same result when applied twice" in {
    val inputs = Seq(
      """{"content": "{\"api_key\": \"a\", \"n\": 1}", "password": 12345, "x": "y"}""",
      "password=hunter2value&user=ann",
      "x-api-key: hunter2value\nok: 1",
      """{"password": "ab\"cd"}""",
      "{'secret': 's'}",
      """{"api_key": "[REDACTED]"}""",
      "plain text with no secret in it"
    )
    inputs.foreach { input =>
      withClue(s"input $input: ") {
        val once = Redaction.redact(input)
        Redaction.redact(once) shouldBe once
      }
    }
  }

  it should "leave an empty value and an already redacted value as they are" in {
    Redaction.redact("""{"password": ""}""") shouldBe """{"password": ""}"""
    Redaction.redact(s"""{"password": "$R"}""") shouldBe s"""{"password": "$R"}"""
    Redaction.redact(s"password=$R") shouldBe s"password=$R"
  }

  it should "leave text that has no credential in it unchanged" in {
    val input = """{"model": "gpt-4o", "messages": [{"role": "user", "content": "Hello"}], "max_tokens": 100}"""
    Redaction.redact(input) shouldBe input
  }

  it should "leave prose and URL paths that mention a credential word" in {
    val input = "Reset your password by clicking the link; the token expires soon. See https://x.test/secret/abc?foo=1"
    Redaction.redact(input) shouldBe input
  }

  // ---------------------------------------------------------------------------------------------
  // A whole provider exchange
  // ---------------------------------------------------------------------------------------------

  "Redaction.redactForLogging" should "redact a request body that quotes a credential in a prompt, and keep its numbers" in {
    val body =
      """{"model": "gpt-4o", "max_tokens": 256, "messages": [{"role": "user", "content": "my config is {\"password\": \"hunter2value\", \"region\": \"eu\"}"}]}"""
    val out = Redaction.redactForLogging(body, maxLength = 0)
    (out should not).include("hunter2value")
    out should include(""""max_tokens": 256""")
    out should include("""\"region\": \"eu\"""")
    ujson.read(out)("model").str shouldBe "gpt-4o"
  }

  // ---------------------------------------------------------------------------------------------
  // Large values: neither the stack nor the time may grow with the length of a value
  // ---------------------------------------------------------------------------------------------

  // java.util.regex recurses once per iteration of a repeated group, so a pattern like `(?:[^"\\]|\\.)*` overflows the
  // stack on a string value of a few hundred characters on a small stack. A payload of a megabyte (a prompt, a file's
  // contents) must redact on a thread with a deliberately small stack, so that a regression fails on any machine and
  // does not depend on the default stack size of the runner.
  private def onSmallStack(input: String): String = {
    @volatile var result: Option[String]     = None
    @volatile var failure: Option[Throwable] = None
    val thread =
      new Thread(null, () => result = Some(Redaction.redact(input)), "redaction-small-stack", SmallStackBytes)
    thread.setUncaughtExceptionHandler((_, e) => failure = Some(e))
    thread.start()
    thread.join(120000L)
    withClue("the redaction did not finish within two minutes: ")(thread.isAlive shouldBe false)
    withClue(s"redaction failed on a thread with a ${SmallStackBytes / 1024} KB stack: ")(failure shouldBe None)
    result.getOrElse(fail("redaction produced no result"))
  }

  "Redaction.redact" should "redact a megabyte-long JSON string value under a credential key" in {
    onSmallStack("{\"password\": \"" + ("a" * MegaChars) + "\", \"user\": \"ann\"}") shouldBe
      s"""{"password": "$R", "user": "ann"}"""
  }

  it should "redact a megabyte-long value that is all escape sequences" in {
    onSmallStack("{\"password\": \"" + ("\\n" * (MegaChars / 2)) + "\"}") shouldBe s"""{"password": "$R"}"""
  }

  it should "redact a megabyte-long single-quoted value" in {
    onSmallStack("{'password': '" + ("a" * MegaChars) + "'}") shouldBe s"{'password': '$R'}"
  }

  it should "redact a megabyte-long value of JSON that sits inside a string" in {
    onSmallStack("{\"c\": \"{\\\"password\\\": \\\"" + ("a" * MegaChars) + "\\\"}\"}") shouldBe
      s"""{"c": "{\\"password\\": \\"$R\\"}"}"""
  }

  it should "leave a megabyte-long string value under a key that is not a credential" in {
    val input = "{\"city\": \"" + ("a" * MegaChars) + "\"}"
    onSmallStack(input) shouldBe input
  }

  it should "redact a megabyte-long value that is never closed, to the end of the input" in {
    onSmallStack("{\"password\": \"" + ("a" * MegaChars)) shouldBe s"""{"password": "$R"""
  }

  it should "redact every one of many credential fields in a large document" in {
    val field = "\"password\": \"hunter2value\", "
    val out   = onSmallStack("{" + (field * 50000) + "\"end\": 1}")
    (out should not).include("hunter2value")
    out should startWith(s"""{"password": "$R", "password": "$R", """)
    out should endWith(""""end": 1}""")
  }

  it should "redact a megabyte-long key=value value" in {
    onSmallStack("password=" + ("a" * MegaChars)) shouldBe s"password=$R"
  }

  it should "redact a megabyte-long header-style value, and one behind a megabyte of spaces" in {
    onSmallStack("x-api-key: " + ("a" * MegaChars)) shouldBe s"x-api-key: $R"
    onSmallStack("x-api-key:" + (" " * MegaChars) + "v") shouldBe s"x-api-key:" + (" " * MegaChars) + R
  }

  it should "redact a megabyte-long number" in {
    onSmallStack("{\"password\": " + ("1" * MegaChars) + "}") shouldBe s"""{"password": "$R"}"""
  }

  it should "leave a megabyte of word characters, of spaces and of newlines unchanged" in {
    Seq("x" * MegaChars, " " * MegaChars, "a\n" * (MegaChars / 2), "api_key" * (MegaChars / 7)).foreach { input =>
      withClue(s"input starting ${input.take(8)}: ")(onSmallStack(input) shouldBe input)
    }
  }
  it should "redact an entire embedded string containing escaped quotes" in {
    val values = Seq("ab\"cd12345", "ab\\\"cd12345", "ends\\")
    values.foreach { value =>
      val embedded = ujson.Obj("password" -> value, "name" -> "keep").render()
      val input    = ujson.Obj("content" -> embedded).render()
      val output   = ujson.read(Redaction.redact(input))("content").str
      ujson.read(output)("password").str shouldBe R
      ujson.read(output)("name").str shouldBe "keep"
      Redaction.redact(Redaction.redact(input)) shouldBe Redaction.redact(input)
    }
  }

  it should "redact single and double quoted assignment values" in {
    Redaction.redact("PASSWORD=\"hunter2value\" NAME=\"keep\"") shouldBe s"PASSWORD=\"$R\" NAME=\"keep\""
    Redaction.redact("PASSWORD='hunter2value' NAME='keep'") shouldBe s"PASSWORD='$R' NAME='keep'"
  }

  it should "redact exponent-form numeric credentials" in {
    Seq("1e10", "-1.25E+10", "1e-10").foreach { number =>
      val output = Redaction.redact(s"""{"password":$number,"count":$number}""")
      ujson.read(output)("password").str shouldBe R
      ujson.read(output)("count").num shouldBe number.toDouble
    }
  }

}
