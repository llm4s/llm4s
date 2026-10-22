package org.llm4s.vectorstore.elasticsearch

import org.llm4s.error.{
  AuthenticationError,
  ConfigurationError,
  NetworkError,
  ProcessingError,
  RateLimitError,
  ServiceError,
  ValidationError
}
import org.llm4s.http.HttpResponse
import org.llm4s.testutil.EchoedCredentials
import org.llm4s.types.Result
import org.llm4s.vectorstore.{ MetadataFilter, VectorRecord }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import FakeElasticsearch.*

/**
 * Every `VectorStore` operation of [[ElasticsearchVectorStore]] against a fake Elasticsearch: the request it sends
 * (method, URL, headers and body), how it reads the answer, and how a failure maps to an `LLMError`.
 */
class ElasticsearchVectorStoreHttpSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val Root     = "http://localhost:9200"
  private val IndexUrl = s"$Root/vectors"
  private val config   = ElasticsearchConfig(index = "vectors")

  private def store(
    respond: PartialFunction[(String, String), Result[HttpResponse]],
    cfg: ElasticsearchConfig = config
  ): (ElasticsearchVectorStore, FakeElasticsearch) = {
    val fake = new FakeElasticsearch(respond)
    (ElasticsearchVectorStore(cfg, fake).value, fake)
  }

  private val record = VectorRecord("doc-1", Array(0.5f, 0.25f), Some("hello"), Map("lang" -> "en"))

  private def source(id: String, vector: Seq[Double], content: Option[String], meta: (String, String)*): ujson.Obj = {
    val s = ujson.Obj(
      "id"        -> id,
      "embedding" -> ujson.Arr.from(vector.map(ujson.Num(_))),
      "metadata"  -> ujson.Obj.from(meta.map { case (k, v) => k -> ujson.Str(v) }),
      "seq"       -> 1
    )
    content.foreach(c => s("content") = c)
    s
  }

  private def hit(id: String, score: Double, src: ujson.Obj): ujson.Obj =
    ujson.Obj("_index" -> "vectors", "_id" -> id, "_score" -> score, "_source" -> src)

  private def searchResponse(hits: ujson.Value*): ujson.Obj =
    ujson.Obj("hits" -> ujson.Obj("total" -> ujson.Obj("value" -> hits.size), "hits" -> ujson.Arr.from(hits)))

  // ============================================================
  // upsert / upsertBatch
  // ============================================================

  "upsert" should "create a missing index with a dense_vector mapping sized to the record, then bulk-index it" in {
    val (es, fake) = store {
      case ("GET", IndexUrl)                                       => IndexNotFound
      case ("PUT", IndexUrl)                                       => ok("""{"acknowledged":true}""")
      case ("POST", u) if u == s"$IndexUrl/_bulk?refresh=wait_for" => BulkOk
    }

    es.upsert(record) shouldBe Right(())

    val mapping = fake.sent("PUT", IndexUrl).head.json("mappings")
    mapping("properties")("embedding") shouldBe ujson.Obj(
      "type"       -> "dense_vector",
      "dims"       -> 2,
      "index"      -> true,
      "similarity" -> "cosine"
    )
    mapping("properties")("id")("type").str shouldBe "keyword"
    mapping("properties")("content")("type").str shouldBe "text"
    mapping("properties")("seq")("type").str shouldBe "long"
    val template = mapping("dynamic_templates")(0)("llm4s_metadata_keywords")
    template("path_match").str shouldBe "metadata.*"
    template("mapping")("type").str shouldBe "keyword"

    val bulk = fake.sent("POST", s"$IndexUrl/_bulk?refresh=wait_for").head
    bulk.headers("Content-Type") shouldBe "application/x-ndjson"
    bulk.body should endWith("\n")
    val lines = bulk.ndjson
    lines should have size 2
    lines.head shouldBe ujson.Obj("index" -> ujson.Obj("_id" -> "doc-1"))
    lines(1)("id").str shouldBe "doc-1"
    lines(1)("embedding").arr.map(_.num) shouldBe Seq(0.5, 0.25)
    lines(1)("content").str shouldBe "hello"
    lines(1)("metadata") shouldBe ujson.Obj("lang" -> "en")
    lines(1)("seq").num should be > 0.0
  }

  it should "check for the index only once, and not create one that exists" in {
    val (es, fake) = store {
      case ("GET", IndexUrl)                                       => ok("""{"vectors":{}}""")
      case ("POST", u) if u == s"$IndexUrl/_bulk?refresh=wait_for" => BulkOk
    }

    es.upsert(record) shouldBe Right(())
    es.upsert(record.copy(id = "doc-2")) shouldBe Right(())

    fake.sent("GET", IndexUrl) should have size 1
    fake.sent("PUT", IndexUrl) shouldBe empty
    fake.sent("POST", s"$IndexUrl/_bulk?refresh=wait_for") should have size 2
  }

  it should "send one action and one document per record in a batch, with increasing sequence numbers" in {
    val (es, fake) = store {
      case ("GET", IndexUrl)                                       => ok("{}")
      case ("POST", u) if u == s"$IndexUrl/_bulk?refresh=wait_for" => BulkOk
    }
    val records = (1 to 3).map(i => VectorRecord(s"r$i", Array(i.toFloat, 0f)))

    es.upsertBatch(records) shouldBe Right(())

    val lines = fake.requests.last.ndjson
    lines.grouped(2).map(_.head("index")("_id").str).toSeq shouldBe Seq("r1", "r2", "r3")
    val seqs = lines.grouped(2).map(_(1)("seq").num).toSeq
    seqs shouldBe seqs.sorted
    seqs.distinct should have size 3
    lines.grouped(2).foreach(pair => pair(1).obj.contains("content") shouldBe false)
  }

  it should "create the index with the configured dimensions and similarity" in {
    val cfg = config.withDimensions(2).withSimilarity(ElasticsearchSimilarity.DotProduct)
    val (es, fake) = store(
      {
        case ("GET", IndexUrl)                                       => IndexNotFound
        case ("PUT", IndexUrl)                                       => ok("{}")
        case ("POST", u) if u == s"$IndexUrl/_bulk?refresh=wait_for" => BulkOk
      },
      cfg
    )

    es.upsert(record) shouldBe Right(())

    val vector = fake.sent("PUT", IndexUrl).head.json("mappings")("properties")("embedding")
    vector("dims").num shouldBe 2
    vector("similarity").str shouldBe "dot_product"
  }

  it should "treat an index another writer created first as created" in {
    val (es, _) = store {
      case ("GET", IndexUrl) => IndexNotFound
      case ("PUT", IndexUrl) =>
        status(400, """{"error":{"type":"resource_already_exists_exception","reason":"index [vectors] exists"}}""")
      case ("POST", u) if u == s"$IndexUrl/_bulk?refresh=wait_for" => BulkOk
    }

    es.upsert(record) shouldBe Right(())
  }

  it should "report a failure to create the index" in {
    val (es, fake) = store {
      case ("GET", IndexUrl) => IndexNotFound
      case ("PUT", IndexUrl) => status(400, """{"error":{"type":"mapper_parsing_exception"}}""")
    }

    val error = es.upsert(record).left.value
    error shouldBe a[ServiceError]
    error.message should (include("create index").and(include("mapper_parsing_exception")))
    fake.requests.map(_.method) should not contain "POST"
  }

  it should "report a failure to check for the index" in {
    val (es, _) = store { case ("GET", IndexUrl) => status(500, "boom") }

    es.upsert(record).left.value.message should include("check index")
  }

  it should "not wait for a refresh when refreshOnWrite is off" in {
    val (es, fake) = store(
      {
        case ("GET", IndexUrl)                      => ok("{}")
        case ("POST", u) if u == s"$IndexUrl/_bulk" => BulkOk
      },
      config.withRefreshOnWrite(false)
    )

    es.upsert(record) shouldBe Right(())
    fake.requests.last.url shouldBe s"$IndexUrl/_bulk"
  }

  it should "refuse a record whose size is not the configured one, sending nothing" in {
    val (es, fake) = store(PartialFunction.empty, config.withDimensions(3))

    val error = es.upsert(record).left.value
    error shouldBe a[ValidationError]
    error.message should (include("doc-1").and(include("2 dimensions")).and(include("expects 3")))
    fake.requests shouldBe empty
  }

  it should "refuse a batch whose records differ in size" in {
    val (es, fake) = store(PartialFunction.empty)

    es.upsertBatch(Seq(record, VectorRecord("odd", Array(1f, 2f, 3f)))).left.value.message should include("'odd'")
    fake.requests shouldBe empty
  }

  it should "refuse an empty embedding" in {
    val (es, _) = store(PartialFunction.empty)

    es.upsert(VectorRecord("empty", Array.emptyFloatArray)).left.value shouldBe a[ValidationError]
  }

  it should "send nothing for an empty batch" in {
    val (es, fake) = store(PartialFunction.empty)

    es.upsertBatch(Seq.empty) shouldBe Right(())
    fake.requests shouldBe empty
  }

  it should "report the records a bulk request rejected, naming a few and counting the rest" in {
    val items = ujson.Arr.from((1 to 5).map { i =>
      ujson.Obj(
        "index" -> ujson.Obj(
          "_id"    -> s"r$i",
          "status" -> 400,
          "error"  -> ujson.Obj("type" -> "document_parsing_exception", "reason" -> s"bad vector $i")
        )
      )
    } :+ ujson.Obj("index" -> ujson.Obj("_id" -> "fine", "status" -> 201)))
    val (es, _) = store {
      case ("GET", IndexUrl) => ok("{}")
      case ("POST", _)       => ok(ujson.Obj("errors" -> true, "items" -> items))
    }

    val error = es.upsertBatch((1 to 6).map(i => VectorRecord(s"r$i", Array(1f)))).left.value
    error shouldBe a[ProcessingError]
    error.message should (include("5 record(s)").and(include("'r1': bad vector 1")).and(include("and 2 more")))
    (error.message should not).include("fine")
  }

  it should "show the whole error of a rejected item that has no reason" in {
    val items = ujson.Arr(ujson.Obj("index" -> ujson.Obj("status" -> 400, "error" -> ujson.Obj("type" -> "odd"))))
    val (es, _) = store {
      case ("GET", IndexUrl) => ok("{}")
      case ("POST", _)       => ok(ujson.Obj("errors" -> true, "items" -> items))
    }

    es.upsert(record).left.value.message should (include("'?'").and(include("odd")))
  }

  // ============================================================
  // search
  // ============================================================

  "search" should "send a kNN query and read the hits, clamping each score to [0, 1]" in {
    val (es, fake) = store {
      case ("POST", u) if u == s"$IndexUrl/_search" =>
        ok(
          searchResponse(
            hit("a", 1.7, source("a", Seq(1.0, 0.0), Some("first"), "lang" -> "en")),
            hit("b", 0.4, source("b", Seq(0.0, 1.0), None)),
            hit("c", -0.2, source("c", Seq(0.5, 0.5), None))
          )
        )
    }

    val results = es.search(Array(1f, 0f), topK = 3).value

    results.map(_.record.id) shouldBe Seq("a", "b", "c")
    results.map(_.score) shouldBe Seq(1.0, 0.4, 0.0)
    results.head.record.content shouldBe Some("first")
    results.head.record.metadata shouldBe Map("lang" -> "en")
    results.head.record.embedding shouldBe Array(1f, 0f)

    val body = fake.requests.head.json
    body("size").num shouldBe 3
    body("knn") shouldBe ujson.Obj(
      "field"          -> "embedding",
      "query_vector"   -> ujson.Arr(1.0, 0.0),
      "k"              -> 3,
      "num_candidates" -> 100
    )
  }

  it should "put the metadata filter inside the kNN clause" in {
    val (es, fake) = store { case ("POST", _) => ok(searchResponse()) }

    es.search(Array(1f), topK = 20, filter = Some(MetadataFilter.Equals("lang", "en"))) shouldBe Right(Seq.empty)

    val knn = fake.requests.head.json("knn")
    knn("filter") shouldBe ujson.Obj("term" -> ujson.Obj("metadata.lang" -> "en"))
    knn("num_candidates").num shouldBe 200
  }

  it should "use the configured candidates, but never fewer than k" in {
    val (es, fake) = store({ case ("POST", _) => ok(searchResponse()) }, config.withNumCandidates(50))

    es.search(Array(1f), topK = 10)
    es.search(Array(1f), topK = 80)

    fake.requests.map(_.json("knn")("num_candidates").num) shouldBe Seq(50, 80)
  }

  it should "cap the default candidates at 10,000" in {
    val (es, fake) = store { case ("POST", _) => ok(searchResponse()) }

    es.search(Array(1f), topK = 5000)

    fake.requests.head.json("knn")("num_candidates").num shouldBe 10000
  }

  it should "answer an index that does not exist yet with no results" in {
    val (es, _) = store { case ("POST", _) => IndexNotFound }

    es.search(Array(1f), topK = 5) shouldBe Right(Seq.empty)
  }

  it should "return nothing for a topK below 1 without a request, and refuse one above 10,000" in {
    val (es, fake) = store(PartialFunction.empty)

    es.search(Array(1f), topK = 0) shouldBe Right(Seq.empty)
    es.search(Array(1f), topK = 10001).left.value shouldBe a[ValidationError]
    fake.requests shouldBe empty
  }

  it should "score a hit without a score as 0" in {
    val (es, _) = store { case ("POST", _) =>
      ok(searchResponse(ujson.Obj("_id" -> "a", "_source" -> source("a", Seq(1.0), None))))
    }

    es.search(Array(1f), topK = 1).value.head.score shouldBe 0.0
  }

  it should "report an unexpected response shape as a processing error" in {
    val (es, _) = store { case ("POST", _) => ok("""{"hits":{}}""") }

    es.search(Array(1f), topK = 1).left.value shouldBe a[ProcessingError]
  }

  it should "report a 404 that is not a missing index as an error" in {
    val (es, _) = store { case ("POST", _) => status(404, "no handler found for uri") }

    es.search(Array(1f), topK = 1).left.value shouldBe a[ServiceError]
  }

  // ============================================================
  // MetadataFilter translation, one case each
  // ============================================================

  "A metadata filter" should "translate All to match_all" in {
    ElasticsearchQueries.filterQuery(MetadataFilter.All) shouldBe ujson.Obj("match_all" -> ujson.Obj())
  }

  it should "translate Equals to a term query on the metadata key" in {
    ElasticsearchQueries.filterQuery(MetadataFilter.Equals("lang", "en")) shouldBe
      ujson.Obj("term" -> ujson.Obj("metadata.lang" -> "en"))
  }

  it should "translate Contains to a wildcard query that matches the substring literally" in {
    ElasticsearchQueries.filterQuery(MetadataFilter.Contains("path", "a*b?c\\d")) shouldBe
      ujson.Obj("wildcard" -> ujson.Obj("metadata.path" -> ujson.Obj("value" -> "*a\\*b\\?c\\\\d*")))
  }

  it should "translate HasKey to an exists query" in {
    ElasticsearchQueries.filterQuery(MetadataFilter.HasKey("author")) shouldBe
      ujson.Obj("exists" -> ujson.Obj("field" -> "metadata.author"))
  }

  it should "translate In to a terms query, and an empty In to match_none" in {
    ElasticsearchQueries.filterQuery(MetadataFilter.In("lang", Set("fr", "en"))) shouldBe
      ujson.Obj("terms" -> ujson.Obj("metadata.lang" -> ujson.Arr("en", "fr")))
    ElasticsearchQueries.filterQuery(MetadataFilter.In("lang", Set.empty)) shouldBe
      ujson.Obj("match_none" -> ujson.Obj())
  }

  it should "translate And to a bool filter of both sides" in {
    ElasticsearchQueries.filterQuery(
      MetadataFilter.And(MetadataFilter.HasKey("a"), MetadataFilter.HasKey("b"))
    ) shouldBe
      ujson.Obj(
        "bool" -> ujson.Obj(
          "filter" -> ujson.Arr(
            ujson.Obj("exists" -> ujson.Obj("field" -> "metadata.a")),
            ujson.Obj("exists" -> ujson.Obj("field" -> "metadata.b"))
          )
        )
      )
  }

  it should "translate Or to a bool should that needs one match" in {
    ElasticsearchQueries.filterQuery(MetadataFilter.Or(MetadataFilter.HasKey("a"), MetadataFilter.HasKey("b"))) shouldBe
      ujson.Obj(
        "bool" -> ujson.Obj(
          "should" -> ujson.Arr(
            ujson.Obj("exists" -> ujson.Obj("field" -> "metadata.a")),
            ujson.Obj("exists" -> ujson.Obj("field" -> "metadata.b"))
          ),
          "minimum_should_match" -> 1
        )
      )
  }

  it should "translate Not to a bool must_not" in {
    ElasticsearchQueries.filterQuery(MetadataFilter.Not(MetadataFilter.Equals("lang", "en"))) shouldBe
      ujson.Obj("bool" -> ujson.Obj("must_not" -> ujson.Arr(ujson.Obj("term" -> ujson.Obj("metadata.lang" -> "en")))))
  }

  it should "nest, through the DSL" in {
    import MetadataFilter.*
    val filter = Equals("a", "1").and(!HasKey("b")).or(In("c", Set("x")))
    val query  = ElasticsearchQueries.filterQuery(filter)

    query("bool")("should")(0)("bool")("filter")(1) shouldBe
      ujson.Obj("bool" -> ujson.Obj("must_not" -> ujson.Arr(ujson.Obj("exists" -> ujson.Obj("field" -> "metadata.b")))))
    query("bool")("should")(1) shouldBe ujson.Obj("terms" -> ujson.Obj("metadata.c" -> ujson.Arr("x")))
  }

  // ============================================================
  // get / getBatch
  // ============================================================

  "get" should "read a found document, URL-encoding the ID" in {
    val (es, fake) = store {
      case ("GET", u) if u == s"$IndexUrl/_doc/a%2Fb%20c%3F" =>
        ok(
          ujson.Obj("_id" -> "a/b c?", "found" -> true, "_source" -> source("a/b c?", Seq(0.5), Some("x"), "k" -> "v"))
        )
    }

    es.get("a/b c?").value shouldBe Some(VectorRecord("a/b c?", Array(0.5f), Some("x"), Map("k" -> "v")))
    fake.requests.head.headers.get("Authorization") shouldBe None
  }

  it should "answer a missing document, or a missing index, with None" in {
    val (es, _) = store {
      case ("GET", u) if u == s"$IndexUrl/_doc/gone"     => status(404, """{"_id":"gone","found":false}""")
      case ("GET", u) if u == s"$IndexUrl/_doc/notfound" => ok("""{"_id":"notfound","found":false}""")
    }

    es.get("gone") shouldBe Right(None)
    es.get("notfound") shouldBe Right(None)
  }

  it should "fall back to _id, and keep a non-string metadata value as JSON, for a document written elsewhere" in {
    val (es, _) = store { case ("GET", _) =>
      ok(
        ujson.Obj(
          "_id"     -> "foreign",
          "found"   -> true,
          "_source" -> ujson.Obj("metadata" -> ujson.Obj("n" -> 3, "flag" -> true, "none" -> ujson.Null))
        )
      )
    }

    val r = es.get("foreign").value.get
    r.id shouldBe "foreign"
    r.embedding shouldBe empty
    r.content shouldBe None
    r.metadata shouldBe Map("n" -> "3", "flag" -> "true", "none" -> "")
  }

  it should "map a 500 to a ServiceError with the status and the body" in {
    val (es, _) = store { case ("GET", _) => status(500, "Internal server error") }

    val error = es.get("x").left.value
    error shouldBe a[ServiceError]
    error.asInstanceOf[ServiceError].httpStatus shouldBe 500
    error.message should include("Internal server error")
  }

  it should "map a 401 or 403 to an AuthenticationError, and a 429 to a RateLimitError" in {
    val (es401, _) = store { case ("GET", _) => status(401, "missing authentication credentials") }
    val (es403, _) = store { case ("GET", _) => status(403, "action is unauthorized") }
    val (es429, _) = store { case ("GET", _) => status(429, "circuit_breaking_exception") }

    es401.get("x").left.value shouldBe an[AuthenticationError]
    es403.get("x").left.value shouldBe an[AuthenticationError]
    es429.get("x").left.value shouldBe a[RateLimitError]
  }

  it should "keep a transport failure as the client reported it" in {
    val (es, _) = store { case ("GET", _) => Left(NetworkError("connection refused", None, "http://localhost:9200")) }

    es.get("x").left.value shouldBe a[NetworkError]
  }

  it should "report a body that is not JSON as a processing error" in {
    val (es, _) = store { case ("GET", _) => ok("<html>proxy</html>") }

    es.get("x").left.value.message should include("unreadable JSON")
  }

  it should "redact credentials an error body echoes, the configured ones included" in {
    val cfg = config.withBaseUrl("https://es.example.com").withAuth(ElasticsearchAuth.ApiKey("my-encoded-key-123"))
    val (es, _) = store(
      { case ("GET", _) => status(500, EchoedCredentials.Text + " my-encoded-key-123") },
      cfg.withIndex("vectors")
    )

    val message = es.get("x").left.value.formatted
    EchoedCredentials.leaked(message) shouldBe empty
    (message should not).include("my-encoded-key-123")
  }

  "getBatch" should "fetch the IDs with _mget and skip those not found" in {
    val (es, fake) = store {
      case ("POST", u) if u == s"$IndexUrl/_mget" =>
        ok(
          ujson.Obj(
            "docs" -> ujson.Arr(
              ujson.Obj("_id" -> "a", "found" -> true, "_source" -> source("a", Seq(1.0), None)),
              ujson.Obj("_id" -> "b", "found" -> false)
            )
          )
        )
    }

    es.getBatch(Seq("a", "b")).value.map(_.id) shouldBe Seq("a")
    fake.requests.head.json shouldBe ujson.Obj("ids" -> ujson.Arr("a", "b"))
    fake.requests.head.headers("Content-Type") shouldBe "application/json"
  }

  it should "send nothing for no IDs, and answer a missing index with nothing" in {
    val (es, fake) = store { case ("POST", _) => IndexNotFound }

    es.getBatch(Seq.empty) shouldBe Right(Seq.empty)
    fake.requests shouldBe empty
    es.getBatch(Seq("a")) shouldBe Right(Seq.empty)
  }

  // ============================================================
  // delete / deleteBatch / deleteByPrefix / deleteByFilter
  // ============================================================

  "deleteBatch" should "bulk-delete the IDs, accepting those already gone" in {
    val items = ujson.Arr(
      ujson.Obj("delete" -> ujson.Obj("_id" -> "a", "status" -> 200, "result" -> "deleted")),
      ujson.Obj(
        "delete" -> ujson.Obj("_id" -> "b", "status" -> 404, "error" -> ujson.Obj("type" -> "not_found"))
      )
    )
    val (es, fake) = store {
      case ("GET", IndexUrl)                                       => ok("{}")
      case ("POST", u) if u == s"$IndexUrl/_bulk?refresh=wait_for" => ok(ujson.Obj("errors" -> true, "items" -> items))
    }

    es.deleteBatch(Seq("a", "b")) shouldBe Right(())
    fake.requests.last.ndjson shouldBe Seq(
      ujson.Obj("delete" -> ujson.Obj("_id" -> "a")),
      ujson.Obj("delete" -> ujson.Obj("_id" -> "b"))
    )
  }

  it should "report an item that failed for another reason" in {
    val items = ujson.Arr(
      ujson.Obj("delete" -> ujson.Obj("_id" -> "a", "status" -> 409, "error" -> ujson.Obj("reason" -> "conflict")))
    )
    val (es, _) = store {
      case ("GET", IndexUrl) => ok("{}")
      case ("POST", _)       => ok(ujson.Obj("errors" -> true, "items" -> items))
    }

    es.delete("a").left.value.message should include("'a': conflict")
  }

  it should "do nothing when the index does not exist, and send nothing for no IDs" in {
    val (es, fake) = store { case ("GET", IndexUrl) => IndexNotFound }

    es.deleteBatch(Seq.empty) shouldBe Right(())
    fake.requests shouldBe empty
    es.delete("a") shouldBe Right(())
    fake.requests.map(_.method) shouldBe Seq("GET")
  }

  "deleteByPrefix" should "delete by a prefix query on the id field and return the count" in {
    val (es, fake) = store {
      case ("POST", u) if u == s"$IndexUrl/_delete_by_query?refresh=true" => ok("""{"deleted":4,"failures":[]}""")
    }

    es.deleteByPrefix("doc-") shouldBe Right(4L)
    fake.requests.head.json shouldBe ujson.Obj(
      "query" -> ujson.Obj("prefix" -> ujson.Obj("id" -> ujson.Obj("value" -> "doc-")))
    )
  }

  it should "count 0 for an index that does not exist" in {
    val (es, _) = store { case ("POST", _) => IndexNotFound }

    es.deleteByPrefix("doc-") shouldBe Right(0L)
  }

  it should "report the failures the response lists" in {
    val (es, _) = store { case ("POST", _) =>
      ok("""{"deleted":1,"failures":[{"cause":{"reason":"version conflict"}}]}""")
    }

    es.deleteByPrefix("doc-").left.value.message should include("version conflict")
  }

  "deleteByFilter" should "delete by the translated filter, without refresh when refreshOnWrite is off" in {
    val (es, fake) = store(
      { case ("POST", u) if u == s"$IndexUrl/_delete_by_query" => ok("""{"deleted":2}""") },
      config.withRefreshOnWrite(false)
    )

    es.deleteByFilter(MetadataFilter.Equals("lang", "en")) shouldBe Right(2L)
    fake.requests.head.json shouldBe ujson.Obj("query" -> ujson.Obj("term" -> ujson.Obj("metadata.lang" -> "en")))
  }

  // ============================================================
  // count / list
  // ============================================================

  "count" should "count every record, or those a filter matches" in {
    val (es, fake) = store { case ("POST", u) if u == s"$IndexUrl/_count" => ok("""{"count":7}""") }

    es.count() shouldBe Right(7L)
    es.count(Some(MetadataFilter.HasKey("k"))) shouldBe Right(7L)
    fake.requests.map(_.json("query")) shouldBe Seq(
      ujson.Obj("match_all" -> ujson.Obj()),
      ujson.Obj("exists"    -> ujson.Obj("field" -> "metadata.k"))
    )
  }

  it should "count 0 for an index that does not exist" in {
    val (es, _) = store { case ("POST", _) => IndexNotFound }

    es.count() shouldBe Right(0L)
  }

  "list" should "page through the records in insertion order" in {
    val (es, fake) = store {
      case ("POST", u) if u == s"$IndexUrl/_search" =>
        ok(searchResponse(hit("a", 1.0, source("a", Seq(1.0), None)), hit("b", 1.0, source("b", Seq(2.0), None))))
    }

    es.list(limit = 2, offset = 4, filter = Some(MetadataFilter.Equals("k", "v"))).value.map(_.id) shouldBe Seq(
      "a",
      "b"
    )
    fake.requests.head.json shouldBe ujson.Obj(
      "query" -> ujson.Obj("term" -> ujson.Obj("metadata.k" -> "v")),
      "from"  -> 4,
      "size"  -> 2,
      "sort"  -> ujson.Arr(ujson.Obj("seq" -> "asc"), ujson.Obj("id" -> "asc"))
    )
  }

  it should "return nothing for a limit below 1, refuse a negative offset, and answer a missing index with nothing" in {
    val (es, fake) = store { case ("POST", _) => IndexNotFound }

    es.list(limit = 0) shouldBe Right(Seq.empty)
    es.list(offset = -1).left.value shouldBe a[ValidationError]
    fake.requests shouldBe empty
    es.list() shouldBe Right(Seq.empty)
  }

  // ============================================================
  // clear / stats / close
  // ============================================================

  "clear" should "delete the index, so the next upsert checks for it again" in {
    val (es, fake) = store {
      case ("GET", IndexUrl)                                       => ok("{}")
      case ("DELETE", IndexUrl)                                    => ok("""{"acknowledged":true}""")
      case ("POST", u) if u == s"$IndexUrl/_bulk?refresh=wait_for" => BulkOk
    }

    es.upsert(record) shouldBe Right(())
    es.clear() shouldBe Right(())
    es.upsert(record) shouldBe Right(())

    fake.requests.map(_.method) shouldBe Seq("GET", "POST", "DELETE", "GET", "POST")
  }

  it should "accept an index that does not exist, and report another failure" in {
    val (missing, _) = store { case ("DELETE", _) => IndexNotFound }
    val (broken, _)  = store { case ("DELETE", _) => status(500, "nope") }

    missing.clear() shouldBe Right(())
    broken.clear().left.value.message should include("clear")
  }

  "stats" should "read the count, the vector size and the store size" in {
    val (es, _) = store {
      case ("GET", IndexUrl)                       => ok("{}")
      case ("POST", u) if u == s"$IndexUrl/_count" => ok("""{"count":12}""")
      case ("GET", u) if u == s"$IndexUrl/_mapping" =>
        ok("""{"vectors":{"mappings":{"properties":{"embedding":{"type":"dense_vector","dims":384}}}}}""")
      case ("GET", u) if u == s"$IndexUrl/_stats/store" =>
        ok("""{"_all":{"primaries":{"store":{"size_in_bytes":2048}}}}""")
    }

    val stats = es.stats().value
    stats.totalRecords shouldBe 12L
    stats.dimensions shouldBe Set(384)
    stats.sizeBytes shouldBe Some(2048L)
  }

  it should "describe an index that does not exist as an empty store" in {
    val (es, fake) = store { case ("GET", IndexUrl) => IndexNotFound }

    val stats = es.stats().value
    stats.totalRecords shouldBe 0L
    stats.dimensions shouldBe empty
    stats.sizeBytes shouldBe None
    fake.requests should have size 1
  }

  it should "leave out what the responses do not carry" in {
    val (es, _) = store {
      case ("GET", IndexUrl)                        => ok("{}")
      case ("POST", _)                              => ok("""{"count":0}""")
      case ("GET", u) if u == s"$IndexUrl/_mapping" => ok("""{"vectors":{"mappings":{}}}""")
      case ("GET", _)                               => ok("{}")
    }

    es.stats().value shouldBe org.llm4s.vectorstore.VectorStoreStats(0L)
  }

  "close" should "close a client the store created, but not one it was given" in {
    val fake = new FakeElasticsearch(PartialFunction.empty)
    ElasticsearchVectorStore(config, fake).value.close()
    fake.closed shouldBe false

    // The owning constructor builds a JDK client; closing it must not throw.
    ElasticsearchVectorStore(config).value.close()
    ElasticsearchVectorStore.local("other").value.config.index shouldBe "other"
  }

  // ============================================================
  // authentication
  // ============================================================

  "Authentication" should "send an API key as ApiKey and a user as Basic" in {
    val (apiKeyStore, apiKeyFake) = store(
      { case ("POST", _) => ok("""{"count":0}""") },
      ElasticsearchConfig("https://es.example.com", index = "vectors", auth = ElasticsearchAuth.ApiKey("ZW5jb2RlZA=="))
    )
    val (basicStore, basicFake) = store(
      { case ("POST", _) => ok("""{"count":0}""") },
      ElasticsearchConfig("https://es.example.com", index = "vectors", auth = ElasticsearchAuth.Basic("elastic", "pw"))
    )

    apiKeyStore.count() shouldBe Right(0L)
    basicStore.count() shouldBe Right(0L)

    apiKeyFake.requests.head.headers("Authorization") shouldBe "ApiKey ZW5jb2RlZA=="
    apiKeyFake.requests.head.url shouldBe "https://es.example.com/vectors/_count"
    basicFake.requests.head.headers("Authorization") shouldBe "Basic ZWxhc3RpYzpwdw=="
  }

  it should "refuse an invalid config when the store is built" in {
    val fake = new FakeElasticsearch(PartialFunction.empty)
    ElasticsearchVectorStore(config.withIndex("Bad"), fake).left.value shouldBe a[ConfigurationError]
    ElasticsearchVectorStore(config.withIndex("Bad")).left.value shouldBe a[ConfigurationError]
  }
}
