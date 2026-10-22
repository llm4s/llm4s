package org.llm4s.vectorstore.opensearch

import org.llm4s.error.{
  AuthenticationError,
  ConfigurationError,
  ProcessingError,
  RateLimitError,
  ServiceError,
  ValidationError
}
import org.llm4s.http.HttpResponse
import org.llm4s.testutil.EchoedCredentials
import org.llm4s.types.Result
import org.llm4s.vectorstore.elasticsearch.FakeElasticsearch
import org.llm4s.vectorstore.elasticsearch.FakeElasticsearch.*
import org.llm4s.vectorstore.{ MetadataFilter, VectorRecord, VectorStoreStats }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * [[OpenSearchVectorStore]] against a fake OpenSearch: the `knn_vector` mapping, the `knn` query for each engine and
 * filter, score normalisation, authentication and errors. The requests it shares with Elasticsearch (`_bulk`, `_mget`,
 * `_count`, `_delete_by_query`, ...) are covered once more here, through the OpenSearch store, so that its wiring and
 * its error naming are checked; `ElasticsearchVectorStoreHttpSpec` covers their edge cases.
 */
class OpenSearchVectorStoreHttpSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val Root     = "http://localhost:9200"
  private val IndexUrl = s"$Root/vectors"
  private val BulkUrl  = s"$IndexUrl/_bulk?refresh=wait_for"
  private val config   = OpenSearchConfig(index = "vectors")

  private def store(
    respond: PartialFunction[(String, String), Result[HttpResponse]],
    cfg: OpenSearchConfig = config
  ): (OpenSearchVectorStore, FakeElasticsearch) = {
    val fake = new FakeElasticsearch(respond)
    (OpenSearchVectorStore(cfg, fake).value, fake)
  }

  private val record = VectorRecord("doc-1", Array(0.5f, 0.25f), Some("hello"), Map("lang" -> "en"))

  private def source(id: String, vector: Seq[Double]): ujson.Obj =
    ujson.Obj(
      "id"        -> id,
      "embedding" -> ujson.Arr.from(vector.map(ujson.Num(_))),
      "content"   -> s"content of $id",
      "metadata"  -> ujson.Obj("lang" -> "en"),
      "seq"       -> 1
    )

  private def hit(id: String, score: Double): ujson.Obj =
    ujson.Obj("_index" -> "vectors", "_id" -> id, "_score" -> score, "_source" -> source(id, Seq(1.0, 0.0)))

  private def searchResponse(hits: ujson.Value*): ujson.Obj =
    ujson.Obj("hits" -> ujson.Obj("total" -> ujson.Obj("value" -> hits.size), "hits" -> ujson.Arr.from(hits)))

  private def searchStore(cfg: OpenSearchConfig, hits: ujson.Value*): (OpenSearchVectorStore, FakeElasticsearch) =
    store({ case ("POST", u) if u == s"$IndexUrl/_search" => ok(searchResponse(hits*)) }, cfg)

  // ============================================================
  // Index creation and writes
  // ============================================================

  "upsert" should "create a k-NN index with an HNSW knn_vector field sized to the record, then bulk-index it" in {
    val (os, fake) = store {
      case ("GET", IndexUrl)           => IndexNotFound
      case ("PUT", IndexUrl)           => ok("""{"acknowledged":true}""")
      case ("POST", u) if u == BulkUrl => BulkOk
    }

    os.upsert(record) shouldBe Right(())

    val body = fake.sent("PUT", IndexUrl).head.json
    body("settings") shouldBe ujson.Obj("index" -> ujson.Obj("knn" -> true))
    val mapping = body("mappings")
    mapping("properties")("embedding") shouldBe ujson.Obj(
      "type"      -> "knn_vector",
      "dimension" -> 2,
      "method"    -> ujson.Obj("name" -> "hnsw", "space_type" -> "cosinesimil", "engine" -> "lucene")
    )
    mapping("properties")("id")("type").str shouldBe "keyword"
    mapping("properties")("content")("type").str shouldBe "text"
    mapping("dynamic_templates")(0)("llm4s_metadata_keywords")("mapping")("type").str shouldBe "keyword"

    val bulk = fake.sent("POST", BulkUrl).head
    bulk.headers("Content-Type") shouldBe "application/x-ndjson"
    val lines = bulk.ndjson
    lines.head shouldBe ujson.Obj("index" -> ujson.Obj("_id" -> "doc-1"))
    lines(1)("embedding").arr.map(_.num) shouldBe Seq(0.5, 0.25)
    lines(1)("metadata") shouldBe ujson.Obj("lang" -> "en")
  }

  it should "create the index with the configured dimensions, space type, engine and HNSW parameters" in {
    val cfg = config
      .withDimensions(2)
      .withSpaceType(OpenSearchSpaceType.InnerProduct)
      .withEngine(OpenSearchEngine.Faiss)
      .withM(24)
      .withEfConstruction(256)
    val (os, fake) = store(
      {
        case ("GET", IndexUrl)           => IndexNotFound
        case ("PUT", IndexUrl)           => ok("{}")
        case ("POST", u) if u == BulkUrl => BulkOk
      },
      cfg
    )

    os.upsert(record) shouldBe Right(())

    fake.sent("PUT", IndexUrl).head.json("mappings")("properties")("embedding")("method") shouldBe ujson.Obj(
      "name"       -> "hnsw",
      "space_type" -> "innerproduct",
      "engine"     -> "faiss",
      "parameters" -> ujson.Obj("m" -> 24, "ef_construction" -> 256)
    )
    os.upsert(VectorRecord("bad", Array(1f, 2f, 3f))).left.value shouldBe a[ValidationError]
  }

  it should "set only the HNSW parameter that is configured" in {
    val (os, fake) = store(
      {
        case ("GET", IndexUrl)           => IndexNotFound
        case ("PUT", IndexUrl)           => ok("{}")
        case ("POST", u) if u == BulkUrl => BulkOk
      },
      config.withEngine(OpenSearchEngine.Nmslib).withSpaceType(OpenSearchSpaceType.L2).withEfConstruction(64)
    )

    os.upsert(record) shouldBe Right(())

    val method = fake.sent("PUT", IndexUrl).head.json("mappings")("properties")("embedding")("method")
    method("engine").str shouldBe "nmslib"
    method("space_type").str shouldBe "l2"
    method("parameters") shouldBe ujson.Obj("ef_construction" -> 64)
  }

  it should "report records the bulk request rejected, as OpenSearch errors" in {
    val (os, _) = store {
      case ("GET", IndexUrl) => ok("{}")
      case ("POST", u) if u == BulkUrl =>
        ok(
          """{"errors":true,"items":[{"index":{"_id":"doc-1","status":400,""" +
            """"error":{"type":"mapper_parsing_exception","reason":"Vector dimension mismatch"}}}]}"""
        )
    }

    val error = os.upsert(record).left.value
    error shouldBe a[ProcessingError]
    error.message should (include("OpenSearch upsert failed for 1 record(s)").and(include("dimension mismatch")))
  }

  it should "report a failure to create the index with the cluster's reason" in {
    val (os, _) = store {
      case ("GET", IndexUrl) => IndexNotFound
      case ("PUT", IndexUrl) =>
        status(400, """{"error":{"reason":"nmslib engine is deprecated and not supported for new indices"}}""")
    }

    val error = os.upsert(record).left.value
    error shouldBe a[ServiceError]
    error.message should (include("OpenSearch create index failed").and(include("nmslib")))
  }

  // ============================================================
  // search: query shape
  // ============================================================

  "search" should "send a knn query on the embedding field with k = topK when there is no filter" in {
    val (os, fake) = searchStore(config)

    os.search(Array(1f, 0f), topK = 3) shouldBe Right(Seq.empty)

    fake.requests.head.json shouldBe ujson.Obj(
      "size"  -> 3,
      "query" -> ujson.Obj("knn" -> ujson.Obj("embedding" -> ujson.Obj("vector" -> ujson.Arr(1.0, 0.0), "k" -> 3)))
    )
  }

  it should "put every kind of metadata filter inside the knn query on Lucene and Faiss (efficient filtering)" in {
    import MetadataFilter.*
    val filters: Seq[(MetadataFilter, ujson.Value)] = Seq(
      All                     -> ujson.Obj("match_all" -> ujson.Obj()),
      Equals("lang", "en")    -> ujson.Obj("term" -> ujson.Obj("metadata.lang" -> "en")),
      Contains("path", "a*b") -> ujson.Obj("wildcard" -> ujson.Obj("metadata.path" -> ujson.Obj("value" -> "*a\\*b*"))),
      HasKey("draft")         -> ujson.Obj("exists" -> ujson.Obj("field" -> "metadata.draft")),
      In("lang", Set("fr", "de")) -> ujson.Obj("terms" -> ujson.Obj("metadata.lang" -> ujson.Arr("de", "fr"))),
      In("lang", Set.empty)       -> ujson.Obj("match_none" -> ujson.Obj()),
      Equals("a", "1").and(HasKey("b")) -> ujson.Obj(
        "bool" -> ujson.Obj(
          "filter" -> ujson.Arr(
            ujson.Obj("term"   -> ujson.Obj("metadata.a" -> "1")),
            ujson.Obj("exists" -> ujson.Obj("field" -> "metadata.b"))
          )
        )
      ),
      Equals("a", "1").or(Equals("a", "2")) -> ujson.Obj(
        "bool" -> ujson.Obj(
          "should" -> ujson.Arr(
            ujson.Obj("term" -> ujson.Obj("metadata.a" -> "1")),
            ujson.Obj("term" -> ujson.Obj("metadata.a" -> "2"))
          ),
          "minimum_should_match" -> 1
        )
      ),
      !HasKey("draft") -> ujson.Obj(
        "bool" -> ujson.Obj("must_not" -> ujson.Arr(ujson.Obj("exists" -> ujson.Obj("field" -> "metadata.draft"))))
      )
    )

    for {
      engine             <- Seq(OpenSearchEngine.Lucene, OpenSearchEngine.Faiss)
      (filter, expected) <- filters
    } {
      val (os, fake) = searchStore(config.withEngine(engine))
      os.search(Array(1f), topK = 5, filter = Some(filter)) shouldBe Right(Seq.empty)

      val body = fake.requests.head.json
      body("size").num shouldBe 5
      val knn = body("query")("knn")("embedding")
      knn("k").num shouldBe 5
      knn("filter") shouldBe expected
    }
  }

  it should "post-filter on NMSLIB: the knn query in a bool must, the filter beside it, with a wider k" in {
    val (os, fake) = searchStore(config.withEngine(OpenSearchEngine.Nmslib))

    os.search(Array(1f), topK = 5, filter = Some(MetadataFilter.Equals("lang", "en"))) shouldBe Right(Seq.empty)

    val body = fake.requests.head.json
    body("size").num shouldBe 5
    body("query") shouldBe ujson.Obj(
      "bool" -> ujson.Obj(
        "must" -> ujson.Arr(
          ujson.Obj("knn" -> ujson.Obj("embedding" -> ujson.Obj("vector" -> ujson.Arr(1.0), "k" -> 100)))
        ),
        "filter" -> ujson.Arr(ujson.Obj("term" -> ujson.Obj("metadata.lang" -> "en")))
      )
    )
  }

  it should "not widen k on NMSLIB when there is no filter" in {
    val (os, fake) = searchStore(config.withEngine(OpenSearchEngine.Nmslib))

    os.search(Array(1f), topK = 7)

    fake.requests.head.json("query")("knn")("embedding")("k").num shouldBe 7
  }

  it should "use the configured k, but never less than topK nor more than 10,000" in {
    val (os, fake) = searchStore(config.withNumCandidates(50))
    os.search(Array(1f), topK = 10)
    os.search(Array(1f), topK = 80)
    fake.requests.map(_.json("query")("knn")("embedding")("k").num) shouldBe Seq(50, 80)

    val (nmslib, nmslibFake) = searchStore(config.withEngine(OpenSearchEngine.Nmslib))
    nmslib.search(Array(1f), topK = 5000, filter = Some(MetadataFilter.HasKey("x")))
    nmslibFake.requests.head.json("query")("bool")("must")(0)("knn")("embedding")("k").num shouldBe 10000
  }

  it should "return nothing for a topK below 1 without a request, and refuse one above 10,000" in {
    val (os, fake) = store(PartialFunction.empty)

    os.search(Array(1f), topK = 0) shouldBe Right(Seq.empty)
    val error = os.search(Array(1f), topK = 10001).left.value
    error shouldBe a[ValidationError]
    error.message should include("OpenSearch")
    fake.requests shouldBe empty
  }

  it should "answer an index that does not exist yet with no results" in {
    val (os, _) = store { case ("POST", _) => IndexNotFound }

    os.search(Array(1f), topK = 5) shouldBe Right(Seq.empty)
  }

  // ============================================================
  // search: scores
  // ============================================================

  it should "read the hits, keeping the cosinesimil score" in {
    val (os, _) = searchStore(config, hit("a", 0.9), hit("b", 0.6))

    val results = os.search(Array(1f, 0f), topK = 2).value
    results.map(_.record.id) shouldBe Seq("a", "b")
    results.map(_.score) shouldBe Seq(0.9, 0.6)
    results.head.record.content shouldBe Some("content of a")
    results.head.record.metadata shouldBe Map("lang" -> "en")
    results.head.record.embedding shouldBe Array(1f, 0f)
  }

  it should "keep the cosinesimil score on Faiss and NMSLIB too, where OpenSearch also scores (1 + cos) / 2" in {
    for (engine <- Seq(OpenSearchEngine.Faiss, OpenSearchEngine.Nmslib)) {
      val (os, _) = searchStore(config.withEngine(engine), hit("same", 1.0), hit("orthogonal", 0.5))

      os.search(Array(1f, 0f), topK = 2).value.map(_.score) shouldBe Seq(1.0, 0.5)
    }
  }

  it should "convert innerproduct scores to (1 + dot) / 2, clamped to [0, 1]" in {
    val (os, _) = searchStore(
      config.withSpaceType(OpenSearchSpaceType.InnerProduct),
      hit("over", 3.5),    // dot = 2.5, not unit length: clamped to 1
      hit("unit", 1.5),    // dot = 0.5
      hit("negative", 0.8) // dot = -0.25
    )

    val scores = os.search(Array(1f, 0f), topK = 3).value.map(_.score)
    scores(0) shouldBe 1.0
    scores(1) shouldBe 0.75 +- 1e-9
    scores(2) shouldBe 0.375 +- 1e-9
  }

  it should "keep l2 scores, clamp anything outside [0, 1], and score a hit without a score as 0" in {
    val (os, _) = searchStore(
      config.withSpaceType(OpenSearchSpaceType.L2),
      hit("near", 0.5),
      hit("odd", 1.2),
      ujson.Obj("_id" -> "none", "_source" -> source("none", Seq(1.0)))
    )

    os.search(Array(1f, 0f), topK = 3).value.map(_.score) shouldBe Seq(0.5, 1.0, 0.0)
  }

  it should "report an unexpected response shape as a processing error naming OpenSearch" in {
    val (os, _) = store { case ("POST", _) => ok("""{"hits":{}}""") }

    val error = os.search(Array(1f), topK = 1).left.value
    error shouldBe a[ProcessingError]
    error.message should include("OpenSearch search returned an unexpected response")
  }

  // ============================================================
  // The shared operations, through the OpenSearch store
  // ============================================================

  "The other operations" should "use the same document APIs as Elasticsearch" in {
    val (os, fake) = store {
      case ("GET", IndexUrl) => ok("{}")
      case ("GET", u) if u == s"$IndexUrl/_doc/doc%201" =>
        ok(ujson.Obj("_id" -> "doc 1", "found" -> true, "_source" -> source("doc 1", Seq(1.0))))
      case ("POST", u) if u == s"$IndexUrl/_mget" =>
        ok(ujson.Obj("docs" -> ujson.Arr(ujson.Obj("_id" -> "a", "found" -> true, "_source" -> source("a", Seq(1.0))))))
      case ("POST", u) if u == s"$IndexUrl/_count" => ok("""{"count":4}""")
      case ("POST", u) if u == s"$IndexUrl/_search" =>
        ok(searchResponse(ujson.Obj("_id" -> "a", "_source" -> source("a", Seq(1.0)))))
      case ("POST", u) if u == s"$IndexUrl/_delete_by_query?refresh=true" => ok("""{"deleted":2,"failures":[]}""")
      case ("POST", u) if u == BulkUrl                                    => BulkOk
      case ("DELETE", IndexUrl)                                           => ok("""{"acknowledged":true}""")
    }

    os.get("doc 1").value.map(_.id) shouldBe Some("doc 1")
    os.getBatch(Seq("a")).value.map(_.id) shouldBe Seq("a")
    os.count(Some(MetadataFilter.Equals("lang", "en"))) shouldBe Right(4L)
    os.list(limit = 2, offset = 1).value.map(_.id) shouldBe Seq("a")
    os.deleteByPrefix("doc-") shouldBe Right(2L)
    os.deleteByFilter(MetadataFilter.HasKey("draft")) shouldBe Right(2L)
    os.delete("a") shouldBe Right(())
    os.deleteBatch(Seq("b", "c")) shouldBe Right(())
    os.clear() shouldBe Right(())

    fake.sent("POST", s"$IndexUrl/_count").head.json shouldBe
      ujson.Obj("query" -> ujson.Obj("term" -> ujson.Obj("metadata.lang" -> "en")))
    val listBody = fake.sent("POST", s"$IndexUrl/_search").head.json
    listBody("from").num shouldBe 1
    listBody("size").num shouldBe 2
    fake.sent("POST", BulkUrl).map(_.ndjson.map(_("delete")("_id").str)) shouldBe Seq(Seq("a"), Seq("b", "c"))
  }

  "stats" should "read the knn_vector's dimension, the count and the store size" in {
    val (os, _) = store {
      case ("GET", IndexUrl)                       => ok("{}")
      case ("POST", u) if u == s"$IndexUrl/_count" => ok("""{"count":12}""")
      case ("GET", u) if u == s"$IndexUrl/_mapping" =>
        ok("""{"vectors":{"mappings":{"properties":{"embedding":{"type":"knn_vector","dimension":384}}}}}""")
      case ("GET", u) if u == s"$IndexUrl/_stats/store" =>
        ok("""{"_all":{"primaries":{"store":{"size_in_bytes":2048}}}}""")
    }

    os.stats().value shouldBe VectorStoreStats(totalRecords = 12L, dimensions = Set(384), sizeBytes = Some(2048L))
  }

  it should "describe an index that does not exist as an empty store" in {
    val (os, _) = store { case ("GET", IndexUrl) => IndexNotFound }

    os.stats().value shouldBe VectorStoreStats(0L)
  }

  // ============================================================
  // Errors and authentication
  // ============================================================

  "Errors" should "map 401 and 403 to AuthenticationError, 429 to RateLimitError and others to ServiceError" in {
    def failing(code: Int) = store { case ("GET", _) => status(code, "nope") }._1.get("x").left.value

    failing(401) shouldBe an[AuthenticationError]
    failing(403) shouldBe an[AuthenticationError]
    failing(429) shouldBe a[RateLimitError]
    val error = failing(500)
    error shouldBe a[ServiceError]
    error.asInstanceOf[ServiceError].httpStatus shouldBe 500
    error.message should include("OpenSearch get failed")
  }

  it should "redact credentials an error body echoes, the configured ones included" in {
    val cfg     = config.withBaseUrl("https://search.example.com").withAuth(OpenSearchAuth.BearerToken("my-jwt-123"))
    val (os, _) = store({ case ("GET", _) => status(500, EchoedCredentials.Text + " my-jwt-123") }, cfg)

    val message = os.get("x").left.value.formatted
    EchoedCredentials.leaked(message) shouldBe empty
    (message should not).include("my-jwt-123")
  }

  "Authentication" should "send a user as Basic and a token as Bearer" in {
    val (basicStore, basicFake) = store(
      { case ("POST", _) => ok("""{"count":0}""") },
      OpenSearchConfig("https://search.example.com", index = "vectors", auth = OpenSearchAuth.Basic("admin", "pw"))
    )
    val (tokenStore, tokenFake) = store(
      { case ("POST", _) => ok("""{"count":0}""") },
      OpenSearchConfig("https://search.example.com", index = "vectors", auth = OpenSearchAuth.BearerToken("jwt"))
    )

    basicStore.count() shouldBe Right(0L)
    tokenStore.count() shouldBe Right(0L)

    basicFake.requests.head.headers("Authorization") shouldBe "Basic YWRtaW46cHc="
    basicFake.requests.head.url shouldBe "https://search.example.com/vectors/_count"
    tokenFake.requests.head.headers("Authorization") shouldBe "Bearer jwt"
  }

  it should "send no Authorization header without credentials" in {
    val (os, fake) = store { case ("POST", _) => ok("""{"count":0}""") }

    os.count() shouldBe Right(0L)
    fake.requests.head.headers.keySet should not contain "Authorization"
  }

  "Building a store" should "refuse an invalid config, and close only a client it created" in {
    val fake = new FakeElasticsearch(PartialFunction.empty)
    OpenSearchVectorStore(config.withIndex("Bad"), fake).left.value shouldBe a[ConfigurationError]
    OpenSearchVectorStore(config.withIndex("Bad")).left.value shouldBe a[ConfigurationError]

    OpenSearchVectorStore(config, fake).value.close()
    fake.closed shouldBe false
    OpenSearchVectorStore(config).value.close()
    OpenSearchVectorStore.local("other").value.config.index shouldBe "other"
  }
}
