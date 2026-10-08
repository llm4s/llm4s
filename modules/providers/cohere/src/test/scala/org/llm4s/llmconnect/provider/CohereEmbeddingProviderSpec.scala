package org.llm4s.llmconnect.provider

import org.llm4s.error.{ CancelledError, NetworkError, RateLimitError }
import org.llm4s.http.{ HttpResponse, Llm4sHttpClient, MockHttpClient, MultipartPart }
import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, EmbeddingProviderConfig }
import org.llm4s.llmconnect.model.{ EmbeddingError, EmbeddingRequest, EmbeddingUsage }
import org.llm4s.testkit.LocalProviderTestServer
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ujson.read

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable
import scala.concurrent.duration.*
import org.llm4s.testutil.EchoedCredentials

/**
 * `CohereEmbeddingProvider` against Cohere's `/v2/embed` contract: the exact request body (including
 * the `embedding_types` v2 requires), the response keyed by embedding type, batching at Cohere's
 * 96-text limit, billed-token usage, the status-code table, secret handling, and the real HTTP
 * wire (a local stub server, no network).
 */
class CohereEmbeddingProviderSpec extends AnyFlatSpec with Matchers {

  private val modelCfg  = EmbeddingModelConfig("embed-english-v3.0", 1024)
  private val secretKey = "cohere-SECRET-key-123"

  private def cfg(base: String = "http://cohere-test") =
    EmbeddingProviderConfig(baseUrl = base, model = "embed-english-v3.0", apiKey = secretKey)

  /** A v2 response: `embeddings` keyed by type, with the billed input tokens in `meta` when given. */
  private def body(vectors: Seq[Seq[Double]], billed: Option[Int] = None): String = {
    val floats = vectors.map(_.mkString("[", ",", "]")).mkString("[", ",", "]")
    val meta =
      billed.map(n => s""","meta":{"api_version":{"version":"2"},"billed_units":{"input_tokens":$n}}""").getOrElse("")
    s"""{"id":"abc","embeddings":{"float":$floats},"texts":[],"response_type":"embeddings_by_type"$meta}"""
  }

  private def ok(vectors: Seq[Seq[Double]], billed: Option[Int] = None): HttpResponse =
    HttpResponse(200, body(vectors, billed), Map.empty)

  private def embed(http: Llm4sHttpClient, texts: Seq[String], inputType: CohereInputType = CohereInputType.default) =
    CohereEmbeddingProvider.forTest(cfg(), http, inputType).embed(EmbeddingRequest(texts, modelCfg))

  /** Records every POST body, and answers each with `respond(callIndex, texts sent)`. Everything else fails. */
  private class RecordingHttp(respond: (Int, Seq[String]) => HttpResponse) extends Llm4sHttpClient {
    val bodies: mutable.Buffer[String] = mutable.Buffer.empty
    def textsOf(i: Int): Seq[String]   = read(bodies(i))("texts").arr.map(_.str).toSeq

    private def fail[A]: Result[A] = Left(NetworkError("not a post", None, "http://cohere-test"))
    def post(u: String, h: Map[String, String], b: String, t: FiniteDuration): Result[HttpResponse] = {
      bodies += b
      Right(respond(bodies.size - 1, textsOf(bodies.size - 1)))
    }
    def get(u: String, h: Map[String, String], p: Map[String, String], t: FiniteDuration)          = fail
    def postBytes(u: String, h: Map[String, String], d: Array[Byte], t: FiniteDuration)            = fail
    def postMultipart(u: String, h: Map[String, String], p: Seq[MultipartPart], t: FiniteDuration) = fail
    def put(u: String, h: Map[String, String], b: String, t: FiniteDuration)                       = fail
    def delete(u: String, h: Map[String, String], t: FiniteDuration)                               = fail
    def postRaw(u: String, h: Map[String, String], b: String, t: FiniteDuration)                   = fail
    def postStream(u: String, h: Map[String, String], b: String, t: FiniteDuration)                = fail
  }

  /** Answers the nth POST with `script(n)`, which may also throw. Everything else fails. */
  private class ScriptedPost(script: Int => Result[HttpResponse]) extends Llm4sHttpClient {
    val calls = new java.util.concurrent.atomic.AtomicInteger(0)

    private def fail[A]: Result[A] = Left(NetworkError("not a post", None, "http://cohere-test"))
    def post(u: String, h: Map[String, String], b: String, t: FiniteDuration): Result[HttpResponse] =
      script(calls.getAndIncrement())
    def get(u: String, h: Map[String, String], p: Map[String, String], t: FiniteDuration)          = fail
    def postBytes(u: String, h: Map[String, String], d: Array[Byte], t: FiniteDuration)            = fail
    def postMultipart(u: String, h: Map[String, String], p: Seq[MultipartPart], t: FiniteDuration) = fail
    def put(u: String, h: Map[String, String], b: String, t: FiniteDuration)                       = fail
    def delete(u: String, h: Map[String, String], t: FiniteDuration)                               = fail
    def postRaw(u: String, h: Map[String, String], b: String, t: FiniteDuration)                   = fail
    def postStream(u: String, h: Map[String, String], b: String, t: FiniteDuration)                = fail
  }

  /** One distinguishable vector per text: `[n]` for the text "tn", so order is checkable. */
  private def vectorsFor(texts: Seq[String]): Seq[Seq[Double]] = texts.map(t => Seq(t.drop(1).toDouble))

  "the request body" should "match the golden JSON for every input type" in {
    CohereInputType.values.foreach { inputType =>
      val http = new MockHttpClient(ok(Seq(Seq(1.0), Seq(2.0))))
      embed(http, Seq("a \"q\"\n", "ü"), inputType)
      withClue(inputType.toString) {
        read(http.lastBody.get) shouldBe read(
          s"""{"model":"embed-english-v3.0","texts":["a \\"q\\"\\n","ü"],"input_type":"${inputType.wireName}","embedding_types":["float"]}"""
        )
      }
    }
  }

  it should "send exactly model, texts, input_type and embedding_types - v2 requires embedding_types, and no output_dimension is sent" in {
    val http = new MockHttpClient(ok(Seq(Seq(1.0))))
    embed(http, Seq("a"))
    val sent = read(http.lastBody.get)
    sent.obj.keySet shouldBe Set("model", "texts", "input_type", "embedding_types")
    sent("embedding_types").arr.map(_.str).toSeq shouldBe Seq("float")
  }

  it should "default to search_document, because indexing is the common batch case" in {
    CohereInputType.default shouldBe CohereInputType.SearchDocument
    val http = new MockHttpClient(ok(Seq(Seq(1.0))))
    embed(http, Seq("a"))
    read(http.lastBody.get)("input_type").str shouldBe "search_document"
  }

  it should "not infer input_type from the number of texts or from the model name" in {
    val single = new MockHttpClient(ok(Seq(Seq(1.0))))
    embed(single, Seq("one text"))
    read(single.lastBody.get)("input_type").str shouldBe "search_document"

    val named = new MockHttpClient(ok(Seq(Seq(1.0))))
    CohereEmbeddingProvider
      .forTest(cfg(), named)
      .embed(EmbeddingRequest(Seq("a"), EmbeddingModelConfig("embed-english-v3.0-search_query", 1024)))
    // the model name is passed through untouched, and says nothing about the type
    read(named.lastBody.get)("model").str shouldBe "embed-english-v3.0-search_query"
    read(named.lastBody.get)("input_type").str shouldBe "search_document"
  }

  it should "carry the Bearer key and JSON content type, with a two-minute timeout" in {
    val http = new MockHttpClient(ok(Seq(Seq(1.0))))
    embed(http, Seq("a"))
    http.lastHeaders.get("Authorization") shouldBe s"Bearer $secretKey"
    http.lastHeaders.get("Content-Type") shouldBe "application/json"
    http.lastTimeout shouldBe Some(120.seconds)
  }

  "URL joining" should "yield <root>/v2/embed for every base-URL spelling, without doubling the version" in {
    val table = Seq(
      "https://api.cohere.com"           -> "https://api.cohere.com/v2/embed",
      "https://api.cohere.com/"          -> "https://api.cohere.com/v2/embed",
      "https://api.cohere.com/v2"        -> "https://api.cohere.com/v2/embed",
      "https://api.cohere.com/v2/"       -> "https://api.cohere.com/v2/embed",
      "https://api.cohere.com/v2/embed"  -> "https://api.cohere.com/v2/embed",
      "https://api.cohere.com/v2/embed/" -> "https://api.cohere.com/v2/embed",
      "http://localhost:8080"            -> "http://localhost:8080/v2/embed",
      "https://proxy.local/a/b/"         -> "https://proxy.local/a/b/v2/embed"
    )
    table.foreach { case (base, expected) =>
      val http = new MockHttpClient(ok(Seq(Seq(1.0))))
      CohereEmbeddingProvider.forTest(cfg(base), http).embed(EmbeddingRequest(Seq("a"), modelCfg))
      withClue(base)(http.lastUrl.get shouldBe expected)
    }
  }

  "the response" should "be read from embeddings.float, in input order, keeping numeric precision" in {
    val raw =
      """{"id":"x","embeddings":{"float":[[1,-2,0.123456789012345,1e-7],[3.5,4,5,6]]},"response_type":"embeddings_by_type"}"""
    val res = embed(new MockHttpClient(HttpResponse(200, raw, Map.empty)), Seq("a", "b"))
    res.toOption.get.embeddings shouldBe Seq(Vector(1.0, -2.0, 0.123456789012345, 1e-7), Vector(3.5, 4.0, 5.0, 6.0))
  }

  it should "read only the float vectors when other embedding types come back too" in {
    val raw = """{"embeddings":{"float":[[0.5,0.25]],"int8":[[1,2]]}}"""
    embed(new MockHttpClient(HttpResponse(200, raw, Map.empty)), Seq("a")).toOption.get.embeddings shouldBe
      Seq(Vector(0.5, 0.25))
  }

  it should "describe itself in metadata: provider, model, count and input type" in {
    val res = embed(new MockHttpClient(ok(Seq(Seq(1.0)))), Seq("a"), CohereInputType.SearchQuery).toOption.get
    res.metadata shouldBe Map(
      "provider"   -> "cohere",
      "model"      -> "embed-english-v3.0",
      "count"      -> "1",
      "input_type" -> "search_query"
    )
  }

  it should "not accept the array-of-objects shape the first draft assumed, which Cohere never sends" in {
    val raw = """{"embeddings":[{"embedding":[0.1,0.2]}]}"""
    embed(new MockHttpClient(HttpResponse(200, raw, Map.empty)), Seq("a")).left.toOption.get.message should
      startWith("Parsing error")
  }

  it should "not accept an OpenAI-style data array either" in {
    val raw = """{"data":[{"index":0,"embedding":[0.1,0.2]}]}"""
    embed(new MockHttpClient(HttpResponse(200, raw, Map.empty)), Seq("a")).left.toOption.get.message should
      startWith("Parsing error")
  }

  it should "be a parse error naming the problem when there is no float embedding" in {
    val err = embed(new MockHttpClient(HttpResponse(200, """{"embeddings":{"int8":[[1]]}}""", Map.empty)), Seq("a"))
    err.left.toOption.get.message should include("no float embeddings")
  }

  it should "be a parse error when the vector count differs from the text count, never a mis-paired result" in {
    val short = embed(new MockHttpClient(ok(Seq(Seq(1.0)))), Seq("a", "b"))
    short.left.toOption.get.message should (include("expected 2").and(include("received 1")))
    val long = embed(new MockHttpClient(ok(Seq(Seq(1.0), Seq(2.0), Seq(3.0)))), Seq("a", "b"))
    long.isLeft shouldBe true
  }

  it should "be a parse error, not an exception, for a non-number, a non-JSON body and an empty body" in {
    Seq("""{"embeddings":{"float":[["x"]]}}""", "not json", "").foreach { raw =>
      val err = embed(new MockHttpClient(HttpResponse(200, raw, Map.empty)), Seq("a")).left.toOption.get
      withClue(raw)(err.message should startWith("Parsing error"))
    }
  }

  "usage" should "report the billed input tokens as the prompt and total tokens" in {
    val res = embed(new MockHttpClient(ok(Seq(Seq(1.0)), billed = Some(7))), Seq("a")).toOption.get
    res.usage shouldBe Some(EmbeddingUsage(promptTokens = 7, totalTokens = 7))
  }

  it should "be absent when Cohere does not report billed units" in {
    embed(new MockHttpClient(ok(Seq(Seq(1.0)))), Seq("a")).toOption.get.usage shouldBe None
    val noInput = HttpResponse(200, """{"embeddings":{"float":[[1]]},"meta":{"billed_units":{}}}""", Map.empty)
    embed(new MockHttpClient(noInput), Seq("a")).toOption.get.usage shouldBe None
  }

  it should "sum across batches, and be absent rather than wrong when a batch does not report" in {
    val texts = (1 to 100).map(i => s"t$i")
    val all   = new RecordingHttp((_, sent) => ok(vectorsFor(sent), Some(sent.size)))
    embed(all, texts).toOption.get.usage shouldBe Some(EmbeddingUsage(promptTokens = 100, totalTokens = 100))

    val partial = new RecordingHttp((i, sent) => ok(vectorsFor(sent), if (i == 0) Some(96) else None))
    embed(partial, texts).toOption.get.usage shouldBe None
  }

  "batching" should "send at most 96 texts per request, and keep the vectors in input order across requests" in {
    val texts = (1 to 200).map(i => s"t$i")
    val http  = new RecordingHttp((_, sent) => ok(vectorsFor(sent)))
    val res   = embed(http, texts).toOption.get

    CohereEmbeddingProvider.MaxTextsPerRequest shouldBe 96
    http.bodies.size shouldBe 3
    (0 until 3).map(i => http.textsOf(i).size) shouldBe Seq(96, 96, 8)
    (0 until 3).flatMap(http.textsOf) shouldBe texts
    res.embeddings.map(_.head) shouldBe texts.map(_.drop(1).toDouble)
    res.metadata("count") shouldBe "200"
  }

  it should "use one request for exactly 96 texts and two for 97" in {
    val h96 = new RecordingHttp((_, sent) => ok(vectorsFor(sent)))
    embed(h96, (1 to 96).map(i => s"t$i"))
    h96.bodies.size shouldBe 1

    val h97 = new RecordingHttp((_, sent) => ok(vectorsFor(sent)))
    embed(h97, (1 to 97).map(i => s"t$i"))
    (0 until h97.bodies.size).map(i => h97.textsOf(i).size) shouldBe Seq(96, 1)
  }

  it should "stop at the first failing request and fail the whole call" in {
    val texts = (1 to 200).map(i => s"t$i")
    val first =
      new RecordingHttp((i, sent) => if (i == 0) HttpResponse(500, "boom", Map.empty) else ok(vectorsFor(sent)))
    embed(first, texts).left.toOption.get.code shouldBe Some("500")
    first.bodies.size shouldBe 1

    val second =
      new RecordingHttp((i, sent) => if (i == 1) HttpResponse(500, "boom", Map.empty) else ok(vectorsFor(sent)))
    embed(second, texts).left.toOption.get.code shouldBe Some("500")
    second.bodies.size shouldBe 2
  }

  it should "pass empty and blank strings through unchanged and in position" in {
    val http = new MockHttpClient(ok(Seq(Seq(1.0), Seq(2.0), Seq(3.0))))
    embed(http, Seq("", "   ", "x"))
    read(http.lastBody.get)("texts").arr.map(_.str).toSeq shouldBe Seq("", "   ", "x")
  }

  "an empty input" should "return an empty response without calling Cohere, which rejects an empty texts" in {
    val http = new MockHttpClient(ok(Seq.empty))
    val res  = embed(http, Seq.empty).toOption.get
    res.embeddings shouldBe empty
    res.metadata("count") shouldBe "0"
    http.postCallCount shouldBe 0
  }

  "every HTTP status" should "map to an EmbeddingError carrying that status as its code (429 is a RateLimitError)" in {
    val table = Seq(
      400 -> "bad request",
      401 -> "Authentication failed",
      402 -> "payment",
      403 -> "forbidden",
      404 -> "no such model",
      413 -> "too large",
      422 -> "unprocessable",
      500 -> "boom",
      502 -> "bad gateway",
      503 -> "unavailable",
      504 -> "timeout"
    )
    table.foreach { case (status, fragment) =>
      val err = embed(new MockHttpClient(HttpResponse(status, fragment, Map.empty)), Seq("a")).left.toOption.get
      withClue(status.toString) {
        err shouldBe an[EmbeddingError]
        err.code shouldBe Some(status.toString)
        err.message should include(fragment)
        err.context("provider") shouldBe "cohere"
      }
    }
  }

  it should "treat a 2xx other than 200 as an error rather than parse it" in {
    val r = embed(new MockHttpClient(HttpResponse(202, body(Seq(Seq(1.0))), Map.empty)), Seq("a"))
    r.left.toOption.get.code shouldBe Some("202")
  }

  it should "truncate an enormous error body" in {
    val err = embed(new MockHttpClient(HttpResponse(500, "x" * 100000, Map.empty)), Seq("a")).left.toOption.get
    err.message.length should be < 4000
  }

  "a 429" should "be a RateLimitError that carries Retry-After when it is a positive number of seconds" in {
    val limited = HttpResponse(429, "slow down", Map("retry-after" -> Seq("30")))
    embed(new MockHttpClient(limited), Seq("a")).left.toOption.get match {
      case e: RateLimitError =>
        e.retryAfter shouldBe Some(30.seconds)
        e.provider shouldBe "cohere"
      case other => fail(s"expected a RateLimitError, got $other")
    }
  }

  it should "carry no delay when Retry-After is absent, zero, negative, or an HTTP date" in {
    val headers = Seq(Map.empty[String, Seq[String]]) ++
      Seq("0", "-5", "Wed, 21 Oct 2026 07:28:00 GMT", "soon").map(v => Map("retry-after" -> Seq(v)))
    headers.foreach { h =>
      embed(new MockHttpClient(HttpResponse(429, "slow down", h)), Seq("a")).left.toOption.get match {
        case e: RateLimitError => withClue(h.toString)(e.retryAfter shouldBe None)
        case other             => fail(s"expected a RateLimitError, got $other")
      }
    }
  }

  "secrets" should "stay out of the config's toString, a reflected 401 body, and transport errors" in {
    (cfg().toString should not).include(secretKey)

    val reflected = HttpResponse(401, s"""{"message":"invalid api token: Bearer $secretKey"}""", Map.empty)
    val authErr   = embed(new MockHttpClient(reflected), Seq("a")).left.toOption.get
    authErr.message should startWith("Authentication failed")
    (authErr.message should not).include(secretKey)

    val failing = new RecordingHttp((_, _) => HttpResponse(200, "", Map.empty)) {
      override def post(u: String, h: Map[String, String], b: String, t: FiniteDuration): Result[HttpResponse] =
        Left(NetworkError("connect timed out", None, "http://cohere-test/v2/embed"))
    }
    val err = embed(failing, Seq("a")).left.toOption.get
    err.message should startWith("HTTP request failed")
    (err.toString should not).include(secretKey)
    (err.message should not).include(secretKey)
  }

  "the real HTTP wire" should "post JSON with the Bearer key to /v2/embed, with and without a trailing slash" in {
    val seen = new AtomicReference[(String, String, String, String)]()
    LocalProviderTestServer.withServer("/v2/embed") { ex =>
      val sent = new String(ex.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
      seen.set((ex.getRequestMethod, ex.getRequestURI.getPath, ex.getRequestHeaders.getFirst("Authorization"), sent))
      LocalProviderTestServer.sendJsonResponse(ex, 200, body(Seq(Seq(0.25, 0.5)), Some(3)))
    } { base =>
      Seq(base, s"$base/", s"$base/v2").foreach { b =>
        val r = CohereEmbeddingProvider
          .fromConfig(cfg(b), CohereInputType.SearchQuery)
          .embed(EmbeddingRequest(Seq("hi"), modelCfg))
        withClue(b) {
          r.toOption.get.embeddings shouldBe Seq(Vector(0.25, 0.5))
          r.toOption.get.usage shouldBe Some(EmbeddingUsage(3, 3))
        }
        val (method, path, auth, sent) = seen.get
        method shouldBe "POST"
        path shouldBe "/v2/embed"
        auth shouldBe s"Bearer $secretKey"
        read(sent)("input_type").str shouldBe "search_query"
        read(sent)("embedding_types")(0).str shouldBe "float"
      }
    }
  }

  it should "map a real 429 with Retry-After and a real 500 from the server" in {
    LocalProviderTestServer.withServer("/v2/embed") { ex =>
      ex.getResponseHeaders.add("Retry-After", "7")
      LocalProviderTestServer.sendJsonResponse(ex, 429, """{"message":"rate limited"}""")
    } { base =>
      CohereEmbeddingProvider
        .fromConfig(cfg(base))
        .embed(EmbeddingRequest(Seq("hi"), modelCfg))
        .left
        .toOption
        .get match {
        case e: RateLimitError => e.retryAfter shouldBe Some(7.seconds)
        case other             => fail(s"expected a RateLimitError, got $other")
      }
    }
    LocalProviderTestServer.withServer("/v2/embed")(ex => LocalProviderTestServer.sendJsonResponse(ex, 500, "boom")) {
      base =>
        val err =
          CohereEmbeddingProvider.fromConfig(cfg(base)).embed(EmbeddingRequest(Seq("hi"), modelCfg)).left.toOption.get
        err.code shouldBe Some("500")
        err.message should include("boom")
    }
  }

  it should "return a transport error when nothing listens" in {
    val err = CohereEmbeddingProvider
      .fromConfig(cfg("http://localhost:1"))
      .embed(EmbeddingRequest(Seq("hi"), modelCfg))
      .left
      .toOption
      .get
    err.code shouldBe None
    err.message should startWith("HTTP request failed")
    (err.message should not).include(secretKey)
  }

  "cancellation" should "pass a CancelledError from the HTTP layer through, not turn it into an EmbeddingError" in {
    Thread.interrupted(): Unit // the flag is clear: only the error says it was cancelled
    val http = new ScriptedPost(_ => Left(CancelledError("http.POST")))

    val result = embed(http, Seq("t1"))

    result should matchPattern { case Left(_: CancelledError) => }
    http.calls.get shouldBe 1
  }

  it should "return CancelledError, with the interrupt flag set, when the HTTP layer throws an InterruptedException" in {
    Thread.interrupted(): Unit
    val http = new ScriptedPost(_ => throw new InterruptedException("cancelled"))

    val result = embed(http, Seq("t1"))
    val flag   = Thread.interrupted() // reads and clears, so it cannot leak into another test

    result should matchPattern { case Left(_: CancelledError) => }
    flag shouldBe true
  }

  it should "stop at the cancelled batch and send no more" in {
    Thread.interrupted(): Unit
    val texts = (1 to (CohereEmbeddingProvider.MaxTextsPerRequest * 3)).map(i => s"t$i")
    val http = new ScriptedPost({
      case 0 => Right(ok(vectorsFor(texts.take(CohereEmbeddingProvider.MaxTextsPerRequest))))
      case _ => Left(CancelledError("http.POST"))
    })

    val result = embed(http, texts)

    result should matchPattern { case Left(_: CancelledError) => }
    http.calls.get shouldBe 2 // the first batch answered, the second was cancelled, the third was never sent
  }

  it should "still report a failed request that was not a cancellation as one" in {
    Thread.interrupted(): Unit
    val http = new ScriptedPost(_ => Left(NetworkError("connection reset", None, "http://cohere-test")))

    val err = embed(http, Seq("t1")).left.toOption.get

    err shouldBe a[EmbeddingError]
    err.message should startWith("HTTP request failed")
  }

  "the descriptor" should "name the provider, its defaults and the dimensions of the models it knows" in {
    CohereEmbeddingProvider.id shouldBe ProviderId("cohere")
    CohereEmbeddingProvider.configSpec.defaultBaseUrl shouldBe Some("https://api.cohere.com")
    CohereEmbeddingProvider.configSpec.requiresApiKey shouldBe true
    CohereEmbeddingProvider.dimensionsOf("embed-v4.0") shouldBe Some(1536)
    CohereEmbeddingProvider.dimensionsOf("embed-english-light-v3.0") shouldBe Some(384)
    CohereEmbeddingProvider.dimensionsOf("not-a-model") shouldBe None
    CohereEmbeddingProvider.modelDimensions.values.foreach(_ should be > 0)
  }

  it should "build a provider for the SPI" in {
    CohereEmbeddingProvider.build(cfg()).isRight shouldBe true
  }

  "an error body" should "be redacted before it reaches the error or the log, whatever the status (#1674)" in {
    for {
      status <- Seq(401, 500)
      reply  <- Seq(EchoedCredentials.Text, EchoedCredentials.JsonError)
    } withClue(s"HTTP $status: ") {
      val (result, lines) =
        EchoedCredentials.logged(embed(new MockHttpClient(HttpResponse(status, reply, Map.empty)), Seq("a")))
      val err = result.left.toOption.get
      err.message should include("[REDACTED]")
      EchoedCredentials.leaked(err.message) shouldBe empty
      lines.flatMap(EchoedCredentials.leaked) shouldBe empty
    }
  }
}
