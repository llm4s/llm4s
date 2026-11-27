package org.llm4s.rag.permissions.pg

import org.llm4s.error.ProcessingError
import org.llm4s.rag.permissions.CollectionPath
import org.llm4s.types.Result
import org.llm4s.util.{ SqlIdentifier, Redaction }
import org.llm4s.vectorstore.PgVectorTableSchema

import java.sql.Connection
import scala.util.{ Try, Using }

/**
 * Manages the PostgreSQL schema for permission-based RAG.
 *
 * Creates and maintains the database tables required for:
 * - Principal ID mapping (users and groups to integers)
 * - Collection hierarchy with permissions
 * - Extended vectors table with collection_id and readable_by
 */
object PgSchemaManager {

  /**
   * Initialize the permission schema in the database.
   *
   * This creates:
   * - llm4s_principals table for user/group ID mapping
   * - llm4s_group_id_seq sequence for negative group IDs
   * - llm4s_collections table for the collection hierarchy
   * - Indexes for efficient querying
   *
   * @param conn Database connection
   * @return Success or error
   */
  def initializeSchema(conn: Connection): Result[Unit] = Try {
    Using.resource(conn.createStatement()) { stmt =>
      // Enable pgvector extension if available (required for vector type)
      stmt.execute("CREATE EXTENSION IF NOT EXISTS vector")

      // Create principals table for user/group mapping
      stmt.execute("""
        CREATE TABLE IF NOT EXISTS llm4s_principals (
          id SERIAL PRIMARY KEY,
          external_id TEXT UNIQUE NOT NULL,
          principal_type TEXT NOT NULL CHECK (principal_type IN ('user', 'group')),
          created_at TIMESTAMPTZ DEFAULT NOW()
        )
      """)

      // Create sequence for negative group IDs
      stmt.execute("""
        DO $$
        BEGIN
          IF NOT EXISTS (SELECT 1 FROM pg_sequences WHERE schemaname = 'public' AND sequencename = 'llm4s_group_id_seq') THEN
            CREATE SEQUENCE llm4s_group_id_seq START -1 INCREMENT -1;
          END IF;
        END $$
      """)

      // Create collections table
      stmt.execute("""
        CREATE TABLE IF NOT EXISTS llm4s_collections (
          id SERIAL PRIMARY KEY,
          path TEXT UNIQUE NOT NULL,
          parent_path TEXT,
          queryable_by INTEGER[] DEFAULT '{}',
          is_leaf BOOLEAN DEFAULT TRUE,
          metadata JSONB DEFAULT '{}',
          created_at TIMESTAMPTZ DEFAULT NOW(),
          updated_at TIMESTAMPTZ DEFAULT NOW(),
          CONSTRAINT valid_path CHECK (path ~ '^[a-zA-Z0-9_-]+(/[a-zA-Z0-9_-]+)*$')
        )
      """)

      // Create indexes for collections
      stmt.execute("""
        CREATE INDEX IF NOT EXISTS idx_llm4s_collections_parent
        ON llm4s_collections(parent_path)
      """)

      stmt.execute("""
        CREATE INDEX IF NOT EXISTS idx_llm4s_collections_path_pattern
        ON llm4s_collections(path text_pattern_ops)
      """)

      stmt.execute("""
        CREATE INDEX IF NOT EXISTS idx_llm4s_collections_queryable
        ON llm4s_collections USING GIN(queryable_by)
      """)

      ()
    }
  }.toEither.left.map(e =>
    ProcessingError("pg-schema-init", s"Failed to initialize schema: ${Redaction.scrubKnown(e.getMessage)}")
  )

  /**
   * Create or upgrade the vectors table and add the permission columns to it.
   *
   * The table itself is the one `PgVectorStore` uses - both run
   * `org.llm4s.vectorstore.PgVectorTableSchema`, so a table created here can be opened by a
   * `PgVectorStore` and vice versa. That also upgrades a table this method created before the
   * two definitions were unified (see `PgVectorTableSchema`).
   *
   * Adds:
   * - collection_id column (foreign key to llm4s_collections)
   * - readable_by column (array of principal IDs)
   * - Indexes for efficient filtering
   * - A full-text (GIN) index on `content`, for `PgSearchIndex.keywordQuery`
   *
   * @param conn Database connection
   * @param tableName The name of the vectors table to extend; must be a valid SQL identifier
   * @return Success or error
   */
  def extendVectorsTable(conn: Connection, tableName: String): Result[Unit] =
    SqlIdentifier.validate(tableName, "pg-schema-extend").flatMap(_ => extendValidatedVectorsTable(conn, tableName))

  private def extendValidatedVectorsTable(conn: Connection, tableName: String): Result[Unit] = Try {
    Using.resource(conn.createStatement()) { stmt =>
      PgVectorTableSchema.statements(tableName).foreach(sql => stmt.execute(sql))

      // Add collection_id column if not exists
      stmt.execute(s"""
        DO $$$$
        BEGIN
          IF NOT EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_name = '$tableName' AND column_name = 'collection_id'
          ) THEN
            ALTER TABLE $tableName ADD COLUMN collection_id INTEGER;
          END IF;
        END $$$$
      """)

      // Add readable_by column if not exists
      stmt.execute(s"""
        DO $$$$
        BEGIN
          IF NOT EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_name = '$tableName' AND column_name = 'readable_by'
          ) THEN
            ALTER TABLE $tableName ADD COLUMN readable_by INTEGER[] DEFAULT '{}';
          END IF;
        END $$$$
      """)

      // Create indexes
      stmt.execute(s"""
        CREATE INDEX IF NOT EXISTS idx_${tableName}_collection
        ON $tableName(collection_id)
      """)

      stmt.execute(s"""
        CREATE INDEX IF NOT EXISTS idx_${tableName}_readable_by
        ON $tableName USING GIN(readable_by)
      """)

      // Full-text index for PgSearchIndex.keywordQuery, on exactly the expression it matches
      stmt.execute(s"""
        CREATE INDEX IF NOT EXISTS idx_${tableName}_content_fts
        ON $tableName USING GIN(${PgSearchIndex.ContentTsVector})
      """)

      ()
    }
  }.toEither.left.map(e =>
    ProcessingError("pg-schema-extend", s"Failed to extend vectors table: ${Redaction.scrubKnown(e.getMessage)}")
  )

  /**
   * Create a public collection named `default`, for [[runFullMigration]] to migrate existing data into.
   *
   * This creates a "default" collection that is:
   * - Public (empty queryable_by): everything migrated into it is readable by everyone, anonymous users included
   * - A leaf collection (can contain documents)
   *
   * @param conn Database connection
   * @return The ID of the default collection, or error
   */
  def createDefaultCollection(conn: Connection): Result[Int] = Try {
    Using.resource(conn.prepareStatement("""
      INSERT INTO llm4s_collections (path, parent_path, queryable_by, is_leaf, metadata)
      VALUES ('default', NULL, '{}', TRUE, '{"description": "Default collection for migrated data"}')
      ON CONFLICT (path) DO UPDATE SET updated_at = NOW()
      RETURNING id
    """)) { stmt =>
      Using.resource(stmt.executeQuery()) { rs =>
        rs.next()
        rs.getInt("id")
      }
    }
  }.toEither.left.map(e =>
    ProcessingError("pg-schema-default", s"Failed to create default collection: ${Redaction.scrubKnown(e.getMessage)}")
  )

  /**
   * Rows `PgSearchIndex.ingest` wrote (a `coll-<id>-` id and a `docId`) that no longer belong to a collection: what an
   * earlier `dropSchema`, before #2992, kept after dropping their permissions. Nothing says who could read them, so
   * the migration never moves them into a collection.
   */
  private[pg] val OrphanedPermissionRow =
    "(metadata->>'docId') IS NOT NULL AND id ~ '^coll-[0-9]+-.*-chunk-[0-9]+$'"

  /**
   * Migrate existing vectors into the collection `collectionId`, which the caller names and must be a leaf.
   *
   * Moves every row with no collection into it, keeping its `readable_by`. A row `PgSearchIndex.ingest` wrote that
   * lost its collection (an earlier `dropSchema` kept it, without its permissions) is skipped: it stays in no
   * collection, so no permission query returns it. [[runFullMigration]] reports how many were skipped.
   *
   * @param conn Database connection
   * @param tableName The name of the vectors table
   * @param collectionId The ID of the collection to migrate into: its readers can read every migrated row
   * @return Number of migrated vectors, or error
   */
  def migrateExistingVectors(conn: Connection, tableName: String, collectionId: Int): Result[Long] =
    SqlIdentifier.validate(tableName, "pg-schema-migrate").flatMap { _ =>
      inTransaction(conn, "pg-schema-migrate") {
        val relations = resolve(conn, Seq(CollectionsTable, tableName))
        (relations.get(CollectionsTable), relations.get(tableName)) match {
          case (None, _) =>
            Left(ProcessingError("pg-schema-migrate", s"$CollectionsTable resolves to no table on the search_path"))
          case (_, None) =>
            Left(ProcessingError("pg-schema-migrate", s"$tableName resolves to no table on the search_path"))
          case (Some(collections), Some(table)) =>
            val leaf = Using.resource(
              conn.prepareStatement(s"SELECT is_leaf FROM ${collections.sql} WHERE id = ? FOR SHARE")
            ) { stmt =>
              stmt.setInt(1, collectionId)
              Using.resource(stmt.executeQuery())(rs => if (rs.next()) Some(rs.getBoolean("is_leaf")) else None)
            }
            leaf match {
              case None => Left(ProcessingError("pg-schema-migrate", s"Collection not found (id $collectionId)"))
              case Some(false) =>
                Left(
                  ProcessingError("pg-schema-migrate", s"Cannot migrate into a non-leaf collection (id $collectionId)")
                )
              case Some(true) =>
                Using.resource(conn.prepareStatement(s"""
                  UPDATE ${table.sql}
                  SET collection_id = ?, readable_by = COALESCE(readable_by, '{}')
                  WHERE collection_id IS NULL AND NOT ($OrphanedPermissionRow)
                """)) { stmt =>
                  stmt.setInt(1, collectionId)
                  Right(stmt.executeUpdate().toLong)
                }
            }
        }
      }
    }

  /**
   * Run the full migration into the collection `into`: initialize the schema, extend the vectors table, and move every
   * row with no collection into `into` ([[migrateExistingVectors]]).
   *
   * `into` must exist and be a leaf, so the caller decides who can read the migrated rows: create it first, after
   * [[initializeSchema]] (`PgSearchIndex.collections.create`, or [[createDefaultCollection]] for a public `default`).
   *
   * @param conn Database connection
   * @param tableName The name of the vectors table
   * @param into The collection to migrate the existing rows into
   * @return Migration stats or error
   */
  def runFullMigration(conn: Connection, tableName: String, into: CollectionPath): Result[MigrationStats] =
    for {
      _            <- initializeSchema(conn)
      _            <- extendVectorsTable(conn, tableName)
      collectionId <- collectionId(conn, into)
      migrated     <- migrateExistingVectors(conn, tableName, collectionId)
      skipped      <- countOrphanedRows(conn, tableName)
    } yield MigrationStats(
      tablesCreated = 2,
      indexesCreated = 5,
      collectionId = collectionId,
      vectorsMigrated = migrated,
      vectorsSkipped = skipped
    )

  private def collectionId(conn: Connection, path: CollectionPath): Result[Int] =
    Try {
      Using.resource(conn.prepareStatement("SELECT id FROM llm4s_collections WHERE path = ?")) { stmt =>
        stmt.setString(1, path.value)
        Using.resource(stmt.executeQuery())(rs => if (rs.next()) Some(rs.getInt("id")) else None)
      }
    }.toEither.left
      .map(e => ProcessingError("pg-schema-migrate", Redaction.scrubKnown(e.getMessage)))
      .flatMap(
        _.toRight(
          ProcessingError(
            "pg-schema-migrate",
            s"Collection not found: ${path.value}. Create it, with the readers the migrated rows should have, " +
              "before migrating into it."
          )
        )
      )

  private def countOrphanedRows(conn: Connection, tableName: String): Result[Long] = Try {
    resolve(conn, Seq(tableName)).get(tableName).fold(0L) { table =>
      Using.resource(
        conn.prepareStatement(
          s"SELECT COUNT(*) FROM ${table.sql} WHERE collection_id IS NULL AND $OrphanedPermissionRow"
        )
      )(stmt => Using.resource(stmt.executeQuery())(rs => if (rs.next()) rs.getLong(1) else 0L))
    }
  }.toEither.left.map(e => ProcessingError("pg-schema-migrate", Redaction.scrubKnown(e.getMessage)))

  /**
   * Check if the permission schema is already initialized.
   *
   * @param conn Database connection
   * @return True if schema exists, false otherwise
   */
  def isSchemaInitialized(conn: Connection): Result[Boolean] = Try {
    Using.resource(conn.prepareStatement("""
      SELECT EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_name = 'llm4s_principals'
      ) AND EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_name = 'llm4s_collections'
      )
    """)) { stmt =>
      Using.resource(stmt.executeQuery()) { rs =>
        rs.next()
        rs.getBoolean(1)
      }
    }
  }.toEither.left.map(e =>
    ProcessingError("pg-schema-check", s"Failed to check schema: ${Redaction.scrubKnown(e.getMessage)}")
  )

  /**
   * Reset the permission schema: delete every row of `tableName` that belongs to a collection, then drop the
   * permission columns, the collection and principal tables and the group sequence, in one transaction.
   *
   * The rows go with the tables because nothing else records who may read them (#2992): kept, a later
   * [[runFullMigration]] would hand them to whichever collection it is given. Rows with no collection, written by a
   * plain `PgVectorStore` on the same table, are kept. The collection table and the vectors table are locked first,
   * so an ingest running meanwhile either finishes before the reset or fails after it.
   *
   * Every name is resolved as the unqualified SQL of an index resolves it, through `search_path`, and the reset then
   * works on those schema-qualified tables. It refuses, changing nothing, if `tableName` resolves to no table.
   *
   * The collection and principal tables are shared by every `PgSearchIndex` that resolves them. If another vectors
   * table (in a schema on `search_path`, or the collection table's) still holds rows in a collection, the reset is
   * refused and nothing changes: those rows would lose their collection. Clear that index's collections first
   * (`PgSearchIndex.clearCollection`).
   *
   * On a connection already in a transaction (auto-commit off), the reset runs inside a savepoint and leaves the
   * commit to the caller.
   *
   * @param conn Database connection
   * @param tableName The name of the vectors table whose permission rows and columns are removed
   * @return Success or error
   */
  def dropSchema(conn: Connection, tableName: String): Result[Unit] =
    SqlIdentifier.validate(tableName, "pg-schema-drop").flatMap { _ =>
      inTransaction(conn, "pg-schema-drop") {
        val relations = resolve(conn, Seq(CollectionsTable, PrincipalsTable, GroupSequence, tableName))
        relations.get(tableName) match {
          case None =>
            Left(
              ProcessingError(
                "pg-schema-drop",
                s"Refusing to drop the permission schema: $tableName resolves to no table on the search_path, so " +
                  "its rows in the collections cannot be deleted first."
              )
            )
          case Some(table) =>
            val collections = relations.get(CollectionsTable)
            collections.foreach(c => execute(conn, s"LOCK TABLE ${c.sql} IN ACCESS EXCLUSIVE MODE"))
            val columns = permissionColumns(conn, table)
            if (columns.nonEmpty) execute(conn, s"LOCK TABLE ${table.sql} IN ACCESS EXCLUSIVE MODE")

            val holding = otherPermissionTables(conn, table, collections.getOrElse(table).schema)
              .filter(r => exists(conn, s"SELECT EXISTS (SELECT 1 FROM ${r.sql} WHERE collection_id IS NOT NULL)"))

            if (holding.nonEmpty)
              Left(
                ProcessingError(
                  "pg-schema-drop",
                  s"Refusing to drop the permission schema: ${holding.map(_.display).mkString(", ")} still hold rows " +
                    "in its collections, which would be left with no collection and no permissions. Clear those " +
                    "indexes' collections first."
                )
              )
            else {
              val owned =
                if (columns == Set("collection_id", "readable_by"))
                  Some("collection_id IS NOT NULL OR cardinality(readable_by) > 0")
                else if (columns.contains("collection_id")) Some("collection_id IS NOT NULL")
                else if (columns.contains("readable_by")) Some("cardinality(readable_by) > 0")
                else None
              owned.foreach(where => execute(conn, s"DELETE FROM ${table.sql} WHERE $where"))
              if (columns.nonEmpty)
                execute(
                  conn,
                  s"ALTER TABLE ${table.sql} DROP COLUMN IF EXISTS collection_id, DROP COLUMN IF EXISTS readable_by"
                )
              execute(conn, s"DROP INDEX IF EXISTS ${quoted(table.schema)}.${quoted(s"idx_${table.name}_content_fts")}")
              collections.foreach(c => execute(conn, s"DROP TABLE IF EXISTS ${c.sql} CASCADE"))
              relations.get(PrincipalsTable).foreach(p => execute(conn, s"DROP TABLE IF EXISTS ${p.sql} CASCADE"))
              relations.get(GroupSequence).foreach(s => execute(conn, s"DROP SEQUENCE IF EXISTS ${s.sql}"))
              Right(())
            }
        }
      }
    }

  private val CollectionsTable = "llm4s_collections"
  private val PrincipalsTable  = "llm4s_principals"
  private val GroupSequence    = "llm4s_group_id_seq"

  /** A relation an unqualified name resolved to: its schema and its name, both as stored in the catalog. */
  final private case class Relation(schema: String, name: String) {
    def sql: String     = s"${quoted(schema)}.${quoted(name)}"
    def display: String = s"$schema.$name"
  }

  /**
   * What each of `names` resolves to through `search_path`, as the unqualified SQL of an index resolves it
   * (`to_regclass`). A name that resolves to nothing is absent from the result. Each name is a validated SQL
   * identifier or one of this object's constants, so it is safe to inline.
   */
  private def resolve(conn: Connection, names: Seq[String]): Map[String, Relation] = {
    val values = names.map(n => s"('$n')").mkString(", ")
    Using.resource(conn.prepareStatement(s"""
      SELECT x.name, n.nspname, c.relname
      FROM (VALUES $values) AS x(name)
      JOIN pg_class c ON c.oid = to_regclass(x.name)
      JOIN pg_namespace n ON n.oid = c.relnamespace
    """)) { stmt =>
      Using.resource(stmt.executeQuery()) { rs =>
        val found = scala.collection.mutable.Map.empty[String, Relation]
        while (rs.next()) found(rs.getString("name")) = Relation(rs.getString("nspname"), rs.getString("relname"))
        found.toMap
      }
    }
  }

  /** Which of `collection_id` and `readable_by` the resolved `table` has. */
  private def permissionColumns(conn: Connection, table: Relation): Set[String] =
    Using.resource(conn.prepareStatement("""
      SELECT column_name FROM information_schema.columns
      WHERE table_schema = ? AND table_name = ? AND column_name IN ('collection_id', 'readable_by')
    """)) { stmt =>
      stmt.setString(1, table.schema)
      stmt.setString(2, table.name)
      Using.resource(stmt.executeQuery()) { rs =>
        val found = scala.collection.mutable.Set.empty[String]
        while (rs.next()) found += rs.getString("column_name")
        found.toSet
      }
    }

  /**
   * The tables other than `table` with a `collection_id` column, in a schema on `search_path` or in
   * `collectionsSchema`: the ones whose rows may be in the collections a reset drops.
   */
  private def otherPermissionTables(conn: Connection, table: Relation, collectionsSchema: String): Seq[Relation] =
    Using.resource(conn.prepareStatement("""
      SELECT table_schema, table_name FROM information_schema.columns
      WHERE column_name = 'collection_id'
        AND (table_schema = ANY (current_schemas(false)) OR table_schema = ?)
    """)) { stmt =>
      stmt.setString(1, collectionsSchema)
      Using.resource(stmt.executeQuery()) { rs =>
        val found = scala.collection.mutable.ListBuffer.empty[Relation]
        while (rs.next()) found += Relation(rs.getString("table_schema"), rs.getString("table_name"))
        found.toList.distinct.filterNot(_ == table).sortBy(_.display)
      }
    }

  private def quoted(identifier: String): String = "\"" + identifier.replace("\"", "\"\"") + "\""

  private def exists(conn: Connection, sql: String): Boolean =
    Using.resource(conn.prepareStatement(sql))(stmt =>
      Using.resource(stmt.executeQuery())(rs => rs.next() && rs.getBoolean(1))
    )

  private def execute(conn: Connection, sql: String): Unit =
    Using.resource(conn.prepareStatement(sql))(stmt => stmt.execute(): Unit)

  /**
   * Runs `body` atomically on `conn`. With auto-commit on, in a transaction of its own: committed if `body` returns
   * `Right`, rolled back if it returns `Left` or throws, and auto-commit restored. With auto-commit off the caller
   * owns the transaction, so `body` runs inside a savepoint instead: released on `Right`, rolled back to on `Left` or
   * a throw, and the commit is left to the caller.
   */
  private def inTransaction[A](conn: Connection, operation: String)(body: => Result[A]): Result[A] =
    Try {
      if (conn.getAutoCommit) {
        conn.setAutoCommit(false)
        val outcome = Try(body).flatMap {
          case right @ Right(_) => Try(conn.commit()).map(_ => right)
          case left @ Left(_)   => Try(conn.rollback()).map(_ => left)
        }
        outcome.failed.foreach(_ => Try(conn.rollback()))
        Try(conn.setAutoCommit(true))
        outcome.get
      } else {
        val savepoint = conn.setSavepoint()
        val outcome = Try(body).flatMap {
          case right @ Right(_) => Try(conn.releaseSavepoint(savepoint)).map(_ => right)
          case left @ Left(_)   => Try(conn.rollback(savepoint)).map(_ => left)
        }
        outcome.failed.foreach(_ => Try(conn.rollback(savepoint)))
        outcome.get
      }
    }.toEither.left
      .map(e => ProcessingError(operation, Redaction.scrubKnown(e.getMessage)))
      .flatMap(identity)
}

/**
 * Statistics from a schema migration.
 *
 * @param tablesCreated Number of tables created
 * @param indexesCreated Number of indexes created
 * @param collectionId ID of the collection the vectors were migrated into
 * @param vectorsMigrated Number of vectors migrated into it
 * @param vectorsSkipped Number of rows `PgSearchIndex.ingest` wrote that had lost their collection, left out of it
 */
final case class MigrationStats(
  tablesCreated: Int,
  indexesCreated: Int,
  collectionId: Int,
  vectorsMigrated: Long,
  vectorsSkipped: Long
)
