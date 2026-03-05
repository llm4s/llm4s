package org.llm4s.eval.dataset

import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class JsonlCodecPropertySpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 50)

  // ---- generators ----

  val genNonEmptyString: Gen[String] = Gen.nonEmptyListOf(Gen.alphaNumChar).map(_.mkString)
  val genExampleId: Gen[ExampleId]   = Gen.uuid.map(id => ExampleId(id.toString))

  // Non-null, integer-valued numbers for exact round-trip fidelity
  val genRoundTripValue: Gen[ujson.Value] = Gen.oneOf(
    Gen.alphaStr.map(ujson.Str(_)),
    Gen.choose(-1000000L, 1000000L).map(n => ujson.Num(n.toDouble)),
    Gen.oneOf(true, false).map(ujson.Bool(_))
  )

  val genTags: Gen[Set[String]]             = Gen.containerOf[Set, String](genNonEmptyString)
  val genMetadata: Gen[Map[String, String]] = Gen.mapOf(Gen.zip(genNonEmptyString, Gen.alphaStr))

  val genRoundTripExample: Gen[Example[ujson.Value, ujson.Value]] =
    for {
      id       <- genExampleId
      input    <- genRoundTripValue
      refOut   <- Gen.option(genRoundTripValue)
      tags     <- genTags
      metadata <- genMetadata
    } yield Example(id, input, refOut, tags, metadata)

  // ---- encode: structural invariants ----

  "JsonlCodec.encode" should "produce a single non-empty line with no newline characters" in {
    forAll(genRoundTripExample) { example =>
      val line = JsonlCodec.encode(example)
      line should not be empty
      (line should not).include("\n")
      (line should not).include("\r")
    }
  }

  it should "always produce valid JSON that can be re-parsed by ujson" in {
    forAll(genRoundTripExample) { example =>
      val line = JsonlCodec.encode(example)
      noException should be thrownBy ujson.read(line)
    }
  }

  it should "include all five required fields in the encoded output" in {
    forAll(genRoundTripExample) { example =>
      val json = ujson.read(JsonlCodec.encode(example))
      json.obj.contains("id") shouldBe true
      json.obj.contains("input") shouldBe true
      json.obj.contains("referenceOutput") shouldBe true
      json.obj.contains("tags") shouldBe true
      json.obj.contains("metadata") shouldBe true
    }
  }

  // ---- decode: optional-field fallbacks (Set.empty / Map.empty) ----

  "JsonlCodec.decode" should "return Some with empty tags when the 'tags' field is absent" in {
    forAll(genNonEmptyString, genRoundTripValue) { (id, input) =>
      val line = ujson.write(
        ujson.Obj("id" -> ujson.Str(id), "input" -> input, "referenceOutput" -> ujson.Null)
        // 'tags' deliberately omitted
      )
      val decoded = JsonlCodec.decode(line)
      decoded shouldBe defined
      decoded.get.tags shouldBe empty
    }
  }

  it should "return Some with empty tags when the 'tags' field is not a JSON array" in {
    forAll(genNonEmptyString, genRoundTripValue) { (id, input) =>
      val line = ujson.write(
        ujson.Obj(
          "id"              -> ujson.Str(id),
          "input"           -> input,
          "referenceOutput" -> ujson.Null,
          "tags"            -> ujson.Str("not-an-array")
        )
      )
      val decoded = JsonlCodec.decode(line)
      decoded shouldBe defined
      decoded.get.tags shouldBe empty
    }
  }

  it should "return Some with empty metadata when the 'metadata' field is absent" in {
    forAll(genNonEmptyString, genRoundTripValue) { (id, input) =>
      val line = ujson.write(
        ujson.Obj(
          "id"              -> ujson.Str(id),
          "input"           -> input,
          "referenceOutput" -> ujson.Null,
          "tags"            -> ujson.Arr()
          // 'metadata' deliberately omitted
        )
      )
      val decoded = JsonlCodec.decode(line)
      decoded shouldBe defined
      decoded.get.metadata shouldBe empty
    }
  }

  it should "return Some with empty metadata when the 'metadata' field is not a JSON object" in {
    forAll(genNonEmptyString, genRoundTripValue) { (id, input) =>
      val line = ujson.write(
        ujson.Obj(
          "id"              -> ujson.Str(id),
          "input"           -> input,
          "referenceOutput" -> ujson.Null,
          "tags"            -> ujson.Arr(),
          "metadata"        -> ujson.Str("not-an-object")
        )
      )
      val decoded = JsonlCodec.decode(line)
      decoded shouldBe defined
      decoded.get.metadata shouldBe empty
    }
  }

  // ---- decode: failure cases ----

  "JsonlCodec.decode" should "return None for any non-JSON string" in {
    forAll(Gen.alphaStr.suchThat(s => s.nonEmpty && !s.startsWith("{")))(s => JsonlCodec.decode(s) shouldBe None)
  }

  it should "return None when the 'id' field is absent" in {
    forAll(genRoundTripValue) { input =>
      val line = ujson.write(
        ujson.Obj(
          "input"           -> input,
          "referenceOutput" -> ujson.Null,
          "tags"            -> ujson.Arr(),
          "metadata"        -> ujson.Obj()
        )
      )
      JsonlCodec.decode(line) shouldBe None
    }
  }

  it should "return None when the 'input' field is absent" in {
    forAll(genNonEmptyString) { id =>
      val line = ujson.write(
        ujson.Obj(
          "id"              -> ujson.Str(id),
          "referenceOutput" -> ujson.Null,
          "tags"            -> ujson.Arr(),
          "metadata"        -> ujson.Obj()
        )
      )
      JsonlCodec.decode(line) shouldBe None
    }
  }

  // ---- encode / decode round-trip ----

  "JsonlCodec.decode(JsonlCodec.encode(e))" should "return Some(e) preserving all fields" in {
    forAll(genRoundTripExample) { example =>
      val decoded = JsonlCodec.decode(JsonlCodec.encode(example))
      decoded shouldBe defined
      decoded.get.id shouldBe example.id
      decoded.get.input shouldBe example.input
      decoded.get.referenceOutput shouldBe example.referenceOutput
      decoded.get.tags shouldBe example.tags
      decoded.get.metadata shouldBe example.metadata
    }
  }

  // ---- None referenceOutput encodes as null and decodes back as None ----

  "JsonlCodec" should "encode None referenceOutput as JSON null" in {
    forAll(genExampleId, genRoundTripValue) { (id, input) =>
      val example = Example[ujson.Value, ujson.Value](id = id, input = input, referenceOutput = None)
      val json    = ujson.read(JsonlCodec.encode(example))
      json("referenceOutput").isNull shouldBe true
    }
  }

  it should "decode a null referenceOutput field back to None" in {
    forAll(genExampleId, genRoundTripValue) { (id, input) =>
      val line = ujson.write(
        ujson.Obj(
          "id"              -> ujson.Str(id.value),
          "input"           -> input,
          "referenceOutput" -> ujson.Null,
          "tags"            -> ujson.Arr(),
          "metadata"        -> ujson.Obj()
        )
      )
      val decoded = JsonlCodec.decode(line)
      decoded shouldBe defined
      decoded.get.referenceOutput shouldBe None
    }
  }

  // ---- tags round-trip: Set ordering is irrelevant ----

  "JsonlCodec tags" should "round-trip as the exact same Set regardless of how many tags there are" in {
    forAll(genTags, genExampleId, genRoundTripValue) { (tags, id, input) =>
      val example = Example[ujson.Value, ujson.Value](id = id, input = input, referenceOutput = None, tags = tags)
      val decoded = JsonlCodec.decode(JsonlCodec.encode(example))
      decoded shouldBe defined
      decoded.get.tags shouldBe tags
    }
  }

  // ---- metadata round-trip ----

  "JsonlCodec metadata" should "round-trip string values in the metadata map" in {
    forAll(genMetadata, genExampleId, genRoundTripValue) { (metadata, id, input) =>
      val example =
        Example[ujson.Value, ujson.Value](id = id, input = input, referenceOutput = None, metadata = metadata)
      val decoded = JsonlCodec.decode(JsonlCodec.encode(example))
      decoded shouldBe defined
      decoded.get.metadata shouldBe metadata
    }
  }
}
