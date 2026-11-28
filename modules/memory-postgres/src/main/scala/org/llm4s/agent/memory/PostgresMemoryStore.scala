package org.llm4s.agent.memory

import org.llm4s.util.Redaction
import org.llm4s.types.Result
import org.llm4s.error.{ LLMError, NotFoundError, OptimisticLockFailure, ProcessingError, ValidationError }
import org.llm4s.security.Secret
import org.llm4s.vectorstore.{ PostgresVectorHelpers, SearchScores }
import com.zaxxer.hikari.{ HikariConfig, HikariDataSource }
import ujson.{ Obj, Str, read, write }

import java.sql.{ BatchUpdateException, Connection, PreparedStatement, ResultSet, Timestamp }
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource
import scala.collection.mutable
import scala.util.{ Try, Using }

/**
 * PostgreSQL implementation of MemoryStore.
 * Persists agent memories to a Postgres table using JDBC.
 *
 * Built either by [[PostgresMemoryStore.apply]], which creates a connection pool and closes it on [[close]], or by
 * [[PostgresMemoryStore.fromDataSource]], over a `javax.sql.DataSource` the caller owns and keeps open.
 *
 * A store belongs to one `tenant`, set when it is built (#3032): every statement reads and writes only that tenant's
 * rows of the table - recall, search, count, delete, `clear()`, bounds eviction and the applied writes - so many
 * tenants share one table without seeing each other. `None` is the rows of no tenant, among them every row written
 * before the column existed; opening the store adds the column to an older table.
 *
 * Vector search scores `(1 + cos) / 2`, as every memory store does (`org.llm4s.vectorstore.SearchScores`), and compares
 * the query only with embeddings of its dimension that are not all zeros. With `embeddingDimensions`, the column is
 * typed `vector(n)` and, up to 2000 dimensions, carries an HNSW index for cosine distance.
 */
final class PostgresMemoryStore private[memory] (
  private val dataSource: DataSource,
  val tableName: String,
  private val embeddingService: Option[EmbeddingService],
  private[memory] val ownsDataSource: Boolean = true,
  val tenant: Option[String] = None,
  val embeddingDimensions: Option[Int] = None
) extends TransactionalMemoryStore
    with AutoCloseable {

  import PostgresMemoryStore.SqlParam

  private val appliedTable = s"${tableName}_applied"
  private val fencesTable  = s"${tableName}_fences"

  /** The `tenant` column's value for this store's rows: the tenant, or `''` for a store without one. */
  private val tenantKey: String = tenant.getOrElse("")

  /** `filter`'s narrowing, restricted to this store's tenant: every query of the memory table goes through it. */
  private def scoped(filter: MemoryFilter): FilterSupport.Narrowing = {
    val plan   = PostgresMemoryStore.narrowing(filter)
    val clause = plan.sql.fold("tenant = ?")(s => s"(${s.clause}) AND tenant = ?")
    FilterSupport.Narrowing(Some(FilterSupport.Sql(clause, plan.params :+ SqlParam.PString(tenantKey))), plan.exact)
  }

  /**
   * The cluster, database, table and tenant this store reads and writes: two stores over one table and one tenant have
   * one identity, whichever data source, address or instance reaches them, so a resolver that hands two tenants the
   * same rows is caught.
   *
   * The cluster is its `system_identifier` (`pg_control_system()`), which does not depend on the address a connection
   * came in on (`127.0.0.1`, `::1`, a host name, a pooler). PostgreSQL grants that function to PUBLIC by default
   * (checked on 14, 16 and 17). Where the store's role may not execute it, the identity is a `Left` naming the missing
   * privilege - fail closed, since an identity without the cluster would let two roles see one table as two - and
   * `SemanticMemoryMiddleware` refuses the turn: `GRANT EXECUTE ON FUNCTION pg_control_system() TO <role>`. The table is
   * its schema-qualified name, as `search_path` resolves it.
   */
  override def storageIdentity: Result[Option[String]] =
    attempt("Failed to read the store's identity") {
      withConnection { conn =>
        val cluster = Try(
          runQuery(conn, "SELECT system_identifier::text FROM pg_control_system()", Seq.empty, stream = false)(rows =>
            if (rows.hasNext) Option(rows.next().getString(1)) else None
          )
        ).toEither.left.map(e => Redaction.scrubKnown(e.getMessage)).flatMap(_.toRight("no system identifier"))
        cluster.left
          .map(reason =>
            ProcessingError(
              "postgres-memory-store",
              "Failed to read the store's identity: the cluster's system identifier needs EXECUTE on " +
                s"pg_control_system() (GRANT EXECUTE ON FUNCTION pg_control_system() TO <role>): $reason"
            )
          )
          .flatMap(cluster =>
            runQuery(
              conn,
              "SELECT current_database(), n.nspname, c.relname FROM pg_class c " +
                "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE c.oid = to_regclass(?)",
              Seq(SqlParam.PString(tableName)),
              stream = false
            ) { rows =>
              if (!rows.hasNext)
                Left(
                  ProcessingError("postgres-memory-store", s"Failed to read the store's identity: no table $tableName")
                )
              else {
                val rs = rows.next()
                Right(
                  Some(
                    s"postgresql://$cluster/${rs.getString(1)}/${rs.getString(2)}.${rs.getString(3)}" +
                      s"?tenant=$tenantKey"
                  )
                )
              }
            }
          )
      }
    }

  private[memory] def initializeSchema(): Unit =
    withConnection { conn =>
      Using.resource(conn.createStatement()) { stmt =>
        // 1. Enable pgvector extension
        stmt.execute("CREATE EXTENSION IF NOT EXISTS vector")

        // 2. Create table with proper VECTOR type
        stmt.execute(s"""
          CREATE TABLE IF NOT EXISTS $tableName (
            tenant TEXT NOT NULL DEFAULT '',
            id TEXT NOT NULL,
            content TEXT NOT NULL,
            memory_type TEXT NOT NULL,
            metadata JSONB DEFAULT '{}',
            created_at TIMESTAMPTZ NOT NULL,
            importance DOUBLE PRECISION,
            embedding ${embeddingDimensions.fold("vector")(n => s"vector($n)")},
            version BIGINT NOT NULL DEFAULT 0,
            PRIMARY KEY (tenant, id)
          )
        """)

        // 3. Indexes for common access patterns
        stmt.execute(s"CREATE INDEX IF NOT EXISTS idx_${tableName}_type ON $tableName(memory_type)")
        stmt.execute(s"CREATE INDEX IF NOT EXISTS idx_${tableName}_created ON $tableName(created_at)")
        stmt.execute(s"CREATE INDEX IF NOT EXISTS idx_${tableName}_metadata ON $tableName USING GIN(metadata)")
        stmt.execute(
          s"CREATE INDEX IF NOT EXISTS idx_${tableName}_conversation ON $tableName ((metadata->>'conversation_id'))"
        )

        // 4. The applied writes of `TransactionalMemoryStore`, and the newest fencing token per scope
        stmt.execute(
          s"CREATE TABLE IF NOT EXISTS $appliedTable (tenant TEXT NOT NULL DEFAULT '', key TEXT NOT NULL, " +
            "scope TEXT NOT NULL, outcome TEXT NOT NULL, PRIMARY KEY (tenant, key))"
        )
        stmt.execute(
          s"CREATE TABLE IF NOT EXISTS $fencesTable (tenant TEXT NOT NULL DEFAULT '', scope TEXT NOT NULL, " +
            "token BIGINT NOT NULL, PRIMARY KEY (tenant, scope))"
        )

        // 5. A table created before tenants (#3032) gains the column, its rows the tenant '', and a key per tenant;
        //    an up-to-date table is only checked, in the catalog, and never locked
        Seq(tableName -> "id", appliedTable -> "key", fencesTable -> "scope").foreach { case (table, key) =>
          stmt.execute(PostgresMemoryStore.tenantKeyMigration(table, key))
        }

        // 6. A declared dimension types the column - an older, untyped one too, if every embedding has it - and
        //    indexes it for cosine distance (#3037)
        embeddingDimensions.foreach { n =>
          stmt.execute(PostgresMemoryStore.declareDimension(tableName, n))
          if (n <= PostgresMemoryStore.MaxIndexedDimensions)
            stmt.execute(
              s"CREATE INDEX IF NOT EXISTS idx_${tableName}_embedding ON $tableName " +
                "USING hnsw (embedding vector_cosine_ops)"
            )
        }
      }
      ()
    }

  /** Stores `add`, then deletes `remove`, in one transaction. */
  override def replace(add: Seq[Memory], remove: Seq[MemoryId]): Result[MemoryStore] =
    embedMissing(add).flatMap { adds =>
      Try {
        withConnection { conn =>
          inTransaction(conn) {
            writeAll(conn, adds)
            deleteIds(conn, remove)
          }
        }
        this
      }.toEither.left.map(e =>
        ProcessingError(
          "postgres-memory-store",
          s"Failed to replace memories: ${PostgresMemoryStore.writeFailure(e)}",
          cause = Some(PostgresMemoryStore.writeCause(e))
        )
      )
    }

  override def applyAtomically(
    key: AppliedWriteKey,
    token: Long,
    add: Seq[Memory],
    remove: Seq[MemoryId],
    bounds: MemoryBounds
  ): Result[(TransactionalMemoryStore, AppliedWrite)] =
    // A key already applied replays its recorded outcome without asking the embedding service (which may be down):
    // the transaction reads the record again, under the lock. Otherwise the memories are embedded before the
    // transaction, so no embedding request runs while the table's advisory lock is held.
    alreadyApplied(key).flatMap { applied =>
      if (applied) applyEmbedded(key, token, add, remove, bounds)
      else embedMissing(add).flatMap(embedded => applyEmbedded(key, token, embedded, remove, bounds))
    }

  /** True if `key` has a recorded outcome; read without the lock, so only to skip work the transaction then skips. */
  private def alreadyApplied(key: AppliedWriteKey): Result[Boolean] =
    attempt("Failed to read applied write") {
      withConnection { conn =>
        runQuery(
          conn,
          s"SELECT 1 FROM $appliedTable WHERE key = ? AND tenant = ?",
          Seq(SqlParam.PString(key.value), SqlParam.PString(tenantKey)),
          stream = false
        )(rows => Right(rows.hasNext))
      }
    }

  private def applyEmbedded(
    key: AppliedWriteKey,
    token: Long,
    add: Seq[Memory],
    remove: Seq[MemoryId],
    bounds: MemoryBounds
  ): Result[(TransactionalMemoryStore, AppliedWrite)] =
    fencedTransaction(key, token) { conn =>
      readApplied(conn, key.value).getOrElse {
        val outcome =
          if (!remove.distinct.forall(id => present(conn, id))) TransactionalMemoryStore.Superseded
          else {
            val adds    = TransactionalMemoryStore.distinctById(add)
            val addIds  = adds.map(_.id).toSet
            val removed = deleteIds(conn, remove.distinct.filterNot(addIds.contains))
            writeAll(conn, adds)
            val evicted = bounds.overflow(entries(conn, bounds))
            deleteIds(conn, evicted): Unit
            AppliedWrite(adds.map(_.id), removed, evicted.size)
          }
        Using.resource(
          conn.prepareStatement(s"INSERT INTO $appliedTable (key, scope, outcome, tenant) VALUES (?, ?, ?, ?)")
        ) { stmt =>
          stmt.setString(1, key.value)
          stmt.setString(2, key.scope)
          stmt.setString(3, write(AppliedWrite.toJson(outcome)))
          stmt.setString(4, tenantKey)
          stmt.executeUpdate(): Unit
        }
        outcome
      }
    }.map(this -> _)

  override def appliedWrite(
    key: AppliedWriteKey,
    token: Long
  ): Result[(TransactionalMemoryStore, Option[AppliedWrite])] =
    fencedTransaction(key, token)(conn => readApplied(conn, key.value)).map(this -> _)

  override def forgetApplied(key: AppliedWriteKey, token: Long): Result[TransactionalMemoryStore] =
    fencedTransaction(key, token) { conn =>
      Using.resource(conn.prepareStatement(s"DELETE FROM $appliedTable WHERE key = ? AND tenant = ?")) { stmt =>
        stmt.setString(1, key.value)
        stmt.setString(2, tenantKey)
        stmt.executeUpdate(): Unit
      }
    }.map(_ => this)

  override def appliedWriteCount: Result[Int] =
    Try {
      withConnection { conn =>
        Using.resource(conn.prepareStatement(s"SELECT COUNT(*) FROM $appliedTable WHERE tenant = ?")) { stmt =>
          stmt.setString(1, tenantKey)
          Using.resource(stmt.executeQuery())(rs => if (rs.next()) rs.getInt(1) else 0)
        }
      }
    }.toEither.left.map(e =>
      ProcessingError(
        "postgres-memory-store",
        s"Failed to count applied writes: ${Redaction.scrubKnown(e.getMessage)}",
        cause = Some(e)
      )
    )

  /**
   * `body` in one transaction, after a transaction-scoped advisory lock on this store's table and tenant - so the
   * tenant's writers in every process sharing the table are serialised, and a bound checked in the transaction holds - and after fencing
   * `token` for `key.scope`: a token older than the scope's newest is refused, changing nothing; otherwise it becomes
   * the newest.
   */
  private def fencedTransaction[A](key: AppliedWriteKey, token: Long)(body: Connection => A): Result[A] =
    Try {
      withConnection { conn =>
        inTransaction(conn) {
          Using.resource(conn.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))")) { stmt =>
            // PostgreSQL folds the unquoted name to lower case: `Memories` and `memories` are one table, one lock. One
            // lock per tenant (#3040): what a write checks - its fences, applied keys and bounds - is the tenant's own
            stmt.setString(1, tableName.toLowerCase(java.util.Locale.ROOT) + "/" + tenantKey)
            Using.resource(stmt.executeQuery())(_.next()): Unit
          }
          val newest =
            Using.resource(conn.prepareStatement(s"SELECT token FROM $fencesTable WHERE scope = ? AND tenant = ?")) {
              stmt =>
                stmt.setString(1, key.scope)
                stmt.setString(2, tenantKey)
                Using.resource(stmt.executeQuery())(rs => if (rs.next()) Some(rs.getLong(1)) else None)
            }
          newest match {
            case Some(n) if token < n => Left(TransactionalMemoryStore.stale(key.scope, token, n))
            case _ =>
              Using.resource(
                conn.prepareStatement(
                  s"INSERT INTO $fencesTable (scope, token, tenant) VALUES (?, ?, ?) " +
                    "ON CONFLICT (tenant, scope) DO UPDATE SET token = EXCLUDED.token"
                )
              ) { stmt =>
                stmt.setString(1, key.scope)
                stmt.setLong(2, token)
                stmt.setString(3, tenantKey)
                stmt.executeUpdate(): Unit
              }
              Right(body(conn))
          }
        }
      }
    }.toEither.left
      .map[LLMError](e =>
        ProcessingError(
          "postgres-memory-store",
          s"Failed to apply memory write: ${PostgresMemoryStore.writeFailure(e)}",
          cause = Some(PostgresMemoryStore.writeCause(e))
        )
      )
      .flatten

  private def readApplied(conn: Connection, key: String): Option[AppliedWrite] =
    Using.resource(conn.prepareStatement(s"SELECT outcome FROM $appliedTable WHERE key = ? AND tenant = ?")) { stmt =>
      stmt.setString(1, key)
      stmt.setString(2, tenantKey)
      Using.resource(stmt.executeQuery()) { rs =>
        if (rs.next())
          Some(
            AppliedWrite.fromJson(read(rs.getString(1))).fold(e => throw new IllegalStateException(e.message), identity)
          )
        else None
      }
    }

  private def present(conn: Connection, id: MemoryId): Boolean =
    Using.resource(conn.prepareStatement(s"SELECT 1 FROM $tableName WHERE id = ? AND tenant = ?")) { stmt =>
      stmt.setString(1, id.value)
      stmt.setString(2, tenantKey)
      Using.resource(stmt.executeQuery())(_.next())
    }

  /** Deletes `ids`, returning those that were there. */
  private def deleteIds(conn: Connection, ids: Seq[MemoryId]): Vector[MemoryId] =
    Using.resource(conn.prepareStatement(s"DELETE FROM $tableName WHERE id = ? AND tenant = ?")) { stmt =>
      ids.filter { id =>
        stmt.setString(1, id.value)
        stmt.setString(2, tenantKey)
        stmt.executeUpdate() > 0
      }.toVector
    }

  /**
   * Every memory of this tenant that `bounds` counts, as [[MemoryBounds.overflow]] ranks it: its id, importance, time
   * and size, computed in SQL, so no content or metadata is sent (#3040). A bound with an owner reads only the owner's
   * rows, through the GIN metadata index.
   */
  private def entries(conn: Connection, bounds: MemoryBounds): Vector[MemoryBounds.Entry] = {
    val owned = bounds.owner.fold("")(_ => " AND metadata @> jsonb_build_object(CAST(? AS text), CAST(? AS text))")
    val sql =
      "SELECT id, importance, created_at, octet_length(content) + COALESCE((SELECT sum(octet_length(key) + " +
        s"octet_length(COALESCE(value, 'null'))) FROM jsonb_each_text(metadata)), 0) FROM $tableName WHERE tenant = ?" +
        owned
    Using.resource(conn.prepareStatement(sql)) { stmt =>
      stmt.setString(1, tenantKey)
      bounds.owner.foreach { o =>
        stmt.setString(2, o.key)
        stmt.setString(3, o.value)
      }
      Using.resource(stmt.executeQuery()) { rs =>
        val out = Vector.newBuilder[MemoryBounds.Entry]
        while (rs.next()) {
          val importance = rs.getDouble(2)
          val known      = !rs.wasNull()
          out += MemoryBounds.Entry(
            MemoryId(rs.getString(1)),
            if (known) Some(importance) else None,
            rs.getTimestamp(3).toInstant,
            rs.getLong(4)
          )
        }
        out.result()
      }
    }
  }

  /** The store's statistics, by aggregate queries over this tenant's rows: no memory is read (#3040). */
  override def stats: Result[MemoryStats] =
    attempt("Failed to count memories") {
      withConnection { conn =>
        val byType = runQuery(
          conn,
          s"SELECT memory_type, COUNT(*) FROM $tableName WHERE tenant = ? GROUP BY memory_type",
          Seq(SqlParam.PString(tenantKey)),
          stream = false
        )(rows => rows.map(r => MemoryTypeCodec.decode(r.getString(1)) -> r.getLong(2)).toVector)
          .groupMapReduce(_._1)(_._2)(_ + _)
        runQuery(
          conn,
          "SELECT COUNT(*), COUNT(DISTINCT metadata->>'entity_id'), COUNT(DISTINCT metadata->>'conversation_id'), " +
            s"COUNT(embedding), MIN(created_at), MAX(created_at) FROM $tableName WHERE tenant = ?",
          Seq(SqlParam.PString(tenantKey)),
          stream = false
        ) { rows =>
          val rs    = rows.next()
          val total = rs.getLong(1)
          Right(
            MemoryStats(
              totalMemories = total,
              byType = MemoryStore.builtInCounts(byType),
              entityCount = rs.getLong(2),
              conversationCount = rs.getLong(3),
              embeddedCount = rs.getLong(4),
              oldestMemory = Option(rs.getTimestamp(5)).map(_.toInstant),
              newestMemory = Option(rs.getTimestamp(6)).map(_.toInstant)
            )
          )
        }
      }
    }

  /** Store `memory`, embedding it first when it has no embedding and the store has an embedding service. */
  override def store(memory: Memory): Result[MemoryStore] =
    embedMissing(Seq(memory)).flatMap { embedded =>
      Try {
        withConnection(conn => writeAll(conn, embedded))
        this
      }.toEither.left.map(e =>
        ProcessingError(
          "postgres-memory-store",
          s"Failed to store memory: ${PostgresMemoryStore.writeFailure(e)}",
          cause = Some(PostgresMemoryStore.writeCause(e))
        )
      )
    }

  /**
   * `memories` with every missing embedding computed by the embedding service, in `embedBatch` calls of at most
   * [[VectorMemoryStore.EmbeddingBatchSize]] texts; unchanged without a service. A memory stored without an
   * embedding is never found by vector search.
   */
  private def embedMissing(memories: Seq[Memory]): Result[Seq[Memory]] =
    embeddingService.fold[Result[Seq[Memory]]](Right(memories))(service =>
      MemoryEmbeddings.embedMissing(service, memories, "postgres-memory-store")
    )

  /**
   * Store `memories` in one transaction, on one pooled connection: all of them are stored or, if any write fails,
   * none is. The rows go to the server as JDBC batches of at most `WriteBatchSize` (1000), not one
   * round trip each, and a memory whose id appears twice is written once, as its last occurrence - so a data source
   * with pgjdbc's `reWriteBatchedInserts=true` can fold a batch into one multi-row insert. With an embedding service,
   * every memory without an embedding is embedded first; if that fails, none is stored.
   */
  override def storeAll(memories: Seq[Memory]): Result[MemoryStore] =
    embedMissing(memories).flatMap { embedded =>
      Try {
        if (embedded.nonEmpty) withConnection(conn => inTransaction(conn)(writeAll(conn, embedded)))
        this
      }.toEither.left.map(e =>
        ProcessingError(
          "postgres-memory-store",
          s"Failed to store memories: ${PostgresMemoryStore.writeFailure(e)}",
          cause = Some(PostgresMemoryStore.writeCause(e))
        )
      )
    }

  /**
   * Upsert `memories` with one prepared statement, sent as JDBC batches of at most
   * `WriteBatchSize` (1000) rows. A repeated id is written once, as its last occurrence: one
   * multi-row `INSERT ... ON CONFLICT DO UPDATE` (what `reWriteBatchedInserts` makes of a batch) cannot touch a row
   * twice.
   */
  private def writeAll(conn: Connection, memories: Seq[Memory]): Unit = {
    val sql = s"""
      INSERT INTO $tableName
        (id, content, memory_type, metadata, created_at, importance, embedding, tenant)
      VALUES (?, ?, ?, ?::jsonb, ?, ?, ?::vector, ?)
      ON CONFLICT (tenant, id) DO UPDATE SET
        content = EXCLUDED.content,
        memory_type = EXCLUDED.memory_type,
        metadata = EXCLUDED.metadata,
        created_at = EXCLUDED.created_at,
        importance = EXCLUDED.importance,
        embedding = EXCLUDED.embedding,
        version = $tableName.version + 1
    """

    Using.resource(conn.prepareStatement(sql)) { stmt =>
      TransactionalMemoryStore.distinctById(memories).grouped(PostgresMemoryStore.WriteBatchSize).foreach { chunk =>
        chunk.foreach(memory => addRow(stmt, memory))
        stmt.executeBatch(): Unit
      }
    }
  }

  /** Bind `memory` to the upsert of `writeAll` and add it to the statement's batch. */
  private def addRow(stmt: PreparedStatement, memory: Memory): Unit = {
    stmt.setString(1, memory.id.value)
    stmt.setString(2, memory.content)
    stmt.setString(3, MemoryTypeCodec.encode(memory.memoryType))
    stmt.setString(4, PostgresMemoryStore.metadataToJson(memory.metadata))
    stmt.setTimestamp(5, Timestamp.from(memory.timestamp))

    memory.importance match {
      case Some(v) => stmt.setDouble(6, v)
      case None    => stmt.setNull(6, java.sql.Types.DOUBLE)
    }

    memory.embedding match {
      case Some(vec) => stmt.setString(7, PostgresVectorHelpers.embeddingToString(vec))
      case None      => stmt.setNull(7, java.sql.Types.OTHER, "vector")
    }
    stmt.setString(8, tenantKey)

    stmt.addBatch()
  }

  /**
   * Run `body` in one transaction on `conn`: committed if it returns, rolled back if anything (fatal errors included)
   * escapes first. Autocommit is restored either way, before the pool takes the connection back.
   */
  private def inTransaction[A](conn: Connection)(body: => A): A = {
    conn.setAutoCommit(false)
    Using.resource(new PostgresMemoryStore.RollbackUnlessCommitted(conn)) { guard =>
      val result = body
      conn.commit()
      guard.committed = true
      result
    }
  }

  override def get(id: MemoryId): Result[Option[Memory]] =
    Try {
      withConnection { conn =>
        Using.resource(
          conn.prepareStatement(s"SELECT ${PostgresMemoryStore.AllColumns} FROM $tableName WHERE id = ? AND tenant = ?")
        ) { stmt =>
          stmt.setString(1, id.value)
          stmt.setString(2, tenantKey)
          Using.resource(stmt.executeQuery()) { rs =>
            if (rs.next()) {
              rowToMemory(rs).map(Some(_))
            } else {
              Right(None)
            }
          }
        }
      }
    }.toEither.left
      .map[LLMError](e =>
        ProcessingError(
          "postgres-memory-store",
          s"Failed to get memory: ${Redaction.scrubKnown(e.getMessage)}",
          cause = Some(e)
        )
      )
      .flatMap(identity)

  /**
   * The columns a row is read with when it is read only to decide `filter`: the embedding only if a `Custom` predicate
   * may look at it, so a filtered count or delete neither reads nor decodes a vector per row.
   */
  private def columnsFor(filter: MemoryFilter): String =
    if (FilterSupport.readsEmbedding(filter)) PostgresMemoryStore.AllColumns
    else PostgresMemoryStore.ColumnsWithoutEmbedding

  /**
   * Run `sql` with `params` on `conn` and hand `read` its rows. With `stream`, the rows are fetched
   * [[PostgresMemoryStore.FetchSize]] at a time, which pgjdbc does only inside a transaction: the caller runs it in
   * one.
   */
  private def runQuery[A](conn: Connection, sql: String, params: Seq[Any], stream: Boolean)(
    read: Iterator[ResultSet] => A
  ): A =
    Using.resource(conn.prepareStatement(sql)) { stmt =>
      params.zipWithIndex.foreach { case (param, idx) => setParameter(stmt, idx + 1, param) }
      if (stream) stmt.setFetchSize(PostgresMemoryStore.FetchSize)
      Using.resource(stmt.executeQuery())(rs => read(Iterator.continually(rs).takeWhile(_.next())))
    }

  /** `body`, with an exception thrown on the way turned into a `ProcessingError` that starts with `failure`. */
  private def attempt[A](failure: String)(body: => Result[A]): Result[A] =
    Try(body).toEither.left
      .map[LLMError](e =>
        ProcessingError("postgres-memory-store", s"$failure: ${Redaction.scrubKnown(e.getMessage)}", cause = Some(e))
      )
      .flatMap(identity)

  /**
   * The newest memories `filter` accepts, at most `limit`. SQL narrows, `matches` decides (`FilterSupport`): when the
   * SQL is exact the database applies the limit; otherwise the narrowed rows are streamed newest first and the limit
   * applies to those `matches` accepts.
   */
  override def recall(filter: MemoryFilter, limit: Int): Result[Seq[Memory]] =
    if (limit <= 0) Right(Seq.empty)
    else
      recallSome(filter, limit)

  private def recallSome(filter: MemoryFilter, limit: Int): Result[Seq[Memory]] =
    attempt("Failed to recall memories") {
      val plan = scoped(filter)
      withConnection { conn =>
        if (plan.needsMatches)
          inTransaction(conn) {
            val sql = s"SELECT ${PostgresMemoryStore.AllColumns} FROM $tableName ${plan.where} ORDER BY created_at DESC"
            runQuery(conn, sql, plan.params, stream = true) { rows =>
              PostgresMemoryStore.firstN(rows.map(rowToMemory(_)).filter(_.forall(filter.matches)), limit)
            }
          }
        else {
          val sql =
            s"SELECT ${PostgresMemoryStore.AllColumns} FROM $tableName ${plan.where} ORDER BY created_at DESC LIMIT ?"
          runQuery(conn, sql, plan.params :+ SqlParam.PInt(limit), stream = false) { rows =>
            PostgresMemoryStore.firstN(rows.map(rowToMemory(_)), Int.MaxValue)
          }
        }
      }
    }

  /**
   * With an embedding service, the `topK` memories `filter` accepts nearest to `query`'s embedding, scored
   * `(1 + cos) / 2`. Only embeddings of the query's dimension that are not all zeros are compared (pgvector's cosine
   * distance is undefined for a zero vector and an error between two dimensions); when the filter admits embedded
   * memories but none of the query's dimension, a `ConfigurationError`. Without a service, a keyword search, scored as
   * `InMemoryStore` scores it. A blank query, or a `topK` below 1, finds nothing without calling the service.
   */
  override def search(
    query: String,
    topK: Int,
    filter: MemoryFilter
  ): Result[Seq[ScoredMemory]] =
    if (query.trim.isEmpty || topK <= 0) Right(Seq.empty)
    else
      embeddingService match {
        case Some(service) =>
          service.embedQuery(query).flatMap { queryVector =>
            if (queryVector.isEmpty)
              Left(ProcessingError("postgres-memory-store", "Generated embedding vector is empty"))
            else if (embeddingDimensions.exists(_ != queryVector.length))
              Left(
                org.llm4s.error.ConfigurationError(
                  s"Embedding dimension mismatch: the query embedding has ${queryVector.length} dimensions but the " +
                    s"store's column is vector(${embeddingDimensions.getOrElse(0)}). Use the store's embedding model, " +
                    "or re-embed the store."
                )
              )
            else if (!SearchScores.comparable(queryVector)) Right(Seq.empty)
            else performVectorSearch(queryVector, topK, filter)
          }
        case None =>
          keywordSearch(query, topK, filter)
      }

  private def performVectorSearch(
    queryVector: Array[Float],
    topK: Int,
    filter: MemoryFilter
  ): Result[Seq[ScoredMemory]] =
    attempt("Vector search failed") {
      val plan = scoped(filter)
      // Only rows pgvector can compare with the query: of its dimension, and not all zeros (whose distance is NaN). A
      // typed column holds only the query's dimension (checked above), and a `vector_dims` condition there would make
      // the planner misjudge its selectivity and never use the index.
      val sameDimension = if (embeddingDimensions.isDefined) Seq.empty else Seq("vector_dims(embedding) = ?")
      val conditions =
        (plan.sql.map(s => s"(${s.clause})").toSeq ++
          (("embedding IS NOT NULL" +: sameDimension) :+ "vector_norm(embedding) > 0")).mkString(" AND ")
      val vectorStr = PostgresVectorHelpers.embeddingToString(queryVector)
      val sql =
        s"""
           |SELECT ${PostgresMemoryStore.AllColumns},
           |       embedding <=> ?::vector AS distance
           |FROM $tableName
           |WHERE $conditions
           |ORDER BY embedding <=> ?::vector""".stripMargin
      val dimensionParam = if (embeddingDimensions.isDefined) Seq.empty else Seq(SqlParam.PInt(queryVector.length))
      val params = ((SqlParam.PString(vectorStr) +: plan.params) ++ dimensionParam) :+ SqlParam.PString(vectorStr)

      def scored(rows: Iterator[ResultSet]): Iterator[Result[ScoredMemory]] =
        rows.flatMap(rs =>
          SearchScores
            .fromCosineDistance(rs.getDouble("distance"))
            .map(score => rowToMemory(rs).map(ScoredMemory(_, score)))
        )

      // An approximate (HNSW) index scan applies the tenant's and the filter's conditions after it picks candidates
      // (`hnsw.ef_search`, 40 by default), so a selective filter could return fewer than topK although enough rows
      // match. Iterative scan (pgvector 0.8+; an unknown setting is a harmless placeholder before) keeps scanning until
      // the filter is satisfied; a search that still comes back short of topK is run again exactly, without the index.
      def run(conn: Connection, exact: Boolean): Result[Vector[ScoredMemory]] =
        inTransaction(conn) {
          if (embeddingDimensions.isDefined)
            Using.resource(conn.createStatement()) { st =>
              if (exact) st.execute("SET LOCAL enable_indexscan = off")
              else st.execute("SET LOCAL hnsw.iterative_scan = relaxed_order")
            }: Unit
          val found =
            if (plan.needsMatches)
              // `matches` decides, so topK applies after it: stream the nearest first and keep the first topK it accepts
              runQuery(conn, sql, params, stream = true) { rows =>
                PostgresMemoryStore.firstN(scored(rows).filter(_.forall(sm => filter.matches(sm.memory))), topK)
              }
            else
              runQuery(conn, s"$sql\nLIMIT ?", params :+ SqlParam.PInt(topK), stream = false) { rows =>
                PostgresMemoryStore.firstN(scored(rows), Int.MaxValue)
              }
          // relaxed order may return the nearest slightly out of order
          found.map(_.sorted(ScoredMemory.byScoreDescending))
        }

      withConnection { conn =>
        val found = run(conn, exact = false).flatMap { hits =>
          if (hits.size < topK && embeddingDimensions.isDefined) run(conn, exact = true) else Right(hits)
        }
        found.flatMap(hits => if (hits.nonEmpty) Right(hits) else mismatch(conn, plan, filter, queryVector.length))
      }
    }

  /**
   * Nothing was found: a `ConfigurationError` if the memories `filter` accepts have embeddings but none of the query's
   * dimension (the store was written with another embedding model), otherwise no results. Rows of the query's
   * dimension are read first, so the first accepted row answers.
   */
  private def mismatch(
    conn: Connection,
    plan: FilterSupport.Narrowing,
    filter: MemoryFilter,
    dimension: Int
  ): Result[Seq[ScoredMemory]] = {
    val conditions = (plan.sql.map(s => s"(${s.clause})").toSeq :+ "embedding IS NOT NULL").mkString(" AND ")
    val sql =
      s"SELECT ${columnsFor(filter)}, vector_dims(embedding) AS dims FROM $tableName WHERE $conditions " +
        "ORDER BY (vector_dims(embedding) = ?) DESC"
    inTransaction(conn) {
      runQuery(conn, sql, plan.params :+ SqlParam.PInt(dimension), stream = true) { rows =>
        val accepted = rows
          .map(rs => rowToMemory(rs, withEmbedding = FilterSupport.readsEmbedding(filter)).map(_ -> rs.getInt("dims")))
          .filter(_.forall { case (m, _) => !plan.needsMatches || filter.matches(m) })
        PostgresMemoryStore
          .firstN(accepted, 1)
          .flatMap(_.headOption match {
            case Some((memory, dims)) if dims != dimension =>
              Left(MemoryEmbeddings.dimensionMismatch(dimension, memory.id, dims))
            case _ => Right(Seq.empty)
          })
      }
    }
  }

  /**
   * Keyword search, for a store without an embedding service. A memory's score is the share of the query's phrases
   * that occur in it as whole words (`KeywordTokens`), exactly as `InMemoryStore` scores it, so both stores find the
   * same memories; ties go to the newer memory. Every row `filter` narrows to is read, so on a large table give the
   * store an embedding service, or a narrow filter.
   */
  private def keywordSearch(text: String, topK: Int, filter: MemoryFilter): Result[Seq[ScoredMemory]] = {
    val phrases = KeywordTokens.searchPhrases(text)
    if (phrases.isEmpty || topK <= 0) Right(Seq.empty)
    else
      attempt("Keyword search failed") {
        val plan = scoped(filter)
        withConnection { conn =>
          inTransaction(conn) {
            val sql = s"SELECT ${PostgresMemoryStore.AllColumns} FROM $tableName ${plan.where} ORDER BY created_at DESC"
            runQuery(conn, sql, plan.params, stream = true) { rows =>
              val memories = rows.map(rowToMemory(_))
              PostgresMemoryStore.topScored(
                if (plan.needsMatches) memories.filter(_.forall(filter.matches)) else memories,
                phrases,
                topK
              )
            }
          }
        }
      }
  }

  override def delete(id: MemoryId): Result[MemoryStore] =
    Try {
      withConnection { conn =>
        Using.resource(conn.prepareStatement(s"DELETE FROM $tableName WHERE id = ? AND tenant = ?")) { stmt =>
          stmt.setString(1, id.value)
          stmt.setString(2, tenantKey)
          stmt.executeUpdate()
        }
      }
      this
    }.toEither.left.map(e =>
      ProcessingError(
        "postgres-memory-store",
        s"Failed to delete memory: ${Redaction.scrubKnown(e.getMessage)}",
        cause = Some(e)
      )
    )

  /**
   * Delete every memory `filter` accepts. When the SQL is exact one `DELETE` does it; otherwise the narrowed rows are
   * read and those `matches` accepts are deleted by id, in the same transaction.
   */
  override def deleteMatching(filter: MemoryFilter): Result[MemoryStore] =
    attempt("Failed to delete matching memories") {
      val plan = scoped(filter)
      withConnection { conn =>
        if (plan.needsMatches)
          inTransaction(conn) {
            // FOR UPDATE: a row updated concurrently is locked as its new version and judged by `matches` as that
            // version, and no update lands between the read and the delete
            val sql = s"SELECT ${columnsFor(filter)} FROM $tableName ${plan.where} FOR UPDATE"
            runQuery(conn, sql, plan.params, stream = true) { rows =>
              val memories = rows.map(rowToMemory(_, withEmbedding = FilterSupport.readsEmbedding(filter)))
              PostgresMemoryStore.firstN(memories.filter(_.forall(filter.matches)).map(_.map(_.id)), Int.MaxValue)
            }.map(ids => deleteAll(conn, ids))
          }
        else
          Using.resource(conn.prepareStatement(s"DELETE FROM $tableName ${plan.where}")) { stmt =>
            plan.params.zipWithIndex.foreach { case (param, idx) => setParameter(stmt, idx + 1, param) }
            Right(stmt.executeUpdate())
          }
      }.map(_ => this)
    }

  /** Delete `ids`, [[PostgresMemoryStore.WriteBatchSize]] at a time. The caller supplies the transaction. */
  private def deleteAll(conn: Connection, ids: Seq[MemoryId]): Unit =
    Using.resource(conn.prepareStatement(s"DELETE FROM $tableName WHERE id = ANY(?) AND tenant = ?")) { stmt =>
      ids.grouped(PostgresMemoryStore.WriteBatchSize).foreach { chunk =>
        stmt.setArray(1, conn.createArrayOf("text", chunk.map(_.value).toArray[AnyRef]))
        stmt.setString(2, tenantKey)
        stmt.executeUpdate(): Unit
      }
    }

  /**
   * Update a memory, guarded by its version. When the content changes and `updateFn` left the embedding as it was, the
   * embedding - computed for the old content - is dropped, and computed again if the store has an embedding service.
   */
  override def update(id: MemoryId, updateFn: Memory => Memory): Result[MemoryStore] =
    getVersioned(id).flatMap {
      case Some(VersionedMemory(existing, version)) =>
        val changed = updateFn(existing)
        if (changed.id != id)
          Left(
            ValidationError(
              "id",
              s"update function changed Memory ID from $id to ${changed.id}; IDs must remain constant"
            )
          )
        else
          embedMissing(Seq(MemoryEmbeddings.afterUpdate(existing, changed))).map(_.head).flatMap { updated =>
            Try {
              withConnection { conn =>
                val sql = s"""
              UPDATE $tableName SET
                content = ?,
                memory_type = ?,
                metadata = ?::jsonb,
                created_at = ?,
                importance = ?,
                embedding = ?::vector,
                version = ?
              WHERE id = ? AND version = ? AND tenant = ?
            """
                Using.resource(conn.prepareStatement(sql)) { stmt =>
                  stmt.setString(1, updated.content)
                  stmt.setString(2, MemoryTypeCodec.encode(updated.memoryType))
                  stmt.setString(3, PostgresMemoryStore.metadataToJson(updated.metadata))
                  stmt.setTimestamp(4, Timestamp.from(updated.timestamp))

                  updated.importance match {
                    case Some(v) => stmt.setDouble(5, v)
                    case None    => stmt.setNull(5, java.sql.Types.DOUBLE)
                  }

                  updated.embedding match {
                    case Some(vec) => stmt.setString(6, PostgresVectorHelpers.embeddingToString(vec))
                    case None      => stmt.setNull(6, java.sql.Types.OTHER, "vector")
                  }

                  stmt.setLong(7, version + 1)
                  stmt.setString(8, id.value)
                  stmt.setLong(9, version)
                  stmt.setString(10, tenantKey)
                  stmt.executeUpdate()
                }
              }
            }.toEither.left
              .map(e =>
                ProcessingError(
                  "postgres-memory-store",
                  s"Failed to update memory: ${Redaction.scrubKnown(e.getMessage)}",
                  cause = Some(e)
                )
              )
              .flatMap { rowsAffected =>
                if (rowsAffected == 0)
                  Left(
                    OptimisticLockFailure(
                      s"Concurrent modification detected for memory: ${id.value}",
                      id.value,
                      version
                    )
                  )
                else
                  Right(this)
              }
          }
      case None => Left(NotFoundError(s"Memory not found: ${id.value}", id.value))
    }

  /** How many memories `filter` accepts: `COUNT(*)` when the SQL is exact, otherwise the narrowed rows `matches` accepts. */
  override def count(filter: MemoryFilter): Result[Long] =
    attempt("Failed to count memories") {
      val plan = scoped(filter)
      withConnection { conn =>
        if (plan.needsMatches)
          inTransaction(conn) {
            val sql = s"SELECT ${columnsFor(filter)} FROM $tableName ${plan.where}"
            runQuery(conn, sql, plan.params, stream = true) { rows =>
              val memories = rows.map(rowToMemory(_, withEmbedding = FilterSupport.readsEmbedding(filter)))
              PostgresMemoryStore.countMatching(memories, filter)
            }
          }
        else
          runQuery(conn, s"SELECT COUNT(*) FROM $tableName ${plan.where}", plan.params, stream = false) { rows =>
            Right(rows.next().getLong(1))
          }
      }
    }

  /**
   * Embed every stored memory that has no embedding, `batchSize` at a time, and return how many were embedded: rows
   * written before this store embedded what it stores, or by another writer, are otherwise never found by vector
   * search. Needs an embedding service. A memory whose content changes meanwhile is left for its own write to embed.
   */
  def embedAll(batchSize: Int = VectorMemoryStore.EmbeddingBatchSize): Result[Int] = embedWhere(batchSize, None)

  /**
   * Embed every stored memory that has no embedding or one whose dimension is not `dimension`, `batchSize` at a time,
   * and return how many were embedded: after a change of embedding model, the memories written with the old one are
   * otherwise never compared with a query of the new one. A column typed `vector(n)` (`embeddingDimensions`) accepts
   * only `n`: to move such a table to another model, make the column untyped first (see the migration guide).
   */
  def reembedAll(dimension: Int, batchSize: Int = VectorMemoryStore.EmbeddingBatchSize): Result[Int] =
    if (dimension <= 0)
      Left(ProcessingError("postgres-memory-store", s"reembedAll needs a positive dimension, got $dimension"))
    else embedWhere(batchSize, Some(dimension))

  private def embedWhere(batchSize: Int, dimension: Option[Int]): Result[Int] =
    embeddingService match {
      case None =>
        Left(ProcessingError("postgres-memory-store", "embedAll needs a store with an embedding service"))
      case Some(_) if batchSize <= 0 =>
        Left(ProcessingError("postgres-memory-store", s"embedAll needs a positive batch size, got $batchSize"))
      case Some(service) =>
        // The rows to embed: those without an embedding, or (re-embedding) without one of `dimension`
        val wanted = dimension.fold("embedding IS NULL")(_ => "(embedding IS NULL OR vector_dims(embedding) <> ?)")
        val wantedParams = dimension.map(SqlParam.PInt(_)).toSeq

        def unembedded(after: Option[String]): Result[Vector[(String, String)]] =
          attempt("Failed to read memories to embed") {
            val sql = s"SELECT id, content FROM $tableName WHERE tenant = ? AND $wanted" +
              after.fold("")(_ => " AND id > ?") + " ORDER BY id LIMIT ?"
            val params =
              (SqlParam.PString(tenantKey) +: (wantedParams ++ after.map(SqlParam.PString(_)).toSeq)) :+
                SqlParam.PInt(batchSize)
            withConnection { conn =>
              runQuery(conn, sql, params, stream = false) { rows =>
                Right(rows.map(rs => rs.getString(1) -> rs.getString(2)).toVector)
              }
            }
          }

        def write(rows: Vector[(String, String)], embeddings: Seq[Array[Float]]): Result[Int] =
          attempt("Failed to store embeddings") {
            withConnection { conn =>
              inTransaction(conn) {
                val sql =
                  s"UPDATE $tableName SET embedding = ?::vector, version = version + 1 " +
                    s"WHERE id = ? AND content = ? AND $wanted AND tenant = ?"
                Using.resource(conn.prepareStatement(sql)) { stmt =>
                  rows.zip(embeddings).foreach { case ((id, content), embedding) =>
                    stmt.setString(1, PostgresVectorHelpers.embeddingToString(embedding))
                    stmt.setString(2, id)
                    stmt.setString(3, content)
                    dimension.foreach(stmt.setInt(4, _))
                    stmt.setString(if (dimension.isDefined) 5 else 4, tenantKey)
                    stmt.addBatch()
                  }
                  Right(stmt.executeBatch().count(_ != 0))
                }
              }
            }
          }

        // Keyset pagination by id: every row is visited once, whatever the embedding service answers
        @scala.annotation.tailrec
        def loop(after: Option[String], total: Int): Result[Int] =
          unembedded(after).flatMap { rows =>
            if (rows.isEmpty) Right(Vector.empty -> 0)
            else
              MemoryEmbeddings
                .embedMissing(
                  service,
                  rows.map { case (id, content) => Memory(MemoryId(id), content, MemoryType.Task) },
                  "postgres-memory-store",
                  batchSize
                )
                .flatMap(embedded => write(rows, embedded.flatMap(_.embedding)))
                .map(rows -> _)
          } match {
            case Left(e)                          => Left(e)
            case Right((rows, _)) if rows.isEmpty => Right(total)
            case Right((rows, n))                 => loop(Some(rows.last._1), total + n)
          }

        loop(None, 0)
    }

  override def clear(): Result[MemoryStore] =
    Try {
      withConnection { conn =>
        Using.resource(conn.prepareStatement(s"DELETE FROM $tableName WHERE tenant = ?")) { stmt =>
          stmt.setString(1, tenantKey)
          stmt.executeUpdate()
        }
      }
      this
    }.toEither.left.map(e =>
      ProcessingError(
        "postgres-memory-store",
        s"Failed to clear memories: ${Redaction.scrubKnown(e.getMessage)}",
        cause = Some(e)
      )
    )

  override def recent(limit: Int, filter: MemoryFilter): Result[Seq[Memory]] =
    recall(filter, limit)

  private val closed = new AtomicBoolean(false)

  /** Closes the connection pool if this store created it; a caller-owned `DataSource` is left open. */
  override def close(): Unit =
    if (ownsDataSource && closed.compareAndSet(false, true)) dataSource match {
      case closeable: AutoCloseable => closeable.close()
      case _                        => ()
    }

  private case class VersionedMemory(memory: Memory, version: Long)

  private def getVersioned(id: MemoryId): Result[Option[VersionedMemory]] =
    Try {
      withConnection { conn =>
        Using.resource(
          conn.prepareStatement(s"SELECT ${PostgresMemoryStore.AllColumns} FROM $tableName WHERE id = ? AND tenant = ?")
        ) { stmt =>
          stmt.setString(1, id.value)
          stmt.setString(2, tenantKey)
          Using.resource(stmt.executeQuery()) { rs =>
            if (rs.next()) rowToMemory(rs).map(mem => Some(VersionedMemory(mem, rs.getLong("version"))))
            else Right(None)
          }
        }
      }
    }.toEither.left
      .map[LLMError](e =>
        ProcessingError(
          "postgres-memory-store",
          s"Failed to get memory: ${Redaction.scrubKnown(e.getMessage)}",
          cause = Some(e)
        )
      )
      .flatMap(identity)

  private def withConnection[A](f: Connection => A): A =
    Using.resource(dataSource.getConnection)(f)

  /** The memory in the current row of `rs`; without `withEmbedding`, the row was read without its embedding column. */
  private def rowToMemory(rs: ResultSet, withEmbedding: Boolean = true): Result[Memory] = {
    val embeddingResult =
      if (!withEmbedding) Right(None)
      else {
        val embeddingStr = rs.getString("embedding")
        if (embeddingStr == null) Right(None)
        else PostgresVectorHelpers.stringToEmbedding(embeddingStr).map(Some(_))
      }

    embeddingResult.map { embedding =>
      Memory(
        id = MemoryId(rs.getString("id")),
        content = rs.getString("content"),
        memoryType = MemoryTypeCodec.decode(rs.getString("memory_type")),
        metadata = PostgresMemoryStore.jsonToMetadata(rs.getString("metadata_text")),
        timestamp = rs.getTimestamp("created_at").toInstant,
        importance = Option(rs.getDouble("importance")).filterNot(_ => rs.wasNull()),
        embedding = embedding
      )
    }
  }

  private def setParameter(stmt: PreparedStatement, index: Int, value: Any): Unit = value match {
    case SqlParam.PString(s)    => stmt.setString(index, s)
    case SqlParam.PInt(i)       => stmt.setInt(index, i)
    case SqlParam.PLong(l)      => stmt.setLong(index, l)
    case SqlParam.PDouble(d)    => stmt.setDouble(index, d)
    case SqlParam.PBoolean(b)   => stmt.setBoolean(index, b)
    case SqlParam.PTimestamp(t) => stmt.setTimestamp(index, t)
    case SqlParam.PNullDouble   => stmt.setNull(index, java.sql.Types.DOUBLE)
    case SqlParam.PNullVector   => stmt.setNull(index, java.sql.Types.OTHER, "vector")
    case other                  => stmt.setObject(index, other)
  }
}

object PostgresMemoryStore {

  sealed trait SqlParam
  object SqlParam {
    final case class PString(v: String)                extends SqlParam
    final case class PInt(v: Int)                      extends SqlParam
    final case class PLong(v: Long)                    extends SqlParam
    final case class PDouble(v: Double)                extends SqlParam
    final case class PBoolean(v: Boolean)              extends SqlParam
    final case class PTimestamp(v: java.sql.Timestamp) extends SqlParam
    case object PNullDouble                            extends SqlParam
    case object PNullVector                            extends SqlParam
  }

  import SqlParam._

  /** The most rows `storeAll` (and every other multi-row write) sends in one JDBC batch. */
  private[memory] val WriteBatchSize: Int = 1000

  /**
   * The scrubbed message of a failed write. A failed JDBC batch reports the statement of its failing row, parameters
   * and embedding included, and chains the server's error behind it: that error is the message, as it was before
   * writes were batched.
   */
  private[memory] def writeFailure(e: Throwable): String =
    Redaction.scrubKnown(writeCause(e).getMessage)

  /** The exception a failed write reports: a batch's chained server error, as its own message holds the bound rows. */
  private[memory] def writeCause(e: Throwable): Throwable =
    e match {
      case batch: BatchUpdateException if batch.getNextException != null => batch.getNextException
      case other                                                         => other
    }

  /**
   * At most 55 characters, so every name the store derives from it - `<table>_applied`, `<table>_fences` and the
   * `idx_<table>_...` indexes - stays distinct within PostgreSQL's 63-byte identifier limit, which truncates silently.
   */
  private val ValidIdentifierPattern = "^[a-zA-Z_][a-zA-Z0-9_]{0,54}$".r

  final case class Config(
    host: String = "localhost",
    port: Int = 5432,
    database: String = "postgres",
    user: String = "postgres",
    password: Secret = Secret(""),
    tableName: String = "agent_memories",
    maxPoolSize: Int = 10,
    tenant: Option[String] = None,
    embeddingDimensions: Option[Int] = None
  ) {
    require(
      ValidIdentifierPattern.matches(tableName),
      s"Invalid table name '$tableName': must match pattern [a-zA-Z_][a-zA-Z0-9_]{0,54}"
    )
    require(!tenant.exists(_.isEmpty), TenantMessage)
    require(embeddingDimensions.forall(_ > 0), DimensionsMessage)
    def jdbcUrl: String = s"jdbc:postgresql://$host:$port/$database"
  }

  /** Rolls `conn` back unless the transaction committed, then puts it back in autocommit mode if it can. */
  final private class RollbackUnlessCommitted(conn: Connection) extends AutoCloseable {
    var committed = false
    // Autocommit goes back on only once the transaction has ended: turning it on after a failed rollback would make
    // pgjdbc commit whatever the transaction holds. Left off, HikariCP rolls the connection back and resets it when
    // it returns to the pool. After a commit, a failure here must not turn stored rows into a Left.
    override def close(): Unit =
      if (committed || Try(conn.rollback()).isSuccess) Try(conn.setAutoCommit(true)): Unit
  }

  /** A store over a connection pool it creates from `config`, and closes on [[PostgresMemoryStore.close]]. */
  def apply(
    config: Config,
    embeddingService: Option[EmbeddingService] = None
  ): Result[PostgresMemoryStore] =
    Try {
      val hikariConfig = new HikariConfig()
      hikariConfig.setJdbcUrl(config.jdbcUrl)
      hikariConfig.setUsername(config.user)
      hikariConfig.setPassword(config.password.reveal)
      hikariConfig.setMaximumPoolSize(config.maxPoolSize)
      hikariConfig.setMinimumIdle(1)
      new HikariDataSource(hikariConfig)
    }.toEither.left
      .map(initFailure)
      .flatMap { dataSource =>
        val store =
          new PostgresMemoryStore(
            dataSource,
            config.tableName,
            embeddingService,
            ownsDataSource = true,
            config.tenant,
            config.embeddingDimensions
          )
        initialized(store).left.map { e =>
          store.close()
          e
        }
      }

  private def initFailure(e: Throwable): LLMError =
    ProcessingError(
      "postgres-memory-store",
      s"Failed to initialize: ${Redaction.scrubKnown(e.getMessage)}",
      cause = Some(e)
    )

  private def initialized(store: PostgresMemoryStore): Result[PostgresMemoryStore] =
    Try(store.initializeSchema()).toEither.left.map(initFailure).map(_ => store)

  /**
   * A store over `dataSource`, a pool the caller already has - a doobie `HikariTransactor`'s `kernel`, a Play or Slick
   * pool, or a platform-provided `DataSource`. The store creates its tables if absent, and never closes `dataSource`:
   * [[PostgresMemoryStore.close]] leaves it open for its owner. Store calls are blocking JDBC; run them on a blocking
   * pool (`IO.blocking`, `Sync[F].blocking`) from effectful code.
   *
   * @param dataSource a `javax.sql.DataSource` the caller owns and closes
   * @param tableName the memory table; at most 55 characters matching `[a-zA-Z_][a-zA-Z0-9_]*`
   * @param embeddingService the service embedding memories, if any
   * @param tenant the tenant whose memories this store reads and writes: many tenants share one table, each seeing,
   *               counting, evicting and clearing only its own rows. `None` is the rows of no tenant, which is every
   *               row a store wrote before tenants existed; a tenant is never empty.
   * @param embeddingDimensions the dimension of every embedding, if declared: the column is typed `vector(n)` (an
   *               older untyped one is converted, if every stored embedding has `n` dimensions) and, up to 2000
   *               dimensions, indexed with HNSW for cosine distance, so a search need not read every row
   */
  def fromDataSource(
    dataSource: DataSource,
    tableName: String = "agent_memories",
    embeddingService: Option[EmbeddingService] = None,
    tenant: Option[String] = None,
    embeddingDimensions: Option[Int] = None
  ): Result[PostgresMemoryStore] =
    if (dataSource == null)
      Left(ProcessingError("postgres-memory-store", "dataSource must not be null"))
    else if (embeddingDimensions.exists(_ <= 0))
      Left(ProcessingError("postgres-memory-store", DimensionsMessage))
    else if (tenant.exists(t => t == null || t.isEmpty))
      Left(ProcessingError("postgres-memory-store", TenantMessage))
    else if (tableName == null || !ValidIdentifierPattern.matches(tableName))
      Left(
        ProcessingError(
          "postgres-memory-store",
          s"Invalid table name '$tableName': must match pattern [a-zA-Z_][a-zA-Z0-9_]{0,54}"
        )
      )
    else
      initialized(
        new PostgresMemoryStore(
          dataSource,
          tableName,
          embeddingService,
          ownsDataSource = false,
          tenant,
          embeddingDimensions
        )
      )

  /**
   * One statement making `(tenant, key)` the primary key of `table`, adding the `tenant` column first, if it is not
   * yet. The check reads only the catalog, so on an up-to-date table - every open but the first - it takes no lock on
   * the table, and never queues behind its readers. Only when the key is not `(tenant, key)` does it lock the table
   * and check again before it changes anything, so two processes opening an older table at once migrate it once.
   * `table` is a validated identifier.
   */
  private[memory] def tenantKeyMigration(table: String, key: String): String = {
    val keyed =
      s"""COALESCE((SELECT array_agg(a.attname::text ORDER BY k.ord) FROM pg_constraint c
         |CROSS JOIN LATERAL unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord)
         |JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum
         |WHERE c.conrelid = '$table'::regclass AND c.contype = 'p') = ARRAY['tenant', '$key'], false)""".stripMargin
    s"""DO $$$$
       |DECLARE pk name;
       |BEGIN
       |  IF NOT $keyed THEN
       |    LOCK TABLE $table IN ACCESS EXCLUSIVE MODE;
       |    IF NOT $keyed THEN
       |      ALTER TABLE $table ADD COLUMN IF NOT EXISTS tenant TEXT NOT NULL DEFAULT '';
       |      SELECT conname INTO pk FROM pg_constraint WHERE conrelid = '$table'::regclass AND contype = 'p';
       |      IF pk IS NOT NULL THEN EXECUTE format('ALTER TABLE $table DROP CONSTRAINT %I', pk); END IF;
       |      ALTER TABLE $table ADD PRIMARY KEY (tenant, $key);
       |    END IF;
       |  END IF;
       |END $$$$""".stripMargin
  }

  private val TenantMessage = "a tenant must not be empty: the rows of no tenant are tenant = None"

  private val DimensionsMessage = "embeddingDimensions must be positive"

  /** The most dimensions pgvector's HNSW index takes for `vector`; a wider declared column is typed but not indexed. */
  private[memory] val MaxIndexedDimensions: Int = 2000

  /**
   * One statement typing `table`'s embedding column `vector(n)`, if it is not yet. An untyped column (every table
   * created before #3037) is converted under a lock, after checking again, only if every embedding has `n` dimensions;
   * otherwise, and for a column typed with another dimension, it fails naming what it found. The check reads only the
   * catalog, so an up-to-date table is never locked. `table` is a validated identifier.
   */
  private[memory] def declareDimension(table: String, n: Int): String = {
    val typmod =
      s"(SELECT a.atttypmod FROM pg_attribute a WHERE a.attrelid = '$table'::regclass AND a.attname = 'embedding')"
    s"""DO $$$$
       |DECLARE other integer;
       |BEGIN
       |  IF $typmod <> $n THEN
       |    LOCK TABLE $table IN ACCESS EXCLUSIVE MODE;
       |    IF $typmod > 0 AND $typmod <> $n THEN
       |      RAISE EXCEPTION 'the column $table.embedding is vector(%), not vector($n)', $typmod;
       |    END IF;
       |    IF $typmod <> $n THEN
       |      SELECT vector_dims(embedding) INTO other FROM $table
       |        WHERE embedding IS NOT NULL AND vector_dims(embedding) <> $n LIMIT 1;
       |      IF other IS NOT NULL THEN
       |        RAISE EXCEPTION '$table holds embeddings of % dimensions, not $n: re-embed them (reembedAll) first', other;
       |      END IF;
       |      ALTER TABLE $table ALTER COLUMN embedding TYPE vector($n);
       |    END IF;
       |  END IF;
       |END $$$$""".stripMargin
  }

  /** How many rows a streamed read fetches per round trip, so reading a large table to decide a filter never holds it. */
  private[memory] val FetchSize: Int = 500

  /**
   * The metadata as `rowToMemory` reads it: every value as the text `->>` gives for it (a JSON null as `null`), so a
   * number, a boolean or a nested value an outside writer stored reads back as the very text a metadata filter compares
   * in SQL - `{"count": 1.0}` is `count -> "1.0"`, which `ByMetadata("count", "1.0")` matches in SQL and in `matches`.
   */
  private val MetadataText =
    "(SELECT jsonb_object_agg(key, COALESCE(value, 'null')) FROM jsonb_each_text(metadata)) AS metadata_text"

  /** Every column `rowToMemory` reads. */
  private[memory] val AllColumns = s"*, $MetadataText"

  /** Every column `rowToMemory` reads but the embedding. */
  private val ColumnsWithoutEmbedding = s"id, content, memory_type, $MetadataText, created_at, importance"

  /**
   * The narrowing of `filter`: SQL narrows, `matches` decides, as in every SQL-backed store (`FilterSupport`). The
   * condition is a superset of what `filter` accepts; when it is not also exact, the store runs `matches` on the rows
   * it reads before any limit, count or delete.
   */
  private[memory] def narrowing(filter: MemoryFilter): FilterSupport.Narrowing =
    FilterSupport.narrow(filter)(leafSql)

  /**
   * The SQL of one filter, when it means exactly what `matches` means: a row satisfies it if and only if `matches`
   * accepts the memory read from the row, and it is never NULL - a NULL under `NOT` would drop a row `Not` accepts,
   * hence the `IS NOT NULL` or `COALESCE` beside every test of a nullable value. A metadata key is a bound parameter,
   * so any key works. A case-insensitive `ContentContains` has none: PostgreSQL's `lower()` depends on the database's
   * collation and is not `String.toLowerCase`, so `matches` decides it.
   */
  private[memory] def leafSql(filter: MemoryFilter): Option[FilterSupport.Sql] = filter match {
    case MemoryFilter.ByType(memoryType) =>
      Some(typeIn(MemoryTypeCodec.spellings(memoryType)))

    case MemoryFilter.ByTypes(memoryTypes) =>
      val names = memoryTypes.toSeq.flatMap(MemoryTypeCodec.spellings).distinct.sorted
      if (names.isEmpty) Some(FilterSupport.Sql("1 = 0", Seq.empty)) else Some(typeIn(names))

    case MemoryFilter.ByEntity(entityId) =>
      Some(metadataEquals("'entity_id'", Seq.empty, entityId.value))

    case MemoryFilter.ByConversation(conversationId) =>
      // The first test is the conversation index's expression, so the index stays usable
      Some(
        FilterSupport.Sql(
          "((metadata->>'conversation_id' = ? AND metadata->>'conversation_id' IS NOT NULL) OR " +
            "COALESCE(? = 'null' AND jsonb_typeof(metadata -> 'conversation_id') = 'null', FALSE))",
          Seq(PString(conversationId), PString(conversationId))
        )
      )

    case MemoryFilter.ByMetadata(key, value) =>
      Some(metadataEquals("CAST(? AS text)", Seq(PString(key)), value))

    case MemoryFilter.HasMetadata(key) =>
      // `->` gives JSON null, not SQL NULL, for a key whose value is null: the key is there
      Some(FilterSupport.Sql("(metadata -> CAST(? AS text)) IS NOT NULL", Seq(PString(key))))

    case MemoryFilter.MetadataContains(key, substring) =>
      // strpos is a literal, case-sensitive substring test; LIKE would read % and _ as wildcards
      Some(
        FilterSupport.Sql(
          s"COALESCE(strpos(${metadataText("CAST(? AS text)")}, ?) > 0, FALSE)",
          metadataTextParams(Seq(PString(key))) :+ PString(substring)
        )
      )

    case MemoryFilter.ContentContains(substring, true) =>
      Some(FilterSupport.Sql("strpos(content, ?) > 0", Seq(PString(substring))))

    case MemoryFilter.MinImportance(threshold) =>
      // PostgreSQL orders NaN above every number, where `>=` in Scala is false for it
      Some(
        FilterSupport.Sql(
          "(importance >= ? AND importance IS NOT NULL AND importance <> 'NaN'::double precision)",
          Seq(PDouble(threshold))
        )
      )

    case MemoryFilter.ByTimeRange(after, before) =>
      // The column keeps microseconds: a stored time is at or after `a` exactly when it is at or after `a` rounded up
      // to the microsecond, and at or before `b` when at or before `b` rounded down
      val bounds =
        after.map(a => "created_at >= ?" -> PTimestamp(Timestamp.from(ceilMicros(a)))).toSeq ++
          before.map(b => "created_at <= ?" -> PTimestamp(Timestamp.from(floorMicros(b)))).toSeq
      if (bounds.isEmpty) Some(FilterSupport.unrestricted)
      else Some(FilterSupport.Sql(bounds.map(_._1).mkString(" AND "), bounds.map(_._2)))

    case _ => None // ContentContains ignoring case; And, Or, Not, All, None and Custom are narrowed by FilterSupport
  }

  private def typeIn(names: Seq[String]): FilterSupport.Sql =
    names match {
      case Seq(only) => FilterSupport.Sql("memory_type = ?", Seq(PString(only)))
      case _ => FilterSupport.Sql(s"memory_type IN (${names.map(_ => "?").mkString(",")})", names.map(PString(_)))
    }

  /**
   * The text of the metadata value at `key` (SQL for the key, a literal or `CAST(? AS text)`) as [[MetadataText]] reads
   * it: what `->>` gives, and `null` for a JSON null; SQL NULL only when the key is absent. Its parameters, `keyParams`
   * twice, are [[metadataTextParams]].
   */
  private def metadataText(key: String): String =
    s"COALESCE(metadata ->> $key, CASE WHEN jsonb_typeof(metadata -> $key) = 'null' THEN 'null' END)"

  private def metadataTextParams(keyParams: Seq[PString]): Seq[PString] = keyParams ++ keyParams

  /**
   * The metadata value at `key` reads as `value`. Compared as text, as `matches` compares what [[MetadataText]] read:
   * a number or a boolean matches its text, not only a JSON string.
   */
  private def metadataEquals(key: String, keyParams: Seq[PString], value: String): FilterSupport.Sql = {
    val exact = FilterSupport.Sql(
      s"COALESCE(${metadataText(key)} = ?, FALSE)",
      metadataTextParams(keyParams) :+ PString(value)
    )
    metadataContainment(key, keyParams, value).fold(exact) { index =>
      FilterSupport.Sql(s"(${index.clause}) AND ${exact.clause}", index.params ++ exact.params)
    }
  }

  /**
   * A containment test the `GIN(metadata)` index serves (#3164), true of every row whose value at `key` reads as
   * `value`, and never NULL: [[metadataEquals]] ANDs it before the exact text test, so the index narrows the rows and
   * the text decides. `value` is what `->>` gives for a JSON string equal to it, and, when it is also the text of a
   * number as PostgreSQL writes one (no exponent, within `numeric`'s range), `true`, `false` or `null`, for that JSON
   * value too: a containment of a number compares numerically, so it may admit `1.0` for `1`, which the text test then
   * refuses. The text of a nested value (`{...}` or `[...]`) has no containment test: none, and the text test alone.
   */
  private def metadataContainment(key: String, keyParams: Seq[PString], value: String): Option[FilterSupport.Sql] = {
    // a literal key is cast, so jsonb_build_object never sees an argument of unknown type
    val named                        = if (keyParams.isEmpty) s"CAST($key AS text)" else key
    def contains(as: String): String = s"metadata @> jsonb_build_object($named, CAST(? AS $as))"
    if (value.startsWith("{") || value.startsWith("[")) None
    else {
      val asString = FilterSupport.Sql(contains("text"), keyParams :+ PString(value))
      if (!isJsonScalarText(value)) Some(asString)
      else
        Some(
          FilterSupport.Sql(
            s"${asString.clause} OR ${contains("jsonb")}",
            asString.params ++ keyParams :+ PString(value)
          )
        )
    }
  }

  /** `numeric`'s limits: digits before and after the point. */
  private val MaxIntegerDigits  = 131072
  private val MaxFractionDigits = 16383
  private val NumericText       = "-?(0|[1-9][0-9]*)(?:\\.([0-9]+))?".r

  /** `s` is how `->>` writes a JSON number, boolean or null, so `CAST(s AS jsonb)` parses it. */
  private[memory] def isJsonScalarText(s: String): Boolean =
    s match {
      case "true" | "false" | "null" => true
      case NumericText(integer, fraction) =>
        integer.length <= MaxIntegerDigits && Option(fraction).forall(_.length <= MaxFractionDigits)
      case _ => false
    }

  private def floorMicros(t: Instant): Instant = t.truncatedTo(ChronoUnit.MICROS)

  private def ceilMicros(t: Instant): Instant = {
    val floor = floorMicros(t)
    if (floor == t) t else floor.plusNanos(1000)
  }

  /** The first `n` values of `rows`, or the first error among them. */
  private[memory] def firstN[A](rows: Iterator[Result[A]], n: Int): Result[Vector[A]] = {
    val out                     = Vector.newBuilder[A]
    var taken                   = 0
    var error: Option[LLMError] = None
    while (error.isEmpty && taken < n && rows.hasNext)
      rows.next() match {
        case Right(a) =>
          out += a
          taken += 1
        case Left(e) => error = Some(e)
      }
    error.toLeft(out.result())
  }

  /** How many of `memories` `filter` accepts, or the first error among them. */
  private def countMatching(memories: Iterator[Result[Memory]], filter: MemoryFilter): Result[Long] = {
    var n                       = 0L
    var error: Option[LLMError] = None
    while (error.isEmpty && memories.hasNext)
      memories.next() match {
        case Right(m) => if (filter.matches(m)) n += 1
        case Left(e)  => error = Some(e)
      }
    error.toLeft(n)
  }

  /**
   * The `topK` best keyword matches among `memories` (newest first), best first, ties to the newer; a memory that
   * matches no phrase is left out. Only `topK` candidates are held at a time.
   */
  private[memory] def topScored(
    memories: Iterator[Result[Memory]],
    phrases: Seq[Vector[String]],
    topK: Int
  ): Result[Seq[ScoredMemory]] = {
    // The head is the worst kept candidate: the lowest score, then the oldest (read last)
    val worstFirst = Ordering.by[(Double, Int, Memory), (Double, Int)] { case (score, order, _) => (-score, order) }
    val kept       = mutable.PriorityQueue.empty[(Double, Int, Memory)](worstFirst)
    var order      = 0
    var error: Option[LLMError] = None
    while (error.isEmpty && memories.hasNext) {
      memories.next() match {
        case Right(memory) =>
          val score = KeywordTokens.score(phrases, memory.content)
          if (score > 0) {
            kept.enqueue((score, order, memory))
            if (kept.size > topK) kept.dequeue(): Unit
          }
        case Left(e) => error = Some(e)
      }
      order += 1
    }
    error.toLeft(kept.toSeq.sortBy { case (score, o, _) => (-score, o) }.map { case (s, _, m) => ScoredMemory(m, s) })
  }

  private[memory] def metadataToJson(metadata: Map[String, String]): String =
    if (metadata.isEmpty) "{}"
    else write(metadata)

  private[memory] def jsonToMetadata(json: String): Map[String, String] =
    if (json == null || json == "{}" || json.isEmpty) Map.empty
    else {
      Try(read(json)).toOption match {
        case Some(Obj(items)) =>
          items.map {
            case (k, Str(s)) => k -> s
            case (k, v)      => k -> v.toString()
          }.toMap
        case _ => Map.empty
      }
    }

}
