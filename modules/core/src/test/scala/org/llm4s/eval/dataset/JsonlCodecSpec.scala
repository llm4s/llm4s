package org.llm4s.eval.dataset

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class JsonlCodecSpec extends AnyFlatSpec with Matchers {

  "JsonlCodec.encode" should "encode example with reference output to valid JSON" in {
    val example = Example[ujson.Value, ujson.Value](
      id = ExampleId("ex-123"),
      input = ujson.Obj("query" -> ujson.Str("hello")),
      referenceOutput = Some(ujson.Str("hi")),
      tags = Set("greeting", "test"),
      metadata = Map("source" -> "unit-test")
    )

    val encoded = JsonlCodec.encode(example)
    val parsed  = ujson.read(encoded)

    parsed("id").str shouldBe "ex-123"
    parsed("input") shouldBe ujson.Obj("query" -> ujson.Str("hello"))
    parsed("referenceOutput").str shouldBe "hi"
    parsed("tags").arr.map(_.str).toSet shouldBe Set("greeting", "test")
    parsed("metadata")("source").str shouldBe "unit-test"
  }

  it should "encode None referenceOutput as null" in {
    val example = Example[ujson.Value, ujson.Value](
      id = ExampleId("ex-456"),
      input = ujson.Str("question"),
      referenceOutput = None,
      tags = Set.empty,
      metadata = Map.empty
    )

    val encoded = JsonlCodec.encode(example)
    val parsed  = ujson.read(encoded)

    parsed("referenceOutput") shouldBe ujson.Null
  }

  it should "not contain newline characters" in {
    val example = Example[ujson.Value, ujson.Value](
      id = ExampleId("ex-789"),
      input = ujson.Str("test"),
      referenceOutput = Some(ujson.Str("answer")),
      tags = Set("test"),
      metadata = Map("key" -> "value")
    )

    val encoded = JsonlCodec.encode(example)
    encoded should not contain "\n"
    encoded should not contain "\r"
  }

  "JsonlCodec.decode" should "decode valid JSONL line to example" in {
    val line =
      """{"id":"ex-123","input":{"query":"hello"},"referenceOutput":"hi","tags":["greeting"],"metadata":{"source":"test"}}"""

    val decoded = JsonlCodec.decode(line)
    decoded shouldBe defined
    decoded.get.id shouldBe ExampleId("ex-123")
    decoded.get.input shouldBe ujson.Obj("query" -> ujson.Str("hello"))
    decoded.get.referenceOutput shouldBe Some(ujson.Str("hi"))
    decoded.get.tags shouldBe Set("greeting")
    decoded.get.metadata shouldBe Map("source" -> "test")
  }

  it should "decode null referenceOutput as None" in {
    val line = """{"id":"ex-456","input":"question","referenceOutput":null,"tags":[],"metadata":{}}"""

    val decoded = JsonlCodec.decode(line)
    decoded shouldBe defined
    decoded.get.referenceOutput shouldBe None
  }

  it should "return None for malformed JSON" in {
    val line = "not valid json"
    JsonlCodec.decode(line) shouldBe None
  }

  it should "return None for missing input field" in {
    val line = """{"id":"ex-789","referenceOutput":"hi","tags":[],"metadata":{}}"""
    JsonlCodec.decode(line) shouldBe None
  }

  it should "return None for non-object JSON" in {
    val line = """"just a string""""
    JsonlCodec.decode(line) shouldBe None
  }

  "JsonlCodec round-trip" should "preserve example data" in {
    val original = Example[ujson.Value, ujson.Value](
      id = ExampleId("rt-123"),
      input = ujson.Obj("query" -> ujson.Str("test"), "context" -> ujson.Str("info")),
      referenceOutput = Some(ujson.Obj("answer" -> ujson.Str("result"), "confidence" -> ujson.Num(0.95))),
      tags = Set("qa", "test"),
      metadata = Map("source" -> "unit-test", "version" -> "1.0")
    )

    val encoded = JsonlCodec.encode(original)
    val decoded = JsonlCodec.decode(encoded)

    decoded shouldBe defined
    decoded.get.id shouldBe original.id
    decoded.get.input shouldBe original.input
    decoded.get.referenceOutput shouldBe original.referenceOutput
    decoded.get.tags shouldBe original.tags
    decoded.get.metadata shouldBe original.metadata
  }

  it should "preserve example with None referenceOutput" in {
    val original = Example[ujson.Value, ujson.Value](
      id = ExampleId("rt-none"),
      input = ujson.Str("just input"),
      referenceOutput = None,
      tags = Set.empty,
      metadata = Map.empty
    )

    val encoded = JsonlCodec.encode(original)
    val decoded = JsonlCodec.decode(encoded)

    decoded shouldBe defined
    decoded.get.referenceOutput shouldBe None
  }
}
