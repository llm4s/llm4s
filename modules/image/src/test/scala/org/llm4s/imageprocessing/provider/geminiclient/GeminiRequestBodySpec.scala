package org.llm4s.imageprocessing.provider.geminiclient

import org.llm4s.imageprocessing.MediaType
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ujson.read

class GeminiRequestBodySpec extends AnyFlatSpec with Matchers {

  "GeminiRequestBody.serialize" should "produce valid JSON with text and image parts" in {
    val json   = GeminiRequestBody.serialize("Describe this", "base64data==", MediaType.Png)
    val parsed = read(json)

    parsed("contents").arr should have size 1
    val parts = parsed("contents")(0)("parts").arr
    parts should have size 2
    parts(0)("text").str shouldBe "Describe this"
    parts(1)("inlineData")("mimeType").str shouldBe "image/png"
    parts(1)("inlineData")("data").str shouldBe "base64data=="
  }

  it should "include the correct MIME type for JPEG" in {
    val json   = GeminiRequestBody.serialize("prompt", "data", MediaType.Jpeg)
    val parsed = read(json)
    parsed("contents")(0)("parts")(1)("inlineData")("mimeType").str shouldBe "image/jpeg"
  }

  it should "include the correct MIME type for GIF" in {
    val json   = GeminiRequestBody.serialize("prompt", "data", MediaType.Gif)
    val parsed = read(json)
    parsed("contents")(0)("parts")(1)("inlineData")("mimeType").str shouldBe "image/gif"
  }

  it should "include the correct MIME type for WebP" in {
    val json   = GeminiRequestBody.serialize("prompt", "data", MediaType.WebP)
    val parsed = read(json)
    parsed("contents")(0)("parts")(1)("inlineData")("mimeType").str shouldBe "image/webp"
  }

  it should "handle empty prompt" in {
    val json   = GeminiRequestBody.serialize("", "data", MediaType.Png)
    val parsed = read(json)
    parsed("contents")(0)("parts")(0)("text").str shouldBe ""
  }

  it should "handle empty base64 data" in {
    val json   = GeminiRequestBody.serialize("prompt", "", MediaType.Png)
    val parsed = read(json)
    parsed("contents")(0)("parts")(1)("inlineData")("data").str shouldBe ""
  }

  it should "handle prompts with special characters" in {
    val prompt = "Describe this image: what do you see? Include \"quotes\" and newlines.\nSecond line."
    val json   = GeminiRequestBody.serialize(prompt, "data", MediaType.WebP)
    val parsed = read(json)
    parsed("contents")(0)("parts")(0)("text").str shouldBe prompt
  }

  it should "produce parseable JSON" in {
    val json = GeminiRequestBody.serialize("test", "abc123", MediaType.Jpeg)
    noException should be thrownBy ujson.read(json)
  }
}
