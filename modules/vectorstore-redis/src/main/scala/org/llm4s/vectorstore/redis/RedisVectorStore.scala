package org.llm4s.vectorstore.redis

import org.llm4s.error.{ AuthenticationError, LLMError, ProcessingError, ValidationError }
import org.llm4s.types.Result
import org.llm4s.vectorstore.{ MetadataFilter, ScoredRecord, VectorRecord, VectorStore, VectorStoreStats }

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong
import scala.annotation.tailrec
import scala.util.Try

/**
 * A [[org.llm4s.vectorstore.VectorStore]] over one search index of a Redis server with the Redis Query Engine (Redis
 * Stack, or Redis 8 and later), or of a Valkey server with `valkey-search`.
 *
 * Each record is a hash at `<keyPrefix><id>` (see [[RedisConfig#effectivePrefix]]). The index is created on the
 * first upsert if it does not exist (`FT.CREATE ... ON HASH`), with a `VECTOR` field sized to
 * [[RedisConfig#dimensions]] (or to the first record) using the configured [[RedisVectorAlgorithm]] and
 * [[RedisDistanceMetric]]. `search` is a KNN query whose metadata filter is a pre-filter, so `topK` results come back
 * when that many match; `count` and `list` are filter queries, `list` sorted by write order.
 *
 * Metadata is indexed as `TAG` values (see `RedisQueries` for the encoding), so every
 * [[org.llm4s.vectorstore.MetadataFilter]] case is a query clause: `Equals` and `In` match entries, `HasKey` matches
 * keys, `Contains` is a wildcard pattern (`w'...'`, Redis Query Engine 2.6 or later; `valkey-search` does not support
 * it) and `And`, `Or` and `Not` combine clauses. A wildcard expands to at most the server's maximum number of terms
 * (`search-max-prefix-expansions`, 200 by default), so `Contains` over a key with more distinct values than that may
 * miss records.
 *
 * Reads of an index that does not exist yet answer as an empty store. `deleteByPrefix` and `clear()` walk the
 * store's keys with `SCAN`; `clear()` also drops the index, which the next upsert creates again. Every operation
 * returns a `Result`: a connection failure is a [[org.llm4s.error.NetworkError]] or a
 * [[org.llm4s.error.TimeoutError]], refused credentials an [[org.llm4s.error.AuthenticationError]], and any other error
 * reply a [[org.llm4s.error.ProcessingError]] carrying the server's message.
 *
 * A search reply is limited by the server's `search-max-search-results` (10,000 by default), so `list`'s
 * `offset + limit` and `search`'s `topK` may not exceed it. The store connects to one standalone server, not to a
 * cluster.
 *
 * The `valkey-search` in Valkey 9.0 answers only vector queries: there, `search` (with any filter but `Contains`) and
 * the operations by ID or key work, while `count` and `stats` over an existing index, `list` and `deleteByFilter` are
 * refused with the server's message.
 */
final class RedisVectorStore private (
  val config: RedisConfig,
  connection: RedisConnection,
  ownsConnection: Boolean
) extends VectorStore {

  import RedisQueries.*
  import RedisVectorStore.*

  private val prefix = config.effectivePrefix

  /** The vector size of the index once it is known to exist; `clear()` resets it. */
  @volatile private var indexDimension: Option[Int] = None

  // ============================================================
  // Writes
  // ============================================================

  override def upsert(record: VectorRecord): Result[Unit] =
    upsertBatch(Seq(record))

  override def upsertBatch(records: Seq[VectorRecord]): Result[Unit] =
    if (records.isEmpty) Right(())
    else
      for {
        wanted   <- Right(config.dimensions.getOrElse(records.head.dimensions))
        _        <- checkDimensions(records, wanted)
        expected <- ensureIndex(wanted)
        _        <- checkDimensions(records, expected)
        commands = records.flatMap { r =>
          val fields = Seq[Arg](
            IdField,
            r.id,
            EmbeddingField,
            vectorBytes(r.embedding),
            SeqField,
            nextSeq().toString,
            MetadataField,
            metadataJson(r.metadata),
            TagsField,
            r.metadata.toSeq.sorted.map { case (k, v) => entryTag(k, v) }.mkString(Separator),
            KeysField,
            r.metadata.keys.toSeq.sorted.map(keyTag).mkString(Separator)
          ) ++ r.content.toSeq.flatMap(c => Seq[Arg](ContentField, c))
          // DEL first, so a field the new record lacks (its content) does not survive from the old one.
          Seq(command("DEL", key(r.id)), command("HSET", (key(r.id) +: fields)*))
        }
        replies <- connection.pipeline(commands)
        _       <- firstError("upsert", replies).toLeft(())
      } yield ()

  override def delete(id: String): Result[Unit] =
    deleteBatch(Seq(id))

  override def deleteBatch(ids: Seq[String]): Result[Unit] =
    if (ids.isEmpty) Right(())
    else run("delete", command("DEL", ids.map(id => key(id): Arg)*)).map(_ => ())

  override def deleteByPrefix(idPrefix: String): Result[Long] =
    scanAndDelete(s"${globLiteral(prefix + idPrefix)}*")

  override def deleteByFilter(filter: MetadataFilter): Result[Long] =
    text(translate(filter)).fold[Result[Long]](Right(0L))(query => deleteMatching(query, 0L))

  override def clear(): Result[Unit] =
    for {
      dropped <- connection.execute(command("FT.DROPINDEX", config.index))
      _ <- dropped match {
        case RedisReply.Error(message) if !isIndexMissing(message) => Left(refused("clear", message))
        case _                                                     => Right(())
      }
      _ = indexDimension = None
      _ <- scanAndDelete(s"${globLiteral(prefix)}*")
    } yield ()

  // ============================================================
  // Reads
  // ============================================================

  override def search(
    queryVector: Array[Float],
    topK: Int,
    filter: Option[MetadataFilter]
  ): Result[Seq[ScoredRecord]] =
    if (topK <= 0) Right(Seq.empty)
    else
      text(translate(filter)) match {
        case None => Right(Seq.empty)
        case Some(query) =>
          val knn = s"${if (query == "*") "*" else s"($query)"}=>[KNN $$K @$EmbeddingField $$BLOB AS $ScoreField]"
          val args = Seq[Arg](config.index, knn, "PARAMS", "4", "K", topK.toString, "BLOB", vectorBytes(queryVector)) ++
            returnFields(ScoreField) ++ Seq[Arg]("LIMIT", "0", topK.toString, "DIALECT", "2")
          searchOptional("search", command("FT.SEARCH", args*)).flatMap { docs =>
            parse("search") {
              docs
                .map { case (k, fields) =>
                  val distance = fields.get(ScoreField).flatMap(textOf).map(_.toDouble).getOrElse(Double.MaxValue)
                  ScoredRecord(toRecord(k, fields), score(distance))
                }
                .sortBy(-_.score)
            }
          }
      }

  override def get(id: String): Result[Option[VectorRecord]] =
    run("get", command("HGETALL", key(id))).flatMap(reply => parse("get")(hashRecord(id, reply)))

  override def getBatch(ids: Seq[String]): Result[Seq[VectorRecord]] =
    if (ids.isEmpty) Right(Seq.empty)
    else
      connection.pipeline(ids.map(id => command("HGETALL", key(id)))).flatMap { replies =>
        firstError("get batch", replies).toLeft(()).flatMap { _ =>
          parse("get batch")(ids.zip(replies).flatMap { case (id, reply) => hashRecord(id, reply) })
        }
      }

  override def count(filter: Option[MetadataFilter]): Result[Long] =
    text(translate(filter)).fold[Result[Long]](Right(0L))(countMatching)

  override def list(limit: Int, offset: Int, filter: Option[MetadataFilter]): Result[Seq[VectorRecord]] =
    if (offset < 0) Left(ValidationError("offset", s"must not be negative, got $offset"))
    else if (limit <= 0) Right(Seq.empty)
    else
      text(translate(filter)) match {
        case None => Right(Seq.empty)
        case Some(query) =>
          val args = Seq[Arg](config.index, query, "SORTBY", SeqField, "ASC") ++ returnFields() ++
            Seq[Arg]("LIMIT", offset.toString, limit.toString, "DIALECT", "2")
          searchOptional("list", command("FT.SEARCH", args*)).flatMap { docs =>
            parse("list")(docs.map { case (k, fields) => toRecord(k, fields) })
          }
      }

  override def stats(): Result[VectorStoreStats] =
    indexInfo().flatMap {
      case None => Right(VectorStoreStats(totalRecords = 0L))
      case Some(dimension) =>
        countMatching("*").map(total => VectorStoreStats(totalRecords = total, dimensions = dimension.toSet))
    }

  /** Closes the connection pool when this store created it; a connection passed in belongs to the caller. */
  override def close(): Unit =
    if (ownsConnection) connection.close()

  // ============================================================
  // Index management
  // ============================================================

  private def checkDimensions(records: Seq[VectorRecord], expected: Int): Result[Unit] =
    records.find(_.dimensions != expected) match {
      case Some(r) =>
        Left(
          ValidationError(
            "embedding",
            s"record '${r.id}' has ${r.dimensions} dimensions; the index ${config.index} expects $expected"
          )
        )
      case None => Right(())
    }

  /**
   * The index's vector size: read from `FT.INFO` when the index exists, else `dimension`, with which the index is
   * created.
   */
  private def ensureIndex(dimension: Int): Result[Int] =
    indexDimension match {
      case Some(known) => Right(known)
      case None =>
        indexInfo()
          .flatMap {
            case Some(existing) => Right(existing.getOrElse(dimension))
            case None if dimension < 1 =>
              Left(ValidationError("embedding", "a record's embedding must not be empty"))
            case None =>
              createIndexCommand(dimension, sortable = true)
                .flatMap {
                  // valkey-search has no SORTABLE: its SORTBY sorts any indexed field, so create the index without it.
                  case RedisReply.Error(message) if message.contains("SORTABLE") =>
                    createIndexCommand(dimension, sortable = false)
                  case reply => Right(reply)
                }
                .flatMap {
                  // Another writer may have created it between the check and this command: that is the same outcome.
                  case RedisReply.Error(message) if !message.toLowerCase.contains("already exists") =>
                    Left(refused("create index", message))
                  case _ => Right(dimension)
                }
          }
          .map { d =>
            indexDimension = Some(d)
            d
          }
    }

  private def createIndexCommand(dimension: Int, sortable: Boolean): Result[RedisReply] =
    connection.execute(command("FT.CREATE", createIndex(config, dimension, sortable).map(s => s: Arg)*))

  /** `None` when the index does not exist; else its vector size, when `FT.INFO` reports it. */
  private def indexInfo(): Result[Option[Option[Int]]] =
    connection.execute(command("FT.INFO", config.index)).flatMap {
      case RedisReply.Error(message) if isIndexMissing(message) => Right(None)
      case RedisReply.Error(message)                            => Left(refused("index info", message))
      case reply                                                => Right(Some(dimensionIn(reply)))
    }

  // ============================================================
  // Commands
  // ============================================================

  private def key(id: String): String = prefix + id

  /** Runs a command whose error reply is a failure. */
  private def run(operation: String, cmd: Seq[Array[Byte]]): Result[RedisReply] =
    connection.execute(cmd).flatMap {
      case RedisReply.Error(message) => Left(refused(operation, message))
      case reply                     => Right(reply)
    }

  /** An `FT.SEARCH`, whose "no such index" error means an empty store. */
  private def searchOptional(operation: String, cmd: Seq[Array[Byte]]): Result[Seq[(String, Map[String, RedisReply])]] =
    connection.execute(cmd).flatMap {
      case RedisReply.Error(message) if isIndexMissing(message) => Right(Seq.empty)
      case RedisReply.Error(message)                            => Left(refused(operation, message))
      case reply                                                => parse(operation)(searchDocuments(reply))
    }

  private def countMatching(query: String): Result[Long] =
    connection.execute(command("FT.SEARCH", config.index, query, "LIMIT", "0", "0", "DIALECT", "2")).flatMap {
      case RedisReply.Error(message) if isIndexMissing(message) => Right(0L)
      case RedisReply.Error(message)                            => Left(refused("count", message))
      case reply                                                => parse("count")(searchTotal(reply))
    }

  /** Deletes the records matching `query` a page at a time, until none is left or a page deletes nothing. */
  @tailrec
  private def deleteMatching(query: String, deleted: Long): Result[Long] = {
    val page = connection.execute(
      command("FT.SEARCH", config.index, query, "NOCONTENT", "LIMIT", "0", DeletePage.toString, "DIALECT", "2")
    ) match {
      case Right(RedisReply.Error(message)) if isIndexMissing(message) => Right(Seq.empty)
      case Right(RedisReply.Error(message))                            => Left(refused("delete by filter", message))
      case Right(reply)                                                => parse("delete by filter")(searchKeys(reply))
      case Left(error)                                                 => Left(error)
    }
    page match {
      case Left(error)                 => Left(error)
      case Right(keys) if keys.isEmpty => Right(deleted)
      case Right(keys) =>
        run("delete by filter", command("DEL", keys.map(k => k: Arg)*)).flatMap(integer("delete by filter", _)) match {
          case Left(error) => Left(error)
          case Right(0L)   => Right(deleted)
          case Right(gone) => deleteMatching(query, deleted + gone)
        }
    }
  }

  /** Deletes every key matching the glob `pattern`, walking the keyspace with `SCAN`. */
  private def scanAndDelete(pattern: String): Result[Long] = {
    @tailrec
    def loop(cursor: String, deleted: Long): Result[Long] =
      run("scan", command("SCAN", cursor, "MATCH", pattern, "COUNT", ScanCount.toString))
        .flatMap(reply => parse("scan")(scanPage(reply)))
        .flatMap { case (next, keys) =>
          if (keys.isEmpty) Right((next, 0L))
          else run("delete", command("DEL", keys.map(k => k: Arg)*)).flatMap(integer("delete", _)).map(n => (next, n))
        } match {
        case Left(error)      => Left(error)
        case Right(("0", n))  => Right(deleted + n)
        case Right((next, n)) => loop(next, deleted + n)
      }
    loop("0", 0L)
  }

  private def firstError(operation: String, replies: Seq[RedisReply]): Option[LLMError] =
    replies.collectFirst { case RedisReply.Error(message) => refused(operation, message) }

  private def integer(operation: String, reply: RedisReply): Result[Long] = reply match {
    case RedisReply.Integer(n) => Right(n)
    case other                 => Left(unexpected(operation, other.toString))
  }

  private def refused(operation: String, message: String): LLMError =
    if (AuthErrors.exists(p => message.startsWith(p)))
      AuthenticationError("redis", s"Redis $operation refused: $message")
    else ProcessingError("redis-store", s"Redis $operation failed: $message")

  private def unexpected(operation: String, detail: String): LLMError =
    ProcessingError("redis-store", s"Redis $operation returned an unexpected reply: $detail")

  private def parse[A](operation: String)(thunk: => A): Result[A] =
    Try(thunk).toEither.left.map(e => unexpected(operation, s"${e.getClass.getSimpleName}: ${e.getMessage}"))

  private def score(distance: Double): Double = {
    val s = config.distanceMetric match {
      case RedisDistanceMetric.L2 => 1.0 / (1.0 + math.max(0.0, distance))
      case _                      => 1.0 - distance / 2.0
    }
    math.max(0.0, math.min(1.0, s))
  }

  // ============================================================
  // Parsing
  // ============================================================

  /** A record from a hash's `HGETALL` reply; `None` for an empty reply, a key that does not exist. */
  private def hashRecord(id: String, reply: RedisReply): Option[VectorRecord] =
    fieldMap(reply) match {
      case fields if fields.isEmpty => None
      case fields                   => Some(toRecord(key(id), fields))
    }

  private def toRecord(redisKey: String, fields: Map[String, RedisReply]): VectorRecord = {
    val id = fields.get(IdField).flatMap(textOf).getOrElse(redisKey.stripPrefix(prefix))
    val embedding = fields.get(EmbeddingField) match {
      case Some(RedisReply.Bulk(bytes)) => vectorFrom(bytes)
      case _                            => Array.emptyFloatArray
    }
    val content  = fields.get(ContentField).flatMap(textOf)
    val metadata = fields.get(MetadataField).flatMap(textOf).map(metadataFrom).getOrElse(Map.empty)
    VectorRecord(id, embedding, content, metadata)
  }
}

object RedisVectorStore {

  /** A Redis command argument: text (sent as UTF-8) or raw bytes. */
  private type Arg = String | Array[Byte]

  private[redis] def command(name: String, args: Arg*): Seq[Array[Byte]] =
    (name +: args).map {
      case s: String      => s.getBytes(StandardCharsets.UTF_8)
      case b: Array[Byte] => b
    }

  /** How many keys a delete-by-filter removes per round trip. */
  private[redis] val DeletePage: Int = 1000

  /** The `COUNT` hint of each `SCAN`. */
  private[redis] val ScanCount: Int = 1000

  private val AuthErrors = Seq("NOAUTH", "WRONGPASS", "NOPERM")

  private val seqs = new AtomicLong(0L)

  /** A sequence number that increases on every call and roughly tracks the clock across processes. */
  private[redis] def nextSeq(): Long =
    seqs.updateAndGet(prev => math.max(prev + 1, System.currentTimeMillis() * 1000L))

  /** Whether an error reply says the index does not exist (Redis Query Engine's and `valkey-search`'s wording). */
  private[redis] def isIndexMissing(message: String): Boolean = {
    val m = message.toLowerCase
    m.contains("no such index") || m.contains("unknown index name") || (m.contains("index") && m.contains("not found"))
  }

  private def returnFields(extra: String*): Seq[Arg] = {
    val fields = Seq(
      RedisQueries.IdField,
      RedisQueries.ContentField,
      RedisQueries.MetadataField,
      RedisQueries.EmbeddingField
    ) ++ extra
    Seq[Arg]("RETURN", fields.size.toString) ++ fields
  }

  private[redis] def textOf(reply: RedisReply): Option[String] = reply.text

  /** A flat `field, value, ...` array as a map; anything else is empty. */
  private[redis] def fieldMap(reply: RedisReply): Map[String, RedisReply] = reply match {
    case RedisReply.Arr(items) =>
      items.grouped(2).collect { case Seq(k, v) => k.text.getOrElse("") -> v }.toMap
    case _ => Map.empty
  }

  /** The total of an `FT.SEARCH` reply. */
  private[redis] def searchTotal(reply: RedisReply): Long = reply match {
    case RedisReply.Arr(RedisReply.Integer(total) +: _) => total
    case other => throw new IllegalStateException(s"not a search reply: $other")
  }

  /** The documents of an `FT.SEARCH` reply: `[total, key, [field, value, ...], key, [...], ...]`. */
  private[redis] def searchDocuments(reply: RedisReply): Seq[(String, Map[String, RedisReply])] = reply match {
    case RedisReply.Arr(RedisReply.Integer(_) +: rest) =>
      rest.grouped(2).collect { case Seq(k, fields) => k.text.getOrElse("") -> fieldMap(fields) }.toSeq
    case other => throw new IllegalStateException(s"not a search reply: $other")
  }

  /** The keys of an `FT.SEARCH ... NOCONTENT` reply: `[total, key, key, ...]`. */
  private[redis] def searchKeys(reply: RedisReply): Seq[String] = reply match {
    case RedisReply.Arr(RedisReply.Integer(_) +: rest) => rest.flatMap(_.text)
    case other                                         => throw new IllegalStateException(s"not a search reply: $other")
  }

  /** A `SCAN` reply: `[cursor, [key, ...]]`. */
  private[redis] def scanPage(reply: RedisReply): (String, Seq[String]) = reply match {
    case RedisReply.Arr(Seq(cursor, RedisReply.Arr(keys))) => (cursor.text.getOrElse("0"), keys.flatMap(_.text))
    case other => throw new IllegalStateException(s"not a scan reply: $other")
  }

  /**
   * The vector size in an `FT.INFO` reply, when it names one: the value after a `dim` (Redis Query Engine) or
   * `dimensions` (`valkey-search`) entry, at any depth.
   */
  private[redis] def dimensionIn(reply: RedisReply): Option[Int] = {
    def search(items: Seq[RedisReply]): Option[Int] =
      items
        .sliding(2)
        .collectFirst {
          case Seq(k, v)
              if k.text.exists(t => t.equalsIgnoreCase("dim") || t.equalsIgnoreCase("dimensions")) &&
                v.text.exists(_.forall(_.isDigit)) && v.text.exists(_.nonEmpty) =>
            v.text.map(_.toInt)
        }
        .flatten
        .orElse(items.iterator.collect { case RedisReply.Arr(inner) => search(inner) }.collectFirst { case Some(d) =>
          d
        })
    reply match {
      case RedisReply.Arr(items) => search(items)
      case _                     => None
    }
  }

  /**
   * A store over `config`'s index with a connection pool of its own, closed by [[RedisVectorStore#close]].
   *
   * No command is sent: the index is created on the first upsert.
   *
   * @return the store, or a [[org.llm4s.error.ConfigurationError]] when [[RedisConfig#validate]] refuses the config
   */
  def apply(config: RedisConfig): Result[RedisVectorStore] =
    for {
      valid      <- config.validate
      connection <- JedisConnection(valid)
    } yield new RedisVectorStore(valid, connection, ownsConnection = true)

  /** A store over `index` of a local server without authentication (`localhost:6379`). */
  def local(index: String = RedisConfig.DefaultIndex): Result[RedisVectorStore] =
    apply(RedisConfig(index = index))

  /** A store sending through `connection`, which stays the caller's to close. For the unit tests. */
  private[redis] def apply(config: RedisConfig, connection: RedisConnection): Result[RedisVectorStore] =
    config.validate.map(valid => new RedisVectorStore(valid, connection, ownsConnection = false))
}
