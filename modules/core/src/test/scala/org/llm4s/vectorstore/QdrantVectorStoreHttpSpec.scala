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
      .when(s"$pointsUrl/test-1?with_payload=true&with_vector=true", *, *, *)
      .returns(httpResponse(200, responseJson))

    val result = store.get("test-1")
    result.isRight shouldBe true
    result.toOption.flatten.map(_.id) shouldBe Some("test-1")
  }

  it should "handle 404 Not Found error" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)

    (mockClient.get _)
      .when(s"$pointsUrl/test-1?with_payload=true&with_vector=true", *, *, *)
      .returns(httpResponse(404, "Not found"))

    val result = store.get("test-1")
    result.isLeft shouldBe true
    result.left.map(error => error.formatted should include("Not found"))
  }

  it should "handle 500 Internal Server Error" in {
    val mockClient = createMockClient()
    val store      = createStore(mockClient)

    (mockClient.get _)
      .when(s"$pointsUrl/test-1?with_payload=true&with_vector=true", *, *, *)
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
      .when(s"$pointsUrl/test-1?with_payload=true&with_vector=true", *, *, *)
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
      .when(s"$pointsUrl/test-1?with_payload=true&with_vector=true", *, *, *)
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
      .when(s"$pointsUrl/test-1?with_payload=true&with_vector=true", *, *, *)
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
      .when(s"$pointsUrl/test-1?with_payload=true&with_vector=true", *, *, *)
      .returns(httpResponse(200, "not valid json"))

    val result = store.get("test-1")
    result.isLeft shouldBe true
    // Should fail during JSON parsing in handleResponse
  }
}
