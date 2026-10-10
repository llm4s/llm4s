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
      (("embed-model", 1536))
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
    (client.model, client.dimensions) shouldBe (("fixture-embed-small", 256))

    val embeddings = client.embed(texts("one", "two"), JEmbeddingPurpose.QUERY).get()
    embeddings.model shouldBe "fixture-embed-small"
    embeddings.dimensions shouldBe FixtureEmbeddingProvider.Dimensions
    embeddings.vectors.asScala.map(_.toList) shouldBe Seq.fill(2)(List.fill(4)(0.5f))
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
