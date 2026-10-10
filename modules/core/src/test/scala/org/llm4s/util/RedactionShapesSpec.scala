package org.llm4s.util

import org.llm4s.testutil.LinearTime
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

  it should "redact the whole of an Authorization value that contains an escaped quote (#1672)" in {
    val out = Redaction.redact("""{"authorization": "6FPVKYYYKXQ\"]WGMW"}""")
    out shouldBe s"""{"authorization": "$R"}"""
    (out should not).include("WGMW")
  }

  it should "redact the whole of an Authorization value with several escaped quotes" in {
    val out = Redaction.redact("""{"Authorization": "Bearer ab\"cdQ\"WXYZ\"tail9", "user": "ann"}""")
    out shouldBe s"""{"Authorization": "$R", "user": "ann"}"""
    Redaction.redact("""{"Authorization": "Basic dXNlcjpwYXNz\"QWXYZ"}""") shouldBe s"""{"Authorization": "$R"}"""
    Redaction.redact("""{"AUTHORIZATION": "\"QWXYZ\""}""") shouldBe s"""{"AUTHORIZATION": "$R"}"""
  }

  it should "end an Authorization value at a quote that follows an escaped backslash" in {
    // `\\` is an escaped backslash, so the quote after it closes the value and the next field is kept.
    Redaction.redact("""{"Authorization": "abc\\", "user": "ann"}""") shouldBe
      s"""{"Authorization": "$R", "user": "ann"}"""
    Redaction.redact("""{"Authorization": "ab\\\"QWXYZ\\", "user": "ann"}""") shouldBe
      s"""{"Authorization": "$R", "user": "ann"}"""
  }

  it should "redact an Authorization value with an escaped quote that is cut off, to the end of the input" in {
    // The first line is a header line too: its redaction must not leave the rest of the value readable.
    val out = Redaction.redact("Authorization: \nx: \"Authorization\": \"Basic QWX\\\"Y\nZtail9 more")
    (out should not).include("Ztail9")
    Redaction.redact("""{"Authorization": "Bearer ab\"QWXYZ""") shouldBe s"""{"Authorization": "$R"""
  }

  it should "leave an empty Authorization value as it is" in {
    Redaction.redact("""{"Authorization": "", "user": "ann"}""") shouldBe """{"Authorization": "", "user": "ann"}"""
  }

  it should "redact an Authorization value with an escaped quote in JSON that sits inside a string" in {
    val input = """{"content": "{\"authorization\": \"QWX\\\"]YZtail9\", \"n\": 1}"}"""
    val out   = Redaction.redact(input)
    out shouldBe s"""{"content": "{\\"authorization\\": \\"$R\\", \\"n\\": 1}"}"""
    (out should not).include("YZtail9")
  }

  it should "redact the rest of an Authorization header line that holds an escaped quote" in {
    Redaction.redact("Authorization: Bearer ab\\\"QWXYZ\nnext: 1") shouldBe s"Authorization: $R\nnext: 1"
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

  it should "redact a number under a credential key in JSON that sits inside a string" in {
    val out = Redaction.redact("""{"content": "{\"password\": 12345678, \"max_tokens\": 100}"}""")
    out shouldBe s"""{"content": "{\\"password\\": \\"$R\\", \\"max_tokens\\": 100}"}"""
    ujson.read(ujson.read(out)("content").str)("password").str shouldBe R
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
  // An array or an object under a credential key: every string and number leaf under it is a secret
  // ---------------------------------------------------------------------------------------------

  it should "redact every string of an array under a credential key, and keep the JSON valid" in {
    val out = Redaction.redact("""{"token": ["abc123", "def456"], "user": "ann"}""")
    out shouldBe s"""{"token": ["$R", "$R"], "user": "ann"}"""
    ujson.read(out)("token").arr.map(_.str) shouldBe Seq(R, R)
  }

  it should "redact every leaf of an object under a credential key, and keep its keys" in {
    val out    = Redaction.redact("""{"credentials": {"user": "ann", "pass": "hunter2value", "port": 5432}, "n": 1}""")
    val parsed = ujson.read(out)
    parsed("credentials")("user").str shouldBe R
    parsed("credentials")("pass").str shouldBe R
    parsed("credentials")("port").str shouldBe R
    parsed("n").num shouldBe 1
    (out should not).include("hunter2value")
    (out should not).include("ann")
  }

  it should "redact the numbers of an array under a credential key, written back as strings" in {
    val out = Redaction.redact("""{"secret": [1, 2.5, -3e2, 7]}""")
    (out should not).include("2.5")
    ujson.read(out)("secret").arr.map(_.str) shouldBe Seq(R, R, R, R)
  }

  it should "redact the leaves of nested arrays and objects, and leave true, false and null" in {
    val out = Redaction.redact("""{"token": [{"value": "a1", "tags": ["x1", "y1"], "on": true}, "b1", null, false]}""")
    val parsed = ujson.read(out)("token")
    parsed(0)("value").str shouldBe R
    parsed(0)("tags").arr.map(_.str) shouldBe Seq(R, R)
    parsed(0)("on").bool shouldBe true
    parsed(1).str shouldBe R
    parsed(2) shouldBe ujson.Null
    parsed(3).bool shouldBe false
    Seq("a1", "x1", "y1", "b1").foreach(leaf => (out should not).include(leaf))
  }

  it should "redact a leaf that contains an escaped quote or a bracket" in {
    val out = Redaction.redact("""{"token": ["a\"]b12345", "c{d12345", "e\\"], "user": "ann"}""")
    out shouldBe s"""{"token": ["$R", "$R", "$R"], "user": "ann"}"""
  }

  it should "leave an empty array, an empty object and an already redacted array as they are" in {
    Seq("""{"token": []}""", """{"token": {}}""", s"""{"token": ["$R"], "n": 1}""").foreach { input =>
      withClue(s"input $input: ")(Redaction.redact(input) shouldBe input)
    }
  }

  it should "leave an array or an object under a key that is not a credential" in {
    val input =
      """{"max_tokens": [1, 2], "tokens": {"a": "b"}, "messages": [{"role": "user", "content": "hi there"}], "token_count": {"n": 3}}"""
    Redaction.redact(input) shouldBe input
  }

  it should "redact a credential array inside a container that is not a credential" in {
    Redaction.redact("""{"messages": [{"role": "user", "token": ["abc123"]}]}""") shouldBe
      s"""{"messages": [{"role": "user", "token": ["$R"]}]}"""
  }

  it should "redact an array under a credential key in JSON that sits inside a string" in {
    val input = """{"content": "{\"token\": [\"abc123\", \"def456\"], \"n\": 1}"}"""
    val out   = Redaction.redact(input)
    out shouldBe s"""{"content": "{\\"token\\": [\\"$R\\", \\"$R\\"], \\"n\\": 1}"}"""
    val embedded = ujson.read(ujson.read(out)("content").str)
    embedded("token").arr.map(_.str) shouldBe Seq(R, R)
    embedded("n").num shouldBe 1
  }

  it should "redact an object under a credential key in JSON that sits inside a string" in {
    val embedded = ujson.Obj("credentials" -> ujson.Obj("user" -> "ann", "pass" -> "p\"q"), "keep" -> "yes").render()
    val input    = ujson.Obj("content" -> embedded).render()
    val out      = ujson.read(ujson.read(Redaction.redact(input))("content").str)
    out("credentials")("user").str shouldBe R
    out("credentials")("pass").str shouldBe R
    out("keep").str shouldBe "yes"
  }

  it should "redact an array that is cut off before its closing bracket, as a truncated payload is" in {
    Redaction.redact("""{"token": ["abc123", "de""") shouldBe s"""{"token": ["$R", "$R"""
    Redaction.redact("""{"token": ["abc123", """) shouldBe s"""{"token": ["$R", """
    Redaction.redact("""{"content": "{\"token\": [\"abc123\", \"de""") shouldBe
      s"""{"content": "{\\"token\\": [\\"$R\\", \\"$R"""
  }

  it should "end an array inside a string at the quote that ends the enclosing string" in {
    Redaction.redact("""{"content": "{\"token\": [\"abc123\", \"de", "n": 1}""") shouldBe
      s"""{"content": "{\\"token\\": [\\"$R\\", \\"$R", "n": 1}"""
  }

  // ---------------------------------------------------------------------------------------------
  // A single-quoted key with an array or an object value: a Python dict or a JavaScript literal in a prompt
  // ---------------------------------------------------------------------------------------------

  it should "redact every string of an array under a single-quoted credential key" in {
    Redaction.redact("{'token': ['abc123', 'def456'], 'user': 'ann'}") shouldBe
      s"{'token': ['$R', '$R'], 'user': 'ann'}"
  }

  it should "redact every leaf of an object under a single-quoted credential key, and keep its keys" in {
    val out = Redaction.redact("{'credentials': {'user': 'ann', 'pass': 'hunter2value', 'port': 5432}, 'n': 1}")
    out shouldBe s"{'credentials': {'user': '$R', 'pass': '$R', 'port': '$R'}, 'n': 1}"
  }

  it should "redact the leaves of a nested single-quoted container, and leave true, false and null" in {
    Redaction.redact("{'token': [{'value': 'a1', 'tags': ['x1', 'y1'], 'on': true}, 'b1', null, false]}") shouldBe
      s"{'token': [{'value': '$R', 'tags': ['$R', '$R'], 'on': true}, '$R', null, false]}"
  }

  it should "redact a single-quoted leaf that contains an escaped quote or a bracket" in {
    Redaction.redact("""{'token': ['a\']b12345', 'c{d12345', "e\"]f12345"], 'user': 'ann'}""") shouldBe
      s"""{'token': ['$R', '$R', "$R"], 'user': 'ann'}"""
  }

  it should "redact a container whose quotes are mixed, in either direction" in {
    // A double-quoted key with single-quoted leaves was mangled, not redacted: `abc` stayed and `123` was taken
    // for a number, giving `['abc"[REDACTED]"']`.
    Redaction.redact("""{"token": ['abc123', 'def456']}""") shouldBe s"""{"token": ['$R', '$R']}"""
    Redaction.redact("""{'token': ["abc123", "def456"]}""") shouldBe s"""{'token': ["$R", "$R"]}"""
    Redaction.redact("""{'credentials': {"user": 'ann', 'pass': "hunter2value"}}""") shouldBe
      s"""{'credentials': {"user": '$R', 'pass': "$R"}}"""
  }

  it should "redact a single-quoted array that is cut off before its closing bracket" in {
    Redaction.redact("{'token': ['abc123', 'de") shouldBe s"{'token': ['$R', '$R"
    Redaction.redact("{'token': ['abc123', ") shouldBe s"{'token': ['$R', "
  }

  it should "leave an array or an object under a single-quoted key that is not a credential" in {
    val input = "{'max_tokens': [1, 2], 'tokens': {'a': 'b'}, 'messages': [{'role': 'user', 'content': 'hi there'}]}"
    Redaction.redact(input) shouldBe input
  }

  it should "redact a single-quoted credential array inside a container that is not a credential" in {
    Redaction.redact("{'messages': [{'role': 'user', 'token': ['abc123']}]}") shouldBe
      s"{'messages': [{'role': 'user', 'token': ['$R']}]}"
  }

  it should "leave an empty single-quoted container and an already redacted one as they are" in {
    Seq("{'token': []}", "{'token': {}}", s"{'token': ['$R'], 'n': 1}").foreach { input =>
      withClue(s"input $input: ")(Redaction.redact(input) shouldBe input)
    }
  }

  it should "give the same result when a single-quoted container is redacted twice" in {
    Seq(
      "{'token': ['abc', 12, {'a': 'b'}], 'credentials': {'user': 'u', 'pass': 'p'}}",
      """{"token": ['abc123']}""",
      """{'token': ["abc123"]}"""
    ).foreach { input =>
      withClue(s"input $input: ") {
        val once = Redaction.redact(input)
        Redaction.redact(once) shouldBe once
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Python reprs: a single-quoted leaf with a `:` or `=`, a number under a single-quoted key (#1675), and a
  // double-quoted value under a single-quoted key, which repr writes for a value holding a `'` (#1687)
  // ---------------------------------------------------------------------------------------------

  /** Redacts `input`, checks that redacting the result again changes nothing, and returns the result. */
  private def redactedIdempotent(input: String): String = {
    val once = Redaction.redact(input)
    withClue(s"redacting the output for $input a second time: ")(Redaction.redact(once) shouldBe once)
    once
  }

  it should "redact a single-quoted leaf with a ':' or '=' under a single-quoted credential key (#1675)" in {
    // Was `{'credentials': {'pass': 'SECRETX:'[REDACTED]''[REDACTED]`: the leaf was taken for a field, `SECRETX`
    // for its key, and the quotes after it were paired the wrong way round.
    redactedIdempotent("{'credentials': {'pass': 'SECRETX:SECRETY'}}") shouldBe s"{'credentials': {'pass': '$R'}}"
    redactedIdempotent("{'token': ['postgres://u:SECRETPW@h/db']}") shouldBe s"{'token': ['$R']}"
    redactedIdempotent("{'token': ['a=b', 'c:d'], 'user': 'ann'}") shouldBe s"{'token': ['$R', '$R'], 'user': 'ann'}"
  }

  it should "redact a single-quoted leaf with a ':' inside a JSON string, and keep the JSON valid (#1675)" in {
    // Was `{'pass': 'SECRETX:SECRETY'[REDACTED]"}`: the whole secret readable.
    val out = redactedIdempotent("""{"c": "{'credentials': {'pass': 'SECRETX:SECRETY'}}"}""")
    out shouldBe s"""{"c": "{'credentials': {'pass': '$R'}}"}"""
    ujson.read(out)("c").str shouldBe s"{'credentials': {'pass': '$R'}}"
  }

  it should "redact a number under a single-quoted credential key, in the key's quote (#1675)" in {
    redactedIdempotent("{'password': 123456}") shouldBe s"{'password': '$R'}"
    redactedIdempotent("{'pin': 0, 'password': -12.5e3, 'n': 1}") shouldBe s"{'pin': 0, 'password': '$R', 'n': 1}"
    val out = redactedIdempotent("""{"p": "{'password': 123456}"}""")
    out shouldBe s"""{"p": "{'password': '$R'}"}"""
    ujson.read(out)("p").str shouldBe s"{'password': '$R'}"
    // A number under a key that is not a credential is kept.
    redactedIdempotent("{'max_tokens': 1024, 'prompt_tokens': 7}") shouldBe "{'max_tokens': 1024, 'prompt_tokens': 7}"
  }

  it should "redact a double-quoted value under a single-quoted credential key in full (#1687)" in {
    // Was `{'Authorization': "[REDACTED]'y"}` (only the Bearer token caught) and `{'password': "it's-secret"}`.
    redactedIdempotent("""{'Authorization': "Bearer x'y"}""") shouldBe s"""{'Authorization': "$R"}"""
    redactedIdempotent("""{'password': "it's-secret"}""") shouldBe s"""{'password': "$R"}"""
    redactedIdempotent("""{'password': "it's \"quoted\" too", 'user': 'bob'}""") shouldBe
      s"""{'password': "$R", 'user': 'bob'}"""
    redactedIdempotent("""{'credentials': {'pass': "it's", 'u': 'x'}}""") shouldBe
      s"""{'credentials': {'pass': "$R", 'u': '$R'}}"""
  }

  it should "redact a double-quoted value under a single-quoted credential key inside a JSON string (#1687)" in {
    val field = redactedIdempotent("""{"c": "{'password': \"it's-secret\", 'n': 1}"}""")
    field shouldBe s"""{"c": "{'password': \\"$R\\", 'n': 1}"}"""
    ujson.read(field)("c").str shouldBe s"""{'password': "$R", 'n': 1}"""
    // And as a leaf of a container, after a leaf with a `:` that used to pair the quotes the wrong way round.
    val leaf = redactedIdempotent("""{"c": "{'token': ['a:b', \"it's\"], 'n': 1}", "model": "gpt-4o"}""")
    leaf shouldBe s"""{"c": "{'token': ['$R', \\"$R\\"], 'n': 1}", "model": "gpt-4o"}"""
    ujson.read(leaf)("model").str shouldBe "gpt-4o"
  }

  it should "leave a double-quoted text after a single-quoted credential key that is not a value of a dict" in {
    // The `"` after the key opens no value unless the value it closes is followed by `,` and a key, or by `}`, or is
    // cut off: prose that mentions the key, and a key that follows it, are not taken for one.
    Seq(
      """say 'password': "abc" to log in""",
      """{"content": "set 'password': \"abc\" here", "n": 1}""",
      """{"content": "the 'password': ", "user": "ann"}"""
    ).foreach(input => withClue(s"input $input: ")(redactedIdempotent(input) shouldBe input))
  }

  it should "leave the double-quoted equivalents as they were" in {
    redactedIdempotent("""{"credentials": {"pass": "x:y"}}""") shouldBe s"""{"credentials": {"pass": "$R"}}"""
    redactedIdempotent("""{"token": ["a=b"]}""") shouldBe s"""{"token": ["$R"]}"""
    redactedIdempotent("""{"password": 123456}""") shouldBe s"""{"password": "$R"}"""
    redactedIdempotent("""{"p": "{\"password\": 123456}"}""") shouldBe s"""{"p": "{\\"password\\": \\"$R\\"}"}"""
    redactedIdempotent("""{"password": "it's-secret"}""") shouldBe s"""{"password": "$R"}"""
  }

  it should "keep prose whose apostrophe a mentioned 'token': [ runs into, though it holds a ':' or '='" in {
    // A single-quoted string with a `:` or `=` is a leaf only where a value stands: after `[`, `,` or `:`. An
    // apostrophe of prose has a letter before it.
    redactedIdempotent("""{"content": "see 'token': [ for details, it's password='hunter2' ok"}""") shouldBe
      s"""{"content": "see 'token': [ for details, it's password='$R' ok"}"""
    redactedIdempotent("""{"content": "see 'token': [ so it's a: b, isn't it", "n": 1}""") shouldBe
      """{"content": "see 'token': [ so it's a: b, isn't it", "n": 1}"""
  }

  // ---------------------------------------------------------------------------------------------
  // A single-quoted key inside a double-quoted string. `'token': [` can sit inside a JSON string value, where `'` is
  // not escaped, so the walk must end where that string does: taking its closing `"` for a leaf opener desynchronised
  // the quotes and wrote a credential of a later field, which the string pass then could not match, out mangled but
  // readable (`"hunter'[REDACTED]'value"`). The four shapes the review found, then their neighbours.
  // ---------------------------------------------------------------------------------------------

  it should "not swallow the field after a JSON string that merely mentions 'token': [" in {
    val out = Redaction.redact(s"""{"content": "see 'token': [ for details", "api_key": "$secretText"}""")
    out shouldBe s"""{"content": "see 'token': [ for details", "api_key": "$R"}"""
    ujson.read(out)("api_key").str shouldBe R
  }

  it should "not swallow the fields after a JSON string that merely mentions 'credentials': {" in {
    val out = Redaction.redact(s"""{"content": "add 'credentials': { to it", "api_key": "$secretText", "x": 1}""")
    out shouldBe s"""{"content": "add 'credentials': { to it", "api_key": "$R", "x": 1}"""
    ujson.read(out)("x").num shouldBe 1
  }

  it should "end a single-quoted array cut off inside a JSON string at that string's end, and redact the field after" in {
    val out = Redaction.redact(s"""{"content": "{'token': ['abc', ", "password": "$secretText"}""")
    out shouldBe s"""{"content": "{'token': ['$R', ", "password": "$R"}"""
    ujson.read(out)("password").str shouldBe R
  }

  it should "leave the fields after a single-quoted key whose leaves are escaped double-quoted strings" in {
    // An escaped double-quoted leaf that its own `\"` closes, where a value stands, is redacted (#1687): Python's
    // repr writes a value holding a `'` in double quotes. `model` and `n` keep their values.
    val input = """{"content": "{'token': [\"abc\"]}", "model": "gpt-4o", "n": 1}"""
    val out   = Redaction.redact(input)
    out shouldBe s"""{"content": "{'token': [\\"$R\\"]}", "model": "gpt-4o", "n": 1}"""
    ujson.read(out)("model").str shouldBe "gpt-4o"
    // Any other `"` is foreign to the container's syntax and ends the walk: the end of a string escaped inside the
    // string that mentions the key, and a quote that does not close a leaf where a value stands.
    Seq(
      """{"content": "x \"see 'token': [\", 'user': 'ann'", "n": 1}""",
      """{"content": "{'token': [\"abc\" and more", "n": 1}"""
    ).foreach(other => withClue(s"input $other: ")(Redaction.redact(other) shouldBe other))
  }

  it should "redact a closed single-quoted dict inside a JSON string, and the field after it" in {
    val out = Redaction.redact(s"""{"content": "{'token': ['abc123']}", "api_key": "$secretText"}""")
    out shouldBe s"""{"content": "{'token': ['$R']}", "api_key": "$R"}"""
    ujson.read(out)("content").str shouldBe s"{'token': ['$R']}"
  }

  it should "redact a single-quoted dict inside escaped JSON inside a JSON string, and the escaped field after it" in {
    val input =
      s"""{"content": "{\\"messages\\": [{\\"content\\": \\"{'token': ['abc123']}\\"}], \\"api_key\\": \\"$secretText\\"}"}"""
    val out = Redaction.redact(input)
    out shouldBe
      s"""{"content": "{\\"messages\\": [{\\"content\\": \\"{'token': ['$R']}\\"}], \\"api_key\\": \\"$R\\"}"}"""
    ujson.read(ujson.read(out)("content").str)("api_key").str shouldBe R
  }

  it should "redact a single-quoted leaf under an escaped key inside a JSON string" in {
    // Was mangled, not redacted: `['abc\"[REDACTED]\"']`, the digits taken for a number.
    Redaction.redact("""{"content": "{\"token\": ['abc123', 'def456']}"}""") shouldBe
      s"""{"content": "{\\"token\\": ['$R', '$R']}"}"""
  }

  it should "redact a single-quoted leaf inside a JSON string that contains an escaped double quote" in {
    Redaction.redact("""{"content": "{'token': ['a\"b123']}", "n": 1}""") shouldBe
      s"""{"content": "{'token': ['$R']}", "n": 1}"""
  }

  it should "not take an apostrophe for a quote" in {
    // An apostrophe inside a JSON string is an ordinary character of that string.
    val prose = Redaction.redact(s"""{"content": "it's a 'token': [x]", "api_key": "$secretText"}""")
    prose shouldBe s"""{"content": "it's a 'token': [x]", "api_key": "$R"}"""
    // An apostrophe in prose before the document does not open a string that the document would then sit in.
    Redaction.redact("User's config: {\"token\": ['abc123']}") shouldBe s"User's config: {\"token\": ['$R']}"
    // An apostrophe inside a single-quoted container inside a JSON string opens a leaf that the string's end closes:
    // the field after it is redacted, the document parses, and nothing is readable.
    val bare = Redaction.redact(s"""{"content": "{'token': [it's", "api_key": "$secretText"}""")
    bare shouldBe s"""{"content": "{'token': [it'$R", "api_key": "$R"}"""
    (bare should not).include(secretText)
    ujson.read(bare)("api_key").str shouldBe R
  }

  it should "not take the end of a single-quoted string for a leaf when it mentions \"token\": [" in {
    // The mirror image: a double-quoted key inside a single-quoted string. Only `"` opens a leaf there, as before
    // #1647 - an apostrophe in prose makes a `'` too uncertain to end the walk on - so the walk runs to the closing
    // brace, and every bare word it passes is replaced, bar a key before `:`: the credential after the string is
    // unreadable, where the head of round 1 wrote it out as `hunter"[REDACTED]"value`. The key `api_key` is kept, so
    // that the single-quoted field pass after the walk still finds it and redacts its value.
    val out = Redaction.redact(s"{'content': 'see \"token\": [ for details', 'api_key': '$secretText'}")
    out shouldBe s"""{'content': 'see "token": [ "$R" "$R"', 'api_key': '$R'}"""
    (out should not).include("hunter")
    (out should not).include("value")
  }

  it should "replace a bare value under a credential key, and keep literals and an unquoted key" in {
    Redaction.redact("""{"token": [abc123, -1, 2.5e3, true, null], "n": 1}""") shouldBe
      s"""{"token": ["$R", "$R", "$R", true, null], "n": 1}"""
    Redaction.redact("{'token': [None, True, False, abc]}") shouldBe s"{'token': [None, True, False, '$R']}"
    Redaction.redact(s"""{"credentials": {user: ann, pass: $secretText}}""") shouldBe
      s"""{"credentials": {user: "$R", pass: "$R"}}"""
    // Inside a double-quoted string the walk cannot pass the string's end, so a bare word there is kept as prose.
    Redaction.redact(
      """{"content": "{\"token\": [abc, 12]}"}"""
    ) shouldBe s"""{"content": "{\\"token\\": [abc, \\"$R\\"]}"}"""
  }

  it should "leave nothing readable when a stray quote before the document misleads the choice of walk" in {
    // An unpaired `"` before the document inverts the pairing of the quotes, so `'token': [` is taken for a
    // top-level container and the walk runs on into the fields after the string. Whatever it passes is replaced,
    // so the credential is gone, though the document is not kept in shape.
    val out = Redaction.redact(s"""body="{"content": "see 'token': [ for details", "api_key": "$secretText"}"""")
    (out should not).include("hunter")
    (out should not).include("value")
    (out should not).include("2")
  }

  // ---------------------------------------------------------------------------------------------
  // A walk never hides a key from the passes after it. A container that a string merely mentions, or that is cut
  // off, runs on into prose, where an apostrophe (it's, O'Brien, users', don't) was taken for the opening quote of a
  // leaf: the leaf swallowed the next `password='...'` up to its `='`, and the credential after it was left as a bare
  // word the walk copied as prose, out of reach of the `key='value'` pass. Each was redacted before #1647.
  // ---------------------------------------------------------------------------------------------

  it should "not let an apostrophe in prose after a mentioned 'token': [ hide the next credential" in {
    Seq(
      """{"content": "see 'token': [ for details, it's password='hunter2' ok"}""" ->
        s"""{"content": "see 'token': [ for details, it's password='$R' ok"}""",
      """{"content": "see 'token': [ for details; O'Brien set api_key='hunter2'"}""" ->
        s"""{"content": "see 'token': [ for details; O'Brien set api_key='$R'"}""",
      """{"content": "see 'token': [ for details; the users' password='hunter2'"}""" ->
        s"""{"content": "see 'token': [ for details; the users' password='$R'"}"""
    ).foreach { case (input, expected) =>
      withClue(s"input $input: ") {
        val out = Redaction.redact(input)
        (out should not).include("hunter2")
        out shouldBe expected
        ujson.read(out)("content").str should include("for details")
      }
    }
  }

  it should "not let an apostrophe in prose after a mentioned \\\"token\\\": [ hide the next credential" in {
    val out = Redaction.redact("""{"content": "bad \"token\": [ field; don't use 'api_key': 'hunter2' here"}""")
    (out should not).include("hunter2")
    out shouldBe s"""{"content": "bad \\"token\\": [ field; don't use 'api_key': '$R' here"}"""
  }

  it should "not let an apostrophe after a cut-off embedded document hide the next credential" in {
    val out = Redaction.redact("""{"content": "{\"token\": [\"a\", it's password='hunter2'"}""")
    (out should not).include("hunter2")
    out shouldBe s"""{"content": "{\\"token\\": [\\"$R\\", it's password='$R'"}"""
  }

  it should "not hide a key from the passes after the walk, whatever the walk takes for a leaf or a word" in {
    // Shapes on which a differential fuzzer, run against the redaction before #1647, caught drafts of this change
    // leaking: the walk replaced a key, or wrote a quote into the middle of a value, and the end of the value - past
    // the walk - was left readable. The walk that guesses at leaves now runs after every field pass. The last two
    // are the review's own low-realism shapes: a quote inside a leaf that ends the enclosing string.
    Seq(
      "{'token': [password='a]hunter2'}",
      "{'token': [password=:a]hunter2",
      "{'token': ['apikey': 'a{hunter2",
      "{\"token\": [password=:a]hunter2",
      "{\"token\": {\"\"apikey\":\":a'=hunter2",
      "{'token': [\"\"password\":\"}hunter2",
      "{\"token\": {'\"'hunter2:",
      "{'token': {''apikey':':a\"=hunter2",
      """{"content": "'token': ['token': '"hunter2"}""",
      """{"content": "\"token\": ['\"'hunter2'"}"""
    ).foreach(input => withClue(s"input $input: ")((Redaction.redact(input) should not).include("hunter2")))
  }

  it should "give the same result when a single-quoted container inside a string is redacted twice" in {
    Seq(
      s"""{"content": "see 'token': [ for details", "api_key": "$secretText"}""",
      s"""{"content": "{'token': ['abc', ", "password": "$secretText"}""",
      """{"content": "{'token': [\"abc\"]}", "model": "gpt-4o", "n": 1}""",
      s"""{"content": "{'token': ['abc123']}", "api_key": "$secretText"}""",
      """{"content": "{\"token\": ['abc123']}"}"""
    ).foreach { input =>
      withClue(s"input $input: ") {
        val once = Redaction.redact(input)
        Redaction.redact(once) shouldBe once
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // A credential key that a message merely mentions leaves the text after it readable (#1654, #1657)
  // ---------------------------------------------------------------------------------------------

  /** Redacts `input`, checks that redacting the result again changes nothing, and returns the result. */
  private def redactedOnceAndTwice(input: String): String = {
    val once = Redaction.redact(input)
    withClue(s"redacting the output for $input a second time: ")(Redaction.redact(once) shouldBe once)
    once
  }

  it should "end an unclosed single-quoted value inside a JSON string where that string ends (#1654)" in {
    // Was `{"content": "use 'password': '[REDACTED]`: with no closing `'`, the value ran to the end of the input,
    // and the fields after the string, and the closing brace, were gone.
    val out =
      redactedOnceAndTwice("""{"content": "use 'password': ' carefully", "model": "gpt-4o", "temperature": 0.2}""")
    out shouldBe s"""{"content": "use 'password': '$R", "model": "gpt-4o", "temperature": 0.2}"""
    ujson.read(out)("model").str shouldBe "gpt-4o"
    ujson.read(out)("temperature").num shouldBe 0.2
    redactedOnceAndTwice("""{"content": "set password=' carefully", "model": "gpt-4o"}""") shouldBe
      s"""{"content": "set password='$R", "model": "gpt-4o"}"""
    // The end of the string is found before a `'` later in the document, which used to end the value.
    redactedOnceAndTwice(
      """{"content": "use 'password': ' carefully", "model": "gpt-4o", "note": "it's ok"}"""
    ) shouldBe
      s"""{"content": "use 'password': '$R", "model": "gpt-4o", "note": "it's ok"}"""
  }

  it should "still redact a single-quoted value inside a JSON string up to its own quote or a quote that ends the string" in {
    redactedOnceAndTwice(s"""{"content": "use 'password': '$secretText' now", "n": 1}""") shouldBe
      s"""{"content": "use 'password': '$R' now", "n": 1}"""
    // A `"` that is not followed by what follows the end of a JSON string - a `,` before the next key, a closing
    // bracket, the end of the input - is taken for part of the value, so a stray quote in it ends nothing.
    redactedOnceAndTwice(s"""{"content": "x 'password': 'ab"$secretText more"}""") shouldBe
      s"""{"content": "x 'password': '$R"}"""
    redactedOnceAndTwice(s"""{"content": "x 'password': 'ab", $secretText"}""") shouldBe
      s"""{"content": "x 'password': '$R"}"""
    // Outside a string, the value still runs to its closing quote, or to the end of a payload cut off inside it.
    redactedOnceAndTwice(s"""{'password': 'ab", "$secretText'}""") shouldBe s"{'password': '$R'}"
    redactedOnceAndTwice(s"{'password': '$secretText") shouldBe s"{'password': '$R"
  }

  it should "redact a single-quoted credential holding a quote and a bracket as a whole, as main does (#1654)" in {
    // A `"` followed by `]]`, `]}`, `}}` or `, "key":` reads as the end of a JSON string. Inside a string - a logfmt
    // `msg="..."`, an unescaped embedding, or after a Python `b'x"y'` - the value used to end there, and the rest of
    // the credential was left readable. A `'` that can close the value follows, so it runs on to it.
    redactedOnceAndTwice("""level=info msg="request: {'password': 'Qx"]]9secretPW', 'user': 'bob'}"""") shouldBe
      s"""level=info msg="request: {'password': '$R', 'user': 'bob'}""""
    redactedOnceAndTwice("""level=info msg="request: {'password': 'Qx", "user": 9secretPW', 'user': 'bob'}"""") shouldBe
      s"""level=info msg="request: {'password': '$R', 'user': 'bob'}""""
    redactedOnceAndTwice("""{'name': b'x"y', 'bearer_token': '5WBF9"]]<XWU4HMJW9BMWX'}""") shouldBe
      s"""{'name': b'x"y', 'bearer_token': '$R'}"""
    redactedOnceAndTwice("""{"content": "{'apiKey': 'YWYF"]]S2V'}"}""") shouldBe s"""{"content": "{'apiKey': '$R'}"}"""
    // A Bearer or Basic token is replaced first, by the header patterns; the rest of the value is still redacted,
    // outside a string and inside one.
    redactedOnceAndTwice("""{'token': 'Bearer abc"]}hunter2secret'}""") shouldBe s"{'token': '$R'}"
    redactedOnceAndTwice("""{'password': 'Basic dXNlcg=="}}hunter2secret'}""") shouldBe s"{'password': '$R'}"
    redactedOnceAndTwice("""{"content": "{'token': 'Bearer abc"]}hunter2secret'}"}""") shouldBe
      s"""{"content": "{'token': '$R'}"}"""
    // The query parameter `&token=b'` ends at the quote that closes the credential, and the query pass keeps that
    // quote (#1667), so the credential is redacted up to it and the text after it is kept.
    redactedOnceAndTwice("""level=info msg="{'password': 'Qx"]]9secret&token=b'}"""") shouldBe
      s"""level=info msg="{'password': '$R'}""""
    // With a `'` later in the document, an unclosed value runs on to it, as on main: over-redaction, never a leak.
    redactedOnceAndTwice(
      """{"content": "use 'password': ' carefully", "model": "gpt-4o", "note": "say 'hi'"}"""
    ) shouldBe
      s"""{"content": "use 'password': '$R'hi'"}"""
  }

  it should "show the part of a truncated single-quoted credential after a quote that reads as a string's end (known trade-off, #1654)" in {
    // Known trade-off, pinned so that a change to it is deliberate. Inside a raw (unescaped) `"`-quoted string, an
    // unclosed single-quoted value holding a `"` followed by what follows the end of a string (here `]]`) ends at that
    // `"` when no `'` that could close it follows - which is what an input cut off inside the credential looks like.
    // The part after the `"` is shown; main hid it by running the value to the end of the input. Every llm4s call site
    // redacts the full text before truncating it; callers must do the same.
    redactedOnceAndTwice("""level=info msg="request: {'password': 'Qx"]]9secretPW""") shouldBe
      s"""level=info msg="request: {'password': '$R"]]9secretPW"""
    redactedOnceAndTwice("""msg="{'password': 'Qx"]]9secretPW""") shouldBe s"""msg="{'password': '$R"]]9secretPW"""
    // Redacted before it is cut, as every call site does, the same credential is hidden whole.
    val full = """level=info msg="request: {'password': 'Qx"]]9secretPW', 'user': 'bob'}""""
    (Redaction.redactForLogging(full, maxLength = 45) should not).include("secretPW")
    Redaction.redactForLogging(full, maxLength = 45) should startWith(
      """level=info msg="request: {'password': '[REDA"""
    )
    // Outside any string the value still runs to the end of the input.
    redactedOnceAndTwice("""{'password': 'Qx"]]9secretPW""") shouldBe s"{'password': '$R"
  }

  it should "read a closing quote between two letters or digits as an apostrophe (known trade-off, #1654)" in {
    // Known trade-off, pinned so that a change to it is deliberate. A `'` with a letter or digit on both sides is
    // read as the apostrophe of a word (`it's`), not as a quote that could close the value, so, with no other `'`
    // after it, the value ends at the `"` that reads as the string's end and the rest is shown.
    redactedOnceAndTwice("""level=info msg="request: {'password': 'Qx"]]9SECRETPW'it"""") shouldBe
      s"""level=info msg="request: {'password': '$R"]]9SECRETPW'it""""
    redactedOnceAndTwice("""msg="{'password': 'Qx"]]9SECRETPW'it"""") shouldBe
      s"""msg="{'password': '$R"]]9SECRETPW'it""""
  }

  it should "keep prose after a 'token': [ that a JSON string mentions (#1657)" in {
    // Was `... Thanks, it'[REDACTED]"}`: the `'` of `it's` opened a leaf that the string's end closed, and a second
    // pass redacted from the `'` of `isn't` as well.
    val input = """{"content":"The 'token': [ field isn't documented. Thanks, it's urgent!"}"""
    redactedOnceAndTwice(input) shouldBe input
    val logLine =
      """INFO Request body: {"model":"gpt-4o-mini","messages":[{"role":"user","content":"Summarise: The 'token': [ field in Bob's YAML isn't documented; it's a list of strings. Thanks, it's urgent!"}],"stream":true}"""
    redactedOnceAndTwice(logLine) shouldBe logLine
    // Only the apostrophe of a word of prose - between two letters, in a word that follows whitespace - is kept so.
    // A quote after a bracket, a space, a word that starts the container, or a Python string prefix still opens a
    // leaf, closed or cut off.
    redactedOnceAndTwice(s"""{"content": "{'token': ['$secretText"}""") shouldBe s"""{"content": "{'token': ['$R"}"""
    redactedOnceAndTwice(s"""{"content": "{'token': [ '$secretText"}""") shouldBe s"""{"content": "{'token': [ '$R"}"""
    redactedOnceAndTwice(s"""{"content": "{'token': [O'$secretText"}""") shouldBe s"""{"content": "{'token': [O'$R"}"""
    redactedOnceAndTwice(s"""{"content": "{'token': [x, b'$secretText"}""") shouldBe
      s"""{"content": "{'token': [x, b'$R"}"""
    redactedOnceAndTwice(s"""{"content": "{'token': [x, rb'$secretText"}""") shouldBe
      s"""{"content": "{'token': [x, rb'$R"}"""
    // Nor where JSON escaped in the string follows the apostrophe: the walk would pair its `\"` from the wrong one and
    // keep the credential as a word of prose, so the leaf runs to the end of the string, as on main.
    redactedOnceAndTwice(
      """{"content": "see \"token\": [ here, it's \" {\"secret_key\": \"hunterSecretValue\"}"}"""
    ) shouldBe
      s"""{"content": "see \\"token\\": [ here, it'$R"}"""
  }

  it should "keep the escaped character when it replaces a bare word after a backslash (#1657)" in {
    // Was `{"token": [\"[REDACTED]"[REDACTED]"...`: the quote written after the backslash read as `\"`, the quotes
    // after it paired the other way round, and the rest of the document, the `password` key with it, was lost.
    redactedOnceAndTwice("""{"token": [\a1, "x"], "password": "QZXJVK"}""") shouldBe
      s"""{"token": [\\a"$R", "$R"], "password": "$R"}"""
    redactedOnceAndTwice("""{"token": [\1abc, "x"], "password": "QZXJVK"}""") shouldBe
      s"""{"token": [\\1"$R", "$R"], "password": "$R"}"""
  }

  it should "still replace the bare words after a 'token': [ that text outside any string mentions" in {
    // Left by decision (#1657): outside a string, the words after an unclosed `'token': [` read as the plain leaves
    // of a YAML flow sequence or a cut-off dict, and nothing tells a word of prose from such a leaf; replacing them
    // is what keeps a credential unreadable when a stray quote misleads the walk. The result is stable.
    redactedOnceAndTwice("note: see 'token': [ for details. Content-Type: application/json, model gpt-4o-mini") shouldBe
      s"note: see 'token': [ '$R' '$R' Content-Type: '$R', '$R' '$R'"
    redactedOnceAndTwice(s"'token': [$secretText, abc]") shouldBe s"'token': ['$R', '$R']"
  }

  // ---------------------------------------------------------------------------------------------
  // A double-quoted field that a single-quoted string mentions ends with that string (#1697)
  // ---------------------------------------------------------------------------------------------

  it should "end a \"password\": \" that a single-quoted string mentions where that string ends (#1697)" in {
    // Was `... "password": "[REDACTED]"7YJ1VSFPX'}", -9935758]}`: the mention's value ran to the next `"`, over the
    // key of the next field, and the credential under it was left readable.
    val input = """{'note': 'see "password": " here', 'apiKey': ["7YJ1VSFPX'}", -9935758]}"""
    val out   = redactedOnceAndTwice(input)
    out shouldBe s"""{'note': 'see "password": "$R', 'apiKey': ["$R", '$R']}"""
    (out should not).include("7YJ1VSFPX")
    (out should not).include("9935758")
  }

  it should "keep the next field's key after a mention wherever the mention and the field sit (#1697)" in {
    Seq(
      // the next field's value single-quoted, double-quoted, a list, a nested dict
      """{'note': 'see "password": " here', 'apiKey': 'SEKone'}""" ->
        s"""{'note': 'see "password": "$R', 'apiKey': '$R'}""",
      """{'note': 'see "password": " here', 'apiKey': "SEKone'"}""" ->
        s"""{'note': 'see "password": "$R', 'apiKey': "$R"}""",
      """{'note': 'see "password": " here', 'apiKey': ["SEKone'}"]}""" ->
        s"""{'note': 'see "password": "$R', 'apiKey': ["$R"]}""",
      """{'note': 'see "password": " here', 'creds': {'token': 'SEKone'}}""" ->
        s"""{'note': 'see "password": "$R', 'creds': {'token': '$R'}}""",
      // the mention at the start, at the end, alone in its string, after a field
      """{'note': '"password": " see', 'token': 'SEKone'}""" -> s"""{'note': '"password": "$R', 'token': '$R'}""",
      """{'note': 'see "password": "', 'token': 'SEKone'}""" -> s"""{'note': 'see "password": "', 'token': '$R'}""",
      """{'apiKey': 'SEKone', 'note': 'see "password": " here', 'token': 'SEKtwo'}""" ->
        s"""{'apiKey': '$R', 'note': 'see "password": "$R', 'token': '$R'}""",
      // the mention in the last field, and a cut-off dict
      """{'token': 'SEKone', 'note': 'see "password": " here'}""" -> s"""{'token': '$R', 'note': 'see "password": "$R'}""",
      """{'note': 'see "password": " here'""" -> s"""{'note': 'see "password": "$R'"""
    ).foreach { case (input, expected) =>
      withClue(s"input $input: ")(redactedOnceAndTwice(input) shouldBe expected)
    }
  }

  it should "keep the next field's key after a mention in nested dicts and lists (#1697)" in {
    redactedOnceAndTwice("""{'a': {'note': '"token": " x'}, 'b': [{'secret': "SEKone'"}]}""") shouldBe
      s"""{'a': {'note': '"token": "$R'}, 'b': [{'secret': "$R"}]}"""
    redactedOnceAndTwice("""['see "password": " here', 'token', {'secret': 'SEKone'}]""") shouldBe
      s"""['see "password": "$R', 'token', {'secret': '$R'}]"""
    redactedOnceAndTwice("""[{'msg': ('see "token": " x', 'y')}, {'password': 'SEKone'}]""") shouldBe
      s"""[{'msg': ('see "token": "$R', 'y')}, {'password': '$R'}]"""
  }

  it should "read an escaped quote inside the single-quoted string as the string's, not its end (#1697)" in {
    // Python escapes a `'` in a single-quoted string: `\'` is content, before the mention and inside its value.
    redactedOnceAndTwice("""{'note': 'it\'s "password": " here', 'apiKey': 'SEKone'}""") shouldBe
      s"""{'note': 'it\\'s "password": "$R', 'apiKey': '$R'}"""
    redactedOnceAndTwice("""{'a': 'see "password": "SEKone\', y" ok', 'b': 'keep'}""") shouldBe
      s"""{'a': 'see "password": "$R" ok', 'b': 'keep'}"""
    // An apostrophe between two letters ends nothing either.
    redactedOnceAndTwice("""{'note': 'see "password": "it's here', 'apiKey': 'SEKone'}""") shouldBe
      s"""{'note': 'see "password": "$R', 'apiKey': '$R'}"""
  }

  it should "end each of several mentions where its own string ends (#1697)" in {
    redactedOnceAndTwice("""{'note': '"password": " a', 'n2': '"token": " b', 'apiKey': 'SEKone'}""") shouldBe
      s"""{'note': '"password": "$R', 'n2': '"token": "$R', 'apiKey': '$R'}"""
    // Two in one string: the first ends at its own quote, the second where the string does.
    redactedOnceAndTwice("""{'note': 'see "password": " and "token": " too', 'apiKey': 'SEKone'}""") shouldBe
      s"""{'note': 'see "password": "$R"token": "$R', 'apiKey': '$R'}"""
  }

  it should "end the other double-quoted shapes a single-quoted string mentions where it ends (#1697)" in {
    // `key="`, `"Authorization": "` (read by its own pass) and an escaped `\"key\": \"`
    redactedOnceAndTwice("""{'note': 'see password=" here', 'apiKey': ["SEKone'}", 1]}""") shouldBe
      s"""{'note': 'see password="$R', 'apiKey': ["$R", '$R']}"""
    redactedOnceAndTwice("""{'msg': 'it\'s "Authorization" : " a: b', 'PASSWORD': "SEKone'}"}""") shouldBe
      s"""{'msg': 'it\\'s "Authorization" : "$R', 'PASSWORD': "$R"}"""
    redactedOnceAndTwice("""{'note': 'see \"password\": \" here', 'apiKey': ["SEKone'}"]}""") shouldBe
      s"""{'note': 'see \\"password\\": \\"$R', 'apiKey': ["$R"]}"""
  }

  it should "end a mention in a Python dict inside a JSON string where its single-quoted string ends (#1697)" in {
    // The repr of the issue's dict, logged as a JSON string: the same mention, its quotes escaped once.
    val dict  = """{'note': 'see "password": " here', 'apiKey': "7YJ1VSFPX'}", 'n': 1}"""
    val input = ujson.Obj("msg" -> dict, "n" -> 1).render()
    val out   = redactedOnceAndTwice(input)
    (out should not).include("7YJ1VSFPX")
    ujson.read(out)("msg").str shouldBe s"""{'note': 'see "password": "$R', 'apiKey': "$R", 'n': 1}"""
    ujson.read(out)("n").num shouldBe 1
    // `\\'` there is Python's escaped `'`, and `\\\\'` an escaped backslash before the closing quote.
    Seq(
      """{'msg': 'say \'hi\' "password": " x\\', 'access_token': 'SEKone"x'}""" ->
        s"""{'msg': 'say \\'hi\\' "password": "$R', 'access_token': '$R'}""",
      """{'msg': 'O\'Brien "token": " x', 'api_key': "SEKone'"}""" ->
        s"""{'msg': 'O\\'Brien "token": "$R', 'api_key': "$R"}""",
      """{'msg': 'it\'s token=" now', 'secret': 'SEKone'}""" -> s"""{'msg': 'it\\'s token="$R', 'secret': '$R'}"""
    ).foreach { case (dict, expected) =>
      withClue(s"dict $dict: ") {
        val out = redactedOnceAndTwice(ujson.Obj("msg" -> dict).render())
        ujson.read(out)("msg").str shouldBe expected
      }
    }
  }

  it should "still redact a double-quoted field closed inside a single-quoted string up to its own quote (#1697)" in {
    redactedOnceAndTwice("""{'body': '{"password": "SEKone", "user": "u"}', 'apiKey': 'SEKtwo'}""") shouldBe
      s"""{'body': '{"password": "$R", "user": "u"}', 'apiKey': '$R'}"""
    redactedOnceAndTwice("""{'note': 'see "password": "SEKone" ok', 'apiKey': 'SEKtwo'}""") shouldBe
      s"""{'note': 'see "password": "$R" ok', 'apiKey': '$R'}"""
    // A `'` followed by anything but what follows a value in a dict, a list or a tuple ends nothing.
    redactedOnceAndTwice("""{'note': 'see "password": "abc' + 'def"', 'apiKey': 'SEKtwo'}""") shouldBe
      s"""{'note': 'see "password": "$R"', 'apiKey': '$R'}"""
    // A `'` that does not stand where a Python value opens - in a word, after other punctuation - opens no string the
    // value could end at: a credential with a stray quote before it, and one holding `')`, is redacted whole.
    redactedOnceAndTwice("""{"body":"QC(A7'<H \"passwd\":\"QDAzq')s5-EAse4p8\"","status":500}""") shouldBe
      s"""{"body":"QC(A7'<H \\"passwd\\":\\"$R\\"","status":500}"""
    redactedOnceAndTwice("""x 7'<a "password": "ab'), c" ok""") shouldBe s"""x 7'<a "password": "$R" ok"""
    // Outside any single-quoted string a value still runs to its own quote.
    redactedOnceAndTwice("""{"password": "ab', 'c", "n": 1}""") shouldBe s"""{"password": "$R", "n": 1}"""
    redactedOnceAndTwice("""password="ab', 'c" ok""") shouldBe s"""password="$R" ok"""
  }

  it should "leave redacted every secret that is redacted when no field is mentioned (#1697)" in {
    // Python dicts and lists whose single-quoted strings mention a double-quoted field, raw and in JSON strings,
    // followed by credentials under sensitive keys. Mentioning a field in a string must not expose what is redacted
    // when the string mentions none: each mention is swapped for prose of the same quotes. Deterministic.
    val rnd                    = new scala.util.Random(1697)
    var id                     = 0
    def secret(): String       = { id += 1; f"Zq$id%05dWx" }
    def pick[A](xs: Seq[A]): A = xs(rnd.nextInt(xs.length))
    def repr(s: String): String = {
      val q = if (s.contains('\'') && !s.contains('"')) '"' else '\''
      q.toString + s.flatMap(c => if (c == q || c == '\\') "\\" + c else c.toString) + q
    }
    val keys     = Seq("password", "token", "apiKey", "client_secret", "Authorization", "access_token")
    val mentions = Seq("\"%s\": \"", "\"%s\":\"", "\"%s\" : \"", "%s=\"", "\\\"%s\\\": \\\"")
    val around   = Seq("" -> "", "see " -> " here", "it's " -> "", "O'Brien says " -> " now", "" -> ", 'x'")
    val values: Seq[String => String] = Seq(
      s => repr(s),
      s => repr(s + "'}"),
      s => s"[${repr(s + "'")}, -9${id}1]", // in a JSON string, left to the leaf walk: see the note below
      s => s"{'k': ${repr(s)}}",
      s => s"[${repr(s)}]"
    )
    (1 to 400).foreach { _ =>
      val fields = Vector.fill(1 + rnd.nextInt(3)) {
        val (before, after) = pick(around)
        val mention         = before + pick(mentions).format(pick(keys)) + after
        (repr(mention), repr(before + "plain" + after), s"${repr(pick(keys))}: ${pick(values)(secret())}")
      }
      val secrets = "Zq\\d{5}Wx".r.findAllIn(fields.map(_._3).mkString).toSeq
      def dict(mentioned: Boolean) =
        fields.map { case (m, p, f) => s"'note': ${if (mentioned) m else p}, $f" }.mkString("{", ", ", "}")
      // In a JSON string a list of `\"`-quoted leaves after a mention with an odd number of `"` is left to the
      // container walk, which reads the mention's quotes as opening a string escaped within the string and keeps the
      // leaves, as main does whatever key the mention names: a separate gap, not this one, so it is carried raw only.
      val hasEscapedLeafList = fields.exists(_._3.contains(": [\""))
      val carriers =
        if (hasEscapedLeafList) Seq[String => String](identity)
        else Seq[String => String](identity, d => ujson.Obj("msg" -> d).render())
      carriers.foreach { carry =>
        val withMention = Redaction.redact(carry(dict(mentioned = true)))
        val without     = Redaction.redact(carry(dict(mentioned = false)))
        secrets.filterNot(without.contains).foreach { s =>
          withClue(s"input ${carry(dict(mentioned = true))}, output $withMention: ")(
            (withMention should not).include(s)
          )
        }
        if (Try(ujson.read(without)).isSuccess) noException should be thrownBy ujson.read(withMention)
      }
    }
  }

  it should "end mentions inside single-quoted strings in time linear in the input (#1697)" in {
    val shapes = Seq(
      ("{", "'n': 'see \"password\": \" here', ", "'k': 1}"),
      ("{'n': 'see \"password\": \"", "a' b ", ""),
      ("{'n': '\"token\": \"", "'                    x", ""),
      ("{", "'n': '\"password\": \"', ", "}"),
      ("[", "'password=\" x', ", "]"),
      ("{\"m\": \"{", "'n': 'see \\\"password\\\": \\\" here', ", "}\"}"),
      ("{\"m\": \"", "'a' \\\"token\\\": \\\"x', ", "\"}"),
      ("{\"m\": \"", "'it\\\\'s token=\\\" now', ", "\"}")
    )
    shapes.foreach { case (prefix, unit, suffix) =>
      def input(repeats: Int): String = prefix + (unit * repeats) + suffix
      LinearTime.assertLinear(s"unit $unit", input(5000), input(20000))(
        Redaction.redact(_)
      )
    }
  }

  it should "read a credential in a JSON field whole however a stray quote before it reads (#1697)" in {
    // A `'` that seems to open a string around a real field - an SQL literal, a shell argument, a log prefix - ends
    // the field's value only where a Python container goes on after it. A credential holding `'),` or `']` was cut
    // there, and the rest written out in the clear.
    Seq(
      """body: '{"password":"Ab3')9xQ","user":"bob"}'"""    -> s"""body: '{"password":"$R","user":"bob"}'""",
      """INFO request body='{"password":"Ab3'),9xQ"}'"""    -> s"""INFO request body='{"password":"$R"}'""",
      """'{"password": "SEKone'), x"}"""                    -> s"""'{"password": "$R"}""",
      """[1, '{"token": "x'] y"}']"""                       -> s"""[1, '{"token": "$R"}']""",
      """[ '{"password": "SEKone', 'rest'"} ]"""            -> s"""[ '{"password": "$R"} ]""",
      """{'h': '{"password": "ab\\'), cd"}'}"""             -> s"""{'h': '{"password": "$R"}'}""",
      """{"m": "it's: 'see \"password\": \"ab', cd\" x"}""" -> s"""{"m": "it's: 'see \\"password\\": \\"$R\\" x"}""",
      """{"m": "user said: 'see \"password\": \"SEKone', ok\" end"}""" ->
        s"""{"m": "user said: 'see \\"password\\": \\"$R\\" end"}"""
    ).foreach { case (input, expected) =>
      withClue(s"input $input: ")(redactedOnceAndTwice(input) shouldBe expected)
    }
  }

  it should "read a credential whole in a JSON document that a single-quoted string on its line wraps (#1697)" in {
    // The wrapper's own closing quote closes a string too, so a credential holding `','`, `',5:` or `', None, '` read
    // as the end of the wrapper and a Python item after it, and the rest was written out in the clear.
    Seq(
      """INFO payload: '{"secret":"[K/T6nHA`O;e1b_S%W]','G","user":"bob"}'""" ->
        s"""INFO payload: '{"secret":"$R","user":"bob"}'""",
      """data: '{"password": "Ab3',5:xyz"}'"""       -> s"""data: '{"password": "$R"}'""",
      """{'body': '{"secret":"',1:r$jaz?4ZBkG"}'}""" -> s"""{'body': '{"secret":"$R"}'}""",
      """json=['{"api_key": "k1', True, 'z"}']"""    -> s"""json=['{"api_key": "$R"}']""",
      """args=('{"secret": "s3', None, 'q"}',)"""    -> s"""args=('{"secret": "$R"}',)""",
      """msg='{"token": "t0k', ('x"}'"""             -> s"""msg='{"token": "$R"}'""",
      """{"m": "x='{\"secret\":\"Zz9'], 'y\"}'"}"""  -> s"""{"m": "x='{\\"secret\\":\\"$R\\"}'"}""",
      // curl, its body single-quoted where a value opens: in an argv list or tuple, after `=`, in a JSON string
      """run: ['curl', '-d', '{"password": "Ab3', 'x"}']""" -> s"""run: ['curl', '-d', '{"password": "$R"}']""",
      """cmd=('curl', '-X', 'POST', '-d', '{"user":"bob","password":"p4ss','w0rd"}')""" ->
        s"""cmd=('curl', '-X', 'POST', '-d', '{"user":"bob","password":"$R"}')""",
      """curl --data='{"client_secret":"a1',2,'b2","grant_type":"x"}'""" ->
        s"""curl --data='{"client_secret":"$R","grant_type":"x"}'""",
      """exec curl -d='{"api_key": "k9', None, 'z"}'""" -> s"""exec curl -d='{"api_key": "$R"}'""",
      """{"argv": "['curl', '-d', '{\"password\": \"Ab3', 'x\"}']"}""" ->
        s"""{"argv": "['curl', '-d', '{\\"password\\": \\"$R\\"}']"}""",
      // and after `-d `, where no value opens, as it was
      """curl -d '{"password": "Ab3', 'x"}'""" -> s"""curl -d '{"password": "$R"}'"""
    ).foreach { case (input, expected) =>
      withClue(s"input $input: ")(redactedOnceAndTwice(input) shouldBe expected)
    }
  }

  it should "read whole any credential in a JSON document that a single-quoted string wraps (#1697)" in {
    // Random printable credentials, some holding what follows the end of a Python string, in JSON wrapped by an
    // unescaped single-quoted string on the same line: each is replaced whole. Deterministic.
    val rnd       = new scala.util.Random(16972)
    val printable = (33 to 126).map(_.toChar).filter(c => c != '"' && c != '\\')
    val keys      = Vector("password", "api_key", "token", "secret", "client_secret", "access_token")
    val wraps =
      Vector(
        "data: '%s'",
        "msg='%s'",
        "{'body': '%s'}",
        "json=['%s']",
        "INFO payload: '%s'",
        "args=('%s',)",
        "run: ['curl', '-d', '%s']"
      )
    val inserts = Vector("',", "', ", "',1", "', 'a", "'],", "'},", "', None", "',True,", "',(", "':", "',5:", "','")
    (1 to 3000).foreach { _ =>
      val chars  = Vector.fill(8 + rnd.nextInt(17))(printable(rnd.nextInt(printable.length))).mkString
      val at     = rnd.nextInt(chars.length)
      val secret = chars.take(at) + inserts(rnd.nextInt(inserts.length)) + chars.drop(at)
      val key    = keys(rnd.nextInt(keys.length))
      val user   = rnd.nextBoolean()
      val wrap   = wraps(rnd.nextInt(wraps.length))
      def doc(value: String): String = {
        val o = ujson.Obj(key -> value)
        if (user) o("user") = "bob"
        wrap.format(ujson.write(o))
      }
      val input = doc(secret)
      withClue(s"input $input: ")(Redaction.redact(input) shouldBe doc(R))
    }
  }

  it should "read a quote doubled as SQL escapes it as part of the value (#1697)" in {
    redactedOnceAndTwice("""statement: INSERT INTO cfg VALUES ('{"api_key": "ab''),cd"}')""") shouldBe
      s"""statement: INSERT INTO cfg VALUES ('{"api_key": "$R"}')"""
    redactedOnceAndTwice("""statement: INSERT INTO cfg VALUES ('{"api_key": "ab''cd"}', 'x')""") shouldBe
      s"""statement: INSERT INTO cfg VALUES ('{"api_key": "$R"}', 'x')"""
  }

  it should "take no quote on an earlier line for a string a field sits in (#1697)" in {
    // A Python string does not run over a line break: a quote that opens a line of prose ends nothing on the next.
    Seq(
      "'tis the season\n{\"password\": \"hunter2'),xyz\"}" -> s"'tis the season\n{\"password\": \"$R\"}",
      "note: 'draft\n{\"password\": \"hunter2'),xyz\"}"    -> s"note: 'draft\n{\"password\": \"$R\"}",
      "'90s music\n{\"api_key\": \"k9'}Qz\"}"              -> s"'90s music\n{\"api_key\": \"$R\"}",
      "', weird\n{\"msg\":\"it's fine\",\"password\":\"Zq1{Kd4}5Zg'}Zq2\",\"note\":\"'tis the season\"}" ->
        s"', weird\n{\"msg\":\"it's fine\",\"password\":\"$R\",\"note\":\"'tis the season\"}",
      "[' bracket\n{\"msg\":\"it's fine\",\"api_key\":\"Zq1i=hM}`D7')?LPPZq2\"}" ->
        s"[' bracket\n{\"msg\":\"it's fine\",\"api_key\":\"$R\"}",
      // a quote left open on an earlier line that a later one closes
      "the users' settings\n{\"msg\":\"the users'\",\"token\":\"ab!'}Bq\"}\n{\"msg\":\"a: 'b\",\"password\":\"c'}, ]KQk\"}" ->
        s"the users' settings\n{\"msg\":\"the users'\",\"token\":\"$R\"}\n{\"msg\":\"a: 'b\",\"password\":\"$R\"}",
      // and a mention on a line of its own still ends where its string does
      "a: b\n{'note': 'see \"password\": \" here', 'apiKey': 'SEKone'}" ->
        s"a: b\n{'note': 'see \"password\": \"$R', 'apiKey': '$R'}"
    ).foreach { case (input, expected) =>
      withClue(s"input $input: ")(redactedOnceAndTwice(input) shouldBe expected)
    }
  }

  it should "redact JSON lines among prose with apostrophes whole, as if no quote were near (#1697)" in {
    // Lines of JSON and lines of English with apostrophes - contractions, possessives, a quote that opens or ends a
    // line - and no single-quoted string on a JSON line: every credential, whatever quotes and brackets it holds, is
    // replaced whole and the rest of the line is kept, as it is with no apostrophe anywhere. Deterministic.
    val rnd       = new scala.util.Random(16971)
    val printable = (33 to 126).map(_.toChar).filter(c => c != '"' && c != '\\')
    val prose = Vector(
      "it's fine",
      "the users' settings were reset",
      "O'Brien logged in",
      "'tis the season",
      "'90s music",
      "a: 'b",
      "note: 'draft",
      "', weird",
      "[' bracket",
      "trailing quote '",
      "ends with the users'",
      "don't retry (we'll see)",
      "values ['a', 'b']",
      "Charles' book, Mark's pen"
    )
    val inWords = Vector("It's a test:", "O'Brien said", "don't log", "Mark's request")
    val keys    = Vector("password", "api_key", "token", "secret", "apiKey", "client_secret", "Authorization")
    val quoted  = Vector("'", "')", "'),", "', ", "']", "'}", "''", "', '", "'}, ", "') ")
    def secret(): String = {
      val chars = Vector.fill(16)(printable(rnd.nextInt(printable.length))).mkString
      val at    = rnd.nextInt(chars.length)
      chars.take(at) + quoted(rnd.nextInt(quoted.length)) + chars.drop(at)
    }
    (1 to 1500).foreach { _ =>
      val lines = Vector.fill(1 + rnd.nextInt(4)) {
        if (rnd.nextInt(3) == 0) {
          val text = prose(rnd.nextInt(prose.length))
          (text, text)
        } else {
          val key    = keys(rnd.nextInt(keys.length))
          val sec    = secret()
          val msg    = prose(rnd.nextInt(prose.length))
          val prefix = if (rnd.nextBoolean()) inWords(rnd.nextInt(inWords.length)) + " " else ""
          val line   = prefix + ujson.Obj("msg" -> msg, key -> sec, "n" -> 1).render()
          (line, prefix + ujson.Obj("msg" -> msg, key -> R, "n" -> 1).render())
        }
      }
      val input = lines.map(_._1).mkString("\n")
      withClue(s"input $input: ")(Redaction.redact(input) shouldBe lines.map(_._2).mkString("\n"))
    }
  }

  it should "end values at a doubled or followed quote in time linear in the input (#1697)" in {
    val shapes = Seq(
      ("{'n': '\"password\": \"", "x', 'yyyyyyyy' z ", ""),
      ("{'n': '\"password\": \"", "x', \"yyyyyyyy\" z ", ""),
      ("{\"m\": \"{'n': '\\\"password\\\": \\\"", "x', \\\"yyyyyyy\\\" z ", "\"}"),
      ("('{\"api_key\": \"", "ab''), ", "\"}')"),
      ("'tis\n", "{\"password\": \"a'), b\"}\n", ""),
      ("data: '{", "\"password\": \"a', 'x\", ", "\"n\": 1}'"),
      ("{\"m\": \"x='{", "\\\"password\\\": \\\"a', 'x\\\", ", "\\\"n\\\": 1}'\"}")
    )
    shapes.foreach { case (prefix, unit, suffix) =>
      def input(repeats: Int): String = prefix + (unit * repeats) + suffix
      LinearTime.assertLinear(s"unit $unit", input(5000), input(20000))(Redaction.redact(_))
    }
  }

  it should "redact a document of many JSON strings that mention 'token': [ in time linear in its length" in {
    // One forward scan decides which string, if any, encloses each container, so the cost does not grow with the
    // square of the length: a document four times as long takes about four times as long, never sixteen.
    val unit = s"""{"role": "user", "content": "see 'token': [ for details", "api_key": "$secretText"}, """
    def document(repeats: Int): String = "[" + (unit * repeats) + "{}]"
    Seq(500, 2000).foreach { repeats =>
      val out = Redaction.redact(document(repeats))
      (out should not).include(secretText)
      ujson.read(out).arr.size shouldBe repeats + 1
    }
    // the cheapest of several runs after a warm-up, in CPU time, not one run each in wall time (#1709)
    LinearTime.assertLinear("500 vs 2000 units", document(500), document(2000))(
      Redaction.redact(_)
    )
  }

  // ---------------------------------------------------------------------------------------------
  // Idempotence, and what is left alone
  // ---------------------------------------------------------------------------------------------

  it should "give the same result when applied twice" in {
    val inputs = Seq(
      """{"content": "{\"api_key\": \"a\", \"n\": 1}", "password": 12345, "x": "y"}""",
      """{"token": ["abc", 12, {"a": "b"}], "credentials": {"user": "u", "pass": "p"}}""",
      """{"content": "{\"token\": [\"abc\", {\"a\": \"b\"}]}"}""",
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

  it should "redact an array of many elements, and one element of a megabyte, under a credential key" in {
    val many = "{\"token\": [" + ("\"hunter2value\", " * 50000) + "\"end\"], \"n\": 1}"
    val out  = onSmallStack(many)
    (out should not).include("hunter2value")
    out should startWith(s"""{"token": ["$R", "$R", """)
    out should endWith(s""""$R"], "n": 1}""")
    onSmallStack("{\"token\": [\"" + ("a" * MegaChars) + "\", 1]}") shouldBe s"""{"token": ["$R", "$R"]}"""
  }

  it should "redact an array nested a hundred thousand levels deep under a credential key" in {
    val depth = 100000
    val input = "{\"token\": " + ("[" * depth) + "\"hunter2value\"" + ("]" * depth) + "}"
    onSmallStack(input) shouldBe "{\"token\": " + ("[" * depth) + s""""$R"""" + ("]" * depth) + "}"
  }

  it should "leave a megabyte-long array under a key that is not a credential" in {
    val input = "{\"messages\": [" + ("\"hunter2value\", " * 50000) + "\"end\"]}"
    onSmallStack(input) shouldBe input
  }

  it should "leave a megabyte of word characters, of spaces and of newlines unchanged" in {
    Seq("x" * MegaChars, " " * MegaChars, "a\n" * (MegaChars / 2), "api_key" * (MegaChars / 7)).foreach { input =>
      withClue(s"input starting ${input.take(8)}: ")(onSmallStack(input) shouldBe input)
    }
  }

  it should "redact megabyte-long Python repr values on a small stack (#1675, #1687)" in {
    onSmallStack("{'token': ['" + ("a:" * (MegaChars / 2)) + "']}") shouldBe s"{'token': ['$R']}"
    onSmallStack("{'password': \"" + ("it's " * (MegaChars / 5)) + "\", 'n': 1}") shouldBe
      s"""{'password': "$R", 'n': 1}"""
    onSmallStack("{\"c\": \"{'token': [\\\"" + ("a'" * (MegaChars / 2)) + "\\\"]}\"}") shouldBe
      s"""{"c": "{'token': [\\"$R\\"]}"}"""
    onSmallStack("{'password': " + ("1" * MegaChars) + "}") shouldBe s"{'password': '$R'}"
  }

  it should "redact quote-heavy Python repr shapes in time linear in their length (#1675, #1687)" in {
    // Each shape repeats a unit that asks a value, leaf or key check of its own: a growth with the square of the
    // length would make four times the input take sixteen times as long.
    val shapes = Seq(
      ("", "'password': \"", ""),
      ("", "'password': \\\"", ""),
      ("", "'password': \"x\" ", ""),
      ("", "'p': \"x\", 'password': \"y\", ", "}"),
      ("{'token': [", "'a:b', ", "]}"),
      ("{'token': [", "', '", "]}"),
      ("{'credentials': {", "'k': 'a=b', ", "}}"),
      ("{\"c\": \"{'token': [", "\\\"x'\\\", ", "]}\"}"),
      ("{\"c\": \"", "\\\"'token': [\\\", ", "\"}"),
      ("", "'password': 1, ", "")
    )
    shapes.foreach { case (prefix, unit, suffix) =>
      def input(repeats: Int): String = prefix + (unit * repeats) + suffix
      LinearTime.assertLinear(s"unit $unit", input(5000), input(20000))(
        Redaction.redact(_)
      )
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
