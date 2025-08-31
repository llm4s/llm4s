package org.llm4s.imageprocessing.provider.anthropicclient

import org.llm4s.imageprocessing.MediaType
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default._

class AnthropicRequestBodySpec extends AnyFlatSpec with Matchers {

  "AnthropicRequestBody" should "correctly serialize request with prompt and image data" in {
    val configModel = "claude-3-opus-20240229"
    val maxTokens   = 1024
    val prompt      = "Describe this image"
    val imageData   = "base64EncodedImageData"
    val mediaType   = MediaType.Jpeg

    val serialized = AnthropicRequestBody.serialize(configModel, maxTokens, prompt, imageData, mediaType)
    val json       = ujson.read(serialized)

    json("model").str shouldBe configModel
    json("max_tokens").num shouldBe maxTokens

    val messages = json("messages").arr
    messages should have size 1

    val message = messages.head
    message("role").str shouldBe "user"

    val content = message("content").arr
    content should have size 2

    val textContent = content(0)
    textContent("type").str shouldBe "text"
    textContent("text").str shouldBe prompt

    val imageContent = content(1)
    imageContent("type").str shouldBe "image"
    imageContent("source")("type").str shouldBe "base64"
    imageContent("source")("media_type").str shouldBe "image/jpeg"
    imageContent("source")("data").str shouldBe imageData
  }

  it should "correctly serialize with PNG media type" in {
    val configModel = "claude-3-sonnet-20240229"
    val maxTokens   = 500
    val prompt      = "What do you see?"
    val imageData   = "pngImageData"
    val mediaType   = MediaType.Png

    val serialized = AnthropicRequestBody.serialize(configModel, maxTokens, prompt, imageData, mediaType)
    val json       = ujson.read(serialized)

    json("model").str shouldBe configModel
    json("max_tokens").num shouldBe maxTokens
    json("messages")(0)("content")(1)("source")("media_type").str shouldBe "image/png"
  }

  it should "correctly serialize with WebP media type" in {
    val configModel = "claude-3-haiku-20240307"
    val maxTokens   = 2000
    val prompt      = "Analyze this WebP image"
    val imageData   = "webpImageData"
    val mediaType   = MediaType.WebP

    val serialized = AnthropicRequestBody.serialize(configModel, maxTokens, prompt, imageData, mediaType)
    val json       = ujson.read(serialized)

    json("messages")(0)("content")(1)("source")("media_type").str shouldBe "image/webp"
  }

  it should "be serializable and deserializable" in {
    val body = AnthropicRequestBody(
      model = "test-model",
      max_tokens = 100,
      messages = List(
        Message(
          role = "user",
          content = ujson.Arr(
            ujson.Obj("type" -> "text", "text" -> "test prompt"),
            ujson.Obj(
              "type" -> "image",
              "source" -> ujson.Obj(
                "type"       -> "base64",
                "media_type" -> "image/jpeg",
                "data"       -> "test-data"
              )
            )
          )
        )
      )
    )

    val serialized   = write(body)
    val deserialized = read[AnthropicRequestBody](serialized)

    deserialized.model shouldBe body.model
    deserialized.max_tokens shouldBe body.max_tokens
    deserialized.messages should have size 1
  }

  it should "produce valid JSON structure for API consumption" in {
    val serialized = AnthropicRequestBody.serialize(
      model = "claude-3-opus-20240229",
      maxTokens = 1000,
      prompt = "What's in this image?",
      base64Image = "imageData",
      mediaType = MediaType.Jpeg
    )

    // Verify it can be parsed as valid JSON
    noException should be thrownBy ujson.read(serialized)

    // Verify the structure matches Anthropic's expected format
    val json = ujson.read(serialized)
    (json.obj should contain).key("model")
    (json.obj should contain).key("max_tokens")
    (json.obj should contain).key("messages")
    json("messages").arr should not be empty
  }
}
