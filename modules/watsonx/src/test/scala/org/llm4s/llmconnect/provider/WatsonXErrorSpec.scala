package org.llm4s.llmconnect.provider

import org.llm4s.error.*
import org.llm4s.http.{ FailingHttpClient, HttpResponse, StreamingHttpResponse }
import org.llm4s.llmconnect.{ ProviderExchange, ProviderExchangeLogging, ProviderExchangeSink }
import org.llm4s.llmconnect.model.*
import org.llm4s.model.ModelRegistryService
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Status-to-error mapping and secret hygiene on every error path. */
class WatsonXErrorSpec extends AnyFunSuite with Matchers:
  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  import StubHttp.*
  import WatsonXTestConfig.{ ApiKey, config }

  private val hi = Conversation(Seq(UserMessage("Hi")))

  private def lower(h: (String, String)*): Map[String, Seq[String]] = h.map((k, v) => k.toLowerCase -> Seq(v)).toMap

  private def failing(
    status: Int,
    headers: Map[String, Seq[String]],
    body: String = """{"errors":[{"message":"m"}]}"""
  ) =
    routed(_ => Right(iamToken()), _ => Right(HttpResponse(status, body, headers)))

  private def viaComplete(http: StubHttp) =
    new WatsonXClient(config, httpClient = http).complete(hi, CompletionOptions())

  private def viaStream(status: Int, headers: Map[String, Seq[String]], body: String) =
    new WatsonXClient(config, httpClient = streaming(_ => Right(StreamingHttpResponse(status, bytes(body), headers))))
      .streamComplete(hi, CompletionOptions(), _ => ())

  private type Check = LLMError => Boolean

  private val table: Seq[(String, Int, Map[String, Seq[String]], Check)] = Seq(
    ("400", 400, Map.empty, { case _: ValidationError => true; case _ => false }),
    ("401", 401, Map.empty, { case _: AuthenticationError => true; case _ => false }),
    ("403", 403, Map.empty, { case _: AuthenticationError => true; case _ => false }),
    ("404", 404, Map.empty, { case e: ServiceError => e.httpStatus == 404; case _ => false }),
    ("429 bare", 429, Map.empty, { case e: RateLimitError => e.retryAfter.isEmpty; case _ => false }),
    (
      "429 Retry-After 7",
      429,
      lower("Retry-After" -> "7"),
      { case e: RateLimitError => e.retryAfter.contains(7.seconds); case _ => false }
    ),
    (
      "429 Retry-After garbage",
      429,
      lower("Retry-After" -> "soon"),
      { case e: RateLimitError => e.retryAfter.isEmpty; case _ => false }
    ),
    (
      "429 Retry-After negative",
      429,
      lower("Retry-After" -> "-5"),
      { case e: RateLimitError => e.retryAfter.isEmpty; case _ => false }
    ),
    ("500", 500, Map.empty, { case e: ServiceError => e.httpStatus == 500 && e.retryAfter.isEmpty; case _ => false }),
    (
      "503 Retry-After 12",
      503,
      lower("Retry-After" -> "12"),
      { case e: ServiceError => e.httpStatus == 503 && e.retryAfter.contains(12.seconds); case _ => false }
    )
  )

  test("HTTP status mapping table, on complete()") {
    table.foreach { case (name, status, headers, check) =>
      val error = viaComplete(failing(status, headers)).left.toOption.getOrElse(fail(s"$name: expected Left"))
      withClue(s"$name -> $error: ")(check(error) shouldBe true)
    }
  }

  test("the same table on streamComplete()") {
    table.foreach { case (name, status, headers, check) =>
      val error = viaStream(status, headers, """{"errors":[{"message":"m"}]}""").left.toOption
        .getOrElse(fail(s"$name: expected Left"))
      withClue(s"$name -> $error: ")(check(error) shouldBe true)
    }
  }

  test("an error body that is not JSON still maps by status") {
    viaComplete(failing(502, Map.empty, "<html>bad gateway</html>")).left.toOption
      .exists(_.isInstanceOf[ServiceError]) shouldBe true
  }

  test("a model-call timeout is a TimeoutError, a refused connection a NetworkError") {
    val timeout = new FailingHttpClient(new java.net.http.HttpTimeoutException("slow"))
    new WatsonXClient(config, httpClient = timeout)
      .complete(hi, CompletionOptions())
      .left
      .toOption
      .exists(_.isInstanceOf[TimeoutError]) shouldBe true

    val refused = new FailingHttpClient(new java.net.ConnectException("refused"))
    new WatsonXClient(config, httpClient = refused)
      .complete(hi, CompletionOptions())
      .left
      .toOption
      .exists(_.isInstanceOf[NetworkError]) shouldBe true
  }

  test("calls use the documented timeouts: 30s for IAM, 120s for generation") {
    var seen = List.empty[FiniteDuration]
    val http = new org.llm4s.http.Llm4sHttpClient:
      private val inner = routed(_ => Right(iamToken()), _ => Right(generation))
      override def post(url: String, headers: Map[String, String], body: String, timeout: FiniteDuration) =
        seen = seen :+ timeout
        inner.post(url, headers, body, timeout)
      override def get(
        url: String,
        headers: Map[String, String],
        params: Map[String, String],
        timeout: FiniteDuration
      ) =
        inner.get(url, headers, params, timeout)
      override def postBytes(url: String, headers: Map[String, String], data: Array[Byte], timeout: FiniteDuration) =
        inner.postBytes(url, headers, data, timeout)
      override def postMultipart(
        url: String,
        headers: Map[String, String],
        parts: Seq[org.llm4s.http.MultipartPart],
        timeout: FiniteDuration
      ) =
        inner.postMultipart(url, headers, parts, timeout)
      override def put(url: String, headers: Map[String, String], body: String, timeout: FiniteDuration) =
        inner.put(url, headers, body, timeout)
      override def delete(url: String, headers: Map[String, String], timeout: FiniteDuration) =
        inner.delete(url, headers, timeout)
      override def postRaw(url: String, headers: Map[String, String], body: String, timeout: FiniteDuration) =
        inner.postRaw(url, headers, body, timeout)
      override def postStream(url: String, headers: Map[String, String], body: String, timeout: FiniteDuration) =
        inner.postStream(url, headers, body, timeout)
    new WatsonXClient(config, httpClient = http).complete(hi, CompletionOptions())
    seen shouldBe List(30.seconds, 120.seconds)
  }

  // ---- secrets ----

  private val BearerToken = "tok-SECRETTOKEN-9876543210"

  private def surfaces(error: LLMError): Seq[String] =
    Seq(error.message, error.toString, error.formatted) ++ error.context.values

  private def assertNoSecrets(label: String, error: LLMError): Unit =
    surfaces(error).filter(_ != null).foreach { text =>
      withClue(s"$label: ")((text should not).include(ApiKey))
      withClue(s"$label: ")((text should not).include(BearerToken))
    }

  test("neither the api key nor the bearer token appears in any error, whichever step failed") {
    val tokenOk = HttpResponse(200, s"""{"access_token":"$BearerToken","expires_in":3600}""")
    val scenarios: Seq[(String, StubHttp)] = Seq(
      "IAM 400" -> routed(_ => Right(HttpResponse(400, """{"errorMessage":"bad key"}""")), _ => Right(generation)),
      "IAM 401 plain text" -> routed(_ => Right(HttpResponse(401, "Unauthorized")), _ => Right(generation)),
      "IAM 500"            -> routed(_ => Right(HttpResponse(500, "boom")), _ => Right(generation)),
      "IAM garbage"        -> routed(_ => Right(HttpResponse(200, "<<<")), _ => Right(generation)),
      "IAM no token"       -> routed(_ => Right(HttpResponse(200, "{}")), _ => Right(generation)),
      "IAM transport" -> routed(
        _ => Left(NetworkError("down", None, "https://iam.example.com")),
        _ => Right(generation)
      ),
      "model 401" -> routed(_ => Right(tokenOk), _ => Right(HttpResponse(401, """{"errors":[{"message":"nope"}]}"""))),
      "model 403" -> routed(_ => Right(tokenOk), _ => Right(HttpResponse(403, "forbidden"))),
      "model 429" -> routed(_ => Right(tokenOk), _ => Right(HttpResponse(429, "{}", lower("Retry-After" -> "3")))),
      "model 500" -> routed(_ => Right(tokenOk), _ => Right(HttpResponse(500, "oops"))),
      "model garbage"    -> routed(_ => Right(tokenOk), _ => Right(HttpResponse(200, "not json"))),
      "model no results" -> routed(_ => Right(tokenOk), _ => Right(HttpResponse(200, "{}"))),
      "model echoes bearer" -> routed(
        _ => Right(tokenOk),
        seen => Right(HttpResponse(401, s"""{"message":"bad ${seen.headers("Authorization")}"}"""))
      ),
      "model echoes key" -> routed(
        _ => Right(tokenOk),
        _ => Right(HttpResponse(400, s"""{"message":"Authorization: Bearer $BearerToken apikey=$ApiKey"}"""))
      )
    )
    val cfg = config
    scenarios.foreach { case (label, http) =>
      new WatsonXClient(cfg, httpClient = http).complete(hi, CompletionOptions()).left.toOption match
        case Some(error) => assertNoSecrets(label, error)
        case None        => fail(s"$label: expected a Left")
    }
  }

  test("the api key is absent from WatsonXConfig's toString, copy and the client's toString") {
    (config.toString should not).include(ApiKey)
    config.toString should include("***")
    (config.copy(model = "x").toString should not).include(ApiKey)
    (config.withModel("y").toString should not).include(ApiKey)
    (new WatsonXClient(config, httpClient = routed(_ => Right(iamToken()), _ => Right(generation))).toString should not)
      .include(ApiKey)
  }

  test("provider exchange logging records the prompt and reply but never the api key, bearer token or headers") {
    val seen = new ConcurrentLinkedQueue[ProviderExchange]()
    val sink = new ProviderExchangeSink:
      override def record(exchange: ProviderExchange): Unit = seen.add(exchange): Unit
    val http = routed(
      _ => Right(HttpResponse(200, s"""{"access_token":"$BearerToken","expires_in":3600}""")),
      _ => Right(generation)
    )
    val c = new WatsonXClient(config, exchangeLogging = ProviderExchangeLogging.enabled(sink), httpClient = http)
    c.complete(hi, CompletionOptions()).isRight shouldBe true
    c.streamComplete(hi, CompletionOptions(), _ => ()).isLeft shouldBe true // no stream scripted: still no secrets
    val all = seen.asScala.toSeq
    all should not be empty
    all.foreach { e =>
      val text = e.toString
      (text should not).include(ApiKey)
      (text should not).include(BearerToken)
      (text should not).include("Authorization")
    }
  }
