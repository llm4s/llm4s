package org.llm4s.vectorstore

/**
 * The one definition of the pgvector vectors table.
 *
 * `PgVectorStore` and `PgSearchIndex` (via `PgSchemaManager`) read and write the same table when a
 * RAG pipeline is built with `RAGConfig.withSearchIndex`. Each used to carry its own
 * `CREATE TABLE IF NOT EXISTS`, and they disagreed: the permission copy had no `created_at` and
 * made `content` `NOT NULL`. Whichever ran first fixed the table's shape, so after
 * `PgSearchIndex.initializeSchema()` the vector store's `created_at` index failed with
 * `column "created_at" does not exist`. Both now run [[statements]].
 *
 * Every statement is idempotent, and the upgrade steps bring a table created by the old
 * permission DDL into line:
 *  - `created_at` is added when missing. Existing rows take the time of the upgrade.
 *  - `content` loses its `NOT NULL`, since a [[VectorRecord]] may have no content.
 *
 * `embedding_dim` is left alone on such tables. The fresh definition makes it `NOT NULL`, but
 * adding that to an existing table would fail on any row that lacks it, and both writers always
 * set it.
 *
 * Each upgrade step checks the catalog first, so opening an already-current table takes no
 * `ACCESS EXCLUSIVE` lock. The table name is interpolated into SQL, so callers must validate it
 * with `SqlIdentifier.validate` first.
 */
private[llm4s] object PgVectorTableSchema {

  /** The statements that create or upgrade `tableName`, in execution order. */
  def statements(tableName: String): Seq[String] =
    Seq(
      "CREATE EXTENSION IF NOT EXISTS vector",
      createTable(tableName),
      addCreatedAt(tableName),
      dropContentNotNull(tableName),
      s"CREATE INDEX IF NOT EXISTS idx_${tableName}_dim ON $tableName(embedding_dim)",
      s"CREATE INDEX IF NOT EXISTS idx_${tableName}_created ON $tableName(created_at)",
      s"CREATE INDEX IF NOT EXISTS idx_${tableName}_metadata ON $tableName USING GIN(metadata)"
    )

  private[vectorstore] def createTable(tableName: String): String =
    s"""CREATE TABLE IF NOT EXISTS $tableName (
       |  id TEXT PRIMARY KEY,
       |  embedding vector,
       |  embedding_dim INTEGER NOT NULL,
       |  content TEXT,
       |  metadata JSONB DEFAULT '{}',
       |  created_at TIMESTAMPTZ DEFAULT NOW()
       |)""".stripMargin

  private[vectorstore] def addCreatedAt(tableName: String): String =
    whenColumn(
      tableName,
      "created_at",
      "NOT FOUND",
      s"ALTER TABLE $tableName ADD COLUMN created_at TIMESTAMPTZ DEFAULT NOW()"
    )

  private[vectorstore] def dropContentNotNull(tableName: String): String =
    whenColumn(
      tableName,
      "content",
      "FOUND AND is_not_null",
      s"ALTER TABLE $tableName ALTER COLUMN content DROP NOT NULL"
    )

  /**
   * A `DO` block that runs `alter` when `condition` holds for `column` of `tableName`.
   *
   * `condition` may use `FOUND` (the column exists) and `is_not_null`. The table is resolved with
   * `to_regclass`, so the lookup follows the search path and PostgreSQL's case folding exactly as
   * the unquoted name in `alter` does.
   */
  private def whenColumn(tableName: String, column: String, condition: String, alter: String): String =
    s"""DO $$$$
       |DECLARE
       |  is_not_null BOOLEAN;
       |BEGIN
       |  SELECT a.attnotnull INTO is_not_null
       |  FROM pg_attribute a
       |  WHERE a.attrelid = to_regclass('$tableName')
       |    AND a.attname = '$column'
       |    AND NOT a.attisdropped;
       |  IF $condition THEN
       |    $alter;
       |  END IF;
       |END $$$$""".stripMargin
}
