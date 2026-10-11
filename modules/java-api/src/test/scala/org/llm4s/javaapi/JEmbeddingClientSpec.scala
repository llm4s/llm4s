package org.llm4s.javaapi

import org.llm4s.error.{ CancelledError, LLMError, NetworkError, ProcessingError, ValidationError }
import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.model.{ EmbeddingError, EmbeddingRequest, EmbeddingResponse, InputPurpose }
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.model.ModelRegistryService
import org.llm4s.testutil.FixtureEmbeddingProvider
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.{ Arrays, OptionalInt }
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*

/**
 * `JEmbeddingClient`, `JEmbeddings` and `Llm4s.createDefaultEmbeddingClient` (#1490), against an embedding provider that
 * records each request it is sent and answers what the test scripts.
 */
class JEmbeddingClientSpec extends AnyFlatSpec with Matchers {

  /** A provider that records every request and answers `reply` to it. */
  final private class Recording(reply: EmbeddingRequest => Result[EmbeddingResponse]) extends EmbeddingProvider {
    val sent: ArrayBuffer[EmbeddingRequest]                         = ArrayBuffer.empty
    def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = { sent += request; reply(request) }
  }

  /** A reply of one vector per text: the text's length, then its index. */
  private def perText(request: EmbeddingRequest): Result[EmbeddingResponse] =
    Right(
      EmbeddingResponse(
        embeddings = request.input.zipWithIndex.map((text, i) => Seq(text.length.toDouble, i.toDouble)),
        metadata = Map("model" -> "embed-model-2025")
      )
    )

  private val configured = EmbeddingModelConfig("embed-model", 1536)

  private def clientOver(provider: EmbeddingProvider): JEmbeddingClient =
    new JEmbeddingClient(new EmbeddingClient(provider)(using ModelRegistryService.fromModels(Nil)), configured)

  private def failingWith(error: LLMError): JEmbeddingClient = clientOver(new Recording(_ => Left(error)))

  private def texts(values: String*): java.util.List[String] = java.util.List.of(values*)

  "embed" should "send every text in one request, in order, and return one vector per text in the same order" in {
    val provider = new Recording(perText)
    val result   = clientOver(provider).embed(texts("a", "bb", "ccc"))

    provider.sent.map(_.input) shouldBe Seq(Seq("a", "bb", "ccc"))
    provider.sent.head.model shouldBe configured
    result.get().vectors.asScala.map(_.toList) shouldBe Seq(List(1f, 0f), List(2f, 1f), List(3f, 2f))
  }

  it should "send a batch of any size whole, never split or truncated" in {
    val provider = new Recording(perText)
    val many     = (1 to 250).map(i => s"text $i")
    val result   = clientOver(provider).embed(java.util.List.copyOf(many.asJava)).get()

    provider.sent.map(_.input) shouldBe Seq(many)
    result.vectors.size shouldBe 250
    result.vectors.get(249)(1) shouldBe 249f
  }

  it should "embed documents when no purpose is given, as core does, and pass each purpose to the request" in {
    val provider = new Recording(perText)
    val client   = clientOver(provider)
    client.embed(texts("doc")).isSuccess shouldBe true
    client.embed(texts("question"), JEmbeddingPurpose.QUERY).isSuccess shouldBe true
    client.embed(texts("doc"), JEmbeddingPurpose.DOCUMENT).isSuccess shouldBe true

    provider.sent.map(_.purpose) shouldBe Seq(InputPurpose.Document, InputPurpose.Query, InputPurpose.Document)
    EmbeddingRequest(Seq("x"), configured).purpose shouldBe InputPurpose.Document
  }

  it should "give the model the provider named, the configured one when it named none, and the vectors' length" in {
    clientOver(new Recording(perText)).embed(texts("a")).get().model shouldBe "embed-model-2025"
    clientOver(new Recording(perText)).embed(texts("a")).get().dimensions shouldBe 2

    def naming(metadata: Map[String, String]) =
      clientOver(new Recording(_ => Right(EmbeddingResponse(Seq(Seq(1.0)), metadata = metadata)))).embed(texts("a"))
    naming(Map.empty).get().model shouldBe "embed-model"
    naming(Map("model" -> "")).get().model shouldBe "embed-model"
  }

  it should "keep each double as its nearest float" in {
    val precise = Seq(0.123456789012345, -1e-3, 1.0 / 3)
    val vector = clientOver(new Recording(_ => Right(EmbeddingResponse(Seq(precise)))))
      .embed(texts("a"))
      .get()
      .vectors
      .get(0)
    vector.toList shouldBe precise.map(_.toFloat).toList
  }

  it should "return no vectors and send no request for an empty list" in {
    val provider = new Recording(perText)
    val result   = clientOver(provider).embed(texts()).get()

    provider.sent shouldBe empty
    result.vectors.isEmpty shouldBe true
    (result.model, result.dimensions) shouldBe (("embed-model", 1536))
  }

  it should "read the list once, so a list changed afterwards does not change what was sent" in {
    val provider = new Recording(perText)
    val list     = new java.util.ArrayList[String](texts("a", "b"))
    val result   = clientOver(provider).embed(list).get()
    list.add("c")
    provider.sent.map(_.input) shouldBe Seq(Seq("a", "b"))
    result.vectors.size shouldBe 2
  }

  it should "fail with VALIDATION, send nothing and throw nothing for a null list, text or purpose" in {
    val provider = new Recording(perText)
    val client   = clientOver(provider)
    val withNull = new java.util.ArrayList[String](texts("fine", "also fine"))
    withNull.add(1, null)

    val failures = List(
      client.embed(null),
      client.embed(null, JEmbeddingPurpose.QUERY),
      client.embed(texts("fine"), null),
      client.embed(withNull)
    )
    failures.foreach(_.getError().getKind shouldBe LlmErrorKind.VALIDATION)
    failures.foreach(_.getError().error shouldBe a[ValidationError])
    failures.map(_.getError().getMessage).zip(List("texts", "texts", "purpose", "texts[1]")).foreach {
      case (message, field) => message should include(field)
    }
    provider.sent shouldBe empty

    EmbeddingCheck.nulls(client).asScala.toList.map(_.takeWhile(_ != ':')) shouldBe List.fill(4)("VALIDATION")
  }

  it should "map an embedding provider's error to a failed result whose kind is the one its status means" in {
    def failure(error: LLMError): LlmException = failingWith(error).embed(texts("a")).getError()

    val unauthorised = failure(EmbeddingError(Some("401"), "Authentication failed: bad key", "voyage"))
    (unauthorised.getKind, unauthorised.getStatusCode, unauthorised.getMessage) shouldBe
      ((LlmErrorKind.AUTHENTICATION, OptionalInt.of(401), "Authentication failed: bad key"))
    failure(EmbeddingError(Some("429"), "slow down", "jina")).getKind shouldBe LlmErrorKind.RATE_LIMIT
    failure(EmbeddingError(Some("400"), "input too long", "openai")).getKind shouldBe LlmErrorKind.VALIDATION
    failure(EmbeddingError(Some("503"), "overloaded", "cohere")).getKind shouldBe LlmErrorKind.SERVICE
    failure(EmbeddingError(None, "HTTP request failed: refused", "ollama")).getKind shouldBe LlmErrorKind.OTHER
    failure(NetworkError("down", None, "http://x")).getKind shouldBe LlmErrorKind.NETWORK
  }

  it should "turn an exception the provider throws into a failed result carrying it, not a throw" in {
    val boom   = new IllegalStateException("provider bug")
    val result = clientOver(new Recording(_ => throw boom)).embed(texts("a"))
    result.isFailure shouldBe true
    result.getError().getCause shouldBe boom
  }

  it should "turn an interrupt while blocked into a CANCELLED result, the interrupt flag left set" in {
    val result           = clientOver(new Recording(_ => throw new InterruptedException("stop"))).embed(texts("a"))
    val stillInterrupted = Thread.interrupted() // reads and clears the flag, so later tests run uninterrupted
    stillInterrupted shouldBe true
    result.getError().getKind shouldBe LlmErrorKind.CANCELLED
    result.getError().error shouldBe a[CancelledError]
  }

  it should "refuse a reply that does not hold one vector per text, or holds vectors of several lengths" in {
    def replying(vectors: Seq[Seq[Double]]): LlmException =
      clientOver(new Recording(_ => Right(EmbeddingResponse(vectors)))).embed(texts("a", "b")).getError()

    val tooFew = replying(Seq(Seq(1.0, 2.0)))
    tooFew.error shouldBe a[ProcessingError]
    tooFew.getMessage should include("1 vectors for 2 texts")
    replying(Nil).getMessage should include("0 vectors for 2 texts")
    replying(Seq(Seq(1.0), Seq(1.0), Seq(1.0))).getMessage should include("3 vectors for 2 texts")
    val uneven = replying(Seq(Seq(1.0, 2.0), Seq(1.0)))
    uneven.getMessage should include("several lengths: 2, 1")
    uneven.getKind shouldBe LlmErrorKind.OTHER
  }

  it should "describe itself by its model and dimensions" in {
    clientOver(new Recording(perText)).toString shouldBe "JEmbeddingClient(embed-model, 1536 dimensions)"
    (clientOver(new Recording(perText)).model, clientOver(new Recording(perText)).dimensions) shouldBe
      (("embed-model", OptionalInt.of(1536)))
  }

  "embed, called from Java source" should "compile with Java types only and read the result back" in {
    EmbeddingCheck.embedAndRead(clientOver(new Recording(perText))).asScala.toList shouldBe List(
      "client:embed-model:1536",
      "model:embed-model-2025",
      "dimensions:2",
      "vector:[5.0, 0.0]",
      "vector:[6.0, 1.0]",
      "queries:1",
      s"similarity:${JEmbeddings.cosineSimilarity(Array(5f, 0f), Array(6f, 1f))}"
    )
    JEmbeddingPurpose.values.toList.map(EmbeddingCheck.describe) shouldBe List("document", "query")
  }

  "Llm4s.createDefaultEmbeddingClient" should "build the client for llm4s.embeddings.model, its dimensions declared" in {
    // this module's test application.conf selects core's canned fixture: four-long vectors of 0.5, no network
    val created = Llm4s.createDefaultEmbeddingClient()
    created.isSuccess shouldBe true
    val client = created.get()
    (client.model, client.dimensions) shouldBe (("fixture-embed-small", OptionalInt.of(256)))

    val embeddings = client.embed(texts("one", "two"), JEmbeddingPurpose.QUERY).get()
    embeddings.model shouldBe "fixture-embed-small"
    embeddings.dimensions shouldBe FixtureEmbeddingProvider.Dimensions
    embeddings.vectors.asScala.map(_.toList) shouldBe Seq.fill(2)(List.fill(4)(0.5f))
  }

  // --- #1781 ---

  /** `ReferenceConfig.withEnv`: the application's `hocon` over every module's reference.conf, with no environment. */
  private def appConf(hocon: String) = org.llm4s.config.ReferenceConfig.withEnv(hocon, Map.empty)

  /** A loaded config's client as the facade hands it out, a failure read as Java reads it. */
  private def created(
    loaded: Result[(String, org.llm4s.llmconnect.config.EmbeddingProviderConfig)]
  ): Either[LlmException, JEmbeddingClient] =
    EmbeddingClients.from(loaded).left.map(new LlmException(_))

  "Llm4s.createEmbeddingClient" should "build the client for the model it names, with its key from configuration" in {
    // llm4s.embeddings.model names fixture-embed-small; the factory asks for another model, its key from the same block
    val client = Llm4s.createEmbeddingClient("fixtureembedding/fixture-embed-large").get()
    (client.model, client.dimensions) shouldBe (("fixture-embed-large", OptionalInt.of(1024)))
    client.embed(texts("one")).get().model shouldBe "fixture-embed-large"
    // an alias of the provider, and a provider name in another case, as llm4s.embeddings.model takes them
    Llm4s.createEmbeddingClient("Fixture-Embedding-AI/fixture-embed-small").get().model shouldBe "fixture-embed-small"
  }

  it should "need no llm4s.embeddings.model at all" in {
    val loaded = org.llm4s.config.Llm4sConfig.embeddingsFor(
      appConf("""llm4s.embeddings.fixtureembedding.apiKey = "k""""),
      "fixtureembedding/fixture-embed-small"
    )
    created(loaded).map(_.model) shouldBe Right("fixture-embed-small")
  }

  it should "refuse a null model as VALIDATION, and a malformed model or an unknown provider as CONFIGURATION" in {
    val refusedNull = Llm4s.createEmbeddingClient(null).getError()
    refusedNull.getKind shouldBe LlmErrorKind.VALIDATION
    refusedNull.getMessage should include("'model' is required")
    Seq("text-embedding-3-small", "openai/", "/text-embedding-3-small", "", "  ").foreach { malformed =>
      val error = Llm4s.createEmbeddingClient(malformed).getError()
      withClue(malformed)(error.getKind shouldBe LlmErrorKind.CONFIGURATION)
      error.getMessage should include("Expected 'provider/model'")
    }
    val unknown = Llm4s.createEmbeddingClient("nosuchprovider/m").getError()
    unknown.getKind shouldBe LlmErrorKind.CONFIGURATION
    unknown.getMessage should include("'nosuchprovider' is not registered")
  }

  it should "refuse a provider whose key is not configured, as CONFIGURATION naming the variable" in {
    val error =
      created(org.llm4s.config.Llm4sConfig.embeddingsFor(appConf(""), "fixtureembedding/fixture-embed-small")).left
        .getOrElse(fail("expected a missing-key failure"))
    error.getKind shouldBe LlmErrorKind.CONFIGURATION
    error.getMessage should include(FixtureEmbeddingProvider.ApiKeyEnv)
  }

  it should "be called from Java source, reading declared and undeclared dimensions and each failure's kind" in {
    EmbeddingCheck
      .createFor(
        java.util.List.of("fixtureembedding/fixture-embed-large", "fixtureembedding/my-own-model", "no-slash", "x/y")
      )
      .asScala
      .toList shouldBe List(
      "fixtureembedding/fixture-embed-large -> fixture-embed-large, 1024 dimensions",
      s"fixtureembedding/my-own-model -> my-own-model, undeclared, made ${FixtureEmbeddingProvider.Dimensions}",
      "no-slash -> CONFIGURATION",
      "x/y -> CONFIGURATION"
    )
  }

  "a model its provider module does not list" should "be created, its dimensions empty and its vectors as long as made" in {
    // the fixture declares fixture-embed-small and fixture-embed-large only; its vectors are four long
    val client = Llm4s.createEmbeddingClient("fixtureembedding/my-own-model").get()
    client.dimensions shouldBe OptionalInt.empty()
    client.toString shouldBe "JEmbeddingClient(my-own-model, dimensions undeclared)"
    client.embed(texts("a", "b")).get().dimensions shouldBe FixtureEmbeddingProvider.Dimensions
    // an empty batch calls no provider, so it has no length to report
    client.embed(texts()).get().dimensions shouldBe 0
  }

  it should "work end to end with Ollama, whose module lists no bge-m3: the request names the model and asks no size" in {
    import org.llm4s.testkit.LocalProviderTestServer.{ sendJsonResponse, withServer }
    val asked = new java.util.concurrent.atomic.AtomicReference[ujson.Value]()
    withServer("/api/embeddings") { exchange =>
      asked.set(ujson.read(new String(exchange.getRequestBody.readAllBytes(), "UTF-8")))
      sendJsonResponse(exchange, 200, """{"embedding":[0.1,0.2,0.3,0.4,0.5]}""")
    } { baseUrl =>
      val loaded = org.llm4s.config.Llm4sConfig.embeddingsFor(
        appConf(s"""llm4s.embeddings.ollama.baseUrl = "$baseUrl""""),
        "ollama/bge-m3"
      )
      val client = created(loaded).getOrElse(fail("expected a client"))
      client.dimensions shouldBe OptionalInt.empty()
      val embeddings = client.embed(texts("hello")).get()
      (embeddings.model, embeddings.dimensions) shouldBe (("bge-m3", 5))
    }
    asked.get.obj.keySet shouldBe Set("model", "prompt")
    asked.get()("model").str shouldBe "bge-m3"
  }

  "an embedding provider that cannot be reached" should "fail the call as NETWORK, not OTHER" in {
    val socket = new java.net.ServerSocket(0)
    val port   = socket.getLocalPort
    socket.close()
    val loaded = org.llm4s.config.Llm4sConfig.embeddingsFor(
      appConf(s"""llm4s.embeddings.ollama.baseUrl = "http://127.0.0.1:$port""""),
      "ollama/bge-m3"
    )
    val error = created(loaded).getOrElse(fail("expected a client")).embed(texts("hello")).getError()
    error.getKind shouldBe LlmErrorKind.NETWORK
    error.getMessage should include(s"127.0.0.1:$port")
  }

  "Llm4s.createDefaultEmbeddingClient's loading" should "fail as CONFIGURATION, saying what to set, with no model configured" in {
    val error = created(org.llm4s.config.Llm4sConfig.embeddings(appConf(""))).left
      .getOrElse(fail("expected a missing-model failure"))
    error.getKind shouldBe LlmErrorKind.CONFIGURATION
    error.getMessage should include("EMBEDDING_MODEL")
  }

  it should "fail as CONFIGURATION, naming the variable, when the model's provider has no key" in {
    val error = created(
      org.llm4s.config.Llm4sConfig.embeddings(
        appConf("""llm4s.embeddings.model = "fixtureembedding/fixture-embed-small"""")
      )
    ).left.getOrElse(fail("expected a missing-key failure"))
    error.getKind shouldBe LlmErrorKind.CONFIGURATION
    error.getMessage should include(FixtureEmbeddingProvider.ApiKeyEnv)
  }

  it should "build a client for a model its provider module does not list" in {
    val client = created(
      org.llm4s.config.Llm4sConfig.embeddings(
        appConf("""llm4s.embeddings { model = "fixtureembedding/my-own-model", fixtureembedding.apiKey = "k" }""")
      )
    ).getOrElse(fail("expected a client"))
    (client.model, client.dimensions) shouldBe (("my-own-model", OptionalInt.empty()))
  }

  "JEmbeddings.vectors" should "hand out copies: changing a returned array or list changes nothing held" in {
    val embeddings = JEmbeddings.of("m", 2, Array(Array(1f, 2f), Array(3f, 4f)))
    val first      = embeddings.vectors
    first.get(0)(0) = 99f
    an[UnsupportedOperationException] should be thrownBy first.add(Array(0f, 0f))
    embeddings.vectors.get(0).toList shouldBe List(1f, 2f)
    embeddings.vectors.get(0) should not be theSameInstanceAs(embeddings.vectors.get(0))
  }

  "JEmbeddings" should "be a value: equal when model, dimensions and every component are, whatever the arrays" in {
    def of(model: String, dims: Int, vectors: Array[Float]*) = JEmbeddings.of(model, dims, vectors.toArray)
    val one                                                  = of("m", 2, Array(1f, 2f), Array(3f, 4f))

    one shouldBe of("m", 2, Array(1f, 2f), Array(3f, 4f))
    one.hashCode shouldBe of("m", 2, Array(1f, 2f), Array(3f, 4f)).hashCode
    one should not be of("n", 2, Array(1f, 2f), Array(3f, 4f))
    one should not be of("m", 3, Array(1f, 2f), Array(3f, 4f))
    one should not be of("m", 2, Array(1f, 2f), Array(3f, 5f))
    one should not be of("m", 2, Array(1f, 2f))
    one should not be of("m", 2, Array(1f, 2f), Array(3f, 4f), Array(5f, 6f))
    one.equals("m") shouldBe false
    one.toString shouldBe "JEmbeddings(m, 2 vectors of 2 dimensions)"
  }

  "JEmbeddings.cosineSimilarity" should "give 1 for the same direction, -1 for the opposite and 0 for orthogonal" in {
    JEmbeddings.cosineSimilarity(Array(1f, 2f, 3f), Array(2f, 4f, 6f)) shouldBe 1.0 +- 1e-12
    JEmbeddings.cosineSimilarity(Array(1f, 2f, 3f), Array(-1f, -2f, -3f)) shouldBe -1.0 +- 1e-12
    JEmbeddings.cosineSimilarity(Array(1f, 0f), Array(0f, 5f)) shouldBe 0.0
    JEmbeddings.cosineSimilarity(Array(3f, 4f), Array(4f, 3f)) shouldBe 0.96
  }

  it should "stay within -1..1 whatever the rounding" in {
    val random = new scala.util.Random(1490)
    (1 to 200).foreach { _ =>
      val v       = Array.fill(1 + random.nextInt(1536))(random.nextFloat() * 2 - 1)
      val negated = v.map(-_)
      JEmbeddings.cosineSimilarity(v, v) should ((be <= 1.0).and(be >= 1.0 - 1e-9))
      JEmbeddings.cosineSimilarity(v, negated) should ((be >= -1.0).and(be <= -1.0 + 1e-9))
    }
  }

  it should "give 0 when either vector is zero, or both are empty, and NaN for a NaN component" in {
    JEmbeddings.cosineSimilarity(Array(0f, 0f), Array(1f, 2f)) shouldBe 0.0
    JEmbeddings.cosineSimilarity(Array(1f, 2f), Array(0f, 0f)) shouldBe 0.0
    JEmbeddings.cosineSimilarity(Array(0f, 0f), Array(0f, 0f)) shouldBe 0.0
    JEmbeddings.cosineSimilarity(Array.empty[Float], Array.empty[Float]) shouldBe 0.0
    JEmbeddings.cosineSimilarity(Array(Float.NaN, 1f), Array(1f, 1f)).isNaN shouldBe true
    JEmbeddings.cosineSimilarity(Array(Float.PositiveInfinity, 1f), Array(1f, 1f)).isNaN shouldBe true
    JEmbeddings.cosineSimilarity(Array(Float.NaN, 1f), Array(0f, 0f)) shouldBe 0.0
  }

  it should "throw IllegalArgumentException for vectors of different lengths and NullPointerException for null" in {
    (the[IllegalArgumentException] thrownBy JEmbeddings.cosineSimilarity(Array(1f, 2f), Array(1f)) should have)
      .message("vectors must have the same length, got 2 and 1")
    (the[NullPointerException] thrownBy JEmbeddings.cosineSimilarity(null, Array(1f)) should have)
      .message("a must not be null")
    (the[NullPointerException] thrownBy JEmbeddings.cosineSimilarity(Array(1f), null) should have)
      .message("b must not be null")
    EmbeddingCheck.cosineMisuse().asScala.toList shouldBe List(
      "npe:a must not be null",
      "iae:vectors must have the same length, got 2 and 1",
      "zero:0.0"
    )
  }

  it should "not change either vector" in {
    val (a, b) = (Array(1f, 2f), Array(3f, 4f))
    JEmbeddings.cosineSimilarity(a, b)
    (Arrays.equals(a, Array(1f, 2f)), Arrays.equals(b, Array(3f, 4f))) shouldBe ((true, true))
  }
}
