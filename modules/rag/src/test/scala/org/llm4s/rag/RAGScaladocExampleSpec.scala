package org.llm4s.rag

import org.llm4s.llmconnect.LLMClient
import org.llm4s.model.ModelRegistryTestSupport
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * The `@example` in the class Scaladoc of [[RAG]], compiled as written (#1587).
 *
 * The body of `scaladocExample` is that example with the same imports; it is never run, since building the
 * pipeline needs a real embedding provider. If it stops compiling, the Scaladoc is teaching something that no
 * longer works: change the Scaladoc and this spec together.
 */
class RAGScaladocExampleSpec extends AnyFlatSpec with Matchers {

  // Stands in for `Llm4sConfig.modelRegistryService().toOption.get` in the Scaladoc.
  private def modelRegistryService = ModelRegistryTestSupport.defaultService()

  @annotation.nowarn("msg=unused")
  private def scaladocExample(llmClient: LLMClient): Unit = {
    import org.llm4s.chunking.ChunkerFactory
    import org.llm4s.model.ModelRegistryService
    import org.llm4s.rag.RAG
    import org.llm4s.rag.RAG.RAGConfigOps

    given ModelRegistryService = modelRegistryService

    val rag = RAG
      .builder()
      .withEmbeddings("openai")
      .withChunking(ChunkerFactory.Strategy.Sentence, 800, 150)
      .build
      .toOption
      .get

    rag.ingest("./docs")

    val results = rag.query("What is X?")

    val ragWithLLM = RAG
      .builder()
      .withEmbeddings("openai")
      .withLLM(llmClient)
      .build
      .toOption
      .get

    val answer = ragWithLLM.queryWithAnswer("What is X?")
  }

  "the RAG class Scaladoc example" should "compile as written" in {
    // Reaching here means `scaladocExample` compiled.
    (scaladocExample _) should not be null
  }
}
