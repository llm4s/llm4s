package org.llm4s.mcp

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{ CountDownLatch, Executors, TimeUnit }

/**
 * Tests for StdioTransportImpl concurrent request handling.
 * Verifies that the fix for issue #326 (race condition) works correctly.
 *
 * The race condition occurred when multiple threads sent requests concurrently,
 * and responses could be mismatched because there was no synchronization between
 * request IDs and responses.
 *
 * These tests use a real subprocess (bash script) that echoes JSON-RPC responses.
 */
class StdioTransportConcurrencySpec extends AnyFlatSpec with Matchers {

  // Skip tests on Windows since they require bash
  private val isWindows: Boolean = System.getProperty("os.name").toLowerCase.contains("win")

  /**
   * Test that verifies concurrent requests get correctly matched responses.
   * This is the key test for issue #326.
   */
  "StdioTransportImpl" should "correctly route responses to concurrent requests" in {
    assume(!isWindows, "Bash not available on Windows")
    // Use cat with a simple transformation - this is a minimal echo server
    // The script reads JSON, extracts id, and returns a response
    val scriptCommand = Seq(
      "bash",
      "-c",
      """while IFS= read -r line; do
           id=$(echo "$line" | grep -o '"id":"[^"]*"' | cut -d'"' -f4)
           if [ -n "$id" ]; then
             echo "{\"jsonrpc\":\"2.0\",\"id\":\"$id\",\"result\":{\"echo\":\"response-$id\"}}"
           fi
         done"""
    )

    val transport = new StdioTransportImpl(scriptCommand, startupTimeoutMs = 500, name = "echo-server")

    // Warm up: send a single request to ensure the process is started before concurrent access
    // This prevents the race condition where multiple threads try to start the process simultaneously
    val warmupRequest = JsonRpcRequest("2.0", "warmup", "test/warmup", None)
    val warmupResult  = transport.sendRequest(warmupRequest)
    warmupResult.isRight shouldBe true

    val executor      = Executors.newFixedThreadPool(5)
    val numRequests   = 10
    val startLatch    = new CountDownLatch(1)
    val completeLatch = new CountDownLatch(numRequests)
    val results       = new java.util.concurrent.ConcurrentHashMap[String, Either[String, JsonRpcResponse]]()

    // Launch concurrent request threads
    (1 to numRequests).foreach { i =>
      val requestId = s"concurrent-$i"
      executor.submit(new Runnable {
        override def run(): Unit = {
          startLatch.await() // Wait for all threads to be ready
          val request = JsonRpcRequest(
            jsonrpc = "2.0",
            id = requestId,
            method = "test/method",
            params = Some(ujson.Obj("value" -> i))
          )
          val result = transport.sendRequest(request)
          results.put(requestId, result)
          completeLatch.countDown()
        }
      })
    }

    // Release all request threads simultaneously to maximize concurrency
    startLatch.countDown()

    // Wait for all requests to complete
    val completed = completeLatch.await(60, TimeUnit.SECONDS)
    completed shouldBe true

    // Verify each request got its correctly matched response
    (1 to numRequests).foreach { i =>
      val requestId = s"concurrent-$i"
      val result    = results.get(requestId)

      withClue(s"Request $requestId should have a result: ") {
        result should not be null
      }

      withClue(s"Request $requestId should succeed: ${result.left.getOrElse("")}") {
        result.isRight shouldBe true
      }

      val response = result.toOption.get

      withClue(s"Response ID should match request ID $requestId: ") {
        response.id shouldBe requestId
      }

      // Verify the response contains the correct echo value
      val echoValue = response.result.get("echo").str
      withClue(s"Echo value should contain the request ID: ") {
        echoValue should include(requestId)
      }
    }

    // Cleanup
    transport.close()
    executor.shutdown()
  }

  it should "handle rapid sequential requests correctly" in {
    assume(!isWindows, "Bash not available on Windows")
    val scriptCommand = Seq(
      "bash",
      "-c",
      """while IFS= read -r line; do
           id=$(echo "$line" | grep -o '"id":"[^"]*"' | cut -d'"' -f4)
           if [ -n "$id" ]; then
             echo "{\"jsonrpc\":\"2.0\",\"id\":\"$id\",\"result\":{\"seq\":\"$id\"}}"
           fi
         done"""
    )

    val transport   = new StdioTransportImpl(scriptCommand, startupTimeoutMs = 500, name = "sequential-server")
    val numRequests = 20

    // Send requests sequentially but rapidly
    val results = (1 to numRequests).map { i =>
      val requestId = s"seq-$i"
      val request = JsonRpcRequest(
        jsonrpc = "2.0",
        id = requestId,
        method = "test/method",
        params = Some(ujson.Obj("index" -> i))
      )
      (requestId, transport.sendRequest(request))
    }

    // Verify all responses matched correctly
    results.foreach { case (requestId, result) =>
      withClue(s"Request $requestId should succeed: ") {
        result.isRight shouldBe true
      }

      val response = result.toOption.get
      response.id shouldBe requestId

      val seqValue = response.result.get("seq").str
      seqValue should include(requestId)
    }

    transport.close()
  }

  it should "handle mixed concurrent and sequential requests" in {
    assume(!isWindows, "Bash not available on Windows")
    val scriptCommand = Seq(
      "bash",
      "-c",
      """while IFS= read -r line; do
           id=$(echo "$line" | grep -o '"id":"[^"]*"' | cut -d'"' -f4)
           if [ -n "$id" ]; then
             # Add small random delay to simulate real server processing
             sleep 0.0$((RANDOM % 3))
             echo "{\"jsonrpc\":\"2.0\",\"id\":\"$id\",\"result\":{\"mixed\":\"$id\"}}"
           fi
         done"""
    )

    val transport = new StdioTransportImpl(scriptCommand, startupTimeoutMs = 500, name = "mixed-server")

    // Warm up: send a single request to ensure the process is started before concurrent access
    val warmupRequest = JsonRpcRequest("2.0", "warmup", "test/warmup", None)
    val warmupResult  = transport.sendRequest(warmupRequest)
    warmupResult.isRight shouldBe true

    val executor      = Executors.newFixedThreadPool(3)
    val numRequests   = 15
    val startLatch    = new CountDownLatch(1)
    val completeLatch = new CountDownLatch(numRequests)
    val results       = new java.util.concurrent.ConcurrentHashMap[String, Either[String, JsonRpcResponse]]()

    // Launch requests in waves - some concurrent, some sequential
    (1 to numRequests).foreach { i =>
      val requestId = s"mixed-$i"
      executor.submit(new Runnable {
        override def run(): Unit = {
          // Stagger starts slightly to create mixed timing
          if (i > 5) Thread.sleep((i - 5) * 10)
          startLatch.await()

          val request = JsonRpcRequest(
            jsonrpc = "2.0",
            id = requestId,
            method = "test/method",
            params = Some(ujson.Obj("wave" -> i))
          )
          val result = transport.sendRequest(request)
          results.put(requestId, result)
          completeLatch.countDown()
        }
      })
    }

    startLatch.countDown()
    val completed = completeLatch.await(60, TimeUnit.SECONDS)
    completed shouldBe true

    // Verify all responses matched correctly
    (1 to numRequests).foreach { i =>
      val requestId = s"mixed-$i"
      val result    = results.get(requestId)

      result should not be null
      withClue(s"Request $requestId: ") {
        result.isRight shouldBe true
      }

      val response = result.toOption.get
      response.id shouldBe requestId
    }

    transport.close()
    executor.shutdown()
  }

  it should "correctly handle notifications (which don't expect responses)" in {
    assume(!isWindows, "Bash not available on Windows")
    val scriptCommand = Seq(
      "bash",
      "-c",
      """while IFS= read -r line; do
           id=$(echo "$line" | grep -o '"id":"[^"]*"' | cut -d'"' -f4)
           if [ -n "$id" ]; then
             echo "{\"jsonrpc\":\"2.0\",\"id\":\"$id\",\"result\":{\"ok\":true}}"
           fi
           # Notifications (no id) are silently accepted
         done"""
    )

    val transport = new StdioTransportImpl(scriptCommand, startupTimeoutMs = 500, name = "notification-server")

    // Send a notification (no response expected)
    val notification = JsonRpcNotification(
      jsonrpc = "2.0",
      method = "test/notify",
      params = Some(ujson.Obj("event" -> "test"))
    )

    val notifyResult = transport.sendNotification(notification)
    notifyResult.isRight shouldBe true

    // Send a regular request after the notification
    val request = JsonRpcRequest(
      jsonrpc = "2.0",
      id = "after-notify",
      method = "test/method",
      params = None
    )

    val requestResult = transport.sendRequest(request)
    requestResult.isRight shouldBe true
    requestResult.toOption.get.id shouldBe "after-notify"

    transport.close()
  }
}
