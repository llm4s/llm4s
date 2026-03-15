package org.llm4s.trace

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.http.{ FailingHttpClient, HttpResponse, MockHttpClient }

import java.util.Base64

class LangfuseBatchSenderSpec extends AnyFlatSpec with Matchers {

  private def testConfig = LangfuseHttpApiCaller(
    langfuseUrl = "https://cloud.langfuse.com/api/public/ingestion",
    publicKey = "pk-lf-test-key",
    secretKey = "sk-lf-test-secret"
  )

  private def testEvents = Seq(
    ujson.Obj("type" -> "trace-create", "body" -> ujson.Obj("id" -> "trace-1", "name" -> "test"))
  )

  "DefaultLangfuseBatchSender" should "send correct URL, headers, and body on success" in {
    val mockClient = new MockHttpClient(HttpResponse(200, """{"successes":1}"""))
    val sender     = new DefaultLangfuseBatchSender(httpClient = mockClient, restoreInterrupt = () => ())

    sender.sendBatch(testEvents, testConfig)

    mockClient.lastUrl shouldBe Some("https://cloud.langfuse.com/api/public/ingestion")
    mockClient.lastTimeout shouldBe Some(30000)

    // Verify Basic auth header
    val expectedCredentials = Base64.getEncoder.encodeToString("pk-lf-test-key:sk-lf-test-secret".getBytes)
    mockClient.lastHeaders.flatMap(_.get("Authorization")) shouldBe Some(s"Basic $expectedCredentials")
    mockClient.lastHeaders.flatMap(_.get("Content-Type")) shouldBe Some("application/json")
    mockClient.lastHeaders.flatMap(_.get("User-Agent")) shouldBe Some("llm4s-scala/1.0.0")

    // Verify body contains batch payload
    val body = mockClient.lastBody.getOrElse(fail("Expected body"))
    val json = ujson.read(body)
    json.obj("batch").arr should have size 1
    json.obj("batch").arr.head.obj("type").str shouldBe "trace-create"
  }

  it should "handle 207 partial success without throwing" in {
    val mockClient = new MockHttpClient(HttpResponse(207, """{"successes":1,"errors":0}"""))
    val sender     = new DefaultLangfuseBatchSender(httpClient = mockClient, restoreInterrupt = () => ())

    // Should not throw
    noException should be thrownBy {
      sender.sendBatch(testEvents, testConfig)
    }
    mockClient.postCallCount shouldBe 1
  }

  it should "handle non-2xx error response without throwing" in {
    val mockClient = new MockHttpClient(HttpResponse(500, """{"error":"Internal Server Error"}"""))
    val sender     = new DefaultLangfuseBatchSender(httpClient = mockClient, restoreInterrupt = () => ())

    noException should be thrownBy {
      sender.sendBatch(testEvents, testConfig)
    }
    mockClient.postCallCount shouldBe 1
  }

  it should "handle 401 unauthorized without throwing" in {
    val mockClient = new MockHttpClient(HttpResponse(401, """{"error":"Unauthorized"}"""))
    val sender     = new DefaultLangfuseBatchSender(httpClient = mockClient, restoreInterrupt = () => ())

    noException should be thrownBy {
      sender.sendBatch(testEvents, testConfig)
    }
    mockClient.postCallCount shouldBe 1
  }

  it should "skip export when public key is empty" in {
    val mockClient = new MockHttpClient(HttpResponse(200, ""))
    val sender     = new DefaultLangfuseBatchSender(httpClient = mockClient, restoreInterrupt = () => ())
    val config     = testConfig.copy(publicKey = "")

    sender.sendBatch(testEvents, config)

    mockClient.postCallCount shouldBe 0
  }

  it should "skip export when secret key is empty" in {
    val mockClient = new MockHttpClient(HttpResponse(200, ""))
    val sender     = new DefaultLangfuseBatchSender(httpClient = mockClient, restoreInterrupt = () => ())
    val config     = testConfig.copy(secretKey = "")

    sender.sendBatch(testEvents, config)

    mockClient.postCallCount shouldBe 0
  }

  it should "call restoreInterrupt on InterruptedException" in {
    var interruptRestored = false
    val mockRestore       = () => interruptRestored = true
    val failingClient     = new FailingHttpClient(new InterruptedException("interrupted"))
    val sender            = new DefaultLangfuseBatchSender(httpClient = failingClient, restoreInterrupt = mockRestore)

    noException should be thrownBy {
      sender.sendBatch(testEvents, testConfig)
    }
    interruptRestored shouldBe true
  }

  it should "handle generic exceptions without throwing" in {
    val failingClient = new FailingHttpClient(new RuntimeException("connection reset"))
    val sender        = new DefaultLangfuseBatchSender(httpClient = failingClient, restoreInterrupt = () => ())

    noException should be thrownBy {
      sender.sendBatch(testEvents, testConfig)
    }
  }

  it should "handle multiple events in batch payload" in {
    val mockClient = new MockHttpClient(HttpResponse(200, """{"successes":3}"""))
    val sender     = new DefaultLangfuseBatchSender(httpClient = mockClient, restoreInterrupt = () => ())

    val events = Seq(
      ujson.Obj("type" -> "trace-create", "body"      -> ujson.Obj("id" -> "t1")),
      ujson.Obj("type" -> "span-create", "body"       -> ujson.Obj("id" -> "s1")),
      ujson.Obj("type" -> "generation-create", "body" -> ujson.Obj("id" -> "g1"))
    )

    sender.sendBatch(events, testConfig)

    val body = mockClient.lastBody.getOrElse(fail("Expected body"))
    val json = ujson.read(body)
    json.obj("batch").arr should have size 3
  }

  it should "encode credentials correctly in Base64" in {
    val mockClient = new MockHttpClient(HttpResponse(200, ""))
    val sender     = new DefaultLangfuseBatchSender(httpClient = mockClient, restoreInterrupt = () => ())
    val config = LangfuseHttpApiCaller(
      langfuseUrl = "https://langfuse.example.com/api",
      publicKey = "pk-test",
      secretKey = "sk-test"
    )

    sender.sendBatch(testEvents, config)

    val authHeader = mockClient.lastHeaders.flatMap(_.get("Authorization")).getOrElse(fail("Missing Authorization"))
    val encoded    = authHeader.stripPrefix("Basic ")
    val decoded    = new String(Base64.getDecoder.decode(encoded))
    decoded shouldBe "pk-test:sk-test"
  }
}
