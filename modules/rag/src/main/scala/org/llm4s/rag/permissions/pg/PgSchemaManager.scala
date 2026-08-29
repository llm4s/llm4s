package org.llm4s.rag.permissions.pg

import org.llm4s.error.ProcessingError
import org.llm4s.types.Result

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
  }.toEither.left.map(e => ProcessingError("pg-schema-init", s"Failed to initialize schema: ${e.getMessage}"))

  /**
   * Extend an existing vectors table with permission columns.
   *
   * Adds:
   * - collection_id column (foreign key to llm4s_collections)
   * - readable_by column (array of principal IDs)
   * - Indexes for efficient filtering
   *
   * @param conn Database connection
   * @param tableName The name of the vectors table to extend
   * @return Success or error
   */
  def extendVectorsTable(conn: Connection, tableName: String): Result[Unit] = Try {
    Using.resource(conn.createStatement()) { stmt =>
      // Create vectors table if it doesn't exist (for standalone permission testing)
      // Uses vector without dimension for flexibility in tests
      stmt.execute(s"""
        CREATE TABLE IF NOT EXISTS $tableName (
          id TEXT PRIMARY KEY,
          content TEXT NOT NULL,
          embedding vector,
          embedding_dim INTEGER,
          metadata JSONB DEFAULT '{}'
        )
      """)

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

      ()
    }
  }.toEither.left.map(e => ProcessingError("pg-schema-extend", s"Failed to extend vectors table: ${e.getMessage}"))

  /**
   * Create a default public collection for migrating existing data.
   *
   * This creates a "default" collection that is:
   * - Public (empty queryable_by)
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
    ProcessingError("pg-schema-default", s"Failed to create default collection: ${e.getMessage}")
  )

  /**
   * Migrate existing vectors to the default collection.
   *
   * Updates all vectors that don't have a collection_id to use the default collection.
   *
   * @param conn Database connection
   * @param tableName The name of the vectors table
   * @param defaultCollectionId The ID of the default collection
   * @return Number of migrated vectors, or error
   */
  def migrateExistingVectors(conn: Connection, tableName: String, defaultCollectionId: Int): Result[Long] = Try {
    Using.resource(conn.prepareStatement(s"""
      UPDATE $tableName
      SET collection_id = ?, readable_by = '{}'
      WHERE collection_id IS NULL
    """)) { stmt =>
      stmt.setInt(1, defaultCollectionId)
      stmt.executeUpdate().toLong
    }
  }.toEither.left.map(e => ProcessingError("pg-schema-migrate", s"Failed to migrate existing vectors: ${e.getMessage}"))

  /**
   * Run the full migration: initialize schema, extend vectors, create default collection, migrate data.
   *
   * @param conn Database connection
   * @param tableName The name of the vectors table
   * @return Migration stats or error
   */
  def runFullMigration(conn: Connection, tableName: String): Result[MigrationStats] =
    for {
      _             <- initializeSchema(conn)
      _             <- extendVectorsTable(conn, tableName)
      defaultId     <- createDefaultCollection(conn)
      migratedCount <- migrateExistingVectors(conn, tableName, defaultId)
    } yield MigrationStats(
      tablesCreated = 2,
      indexesCreated = 5,
      defaultCollectionId = defaultId,
      vectorsMigrated = migratedCount
    )

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
  }.toEither.left.map(e => ProcessingError("pg-schema-check", s"Failed to check schema: ${e.getMessage}"))

  /**
   * Drop all permission-related tables and sequences.
   *
   * WARNING: This is destructive and will delete all permission data.
   * Only use for testing or complete reset.
   *
   * @param conn Database connection
   * @param tableName The name of the vectors table (to remove permission columns)
   * @return Success or error
   */
  def dropSchema(conn: Connection, tableName: String): Result[Unit] = Try {
    Using.resource(conn.createStatement()) { stmt =>
      // Remove columns from vectors table
      stmt.execute(s"""
        DO $$$$
        BEGIN
          IF EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_name = '$tableName' AND column_name = 'collection_id'
          ) THEN
            ALTER TABLE $tableName DROP COLUMN collection_id;
          END IF;
          IF EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_name = '$tableName' AND column_name = 'readable_by'
          ) THEN
            ALTER TABLE $tableName DROP COLUMN readable_by;
          END IF;
        END $$$$
      """)

      // Drop tables
      stmt.execute("DROP TABLE IF EXISTS llm4s_collections CASCADE")
      stmt.execute("DROP TABLE IF EXISTS llm4s_principals CASCADE")
      stmt.execute("DROP SEQUENCE IF EXISTS llm4s_group_id_seq")

      ()
    }
  }.toEither.left.map(e => ProcessingError("pg-schema-drop", s"Failed to drop schema: ${e.getMessage}"))
}

/**
 * Statistics from a schema migration.
 *
 * @param tablesCreated Number of tables created
 * @param indexesCreated Number of indexes created
 * @param defaultCollectionId ID of the default collection
 * @param vectorsMigrated Number of vectors migrated to the default collection
 */
final case class MigrationStats(
  tablesCreated: Int,
  indexesCreated: Int,
  defaultCollectionId: Int,
  vectorsMigrated: Long
)
