package org.llm4s.rag.permissions.pg

import org.llm4s.rag.permissions.{ CollectionPath, CollectionPattern }
import org.llm4s.vectorstore.SqlLikePattern
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.sql.DriverManager
import scala.util.Using

/**
 * The SQL `PgCollectionStore` builds for a [[CollectionPattern]] (#2989).
 *
 * The `LIKE` parameter is checked by evaluating it with SQLite's `LIKE ... ESCAPE '\'`, whose `_`, `%` and
 * escape handling is the same as Postgres's, so no database server is needed.
 */
class PgCollectionStorePatternSpec extends AnyWordSpec with Matchers {

  private def path(p: String): CollectionPath = CollectionPath.unsafe(p)

  /** Whether `candidate` is selected by the `AllDescendants(prefix)` clause. */
  private def descendantMatches(prefix: String, candidate: String): Boolean = {
    val (_, params)             = PgCollectionStore.patternToSql(CollectionPattern.AllDescendants(path(prefix)))
    val Seq(exact, likePattern) = params
    candidate == exact || Using.resource(DriverManager.getConnection("jdbc:sqlite::memory:")) { conn =>
      Using.resource(conn.prepareStatement("SELECT ? LIKE ? ESCAPE '\\'")) { stmt =>
        stmt.setString(1, candidate)
        stmt.setString(2, likePattern)
        Using.resource(stmt.executeQuery())(rs => rs.next() && rs.getBoolean(1))
      }
    }
  }

  "PgCollectionStore.patternToSql" should {
    "use an ESCAPE clause for AllDescendants" in {
      val (sql, params) = PgCollectionStore.patternToSql(CollectionPattern.AllDescendants(path("tenant_1")))
      sql shouldBe s"(path = ? OR path LIKE ? ${SqlLikePattern.EscapeClause})"
      params shouldBe Seq("tenant_1", "tenant\\_1/%")
    }

    "match the prefix itself and its descendants" in {
      descendantMatches("tenant_1", "tenant_1") shouldBe true
      descendantMatches("tenant_1", "tenant_1/docs") shouldBe true
      descendantMatches("tenant_1", "tenant_1/docs/2026") shouldBe true
    }

    "not treat _ in the prefix as a wildcard" in {
      descendantMatches("tenant_1", "tenant11/docs") shouldBe false
      descendantMatches("tenant_1", "tenant-1/docs") shouldBe false
      descendantMatches("tenant_1", "tenantA1/docs") shouldBe false
    }

    "not treat % in the prefix as a wildcard" in {
      descendantMatches("a%b", "a%b/docs") shouldBe true
      descendantMatches("a%b", "axyzb/docs") shouldBe false
    }

    "not match a sibling that only shares the prefix text" in {
      descendantMatches("tenant_1", "tenant_10/docs") shouldBe false
    }

    "agree with the in-memory CollectionPattern.matches" in {
      val pattern    = CollectionPattern.AllDescendants(path("tenant_1"))
      val candidates = Seq("tenant_1", "tenant_1/docs", "tenant11/docs", "tenant-1/docs", "tenant_10/docs")
      candidates.foreach { c =>
        withClue(c) {
          descendantMatches("tenant_1", c) shouldBe pattern.matches(path(c))
        }
      }
    }

    "leave the other patterns unchanged" in {
      PgCollectionStore.patternToSql(CollectionPattern.All) shouldBe ("TRUE", Seq.empty)
      PgCollectionStore.patternToSql(CollectionPattern.Exact(path("a_b"))) shouldBe ("path = ?", Seq("a_b"))
      PgCollectionStore.patternToSql(CollectionPattern.ImmediateChildren(path("a_b"))) shouldBe
        ("parent_path = ?", Seq("a_b"))
    }
  }
}
