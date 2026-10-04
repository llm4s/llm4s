package org.llm4s.llmconnect.provider

import org.llm4s.error.LLMError
import org.llm4s.http.HttpResponse
import org.llm4s.llmconnect.config.{ ContextWindowResolver, WatsonXConfig }
import org.llm4s.llmconnect.model.*
import org.llm4s.model.ModelRegistryService
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{ CountDownLatch, Executors, TimeUnit }
import scala.util.Try

/** Regressions for defects found reviewing #1053: each fails against the code as first submitted. */
class WatsonXRegressionSpec extends AnyFunSuite with Matchers:
  private given ModelRegistryService  = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  private given ContextWindowResolver = ContextWindowResolver(org.llm4s.model.ModelRegistryTestSupport.defaultService())
  import StubHttp.*
  import WatsonXTestConfig.{ ApiKey, config }

  private val hi = Conversation(Seq(UserMessage("Hi")))

  private def left(r: Try[org.llm4s.types.Result[?]]): Option[LLMError] =
    r.toOption.flatMap(_.left.toOption)

  test("50 concurrent first calls make exactly one IAM exchange, and all get the same token") {
    val threads = 50
    val http = routed(
      _ => { Thread.sleep(40); Right(iamToken()) }, // widen the window in which a second exchange could start
      _ => Right(generation)
    )
    val c    = new WatsonXClient(config, httpClient = http)
    val gate = new CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(threads)
    val tasks = (1 to threads).map(_ =>
      pool.submit { () =>
        gate.await(); c.bearerToken()
      }
    )
    gate.countDown()
    val results = tasks.map(_.get(30, TimeUnit.SECONDS))
    pool.shutdownNow(): Unit
    results.distinct shouldBe Seq(Right("tok-1"))
    http.iamRequests.size shouldBe 1
  }

  test("a 401 from the model endpoint drops the cached token, so the next call exchanges a fresh one") {
    var tokens = 0
    val http = routed(
      _ => { tokens += 1; Right(iamToken(s"tok-$tokens")) },
      seen =>
        if seen.headers("Authorization") == "Bearer tok-1" then Right(HttpResponse(401, """{"errors":[]}"""))
        else Right(generation)
    )
    val c = new WatsonXClient(config, httpClient = http)
    c.complete(hi, CompletionOptions()).isLeft shouldBe true
    c.complete(hi, CompletionOptions()).map(_.content) shouldBe Right("Hello")
    http.iamRequests.size shouldBe 2
  }

  test("a 401 on a stream drops the cached token too") {
    var tokens = 0
    val http = new StubHttp(
      _ => { tokens += 1; Right(iamToken(s"tok-$tokens")) },
      seen =>
        Right(
          org.llm4s.http.StreamingHttpResponse(
            if seen.headers("Authorization") == "Bearer tok-1" then 401 else 200,
            bytes("""data: {"results":[{"generated_text":"ok","stop_reason":"eos_token"}]}""" + "\n\n")
          )
        )
    )
    val c = new WatsonXClient(config, httpClient = http)
    c.streamComplete(hi, CompletionOptions(), _ => ()).isLeft shouldBe true
    c.streamComplete(hi, CompletionOptions(), _ => ()).map(_.content) shouldBe Right("ok")
  }

  test("an IAM answer that is valid JSON but not an object is an error, not an exception") {
    Seq("[]", "\"x\"", "123", "null").foreach { body =>
      val c =
        new WatsonXClient(config, httpClient = routed(_ => Right(HttpResponse(200, body)), _ => Right(generation)))
      withClue(s"IAM body $body: ")(left(Try(c.complete(hi, CompletionOptions()))).isDefined shouldBe true)
    }
  }

  test("a generation answer with an unexpected shape is an error, not an exception") {
    Seq("[]", "123", """{"results":[1]}""", """{"results":["x"]}""", """{"results":[null]}""").foreach { body =>
      val http = routed(_ => Right(iamToken()), _ => Right(HttpResponse(200, body)))
      val c    = new WatsonXClient(config, httpClient = http)
      withClue(s"body $body: ")(left(Try(c.complete(hi, CompletionOptions()))).isDefined shouldBe true)
    }
  }

  test("a trailing slash or stray whitespace on baseUrl does not produce a double slash in the endpoint") {
    Seq("https://wx.example.com/", "https://wx.example.com//", "  https://wx.example.com ").foreach { base =>
      val cfg = WatsonXConfig
        .fromValues("m", ApiKey, Some("p"), baseUrl = base)
        .getOrElse(fail(s"config for '$base' should be valid"))
      val http = routed(_ => Right(iamToken()), _ => Right(generation))
      new WatsonXClient(cfg, httpClient = http).complete(hi, CompletionOptions())
      http.modelRequests.map(_.url) shouldBe Seq("https://wx.example.com/ml/v1/text/generation?version=2024-05-31")
    }
  }

  test("an IAM error body that echoes the api key does not carry it into the error") {
    val http = routed(
      _ => Right(HttpResponse(400, s"""{"errorMessage":"Provided API key $ApiKey is invalid"}""")),
      _ => Right(generation)
    )
    val error = new WatsonXClient(config, httpClient = http).complete(hi, CompletionOptions()).left.toOption
    (error.map(_.message).getOrElse("") should not).include(ApiKey)
  }

  test("neither secret appears in a stream error either") {
    Seq(401, 429, 500).foreach { status =>
      new WatsonXClient(
        config,
        httpClient = streaming(_ =>
          Right(
            org.llm4s.http.StreamingHttpResponse(
              status,
              bytes(s"""{"message":"Bearer tok-SECRETTOKEN-9876543210 $ApiKey"}""")
            )
          )
        )
      ).streamComplete(hi, CompletionOptions(), _ => ()).left.toOption match
        case Some(error) =>
          List(error.message, error.formatted).foreach { text =>
            (text should not).include(ApiKey)
            (text should not).include("tok-SECRETTOKEN-9876543210")
          }
        case None => fail("expected Left")
    }
  }
