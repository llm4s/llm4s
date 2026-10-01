package org.llm4s.llmconnect.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ResponseFormatMapperSpec extends AnyFlatSpec with Matchers {

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
}
