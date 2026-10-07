package org.llm4s.llmconnect.provider

import org.llm4s.error.{ AuthenticationError, CancelledError, NetworkError }
import org.llm4s.http.HttpResponse
import org.llm4s.llmconnect.model.*
import org.llm4s.model.ModelRegistryService
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger
import scala.util.Try

/** The IAM bearer-token cache: when it exchanges, when it reuses, and what a failure leaves behind. */
class WatsonXTokenSpec extends AnyFunSuite with Matchers:
  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  import StubHttp.*
  import WatsonXTestConfig.config

  private val hi = Conversation(Seq(UserMessage("Hi")))

  private def iamCount(http: StubHttp): Int = http.iamRequests.size

  private def okStub(iamBody: HttpResponse = iamToken()): StubHttp =
    routed(_ => Right(iamBody), _ => Right(chatReply))

  test("token refresh boundary: reused strictly more than 300s before expiry, exchanged again at 300s and inside") {
    val table = Seq(
      3599L -> false, // 3599s of 3600 left
      301L  -> false, // 301s left: just outside the buffer
      300L  -> true,  // exactly at the buffer: refreshed (the comparison is strict)
      299L  -> true,
      1L    -> true,
      0L    -> true,  // expired
      -50L  -> true   // long expired
    )
    table.foreach { case (remaining, refreshes) =>
      val http = okStub()
      var now  = 1000L
      val c    = new WatsonXClient(config, httpClient = http, nowSeconds = () => now)
      c.bearerToken() shouldBe Right("tok-1")
      now = 1000L + 3600L - remaining
      c.bearerToken() shouldBe Right("tok-1")
      withClue(s"remaining=$remaining: ")(iamCount(http) shouldBe (if refreshes then 2 else 1))
    }
  }

  test("expires_in is in seconds: a 7200 token survives an hour, a missing expires_in defaults to 3600") {
    val long = okStub(iamToken(expiresIn = 7200))
    var now  = 0L
    val a    = new WatsonXClient(config, httpClient = long, nowSeconds = () => now)
    a.bearerToken()
    now = 3600L
    a.bearerToken()
    iamCount(long) shouldBe 1

    val noTtl = okStub(HttpResponse(200, """{"access_token":"tok-1"}"""))
    var now2  = 0L
    val b     = new WatsonXClient(config, httpClient = noTtl, nowSeconds = () => now2)
    b.bearerToken()
    now2 = 3299L
    b.bearerToken()
    iamCount(noTtl) shouldBe 1
    now2 = 3301L
    b.bearerToken()
    iamCount(noTtl) shouldBe 2
  }

  test("a token that lives no longer than the buffer is exchanged on every call (no caching, no error)") {
    val http = okStub(iamToken(expiresIn = 100))
    val c    = new WatsonXClient(config, httpClient = http, nowSeconds = () => 5L)
    (1 to 3).foreach(_ => c.bearerToken() shouldBe Right("tok-1"))
    iamCount(http) shouldBe 3
  }

  test("a fractional expires_in is accepted") {
    val http = okStub(HttpResponse(200, """{"access_token":"tok-1","expires_in":3599.9}"""))
    new WatsonXClient(config, httpClient = http).bearerToken() shouldBe Right("tok-1")
  }

  test("a failed exchange is not cached: the next call exchanges again and succeeds") {
    val calls = new AtomicInteger(0)
    val http = routed(
      _ => if calls.getAndIncrement() == 0 then Right(HttpResponse(503, "busy")) else Right(iamToken()),
      _ => Right(chatReply)
    )
    val c = new WatsonXClient(config, httpClient = http)
    c.complete(hi, CompletionOptions()).left.toOption.exists(_.isInstanceOf[AuthenticationError]) shouldBe true
    http.modelRequests shouldBe empty
    c.complete(hi, CompletionOptions()).map(_.content) shouldBe Right("Hello")
    iamCount(http) shouldBe 2
  }

  test("a transport failure on the IAM exchange is not cached either") {
    val calls = new AtomicInteger(0)
    val http = routed(
      _ =>
        if calls.getAndIncrement() == 0 then Left(NetworkError("down", None, "https://iam.example.com"))
        else Right(iamToken()),
      _ => Right(chatReply)
    )
    val c = new WatsonXClient(config, httpClient = http)
    c.bearerToken().isLeft shouldBe true
    c.bearerToken() shouldBe Right("tok-1")
  }

  test("a refresh that fails after a good token expired surfaces the failure and keeps no stale token") {
    val calls = new AtomicInteger(0)
    val http = routed(
      _ => if calls.getAndIncrement() == 0 then Right(iamToken()) else Right(HttpResponse(500, "boom")),
      _ => Right(chatReply)
    )
    var now = 0L
    val c   = new WatsonXClient(config, httpClient = http, nowSeconds = () => now)
    c.bearerToken() shouldBe Right("tok-1")
    now = 4000L
    c.bearerToken().isLeft shouldBe true
  }

  test("the IAM request is a form POST with the apikey grant type and no bearer header") {
    val http = okStub()
    new WatsonXClient(config, httpClient = http).bearerToken()
    val req = http.iamRequests.head
    req.headers("Content-Type") shouldBe "application/x-www-form-urlencoded"
    req.headers.keySet should not contain "Authorization"
    req.body should startWith("grant_type=urn%3Aibm%3Aparams%3Aoauth%3Agrant-type%3Aapikey&apikey=")
  }

  test("a call made with the interrupt flag already set is cancelled before any HTTP request") {
    val http = okStub()
    val c    = new WatsonXClient(config, httpClient = http)
    Thread.currentThread().interrupt()
    val result = Try(c.complete(hi, CompletionOptions()))
    Thread.interrupted(): Unit
    result.toOption.flatMap(_.left.toOption).exists(_.isInstanceOf[CancelledError]) shouldBe true
    http.requests shouldBe empty
  }
