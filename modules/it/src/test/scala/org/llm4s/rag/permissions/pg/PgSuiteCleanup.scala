package org.llm4s.rag.permissions.pg

import org.llm4s.rag.permissions.CollectionPath
import org.llm4s.security.Secret

import java.sql.DriverManager
import scala.util.{ Try, Using }

/**
 * Cleanup for a `@Docker` suite that uses a [[PgSearchIndex]] on a database other suites share.
 *
 * `PgSearchIndex.dropSchema()` must not be used by a suite: it drops the shared `llm4s_collections` and
 * `llm4s_principals` tables with CASCADE, under any suite running beside it. A suite instead gives its collections
 * paths of its own (a per-run root, from [[runRoot]]), removes exactly those, and drops only the tables it created.
 */
object PgSuiteCleanup {

  /** A collection root no other suite or earlier run uses, such as `filter-parity-1764239000000`. */
  def runRoot(label: String): CollectionPath = CollectionPath.unsafe(s"$label-${System.currentTimeMillis()}")

  /** Drops `tables` if they exist. Nothing else: never the shared `llm4s_` tables. */
  def dropOwnTables(url: String, user: String, password: Secret, tables: Seq[String]): Unit =
    Try {
      Using.resource(DriverManager.getConnection(url, user, password.reveal)) { conn =>
        Using.resource(conn.createStatement())(stmt => tables.foreach(t => stmt.execute(s"DROP TABLE IF EXISTS $t")))
      }
    }

  /**
   * Removes `root` and every collection under it, deepest first, with their documents, then drops `tables`. Best
   * effort, as an `afterAll` is.
   */
  def removeOwn(
    index: PgSearchIndex,
    root: CollectionPath,
    url: String,
    user: String,
    password: Secret,
    tables: Seq[String]
  ): Unit = {
    val own = index.collections
      .list(org.llm4s.rag.permissions.CollectionPattern.AllDescendants(root))
      .getOrElse(Seq.empty)
      .map(_.path)
      .sortBy(-_.segments.length)
    own.foreach { path =>
      Try(index.clearCollection(path))
      Try(index.collections.delete(path))
    }
    dropOwnTables(url, user, password, tables)
  }
}
