package org.llm4s.vectorstore

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalamock.scalatest.MockFactory
import org.llm4s.http.{ HttpResponse, Llm4sHttpClient }

/**
 * Unit tests for QdrantVectorStore HTTP methods.
 *
 * Tests the HTTP helper methods (httpGet, httpPost, httpPut, httpDelete, handleResponse)
 * indirectly through public API methods with a mocked HTTP client.
 */
class QdrantVectorStoreHttpSpec extends AnyFlatSpec with Matchers with MockFactory {

  private val testUrl        = "http://localhost:6333"
  private val testCollection = "test_collection"
  private val collectionsUrl = s"$testUrl/collections/$testCollection"
  private val pointsUrl      = s"$collectionsUrl/points"

  // Qdrant only accepts an unsigned integer or a UUID as a point ID, so a record ID like
  // "test-1" reaches it as a UUID derived from that string, with the record's own ID carried
  // in the payload. Spelled out as a literal rather than recomputed, so that a change to the
  // derivation - which would orphan every point already written - shows up as a test failure.
  private val testPointId  = "0d75226c-b7a2-849e-8abb-9be195dca8ec"
  private val testPointUrl = s"$pointsUrl/$testPointId?with_payload=true&with_vector=true"

  // Helper to create a mock HTTP client
  private def createMockClient(): Llm4sHttpClient = stub[Llm4sHttpClient]

  // Helper to create HttpResponse
  private def httpResponse(statusCode: Int, body: String): HttpResponse =
    HttpResponse(statusCode, body, Map.empty)

  private val testConfig = QdrantVectorStore.Config(
    host = "localhost",
    port = 6333,
    collectionName = testCollection
  )

  // Helper to create a QdrantVectorStore with mocked HTTP client
  private def createStore(mockClient: Llm4sHttpClient): QdrantVectorStore = {
    // Mock the initial collection check in ensureCollection()
    (mockClient.get _).when(collectionsUrl, *, *, *).returns(httpResponse(404, "Not found"))

    QdrantVectorStore(testConfig, mockClient) match {
      case Right(store) => store
      case Left(err)    => fail(s"Failed to create store: ${err.formatted}")
    }
  }

  // ============================================================
  // httpGet tests (via get, stats methods)
  // ============================================================

  "httpGet via get method" should "handle successful response (200 OK)" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)

    val responseJson = """{
      "result": {
        "id": "test-1",
        "vector": [0.1, 0.2, 0.3],
        "payload": {
          "content": "Test content",
          "meta_type": "document"
        }
      }
    }"""

    (mockClient.get _)
      .when(testPointUrl, *, *, *)
      .returns(httpResponse(200, responseJson))

    val result = store.get("test-1")
    result.isRight shouldBe true
    result.toOption.flatten.map(_.id) shouldBe Some("test-1")
  }

  it should "report a 404 as an absent record, not as an error" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)

    (mockClient.get _)
      .when(testPointUrl, *, *, *)
      .returns(httpResponse(404, "Not found"))

    // Qdrant 404s for a point that does not exist, and for a collection not created yet.
    // `get` returns an Option precisely so that absence has somewhere to go other than Left.
    store.get("test-1") shouldBe Right(None)
  }

  it should "handle 500 Internal Server Error" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)

    (mockClient.get _)
      .when(testPointUrl, *, *, *)
      .returns(httpResponse(500, "Internal server error"))

    val result = store.get("test-1")
    result.isLeft shouldBe true
    result.left.map { error =>
      error.formatted should include("500")
      error.formatted should include("Internal server error")
    }
  }

  it should "handle other HTTP errors (403 Forbidden)" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)

    (mockClient.get _)
      .when(testPointUrl, *, *, *)
      .returns(httpResponse(403, "Forbidden"))

    val result = store.get("test-1")
    result.isLeft shouldBe true
    result.left.map { error =>
      error.formatted should include("403")
      error.formatted should include("Forbidden")
    }
  }

  it should "handle HTTP client exceptions" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)

    (mockClient.get _)
      .when(testPointUrl, *, *, *)
      .throws(new RuntimeException("Connection timeout"))

    val result = store.get("test-1")
    result.isLeft shouldBe true
    result.left.map { error =>
      error.formatted should include("HTTP GET failed")
      error.formatted should include("Connection timeout")
    }
  }

  "httpGet via stats method" should "handle successful response" in {
    val mockClient = createMockClient()

    // Mock the collection check in ensureCollection (constructor)
    (mockClient.get _)
      .when(collectionsUrl, *, *, *)
      .returns(
        httpResponse(
          200,
          """{
      "result": {
        "vectors_count": 42,
        "points_count": 42,
        "config": {
          "params": {
            "vectors": {
              "size": 3
            }
          }
        }
      }
    }"""
        )
      )
      .anyNumberOfTimes()

    val store = createStore(mockClient)

    val result = store.stats()
    result match {
      case Right(stats) =>
        stats.totalRecords shouldBe 42
        stats.dimensions shouldBe Set(3)
      case Left(err) => fail(s"Expected Right but got Left: ${err.formatted}")
    }
  }

  // ============================================================
  // Point ID mapping
  // ============================================================

  "point IDs" should "be sent to Qdrant as a UUID, with the record ID kept in the payload" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)

    // The store creates the collection first, since the mocked existence check 404s.
    (mockClient.put _).when(collectionsUrl, *, *, *).returns(httpResponse(200, """{"result": "ok"}"""))
    (mockClient.put _).when(s"$pointsUrl?wait=true", *, *, *).returns(httpResponse(200, """{"result": "ok"}"""))

    store.upsert(VectorRecord("test-1", Array(0.1f, 0.2f, 0.3f), Some("Test content"))) shouldBe Right(())

    (mockClient.put _).verify(
      // Guard on the URL first: the collection-creation PUT above carries no points array.
      where { (url: String, _: Map[String, String], body: String, _: Int) =>
        url == s"$pointsUrl?wait=true" && {
          val point = ujson.read(body)("points").arr.head
          point("id").str == testPointId && point("payload")("llm4s_id").str == "test-1"
        }
      }
    )
  }

  it should "pass a record ID that is already a UUID through unchanged" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)
    val uuid       = "123e4567-e89b-12d3-a456-426614174000"

    (mockClient.get _)
      .when(s"$pointsUrl/$uuid?with_payload=true&with_vector=true", *, *, *)
      .returns(httpResponse(404, "Not found"))

    // Reaching the stub at all is the assertion: an unmapped URL would not match it.
    store.get(uuid) shouldBe Right(None)
  }

  it should "keep a caller-supplied UUID clear of the derived ID it looks like" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)

    // The derivation is public, so a caller can supply the UUID that "test-1" maps to as a
    // record ID in its own right. The two records must not end up on the same point.
    val literalUuidRecord = testPointId
    val itsPoint          = QdrantVectorStore.derivedPointId(literalUuidRecord)
    itsPoint should not be testPointId

    (mockClient.get _)
      .when(testPointUrl, *, *, *)
      .returns(
        httpResponse(
          200,
          s"""{"result": {"id": "$testPointId", "vector": [0.1],
                                     "payload": {"llm4s_id": "test-1"}}}"""
        )
      )
    (mockClient.get _)
      .when(s"$pointsUrl/$itsPoint?with_payload=true&with_vector=true", *, *, *)
      .returns(
        httpResponse(
          200,
          s"""{"result": {"id": "$itsPoint", "vector": [0.2],
                                     "payload": {"llm4s_id": "$literalUuidRecord"}}}"""
        )
      )

    store.get("test-1").toOption.flatten.map(_.id) shouldBe Some("test-1")
    store.get(literalUuidRecord).toOption.flatten.map(_.id) shouldBe Some(literalUuidRecord)
  }

  it should "be read back from the payload rather than from the point ID" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)

    val responseJson = """{
      "result": {
        "id": "0d75226c-b7a2-849e-8abb-9be195dca8ec",
        "vector": [0.1, 0.2, 0.3],
        "payload": {
          "llm4s_id": "test-1",
          "content": "Test content"
        }
      }
    }"""

    (mockClient.get _).when(testPointUrl, *, *, *).returns(httpResponse(200, responseJson))

    store.get("test-1").toOption.flatten.map(_.id) shouldBe Some("test-1")
  }

  it should "fall back to the point ID for a point written without one" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)

    // A point put there by something other than llm4s carries no `llm4s_id`, and Qdrant's
    // other legal ID form is an integer rather than a string.
    (mockClient.post _)
      .when(s"$pointsUrl/search", *, *, *)
      .returns(
        httpResponse(
          200,
          """{"result": [{ "id": 7, "score": 0.5, "vector": [0.1], "payload": { "content": "foreign" } }]}"""
        )
      )

    store.search(Array(0.1f), topK = 1).toOption.flatMap(_.headOption).map(_.record.id) shouldBe Some("7")
  }

  it should "degrade to the raw JSON for a point ID that is neither string nor integer" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)

    // Not something Qdrant should ever send. Reading it as text keeps a malformed response
    // from throwing inside what is otherwise a total read path.
    (mockClient.post _)
      .when(s"$pointsUrl/search", *, *, *)
      .returns(httpResponse(200, """{"result": [{ "id": null, "score": 0.5, "vector": [0.1], "payload": {} }]}"""))

    store.search(Array(0.1f), topK = 1).toOption.flatMap(_.headOption).map(_.record.id) shouldBe Some("null")
  }

  it should "be sent as derived UUIDs when deleting a batch" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)

    (mockClient.post _)
      .when(s"$pointsUrl/delete?wait=true", *, *, *)
      .returns(httpResponse(200, """{"result": "ok"}"""))

    store.deleteBatch(Seq("test-1", "test-2")) shouldBe Right(())

    (mockClient.post _).verify(
      where { (url: String, _: Map[String, String], body: String, _: Int) =>
        url == s"$pointsUrl/delete?wait=true" &&
        ujson.read(body)("points").arr.map(_.str).toSeq ==
          Seq(testPointId, QdrantVectorStore.derivedPointId("test-2"))
      }
    )
  }

  // ============================================================
  // Reads of a store that does not exist yet
  // ============================================================

  "a read of a collection that does not exist" should "count zero rather than fail" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)

    // `clear()` deletes the collection outright and the next upsert recreates it, so an
    // empty store legitimately 404s.
    (mockClient.post _).when(s"$pointsUrl/count", *, *, *).returns(httpResponse(404, "Not found"))

    store.count() shouldBe Right(0L)
  }

  it should "search and list as empty rather than fail" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)

    (mockClient.post _).when(s"$pointsUrl/search", *, *, *).returns(httpResponse(404, "Not found"))
    (mockClient.post _).when(s"$pointsUrl/scroll", *, *, *).returns(httpResponse(404, "Not found"))

    store.search(Array(0.1f, 0.2f, 0.3f), topK = 5) shouldBe Right(Seq.empty)
    store.list(limit = 10, offset = 0) shouldBe Right(Seq.empty)
  }

  it should "report no dimensions for a collection configured with named vectors" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)

    // Named-vector collections nest each vector's size under its name, so there is no single
    // dimension to report.
    val namedVectors = """{
      "result": {
        "points_count": 3,
        "config": { "params": { "vectors": { "title": { "size": 4, "distance": "Cosine" } } } }
      }
    }"""

    val freshClient = stub[Llm4sHttpClient]
    (freshClient.get _).when(collectionsUrl, *, *, *).returns(httpResponse(200, namedVectors))
    val namedStore = QdrantVectorStore(testConfig, freshClient) match {
      case Right(s)  => s
      case Left(err) => fail(s"Failed to create store: ${err.formatted}")
    }

    namedStore.stats() shouldBe Right(VectorStoreStats(totalRecords = 3L, dimensions = Set.empty, sizeBytes = None))
    store.stats().isRight shouldBe true
  }

  it should "report a zero-record store rather than fail" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)

    // The store's constructor already stubs this URL as a 404.
    store.stats() shouldBe Right(VectorStoreStats(totalRecords = 0L, dimensions = Set.empty, sizeBytes = None))
  }

  // ============================================================
  // deleteByPrefix
  // ============================================================

  "deleteByPrefix" should "match on the record ID and delete by the ID Qdrant gave it" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)

    // No `next_page_offset`, so one page ends the scroll. The integer ID is what a point
    // written by another tool looks like; the record ID still comes from the payload.
    val scrollJson = """{
      "result": {
        "points": [
          { "id": 7, "payload": { "llm4s_id": "doc-1" } },
          { "id": "0d75226c-b7a2-849e-8abb-9be195dca8ec", "payload": { "llm4s_id": "other-1" } }
        ]
      }
    }"""

    (mockClient.post _).when(s"$pointsUrl/scroll", *, *, *).returns(httpResponse(200, scrollJson))
    (mockClient.post _).when(s"$pointsUrl/delete?wait=true", *, *, *).returns(httpResponse(200, """{"result": "ok"}"""))

    store.deleteByPrefix("doc-") shouldBe Right(1L)

    (mockClient.post _).verify(
      where { (url: String, _: Map[String, String], body: String, _: Int) =>
        url == s"$pointsUrl/delete?wait=true" && ujson.read(body)("points") == ujson.Arr(ujson.Num(7))
      }
    )
  }

  // ============================================================
  // httpPost tests (via search, count, getBatch methods)
  // ============================================================

  "httpPost via search method" should "handle successful response (200 OK)" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)

    val searchResponseJson = """{
      "result": [
        {
          "id": "test-1",
          "score": 0.95,
          "vector": [0.1, 0.2, 0.3],
          "payload": {
            "content": "Test content"
          }
        }
      ]
    }"""

    (mockClient.post _).when(s"$pointsUrl/search", *, *, *).returns(httpResponse(200, searchResponseJson))

    val result = store.search(Array(0.1f, 0.2f, 0.3f), topK = 1)
    result match {
      case Right(scored) =>
        scored should have size 1
        scored.headOption match {
          case Some(r) => r.score shouldBe 0.95
          case None    => fail("Expected at least one result but got empty list")
        }
      case Left(err) => fail(s"Expected Right but got Left: ${err.formatted}")
    }
  }

  it should "handle 400 Bad Request error" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)

    (mockClient.post _)
      .when(s"$pointsUrl/search", *, *, *)
      .returns(httpResponse(400, "Bad Request: Invalid vector dimensions"))

    val result = store.search(Array(0.1f, 0.2f), topK = 1)
    result.isLeft shouldBe true
    result.left.map { error =>
      error.formatted should include("400")
      error.formatted should include("Bad Request")
    }
  }

  it should "handle 500 Internal Server Error" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)

    (mockClient.post _).when(s"$pointsUrl/search", *, *, *).returns(httpResponse(500, "Internal server error"))

    val result = store.search(Array(0.1f, 0.2f, 0.3f), topK = 1)
    result.isLeft shouldBe true
    result.left.map(error => error.formatted should include("500"))
  }

  it should "handle HTTP client exceptions" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)

    (mockClient.post _).when(s"$pointsUrl/search", *, *, *).throws(new RuntimeException("Network error"))

    val result = store.search(Array(0.1f, 0.2f, 0.3f), topK = 1)
    result.isLeft shouldBe true
    result.left.map { error =>
      error.formatted should include("HTTP POST failed")
      error.formatted should include("Network error")
    }
  }

  "httpPost via count method" should "handle successful response" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)

    val countResponseJson = """{
      "result": {
        "count": 42
      }
    }"""

    (mockClient.post _).when(s"$pointsUrl/count", *, *, *).returns(httpResponse(200, countResponseJson))

    val result = store.count()
    result shouldBe Right(42L)
  }

  it should "handle error responses" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)

    (mockClient.post _).when(s"$pointsUrl/count", *, *, *).returns(httpResponse(503, "Service unavailable"))

    val result = store.count()
    result.isLeft shouldBe true
    result.left.map(error => error.formatted should include("503"))
  }

  "httpPost via getBatch method" should "handle successful response" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)

    val getBatchResponseJson = """{
      "result": [
        {
          "id": "test-1",
          "vector": [0.1, 0.2],
          "payload": {"content": "Content 1"}
        },
        {
          "id": "test-2",
          "vector": [0.3, 0.4],
          "payload": {"content": "Content 2"}
        }
      ]
    }"""

    (mockClient.post _).when(pointsUrl, *, *, *).returns(httpResponse(200, getBatchResponseJson))

    val result = store.getBatch(Seq("test-1", "test-2"))
    result match {
      case Right(records) => records should have size 2
      case Left(err)      => fail(s"Expected Right but got Left: ${err.formatted}")
    }
  }

  // ============================================================
  // httpPut tests (via upsert method)
  // ============================================================

  "httpPut via upsert method" should "handle successful response (200 OK)" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)

    // Mock collection creation PUT
    (mockClient.put _).when(collectionsUrl, *, *, *).returns(httpResponse(200, """{"result": true}"""))
    // Mock successful PUT for upsert
    (mockClient.put _).when(s"$pointsUrl?wait=true", *, *, *).returns(httpResponse(200, """{"result": "ok"}"""))

    val record = VectorRecord("test-1", Array(0.1f, 0.2f, 0.3f), Some("Test content"))
    val result = store.upsert(record)
    result shouldBe Right(())
  }

  it should "handle 201 Created response" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)

    (mockClient.put _).when(collectionsUrl, *, *, *).returns(httpResponse(200, """{"result": true}"""))
    (mockClient.put _).when(s"$pointsUrl?wait=true", *, *, *).returns(httpResponse(201, """{"result": "created"}"""))

    val record = VectorRecord("test-1", Array(0.1f, 0.2f, 0.3f))
    val result = store.upsert(record)
    result shouldBe Right(())
  }

  it should "handle 204 No Content response" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)

    (mockClient.put _).when(collectionsUrl, *, *, *).returns(httpResponse(200, """{"result": true}"""))
    (mockClient.put _).when(s"$pointsUrl?wait=true", *, *, *).returns(httpResponse(204, ""))

    val record = VectorRecord("test-1", Array(0.1f, 0.2f, 0.3f))
    val result = store.upsert(record)
    result shouldBe Right(())
  }

  it should "handle 400 Bad Request error" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)

    (mockClient.put _).when(collectionsUrl, *, *, *).returns(httpResponse(200, """{"result": true}"""))
    (mockClient.put _)
      .when(s"$pointsUrl?wait=true", *, *, *)
      .returns(httpResponse(400, "Bad Request: Invalid vector size"))

    val record = VectorRecord("test-1", Array(0.1f))
    val result = store.upsert(record)
    result.isLeft shouldBe true
    result.left.map { error =>
      error.formatted should include("400")
      error.formatted should include("Bad Request")
    }
  }

  it should "handle 401 Unauthorized error" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)

    (mockClient.put _).when(collectionsUrl, *, *, *).returns(httpResponse(200, """{"result": true}"""))
    (mockClient.put _).when(s"$pointsUrl?wait=true", *, *, *).returns(httpResponse(401, "Unauthorized"))

    val record = VectorRecord("test-1", Array(0.1f, 0.2f, 0.3f))
    val result = store.upsert(record)
    result.isLeft shouldBe true
    result.left.map(error => error.formatted should include("401"))
  }

  it should "handle 500 Internal Server Error" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)

    (mockClient.put _).when(collectionsUrl, *, *, *).returns(httpResponse(200, """{"result": true}"""))
    (mockClient.put _).when(s"$pointsUrl?wait=true", *, *, *).returns(httpResponse(500, "Internal error"))

    val record = VectorRecord("test-1", Array(0.1f, 0.2f, 0.3f))
    val result = store.upsert(record)
    result.isLeft shouldBe true
    result.left.map(error => error.formatted should include("500"))
  }

  it should "handle 503 Service Unavailable error" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)

    (mockClient.put _).when(collectionsUrl, *, *, *).returns(httpResponse(200, """{"result": true}"""))
    (mockClient.put _)
      .when(s"$pointsUrl?wait=true", *, *, *)
      .returns(httpResponse(503, "Service temporarily unavailable"))

    val record = VectorRecord("test-1", Array(0.1f, 0.2f, 0.3f))
    val result = store.upsert(record)
    result.isLeft shouldBe true
    result.left.map(error => error.formatted should include("503"))
  }

  it should "handle HTTP client exceptions" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)

    (mockClient.put _).when(collectionsUrl, *, *, *).returns(httpResponse(200, """{"result": true}"""))
    (mockClient.put _).when(s"$pointsUrl?wait=true", *, *, *).throws(new RuntimeException("Connection refused"))

    val record = VectorRecord("test-1", Array(0.1f, 0.2f, 0.3f))
    val result = store.upsert(record)
    result.isLeft shouldBe true
    result.left.map(error => error.formatted should include("Connection refused"))
  }

  // ============================================================
  // httpDelete tests (via clear method)
  // ============================================================

  "httpDelete via clear method" should "handle successful response (200 OK)" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)

    (mockClient.delete _).when(collectionsUrl, *, *).returns(httpResponse(200, """{"result": true}"""))

    val result = store.clear()
    result shouldBe Right(())
  }

  it should "handle 204 No Content response" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)

    (mockClient.delete _).when(collectionsUrl, *, *).returns(httpResponse(204, ""))

    val result = store.clear()
    result shouldBe Right(())
  }

  it should "handle 400 Bad Request error" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)

    (mockClient.delete _).when(collectionsUrl, *, *).returns(httpResponse(400, "Bad Request"))

    val result = store.clear()
    result.isLeft shouldBe true
    result.left.map(error => error.formatted should include("400"))
  }

  it should "handle 401 Unauthorized error" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)

    (mockClient.delete _).when(collectionsUrl, *, *).returns(httpResponse(401, "Unauthorized"))

    val result = store.clear()
    result.isLeft shouldBe true
    result.left.map(error => error.formatted should include("401"))
  }

  it should "handle 403 Forbidden error" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)

    (mockClient.delete _).when(collectionsUrl, *, *).returns(httpResponse(403, "Forbidden"))

    val result = store.clear()
    result.isLeft shouldBe true
    result.left.map(error => error.formatted should include("403"))
  }

  it should "handle 404 Not Found error" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)

    (mockClient.delete _).when(collectionsUrl, *, *).returns(httpResponse(404, "Collection not found"))

    val result = store.clear()
    result.isLeft shouldBe true
    result.left.map(error => error.formatted should include("404"))
  }

  it should "handle 500 Internal Server Error" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)

    (mockClient.delete _).when(collectionsUrl, *, *).returns(httpResponse(500, "Internal error"))

    val result = store.clear()
    result.isLeft shouldBe true
    result.left.map(error => error.formatted should include("500"))
  }

  it should "handle 503 Service Unavailable error" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)

    (mockClient.delete _).when(collectionsUrl, *, *).returns(httpResponse(503, "Service unavailable"))

    val result = store.clear()
    result.isLeft shouldBe true
    result.left.map(error => error.formatted should include("503"))
  }

  it should "handle HTTP client exceptions" in {
    val mockClient = createMockClient()

    // Mock the initial GET in constructor
    (mockClient.get _).when(collectionsUrl, *, *, *).returns(httpResponse(404, "Not found"))

    // Create a fresh mock for delete that will throw
    val throwingClient = stub[Llm4sHttpClient]
    (throwingClient.get _).when(collectionsUrl, *, *, *).returns(httpResponse(404, "Not found"))
    (throwingClient.delete _).when(collectionsUrl, *, *).throws(new RuntimeException("Network timeout"))

    val store = QdrantVectorStore(testConfig, throwingClient) match {
      case Right(s)  => s
      case Left(err) => fail(s"Failed to create store: ${err.formatted}")
    }

    val result = store.clear()
    result.isLeft shouldBe true
    result.left.map { error =>
      error.formatted should include("HTTP DELETE failed")
      error.formatted should include("Network timeout")
    }
  }

  // ============================================================
  // handleResponse edge cases
  // ============================================================

  "handleResponse" should "handle 2xx range properly" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)

    // Test 202 Accepted via upsert (which uses httpPut that checks 200-299 range)
    (mockClient.put _).when(collectionsUrl, *, *, *).returns(httpResponse(200, """{"result": true}"""))
    (mockClient.put _).when(s"$pointsUrl?wait=true", *, *, *).returns(httpResponse(202, """{"result": "accepted"}"""))

    val record = VectorRecord("test-1", Array(0.1f, 0.2f, 0.3f))
    val result = store.upsert(record)
    result shouldBe Right(())
  }

  it should "handle various 4xx errors" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)

    // Test 405 Method Not Allowed
    (mockClient.get _)
      .when(testPointUrl, *, *, *)
      .returns(httpResponse(405, "Method Not Allowed"))

    val result = store.get("test-1")
    result.isLeft shouldBe true
    result.left.map(error => error.formatted should include("405"))
  }

  it should "handle JSON parse errors gracefully" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)

    // Return invalid JSON
    (mockClient.get _)
      .when(testPointUrl, *, *, *)
      .returns(httpResponse(200, "not valid json"))

    val result = store.get("test-1")
    result.isLeft shouldBe true
    // Should fail during JSON parsing in handleResponse
  }
}
