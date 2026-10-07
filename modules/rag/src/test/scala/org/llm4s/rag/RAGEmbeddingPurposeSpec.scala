package org.llm4s.rag

import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.model.{ EmbeddingRequest, EmbeddingResponse, InputPurpose }
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ListBuffer

/**
 * RAG always knows whether it is indexing or querying, so it says so: the texts it ingests are embedded
 * as documents and the question it answers is embedded as a query. A model that embeds the two differently
 * is only searched correctly if the side a text was embedded on matches the side it is searched from.
 */
class RAGEmbeddingPurposeSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  /** Records every request it is asked, and answers with a vector per text. */
  final private class RecordingProvider extends EmbeddingProvider {
    val requests: ListBuffer[EmbeddingRequest] = ListBuffer.empty

    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = {
      requests += request
      Right(EmbeddingResponse(request.input.map(t => Seq.tabulate(3)(i => ((t.hashCode.abs + i) % 100) / 100.0))))
    }
  }

  private def withRag(test: (RAG, RecordingProvider) => Any): Unit = {
    val provider = new RecordingProvider
    val rag = RAG
      .buildWithClient(RAGConfig.default, new EmbeddingClient(provider))
      .fold(e => fail(s"could not build the RAG: ${e.formatted}"), identity)
    try test(rag, provider)
    finally rag.close()
  }

  "RAG.query" should "embed the question as a query" in withRag { (rag, provider) =>
    rag.ingestText("Scala is a statically typed language for the JVM.", "doc-1").isRight shouldBe true
    provider.requests.clear()

    rag.query("what is scala?").isRight shouldBe true

    provider.requests.map(r => (r.input, r.purpose)).toList shouldBe
      List((Seq("what is scala?"), InputPurpose.Query))
  }

  "RAG.ingestText" should "embed what it indexes as documents" in withRag { (rag, provider) =>
    rag.ingestText("Scala is a statically typed language for the JVM.", "doc-1").isRight shouldBe true

    provider.requests should not be empty
    provider.requests.map(_.purpose).toSet shouldBe Set(InputPurpose.Document)
  }

  "a RAG that ingests and then queries" should "use each purpose on its own side only" in withRag { (rag, provider) =>
    rag.ingestText("The quick brown fox jumps over the lazy dog.", "doc-1").isRight shouldBe true
    rag.query("who jumps?").isRight shouldBe true

    val byPurpose = provider.requests.groupBy(_.purpose).view.mapValues(_.flatMap(_.input).toSet).toMap
    byPurpose(InputPurpose.Query) shouldBe Set("who jumps?")
    byPurpose(InputPurpose.Document) should not contain "who jumps?"
  }
}
