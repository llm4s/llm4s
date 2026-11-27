package org.llm4s.vectorstore

import org.llm4s.vectorstore.MetadataFilter._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * The SQL the Postgres stores send for each [[MetadataFilter]] (#2996). The `@Docker` contract suites and
 * `PgMetadataFilterParitySpec` in `modules/it` run it against Postgres, against the answers of `MetadataFilterMatrix`.
 */
class PgMetadataFilterSqlSpec extends AnyWordSpec with Matchers {

  "PgMetadataFilterSql" should {
    "make every leaf two-valued, so Not keeps a record without the key" in {
      PgMetadataFilterSql(Not(Equals("lang", "en"))) shouldBe
        ("NOT (COALESCE(metadata->>? = ?, FALSE))", Seq("lang", "en"))
    }

    "test Contains with strpos, passing the substring unchanged" in {
      PgMetadataFilterSql(Contains("path", "a_b%")) shouldBe
        ("COALESCE(strpos(metadata->>?, ?) > 0, FALSE)", Seq("path", "a_b%"))
    }

    "test HasKey with jsonb_exists, which has no ? for pgjdbc to read as a placeholder" in {
      val (sql, params) = PgMetadataFilterSql(HasKey("type"))
      sql shouldBe "jsonb_exists(metadata, ?)"
      sql.count(_ == '?') shouldBe params.size
    }

    "match nothing for an empty In and for Not(All)" in {
      PgMetadataFilterSql(In("lang", Set.empty)) shouldBe ("FALSE", Seq.empty)
      PgMetadataFilterSql(Not(All)) shouldBe ("NOT (TRUE)", Seq.empty)
    }

    "bind one parameter per In value, after the key" in {
      val (sql, params) = PgMetadataFilterSql(In("lang", Set("en", "fr")))
      sql shouldBe "COALESCE(metadata->>? IN (?,?), FALSE)"
      params.head shouldBe "lang"
      params.tail.toSet shouldBe Set("en", "fr")
    }

    "combine And and Or, keeping parameter order" in {
      PgMetadataFilterSql(Or(And(All, HasKey("a")), Equals("b", "c"))) shouldBe
        ("((TRUE AND jsonb_exists(metadata, ?)) OR COALESCE(metadata->>? = ?, FALSE))", Seq("a", "b", "c"))
    }

    "bind exactly as many parameters as the SQL has placeholders" in {
      val filter        = And(Not(In("x", Set("1", "2", "3"))), Or(Contains("y", "?"), Not(HasKey("z"))))
      val (sql, params) = PgMetadataFilterSql(filter)
      sql.count(_ == '?') shouldBe params.size
    }
  }
}
