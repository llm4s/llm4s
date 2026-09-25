package org.llm4s.samples.rag.modular

import org.scalatest.EitherValues
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class ModularRAGExampleSpec extends AnyFunSuite with Matchers with EitherValues {

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
