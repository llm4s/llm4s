package org.llm4s.rag

import org.llm4s.error.ProcessingError
import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.model.{ EmbeddingRequest, EmbeddingResponse }
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.model.ModelRegistryService
import org.llm4s.rag.transform.QueryTransformer
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.Locale
import scala.collection.mutable.ArrayBuffer
import scala.util.Using

class RAGQueryTransformerSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private class RecordingEmbeddingProvider extends EmbeddingProvider {
    val inputs: ArrayBuffer[Seq[String]] = ArrayBuffer.empty

    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = {
      inputs += request.input
      // Distinct vectors make a rewritten query retrieve a different stored chunk.
      val embeddings = request.input.map {
        case "hello" => Seq(1.0, 0.0, 0.0)
        case _       => Seq(0.0, 1.0, 0.0)
      }
      Right(EmbeddingResponse(embeddings))
    }
  }

  private def transformer(transformerName: String)(f: String => Result[String]): QueryTransformer =
    new QueryTransformer {
      override val name: String                             = transformerName
      override def transform(query: String): Result[String] = f(query)
    }

  private def upperCase: QueryTransformer =
    transformer("upper-case")(query => Right(query.toUpperCase(Locale.ROOT)))

  private def withRag(config: RAGConfig)(f: (RAG, RecordingEmbeddingProvider) => Unit): Unit = {
    val provider = new RecordingEmbeddingProvider
    val rag = RAG
      .buildWithClient(
        config.inMemory.vectorOnly.withEmbeddingDimensions(3),
        new EmbeddingClient(provider)
      )
      .value
    Using.resource(rag)(r => f(r, provider))
  }

  "RAGConfig.queryTransformers" should "be empty by default" in {
    RAGConfig.default.queryTransformers shouldBe empty
  }

  "RAGConfig.withQueryTransformer" should "append transformers in order" in {
    val first  = transformer("first")(query => Right(s"$query first"))
    val second = upperCase

    val config = RAGConfig.default.withQueryTransformer(first).withQueryTransformer(second)

    config.queryTransformers shouldBe Seq(first, second)
    RAGConfig.default.queryTransformers shouldBe empty
  }

  "RAGConfig.withQueryTransformers" should "replace previously configured transformers" in {
    val original    = upperCase
    val replacement = transformer("replacement")(query => Right(s"$query replacement"))
    val config      = RAGConfig.default.withQueryTransformer(original)

    config.withQueryTransformers(Seq(replacement)).queryTransformers shouldBe Seq(replacement)
    config.queryTransformers shouldBe Seq(original)
  }

  "RAG.query" should "embed the upper-cased query and retrieve its matching chunk" in {
    withRag(RAGConfig.default.withQueryTransformer(upperCase)) { (rag, provider) =>
      rag.ingestChunks("lower-case", Seq("hello")) shouldBe Right(1)
      rag.ingestChunks("upper-case", Seq("HELLO")) shouldBe Right(1)
      provider.inputs.clear()

      val results = rag.query("hello", topK = Some(1)).value

      provider.inputs.toSeq shouldBe Seq(Seq("HELLO"))
      results.map(_.content) shouldBe Seq("HELLO")
    }
  }

  it should "apply both transformers in order before embedding" in {
    val suffix = transformer("suffix")(query => Right(s"$query search"))
    val config = RAGConfig.default.withQueryTransformer(suffix).withQueryTransformer(upperCase)

    withRag(config) { (rag, provider) =>
      rag.query("hello") shouldBe Right(Seq.empty)

      // Reversing the chain would produce "HELLO search" instead.
      provider.inputs.toSeq shouldBe Seq(Seq("HELLO SEARCH"))
    }
  }

  it should "return the transform error without calling later transformers or the embedding provider" in {
    val error        = ProcessingError("query-transform", "Cannot rewrite the query")
    val failedInputs = ArrayBuffer.empty[String]
    val failing = transformer("failing") { query =>
      failedInputs += query
      Left(error)
    }
    val unreachable = transformer("unreachable")(_ => fail("A transformer after a failure must not run"))
    val config      = RAGConfig.default.withQueryTransformers(Seq(upperCase, failing, unreachable))

    withRag(config) { (rag, provider) =>
      rag.query("hello") shouldBe Left(error)

      failedInputs.toSeq shouldBe Seq("HELLO")
      provider.inputs shouldBe empty
    }
  }

  it should "embed the original query unchanged when no transformers are configured" in {
    withRag(RAGConfig.default) { (rag, provider) =>
      rag.ingestChunks("lower-case", Seq("hello")) shouldBe Right(1)
      rag.ingestChunks("upper-case", Seq("HELLO")) shouldBe Right(1)
      provider.inputs.clear()

      val results = rag.query("hello", topK = Some(1)).value

      provider.inputs.toSeq shouldBe Seq(Seq("hello"))
      results.map(_.content) shouldBe Seq("hello")
    }
  }
}
