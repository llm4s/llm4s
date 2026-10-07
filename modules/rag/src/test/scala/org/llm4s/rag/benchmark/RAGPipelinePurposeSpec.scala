package org.llm4s.rag.benchmark

import org.llm4s.llmconnect.{ EmbeddingClient, LLMClient }
import org.llm4s.llmconnect.model.*
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result
import org.llm4s.vectorstore.{ KeywordIndex, VectorStoreFactory }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ListBuffer

/** The benchmark pipeline embeds what it indexes as documents and a search as a query. */
class RAGPipelinePurposeSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  final private class RecordingProvider extends EmbeddingProvider {
    val requests: ListBuffer[EmbeddingRequest] = ListBuffer.empty

    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = {
      requests += request
      Right(EmbeddingResponse(request.input.map(t => Seq.tabulate(4)(i => ((t.hashCode.abs + i) % 100) / 100.0))))
    }
  }

  /** The pipeline never completes anything in these tests; it only needs a client to be built with. */
  private object NoopLLM extends LLMClient {
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      Right(
        Completion(
          id = "noop",
          created = 0L,
          content = "",
          model = "noop",
          message = AssistantMessage(contentOpt = Some("")),
          usage = None
        )
      )
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 512
  }

  private def withPipeline(test: (RAGPipeline, RecordingProvider) => Any): Unit = {
    val provider = new RecordingProvider
    val vectors  = VectorStoreFactory.inMemory().fold(e => fail(e.formatted), identity)
    val keywords = KeywordIndex.inMemory().fold(e => fail(e.formatted), identity)
    val pipeline =
      RAGPipeline.withStores(RAGExperimentConfig.default, NoopLLM, new EmbeddingClient(provider), vectors, keywords)
    try test(pipeline, provider)
    finally pipeline.close()
  }

  "RAGPipeline.search" should "embed the query as a query" in withPipeline { (pipeline, provider) =>
    pipeline.indexDocument("doc1", "Scala is a statically typed language for the JVM.").isRight shouldBe true
    provider.requests.clear()

    pipeline.search("what is scala?").isRight shouldBe true

    provider.requests.map(r => (r.input, r.purpose)).toList shouldBe
      List((Seq("what is scala?"), InputPurpose.Query))
  }

  "RAGPipeline.indexDocument" should "embed the chunks it indexes as documents" in withPipeline {
    (pipeline, provider) =>
      pipeline.indexDocument("doc1", "Scala is a statically typed language for the JVM.").isRight shouldBe true

      provider.requests should not be empty
      provider.requests.map(_.purpose).toSet shouldBe Set(InputPurpose.Document)
  }
}
