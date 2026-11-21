package org.llm4s.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

/**
 * #1800: an empty `key=` value at the end of a JSON string - bare, or inside JSON held in a string - read the quote
 * that closes that string as the opening quote of its value, and masked the `,` or `}` after it, so the JSON no
 * longer parsed. The value is empty instead; what the passes give up is only punctuation.
 */
class RedactionClosingQuotePairSpec extends AnyFlatSpec with Matchers {

  private val R = Redaction.RedactionPlaceholder

  private def parses(json: String): Boolean = Try(ujson.read(json)).isSuccess

  private def innerParses(json: String, field: String): Boolean =
    Try(ujson.read(ujson.read(json)(field).str)).isSuccess

  "Redaction.redact" should "leave an empty pair value before the end of a nested JSON string as it is (#1800)" in {
    val cases = Seq(
      """{"o":"{\"a\":\"password=\",\"b\":1}"}""",
      """{"o":"{\"a\":\"x\\ntoken=\",\"b\":\"c\"}"}""",
      """{"o":"{\"a\":\"token=\"}","p":1}""",
      """{"o":"{\"a\":\"token=\"} ]","p":1}"""
    )
    cases.foreach { in =>
      withClue(in) {
        Redaction.redact(in) shouldBe in
      }
    }
  }

  it should "keep the inner JSON parsing after an auth scheme before the pair (#1800)" in {
    val basic = Redaction.redact("""{"o":"{\"a\":\"Basic token=\",\"b\":1}"}""")
    basic shouldBe s"""{"o":"{\\"a\\":\\"$R\\",\\"b\\":1}"}"""
    innerParses(basic, "o") shouldBe true

    val bearer = Redaction.redact("""{"o":"{\"a\":\"Bearer password=\"}","p":1}""")
    (bearer should not).include(s"""\\"$R"""")
    innerParses(bearer, "o") shouldBe true
  }

  it should "leave an empty query value before the end of a JSON string as it is (#1800, #1707)" in {
    val in = """{"q":"a=1&token=","n":"x"}"""
    Redaction.redact(in) shouldBe in
    parses(Redaction.redact(in)) shouldBe true
    val python = "{'q': 'a=1&token=', 'n': 'x'}"
    Redaction.redact(python) shouldBe python
    val end = """{"q":"a=1&token="}"""
    Redaction.redact(end) shouldBe end
  }

  it should "still redact a value that the quote opens (#1800)" in {
    Redaction.redact("""{"o":"{\"a\":\"password=\\\"s3cr3t\\\"\",\"b\":1}"}""") shouldBe
      s"""{"o":"{\\"a\\":\\"password=\\\\\\"$R\\\\\\"\\",\\"b\\":1}"}"""
    Redaction.redact("""{"a":"token=\"abc\""}""") shouldBe s"""{"a":"token=\\"$R\\""}"""
    Redaction.redact("""password=\"s3cr3t\" next""") shouldBe s"""password=\\"$R\\" next"""
    // A quote followed by `,` and no key, or by a bracket and a word, still opens a value: the text up to the next
    // quote may be the credential.
    (Redaction.redact("""log password=", hunter2" end""") should not).include("hunter2")
    (Redaction.redact("""log password="} hunter2" end""") should not).include("hunter2")
    (Redaction.redact("""{"o":"password=\", hunter2\" end"}""") should not).include("hunter2")
    (Redaction.redact("""{"o":"password=\"}, [hunter2\" end"}""") should not).include("hunter2")
    (Redaction.redact("""{"o":"password=\"}\nhunter2\" end"}""") should not).include("hunter2")
  }

  it should "be idempotent on the shapes of #1800" in {
    Seq(
      """{"o":"{\"a\":\"password=\",\"b\":1}"}""",
      """{"o":"{\"a\":\"Basic token=\",\"b\":1}"}""",
      """{"o":"{\"a\":\"Bearer password=\"}","p":1}""",
      """{"q":"a=1&token=","n":"x"}""",
      """{"o":"{\"a\":\"password=\\\"s3cr3t\\\"\",\"b\":1}"}"""
    ).foreach { in =>
      val once = Redaction.redact(in)
      withClue(in) {
        Redaction.redact(once) shouldBe once
      }
    }
  }
}
