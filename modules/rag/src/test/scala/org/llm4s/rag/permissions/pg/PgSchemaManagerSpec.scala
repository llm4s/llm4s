package org.llm4s.rag.permissions.pg

import org.llm4s.error.ProcessingError
import org.llm4s.rag.permissions.CollectionPath
import org.llm4s.vectorstore.ScriptedDb
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * `PgSchemaManager`'s reset and migration over a scripted JDBC connection (#2992). The same against Postgres is
 * `PgSchemaResetSpec` (`modules/it`, `@Docker`).
 */
class PgSchemaManagerSpec extends AnyFlatSpec with Matchers {

  "PgSchemaManager.extendVectorsTable" should "reject an invalid table name before touching the connection" in {
    // A null connection proves no SQL is attempted: the name is interpolated into DDL.
    PgSchemaManager.extendVectorsTable(null, "vectors; DROP TABLE users") match {
      case Left(e: ProcessingError) => e.message should include("Invalid table name")
      case other                    => fail(s"Expected a ProcessingError, got $other")
    }
  }

  private def column(table: String, name: String) = Map("table_name" -> table, "column_name" -> name)

  /** A database whose `vectors` has both permission columns; `other`, if given, does too and holds rows or not. */
  private def schema(other: Option[Boolean] = None): ScriptedDb =
    new ScriptedDb(sql =>
      if (sql.contains("to_regclass")) Seq(Map("exists" -> true))
      else if (sql.contains("information_schema.columns"))
        Seq(column("vectors", "collection_id"), column("vectors", "readable_by")) ++
          other.toSeq.flatMap(_ => Seq(column("other", "collection_id"), column("other", "readable_by")))
      else if (sql.contains("FROM \"other\"")) other.toSeq.map(holds => Map("exists" -> holds))
      else Seq.empty
    )

  "PgSchemaManager.dropSchema" should "delete every row in a collection before dropping the schema (#2992)" in {
    val db = schema()

    PgSchemaManager.dropSchema(db.dataSource.getConnection, "vectors") shouldBe Right(())

    val sql                       = db.statements
    def at(fragment: String): Int = sql.indexWhere(_.contains(fragment))
    at("LOCK TABLE llm4s_collections") should be >= 0
    at("LOCK TABLE vectors") should be > at("LOCK TABLE llm4s_collections")
    val delete = at("DELETE FROM vectors")
    delete should be > at("LOCK TABLE vectors")
    sql(delete) should include("collection_id IS NOT NULL")
    sql(delete) should include("cardinality(readable_by) > 0")
    at("DROP COLUMN IF EXISTS collection_id") should be > delete
    at("DROP TABLE IF EXISTS llm4s_collections") should be > delete
    db.commits shouldBe 1
    db.rollbacks shouldBe 0
  }

  it should "reset a table beside another permission table that holds no rows" in {
    val db = schema(other = Some(false))

    PgSchemaManager.dropSchema(db.dataSource.getConnection, "vectors") shouldBe Right(())
    db.statements.exists(_.contains("DROP TABLE IF EXISTS llm4s_collections")) shouldBe true
  }

  it should "refuse, changing nothing, while another vectors table holds rows in the shared collections" in {
    val db = schema(other = Some(true))

    val result = PgSchemaManager.dropSchema(db.dataSource.getConnection, "vectors")

    result.left.toOption.get.message should include("other")
    db.statements.exists(_.contains("DELETE FROM")) shouldBe false
    db.statements.exists(_.contains("DROP ")) shouldBe false
    db.statements.exists(_.contains("ALTER TABLE")) shouldBe false
    db.rollbacks shouldBe 1
    db.commits shouldBe 0
  }

  it should "reject an invalid table name before touching the connection" in {
    PgSchemaManager.dropSchema(null, "vectors; DROP TABLE users").left.toOption.get.message should include(
      "Invalid table name"
    )
  }

  private def collections(leaf: Option[Boolean], orphans: Long = 0): ScriptedDb =
    new ScriptedDb(sql =>
      if (sql.contains("FOR SHARE")) leaf.toSeq.map(l => Map("is_leaf" -> l))
      else if (sql.contains("SELECT id FROM llm4s_collections")) leaf.toSeq.map(_ => Map("id" -> 9))
      else if (sql.contains("COUNT(*)")) Seq(Map("count" -> orphans))
      else Seq.empty
    )

  "PgSchemaManager.migrateExistingVectors" should "keep each row's readable_by and skip rows ingest wrote (#2992)" in {
    val db = collections(leaf = Some(true))

    PgSchemaManager.migrateExistingVectors(db.dataSource.getConnection, "vectors", 9) shouldBe Right(1L)

    val update = db.statements.find(_.contains("UPDATE vectors")).get
    (update should not).include("readable_by = '{}'")
    update should include("COALESCE(readable_by, '{}')")
    update should include(s"NOT (${PgSchemaManager.OrphanedPermissionRow})")
    db.commits shouldBe 1
  }

  it should "refuse a collection that does not exist or is not a leaf, migrating nothing" in {
    Seq(None, Some(false)).foreach { leaf =>
      val db = collections(leaf)
      PgSchemaManager.migrateExistingVectors(db.dataSource.getConnection, "vectors", 9).isLeft shouldBe true
      db.statements.exists(_.contains("UPDATE vectors")) shouldBe false
      db.rollbacks shouldBe 1
    }
  }

  "PgSchemaManager.runFullMigration" should "migrate only into a collection the caller names and created" in {
    val missing = collections(leaf = None)
    PgSchemaManager
      .runFullMigration(missing.dataSource.getConnection, "vectors", CollectionPath.unsafe("legacy"))
      .left
      .toOption
      .get
      .message should include("Collection not found: legacy")
    missing.statements.exists(_.contains("UPDATE vectors")) shouldBe false
    missing.statements.exists(_.contains("INSERT INTO llm4s_collections")) shouldBe false

    val present = collections(leaf = Some(true), orphans = 3)
    PgSchemaManager.runFullMigration(
      present.dataSource.getConnection,
      "vectors",
      CollectionPath.unsafe("legacy")
    ) shouldBe
      Right(MigrationStats(2, 5, collectionId = 9, vectorsMigrated = 1, vectorsSkipped = 3))
  }
}
