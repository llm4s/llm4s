package org.llm4s.llmconnect.model

import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * The multimodal embedding types (#2808): values with content equality, whose contents a caller cannot change
 * after building them, built through `apply` and changed through `with*`.
 */
class MultimodalEmbeddingTypesSpec extends AnyFlatSpec with Matchers {

  "RawMediaInput" should "equal another input with the same bytes and MIME type" in {
    val a = RawMediaInput(Array[Byte](1, 2, 3), "image/png")
    val b = RawMediaInput(Array[Byte](1, 2, 3), "image/png")

    a shouldBe b
    a.hashCode shouldBe b.hashCode
    Set(a, b) should have size 1
  }

  it should "differ from an input with other bytes or another MIME type" in {
    val input = RawMediaInput(Array[Byte](1, 2, 3), "image/png")

    input should not be RawMediaInput(Array[Byte](1, 2, 4), "image/png")
    input should not be RawMediaInput(Array[Byte](1, 2, 3), "image/jpeg")
  }

  it should "not change when the array it was built from does" in {
    val bytes = Array[Byte](1, 2, 3)
    val input = RawMediaInput(bytes, "image/png")
    bytes(0) = 9

    input.data.toSeq shouldBe Seq[Byte](1, 2, 3)
    input shouldBe RawMediaInput(Array[Byte](1, 2, 3), "image/png")
  }

  it should "change each field through its setter" in {
    val raw = RawMediaInput(Array.emptyByteArray, "image/png").withData(Array[Byte](9)).withMime("image/jpeg")

    raw.data.toSeq shouldBe Seq[Byte](9)
    raw.mime shouldBe "image/jpeg"
    raw.sizeBytes shouldBe 1L
  }

  it should "not print its bytes" in {
    RawMediaInput(Array[Byte](1, 2, 3), "image/png").toString shouldBe "RawMediaInput(image/png, 3 bytes)"
  }

  "EmbeddingVector" should "equal another vector with the same values" in {
    val a = EmbeddingVector("id", Modality.Text, "m", Seq(1.0, 2.0))
    val b = EmbeddingVector("id", Modality.Text, "m", Array(1.0, 2.0).toSeq)

    a shouldBe b
    a.hashCode shouldBe b.hashCode
    a should not be EmbeddingVector("id", Modality.Text, "m", Seq(1.0, 2.5))
  }

  it should "report the number of its values as its dimensionality" in {
    EmbeddingVector("id", Modality.Text, "m", Seq(1.0, 2.0, 3.0)).dim shouldBe 3
    EmbeddingVector("id", Modality.Text, "m", Seq(1.0, 2.0, 3.0)).withValues(Seq(0.5)).dim shouldBe 1
  }

  it should "not change when the buffer it was built from does" in {
    val buffer = scala.collection.mutable.ArrayBuffer(1.0, 2.0)
    val vector = EmbeddingVector("id", Modality.Text, "m", buffer.toSeq)
    buffer(0) = 9.0

    vector.values shouldBe Seq(1.0, 2.0)
  }

  it should "default its metadata and change each field through its setter" in {
    val vector = EmbeddingVector("id", Modality.Text, "m", Seq(1.0, 2.0))
    vector.meta shouldBe empty

    val changed = vector
      .withId("other")
      .withModality(Modality.Image)
      .withModel("clip")
      .withValues(Seq(0.5))
      .withMeta(Map("k" -> "v"))

    changed shouldBe EmbeddingVector("other", Modality.Image, "clip", Seq(0.5), Map("k" -> "v"))
  }

  "MultimediaEmbeddingRequest" should "default its metadata and change each field through its setter" in {
    val model   = EmbeddingModelConfig("m", 4)
    val request = MultimediaEmbeddingRequest(Seq.empty, model, Modality.Audio)
    request.meta shouldBe empty

    val other = EmbeddingModelConfig("clip", 8)
    val image = RawMediaInput(Array[Byte](1), "image/png")
    val changed =
      request.withInputs(Seq(image)).withModel(other).withModality(Modality.Image).withMeta(Map("k" -> "v"))

    changed shouldBe MultimediaEmbeddingRequest(Seq(image), other, Modality.Image, Map("k" -> "v"))
  }

  "The multimodal embedding types" should "not expose their constructor or copy" in {
    assertTypeError("""new EmbeddingVector("id", Modality.Text, "m", Seq(1.0), Map.empty)""")
    assertTypeError("""EmbeddingVector("id", Modality.Text, "m", Seq(1.0)).copy(id = "x")""")
    assertTypeError("""new RawMediaInput(Array[Byte](), "m")""")
    assertTypeError(
      """new MultimediaEmbeddingRequest(Seq.empty, EmbeddingModelConfig("m", 4), Modality.Text, Map.empty)"""
    )
  }
}
