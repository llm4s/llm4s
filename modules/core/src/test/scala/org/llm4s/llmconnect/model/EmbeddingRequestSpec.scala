package org.llm4s.llmconnect.model

import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** [[EmbeddingRequest]] and [[InputPurpose]]: what the request says about the texts it carries. */
class EmbeddingRequestSpec extends AnyFlatSpec with Matchers {

  private val model = EmbeddingModelConfig("test-model", 8)

  "EmbeddingRequest" should "embed documents unless told otherwise" in {
    EmbeddingRequest(Seq("a"), model).purpose shouldBe InputPurpose.Document
    EmbeddingRequest(input = Seq("a"), model = model).purpose shouldBe InputPurpose.Document
  }

  it should "carry the purpose it is given, positionally or by name" in {
    EmbeddingRequest(Seq("a"), model, InputPurpose.Query).purpose shouldBe InputPurpose.Query
    EmbeddingRequest(input = Seq("a"), model = model, purpose = InputPurpose.Query).purpose shouldBe InputPurpose.Query
  }

  it should "change one field at a time, leaving the others as they were" in {
    val base = EmbeddingRequest(Seq("a", "b"), model, InputPurpose.Query)

    base.withPurpose(InputPurpose.Document) shouldBe EmbeddingRequest(Seq("a", "b"), model, InputPurpose.Document)
    base.withInput(Seq("c")) shouldBe EmbeddingRequest(Seq("c"), model, InputPurpose.Query)
    base.withModel(EmbeddingModelConfig("other", 4)) shouldBe
      EmbeddingRequest(Seq("a", "b"), EmbeddingModelConfig("other", 4), InputPurpose.Query)
  }

  it should "treat requests that differ only in purpose as different requests" in {
    val doc   = EmbeddingRequest(Seq("a"), model)
    val query = doc.withPurpose(InputPurpose.Query)

    (doc should not).equal(query)
    doc shouldBe EmbeddingRequest(Seq("a"), model, InputPurpose.Document)
  }

  it should "keep its constructor and copy private, so a later field cannot break a caller" in {
    val base = EmbeddingRequest(Seq("a"), model)

    base.withPurpose(InputPurpose.Query).purpose shouldBe InputPurpose.Query // the supported way
    scala.compiletime.testing.typeCheckErrors("base.copy(purpose = InputPurpose.Query)") should not be empty
    scala.compiletime.testing.typeCheckErrors(
      "new EmbeddingRequest(Seq(\"a\"), model, InputPurpose.Query)"
    ) should not be empty
  }

  "InputPurpose" should "have exactly the two sides of retrieval" in {
    InputPurpose.values.toSet shouldBe Set(InputPurpose.Document, InputPurpose.Query)
  }
}
