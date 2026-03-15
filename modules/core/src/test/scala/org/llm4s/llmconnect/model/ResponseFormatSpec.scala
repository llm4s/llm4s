package org.llm4s.llmconnect.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ResponseFormatSpec extends AnyFlatSpec with Matchers {

  "ResponseFormat.Json" should "be the Json case object" in {
    ResponseFormat.Json shouldBe ResponseFormat.Json
  }

  "ResponseFormat.JsonSchema" should "wrap the schema with default name and strict" in {
    val schema = ujson.Obj("type" -> "object", "properties" -> ujson.Obj("name" -> ujson.Obj("type" -> "string")))
    val fmt    = ResponseFormat.JsonSchema(schema)
    fmt.schema shouldBe schema
    fmt.name shouldBe "response"
    fmt.strict shouldBe true
  }

  it should "allow custom name and strict" in {
    val schema = ujson.Obj("type" -> "object")
    val fmt    = ResponseFormat.JsonSchema(schema, name = "my_schema", strict = false)
    fmt.name shouldBe "my_schema"
    fmt.strict shouldBe false
  }

  "ResponseFormatMapper.toOpenAIResponseFormat" should "produce json_object for Json" in {
    val result = ResponseFormatMapper.toOpenAIResponseFormat(ResponseFormat.Json)
    result shouldBe defined
    result.get.obj("type").str shouldBe "json_object"
  }

  it should "produce json_schema structure for JsonSchema with default name and strict" in {
    val schema = ujson.Obj("type" -> "object", "properties" -> ujson.Obj())
    val result = ResponseFormatMapper.toOpenAIResponseFormat(ResponseFormat.JsonSchema(schema))
    result shouldBe defined
    result.get.obj("type").str shouldBe "json_schema"
    result.get.obj("json_schema").obj("name").str shouldBe "response"
    result.get.obj("json_schema").obj("strict").bool shouldBe true
    result.get.obj("json_schema").obj("schema") shouldBe schema
  }

  it should "produce json_schema structure with custom name and strict" in {
    val schema = ujson.Obj("type" -> "object")
    val result = ResponseFormatMapper.toOpenAIResponseFormat(ResponseFormat.JsonSchema(schema, "custom", false))
    result shouldBe defined
    result.get.obj("json_schema").obj("name").str shouldBe "custom"
    result.get.obj("json_schema").obj("strict").bool shouldBe false
  }

  "CompletionOptions" should "have responseFormat None by default" in {
    CompletionOptions().responseFormat shouldBe None
  }

  it should "set responseFormat via withResponseFormat" in {
    val opts = CompletionOptions().withResponseFormat(ResponseFormat.Json)
    opts.responseFormat shouldBe Some(ResponseFormat.Json)
  }

  it should "support JsonSchema via withResponseFormat" in {
    val schema = ujson.Obj("type" -> "object")
    val opts   = CompletionOptions().withResponseFormat(ResponseFormat.JsonSchema(schema))
    opts.responseFormat shouldBe Some(ResponseFormat.JsonSchema(schema))
  }

  it should "preserve other options when using withResponseFormat" in {
    val opts = CompletionOptions(temperature = 0.5).withResponseFormat(ResponseFormat.Json)
    opts.temperature shouldBe 0.5
    opts.responseFormat shouldBe Some(ResponseFormat.Json)
  }
}
