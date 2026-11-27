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

  /** A row of the name-resolution query: `name` resolved, through `search_path`, to `schema.name`. */
  private def resolved(name: String, schema: String = "public") =
    Map("name" -> name, "nspname" -> schema, "relname" -> name)

  private def allResolved: Seq[Map[String, Any]] =
    Seq("llm4s_collections", "llm4s_principals", "llm4s_group_id_seq", "vectors").map(resolved(_))

  /**
   * A database whose names all resolve to `public` and whose `vectors` has both permission columns; `other`, if
   * given, is another table with a `collection_id` that holds rows or not.
   */
  private def schema(
    other: Option[Boolean] = None,
    relations: Seq[Map[String, Any]] = allResolved,
    autoCommit: Boolean = true
  ): ScriptedDb =
    new ScriptedDb(
      sql =>
        if (sql.contains("to_regclass(x.name)")) relations
        else if (sql.contains("current_schemas"))
          Seq(Map("table_schema" -> "public", "table_name" -> "vectors")) ++
            other.toSeq.map(_ => Map("table_schema" -> "public", "table_name" -> "other"))
        else if (sql.contains("information_schema.columns"))
          Seq(Map("column_name" -> "collection_id"), Map("column_name" -> "readable_by"))
        else if (sql.contains("FROM \"public\".\"other\"")) other.toSeq.map(holds => Map("exists" -> holds))
        else Seq.empty,
      autoCommit = autoCommit
    )

  "PgSchemaManager.dropSchema" should "delete every row in a collection before dropping the schema (#2992)" in {
    val db = schema()

    PgSchemaManager.dropSchema(db.dataSource.getConnection, "vectors") shouldBe Right(())

    val sql                       = db.statements
    def at(fragment: String): Int = sql.indexWhere(_.contains(fragment))
    at("LOCK TABLE \"public\".\"llm4s_collections\"") should be >= 0
    at("LOCK TABLE \"public\".\"vectors\"") should be > at("LOCK TABLE \"public\".\"llm4s_collections\"")
    val delete = at("DELETE FROM \"public\".\"vectors\"")
    delete should be > at("LOCK TABLE \"public\".\"vectors\"")
    sql(delete) should include("collection_id IS NOT NULL")
    sql(delete) should include("cardinality(readable_by) > 0")
    at("DROP COLUMN IF EXISTS collection_id") should be > delete
    at("DROP TABLE IF EXISTS \"public\".\"llm4s_collections\"") should be > delete
    at("DROP SEQUENCE IF EXISTS \"public\".\"llm4s_group_id_seq\"") should be > delete
    db.commits shouldBe 1
    db.rollbacks shouldBe 0
  }

  it should "inspect the table's columns in the schema its name resolves to, not current_schema()" in {
    val db = schema(relations = allResolved.map(_ + ("nspname" -> "data")))

    PgSchemaManager.dropSchema(db.dataSource.getConnection, "vectors") shouldBe Right(())

    val columns = db.writes.find { case (sql, _) => sql.contains("table_schema = ? AND table_name = ?") }.get
    columns._2 shouldBe Map(1 -> "data", 2 -> "vectors")
    db.statements.exists(_.contains("DELETE FROM \"data\".\"vectors\"")) shouldBe true
    db.statements.exists(_.contains("current_schema()")) shouldBe false
  }

  it should "refuse, changing nothing, when the table resolves to no table on the search_path" in {
    val db = schema(relations = allResolved.filterNot(_("name") == "vectors"))

    PgSchemaManager.dropSchema(db.dataSource.getConnection, "vectors").left.toOption.get.message should include(
      "resolves to no table"
    )
    db.statements.exists(s => s.contains("DELETE") || s.contains("DROP ") || s.contains("LOCK")) shouldBe false
    db.rollbacks shouldBe 1
  }

  it should "reset a table beside another permission table that holds no rows" in {
    val db = schema(other = Some(false))

    PgSchemaManager.dropSchema(db.dataSource.getConnection, "vectors") shouldBe Right(())
    db.statements.exists(_.contains("DROP TABLE IF EXISTS \"public\".\"llm4s_collections\"")) shouldBe true
  }

  it should "refuse, changing nothing, while another vectors table holds rows in the shared collections" in {
    val db = schema(other = Some(true))

    val result = PgSchemaManager.dropSchema(db.dataSource.getConnection, "vectors")

    result.left.toOption.get.message should include("public.other")
    db.statements.exists(_.contains("DELETE FROM")) shouldBe false
    db.statements.exists(_.contains("DROP ")) shouldBe false
    db.statements.exists(_.contains("ALTER TABLE")) shouldBe false
    db.rollbacks shouldBe 1
    db.commits shouldBe 0
  }

  it should "run in a savepoint inside a transaction the caller owns, leaving the commit to it" in {
    val ok = schema(autoCommit = false)
    PgSchemaManager.dropSchema(ok.dataSource.getConnection, "vectors") shouldBe Right(())
    (ok.savepoints, ok.savepointReleases, ok.savepointRollbacks) shouldBe ((1, 1, 0))
    (ok.commits, ok.rollbacks) shouldBe ((0, 0))

    val refused = schema(other = Some(true), autoCommit = false)
    PgSchemaManager.dropSchema(refused.dataSource.getConnection, "vectors").isLeft shouldBe true
    (refused.savepoints, refused.savepointReleases, refused.savepointRollbacks) shouldBe ((1, 0, 1))
    (refused.commits, refused.rollbacks) shouldBe ((0, 0))
  }

  it should "commit a transaction of its own when auto-commit is on, using no savepoint" in {
    val db = schema()
    PgSchemaManager.dropSchema(db.dataSource.getConnection, "vectors") shouldBe Right(())
    (db.commits, db.savepoints) shouldBe ((1, 0))
  }

  it should "reject an invalid table name before touching the connection" in {
    PgSchemaManager.dropSchema(null, "vectors; DROP TABLE users").left.toOption.get.message should include(
      "Invalid table name"
    )
  }

  private def collections(leaf: Option[Boolean], orphans: Long = 0, autoCommit: Boolean = true): ScriptedDb =
    new ScriptedDb(
      sql =>
        if (sql.contains("to_regclass(x.name)")) Seq(resolved("llm4s_collections"), resolved("vectors"))
        else if (sql.contains("FOR SHARE")) leaf.toSeq.map(l => Map("is_leaf" -> l))
        else if (sql.contains("SELECT id FROM llm4s_collections")) leaf.toSeq.map(_ => Map("id" -> 9))
        else if (sql.contains("COUNT(*)")) Seq(Map("count" -> orphans))
        else Seq.empty,
      autoCommit = autoCommit
    )

  "PgSchemaManager.migrateExistingVectors" should "keep each row's readable_by and skip rows ingest wrote (#2992)" in {
    val db = collections(leaf = Some(true))

    PgSchemaManager.migrateExistingVectors(db.dataSource.getConnection, "vectors", 9) shouldBe Right(1L)

    db.statements.exists(_.contains("FROM \"public\".\"llm4s_collections\" WHERE id = ? FOR SHARE")) shouldBe true
    val update = db.statements.find(_.contains("UPDATE \"public\".\"vectors\"")).get
    (update should not).include("readable_by = '{}'")
    update should include("COALESCE(readable_by, '{}')")
    update should include(s"NOT (${PgSchemaManager.OrphanedPermissionRow})")
    db.commits shouldBe 1
  }

  it should "refuse a collection that does not exist or is not a leaf, migrating nothing" in {
    Seq(None, Some(false)).foreach { leaf =>
      val db = collections(leaf)
      PgSchemaManager.migrateExistingVectors(db.dataSource.getConnection, "vectors", 9).isLeft shouldBe true
      db.statements.exists(_.contains("UPDATE ")) shouldBe false
      db.rollbacks shouldBe 1
    }
  }

  it should "use a savepoint, not commit, inside a transaction the caller owns" in {
    val db = collections(leaf = Some(true), autoCommit = false)
    PgSchemaManager.migrateExistingVectors(db.dataSource.getConnection, "vectors", 9) shouldBe Right(1L)
    (db.savepoints, db.savepointReleases, db.commits) shouldBe ((1, 1, 0))

    val refused = collections(leaf = None, autoCommit = false)
    PgSchemaManager.migrateExistingVectors(refused.dataSource.getConnection, "vectors", 9).isLeft shouldBe true
    (refused.savepointRollbacks, refused.rollbacks, refused.commits) shouldBe ((1, 0, 0))
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
