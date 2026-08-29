package org.llm4s.vectorstore

import org.llm4s.types.Result
import org.llm4s.error.ProcessingError
import org.llm4s.http.Llm4sHttpClient

import java.nio.charset.StandardCharsets
import java.util.UUID
import scala.util.Try

/**
 * Qdrant vector database implementation of VectorStore.
 *
 * Uses Qdrant's REST API for vector similarity search with support
 * for filtering, payload storage, and multiple distance metrics.
 *
 * Features:
 * - Cloud-native architecture with horizontal scaling
 * - HNSW indexing for fast approximate nearest neighbor search
 * - Rich filtering on payload (metadata) fields
 * - Multiple distance metrics (Cosine, Euclid, Dot)
 * - Snapshot and backup capabilities
 *
 * Requirements:
 * - Qdrant server running (docker or cloud)
 * - REST API enabled (default port 6333)
 *
 * @param baseUrl Base URL for Qdrant API (e.g., "http://localhost:6333")
 * @param collectionName Name of the collection to use
 * @param apiKey Optional API key for authentication
 */
final class QdrantVectorStore private (
  val baseUrl: String,
  val collectionName: String,
  private val apiKey: Option[String],
  private val httpClient: Llm4sHttpClient
) extends VectorStore {

  private val collectionsUrl = s"$baseUrl/collections/$collectionName"
  private val pointsUrl      = s"$collectionsUrl/points"

  /**
   * Qdrant point IDs must be an unsigned integer or a UUID - it rejects anything else with
   * "not a valid point ID" - while a `VectorRecord` ID is an arbitrary string. IDs that are
   * already UUIDs are used as-is; every other ID is mapped to a UUID derived from it, so the
   * mapping is stable across processes and stores.
   *
   * The record's own ID is what callers search, get and delete by, so it travels in the
   * payload under `llm4s_id` and is read back from there rather than from the point ID.
   */
  private def pointId(id: String): String =
    if (QdrantVectorStore.isUuid(id)) id
    else UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)).toString

  /** The record ID a point carries, falling back to its point ID for foreign-written points. */
  private def recordId(point: ujson.Value): String =
    point.obj
      .get("payload")
      .flatMap(_.objOpt)
      .flatMap(_.get(QdrantVectorStore.IdKey))
      .collect { case ujson.Str(value) => value }
      .getOrElse(pointIdString(point("id")))

  private def pointIdString(id: ujson.Value): String = id match {
    case ujson.Str(value) => value
    case ujson.Num(value) => value.toLong.toString
    case other            => other.toString
  }

  // Initialize collection if it doesn't exist
  ensureCollection()

  private def ensureCollection(): Unit = {
    // Check if collection exists
    val checkResult = httpGet(collectionsUrl)
    if (checkResult.isLeft) {
      // Collection doesn't exist, will be created on first upsert
      // We need dimension info to create, so defer until first insert
    }
  }

  private def createCollection(dimension: Int): Result[Unit] =
    Try {
      val body = ujson.Obj(
        "vectors" -> ujson.Obj(
          "size"     -> dimension,
          "distance" -> "Cosine"
        )
      )
      httpPut(collectionsUrl, body)
    }.toEither.left
      .map(e => ProcessingError("qdrant-store", s"Failed to create collection: ${e.getMessage}"))
      .flatMap(identity)

  override def upsert(record: VectorRecord): Result[Unit] =
    upsertBatch(Seq(record))

  override def upsertBatch(records: Seq[VectorRecord]): Result[Unit] =
    if (records.isEmpty) Right(())
    else {
      // Ensure collection exists with correct dimension
      val dimension   = records.head.dimensions
      val checkResult = httpGet(collectionsUrl)
      val ensureResult =
        if (checkResult.isLeft) createCollection(dimension)
        else Right(())

      ensureResult.flatMap { _ =>
        Try {
          val points = ujson.Arr(records.map { record =>
            ujson.Obj(
              "id"      -> pointId(record.id),
              "vector"  -> ujson.Arr(record.embedding.toIndexedSeq.map(f => ujson.Num(f.toDouble)): _*),
              "payload" -> recordToPayload(record)
            )
          }: _*)

          val body = ujson.Obj("points" -> points)
          httpPut(s"$pointsUrl?wait=true", body)
        }.toEither.left
          .map(e => ProcessingError("qdrant-store", s"Failed to upsert: ${e.getMessage}"))
          .flatMap(identity)
      }
    }

  override def search(
    queryVector: Array[Float],
    topK: Int,
    filter: Option[MetadataFilter]
  ): Result[Seq[ScoredRecord]] =
    Try {
      val body = ujson.Obj(
        "vector"       -> ujson.Arr(queryVector.toIndexedSeq.map(f => ujson.Num(f.toDouble)): _*),
        "limit"        -> topK,
        "with_payload" -> true,
        "with_vector"  -> true
      )

      filter.foreach(f => body("filter") = filterToQdrant(f))

      httpPostOptional(s"$pointsUrl/search", body).map { responseOpt =>
        val results = responseOpt.map(_("result").arr).getOrElse(ujson.Arr().arr)
        results.map { point =>
          val id       = recordId(point)
          val score    = point("score").num
          val vector   = point("vector").arr.map(_.num.toFloat).toArray
          val payload  = point("payload").obj
          val content  = payload.get("content").flatMap(v => if (v.isNull) None else Some(v.str))
          val metadata = payloadToMetadata(payload)

          // Qdrant cosine similarity is already 0-1 for normalized vectors
          val normalizedScore = math.max(0.0, math.min(1.0, score))

          ScoredRecord(
            VectorRecord(id, vector, content, metadata),
            normalizedScore
          )
        }.toSeq
      }
    }.toEither.left
      .map(e => ProcessingError("qdrant-store", s"Search failed: ${e.getMessage}"))
      .flatMap(identity)

  override def get(id: String): Result[Option[VectorRecord]] =
    Try {
      httpGetOptional(s"$pointsUrl/${pointId(id)}?with_payload=true&with_vector=true").map {
        _.map(_("result")).filterNot(_.isNull).map(pointToRecord)
      }
    }.toEither.left
      .map(e => ProcessingError("qdrant-store", s"Failed to get: ${e.getMessage}"))
      .flatMap(identity)

  override def getBatch(ids: Seq[String]): Result[Seq[VectorRecord]] =
    if (ids.isEmpty) Right(Seq.empty)
    else
      Try {
        val body = ujson.Obj(
          "ids"          -> ujson.Arr(ids.map(id => ujson.Str(pointId(id))): _*),
          "with_payload" -> true,
          "with_vector"  -> true
        )

        httpPost(s"$pointsUrl", body).map(response => response("result").arr.map(pointToRecord).toSeq)
      }.toEither.left
        .map(e => ProcessingError("qdrant-store", s"Failed to get batch: ${e.getMessage}"))
        .flatMap(identity)

  override def delete(id: String): Result[Unit] =
    deleteBatch(Seq(id))

  override def deleteBatch(ids: Seq[String]): Result[Unit] =
    if (ids.isEmpty) Right(())
    else
      Try {
        val body = ujson.Obj(
          "points" -> ujson.Arr(ids.map(id => ujson.Str(pointId(id))): _*)
        )
        httpPost(s"$pointsUrl/delete?wait=true", body).map(_ => ())
      }.toEither.left
        .map(e => ProcessingError("qdrant-store", s"Failed to delete: ${e.getMessage}"))
        .flatMap(identity)

  override def deleteByPrefix(prefix: String): Result[Long] =
    // Qdrant doesn't support prefix-based deletion directly
    // We need to scroll through all records and filter by ID prefix
    Try {
      var deleted                = 0L
      var offset: Option[String] = None
      var hasMore                = true

      while (hasMore) {
        // Payloads are needed here even though the vectors are not: the prefix belongs to the
        // record ID, and the point ID is a UUID derived from it.
        val body = ujson.Obj("limit" -> 100, "with_payload" -> true, "with_vector" -> false)
        offset.foreach(o => body("offset") = o)

        val result = httpPost(s"$pointsUrl/scroll", body)
        result match {
          case Right(response) =>
            val points = response("result")("points").arr
            if (points.isEmpty) {
              hasMore = false
            } else {
              val matchingIds = points.flatMap { p =>
                if (recordId(p).startsWith(prefix)) Some(pointIdString(p("id"))) else None
              }.toSeq

              if (matchingIds.nonEmpty) {
                val deleteBody = ujson.Obj("points" -> ujson.Arr(matchingIds.map(ujson.Str(_)): _*))
                httpPost(s"$pointsUrl/delete?wait=true", deleteBody)
                deleted += matchingIds.size
              }

              offset = response("result").obj.get("next_page_offset").map(_.str)
              hasMore = offset.isDefined
            }
          case Left(_) =>
            hasMore = false
        }
      }
      deleted
    }.toEither.left
      .map(e => ProcessingError("qdrant-store", s"Failed to delete by prefix: ${e.getMessage}"))

  override def deleteByFilter(filter: MetadataFilter): Result[Long] =
    Try {
      // First count matching records
      val countBefore = count(Some(filter)).getOrElse(0L)

      val body = ujson.Obj(
        "filter" -> filterToQdrant(filter)
      )
      httpPost(s"$pointsUrl/delete?wait=true", body).map(_ => countBefore)
    }.toEither.left
      .map(e => ProcessingError("qdrant-store", s"Failed to delete by filter: ${e.getMessage}"))
      .flatMap(identity)

  override def count(filter: Option[MetadataFilter]): Result[Long] =
    Try {
      val body = ujson.Obj("exact" -> true)
      filter.foreach(f => body("filter") = filterToQdrant(f))

      httpPostOptional(s"$pointsUrl/count", body)
        .map(_.map(_("result")("count").num.toLong).getOrElse(0L))
    }.toEither.left
      .map(e => ProcessingError("qdrant-store", s"Failed to count: ${e.getMessage}"))
      .flatMap(identity)

  override def list(limit: Int, offset: Int, filter: Option[MetadataFilter]): Result[Seq[VectorRecord]] =
    Try {
      val body = ujson.Obj(
        "limit"        -> limit,
        "offset"       -> offset,
        "with_payload" -> true,
        "with_vector"  -> true
      )
      filter.foreach(f => body("filter") = filterToQdrant(f))

      httpPostOptional(s"$pointsUrl/scroll", body)
        .map(_.map(_("result")("points").arr.map(pointToRecord).toSeq).getOrElse(Seq.empty))
    }.toEither.left
      .map(e => ProcessingError("qdrant-store", s"Failed to list: ${e.getMessage}"))
      .flatMap(identity)

  override def clear(): Result[Unit] =
    // Delete collection - it will be recreated on next upsert
    httpDelete(collectionsUrl)

  override def stats(): Result[VectorStoreStats] =
    Try {
      httpGetOptional(collectionsUrl).map {
        // A collection that does not exist yet is an empty store, not a failure.
        case None => VectorStoreStats(totalRecords = 0L, dimensions = Set.empty, sizeBytes = None)
        case Some(response) =>
          val result        = response("result")
          val vectorsConfig = result("config")("params")("vectors")

          val dimensions =
            if (vectorsConfig.obj.contains("size")) Set(vectorsConfig("size").num.toInt)
            else Set.empty[Int]

          VectorStoreStats(
            totalRecords = result("points_count").num.toLong,
            dimensions = dimensions,
            sizeBytes = None // Qdrant doesn't expose this directly
          )
      }
    }.toEither.left
      .map(e => ProcessingError("qdrant-store", s"Failed to get stats: ${e.getMessage}"))
      .flatMap(identity)

  override def close(): Unit = {
    // No persistent connection to close for REST API
  }

  // ============================================================
  // HTTP Helpers
  // ============================================================

  /**
   * A read of something that is not there.
   *
   * Qdrant answers 404 both for a point that does not exist and for a collection that does
   * not exist - and the collection legitimately does not exist much of the time here, since
   * it is created lazily on the first upsert and removed outright by `clear()`. Absence is
   * not a failure for a read: `get` has an `Option` in its return type precisely to say so,
   * and an empty store counts 0 rather than erroring. Writes keep the 404 as an error.
   */
  private def httpGetOptional(url: String): Result[Option[ujson.Value]] =
    Try(httpClient.get(url, headers = authHeaders)).toEither.left
      .map(e => ProcessingError("qdrant-store", s"HTTP GET failed: ${e.getMessage}"))
      .flatMap {
        case response if response.statusCode == 404 => Right(None)
        case response                               => handleResponse(response).map(Some(_))
      }

  private def httpPostOptional(url: String, body: ujson.Value): Result[Option[ujson.Value]] =
    Try(
      httpClient.post(
        url,
        headers = authHeaders ++ Map("Content-Type" -> "application/json"),
        body = ujson.write(body)
      )
    ).toEither.left
      .map(e => ProcessingError("qdrant-store", s"HTTP POST failed: ${e.getMessage}"))
      .flatMap {
        case response if response.statusCode == 404 => Right(None)
        case response                               => handleResponse(response).map(Some(_))
      }

  private def httpGet(url: String): Result[ujson.Value] =
    Try {
      httpClient.get(url, headers = authHeaders)
    }.toEither.left
      .map(e => ProcessingError("qdrant-store", s"HTTP GET failed: ${e.getMessage}"))
      .flatMap(handleResponse)

  private def httpPost(url: String, body: ujson.Value): Result[ujson.Value] =
    Try {
      httpClient.post(
        url,
        headers = authHeaders ++ Map("Content-Type" -> "application/json"),
        body = ujson.write(body)
      )
    }.toEither.left
      .map(e => ProcessingError("qdrant-store", s"HTTP POST failed: ${e.getMessage}"))
      .flatMap(handleResponse)

  private def httpPut(url: String, body: ujson.Value): Result[Unit] =
    Try {
      httpClient.put(
        url,
        headers = authHeaders ++ Map("Content-Type" -> "application/json"),
        body = ujson.write(body)
      )
    }.toEither.left
      .map(e => ProcessingError("qdrant-store", s"HTTP PUT failed: ${e.getMessage}"))
      .flatMap { response =>
        if (response.statusCode >= 200 && response.statusCode < 300) Right(())
        else Left(ProcessingError("qdrant-store", s"HTTP PUT failed: ${response.statusCode} - ${response.body}"))
      }

  private def httpDelete(url: String): Result[Unit] =
    Try {
      httpClient.delete(url, headers = authHeaders)
    }.toEither.left
      .map(e => ProcessingError("qdrant-store", s"HTTP DELETE failed: ${e.getMessage}"))
      .flatMap { response =>
        if (response.statusCode >= 200 && response.statusCode < 300) Right(())
        else Left(ProcessingError("qdrant-store", s"HTTP DELETE failed: ${response.statusCode}"))
      }

  private def handleResponse(response: org.llm4s.http.HttpResponse): Result[ujson.Value] =
    if (response.statusCode >= 200 && response.statusCode < 300) {
      Right(ujson.read(response.body))
    } else if (response.statusCode == 404) {
      Left(ProcessingError("qdrant-store", "Not found"))
    } else {
      Left(ProcessingError("qdrant-store", s"HTTP error: ${response.statusCode} - ${response.body}"))
    }

  private def authHeaders: Map[String, String] =
    apiKey.map(key => Map("api-key" -> key)).getOrElse(Map.empty)

  // ============================================================
  // Conversion Helpers
  // ============================================================

  private def recordToPayload(record: VectorRecord): ujson.Obj = {
    val payload = ujson.Obj()
    payload(QdrantVectorStore.IdKey) = record.id
    record.content.foreach(c => payload("content") = c)
    record.metadata.foreach { case (k, v) =>
      payload(s"meta_$k") = v
    }
    payload
  }

  private def payloadToMetadata(payload: ujson.Obj): Map[String, String] =
    payload.value
      .filter { case (k, _) => k.startsWith("meta_") }
      .map { case (k, v) => k.stripPrefix("meta_") -> v.str }
      .toMap

  private def pointToRecord(point: ujson.Value): VectorRecord = {
    val id       = recordId(point)
    val vector   = point("vector").arr.map(_.num.toFloat).toArray
    val payload  = point("payload").obj
    val content  = payload.get("content").flatMap(v => if (v.isNull) None else Some(v.str))
    val metadata = payloadToMetadata(payload)

    VectorRecord(id, vector, content, metadata)
  }

  private def filterToQdrant(filter: MetadataFilter): ujson.Obj = filter match {
    case MetadataFilter.All =>
      ujson.Obj()

    case MetadataFilter.Equals(key, value) =>
      ujson.Obj(
        "must" -> ujson.Arr(
          ujson.Obj(
            "key"   -> s"meta_$key",
            "match" -> ujson.Obj("value" -> value)
          )
        )
      )

    case MetadataFilter.Contains(key, substring) =>
      ujson.Obj(
        "must" -> ujson.Arr(
          ujson.Obj(
            "key"   -> s"meta_$key",
            "match" -> ujson.Obj("text" -> substring)
          )
        )
      )

    case MetadataFilter.HasKey(key) =>
      ujson.Obj(
        "must" -> ujson.Arr(
          ujson.Obj(
            "is_null" -> ujson.Obj(
              "key"     -> s"meta_$key",
              "is_null" -> false
            )
          )
        )
      )

    case MetadataFilter.In(key, values) =>
      ujson.Obj(
        "must" -> ujson.Arr(
          ujson.Obj(
            "key"   -> s"meta_$key",
            "match" -> ujson.Obj("any" -> ujson.Arr(values.toSeq.map(ujson.Str(_)): _*))
          )
        )
      )

    case MetadataFilter.And(left, right) =>
      ujson.Obj(
        "must" -> ujson.Arr(
          filterToQdrant(left),
          filterToQdrant(right)
        )
      )

    case MetadataFilter.Or(left, right) =>
      ujson.Obj(
        "should" -> ujson.Arr(
          filterToQdrant(left),
          filterToQdrant(right)
        )
      )

    case MetadataFilter.Not(inner) =>
      ujson.Obj(
        "must_not" -> ujson.Arr(filterToQdrant(inner))
      )
  }
}

object QdrantVectorStore {

  /** Payload key holding the record's own ID; see `pointId` for why it is not the point ID. */
  private[vectorstore] val IdKey = "llm4s_id"

  private val UuidPattern = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}".r

  private[vectorstore] def isUuid(id: String): Boolean = UuidPattern.matches(id)

  /**
   * Configuration for QdrantVectorStore.
   *
   * @param host Qdrant host
   * @param port Qdrant port (default: 6333)
   * @param collectionName Collection name
   * @param apiKey Optional API key
   * @param https Use HTTPS (default: false for local)
   */
  final case class Config(
    host: String = "localhost",
    port: Int = 6333,
    collectionName: String = "vectors",
    apiKey: Option[String] = None,
    https: Boolean = false
  ) {
    def baseUrl: String = {
      val protocol = if (https) "https" else "http"
      s"$protocol://$host:$port"
    }
  }

  /**
   * Create a QdrantVectorStore from configuration.
   *
   * @param config The store configuration
   * @param httpClient HTTP client for DI/testing (defaults to JDK implementation)
   * @return The vector store or error
   */
  def apply(
    config: Config,
    httpClient: Llm4sHttpClient
  ): Result[QdrantVectorStore] =
    Try {
      new QdrantVectorStore(config.baseUrl, config.collectionName, config.apiKey, httpClient)
    }.toEither.left.map(e => ProcessingError("qdrant-store", s"Failed to create store: ${e.getMessage}"))

  /** Create a QdrantVectorStore from configuration with the default JDK HTTP client. */
  def apply(config: Config): Result[QdrantVectorStore] =
    apply(config, Llm4sHttpClient.create())

  /**
   * Create a QdrantVectorStore from base URL.
   *
   * @param baseUrl Base URL for Qdrant API
   * @param collectionName Collection name
   * @param apiKey Optional API key
   * @param httpClient HTTP client for DI/testing (defaults to JDK implementation)
   * @return The vector store or error
   */
  def apply(
    baseUrl: String,
    collectionName: String = "vectors",
    apiKey: Option[String] = None,
    httpClient: Llm4sHttpClient = Llm4sHttpClient.create()
  ): Result[QdrantVectorStore] =
    Try {
      new QdrantVectorStore(baseUrl, collectionName, apiKey, httpClient)
    }.toEither.left.map(e => ProcessingError("qdrant-store", s"Failed to create store: ${e.getMessage}"))

  /**
   * Create a QdrantVectorStore with default local settings.
   *
   * Connects to localhost:6333.
   *
   * @param collectionName Collection name (default: "vectors")
   * @param httpClient HTTP client for DI/testing (defaults to JDK implementation)
   * @return The vector store or error
   */
  def local(
    collectionName: String = "vectors",
    httpClient: Llm4sHttpClient = Llm4sHttpClient.create()
  ): Result[QdrantVectorStore] =
    apply(Config(collectionName = collectionName), httpClient)

  /**
   * Create a QdrantVectorStore for Qdrant Cloud.
   *
   * @param cloudUrl Qdrant Cloud URL
   * @param apiKey API key for authentication
   * @param collectionName Collection name
   * @param httpClient HTTP client for DI/testing (defaults to JDK implementation)
   * @return The vector store or error
   */
  def cloud(
    cloudUrl: String,
    apiKey: String,
    collectionName: String = "vectors",
    httpClient: Llm4sHttpClient = Llm4sHttpClient.create()
  ): Result[QdrantVectorStore] =
    apply(cloudUrl, collectionName, Some(apiKey), httpClient)
}
