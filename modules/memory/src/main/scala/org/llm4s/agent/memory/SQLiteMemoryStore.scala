package org.llm4s.agent.memory

import org.llm4s.error.{ ConfigurationError, NotFoundError, ProcessingError, ValidationError }
import org.llm4s.types.Result
import org.llm4s.util.PrivateSqliteFile
import org.llm4s.vectorstore.SearchScores

import java.sql.{ Connection, DriverManager, PreparedStatement, ResultSet, SQLException }
import java.time.Instant
import scala.util.{ Try, Using }

/**
 * SQLite-backed implementation of MemoryStore.
 *
 * Provides persistent storage for memories using SQLite database.
 * Supports full-text search via SQLite FTS5 extension.
 *
 * Thread Safety: This implementation is NOT thread-safe. For concurrent
 * access, use connection pooling or synchronization.
 *
 * Writes: `store` and `storeAll` are each one transaction - a batch is stored whole or not at all - and so are
 * `deleteMatching` and opening the store. A write takes the database's write lock when it begins, waiting up to the
 * connection's busy timeout (sqlite-jdbc's default, 3 seconds) for a writer on another connection to the same file.
 *
 * Semantic search: given an [[EmbeddingService]] ([[SQLiteMemoryStore.withEmbeddingService]]), the store embeds every
 * memory it writes without an embedding (`store`, `storeAll`, `replace`, `applyAtomically`, and `update` when the
 * content changes), before the write's transaction, and `search` ranks by similarity under the shared score rule
 * (`org.llm4s.vectorstore.SearchScores`), falling back to its full-text index when nothing the filter admits has an
 * embedding or the query cannot be embedded (#3039). It is then both semantic and transactional. Without one, `search`
 * is full-text only.
 *
 * A file written by `VectorMemoryStore` before 1.1 (its own `memories` columns) is migrated to this schema when it is
 * opened, so either store opens either file.
 *
 * @param dbPath Path to SQLite database file (use ":memory:" for in-memory)
 * @param config Store configuration
 */
final class SQLiteMemoryStore private (
  val dbPath: String,
  val config: MemoryStoreConfig,
  private val connection: Connection,
  val embeddingService: Option[EmbeddingService]
) extends TransactionalMemoryStore
    with AutoCloseable {

  import SQLiteMemoryStore._
  import FilterSupport.Sql

  /**
   * The database file's real path - links resolved, and on a case-insensitive file system its stored spelling - so
   * two stores opened on one file by different spellings (`Alice.db`, `alice.db`, `./x/../alice.db`) have one
   * identity. `None` for an in-memory database, which only this connection sees.
   */
  override def storageIdentity: Result[Option[String]] = fileIdentity

  private lazy val fileIdentity: Result[Option[String]] = SQLiteMemoryStore.fileIdentity(dbPath)

  /** True for a database in a file; false in memory, private or shared by name, which dies with its last connection. */
  override def durable: Boolean = fileIdentity.exists(_.exists(_.startsWith("sqlite:")))

  /** Stores `add`, then deletes `remove`, in one transaction, after embedding what `add` is missing. */
  override def replace(add: Seq[Memory], remove: Seq[MemoryId]): Result[MemoryStore] =
    embedMissing(add).flatMap { adds =>
      Try {
        inTransaction {
          writeAll(adds)
          deleteIds(remove.map(_.value)) // store, then delete: an id in both goes, as `MemoryStore.replace` says
        }
        this
      }.toEither.left.map(e => ProcessingError("sqlite-replace", s"Failed to replace memories: ${e.getMessage}"))
    }

  /**
   * `memories` with every missing embedding computed by the embedding service, in batches, before any transaction;
   * unchanged without a service.
   */
  private def embedMissing(memories: Seq[Memory]): Result[Seq[Memory]] =
    embeddingService.fold[Result[Seq[Memory]]](Right(memories))(service =>
      MemoryEmbeddings.embedMissing(service, memories, "sqlite-memory-store")
    )

  /**
   * A key already applied replays its outcome without asking the embedding service (which may be down); otherwise the
   * memories are embedded first, so no embedding request runs while the write lock is held.
   */
  override def applyAtomically(
    key: AppliedWriteKey,
    token: Long,
    add: Seq[Memory],
    remove: Seq[MemoryId],
    bounds: MemoryBounds
  ): Result[(TransactionalMemoryStore, AppliedWrite)] =
    Try(readApplied(key.value).isDefined).toEither.left
      .map(e => ProcessingError("sqlite-apply", s"Failed to read applied write: ${e.getMessage}"))
      .flatMap(applied => if (applied) Right(add) else embedMissing(add))
      .flatMap(adds => applyEmbedded(key, token, adds, remove, bounds))

  private def applyEmbedded(
    key: AppliedWriteKey,
    token: Long,
    add: Seq[Memory],
    remove: Seq[MemoryId],
    bounds: MemoryBounds
  ): Result[(TransactionalMemoryStore, AppliedWrite)] =
    fencedTransaction("sqlite-apply", key, token) {
      readApplied(key.value).getOrElse {
        val outcome =
          if (!remove.forall(present)) TransactionalMemoryStore.Superseded
          else {
            val adds    = TransactionalMemoryStore.distinctById(add)
            val addIds  = adds.map(_.id).toSet
            val removed = remove.distinct.filterNot(addIds.contains).toVector
            deleteIds(removed.map(_.value))
            writeAll(adds)
            val evicted = bounds.overflow(entries(bounds))
            deleteIds(evicted.map(_.value))
            AppliedWrite(adds.map(_.id), removed, evicted.size)
          }
        bindAndUpdate(
          "INSERT INTO memory_applied_writes (key, scope, outcome) VALUES (?, ?, ?)",
          Seq(key.value, key.scope, ujson.write(AppliedWrite.toJson(outcome)))
        )
        outcome
      }
    }.map(this -> _)

  override def appliedWrite(
    key: AppliedWriteKey,
    token: Long
  ): Result[(TransactionalMemoryStore, Option[AppliedWrite])] =
    fencedTransaction("sqlite-applied", key, token)(readApplied(key.value)).map(this -> _)

  override def forgetApplied(key: AppliedWriteKey, token: Long): Result[TransactionalMemoryStore] =
    fencedTransaction("sqlite-forget", key, token) {
      bindAndUpdate("DELETE FROM memory_applied_writes WHERE key = ?", Seq(key.value)): Unit
    }.map(_ => this)

  override def appliedWriteCount: Result[Int] =
    Try {
      Using.resource(connection.createStatement()) { stmt =>
        Using.resource(stmt.executeQuery("SELECT COUNT(*) FROM memory_applied_writes"))(rs =>
          if (rs.next()) rs.getInt(1) else 0
        )
      }
    }.toEither.left.map(e =>
      ProcessingError("sqlite-applied-count", s"Failed to count applied writes: ${e.getMessage}")
    )

  /**
   * `body` in one write transaction, after fencing `token` for `key.scope`: a token older than the scope's newest is
   * refused, rolling back and changing nothing; otherwise it becomes the newest.
   */
  private def fencedTransaction[A](operation: String, key: AppliedWriteKey, token: Long)(body: => A): Result[A] =
    Try {
      inTransaction {
        val newest = Using.resource(connection.prepareStatement("SELECT token FROM memory_fences WHERE scope = ?")) {
          stmt =>
            stmt.setString(1, key.scope)
            Using.resource(stmt.executeQuery())(rs => if (rs.next()) Some(rs.getLong(1)) else None)
        }
        newest match {
          case Some(n) if token < n => Left(TransactionalMemoryStore.stale(key.scope, token, n))
          case _ =>
            bindAndUpdate(
              "INSERT INTO memory_fences (scope, token) VALUES (?, ?) " +
                "ON CONFLICT(scope) DO UPDATE SET token = excluded.token",
              Seq(key.scope, token)
            )
            Right(body)
        }
      }
    }.toEither.left
      .map(e => ProcessingError(operation, s"Failed to apply memory write: ${e.getMessage}"))
      .flatten

  private def readApplied(key: String): Option[AppliedWrite] =
    Using.resource(connection.prepareStatement("SELECT outcome FROM memory_applied_writes WHERE key = ?")) { stmt =>
      stmt.setString(1, key)
      Using.resource(stmt.executeQuery()) { rs =>
        if (rs.next())
          Some(
            AppliedWrite
              .fromJson(ujson.read(rs.getString(1)))
              .fold(e => throw new IllegalStateException(e.message), identity)
          )
        else None
      }
    }

  private def present(id: MemoryId): Boolean =
    Using.resource(connection.prepareStatement("SELECT 1 FROM memories WHERE id = ?")) { stmt =>
      stmt.setString(1, id.value)
      Using.resource(stmt.executeQuery())(_.next())
    }

  /**
   * Every stored memory `bounds` counts, as [[MemoryBounds.overflow]] ranks it, without its embedding. A bound with an
   * owner reads only the owner's rows (#3040), never the whole table.
   */
  private def entries(bounds: MemoryBounds): Vector[MemoryBounds.Entry] = {
    val owner = bounds.owner.flatMap(o => FilterSupport.jsonPath(o.key).map(_ -> o.value))
    val sql = "SELECT id, importance, timestamp, content, metadata_json FROM memories" +
      owner.fold("")(_ => " WHERE json_valid(metadata_json) AND json_extract(metadata_json, ?) = ?")
    Using.resource(connection.prepareStatement(sql)) { stmt =>
      owner.foreach { case (path, value) => stmt.setString(1, path); stmt.setString(2, value) }
      Using.resource(stmt.executeQuery()) { rs =>
        val out = Vector.newBuilder[MemoryBounds.Entry]
        while (rs.next()) {
          val importance = rs.getDouble(2)
          val known      = !rs.wasNull()
          val metadata   = jsonToMetadata(rs.getString(5))
          if (bounds.counts(metadata))
            out += MemoryBounds.Entry(
              MemoryId(rs.getString(1)),
              if (known) Some(importance) else None,
              Instant.ofEpochMilli(rs.getLong(3)),
              MemoryBounds.sizeOf(rs.getString(4), metadata)
            )
        }
        out.result()
      }
    }
  }

  override def store(memory: Memory): Result[MemoryStore] =
    embedMissing(Seq(memory)).flatMap { embedded =>
      Try {
        inTransaction(writeAll(embedded))
        this
      }.toEither.left.map(e => ProcessingError("sqlite-store", s"Failed to store memory: ${e.getMessage}"))
    }

  /**
   * Store `memories` in one transaction: all of them are stored or, if any fails, none is. The commit - and the
   * file sync behind it - is paid once for the batch, not once per row.
   */
  override def storeAll(memories: Seq[Memory]): Result[MemoryStore] =
    embedMissing(memories).flatMap { embedded =>
      Try {
        if (embedded.nonEmpty) inTransaction(writeAll(embedded))
        this
      }.toEither.left.map(e => ProcessingError("sqlite-store", s"Failed to store memories: ${e.getMessage}"))
    }

  /**
   * Upsert each memory and replace its full-text entry, in order, reusing three prepared statements. The caller
   * supplies the transaction.
   */
  private def writeAll(memories: Seq[Memory]): Unit = {
    val sql =
      """INSERT INTO memories (id, content, memory_type, timestamp, importance, conversation_id, entity_id, source, metadata_json, embedding_blob)
        |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        |ON CONFLICT(id) DO UPDATE SET
        |  content = excluded.content,
        |  memory_type = excluded.memory_type,
        |  timestamp = excluded.timestamp,
        |  importance = excluded.importance,
        |  conversation_id = excluded.conversation_id,
        |  entity_id = excluded.entity_id,
        |  source = excluded.source,
        |  metadata_json = excluded.metadata_json,
        |  embedding_blob = excluded.embedding_blob""".stripMargin

    Using.Manager { use =>
      val stmt      = use(connection.prepareStatement(sql))
      val ftsDelete = use(connection.prepareStatement("DELETE FROM memories_fts WHERE id = ?"))
      val ftsInsert = use(connection.prepareStatement("INSERT INTO memories_fts (id, content) VALUES (?, ?)"))
      memories.foreach { memory =>
        stmt.setString(1, memory.id.value)
        stmt.setString(2, memory.content)
        stmt.setString(3, MemoryTypeCodec.encode(memory.memoryType))
        stmt.setLong(4, memory.timestamp.toEpochMilli)
        memory.importance match {
          case Some(imp) => stmt.setDouble(5, imp)
          case None      => stmt.setNull(5, java.sql.Types.DOUBLE)
        }
        // Extract from metadata for efficient querying
        memory.conversationId match {
          case Some(cid) => stmt.setString(6, cid)
          case None      => stmt.setNull(6, java.sql.Types.VARCHAR)
        }
        memory.getMetadata("entity_id") match {
          case Some(eid) => stmt.setString(7, eid)
          case None      => stmt.setNull(7, java.sql.Types.VARCHAR)
        }
        memory.source match {
          case Some(src) => stmt.setString(8, src)
          case None      => stmt.setNull(8, java.sql.Types.VARCHAR)
        }
        stmt.setString(9, metadataToJson(memory.metadata))
        memory.embedding match {
          case Some(emb) => stmt.setBytes(10, embeddingToBytes(emb))
          case None      => stmt.setNull(10, java.sql.Types.BLOB)
        }
        stmt.executeUpdate()

        // Replace the full-text entry
        ftsDelete.setString(1, memory.id.value)
        ftsDelete.executeUpdate()
        ftsInsert.setString(1, memory.id.value)
        ftsInsert.setString(2, memory.content)
        ftsInsert.executeUpdate()
      }
    }.get
  }

  override def get(id: MemoryId): Result[Option[Memory]] =
    Try {
      val sql = "SELECT * FROM memories WHERE id = ?"
      Using.resource(connection.prepareStatement(sql)) { stmt =>
        stmt.setString(1, id.value)
        Using.resource(stmt.executeQuery()) { rs =>
          if (rs.next()) Some(rowToMemory(rs))
          else None
        }
      }
    }.toEither.left.map(e => ProcessingError("sqlite-get", s"Failed to get memory: ${e.getMessage}"))

  override def recall(
    filter: MemoryFilter = MemoryFilter.All,
    limit: Int = 100
  ): Result[Seq[Memory]] =
    if (limit <= 0) Right(Seq.empty)
    else
      recallSome(filter, limit)

  private def recallSome(filter: MemoryFilter, limit: Int): Result[Seq[Memory]] =
    Try {
      // SQL narrows, `matches` decides: what SQL cannot express exactly (a Custom predicate is code) is decided on
      // the rows read, newest first, so the limit applies to the memories that match and not to the newest overall.
      val plan = narrowing(filter)
      val sql =
        if (plan.needsMatches) s"SELECT * FROM memories ${plan.where} ORDER BY timestamp DESC, id DESC"
        else s"SELECT * FROM memories ${plan.where} ORDER BY timestamp DESC, id DESC LIMIT ?"
      Using.resource(connection.prepareStatement(sql)) { stmt =>
        plan.params.zipWithIndex.foreach { case (param, idx) =>
          setParameter(stmt, idx + 1, param)
        }
        if (!plan.needsMatches) stmt.setInt(plan.params.length + 1, limit)
        Using.resource(stmt.executeQuery()) { rs =>
          val memories = Iterator.continually(rs).takeWhile(_.next()).map(rowToMemory)
          (if (plan.needsMatches) memories.filter(filter.matches).take(limit) else memories).toSeq
        }
      }
    }.toEither.left.map(e => ProcessingError("sqlite-recall", s"Failed to recall memories: ${e.getMessage}"))

  /**
   * Full-text search by FTS5: the memories `filter` accepts holding any of the query's phrases, best first. FTS5's
   * `bm25` is negative, more negative for a better match; it scores `s / (1 + s)` with `s = -bm25`
   * (`org.llm4s.vectorstore.SearchScores`), so the better match scores higher. A blank query, or a `topK` below 1,
   * finds nothing.
   */
  override def search(
    query: String,
    topK: Int = 10,
    filter: MemoryFilter = MemoryFilter.All
  ): Result[Seq[ScoredMemory]] =
    if (query.trim.isEmpty || topK <= 0) Right(Seq.empty)
    else
      embeddingService match {
        case None => searchSome(query, topK, filter)
        case Some(service) =>
          Try(service.embedQuery(query)).toEither.left
            .map(e => ProcessingError("sqlite-search", s"Embedding the query failed: ${e.getMessage}"))
            .flatten match {
            case Right(vector) =>
              semanticSearch(vector, topK, filter).flatMap {
                case Some(found) => Right(found)
                case None        => searchSome(query, topK, filter) // nothing the filter admits is embedded
              }
            case Left(cancelled: org.llm4s.error.CancelledError) => Left(cancelled)
            case Left(_)                                         => searchSome(query, topK, filter)
          }
      }

  /**
   * The `topK` memories `filter` accepts nearest to `query`, or `None` when none of them has an embedding (the caller
   * then searches the full-text index). Only the ids and vectors of the candidates are read and decoded, of the
   * query's dimension only (`length(embedding_blob)`), unless `matches` must decide the filter; the `topK` memories are
   * then read whole. Candidates all of another dimension are a `ConfigurationError`.
   */
  private def semanticSearch(
    query: Array[Float],
    topK: Int,
    filter: MemoryFilter
  ): Result[Option[Seq[ScoredMemory]]] =
    Try {
      val plan     = narrowing(filter)
      val embedded = "embedding_blob IS NOT NULL AND length(embedding_blob) > 0"
      val where    = plan.sql.fold(s"WHERE $embedded")(s => s"WHERE (${s.clause}) AND $embedded")
      if (plan.needsMatches) {
        // `matches` decides: read the candidate rows whole, keep those it accepts
        val sql = s"SELECT * FROM memories $where"
        Using
          .resource(connection.prepareStatement(sql)) { stmt =>
            plan.params.zipWithIndex.foreach { case (p, i) => setParameter(stmt, i + 1, p) }
            Using.resource(stmt.executeQuery()) { rs =>
              Iterator
                .continually(rs)
                .takeWhile(_.next())
                .map(rowToMemory)
                .filter(filter.matches)
                .flatMap(m => m.embedding.map(m -> _))
                .toVector
            }
          }
          .map { case (m, v) => (m.id, v) }
      } else {
        // the vectors of the query's dimension, and one row of any other, to tell "none embedded" from "mismatch"
        val sql =
          s"SELECT id, embedding_blob FROM memories $where AND length(embedding_blob) = ? " +
            s"UNION ALL SELECT * FROM (SELECT id, embedding_blob FROM memories $where AND length(embedding_blob) <> ? LIMIT 1)"
        val bytes = query.length * 4
        Using.resource(connection.prepareStatement(sql)) { stmt =>
          val params = (plan.params :+ bytes) ++ (plan.params :+ bytes)
          params.zipWithIndex.foreach { case (p, i) => setParameter(stmt, i + 1, p) }
          Using.resource(stmt.executeQuery()) { rs =>
            Iterator
              .continually(rs)
              .takeWhile(_.next())
              .map(r => MemoryId(r.getString(1)) -> bytesToEmbedding(r.getBytes(2)))
              .toVector
          }
        }
      }
    }.toEither.left
      .map(e => ProcessingError("sqlite-search", s"Failed to search memories: ${e.getMessage}"))
      .flatMap { candidates =>
        if (candidates.isEmpty) Right(None)
        else
          MemoryEmbeddings
            .rankIds(query, candidates)
            .flatMap { scored =>
              val best = scored.sortBy(-_._2).take(topK)
              readAll(best.map(_._1))
                .map(found => Some(best.flatMap { case (id, score) => found.get(id).map(ScoredMemory(_, score)) }))
            }
      }

  /** The memories of `ids`, by id. */
  private def readAll(ids: Seq[MemoryId]): Result[Map[MemoryId, Memory]] =
    if (ids.isEmpty) Right(Map.empty)
    else
      Try {
        val sql = s"SELECT * FROM memories WHERE id IN (${ids.map(_ => "?").mkString(",")})"
        Using.resource(connection.prepareStatement(sql)) { stmt =>
          ids.zipWithIndex.foreach { case (id, i) => stmt.setString(i + 1, id.value) }
          Using.resource(stmt.executeQuery()) { rs =>
            Iterator.continually(rs).takeWhile(_.next()).map(rowToMemory).map(m => m.id -> m).toMap
          }
        }
      }.toEither.left.map(e => ProcessingError("sqlite-search", s"Failed to read memories: ${e.getMessage}"))

  private def searchSome(query: String, topK: Int, filter: MemoryFilter): Result[Seq[ScoredMemory]] =
    Try {
      // Use FTS5 for full-text search. What SQL cannot narrow exactly is decided by `matches` after the full-text
      // match and before topK, so the limit is applied to the memories the filter accepts.
      val plan = narrowing(filter)

      // Build query to join with FTS table
      val sql = if (plan.where.isEmpty) {
        """SELECT m.*, bm25(memories_fts) as score
          |FROM memories m
          |JOIN memories_fts fts ON m.id = fts.id
          |WHERE fts.content MATCH ?
          |ORDER BY score
          |LIMIT ?""".stripMargin
      } else {
        // Filter in a subquery: a bare `content` in the filter would be ambiguous with the FTS table's column
        s"""SELECT m.*, bm25(memories_fts) as score
           |FROM (SELECT * FROM memories ${plan.where}) m
           |JOIN memories_fts fts ON m.id = fts.id
           |WHERE fts.content MATCH ?
           |ORDER BY score
           |LIMIT ?""".stripMargin
      }

      Using.resource(connection.prepareStatement(sql)) { stmt =>
        plan.params.zipWithIndex.foreach { case (param, idx) =>
          setParameter(stmt, idx + 1, param)
        }
        stmt.setString(plan.params.length + 1, escapeFtsQuery(query))
        // SQLite: a negative LIMIT means no limit
        stmt.setInt(plan.params.length + 2, if (plan.needsMatches) -1 else topK)
        Using.resource(stmt.executeQuery()) { rs =>
          val scored = Iterator
            .continually(rs)
            .takeWhile(_.next())
            .map { row =>
              val memory = rowToMemory(row)
              ScoredMemory(memory, SearchScores.fromBm25(row.getDouble("score")))
            }
          (if (plan.needsMatches) scored.filter(sm => filter.matches(sm.memory)).take(topK) else scored).toSeq
        }
      }
    }.toEither.left.map(e => ProcessingError("sqlite-search", s"Failed to search memories: ${e.getMessage}"))

  /** Delete the memory and its full-text entry, in one transaction. */
  override def delete(id: MemoryId): Result[MemoryStore] =
    Try {
      inTransaction(deleteIds(Seq(id.value)))
      this
    }.toEither.left.map(e => ProcessingError("sqlite-delete", s"Failed to delete memory: ${e.getMessage}"))

  override def deleteMatching(filter: MemoryFilter): Result[MemoryStore] =
    Try {
      val plan = narrowing(filter)
      // One transaction: the whole delete happens or none of it, and the commit is paid once, not once per row.
      inTransaction {
        if (plan.exact) {
          // SQL says exactly which rows go: delete them in SQL, from the full-text index and the table, without
          // reading a single row into memory.
          bindAndUpdate(s"DELETE FROM memories_fts WHERE id IN (SELECT id FROM memories ${plan.where})", plan.params)
          bindAndUpdate(s"DELETE FROM memories ${plan.where}", plan.params)
        } else {
          // `matches` decides: stream the narrowed rows (without the embedding unless the filter can read it),
          // keep only the ids it accepts, then delete those.
          val ids = Vector.newBuilder[String]
          foreachMatching(filter, plan)(m => ids += m.id.value)
          deleteIds(ids.result())
        }
      }
      this: MemoryStore
    }.toEither.left.map(e =>
      ProcessingError("sqlite-delete-matching", s"Failed to delete matching memories: ${e.getMessage}")
    )

  private def bindAndUpdate(sql: String, params: Seq[Any]): Int =
    Using.resource(connection.prepareStatement(sql)) { stmt =>
      params.zipWithIndex.foreach { case (param, idx) =>
        setParameter(stmt, idx + 1, param)
      }
      stmt.executeUpdate()
    }

  /** Delete `ids` from the full-text index and the table, as two batches. */
  private def deleteIds(ids: Seq[String]): Unit =
    if (ids.nonEmpty)
      Seq("DELETE FROM memories_fts WHERE id = ?", "DELETE FROM memories WHERE id = ?").foreach { sql =>
        Using.resource(connection.prepareStatement(sql)) { stmt =>
          ids.foreach { id =>
            stmt.setString(1, id)
            stmt.addBatch()
          }
          stmt.executeBatch()
        }
      }

  /**
   * Call `f` on each memory `filter` accepts among the rows `plan` narrows to, one row at a time: nothing is
   * materialised. The embedding is read and decoded only if the filter can look at it (a `Custom` predicate).
   */
  private def foreachMatching(filter: MemoryFilter, plan: FilterSupport.Narrowing)(f: Memory => Unit): Unit = {
    val embedding = if (FilterSupport.readsEmbedding(filter)) "embedding_blob" else "NULL AS embedding_blob"
    val sql =
      s"SELECT id, content, memory_type, timestamp, importance, metadata_json, $embedding FROM memories ${plan.where}"
    Using.resource(connection.prepareStatement(sql)) { stmt =>
      plan.params.zipWithIndex.foreach { case (param, idx) =>
        setParameter(stmt, idx + 1, param)
      }
      Using.resource(stmt.executeQuery()) { rs =>
        while (rs.next()) {
          val memory = rowToMemory(rs)
          if (filter.matches(memory)) f(memory)
        }
      }
    }
  }

  /** Run `body` as one write transaction: committed if it returns, rolled back otherwise. */
  private def inTransaction[A](body: => A): A = SqliteTransaction.immediate(connection)(body)

  override def update(id: MemoryId, updateFn: Memory => Memory): Result[MemoryStore] =
    for {
      maybeMemory <- get(id)
      memory <- maybeMemory.toRight(
        NotFoundError(s"Memory not found: ${id.value}", id.value)
      )
      updated = MemoryEmbeddings.afterUpdate(memory, updateFn(memory))
      _ <- Either.cond(
        updated.id == id,
        (),
        ValidationError("id", s"update function changed Memory ID from $id to ${updated.id}; IDs must remain constant")
      )
      embedded <- embedMissing(Seq(updated))
      result <- Try {
        inTransaction(writeAll(embedded))
        this: MemoryStore
      }.toEither.left.map(e => ProcessingError("sqlite-update", s"Failed to update memory: ${e.getMessage}"))
    } yield result

  override def count(filter: MemoryFilter = MemoryFilter.All): Result[Long] =
    Try {
      val plan = narrowing(filter)
      if (plan.needsMatches) {
        // SQL cannot say which rows match exactly: count the rows `matches` accepts among those read.
        var n = 0L
        foreachMatching(filter, plan)(_ => n += 1)
        n
      } else {
        val sql = s"SELECT COUNT(*) FROM memories ${plan.where}"
        Using.resource(connection.prepareStatement(sql)) { stmt =>
          plan.params.zipWithIndex.foreach { case (param, idx) =>
            setParameter(stmt, idx + 1, param)
          }
          Using.resource(stmt.executeQuery()) { rs =>
            if (rs.next()) rs.getLong(1)
            else 0L
          }
        }
      }
    }.toEither.left.map(e => ProcessingError("sqlite-count", s"Failed to count memories: ${e.getMessage}"))

  override def clear(): Result[MemoryStore] =
    Try {
      inTransaction(Using.resource(connection.createStatement()) { stmt =>
        stmt.executeUpdate("DELETE FROM memories_fts")
        stmt.executeUpdate("DELETE FROM memories")
      })
      this
    }.toEither.left.map(e => ProcessingError("sqlite-clear", s"Failed to clear memories: ${e.getMessage}"))

  override def recent(limit: Int = 10, filter: MemoryFilter = MemoryFilter.All): Result[Seq[Memory]] =
    recall(filter, limit)

  /**
   * Close the database connection.
   * Should be called when the store is no longer needed.
   */
  override def close(): Unit =
    if (!connection.isClosed) {
      connection.close()
    }

  /** Embed every memory that has no embedding, `batchSize` at a time; needs an embedding service. */
  def embedAll(batchSize: Int = VectorMemoryStore.EmbeddingBatchSize): Result[Int] = embedWhere(batchSize, None)

  /**
   * Embed every memory with no embedding or one whose dimension is not `dimension`, `batchSize` at a time: after a
   * change of embedding model, what the old one wrote is otherwise never compared with a query. Needs an embedding
   * service.
   */
  def reembedAll(dimension: Int, batchSize: Int = VectorMemoryStore.EmbeddingBatchSize): Result[Int] =
    if (dimension <= 0) Left(ProcessingError("sqlite-embed", s"reembedAll needs a positive dimension, got $dimension"))
    else embedWhere(batchSize, Some(dimension))

  /**
   * Every memory without an embedding (or, with `dimension`, without one of that dimension) is visited once, by id:
   * keyset pagination, so a service that answers with fewer vectors than texts is an error, never a loop. A memory
   * whose content changes meanwhile is left for its own write to embed.
   */
  private def embedWhere(batchSize: Int, dimension: Option[Int]): Result[Int] =
    embeddingService match {
      case None => Left(ProcessingError("sqlite-embed", "embedding needs a store with an embedding service"))
      case Some(_) if batchSize <= 0 =>
        Left(ProcessingError("sqlite-embed", s"embedding needs a positive batch size, got $batchSize"))
      case Some(service) =>
        val wanted =
          dimension.fold("(embedding_blob IS NULL OR length(embedding_blob) = 0)")(_ =>
            "(embedding_blob IS NULL OR length(embedding_blob) <> ?)"
          )
        val wantedParams: Seq[Any] = dimension.map(_ * 4).toSeq

        def fetch(after: Option[String]): Result[Vector[(String, String)]] =
          Try {
            val sql = s"SELECT id, content FROM memories WHERE $wanted" + after.fold("")(_ => " AND id > ?") +
              " ORDER BY id LIMIT ?"
            Using.resource(connection.prepareStatement(sql)) { stmt =>
              (wantedParams ++ after.toSeq :+ batchSize).zipWithIndex.foreach { case (p, i) =>
                setParameter(stmt, i + 1, p)
              }
              Using.resource(stmt.executeQuery()) { rs =>
                Iterator.continually(rs).takeWhile(_.next()).map(r => r.getString(1) -> r.getString(2)).toVector
              }
            }
          }.toEither.left.map(e => ProcessingError("sqlite-embed", s"Failed to read memories: ${e.getMessage}"))

        def write(rows: Vector[(String, String)], vectors: Seq[Array[Float]]): Result[Int] =
          Try {
            inTransaction {
              Using.resource(
                connection.prepareStatement("UPDATE memories SET embedding_blob = ? WHERE id = ? AND content = ?")
              ) { stmt =>
                rows
                  .zip(vectors)
                  .map { case ((id, content), vector) =>
                    stmt.setBytes(1, embeddingToBytes(vector))
                    stmt.setString(2, id)
                    stmt.setString(3, content)
                    stmt.executeUpdate()
                  }
                  .sum
              }
            }
          }.toEither.left.map(e => ProcessingError("sqlite-embed", s"Failed to store embeddings: ${e.getMessage}"))

        @scala.annotation.tailrec
        def loop(after: Option[String], total: Int): Result[Int] =
          fetch(after).flatMap { rows =>
            if (rows.isEmpty) Right(rows -> 0)
            else
              MemoryEmbeddings
                .embedMissing(
                  service,
                  rows.map { case (id, content) => Memory(MemoryId(id), content, MemoryType.Task) },
                  "sqlite-embed",
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

  /** The store's statistics, by aggregate queries: no memory is read (#3040). */
  override def stats: Result[MemoryStats] =
    Try {
      Using.resource(connection.createStatement()) { stmt =>
        val byType =
          Using.resource(stmt.executeQuery("SELECT memory_type, COUNT(*) FROM memories GROUP BY memory_type")) { rs =>
            Iterator
              .continually(rs)
              .takeWhile(_.next())
              .map(r => MemoryTypeCodec.decode(r.getString(1)) -> r.getLong(2))
              .toVector
              .groupMapReduce(_._1)(_._2)(_ + _)
          }
        Using.resource(
          stmt.executeQuery(
            "SELECT COUNT(*), COUNT(DISTINCT entity_id), COUNT(DISTINCT conversation_id), " +
              "COUNT(CASE WHEN embedding_blob IS NOT NULL THEN 1 END), MIN(timestamp), MAX(timestamp) FROM memories"
          )
        ) { rs =>
          val total = rs.getLong(1)
          MemoryStats(
            totalMemories = total,
            byType = MemoryStore.builtInCounts(byType),
            entityCount = rs.getLong(2),
            conversationCount = rs.getLong(3),
            embeddedCount = rs.getLong(4),
            oldestMemory = if (total == 0) None else Some(Instant.ofEpochMilli(rs.getLong(5))),
            newestMemory = if (total == 0) None else Some(Instant.ofEpochMilli(rs.getLong(6)))
          )
        }
      }
    }.toEither.left.map(e => ProcessingError("sqlite-stats", s"Failed to count memories: ${e.getMessage}"))

  /** How many memories there are, how many have an embedding, and of which dimensions. */
  def vectorStats: Result[VectorStoreStats] =
    Try {
      Using.resource(connection.createStatement()) { stmt =>
        val (total, embedded) = Using.resource(
          stmt.executeQuery(
            "SELECT COUNT(*), COUNT(CASE WHEN length(embedding_blob) > 0 THEN 1 END) FROM memories"
          )
        )(rs => (rs.getLong(1), rs.getLong(2)))
        val dims = Using.resource(
          stmt.executeQuery("SELECT DISTINCT length(embedding_blob) / 4 FROM memories WHERE length(embedding_blob) > 0")
        )(rs => Iterator.continually(rs).takeWhile(_.next()).map(_.getInt(1)).toSet)
        VectorStoreStats(totalMemories = total, embeddedMemories = embedded, embeddingDimensions = dims)
      }
    }.toEither.left.map(e => ProcessingError("sqlite-stats", s"Failed to get stats: ${e.getMessage}"))

  private def narrowing(filter: MemoryFilter): FilterSupport.Narrowing =
    FilterSupport.narrow(filter)(leafSql)

  /**
   * The SQL of one filter, when it means exactly what `matches` means: a row satisfies it if and only if `matches`
   * accepts the row, and it is never NULL (a NULL under `NOT` would drop a row `Not` accepts), hence the `COALESCE`
   * around every comparison with a nullable column.
   */
  private def leafSql(filter: MemoryFilter): Option[FilterSupport.Sql] = filter match {
    case MemoryFilter.ByType(memoryType) =>
      // A custom type is stored as `custom:<name>`, or as its bare name by a file `VectorMemoryStore` wrote first
      val names = MemoryTypeCodec.spellings(memoryType)
      Some(Sql(s"memory_type IN (${names.map(_ => "?").mkString(",")})", names))

    case MemoryFilter.ByTypes(memoryTypes) =>
      // A Seq, not the Set: mapping a Set to "?" would collapse the placeholders into one
      val names = memoryTypes.toSeq.flatMap(MemoryTypeCodec.spellings).distinct
      if (names.isEmpty) Some(Sql("1 = 0", Seq.empty))
      else Some(Sql(s"memory_type IN (${names.map(_ => "?").mkString(",")})", names))

    case MemoryFilter.ByConversation(conversationId) =>
      Some(Sql("COALESCE(conversation_id = ?, 0)", Seq(conversationId)))

    case MemoryFilter.ByEntity(entityId) =>
      Some(Sql("COALESCE(entity_id = ?, 0)", Seq(entityId.value)))

    case MemoryFilter.ByTimeRange(after, before) =>
      Some(FilterSupport.timeRange(after, before))

    case MemoryFilter.Preceding(timestamp, id) =>
      // the stored time is the memory's truncated to the millisecond, as `matches` reads it back
      FilterSupport.preceding(timestamp).map { case (clause, params) => Sql(clause, params :+ id.value) }

    case MemoryFilter.MinImportance(threshold) =>
      Some(Sql("COALESCE(importance >= ?, 0)", Seq(threshold)))

    case MemoryFilter.ByMetadata(key, value) =>
      jsonPath(key).map(path => Sql("COALESCE(json_extract(metadata_json, ?) = ?, 0)", Seq(path, value)))

    case MemoryFilter.HasMetadata(key) =>
      jsonPath(key).map(path => Sql("json_extract(metadata_json, ?) IS NOT NULL", Seq(path)))

    case MemoryFilter.MetadataContains(key, substring) =>
      // instr is a literal, case-sensitive substring test, like String.contains: LIKE would read % and _ as wildcards
      jsonPath(key).map(path => Sql("COALESCE(instr(json_extract(metadata_json, ?), ?) > 0, 0)", Seq(path, substring)))

    case MemoryFilter.ContentContains(substring, caseSensitive) =>
      // instr is a literal substring test: LIKE would read % and _ as wildcards and is never case sensitive
      if (caseSensitive) Some(Sql("instr(content, ?) > 0", Seq(substring)))
      else Some(Sql(s"instr(${FilterSupport.JavaLower}(content), ${FilterSupport.JavaLower}(?)) > 0", Seq(substring)))

    case _ => scala.None // And, Or, Not, All, None and Custom are narrowed by FilterSupport, not here
  }

  private def jsonPath(key: String): Option[String] = FilterSupport.jsonPath(key)

  private def setParameter(stmt: PreparedStatement, idx: Int, value: Any): Unit = value match {
    case s: String => stmt.setString(idx, s)
    case l: Long   => stmt.setLong(idx, l)
    case d: Double => stmt.setDouble(idx, d)
    case i: Int    => stmt.setInt(idx, i)
    case _         => stmt.setObject(idx, value)
  }

  private def rowToMemory(rs: ResultSet): Memory = {
    val importanceValue = rs.getDouble("importance")
    val importance      = if (rs.wasNull()) None else Some(importanceValue)
    val embeddingBytes  = Option(rs.getBytes("embedding_blob"))

    // Reconstruct metadata from stored JSON
    val storedMetadata = jsonToMetadata(rs.getString("metadata_json"))

    Memory(
      id = MemoryId(rs.getString("id")),
      content = rs.getString("content"),
      memoryType = MemoryTypeCodec.decode(rs.getString("memory_type")),
      metadata = storedMetadata,
      timestamp = Instant.ofEpochMilli(rs.getLong("timestamp")),
      importance = importance,
      embedding = embeddingBytes.map(bytesToEmbedding)
    )
  }
}

object SQLiteMemoryStore {

  /** sqlite-jdbc's pragma names: the URL parameters it takes out of a file name (#3087). */
  private[memory] val JdbcPragmas: Set[String] =
    org.sqlite.SQLiteConfig.Pragma.values.iterator.map(_.pragmaName).toSet

  /**
   * Create a new SQLite memory store.
   *
   * @param dbPath Path to database file, or ":memory:" for in-memory database
   * @param config Store configuration
   * @return Initialized store or error
   */
  def apply(
    dbPath: String,
    config: MemoryStoreConfig = MemoryStoreConfig.default
  ): Result[SQLiteMemoryStore] =
    open(dbPath, config, path => DriverManager.getConnection(s"jdbc:sqlite:$path"))

  /**
   * Open the store on a connection from `connect`. The seam exists so a test can prove that a failed open
   * closes the connection it made.
   */
  /**
   * A store that embeds what it writes with `service` and searches by similarity, falling back to its full-text index
   * (#3039). Both semantic and transactional, so `SemanticMemoryMiddleware` can record, replay and consolidate on it.
   */
  def withEmbeddingService(
    dbPath: String,
    service: EmbeddingService,
    config: MemoryStoreConfig = MemoryStoreConfig.default
  ): Result[SQLiteMemoryStore] =
    open(dbPath, config, path => DriverManager.getConnection(s"jdbc:sqlite:$path"), Some(service))

  private[memory] def open(
    dbPath: String,
    config: MemoryStoreConfig,
    connect: String => Connection,
    embeddingService: Option[EmbeddingService] = None,
    busyTimeoutMillis: Option[Int] = None
  ): Result[SQLiteMemoryStore] =
    Try {
      Class.forName("org.sqlite.JDBC")
      PrivateSqliteFile.prepare(dbPath, JdbcPragmas)
      val connection = connect(dbPath)
      // If setting up the schema fails (e.g. the file is not a database) the connection must not leak: an open
      // handle keeps the file locked, which blocks deleting it on Windows.
      Try {
        connection.setAutoCommit(true)
        FilterSupport.registerJavaLower(connection)
        busyTimeoutMillis.foreach(ms =>
          Using.resource(connection.createStatement())(_.execute(s"PRAGMA busy_timeout = $ms")): Unit
        )
        initializeSchema(connection)
        new SQLiteMemoryStore(dbPath, config, connection, embeddingService)
      }.recoverWith { case e =>
        Try(connection.close())
        scala.util.Failure(e)
      }.get
    }.toEither.left.map {
      case e: ClassNotFoundException =>
        ConfigurationError(s"SQLite JDBC driver not found: ${e.getMessage}")
      case e: SQLException =>
        ProcessingError("sqlite-open", s"Failed to open SQLite database: ${e.getMessage}")
      case e: Throwable =>
        ProcessingError("sqlite-init", s"Unexpected error creating SQLite store: ${e.getMessage}")
    }

  /**
   * Create an in-memory SQLite store for testing.
   */
  def inMemory(config: MemoryStoreConfig = MemoryStoreConfig.testing): Result[SQLiteMemoryStore] =
    apply(":memory:", config)

  /**
   * The identity of the database `dbPath` opens, read as SQLite reads it (https://sqlite.org/uri.html).
   *
   *   - A file: `sqlite:` and its canonical path - the real path, or, for a file not created yet, its directory's real
   *     path and its name, so a later open of the created file agrees.
   *   - A `file:` URI: the path percent-decoded, the query and fragment dropped, an empty or `localhost` authority
   *     dropped (another authority is refused, as SQLite refuses it), and on Windows the `/` before a drive letter.
   *   - An in-memory database shared by name (`file:x?mode=memory&cache=shared`, `file::memory:?cache=shared`), which
   *     every connection of this process that names it opens: `sqlite-memory:` and the name.
   *   - A private in-memory database (`:memory:`, an empty path, `mode=memory` without `cache=shared`): `None`, only
   *     its own connection sees it.
   */
  private[memory] def fileIdentity(dbPath: String): Result[Option[String]] =
    Try {
      if (dbPath.isEmpty || dbPath == ":memory:") None
      else if (!dbPath.startsWith("file:")) Some(s"sqlite:${canonical(dbPath)}")
      else {
        val rest        = dbPath.stripPrefix("file:").takeWhile(_ != '#')
        val (target, q) = rest.span(_ != '?')
        val params      = queryParams(q.drop(1))
        val path        = decode(withoutAuthority(target))
        val memory      = path.isEmpty || path == ":memory:" || params.get("mode").contains("memory")
        val shared      = params.get("cache").contains("shared")
        if (memory) if (shared) Some(s"sqlite-memory:$path") else None
        else Some(s"sqlite:${canonical(onWindows(path))}")
      }
    }.toEither.left.map(e => ProcessingError("sqlite-identity", s"Cannot resolve the path of $dbPath: ${e.getMessage}"))

  /** `target` without its `//authority`: SQLite accepts only an empty one or `localhost`. */
  private def withoutAuthority(target: String): String =
    if (!target.startsWith("//")) target
    else {
      val (authority, path) = target.drop(2).span(_ != '/')
      if (authority.isEmpty || authority.equalsIgnoreCase("localhost")) path
      else throw new IllegalArgumentException(s"invalid uri authority: $authority")
    }

  /** The query's parameters, names and values percent-decoded; a later one wins, as in SQLite. */
  private def queryParams(query: String): Map[String, String] =
    query
      .split('&')
      .iterator
      .filter(_.nonEmpty)
      .map { p =>
        val (k, v) = p.span(_ != '=')
        decode(k) -> decode(v.drop(1))
      }
      .toMap

  /** `s` with every `%XX` escape decoded as UTF-8 bytes; `+` is a plus, as SQLite reads it. */
  private def decode(s: String): String =
    if (!s.contains('%')) s
    else java.net.URLDecoder.decode(s.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8)

  /** On Windows, `/C:/x` is `C:/x`. */
  private def onWindows(path: String): String =
    if (java.io.File.separatorChar == '\\' && path.matches("^/[A-Za-z]:.*")) path.drop(1) else path

  /** `path`'s real path, or, if it does not exist, its directory's real path and its name. */
  private def canonical(path: String): java.nio.file.Path = {
    val named = java.nio.file.Paths.get(path).toAbsolutePath
    Try(named.toRealPath()).getOrElse {
      val file = named.normalize()
      Option(file.getParent).flatMap(dir => Try(dir.toRealPath()).toOption).fold(file)(_.resolve(file.getFileName))
    }
  }

  /**
   * Schema version for migrations: 2 added the applied-write tables; 3 reads a `VectorMemoryStore` file (#3039).
   */
  val SchemaVersion: Int = 3

  /** The columns of `memories` in a file `VectorMemoryStore` wrote before it shared this schema. */
  private val VectorSchemaColumns = Set("metadata", "embedding", "embedding_dim")

  /**
   * Migrate a file written by the old `VectorMemoryStore` to this schema, in the caller's transaction: its `memories`
   * columns are renamed (`metadata` to `metadata_json`, `embedding` to `embedding_blob`; `embedding_dim` is the blob's
   * length), its `schema_version` table (which had an `applied_at` column) is rebuilt, and its full-text index, of the
   * same shape, is kept. A file of this schema is untouched.
   */
  private def migrateVectorSchema(connection: Connection): Unit = {
    val columns = Using.resource(connection.createStatement()) { stmt =>
      Using.resource(stmt.executeQuery("PRAGMA table_info(memories)")) { rs =>
        Iterator.continually(rs).takeWhile(_.next()).map(_.getString("name")).toSet
      }
    }
    if (VectorSchemaColumns.subsetOf(columns) && !columns.contains("metadata_json"))
      Using.resource(connection.createStatement()) { stmt =>
        Seq(
          "ALTER TABLE memories RENAME TO memories_vector_v1",
          """CREATE TABLE memories (
            |  id TEXT PRIMARY KEY,
            |  content TEXT NOT NULL,
            |  memory_type TEXT NOT NULL,
            |  timestamp INTEGER NOT NULL,
            |  importance REAL,
            |  conversation_id TEXT,
            |  entity_id TEXT,
            |  source TEXT,
            |  metadata_json TEXT DEFAULT '{}',
            |  embedding_blob BLOB
            |)""".stripMargin,
          "INSERT INTO memories (id, content, memory_type, timestamp, importance, conversation_id, entity_id, " +
            "source, metadata_json, embedding_blob) SELECT id, content, memory_type, timestamp, importance, " +
            "conversation_id, entity_id, source, COALESCE(metadata, '{}'), embedding FROM memories_vector_v1",
          "DROP TABLE memories_vector_v1",
          "DROP TABLE IF EXISTS schema_version"
        ).foreach(stmt.executeUpdate)
      }
  }

  private def initializeSchema(connection: Connection): Unit = {
    val statements = Seq(
      // Main memories table
      """CREATE TABLE IF NOT EXISTS memories (
        |  id TEXT PRIMARY KEY,
        |  content TEXT NOT NULL,
        |  memory_type TEXT NOT NULL,
        |  timestamp INTEGER NOT NULL,
        |  importance REAL,
        |  conversation_id TEXT,
        |  entity_id TEXT,
        |  source TEXT,
        |  metadata_json TEXT DEFAULT '{}',
        |  embedding_blob BLOB
        |)""".stripMargin,
      // Indexes for common queries
      "CREATE INDEX IF NOT EXISTS idx_memories_type ON memories(memory_type)",
      "CREATE INDEX IF NOT EXISTS idx_memories_conversation ON memories(conversation_id)",
      "CREATE INDEX IF NOT EXISTS idx_memories_entity ON memories(entity_id)",
      "CREATE INDEX IF NOT EXISTS idx_memories_timestamp ON memories(timestamp DESC)",
      "CREATE INDEX IF NOT EXISTS idx_memories_importance ON memories(importance DESC)",
      // FTS5 virtual table for full-text search (standalone, not external content)
      """CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts5(
        |  id UNINDEXED,
        |  content
        |)""".stripMargin,
      // Version 2: the applied writes of `TransactionalMemoryStore`, and the newest fencing token per scope
      """CREATE TABLE IF NOT EXISTS memory_applied_writes (
        |  key TEXT PRIMARY KEY,
        |  scope TEXT NOT NULL,
        |  outcome TEXT NOT NULL
        |)""".stripMargin,
      """CREATE TABLE IF NOT EXISTS memory_fences (
        |  scope TEXT PRIMARY KEY,
        |  token INTEGER NOT NULL
        |)""".stripMargin,
      // Schema version tracking
      """CREATE TABLE IF NOT EXISTS schema_version (
        |  version INTEGER PRIMARY KEY
        |)""".stripMargin,
      s"INSERT OR IGNORE INTO schema_version (version) VALUES ($SchemaVersion)"
    )

    // One transaction, so opening a store commits (and syncs the file) once rather than once per statement.
    SqliteTransaction.immediate(connection) {
      migrateVectorSchema(connection)
      Using.resource(connection.createStatement())(stmt => statements.foreach(stmt.executeUpdate))
    }
  }

  private def metadataToJson(metadata: Map[String, String]): String =
    if (metadata.isEmpty) "{}"
    else ujson.write(ujson.Obj.from(metadata.map { case (k, v) => k -> ujson.Str(v) }))

  /**
   * Metadata from its stored JSON. A real JSON parser: the hand-written decoder this replaced undid its escapes one
   * after another, so a stored `c:\temp` came back as `c:`, a tab and `emp`. Rows written by that encoder are valid
   * JSON, so they read correctly now. Text that is not JSON at all (a control character other than tab, newline
   * or carriage return was written raw) still goes through the old lenient reading.
   */
  private def jsonToMetadata(json: String): Map[String, String] =
    if (json == null || json.isEmpty || json == "{}") Map.empty
    else
      Try(ujson.read(json).obj.collect { case (k, ujson.Str(v)) => k -> v }.toMap)
        .getOrElse(lenientJsonToMetadata(json))

  private def lenientJsonToMetadata(json: String): Map[String, String] = {
    // Simple JSON parsing for flat string maps
    val content = json.trim.stripPrefix("{").stripSuffix("}")
    if (content.isEmpty) Map.empty
    else {
      content
        .split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")
        .flatMap { pair =>
          val parts = pair.split(":", 2)
          if (parts.length == 2) {
            val key   = parts(0).trim.stripPrefix("\"").stripSuffix("\"")
            val value = parts(1).trim.stripPrefix("\"").stripSuffix("\"")
            Some(unescapeJson(key) -> unescapeJson(value))
          } else None
        }
        .toMap
    }
  }

  private def unescapeJson(s: String): String =
    s.replace("\\\"", "\"")
      .replace("\\\\", "\\")
      .replace("\\n", "\n")
      .replace("\\r", "\r")
      .replace("\\t", "\t")

  private def embeddingToBytes(embedding: Array[Float]): Array[Byte] = {
    val buffer = java.nio.ByteBuffer.allocate(embedding.length * 4)
    embedding.foreach(buffer.putFloat)
    buffer.array()
  }

  private def bytesToEmbedding(bytes: Array[Byte]): Array[Float] = {
    val buffer = java.nio.ByteBuffer.wrap(bytes)
    Array.fill(bytes.length / 4)(buffer.getFloat)
  }

  private def escapeFtsQuery(query: String): String = {
    // Split into phrases and create OR query for FTS5
    // FTS5 uses implicit AND, so we use OR for more flexible matching. InMemoryStore splits queries the same way.
    val words = KeywordTokens.queryPhrases(query).map(w => s""""$w"""")

    if (words.isEmpty) "\"\""
    else words.mkString(" OR ")
  }
}
