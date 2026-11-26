package org.llm4s.vectorstore

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Pure unit tests for the metadata JSON the Postgres stores write to and read from `jsonb` (#2993).
 *
 * The stored text is a wire format, so values that the old hand-rolled writer and regex reader broke on are
 * pinned here: quotes, backslashes, newlines, tabs, unicode and a value that tries to add a key.
 */
class PgMetadataJsonSpec extends AnyWordSpec with Matchers {

  private def roundTrip(metadata: Map[String, String]): Map[String, String] =
    PgMetadataJson.read(PgMetadataJson.write(metadata))

  "PgMetadataJson.write" should {
    "write an empty map as {}" in {
      PgMetadataJson.write(Map.empty) shouldBe "{}"
    }

    "write a plain map as a JSON object" in {
      ujson.read(PgMetadataJson.write(Map("source" -> "doc.pdf"))) shouldBe ujson.Obj("source" -> "doc.pdf")
    }

    "keep a value that tries to inject a key as a single value" in {
      val crafted = """x","tenant":"other"""
      val parsed  = ujson.read(PgMetadataJson.write(Map("title" -> crafted, "tenant" -> "acme"))).obj

      parsed.keySet shouldBe Set("title", "tenant")
      parsed("tenant").str shouldBe "acme"
      parsed("title").str shouldBe crafted
    }

    "escape a trailing backslash so the next key is not swallowed" in {
      val parsed = ujson.read(PgMetadataJson.write(Map("path" -> """C:\docs\""", "tenant" -> "acme"))).obj

      parsed("path").str shouldBe """C:\docs\"""
      parsed("tenant").str shouldBe "acme"
    }

    "write newlines, tabs and control characters as valid JSON" in {
      val json = PgMetadataJson.write(Map("title" -> "line one\nline two\tend\u0001"))
      (json should not).include("\n")
      (json should not).include("\t")
      noException should be thrownBy ujson.read(json)
    }
  }

  "PgMetadataJson.read" should {
    "return an empty map for null, empty, {} and unparseable text" in {
      PgMetadataJson.read(null) shouldBe empty
      PgMetadataJson.read("") shouldBe empty
      PgMetadataJson.read("{}") shouldBe empty
      PgMetadataJson.read("not json") shouldBe empty
      PgMetadataJson.read("""["a","b"]""") shouldBe empty
    }

    "decode escaped quotes instead of truncating the value" in {
      PgMetadataJson.read("""{"title": "say \"hi\" now"}""") shouldBe Map("title" -> "say \"hi\" now")
    }

    "decode \\n, \\t and \\uXXXX escapes" in {
      PgMetadataJson.read("""{"title": "a\nb\tc \u00e9"}""") shouldBe Map("title" -> "a\nb\tc é")
    }

    "not invent a key from text inside a value" in {
      PgMetadataJson.read("""{"note": "\"a\": \"b\""}""") shouldBe Map("note" -> "\"a\": \"b\"")
    }

    "skip non-string values" in {
      PgMetadataJson.read("""{"source": "doc.pdf", "page": 3, "ok": true, "x": null}""") shouldBe
        Map("source" -> "doc.pdf")
    }

    "read the jsonb text form Postgres returns (space after the colon)" in {
      PgMetadataJson.read("""{"lang": "en", "source": "doc.pdf"}""") shouldBe
        Map("lang" -> "en", "source" -> "doc.pdf")
    }
  }

  "PgMetadataJson round trip" should {
    "preserve every kind of key and value" in {
      val metadata = Map(
        "plain"          -> "value",
        "quote"          -> "say \"hi\"",
        "backslash"      -> """a\b\""",
        "newline"        -> "line one\nline two",
        "tab"            -> "a\tb",
        "unicode"        -> "héllo 世界 🚀",
        "injection"      -> """x","tenant":"other""",
        "key \"quoted\"" -> "k",
        ""               -> "empty key",
        "empty"          -> ""
      )
      roundTrip(metadata) shouldBe metadata
    }
  }
}
