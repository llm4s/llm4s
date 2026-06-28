package org.llm4s.samples.rag.modular

import org.llm4s.error.ConfigurationError
import org.scalatest.EitherValues
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class ModularRAGExampleSpec extends AnyFunSuite with Matchers with EitherValues {

  test("toEmbeddingProvider should map supported providers and reject unknown ones") {
    ModularRAGSupport.toEmbeddingProvider("openai").toOption.map(_.name) shouldBe Some("openai")
    ModularRAGSupport.toEmbeddingProvider("voyage").toOption.map(_.name) shouldBe Some("voyage")
    ModularRAGSupport.toEmbeddingProvider("ollama").toOption.map(_.name) shouldBe Some("ollama")

    val unknown = ModularRAGSupport.toEmbeddingProvider("not-a-provider")
    unknown.left.value shouldBe a[ConfigurationError]
  }

  test("seedCorpus should ingest all seeded documents") {
    final class RecordingIngestionModule extends IngestionModule {
      var ingestedDocumentIds: Vector[String] = Vector.empty

      override def ingestPath(path: String, metadata: Map[String, String]) =
        Right(0)

      override def ingestText(
        documentId: String,
        content: String,
        metadata: Map[String, String]
      ) = {
        ingestedDocumentIds = ingestedDocumentIds :+ documentId
        Right(1)
      }
    }

    val ingestion = new RecordingIngestionModule
    val result    = ModularRAGSupport.seedCorpus(ingestion)

    result shouldBe Right(3)
    (ingestion.ingestedDocumentIds should contain).theSameElementsInOrderAs(
      Seq(
        "rag-intro",
        "llm4s-reliability",
        "architecture"
      )
    )
  }
}
