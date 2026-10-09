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
    // Inside a string the container's own syntax has no `"`, so a `"`, escaped or not, is foreign and ends the walk:
    // the leaf is left to the other passes, and `model` and `n` keep their values.
    val input = """{"content": "{'token': [\"abc\"]}", "model": "gpt-4o", "n": 1}"""
    val out   = Redaction.redact(input)
    out shouldBe input
    ujson.read(out)("model").str shouldBe "gpt-4o"
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

  it should "redact a document of many JSON strings that mention 'token': [ in time linear in its length" in {
    // One forward scan decides which string, if any, encloses each container, so the cost does not grow with the
    // square of the length: a document four times as long takes about four times as long, never sixteen.
    val unit = s"""{"role": "user", "content": "see 'token': [ for details", "api_key": "$secretText"}, """
    def time(repeats: Int): Long = {
      val input = "[" + (unit * repeats) + "{}]"
      Redaction.redact(input) // warm up
      val start = System.nanoTime()
      val out   = Redaction.redact(input)
      val taken = System.nanoTime() - start
      (out should not).include(secretText)
      ujson.read(out).arr.size shouldBe repeats + 1
      taken
    }
    val small = time(500)
    val large = time(2000)
    withClue(s"500 units took ${small / 1000000} ms, 2000 units took ${large / 1000000} ms: ") {
      large should be < (small * 12 + 50000000L)
    }
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
