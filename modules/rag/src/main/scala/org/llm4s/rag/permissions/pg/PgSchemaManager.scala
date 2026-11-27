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
        val leaf = Using.resource(
          conn.prepareStatement("SELECT is_leaf FROM llm4s_collections WHERE id = ? FOR SHARE")
        ) { stmt =>
          stmt.setInt(1, collectionId)
          Using.resource(stmt.executeQuery())(rs => if (rs.next()) Some(rs.getBoolean("is_leaf")) else None)
        }
        leaf match {
          case None => Left(ProcessingError("pg-schema-migrate", s"Collection not found (id $collectionId)"))
          case Some(false) =>
            Left(ProcessingError("pg-schema-migrate", s"Cannot migrate into a non-leaf collection (id $collectionId)"))
          case Some(true) =>
            Using.resource(conn.prepareStatement(s"""
              UPDATE $tableName
              SET collection_id = ?, readable_by = COALESCE(readable_by, '{}')
              WHERE collection_id IS NULL AND NOT ($OrphanedPermissionRow)
            """)) { stmt =>
              stmt.setInt(1, collectionId)
              Right(stmt.executeUpdate().toLong)
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
    Using.resource(
      conn.prepareStatement(s"SELECT COUNT(*) FROM $tableName WHERE collection_id IS NULL AND $OrphanedPermissionRow")
    )(stmt => Using.resource(stmt.executeQuery())(rs => if (rs.next()) rs.getLong(1) else 0L))
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
   * The collection and principal tables are shared by every `PgSearchIndex` in the database schema. If another
   * vectors table still holds rows in a collection, the reset is refused and nothing changes: those rows would lose
   * their collection. Clear that index's collections first (`PgSearchIndex.clearCollection`).
   *
   * @param conn Database connection
   * @param tableName The name of the vectors table whose permission rows and columns are removed
   * @return Success or error
   */
  def dropSchema(conn: Connection, tableName: String): Result[Unit] =
    SqlIdentifier.validate(tableName, "pg-schema-drop").flatMap { _ =>
      inTransaction(conn, "pg-schema-drop") {
        if (exists(conn, "SELECT to_regclass('llm4s_collections') IS NOT NULL"))
          execute(conn, "LOCK TABLE llm4s_collections IN ACCESS EXCLUSIVE MODE")
        val columns = permissionColumns(conn).getOrElse(tableName.toLowerCase, Set.empty)
        if (columns.nonEmpty) execute(conn, s"LOCK TABLE $tableName IN ACCESS EXCLUSIVE MODE")

        val others = permissionColumns(conn)
          .collect {
            case (table, cols) if table != tableName.toLowerCase && cols.contains("collection_id") => table
          }
          .toSeq
          .sorted
        val holding = others
          .filter(t => exists(conn, s"SELECT EXISTS (SELECT 1 FROM ${quoted(t)} WHERE collection_id IS NOT NULL)"))

        if (holding.nonEmpty)
          Left(
            ProcessingError(
              "pg-schema-drop",
              s"Refusing to drop the permission schema: ${holding.mkString(", ")} still hold rows in its collections, " +
                "which would be left with no collection and no permissions. Clear those indexes' collections first."
            )
          )
        else {
          val owned =
            if (columns == Set("collection_id", "readable_by"))
              Some("collection_id IS NOT NULL OR cardinality(readable_by) > 0")
            else if (columns.contains("collection_id")) Some("collection_id IS NOT NULL")
            else if (columns.contains("readable_by")) Some("cardinality(readable_by) > 0")
            else None
          owned.foreach(where => execute(conn, s"DELETE FROM $tableName WHERE $where"))
          if (columns.nonEmpty)
            execute(
              conn,
              s"ALTER TABLE $tableName DROP COLUMN IF EXISTS collection_id, DROP COLUMN IF EXISTS readable_by"
            )
          execute(conn, s"DROP INDEX IF EXISTS idx_${tableName}_content_fts")
          execute(conn, "DROP TABLE IF EXISTS llm4s_collections CASCADE")
          execute(conn, "DROP TABLE IF EXISTS llm4s_principals CASCADE")
          execute(conn, "DROP SEQUENCE IF EXISTS llm4s_group_id_seq")
          Right(())
        }
      }
    }

  /** The tables of the current schema with a `collection_id` or `readable_by` column, and which of the two. */
  private def permissionColumns(conn: Connection): Map[String, Set[String]] =
    Using.resource(conn.prepareStatement("""
      SELECT table_name, column_name FROM information_schema.columns
      WHERE table_schema = current_schema() AND column_name IN ('collection_id', 'readable_by')
    """)) { stmt =>
      Using.resource(stmt.executeQuery()) { rs =>
        val found = scala.collection.mutable.ListBuffer.empty[(String, String)]
        while (rs.next()) found += ((rs.getString("table_name"), rs.getString("column_name")))
        found.toList.groupMap(_._1)(_._2).view.mapValues(_.toSet).toMap
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
   * Runs `body` in a transaction on `conn`: committed if it returns `Right`, rolled back if it returns `Left` or
   * throws. The connection's auto-commit mode is restored afterwards.
   */
  private def inTransaction[A](conn: Connection, operation: String)(body: => Result[A]): Result[A] =
    Try {
      val original = conn.getAutoCommit
      conn.setAutoCommit(false)
      val outcome = Try(body).flatMap {
        case right @ Right(_) => Try(conn.commit()).map(_ => right)
        case left @ Left(_)   => Try(conn.rollback()).map(_ => left)
      }
      outcome.failed.foreach(_ => Try(conn.rollback()))
      Try(conn.setAutoCommit(original))
      outcome.get
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
