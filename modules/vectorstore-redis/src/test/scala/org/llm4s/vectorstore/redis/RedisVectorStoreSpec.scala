package org.llm4s.vectorstore.redis

import org.llm4s.error.{ AuthenticationError, ConfigurationError, NetworkError, ProcessingError, ValidationError }
import org.llm4s.vectorstore.{ MetadataFilter, VectorRecord }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RedisVectorStoreSpec extends AnyFlatSpec with Matchers with EitherValues {

  import FakeRedis.*
  import RedisQueries.{ entryTag, keyTag, vectorBytes }

  private def storeWith(fake: FakeRedis, config: RedisConfig = RedisConfig()): RedisVectorStore =
    RedisVectorStore(config, fake).value

  private def fresh(config: RedisConfig = RedisConfig()): (FakeRedis, RedisVectorStore) = {
    val fake = new FakeRedis
    (fake, storeWith(fake, config))
  }

  /** A fake whose index exists with 3-dimensional vectors. */
  private def withIndex(config: RedisConfig = RedisConfig()): (FakeRedis, RedisVectorStore) = {
    val fake = new FakeRedis
    fake.respond { case Seq("FT.INFO", _*) => FakeRedis.info(3) }
    (fake, storeWith(fake, config))
  }

  private val rec1 = VectorRecord("doc-1", Array(1f, 0f, 0f), Some("hello"), Map("lang" -> "en", "path" -> "/a"))
  private val rec2 = VectorRecord("doc-2", Array(0f, 1f, 0f))

  private def processingMessage(result: Either[org.llm4s.error.LLMError, ?]): String =
    result.left.value match {
      case e: ProcessingError => e.message
      case other              => fail(s"expected a ProcessingError, got $other")
    }

  // ============================================================
  // Construction
  // ============================================================

  "RedisVectorStore" should "refuse an invalid config" in {
    RedisVectorStore(RedisConfig(port = 0), new FakeRedis).left.value shouldBe a[ConfigurationError]
    RedisVectorStore(RedisConfig(port = 0)).left.value shouldBe a[ConfigurationError]
  }

  it should "build a store with a client of its own without connecting, and close it" in {
    val store = RedisVectorStore.local("my-index").value
    store.config.index shouldBe "my-index"
    store.close()
  }

  it should "leave a connection passed in open on close" in {
    val (fake, store) = fresh()
    store.close()
    fake.closed shouldBe false
  }

  // ============================================================
  // Upsert
  // ============================================================

  it should "create the index on the first upsert, sized to the record" in {
    val (fake, store) = fresh()
    store.upsert(rec1) shouldBe Right(())

    fake.names.take(2) shouldBe Seq("FT.INFO", "FT.CREATE")
    fake.named("FT.CREATE").head shouldBe Seq(
      "FT.CREATE",
      "llm4s-vectors",
      "ON",
      "HASH",
      "PREFIX",
      "1",
      "llm4s-vectors:",
      "SCHEMA",
      "seq",
      "NUMERIC",
      "SORTABLE",
      "meta_tags",
      "TAG",
      "SEPARATOR",
      ",",
      "CASESENSITIVE",
      "meta_keys",
      "TAG",
      "SEPARATOR",
      ",",
      "CASESENSITIVE",
      "embedding",
      "VECTOR",
      "HNSW",
      "6",
      "TYPE",
      "FLOAT32",
      "DIM",
      "3",
      "DISTANCE_METRIC",
      "COSINE"
    )
  }

  it should "create the index with the configured dimensions, algorithm, metric, index and prefix" in {
    val config = RedisConfig(
      index = "docs",
      keyPrefix = Some("v:"),
      algorithm = RedisVectorAlgorithm.Flat,
      distanceMetric = RedisDistanceMetric.L2,
      dimensions = Some(2)
    )
    val (fake, store) = fresh(config)
    store.upsert(VectorRecord("a", Array(1f, 2f))) shouldBe Right(())

    val create = fake.named("FT.CREATE").head
    create.slice(1, 7) shouldBe Seq("docs", "ON", "HASH", "PREFIX", "1", "v:")
    create.takeRight(9) shouldBe Seq("FLAT", "6", "TYPE", "FLOAT32", "DIM", "2", "DISTANCE_METRIC", "L2").prepended(
      "VECTOR"
    )
    fake.named("HSET").head(1) shouldBe "v:a"
  }

  it should "write each record as a DEL and an HSET of every field, in one pipeline" in {
    val (fake, store) = fresh()
    store.upsertBatch(Seq(rec1, rec2)) shouldBe Right(())

    fake.pipelines shouldBe 1
    fake.names.drop(2) shouldBe Seq("DEL", "HSET", "DEL", "HSET")
    fake.named("DEL").map(_.tail) shouldBe Seq(Seq("llm4s-vectors:doc-1"), Seq("llm4s-vectors:doc-2"))

    val hset   = fake.named("HSET").head
    val fields = hset.drop(2).grouped(2).map(p => p.head -> p(1)).toMap
    hset(1) shouldBe "llm4s-vectors:doc-1"
    fields("id") shouldBe "doc-1"
    fields("content") shouldBe "hello"
    ujson.read(fields("metadata")) shouldBe ujson.Obj("lang" -> "en", "path" -> "/a")
    fields("meta_tags") shouldBe s"${entryTag("lang", "en")},${entryTag("path", "/a")}"
    fields("meta_keys") shouldBe s"${keyTag("lang")},${keyTag("path")}"
    fields("seq").toLong should be > 0L

    val rawHset = fake.rawNamed("HSET").head
    val idx     = rawHset.map(FakeRedis.utf8).indexOf("embedding")
    rawHset(idx + 1) shouldBe vectorBytes(rec1.embedding)

    val second = fake.named("HSET")(1).drop(2).grouped(2).map(p => p.head -> p(1)).toMap
    second.keySet should not contain "content"
    second("meta_tags") shouldBe ""
    second("seq").toLong should be > fields("seq").toLong
  }

  it should "check the index only once" in {
    val (fake, store) = fresh()
    store.upsert(rec1) shouldBe Right(())
    store.upsert(rec2) shouldBe Right(())
    fake.named("FT.INFO") should have size 1
    fake.named("FT.CREATE") should have size 1
  }

  it should "send nothing for an empty batch" in {
    val (fake, store) = fresh()
    store.upsertBatch(Seq.empty) shouldBe Right(())
    store.deleteBatch(Seq.empty) shouldBe Right(())
    store.getBatch(Seq.empty) shouldBe Right(Seq.empty)
    fake.sent shouldBe empty
  }

  it should "use an existing index, refusing a record of another size" in {
    val (fake, store) = withIndex()
    store.upsert(VectorRecord("x", Array(1f, 2f))).left.value shouldBe a[ValidationError]
    fake.named("FT.CREATE") shouldBe empty
    fake.named("HSET") shouldBe empty
    store.upsert(rec1) shouldBe Right(())
  }

  it should "use an existing index whose size FT.INFO does not report" in {
    val fake = new FakeRedis().enqueue("FT.INFO", RedisReply.Arr(Seq(RedisReply.str("index_name"))))
    storeWith(fake).upsert(rec1) shouldBe Right(())
    fake.named("FT.CREATE") shouldBe empty
  }

  it should "refuse records of different sizes, or of the configured size, before sending anything" in {
    val (fake, store) = fresh()
    store.upsertBatch(Seq(rec1, VectorRecord("short", Array(1f)))).left.value shouldBe a[ValidationError]
    val (fake2, store2) = fresh(RedisConfig(dimensions = Some(4)))
    store2.upsert(rec1).left.value.message should include("expects 4")
    (fake.sent ++ fake2.sent) shouldBe empty
  }

  it should "refuse an empty embedding" in {
    val (fake, store) = fresh()
    store.upsert(VectorRecord("empty", Array.emptyFloatArray)).left.value shouldBe a[ValidationError]
    fake.named("FT.CREATE") shouldBe empty
  }

  it should "accept an index another writer created first" in {
    val fake = new FakeRedis().enqueue("FT.CREATE", RedisReply.Error("Index already exists"))
    storeWith(fake).upsert(rec1) shouldBe Right(())
  }

  it should "create the index without SORTABLE on a server that refuses it, as valkey-search does" in {
    val fake = new FakeRedis()
      .enqueue("FT.CREATE", RedisReply.Error("Invalid field type for field `SORTABLE`: Unknown argument `meta_tags`"))
    storeWith(fake).upsert(rec1) shouldBe Right(())

    val creates = fake.named("FT.CREATE")
    creates should have size 2
    creates.head.slice(8, 11) shouldBe Seq("seq", "NUMERIC", "SORTABLE")
    creates(1).slice(8, 11) shouldBe Seq("seq", "NUMERIC", "meta_tags")
  }

  it should "report an index it cannot create, or cannot inspect" in {
    val fake = new FakeRedis().enqueue("FT.CREATE", RedisReply.Error("ERR bad schema"))
    processingMessage(storeWith(fake).upsert(rec1)) should include("bad schema")

    val fake2 = new FakeRedis().enqueue("FT.INFO", RedisReply.Error("ERR something else"))
    processingMessage(storeWith(fake2).upsert(rec1)) should include("index info")
  }

  it should "report a write the server refuses, and a transport failure" in {
    val fake = new FakeRedis().enqueue("HSET", RedisReply.Error("OOM command not allowed"))
    processingMessage(storeWith(fake).upsert(rec1)) should include("OOM")

    val fake2 = new FakeRedis().enqueueResult("HSET", Left(NetworkError("down", None, "localhost:6379")))
    storeWith(fake2).upsert(rec1).left.value shouldBe a[NetworkError]
  }

  it should "report refused credentials as an authentication error" in {
    val fake = new FakeRedis().enqueue("FT.INFO", RedisReply.Error("NOAUTH Authentication required."))
    storeWith(fake).upsert(rec1).left.value shouldBe an[AuthenticationError]
  }

  // ============================================================
  // Delete
  // ============================================================

  it should "delete by ID with one DEL" in {
    val (fake, store) = fresh()
    store.delete("doc-1") shouldBe Right(())
    store.deleteBatch(Seq("a", "b")) shouldBe Right(())
    fake.commands shouldBe Seq(
      Seq("DEL", "llm4s-vectors:doc-1"),
      Seq("DEL", "llm4s-vectors:a", "llm4s-vectors:b")
    )
  }

  it should "report a delete the server refuses" in {
    val fake = new FakeRedis().enqueue("DEL", RedisReply.Error("READONLY You can't write against a read only replica."))
    processingMessage(storeWith(fake).delete("x")) should include("READONLY")
  }

  it should "delete by prefix, scanning every page of matching keys" in {
    val fake = new FakeRedis()
      .enqueue(
        "SCAN",
        scan("17", "llm4s-vectors:doc-1", "llm4s-vectors:doc-2"),
        scan("5"),
        scan("0", "llm4s-vectors:doc-3")
      )
    storeWith(fake).deleteByPrefix("doc-") shouldBe Right(3L)

    fake.named("SCAN").map(_(1)) shouldBe Seq("0", "17", "5")
    fake.named("SCAN").head shouldBe Seq("SCAN", "0", "MATCH", "llm4s-vectors:doc-*", "COUNT", "1000")
    fake.named("DEL") shouldBe Seq(
      Seq("DEL", "llm4s-vectors:doc-1", "llm4s-vectors:doc-2"),
      Seq("DEL", "llm4s-vectors:doc-3")
    )
  }

  it should "match a prefix literally, escaping glob characters" in {
    val (fake, store) = fresh(RedisConfig(keyPrefix = Some("v[1]:")))
    store.deleteByPrefix("a*?\\b") shouldBe Right(0L)
    fake.named("SCAN").head(3) shouldBe "v\\[1\\]:a\\*\\?\\\\b*"
  }

  it should "report a scan the server refuses, or an unexpected scan reply" in {
    val fake = new FakeRedis().enqueue("SCAN", RedisReply.Error("ERR invalid cursor"))
    processingMessage(storeWith(fake).deleteByPrefix("x")) should include("invalid cursor")

    val fake2 = new FakeRedis().enqueue("SCAN", RedisReply.Integer(1))
    processingMessage(storeWith(fake2).deleteByPrefix("x")) should include("unexpected")

    val fake3 = new FakeRedis().enqueue("SCAN", scan("0", "k")).enqueue("DEL", RedisReply.str("odd"))
    processingMessage(storeWith(fake3).deleteByPrefix("x")) should include("unexpected")
  }

  it should "delete by filter a page at a time until nothing matches" in {
    val fake = new FakeRedis()
      .enqueue("FT.SEARCH", keys(3, "llm4s-vectors:a", "llm4s-vectors:b"), keys(1, "llm4s-vectors:c"), keys(0))
    storeWith(fake).deleteByFilter(MetadataFilter.Equals("lang", "en")) shouldBe Right(3L)

    fake.named("FT.SEARCH").head shouldBe Seq(
      "FT.SEARCH",
      "llm4s-vectors",
      s"@meta_tags:{${entryTag("lang", "en")}}",
      "NOCONTENT",
      "LIMIT",
      "0",
      "1000",
      "DIALECT",
      "2"
    )
    fake.named("DEL").map(_.tail) shouldBe Seq(Seq("llm4s-vectors:a", "llm4s-vectors:b"), Seq("llm4s-vectors:c"))
  }

  it should "stop deleting by filter when a page deletes nothing" in {
    val fake = new FakeRedis()
      .enqueue("FT.SEARCH", keys(1, "llm4s-vectors:stale"))
      .enqueue("DEL", RedisReply.Integer(0))
    storeWith(fake).deleteByFilter(MetadataFilter.All) shouldBe Right(0L)
    fake.named("FT.SEARCH").head(2) shouldBe "*"
  }

  it should "delete nothing by a filter that matches nothing, or when the index is missing" in {
    val (fake, store) = fresh()
    store.deleteByFilter(MetadataFilter.In("lang", Set.empty)) shouldBe Right(0L)
    fake.sent shouldBe empty
    store.deleteByFilter(MetadataFilter.HasKey("lang")) shouldBe Right(0L)
  }

  it should "report a delete by filter the server refuses" in {
    val fake = new FakeRedis().enqueue("FT.SEARCH", RedisReply.Error("Syntax error"))
    processingMessage(storeWith(fake).deleteByFilter(MetadataFilter.All)) should include("Syntax error")

    val fake2 = new FakeRedis().enqueueResult("FT.SEARCH", Left(NetworkError("down", None, "x")))
    storeWith(fake2).deleteByFilter(MetadataFilter.All).left.value shouldBe a[NetworkError]

    val fake3 = new FakeRedis().enqueue("FT.SEARCH", keys(1, "k")).enqueue("DEL", RedisReply.Error("READONLY"))
    processingMessage(storeWith(fake3).deleteByFilter(MetadataFilter.All)) should include("READONLY")
  }

  it should "clear by dropping the index and deleting every key under the prefix" in {
    val fake = new FakeRedis()
      .respond { case Seq("FT.INFO", _*) => FakeRedis.info(3) }
      .enqueue("SCAN", scan("0", "llm4s-vectors:a"))
    val store = storeWith(fake)
    store.upsert(rec1) shouldBe Right(())
    fake.reset()

    store.clear() shouldBe Right(())
    fake.commands shouldBe Seq(
      Seq("FT.DROPINDEX", "llm4s-vectors"),
      Seq("SCAN", "0", "MATCH", "llm4s-vectors:*", "COUNT", "1000"),
      Seq("DEL", "llm4s-vectors:a")
    )

    fake.reset()
    store.upsert(rec1) shouldBe Right(())
    fake.names.head shouldBe "FT.INFO"
  }

  it should "clear a store whose index does not exist, and report another refusal" in {
    val fake = new FakeRedis().enqueue("FT.DROPINDEX", RedisReply.Error("Unknown Index name"))
    storeWith(fake).clear() shouldBe Right(())

    val fake2 = new FakeRedis().enqueue("FT.DROPINDEX", RedisReply.Error("ERR busy"))
    processingMessage(storeWith(fake2).clear()) should include("busy")
    fake2.named("SCAN") shouldBe empty
  }

  // ============================================================
  // Search
  // ============================================================

  it should "search with a KNN query and read the records and scores back, closest first" in {
    val fake = new FakeRedis().enqueue(
      "FT.SEARCH",
      search(
        2,
        "llm4s-vectors:doc-2" -> (recordFields("doc-2", rec2.embedding) :+ ("__vector_distance" -> RedisReply.str(
          "0.4"
        ))),
        "llm4s-vectors:doc-1"  -> (recordFields("doc-1", rec1.embedding, rec1.content, rec1.metadata) :+
          ("__vector_distance" -> RedisReply.str("0")))
      )
    )
    val results = storeWith(fake).search(Array(1f, 0f, 0f), topK = 2).value

    results.map(_.record) shouldBe Seq(rec1, rec2)
    results.map(_.score) shouldBe Seq(1.0, 0.8)

    val sent = fake.named("FT.SEARCH").head
    sent shouldBe Seq(
      "FT.SEARCH",
      "llm4s-vectors",
      "*=>[KNN $K @embedding $BLOB AS __vector_distance]",
      "PARAMS",
      "4",
      "K",
      "2",
      "BLOB",
      sent(8),
      "RETURN",
      "5",
      "id",
      "content",
      "metadata",
      "embedding",
      "__vector_distance",
      "LIMIT",
      "0",
      "2",
      "DIALECT",
      "2"
    )
    fake.rawNamed("FT.SEARCH").head(8) shouldBe vectorBytes(Array(1f, 0f, 0f))
  }

  it should "pre-filter a KNN search with the metadata filter" in {
    val (fake, store) = fresh()
    store.search(Array(1f, 0f, 0f), topK = 3, filter = Some(MetadataFilter.HasKey("lang"))) shouldBe Right(Seq.empty)
    fake.named("FT.SEARCH").head(2) shouldBe
      s"(@meta_keys:{${keyTag("lang")}})=>[KNN $$K @embedding $$BLOB AS __vector_distance]"
  }

  it should "not search for a filter that matches nothing, or for no results" in {
    val (fake, store) = fresh()
    store.search(Array(1f), topK = 3, filter = Some(MetadataFilter.Not(MetadataFilter.All))) shouldBe Right(Seq.empty)
    store.search(Array(1f), topK = 0) shouldBe Right(Seq.empty)
    fake.sent shouldBe empty
  }

  it should "score each metric's distance into [0, 1]" in {
    def scoreOf(metric: RedisDistanceMetric, distance: String): Double = {
      val fake = new FakeRedis().enqueue(
        "FT.SEARCH",
        search(
          1,
          "llm4s-vectors:a" -> (recordFields("a", Array(1f)) :+ ("__vector_distance" -> RedisReply.str(distance)))
        )
      )
      storeWith(fake, RedisConfig(distanceMetric = metric)).search(Array(1f), 1).value.head.score
    }
    scoreOf(RedisDistanceMetric.Cosine, "2") shouldBe 0.0
    scoreOf(RedisDistanceMetric.Cosine, "-0.0000001") shouldBe 1.0
    scoreOf(RedisDistanceMetric.InnerProduct, "0.5") shouldBe 0.75
    scoreOf(RedisDistanceMetric.InnerProduct, "3") shouldBe 0.0
    scoreOf(RedisDistanceMetric.L2, "1") shouldBe 0.5
    scoreOf(RedisDistanceMetric.L2, "0") shouldBe 1.0
  }

  it should "score a result without a distance as least similar" in {
    val fake = new FakeRedis().enqueue("FT.SEARCH", search(1, "llm4s-vectors:a" -> recordFields("a", Array(1f))))
    storeWith(fake).search(Array(1f), 1).value.head.score shouldBe 0.0
  }

  it should "answer an empty search when the index does not exist, and report other refusals" in {
    val (_, store) = fresh()
    store.search(Array(1f), 3) shouldBe Right(Seq.empty)

    val fake = new FakeRedis().enqueue("FT.SEARCH", RedisReply.Error("Syntax error at offset 3"))
    processingMessage(storeWith(fake).search(Array(1f), 3)) should include("Syntax error")

    val fake2 = new FakeRedis().enqueue("FT.SEARCH", RedisReply.str("not an array"))
    processingMessage(storeWith(fake2).search(Array(1f), 3)) should include("unexpected")

    val fake3 = new FakeRedis().enqueue(
      "FT.SEARCH",
      search(1, "llm4s-vectors:a" -> (recordFields("a", Array(1f)) :+ ("__vector_distance" -> RedisReply.str("NaN?"))))
    )
    processingMessage(storeWith(fake3).search(Array(1f), 1)) should include("unexpected")
  }

  // ============================================================
  // Get
  // ============================================================

  it should "get a record by ID, and None for a missing one" in {
    val fake =
      new FakeRedis().enqueue("HGETALL", fields(recordFields("doc-1", rec1.embedding, rec1.content, rec1.metadata)*))
    val store = storeWith(fake)
    store.get("doc-1") shouldBe Right(Some(rec1))
    store.get("nope") shouldBe Right(None)
    fake.commands shouldBe Seq(Seq("HGETALL", "llm4s-vectors:doc-1"), Seq("HGETALL", "llm4s-vectors:nope"))
  }

  it should "read a hash written by something else as best it can" in {
    val fake = new FakeRedis().enqueue(
      "HGETALL",
      fields(
        "metadata"  -> RedisReply.str("""{"n":3,"nil":null,"s":"x"}"""),
        "embedding" -> RedisReply.str("")
      )
    )
    storeWith(fake).get("ext") shouldBe Right(
      Some(VectorRecord("ext", Array.emptyFloatArray, None, Map("n" -> "3", "nil" -> "", "s" -> "x")))
    )

    val fake2 =
      new FakeRedis().enqueue("HGETALL", fields("metadata" -> RedisReply.str("[1]"), "embedding" -> RedisReply.Nil))
    storeWith(fake2).get("ext").value.map(_.metadata) shouldBe Some(Map.empty)
  }

  it should "report a get the server refuses, or unreadable metadata" in {
    val fake = new FakeRedis()
      .enqueue("HGETALL", RedisReply.Error("WRONGTYPE Operation against a key holding the wrong kind of value"))
    processingMessage(storeWith(fake).get("x")) should include("WRONGTYPE")

    val fake2 = new FakeRedis().enqueue("HGETALL", fields("metadata" -> RedisReply.str("{not json")))
    processingMessage(storeWith(fake2).get("x")) should include("unexpected")
  }

  it should "get several records in one pipeline, skipping missing ones" in {
    val fake = new FakeRedis().enqueue(
      "HGETALL",
      fields(recordFields("doc-1", rec1.embedding, rec1.content, rec1.metadata)*),
      RedisReply.Arr(Seq.empty),
      fields(recordFields("doc-2", rec2.embedding)*)
    )
    storeWith(fake).getBatch(Seq("doc-1", "nope", "doc-2")) shouldBe Right(Seq(rec1, rec2))
    fake.pipelines shouldBe 1
  }

  it should "report a batch get the server refuses" in {
    val fake = new FakeRedis().enqueue("HGETALL", RedisReply.Error("WRONGTYPE"))
    processingMessage(storeWith(fake).getBatch(Seq("a"))) should include("WRONGTYPE")
  }

  // ============================================================
  // Count, list, stats
  // ============================================================

  it should "count with a filter query" in {
    val fake  = new FakeRedis().enqueue("FT.SEARCH", keys(7), keys(2))
    val store = storeWith(fake)
    store.count() shouldBe Right(7L)
    store.count(Some(MetadataFilter.Equals("lang", "en"))) shouldBe Right(2L)
    fake.named("FT.SEARCH") shouldBe Seq(
      Seq("FT.SEARCH", "llm4s-vectors", "*", "LIMIT", "0", "0", "DIALECT", "2"),
      Seq("FT.SEARCH", "llm4s-vectors", s"@meta_tags:{${entryTag("lang", "en")}}", "LIMIT", "0", "0", "DIALECT", "2")
    )
  }

  it should "count zero when the index is missing or the filter matches nothing, and report refusals" in {
    val (fake, store) = fresh()
    store.count() shouldBe Right(0L)
    fake.reset()
    store.count(Some(MetadataFilter.In("k", Set.empty))) shouldBe Right(0L)
    fake.sent shouldBe empty

    val fake2 = new FakeRedis().enqueue("FT.SEARCH", RedisReply.Error("ERR timeout"))
    processingMessage(storeWith(fake2).count()) should include("timeout")

    val fake3 = new FakeRedis().enqueue("FT.SEARCH", RedisReply.Arr(Seq.empty))
    processingMessage(storeWith(fake3).count()) should include("unexpected")
  }

  it should "list a page in write order" in {
    val fake = new FakeRedis().enqueue(
      "FT.SEARCH",
      search(5, "llm4s-vectors:doc-1" -> recordFields("doc-1", rec1.embedding, rec1.content, rec1.metadata))
    )
    storeWith(fake).list(limit = 1, offset = 2, filter = Some(MetadataFilter.Contains("path", "a"))) shouldBe Right(
      Seq(rec1)
    )
    fake.named("FT.SEARCH").head shouldBe Seq(
      "FT.SEARCH",
      "llm4s-vectors",
      s"@meta_tags:{w'${keyTag("path")}x*_61*'}",
      "SORTBY",
      "seq",
      "ASC",
      "RETURN",
      "4",
      "id",
      "content",
      "metadata",
      "embedding",
      "LIMIT",
      "2",
      "1",
      "DIALECT",
      "2"
    )
  }

  it should "list nothing for a non-positive limit or a filter that matches nothing, and refuse a negative offset" in {
    val (fake, store) = fresh()
    store.list(limit = 0) shouldBe Right(Seq.empty)
    store.list(filter = Some(MetadataFilter.In("k", Set.empty))) shouldBe Right(Seq.empty)
    fake.sent shouldBe empty
    store.list(offset = -1).left.value shouldBe a[ValidationError]
    store.list() shouldBe Right(Seq.empty)
  }

  it should "report the record count and the vector size" in {
    val fake  = new FakeRedis().respond { case Seq("FT.INFO", _*) => FakeRedis.info(3) }.enqueue("FT.SEARCH", keys(4))
    val stats = storeWith(fake).stats().value
    stats.totalRecords shouldBe 4L
    stats.dimensions shouldBe Set(3)
  }

  it should "report an empty store when the index does not exist" in {
    val (_, store) = fresh()
    store.stats().value.totalRecords shouldBe 0L
  }

  it should "report an error from FT.INFO" in {
    val fake = new FakeRedis().enqueue("FT.INFO", RedisReply.Error("WRONGPASS invalid username-password pair"))
    storeWith(fake).stats().left.value shouldBe an[AuthenticationError]
  }
}
