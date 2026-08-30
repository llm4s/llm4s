package org.llm4s.mcp

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll
import java.net.http.{ HttpClient, HttpRequest, HttpResponse }
import java.net.URI

class NotificationSpec extends AnyFunSpec with Matchers with BeforeAndAfterAll {

  val options = MCPServerOptions(
    port = 0, // Random port
    path = "/mcp",
    name = "TestServer",
    version = "1.0"
  )

  var server: MCPServer = _
  var port: Int         = _
  var baseUrl: String   = _
  val client            = HttpClient.newHttpClient()

  override def beforeAll(): Unit = {
    server = new MCPServer(options, Seq.empty)
    server.start().fold(e => throw e, _ => ())
    port = server.getPort
    baseUrl = s"http://127.0.0.1:$port/mcp"
  }

  override def afterAll(): Unit =
    if (server != null) {
      server.stop()
    }

  describe("MCPServer Notification Support") {
    it("should handle standard requests (with id) normally") {
      val requestBody = ujson
        .Obj(
          "jsonrpc" -> "2.0",
          "id"      -> "123",
          "method" -> "ping" // Assume ping is treated as method not found if not implemented, but valid request structure
        )
        .toString

      val request = HttpRequest
        .newBuilder()
        .uri(URI.create(baseUrl))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
        .build()

      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      response.statusCode() shouldBe 200

      val responseJson = ujson.read(response.body())
      responseJson.obj.contains("id") shouldBe true
      responseJson("id").str shouldBe "123"
      // Likely Method Not Found error since we didn't add ping tool and it's not a built-in method like initialize
      // But checking we got a valid JSON-RPC response is enough
    }

    it("should handle notifications (without id) and return 200 OK with no body") {
      val notificationBody = ujson
        .Obj(
          "jsonrpc" -> "2.0",
          "method"  -> "notifications/initialized"
        )
        .toString

      val request = HttpRequest
        .newBuilder()
        .uri(URI.create(baseUrl))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(notificationBody))
        .build()

      val response = client.send(request, HttpResponse.BodyHandlers.ofString())

      // We expect 200 OK
      response.statusCode() shouldBe 200

      // HttpExchange.sendResponseHeaders(200, -1) means "no content length" / "no content"?
      // Actually -1 means no content body will be sent.
      // So body should be empty.
      response.body() shouldBe ""
    }

    it("should handle unknown notifications gracefully") {
      val notificationBody = ujson
        .Obj(
          "jsonrpc" -> "2.0",
          "method"  -> "some/random/notification"
        )
        .toString

      val request = HttpRequest
        .newBuilder()
        .uri(URI.create(baseUrl))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(notificationBody))
        .build()

      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      response.statusCode() shouldBe 200
      response.body() shouldBe ""
    }
  }
}
