package org.llm4s.mcp

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll
import java.net.HttpURLConnection

class PayloadLimitSpec extends AnyFunSpec with Matchers with BeforeAndAfterAll {

  var server: MCPServer = _
  var port: Int         = _

  override def beforeAll(): Unit = {
    val options = MCPServerOptions(0, "/mcp", "TestServer", "1.0")
    server = new MCPServer(options, Seq.empty)
    server.start().fold(e => throw e, _ => ())
    port = server.boundPort
  }

  override def afterAll(): Unit =
    if (server != null) server.stop()

  describe("MCPServer Payload Limit") {
    it("should reject payloads exceeding 10MB") {
      val url        = java.net.URI.create(s"http://127.0.0.1:$port/mcp").toURL()
      val connection = url.openConnection().asInstanceOf[HttpURLConnection]
      connection.setRequestMethod("POST")
      connection.setDoOutput(true)
      // Use Expect: 100-continue to allow server to reject based on Content-Length before we send data
      connection.setRequestProperty("Expect", "100-continue")

      val largeBodySize = 10 * 1024 * 1024 + 1024
      connection.setChunkedStreamingMode(4096)

      val sendResult = scala.util.Try {
        val os      = connection.getOutputStream
        val buffer  = new Array[Byte](4096)
        var written = 0
        while (written < largeBodySize) {
          os.write(buffer)
          written += buffer.length
        }
        os.close()
        connection.getResponseCode
      }

      sendResult match {
        case scala.util.Success(responseCode) =>
          responseCode should be(413)
        case scala.util.Failure(_) =>
          // Server closed connection during write — verify it was a 413 rejection
          val verifyResult = scala.util.Try(connection.getResponseCode)
          verifyResult match {
            case scala.util.Success(code) => code should be(413)
            case scala.util.Failure(e)    => fail("Failed to verify 413 response: " + e.getMessage)
          }
      }
    }
  }
}
