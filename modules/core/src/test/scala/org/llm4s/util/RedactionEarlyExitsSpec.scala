package org.llm4s.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * The scanner's early exits - "already masked", "this is the next field's key", "the value ends here", "this is
 * prose", the `Bearer`/`Basic` exceptions - each audited against a value that only begins like what it skips (#1932).
 * An exit that leaked now fails toward masking; one kept on purpose is pinned here at its boundary, with what is
 * still replaced on the masking side of it. `RedactionPropertySpec` checks the class over every sensitive key shape.
 */
class RedactionEarlyExitsSpec extends AnyFlatSpec with Matchers {

  private val R = Redaction.RedactionPlaceholder

  /** Redacts `input`, checks that a second redaction changes nothing, and returns the result. */
  private def redacted(input: String): String = {
    val out = Redaction.redact(input)
    withClue(s"redacting the output for $input again: ")(Redaction.redact(out) shouldBe out)
    out
  }

  "The already-masked exit" should "skip a value only where the placeholder is the whole of it" in {
    redacted(s"password=${R}hunter2") shouldBe s"password=$R"
    redacted(s"""{"password": ${R}hunter2, "n": 1}""") shouldBe s"""{"password": $R, "n": 1}"""
    redacted(s"""{"password": [${R}hunter2]}""") shouldBe s"""{"password": [$R]}"""
    redacted(s"password=$R(hunter2)") shouldBe s"password=$R($R)"
    // the placeholder alone, before whitespace or what ends a value, is left as it is
    redacted(s"password=$R") shouldBe s"password=$R"
    redacted(s"password=$R next=1") shouldBe s"password=$R next=1"
    redacted(s"?password=$R&x=1") shouldBe s"?password=$R&x=1"
  }

  "The next-field exit" should "read a quoted pair value before a `:` on the same line as the value" in {
    redacted("""password="hunter2": failed""") shouldBe s"""password="$R": failed"""
    redacted("password='hunter2': failed") shouldBe s"password='$R': failed"
    redacted(""""password"="hunter2": failed""") shouldBe s""""password"="$R": failed"""
  }

  it should "keep an empty pair's next key only where a line break inside a bracket separates them" in {
    redacted("{password=\n\"user\": 1}") shouldBe "{password=\n\"user\": 1}"
  }

  it should "join a string written right after a field's value on its line, not one on the next line" in {
    redacted("{'password': 'abc' 'hunter2': 1}") shouldBe s"{'password': '$R' '$R': 1}"
    redacted("{'password': 'abc'\n 'user': 1}") shouldBe s"{'password': '$R'\n 'user': 1}"
  }

  "The glued-close exit" should "keep the rest of a quoted key only where that key names a credential" in {
    redacted("""{"password": "abc"hunter2": 1}""") shouldBe s"""{"password": "$R"$R": 1}"""
  }

  "The value-after-a-value exit" should "walk on over a Go slice's bare words on the same line" in {
    redacted("password=[abc hunter2] user=bob") shouldBe s"password=[$R $R] user=bob"
    redacted("password=[[a b] [c hunter2]]") shouldBe s"password=[[$R $R] [$R $R]]"
    redacted("""{"password": [a hunter2]}""") shouldBe s"""{"password": ["$R" "$R"]}"""
  }

  it should "keep the bracket of prose that puts a blank after it" in {
    redacted("see 'token': [ for details") shouldBe "see 'token': [ for details"
  }

  "The call-name exit" should "keep only a name that reads as a type's" in {
    redacted("password=SecretStr('hunter2')") shouldBe s"password=SecretStr('$R')"
    redacted("password=pydantic.SecretStr('hunter2')") shouldBe s"password=pydantic.SecretStr('$R')"
    redacted("password=hunter2(x)") shouldBe s"password=$R($R)"
    redacted("password=Pa55(word)") shouldBe s"password=$R($R)"
  }

  "The Bearer and Basic exits" should "leave a token to a header only where it names a sensitive header" in {
    redacted("Basic alice:hunter2 ok") shouldBe s"$R ok"
    redacted("Bearer hunter2: x") shouldBe s"$R x"
    redacted("Bearer Authorization: hunter2") shouldBe s"Bearer Authorization: $R"
    redacted("Bearer \"hunter2\" ok") shouldBe s"Bearer \"$R\" ok"
    redacted("auth Bearer 'hunter2'") shouldBe s"auth Bearer '$R'"
  }

  "The comparison exit" should "read the right side of a sensitive key's `==` as its value" in {
    redacted("E   assert password == 'hunter2'") shouldBe s"E   assert password == '$R'"
    redacted(""""password" == "hunter2"""") shouldBe s""""password" == "$R""""
    redacted("assert x == y") shouldBe "assert x == y"
  }

  "The value-ends-here exits" should "read a value in angle brackets" in {
    redacted("password=<hunter2> ok") shouldBe s"password=$R ok"
  }

  it should "end where they are kept on purpose, replacing everything before" in {
    // `;` and `,` separate pairs (a cookie, a list); `&key=` starts a query's next parameter
    redacted("password=abc;next=1") shouldBe s"password=$R;next=1"
    redacted("password=abc&next=1") shouldBe s"password=$R&next=1"
    // a query value ends before `,` and JSON, as a URL in a JSON array does
    redacted("""["https://h/?token=abc","x"]""") shouldBe s"""["https://h/?token=$R","x"]"""
    // a line break outside a bracket ends a `key=` or `key:` line, empty
    redacted("password=\nnext=1") shouldBe "password=\nnext=1"
    redacted("password\n: x") shouldBe "password\n: x"
    // a URL's authority ends at its path: a `/` before any `@` is the path's
    redacted("https://user:pw@host/a@b") shouldBe s"https://user:$R@host/a@b"
  }

  "The prose exits" should "keep prose and replace a value that reads as a credential" in {
    redacted("token= see the docs") shouldBe "token= see the docs"
    redacted("token= Zq9abc") shouldBe s"token= $R"
    redacted("Reset your password: the link expires.") shouldBe "Reset your password: the link expires."
    redacted("your password: hunter2x9") shouldBe s"your password: $R"
    redacted("""{"assertion": "response.status == 200"}""") shouldBe """{"assertion": "response.status == 200"}"""
    redacted("""{"assertion": "eyJhbGciOiJSUzI1NiJ9abcdefghij"}""") shouldBe s"""{"assertion": "$R"}"""
  }
}
