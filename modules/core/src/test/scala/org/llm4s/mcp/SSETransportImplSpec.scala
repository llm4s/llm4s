package org.llm4s.mcp

import org.llm4s.http.{ HttpResponse, Llm4sHttpClient }
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import upickle.default._

import scala.concurrent.duration._

/**
 * Comprehensive unit tests for SSETransportImpl.
 *
 * Tests the MCP 2024-11-05 SSE transport protocol implementation including:
 * - Request/response handling
 * - SSE stream parsing
 * - Session management
 * - Error handling
 * - Notification sending
 */
class SSETransportImplSpec extends AnyWordSpec with Matchers with MockFactory {

  // Test data
  val testUrl     = "http://localhost:8080/mcp"
  val testName    = "test-transport"
  val testTimeout = 5.seconds

  // Helper to create HTTP responses
  def httpResponse(
    statusCode: Int,
    body: String,
    headers: Map[String, Seq[String]] = Map.empty
  ): HttpResponse =
    HttpResponse(statusCode = statusCode, body = body, headers = headers)

  // Helper to create JSON-RPC request
  def createRequest(method: String, id: String): JsonRpcRequest =
    JsonRpcRequest(
      jsonrpc = "2.0",
      method = method,
      id = id,
      params = None
    )

  // Helper to create JSON-RPC response
  def createResponse(id: String, result: Option[ujson.Value] = None): String =
    write(
      JsonRpcResponse(
        jsonrpc = "2.0",
        id = id,
        result = result,
        error = None
      )
    )

  // Helper to create JSON-RPC error response
  def createErrorResponse(id: String, code: Int, message: String): String =
    write(
      JsonRpcResponse(
        jsonrpc = "2.0",
        id = id,
        result = None,
        error = Some(JsonRpcError(code = code, message = message, data = None))
      )
    )

  // Helper to create SSE formatted response
  def createSSEResponse(data: String, eventType: Option[String] = None, id: Option[String] = None): String = {
    val lines = scala.collection.mutable.ListBuffer[String]()
    eventType.foreach(e => lines += s"event: $e")
    id.foreach(i => lines += s"id: $i")
    lines += s"data: $data"
    lines += "" // Empty line terminates event
    lines.mkString("\n")
  }

  "SSETransportImpl initialization" should {

    "create transport with correct configuration" in {
      val mockHttp  = stub[Llm4sHttpClient]
      val transport = new SSETransportImpl(testUrl, testName, testTimeout, mockHttp)

      transport.name shouldBe testName
    }
  }

  "SSETransportImpl.sendRequest" should {

    "send POST request with correct base headers for initialize" in {
      val mockHttp  = stub[Llm4sHttpClient]
      val transport = new SSETransportImpl(testUrl, testName, testTimeout, mockHttp)

      val request      = createRequest("initialize", "1")
      val responseJson = createResponse("1", Some(ujson.Obj("protocolVersion" -> "2024-11-05")))

      (mockHttp.post _)
        .when(
          testUrl,
          Map(
            "Content-Type" -> "application/json",
            "Accept"       -> "application/json, text/event-stream"
          ),
          *,
          testTimeout.toMillis.toInt
        )
        .returns(httpResponse(200, responseJson))

      val result = transport.sendRequest(request)
      result.isRight shouldBe true
    }

    "NOT include MCP-Protocol-Version header on initialize request" in {
      val mockHttp  = stub[Llm4sHttpClient]
      val transport = new SSETransportImpl(testUrl, testName, testTimeout, mockHttp)

      val request      = createRequest("initialize", "1")
      val responseJson = createResponse("1")

      // Stub to capture headers
      var capturedHeaders: Map[String, String] = Map.empty
      (mockHttp.post _)
        .when(*, *, *, *)
        .onCall { (_: String, headers: Map[String, String], _: String, _: Int) =>
          capturedHeaders = headers
          httpResponse(200, responseJson)
        }

      transport.sendRequest(request)

      // Verify no protocol version header on initialize
      capturedHeaders should not contain key("MCP-Protocol-Version")
    }

    "include MCP-Protocol-Version header on non-initialize requests" in {
      val mockHttp  = stub[Llm4sHttpClient]
      val transport = new SSETransportImpl(testUrl, testName, testTimeout, mockHttp)

      // Capture headers from both calls
      var callCount                              = 0
      var secondCallHeaders: Map[String, String] = Map.empty
      val initRequest                            = createRequest("initialize", "1")
      val initResponseJson                       = createResponse("1")
      val request                                = createRequest("tools/list", "2")
      val responseJson                           = createResponse("2")

      (mockHttp.post _)
        .when(*, *, *, *)
        .onCall { (_: String, headers: Map[String, String], _: String, _: Int) =>
          callCount += 1
          if (callCount == 1) {
            // First call: initialize
            httpResponse(200, initResponseJson)
          } else {
            // Second call: capture headers
            secondCallHeaders = headers
            httpResponse(200, responseJson)
          }
        }

      // First initialize
      transport.sendRequest(initRequest)

      // Then send regular request
      val result = transport.sendRequest(request)
      result.isRight shouldBe true

      // Verify protocol version header is present in second call
      (secondCallHeaders should contain).key("MCP-Protocol-Version")
      secondCallHeaders("MCP-Protocol-Version") shouldBe "2024-11-05"
    }

    "handle successful JSON response" in {
      val mockHttp  = stub[Llm4sHttpClient]
      val transport = new SSETransportImpl(testUrl, testName, testTimeout, mockHttp)

      val request      = createRequest("initialize", "1")
      val responseJson = createResponse("1", Some(ujson.Obj("serverInfo" -> ujson.Obj("name" -> "test"))))

      (mockHttp.post _).when(*, *, *, *).returns(httpResponse(200, responseJson))

      val result = transport.sendRequest(request)
      result.isRight shouldBe true
      result.map(_.id) shouldBe Right("1")
    }

    "handle successful SSE stream response" in {
      val mockHttp  = stub[Llm4sHttpClient]
      val transport = new SSETransportImpl(testUrl, testName, testTimeout, mockHttp)

      val request        = createRequest("initialize", "1")
      val responseJson   = createResponse("1", Some(ujson.Obj("serverInfo" -> ujson.Obj("name" -> "test"))))
      val sseResponse    = createSSEResponse(responseJson)
      val contentTypeSSE = Map("content-type" -> Seq("text/event-stream; charset=utf-8"))

      (mockHttp.post _).when(*, *, *, *).returns(httpResponse(200, sseResponse, contentTypeSSE))

      val result = transport.sendRequest(request)
      result.isRight shouldBe true
      result.map(_.id) shouldBe Right("1")
    }

    "parse SSE events with event type and id fields" in {
      val mockHttp  = stub[Llm4sHttpClient]
      val transport = new SSETransportImpl(testUrl, testName, testTimeout, mockHttp)

      val request      = createRequest("initialize", "1")
      val responseJson = createResponse("1")
      val sseResponse  = createSSEResponse(responseJson, eventType = Some("message"), id = Some("event-1"))
      val contentType  = Map("content-type" -> Seq("text/event-stream"))

      (mockHttp.post _).when(*, *, *, *).returns(httpResponse(200, sseResponse, contentType))

      val result = transport.sendRequest(request)
      result.isRight shouldBe true
    }

    "extract session ID from initialize response headers" in {
      val mockHttp  = stub[Llm4sHttpClient]
      val transport = new SSETransportImpl(testUrl, testName, testTimeout, mockHttp)

      val request       = createRequest("initialize", "1")
      val responseJson  = createResponse("1")
      val request2      = createRequest("tools/list", "2")
      val responseJson2 = createResponse("2")

      // Capture headers from both calls
      var callCount                              = 0
      var secondCallHeaders: Map[String, String] = Map.empty

      (mockHttp.post _)
        .when(*, *, *, *)
        .onCall { (_: String, headers: Map[String, String], _: String, _: Int) =>
          callCount += 1
          if (callCount == 1) {
            // First call: initialize with session
            httpResponse(200, responseJson, Map("mcp-session-id" -> Seq("session-123")))
          } else {
            // Second call: capture headers
            secondCallHeaders = headers
            httpResponse(200, responseJson2)
          }
        }

      transport.sendRequest(request)

      val result = transport.sendRequest(request2)
      result.isRight shouldBe true

      // Verify session header is present in second request
      (secondCallHeaders should contain).key("mcp-session-id")
      secondCallHeaders("mcp-session-id") shouldBe "session-123"
    }

    "handle server without session management" in {
      val mockHttp  = stub[Llm4sHttpClient]
      val transport = new SSETransportImpl(testUrl, testName, testTimeout, mockHttp)

      val request      = createRequest("initialize", "1")
      val responseJson = createResponse("1")
      val headers      = Map.empty[String, Seq[String]] // No session header

      (mockHttp.post _).when(*, *, *, *).returns(httpResponse(200, responseJson, headers))

      val result = transport.sendRequest(request)
      result.isRight shouldBe true

      // Next request should not include session header
      var capturedHeaders: Map[String, String] = Map.empty
      val request2                             = createRequest("tools/list", "2")
      val responseJson2                        = createResponse("2")

      (mockHttp.post _)
        .when(*, *, *, *)
        .onCall { (_: String, headers: Map[String, String], _: String, _: Int) =>
          capturedHeaders = headers
          httpResponse(200, responseJson2)
        }

      transport.sendRequest(request2)

      // Verify no session header
      capturedHeaders should not contain key("mcp-session-id")
    }

    "handle multiple SSE events and extract JSON-RPC response" in {
      val mockHttp  = stub[Llm4sHttpClient]
      val transport = new SSETransportImpl(testUrl, testName, testTimeout, mockHttp)

      val request      = createRequest("initialize", "1")
      val responseJson = createResponse("1")

      // Multiple SSE events, second one has the JSON-RPC response
      val sseResponse = Seq(
        "event: info",
        "data: Server starting",
        "",
        "event: message",
        s"data: $responseJson",
        ""
      ).mkString("\n")

      val contentType = Map("content-type" -> Seq("text/event-stream"))

      (mockHttp.post _).when(*, *, *, *).returns(httpResponse(200, sseResponse, contentType))

      val result = transport.sendRequest(request)
      result.isRight shouldBe true
      result.map(_.id) shouldBe Right("1")
    }
  }

  "SSETransportImpl error handling" should {

    "handle HTTP 404 with active session as session expiration" in {
      val mockHttp  = stub[Llm4sHttpClient]
      val transport = new SSETransportImpl(testUrl, testName, testTimeout, mockHttp)

      val initRequest      = createRequest("initialize", "1")
      val initResponseJson = createResponse("1")
      val request          = createRequest("tools/list", "2")

      // Handle both calls
      var callCount = 0
      (mockHttp.post _)
        .when(*, *, *, *)
        .onCall { (_: String, _: Map[String, String], _: String, _: Int) =>
          callCount += 1
          if (callCount == 1) {
            // First call: initialize with session
            httpResponse(200, initResponseJson, Map("mcp-session-id" -> Seq("session-123")))
          } else {
            // Second call: 404
            httpResponse(404, "Not Found")
          }
        }

      transport.sendRequest(initRequest)

      val result = transport.sendRequest(request)
      result.isLeft shouldBe true
      result.left.map(_ should include("session expired"))
    }

    "handle HTTP 400+ errors with body in error message" in {
      val mockHttp  = stub[Llm4sHttpClient]
      val transport = new SSETransportImpl(testUrl, testName, testTimeout, mockHttp)

      val request = createRequest("initialize", "1")
      (mockHttp.post _).when(*, *, *, *).returns(httpResponse(500, "Internal Server Error: Database down"))

      val result = transport.sendRequest(request)
      result.isLeft shouldBe true
      result.left.map(_ should include("500"))
    }

    "handle JSON-RPC error responses" in {
      val mockHttp  = stub[Llm4sHttpClient]
      val transport = new SSETransportImpl(testUrl, testName, testTimeout, mockHttp)

      val request       = createRequest("initialize", "1")
      val errorResponse = createErrorResponse("1", -32600, "Invalid Request")

      (mockHttp.post _).when(*, *, *, *).returns(httpResponse(200, errorResponse))

      val result = transport.sendRequest(request)
      result.isLeft shouldBe true
      result.left.map(_ should include("-32600"))
      result.left.map(_ should include("Invalid Request"))
    }

    "handle malformed JSON in response" in {
      val mockHttp  = stub[Llm4sHttpClient]
      val transport = new SSETransportImpl(testUrl, testName, testTimeout, mockHttp)

      val request = createRequest("initialize", "1")
      (mockHttp.post _).when(*, *, *, *).returns(httpResponse(200, "{invalid-json"))

      val result = transport.sendRequest(request)
      result.isLeft shouldBe true
      result.left.map(_ should include("Transport error"))
    }

    "handle network exceptions" in {
      val mockHttp  = stub[Llm4sHttpClient]
      val transport = new SSETransportImpl(testUrl, testName, testTimeout, mockHttp)

      val request = createRequest("initialize", "1")
      (mockHttp.post _).when(*, *, *, *).throws(new java.net.SocketTimeoutException("Connection timed out"))

      val result = transport.sendRequest(request)
      result.isLeft shouldBe true
      result.left.map(_ should include("Transport error"))
    }

    "handle connection failures" in {
      val mockHttp  = stub[Llm4sHttpClient]
      val transport = new SSETransportImpl(testUrl, testName, testTimeout, mockHttp)

      val request = createRequest("initialize", "1")
      (mockHttp.post _).when(*, *, *, *).throws(new java.net.ConnectException("Connection refused"))

      val result = transport.sendRequest(request)
      result.isLeft shouldBe true
      result.left.map(_ should include("Transport error"))
    }

    "handle empty SSE stream with no valid JSON-RPC response" in {
      val mockHttp  = stub[Llm4sHttpClient]
      val transport = new SSETransportImpl(testUrl, testName, testTimeout, mockHttp)

      val request = createRequest("initialize", "1")

      // SSE stream with only comments and no JSON-RPC data
      val sseResponse = Seq(
        ": This is a comment",
        "event: info",
        "data: Server ready",
        ""
      ).mkString("\n")

      val contentType = Map("content-type" -> Seq("text/event-stream"))

      (mockHttp.post _).when(*, *, *, *).returns(httpResponse(200, sseResponse, contentType))

      val result = transport.sendRequest(request)
      result.isLeft shouldBe true
      result.left.map(_ should include("No valid JSON-RPC response"))
    }
  }

  "SSETransportImpl SSE parsing" should {

    "handle SSE with valid JSON-RPC response on single data line" in {
      val mockHttp  = stub[Llm4sHttpClient]
      val transport = new SSETransportImpl(testUrl, testName, testTimeout, mockHttp)

      val request      = createRequest("initialize", "1")
      val responseJson = createResponse("1", Some(ujson.Obj("result" -> "ok")))

      // SSE response with complete JSON on single data line
      val sseResponse = Seq(
        s"data: $responseJson",
        ""
      ).mkString("\n")

      val contentType = Map("content-type" -> Seq("text/event-stream"))

      (mockHttp.post _).when(*, *, *, *).returns(httpResponse(200, sseResponse, contentType))

      val result = transport.sendRequest(request)
      result.isRight shouldBe true
    }

    "handle SSE comments (lines starting with colon)" in {
      val mockHttp  = stub[Llm4sHttpClient]
      val transport = new SSETransportImpl(testUrl, testName, testTimeout, mockHttp)

      val request      = createRequest("initialize", "1")
      val responseJson = createResponse("1")

      val sseResponse = Seq(
        ": This is a comment",
        ": Another comment",
        s"data: $responseJson",
        ""
      ).mkString("\n")

      val contentType = Map("content-type" -> Seq("text/event-stream"))

      (mockHttp.post _).when(*, *, *, *).returns(httpResponse(200, sseResponse, contentType))

      val result = transport.sendRequest(request)
      result.isRight shouldBe true
    }

    "handle [DONE] sentinel in SSE data" in {
      val mockHttp  = stub[Llm4sHttpClient]
      val transport = new SSETransportImpl(testUrl, testName, testTimeout, mockHttp)

      val request      = createRequest("initialize", "1")
      val responseJson = createResponse("1")

      val sseResponse = Seq(
        s"data: $responseJson",
        "",
        "data: [DONE]",
        ""
      ).mkString("\n")

      val contentType = Map("content-type" -> Seq("text/event-stream"))

      (mockHttp.post _).when(*, *, *, *).returns(httpResponse(200, sseResponse, contentType))

      val result = transport.sendRequest(request)
      result.isRight shouldBe true
    }

    "skip non-JSON-RPC data in SSE stream" in {
      val mockHttp  = stub[Llm4sHttpClient]
      val transport = new SSETransportImpl(testUrl, testName, testTimeout, mockHttp)

      val request      = createRequest("initialize", "1")
      val responseJson = createResponse("1")

      val sseResponse = Seq(
        "data: Plain text message",
        "",
        "data: {\"not\": \"json-rpc\"}",
        "",
        s"data: $responseJson",
        ""
      ).mkString("\n")

      val contentType = Map("content-type" -> Seq("text/event-stream"))

      (mockHttp.post _).when(*, *, *, *).returns(httpResponse(200, sseResponse, contentType))

      val result = transport.sendRequest(request)
      result.isRight shouldBe true
      result.map(_.id) shouldBe Right("1")
    }
  }

  "SSETransportImpl.sendNotification" should {

    "send notification with Content-Type header" in {
      val mockHttp  = stub[Llm4sHttpClient]
      val transport = new SSETransportImpl(testUrl, testName, testTimeout, mockHttp)

      var capturedHeaders: Map[String, String] = Map.empty
      val notification = JsonRpcNotification(
        jsonrpc = "2.0",
        method = "notifications/initialized",
        params = None
      )

      (mockHttp.post _)
        .when(*, *, *, *)
        .onCall { (_: String, headers: Map[String, String], _: String, _: Int) =>
          capturedHeaders = headers
          httpResponse(200, "")
        }

      val result = transport.sendNotification(notification)
      result.isRight shouldBe true

      // Verify Content-Type header
      (capturedHeaders should contain).key("Content-Type")
      capturedHeaders("Content-Type") shouldBe "application/json"
    }

    "include MCP-Protocol-Version in notification headers" in {
      val mockHttp  = stub[Llm4sHttpClient]
      val transport = new SSETransportImpl(testUrl, testName, testTimeout, mockHttp)

      var capturedHeaders: Map[String, String] = Map.empty
      val notification = JsonRpcNotification(
        jsonrpc = "2.0",
        method = "notifications/initialized",
        params = None
      )

      (mockHttp.post _)
        .when(*, *, *, *)
        .onCall { (_: String, headers: Map[String, String], _: String, _: Int) =>
          capturedHeaders = headers
          httpResponse(200, "")
        }

      val result = transport.sendNotification(notification)
      result.isRight shouldBe true

      // Verify protocol version header
      (capturedHeaders should contain).key("MCP-Protocol-Version")
      capturedHeaders("MCP-Protocol-Version") shouldBe "2024-11-05"
    }

    "include session ID in notification if present" in {
      val mockHttp  = stub[Llm4sHttpClient]
      val transport = new SSETransportImpl(testUrl, testName, testTimeout, mockHttp)

      val initRequest      = createRequest("initialize", "1")
      val initResponseJson = createResponse("1")
      val notification = JsonRpcNotification(
        jsonrpc = "2.0",
        method = "notifications/initialized",
        params = None
      )

      // Handle both request and notification calls
      var callCount                                = 0
      var notificationHeaders: Map[String, String] = Map.empty
      (mockHttp.post _)
        .when(*, *, *, *)
        .onCall { (_: String, headers: Map[String, String], _: String, _: Int) =>
          callCount += 1
          if (callCount == 1) {
            // First call: initialize with session
            httpResponse(200, initResponseJson, Map("mcp-session-id" -> Seq("session-123")))
          } else {
            // Second call: notification - capture headers
            notificationHeaders = headers
            httpResponse(200, "")
          }
        }

      transport.sendRequest(initRequest)

      val result = transport.sendNotification(notification)
      result.isRight shouldBe true

      // Verify session header in notification
      (notificationHeaders should contain).key("mcp-session-id")
      notificationHeaders("mcp-session-id") shouldBe "session-123"
    }

    "handle notification errors" in {
      val mockHttp  = stub[Llm4sHttpClient]
      val transport = new SSETransportImpl(testUrl, testName, testTimeout, mockHttp)

      val notification = JsonRpcNotification(
        jsonrpc = "2.0",
        method = "notifications/initialized",
        params = None
      )

      (mockHttp.post _).when(*, *, *, *).returns(httpResponse(500, "Server Error"))

      val result = transport.sendNotification(notification)
      result.isLeft shouldBe true
      result.left.map(_ should include("500"))
    }
  }

  "SSETransportImpl.close" should {

    "send DELETE request if session exists" in {
      val mockHttp  = stub[Llm4sHttpClient]
      val transport = new SSETransportImpl(testUrl, testName, testTimeout, mockHttp)

      // Initialize with session
      val initRequest      = createRequest("initialize", "1")
      val initResponseJson = createResponse("1")
      val headers          = Map("mcp-session-id" -> Seq("session-123"))
      (mockHttp.post _).when(*, *, *, *).returns(httpResponse(200, initResponseJson, headers))
      transport.sendRequest(initRequest)

      // Expect DELETE on close - capture headers
      var capturedHeaders: Map[String, String] = Map.empty
      (mockHttp.delete _)
        .when(*, *, *)
        .onCall { (_: String, headers: Map[String, String], _: Int) =>
          capturedHeaders = headers
          httpResponse(200, "")
        }

      transport.close()

      // Verify session header in DELETE request
      (capturedHeaders should contain).key("mcp-session-id")
      capturedHeaders("mcp-session-id") shouldBe "session-123"
    }

    "clear session after close" in {
      val mockHttp  = stub[Llm4sHttpClient]
      val transport = new SSETransportImpl(testUrl, testName, testTimeout, mockHttp)

      // Initialize with session
      val initRequest      = createRequest("initialize", "1")
      val initResponseJson = createResponse("1")
      val headers          = Map("mcp-session-id" -> Seq("session-123"))
      (mockHttp.post _).when(*, *, *, *).returns(httpResponse(200, initResponseJson, headers))
      transport.sendRequest(initRequest)

      (mockHttp.delete _).when(*, *, *).returns(httpResponse(200, ""))

      transport.close()

      // After close, new requests should not include session
      var capturedHeaders: Map[String, String] = Map.empty
      val request2                             = createRequest("initialize", "2")
      val responseJson2                        = createResponse("2")

      (mockHttp.post _)
        .when(*, *, *, *)
        .onCall { (_: String, headers: Map[String, String], _: String, _: Int) =>
          capturedHeaders = headers
          httpResponse(200, responseJson2)
        }

      transport.sendRequest(request2)

      // Verify no session header after close
      capturedHeaders should not contain key("mcp-session-id")
    }

    "handle 405 error during session termination gracefully" in {
      val mockHttp  = stub[Llm4sHttpClient]
      val transport = new SSETransportImpl(testUrl, testName, testTimeout, mockHttp)

      // Initialize with session
      val initRequest      = createRequest("initialize", "1")
      val initResponseJson = createResponse("1")
      val headers          = Map("mcp-session-id" -> Seq("session-123"))
      (mockHttp.post _).when(*, *, *, *).returns(httpResponse(200, initResponseJson, headers))
      transport.sendRequest(initRequest)

      // DELETE fails with 405
      (mockHttp.delete _).when(*, *, *).throws(new RuntimeException("405 Method Not Allowed"))

      // Should not throw
      noException should be thrownBy transport.close()
    }

    "allow close without session" in {
      val mockHttp  = stub[Llm4sHttpClient]
      val transport = new SSETransportImpl(testUrl, testName, testTimeout, mockHttp)

      // Should not throw
      noException should be thrownBy transport.close()
    }
  }

  "SSETransportImpl.generateId" should {

    "generate unique sequential IDs" in {
      val mockHttp  = stub[Llm4sHttpClient]
      val transport = new SSETransportImpl(testUrl, testName, testTimeout, mockHttp)

      val id1 = transport.generateId()
      val id2 = transport.generateId()
      val id3 = transport.generateId()

      id1 shouldBe "1"
      id2 shouldBe "2"
      id3 shouldBe "3"
    }
  }
}
