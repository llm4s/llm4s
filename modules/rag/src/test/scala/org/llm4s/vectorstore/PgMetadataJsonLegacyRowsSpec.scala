package org.llm4s.vectorstore

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Rows already stored by the hand-rolled writers that `PgMetadataJson` replaced (#2993) read back as before.
 *
 * `Legacy` is the writer and reader each store had before the change, copied verbatim. `jsonb` is the text
 * Postgres 16 returned for `<legacy writer output>::jsonb::text` (Postgres keeps no original spelling: it
 * re-serialises the stored value), captured from a pgvector/pgvector:pg16 container; a value the legacy writer
 * could not store (`jsonb` rejected the text) has no row and is not listed.
 */
class PgMetadataJsonLegacyRowsSpec extends AnyWordSpec with Matchers {

  private object Legacy {
    // PgVectorStore
    def vectorWrite(metadata: Map[String, String]): String =
      if (metadata.isEmpty) "{}"
      else metadata.map { case (k, v) => s""""$k":"$v"""" }.mkString("{", ",", "}")

    def vectorRead(json: String): Map[String, String] =
      if (json == null || json == "{}" || json.isEmpty) Map.empty
      else {
        val pattern = """"([^"]+)":\s*"([^"]*)"""".r
        pattern.findAllMatchIn(json).map(m => m.group(1) -> m.group(2)).toMap
      }

    // PgKeywordIndex and PgSearchIndex (the same code)
    def keywordWrite(metadata: Map[String, String]): String =
      if (metadata.isEmpty) "{}"
      else {
        val entries = metadata.map { case (k, v) =>
          val escapedKey   = k.replace("\\", "\\\\").replace("\"", "\\\"")
          val escapedValue = v.replace("\\", "\\\\").replace("\"", "\\\"")
          s""""$escapedKey":"$escapedValue""""
        }
        s"{${entries.mkString(",")}}"
      }

    def keywordRead(json: String): Map[String, String] =
      if (json == null || json.isEmpty || json == "{}") Map.empty
      else {
        val pattern = """"([^"\\]*(?:\\.[^"\\]*)*)"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""".r
        pattern
          .findAllMatchIn(json)
          .map { m =>
            val key   = m.group(1).replace("\\\"", "\"").replace("\\\\", "\\")
            val value = m.group(2).replace("\\\"", "\"").replace("\\\\", "\\")
            key -> value
          }
          .toMap
      }

    // PgCollectionStore
    def collectionWrite(metadata: Map[String, String]): String =
      if (metadata.isEmpty) "{}"
      else {
        val entries = metadata.map { case (k, v) =>
          val escapedKey   = k.replace("\"", "\\\"")
          val escapedValue = v.replace("\"", "\\\"")
          s""""$escapedKey":"$escapedValue""""
        }
        s"{${entries.mkString(",")}}"
      }

    def collectionRead(json: String): Map[String, String] =
      if (json == null || json.isEmpty || json == "{}") Map.empty
      else {
        val pattern = """"([^"]+)"\s*:\s*"([^"]*)"""".r
        pattern.findAllMatchIn(json).map(m => m.group(1) -> m.group(2)).toMap
      }
  }

  /** A value the legacy writers all stored, with the `jsonb` text Postgres returns for `source`, `k`. */
  private val plainRows: Seq[(String, String)] = Seq(
    "doc.pdf"     -> """{"k": "doc.pdf", "source": "doc.pdf"}""",
    ""            -> """{"k": "", "source": "doc.pdf"}""",
    "héllo 世界 🚀" -> """{"k": "héllo 世界 🚀", "source": "doc.pdf"}""",
    "a: b, c"     -> """{"k": "a: b, c", "source": "doc.pdf"}""",
    "{}"          -> """{"k": "{}", "source": "doc.pdf"}""",
    "it's"        -> """{"k": "it's", "source": "doc.pdf"}""",
    "a/b"         -> """{"k": "a/b", "source": "doc.pdf"}"""
  )

  /** Rows only the escaping writers (keyword index, search index, and for quotes the collection store) stored. */
  private val keywordRows: Seq[(String, String)] = Seq(
    "say \"hi\""             -> """{"k": "say \"hi\"", "source": "doc.pdf"}""",
    """C:\docs"""            -> """{"k": "C:\\docs", "source": "doc.pdf"}""",
    """C:\docs\"""           -> """{"k": "C:\\docs\\", "source": "doc.pdf"}""",
    """a\nb"""               -> """{"k": "a\\nb", "source": "doc.pdf"}""",
    """x","tenant":"other""" -> """{"k": "x\",\"tenant\":\"other", "source": "doc.pdf"}"""
  )

  private def meta(v: String) = Map("source" -> "doc.pdf", "k" -> v)

  "PgMetadataJson.write" should {
    "write the same text as every legacy writer for a value none of them had to escape" in {
      plainRows.foreach { case (v, _) =>
        withClue(v) {
          PgMetadataJson.write(meta(v)) shouldBe Legacy.vectorWrite(meta(v))
          PgMetadataJson.write(meta(v)) shouldBe Legacy.keywordWrite(meta(v))
          PgMetadataJson.write(meta(v)) shouldBe Legacy.collectionWrite(meta(v))
        }
      }
    }

    "write the same text as the keyword and search index writers for quotes and backslashes" in {
      keywordRows.foreach { case (v, _) =>
        withClue(v)(PgMetadataJson.write(meta(v)) shouldBe Legacy.keywordWrite(meta(v)))
      }
    }
  }

  "PgMetadataJson.read" should {
    "read a plain row as every legacy reader did" in {
      plainRows.foreach { case (v, jsonb) =>
        withClue(v) {
          Legacy.vectorRead(jsonb) shouldBe meta(v)
          Legacy.keywordRead(jsonb) shouldBe meta(v)
          Legacy.collectionRead(jsonb) shouldBe meta(v)
          PgMetadataJson.read(jsonb) shouldBe meta(v)
        }
      }
    }

    "read a keyword or search index row with quotes or backslashes as the legacy reader did" in {
      keywordRows.foreach { case (v, jsonb) =>
        withClue(v) {
          Legacy.keywordRead(jsonb) shouldBe meta(v)
          PgMetadataJson.read(jsonb) shouldBe meta(v)
        }
      }
    }

    "read a collection row with a quote as it was written, which the legacy reader truncated" in {
      val jsonb = """{"k": "say \"hi\"", "source": "doc.pdf"}"""
      Legacy.collectionRead(jsonb) should not be meta("say \"hi\"")
      PgMetadataJson.read(jsonb) shouldBe meta("say \"hi\"")
    }

    "read a vector row that a value injected a key into with the same keys as before" in {
      // PgVectorStore wrote x","tenant":"other unescaped, so the stored row has three keys.
      val jsonb = """{"k": "x", "source": "doc.pdf", "tenant": "other"}"""
      PgMetadataJson.read(jsonb) shouldBe Legacy.vectorRead(jsonb)
    }

    "read a vector or collection row written from a literal backslash escape as the value Postgres stored" in {
      // PgVectorStore and PgCollectionStore wrote a\nb (backslash, n) unescaped, so jsonb stored a newline, and
      // metadata->>'k' (what filters compare) is a newline. The legacy regex returned the escape's text, the
      // value originally given; the decoded value is what is stored. Every other backslash those two wrote
      // made the jsonb cast fail, so no row has one.
      val jsonb = """{"k": "a\nb", "source": "doc.pdf"}"""
      Legacy.vectorRead(jsonb) shouldBe meta("""a\nb""")
      PgMetadataJson.read(jsonb) shouldBe meta("a\nb")
    }
  }
}
