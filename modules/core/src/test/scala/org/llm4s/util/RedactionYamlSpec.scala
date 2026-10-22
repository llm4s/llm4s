package org.llm4s.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * A sensitive key on an indented YAML line (#1844): a nested key of a config dump - `  apiKey: ...` under a section,
 * `  - password: ...` in a list - had its value left in the clear, whatever the key; only a key at the start of the
 * line was read.
 */
class RedactionYamlSpec extends AnyFlatSpec with Matchers {

  private val Secret = "hunter2value"

  private val keys =
    Seq(
      "password",
      "apiKey",
      "api_key",
      "token",
      "secret",
      "client_secret",
      "access_token",
      "private_key",
      "assertion",
      "client_assertion",
      "saml_assertion",
      "samlAssertion"
    )

  private val styles = Seq(Secret, s"'$Secret'", s"\"$Secret\"")

  private val indents = Seq("  ", "    ", "\t", "      ")

  "Redaction.redact" should "redact a sensitive key at any indent, in every YAML scalar style" in {
    for {
      key    <- keys
      style  <- styles
      indent <- indents
    } {
      val in  = s"config:\n$indent$key: $style\n${indent}other: ok"
      val out = Redaction.redact(in)
      withClue(s"[$in] -> [$out]") {
        (out should not).include(Secret)
        out should include(s"$indent$key: ")
        out should include(s"${indent}other: ok")
      }
    }
  }

  it should "redact a sensitive key of a YAML list item" in {
    val out = Redaction.redact(s"users:\n  - name: alice\n  - password: $Secret\n  -   token: '$Secret'\n")
    (out should not).include(Secret)
    out should include("  - name: alice")
  }

  it should "redact every sensitive value of a nested config dump, and keep the rest" in {
    val dump =
      s"""llm4s:
         |  providers:
         |    openai-main:
         |      provider: openai
         |      model: gpt-4o-mini
         |      apiKey: sk-$Secret
         |  credentials:
         |    anthropic:
         |      apiKey: "$Secret"
         |  tracing:
         |    langfuse:
         |      secretKey: '$Secret'
         |      url: https://cloud.langfuse.com
         |""".stripMargin
    val out = Redaction.redact(dump)
    (out should not).include(Secret)
    out should include("      provider: openai")
    out should include("      model: gpt-4o-mini")
    out should include("      url: https://cloud.langfuse.com")
  }

  it should "leave an indented line whose key is not sensitive alone" in {
    val text = "server:\n  host: example.com\n  port: 8080\n  note: rotate the key monthly\n"
    Redaction.redact(text) shouldBe text
  }

  it should "still redact a key at the start of a line, as before" in {
    (Redaction.redact(s"password: $Secret") should not).include(Secret)
    (Redaction.redact(s"x-api-key: $Secret") should not).include(Secret)
  }

  it should "stay linear on long runs of indentation" in {
    val long    = " " * 200000 + "password"
    val started = System.nanoTime()
    Redaction.redact(long) shouldBe long
    Redaction.redact(long + ": " + Secret).contains(Secret) shouldBe false
    (System.nanoTime() - started) should be < 5_000_000_000L
  }
}
