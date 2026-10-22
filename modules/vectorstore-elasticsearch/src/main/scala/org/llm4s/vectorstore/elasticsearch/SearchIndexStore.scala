package org.llm4s.vectorstore.elasticsearch

import org.llm4s.error.{ AuthenticationError, LLMError, ProcessingError, RateLimitError, ServiceError, ValidationError }
import org.llm4s.http.{ HttpResponse, Llm4sHttpClient }
import org.llm4s.types.Result
import org.llm4s.util.Redaction
import org.llm4s.vectorstore.{ MetadataFilter, ScoredRecord, VectorRecord, VectorStore, VectorStoreStats }

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

/**
 * What differs between the products [[SearchIndexStore]] speaks to: Elasticsearch and OpenSearch share the document,
 * `_bulk`, `_mget`, `_count`, `_delete_by_query`, `_mapping` and `_stats` APIs and the query DSL, and differ in the
 * vector field's mapping, the kNN query, how a similarity becomes a `_score`, and authentication.
 */
private[vectorstore] trait SearchBackend {

  /** The product's name, for messages: `Elasticsearch`. */
  def product: String

  /** The provider name errors carry: `elasticsearch`. */
  def errorId: String

  /** The cluster's root URL, without a trailing slash. */
  def root: String
  def index: String
  def timeout: FiniteDuration
  def refreshOnWrite: Boolean
  def dimensions: Option[Int]

  /** The `Authorization` header, if any. */
  def authHeaders: Map[String, String]

  /** Every configured credential, masked wherever an error body echoes it. */
  def secrets: Seq[String]

  /** The largest `topK` a search accepts. */
  def maxTopK: Int

  /** The body of `PUT /<index>` for vectors of `dimensions`. */
  def indexDefinition(dimensions: Int): ujson.Obj

  /** The key of the vector field's mapping that holds its size: `dims` or `dimension`. */
  def dimensionsKey: String

  /** The `_search` body of a kNN search for at most `topK` hits. */
  def searchBody(queryVector: Array[Float], topK: Int, filter: Option[MetadataFilter]): ujson.Obj

  /** A hit's `_score` as a [[org.llm4s.vectorstore.ScoredRecord]] score, in `[0, 1]`. */
  def score(raw: Double): Double
}

/**
 * The [[org.llm4s.vectorstore.VectorStore]] operations over one index, for every [[SearchBackend]]: the public
 * stores, [[ElasticsearchVectorStore]] and [[org.llm4s.vectorstore.opensearch.OpenSearchVectorStore]], delegate to it.
 */
final private[vectorstore] class SearchIndexStore(
  backend: SearchBackend,
  httpClient: Llm4sHttpClient,
  ownsClient: Boolean
) extends VectorStore {

  import ElasticsearchQueries.*

  private val indexUrl = s"${backend.root}/${backend.index}"

  private val product = backend.product

  /** Set once the index is known to exist; `clear()` resets it. */
  @volatile private var indexReady = false

  // ============================================================
  // Writes
  // ============================================================

  override def upsert(record: VectorRecord): Result[Unit] =
    upsertBatch(Seq(record))

  override def upsertBatch(records: Seq[VectorRecord]): Result[Unit] =
    if (records.isEmpty) Right(())
    else
      for {
        dimension <- checkDimensions(records)
        _         <- ensureIndex(dimension)
        body = records.map { r =>
          val action = ujson.Obj("index" -> ujson.Obj("_id" -> r.id))
          val doc    = document(r.id, r.embedding, r.content, r.metadata, SearchIndexStore.nextSeq())
          s"${ujson.write(action)}\n${ujson.write(doc)}\n"
        }.mkString
        _ <- bulk("upsert", body, tolerateNotFound = false)
      } yield ()

  override def delete(id: String): Result[Unit] =
    deleteBatch(Seq(id))

  override def deleteBatch(ids: Seq[String]): Result[Unit] =
    if (ids.isEmpty) Right(())
    else
      indexExists().flatMap {
        case false => Right(())
        case true =>
          val body = ids.map(id => s"${ujson.write(ujson.Obj("delete" -> ujson.Obj("_id" -> id)))}\n").mkString
          bulk("delete", body, tolerateNotFound = true)
      }

  override def deleteByPrefix(prefix: String): Result[Long] =
    deleteByQuery("delete by prefix", idPrefixQuery(prefix))

  override def deleteByFilter(filter: MetadataFilter): Result[Long] =
    deleteByQuery("delete by filter", filterQuery(filter))

  override def clear(): Result[Unit] =
    httpClient.delete(indexUrl, headers = authHeaders, timeout = backend.timeout).flatMap { response =>
      indexReady = false
      if (response.statusCode == 404 || isSuccess(response)) Right(())
      else Left(failure("clear", response))
    }

  // ============================================================
  // Reads
  // ============================================================

  override def search(
    queryVector: Array[Float],
    topK: Int,
    filter: Option[MetadataFilter]
  ): Result[Seq[ScoredRecord]] =
    if (topK <= 0) Right(Seq.empty)
    else if (topK > backend.maxTopK)
      Left(ValidationError("topK", s"must be at most ${backend.maxTopK} for an $product kNN search"))
    else
      postOptional("search", s"$indexUrl/_search", backend.searchBody(queryVector, topK, filter)).flatMap {
        case None => Right(Seq.empty)
        case Some(json) =>
          parse("search") {
            hits(json).map { hit =>
              val raw = hit.obj.get("_score").collect { case ujson.Num(n) => n }
              ScoredRecord(toRecord(hit), raw.fold(0.0)(r => math.max(0.0, math.min(1.0, backend.score(r)))))
            }
          }
      }

  override def get(id: String): Result[Option[VectorRecord]] =
    httpClient
      .get(s"$indexUrl/_doc/${encode(id)}", headers = authHeaders, timeout = backend.timeout)
      .flatMap { response =>
        if (response.statusCode == 404) Right(None)
        else
          json("get", response).flatMap { doc =>
            parse("get")(if (doc.obj.get("found").contains(ujson.Bool(true))) Some(toRecord(doc)) else None)
          }
      }

  override def getBatch(ids: Seq[String]): Result[Seq[VectorRecord]] =
    if (ids.isEmpty) Right(Seq.empty)
    else
      postOptional("get batch", s"$indexUrl/_mget", ujson.Obj("ids" -> ujson.Arr.from(ids.map(ujson.Str(_))))).flatMap {
        case None => Right(Seq.empty)
        case Some(json) =>
          parse("get batch") {
            json("docs").arr.toSeq.filter(_.obj.get("found").contains(ujson.Bool(true))).map(toRecord)
          }
      }

  override def count(filter: Option[MetadataFilter]): Result[Long] =
    countMatching(query(filter))

  override def list(limit: Int, offset: Int, filter: Option[MetadataFilter]): Result[Seq[VectorRecord]] =
    if (offset < 0) Left(ValidationError("offset", s"must not be negative, got $offset"))
    else if (limit <= 0) Right(Seq.empty)
    else
      postOptional("list", s"$indexUrl/_search", listPage(limit, offset, filter)).flatMap {
        case None       => Right(Seq.empty)
        case Some(json) => parse("list")(hits(json).map(toRecord))
      }

  override def stats(): Result[VectorStoreStats] =
    indexExists().flatMap {
      case false => Right(VectorStoreStats(totalRecords = 0L))
      case true  => countMatching(MatchAll).flatMap(fullStats)
    }

  /** Closes the HTTP client when the store created it; a client passed in belongs to the caller. */
  override def close(): Unit =
    if (ownsClient) httpClient.close()

  // ============================================================
  // Index management
  // ============================================================

  private def checkDimensions(records: Seq[VectorRecord]): Result[Int] = {
    val expected = backend.dimensions.getOrElse(records.head.dimensions)
    records.find(_.dimensions != expected) match {
      case Some(r) =>
        Left(
          ValidationError(
            "embedding",
            s"record '${r.id}' has ${r.dimensions} dimensions; the index ${backend.index} expects $expected"
          )
        )
      case None if expected < 1 => Left(ValidationError("embedding", "a record's embedding must not be empty"))
      case None                 => Right(expected)
    }
  }

  private def indexExists(): Result[Boolean] =
    if (indexReady) Right(true)
    else
      httpClient.get(indexUrl, headers = authHeaders, timeout = backend.timeout).flatMap { response =>
        if (response.statusCode == 404) Right(false)
        else if (isSuccess(response)) {
          indexReady = true
          Right(true)
        } else Left(failure("check index", response))
      }

  private def ensureIndex(dimension: Int): Result[Unit] =
    indexExists().flatMap {
      case true => Right(())
      case false =>
        val body = ujson.write(backend.indexDefinition(dimension))
        httpClient
          .put(indexUrl, headers = jsonHeaders, body = body, timeout = backend.timeout)
          .flatMap { response =>
            // Another writer may have created it between the check and this request: that is the same outcome.
            if (isSuccess(response) || response.body.contains("resource_already_exists_exception")) {
              indexReady = true
              Right(())
            } else Left(failure("create index", response))
          }
    }

  private def fullStats(total: Long): Result[VectorStoreStats] =
    for {
      mapping <- getJson("stats", s"$indexUrl/_mapping")
      store   <- getJson("stats", s"$indexUrl/_stats/store")
      stats <- parse("stats") {
        val dims = mapping.obj.values.flatMap { idx =>
          idx.obj
            .get("mappings")
            .flatMap(_.obj.get("properties"))
            .flatMap(_.obj.get(EmbeddingField))
            .flatMap(_.obj.get(backend.dimensionsKey))
            .collect { case ujson.Num(n) => n.toInt }
        }.toSet
        val size = store.obj
          .get("_all")
          .flatMap(_.obj.get("primaries"))
          .flatMap(_.obj.get("store"))
          .flatMap(_.obj.get("size_in_bytes"))
          .collect { case ujson.Num(n) => n.toLong }
        VectorStoreStats(totalRecords = total, dimensions = dims, sizeBytes = size)
      }
    } yield stats

  // ============================================================
  // Requests
  // ============================================================

  private def bulk(operation: String, body: String, tolerateNotFound: Boolean): Result[Unit] = {
    val url = s"$indexUrl/_bulk${refreshParam("wait_for")}"
    httpClient
      .post(
        url,
        headers = authHeaders ++ Map("Content-Type" -> "application/x-ndjson"),
        body = body,
        timeout = backend.timeout
      )
      .flatMap(json(operation, _))
      .flatMap { response =>
        if (!response.obj.get("errors").contains(ujson.Bool(true))) Right(())
        else
          parse(operation) {
            response("items").arr.toSeq.flatMap(_.obj.values.headOption).filter { item =>
              item.obj.contains("error") && !(tolerateNotFound && item.obj.get("status").contains(ujson.Num(404)))
            }
          }.flatMap { failed =>
            if (failed.isEmpty) Right(())
            else {
              val reasons = failed.take(3).map { item =>
                val id     = item.obj.get("_id").map(_.str).getOrElse("?")
                val error  = item("error")
                val reason = error.obj.get("reason").map(_.str).getOrElse(ujson.write(error))
                s"'$id': ${scrub(reason)}"
              }
              val more = if (failed.size > 3) s" (and ${failed.size - 3} more)" else ""
              Left(
                ProcessingError(
                  storeId,
                  s"$product $operation failed for ${failed.size} record(s): ${reasons.mkString("; ")}$more"
                )
              )
            }
          }
      }
  }

  private def deleteByQuery(operation: String, query: ujson.Obj): Result[Long] =
    postOptional(
      operation,
      s"$indexUrl/_delete_by_query${refreshParam("true")}",
      ujson.Obj("query" -> query)
    ).flatMap {
      case None => Right(0L)
      case Some(json) =>
        parse(operation)(json.obj.get("failures").map(_.arr.toSeq).getOrElse(Seq.empty)).flatMap { failures =>
          if (failures.isEmpty) parse(operation)(json("deleted").num.toLong)
          else Left(ProcessingError(storeId, s"$product $operation failed: ${scrub(ujson.write(failures.head))}"))
        }
    }

  private def countMatching(query: ujson.Obj): Result[Long] =
    postOptional("count", s"$indexUrl/_count", ujson.Obj("query" -> query)).flatMap {
      case None       => Right(0L)
      case Some(json) => parse("count")(json("count").num.toLong)
    }

  /** A POST whose 404 means the index does not exist yet: `None`, which the caller reads as an empty store. */
  private def postOptional(operation: String, url: String, body: ujson.Value): Result[Option[ujson.Value]] =
    httpClient.post(url, headers = jsonHeaders, body = ujson.write(body), timeout = backend.timeout).flatMap {
      response =>
        if (response.statusCode == 404 && isIndexMissing(response)) Right(None)
        else json(operation, response).map(Some(_))
    }

  private def getJson(operation: String, url: String): Result[ujson.Value] =
    httpClient.get(url, headers = authHeaders, timeout = backend.timeout).flatMap(json(operation, _))

  private def json(operation: String, response: HttpResponse): Result[ujson.Value] =
    if (!isSuccess(response)) Left(failure(operation, response))
    else
      Try(ujson.read(response.body)).toEither.left.map(e =>
        ProcessingError(storeId, s"$product $operation returned unreadable JSON: ${e.getMessage}")
      )

  private def parse[A](operation: String)(thunk: => A): Result[A] =
    Try(thunk).toEither.left.map(e =>
      ProcessingError(
        storeId,
        s"$product $operation returned an unexpected response: ${e.getClass.getSimpleName}: ${e.getMessage}"
      )
    )

  private def storeId: String = s"${backend.errorId}-store"

  private def isSuccess(response: HttpResponse): Boolean =
    response.statusCode >= 200 && response.statusCode < 300

  private def isIndexMissing(response: HttpResponse): Boolean =
    response.body.contains("index_not_found_exception")

  private def failure(operation: String, response: HttpResponse): LLMError = {
    val body = scrub(response.body)
    response.statusCode match {
      case 401 | 403 => AuthenticationError(backend.errorId, s"$product $operation refused: $body")
      case 429       => RateLimitError(backend.errorId)
      case status    => ServiceError(status, backend.errorId, s"$product $operation failed: $body")
    }
  }

  /** Masks the configured credentials wherever a body echoes them, then applies the shared redaction. */
  private def scrub(text: String): String =
    Redaction.safeBody(backend.secrets.filter(_.nonEmpty).foldLeft(text)((t, s) => t.replace(s, "***")))

  private def refreshParam(value: String): String =
    if (backend.refreshOnWrite) s"?refresh=$value" else ""

  private def authHeaders: Map[String, String] = backend.authHeaders

  private def jsonHeaders: Map[String, String] = authHeaders ++ Map("Content-Type" -> "application/json")

  private def encode(id: String): String =
    URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20")

  // ============================================================
  // Parsing
  // ============================================================

  private def hits(json: ujson.Value): Seq[ujson.Value] =
    json("hits")("hits").arr.toSeq

  /** A record from a hit or a document: its `_source`, with `_id` standing in for a document written elsewhere. */
  private def toRecord(hit: ujson.Value): VectorRecord = {
    val source = hit("_source").obj
    val id     = source.get(IdField).collect { case ujson.Str(s) => s }.getOrElse(hit("_id").str)
    val embedding = source.get(EmbeddingField) match {
      case Some(ujson.Arr(values)) => values.iterator.map(_.num.toFloat).toArray
      case _                       => Array.emptyFloatArray
    }
    val content = source.get(ContentField).collect { case ujson.Str(s) => s }
    val metadata = source.get(MetadataField) match {
      case Some(ujson.Obj(values)) =>
        values.iterator.collect {
          case (k, ujson.Str(v)) => k -> v
          case (k, ujson.Null)   => k -> ""
          case (k, other)        => k -> ujson.write(other)
        }.toMap
      case _ => Map.empty[String, String]
    }
    VectorRecord(id, embedding, content, metadata)
  }
}

private[vectorstore] object SearchIndexStore {

  private val seqs = new AtomicLong(0L)

  /** A sequence number that increases on every call and roughly tracks the clock across processes. */
  def nextSeq(): Long =
    seqs.updateAndGet(prev => math.max(prev + 1, System.currentTimeMillis() * 1000L))
}
