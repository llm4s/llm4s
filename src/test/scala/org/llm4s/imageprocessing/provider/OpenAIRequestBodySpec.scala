package org.llm4s.imageprocessing.provider

import org.llm4s.imageprocessing.MediaType
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default._

class OpenAIRequestBodySpec extends AnyFlatSpec with Matchers {

  "OpenAIRequestBody" should "correctly serialize request with prompt and JPEG image data" in {
    val model     = "gpt-4-vision-preview"
    val maxTokens = 1024
    val prompt    = "Describe this image"
    val imageData = "base64EncodedImageData"
    val mediaType = MediaType.Jpeg

    val serialized = OpenAIRequestBody.serialize(model, maxTokens, prompt, imageData, mediaType)
    val json       = ujson.read(serialized)

    json("model").str shouldBe model
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
    imageContent("type").str shouldBe "image_url"
    imageContent("image_url")("url").str shouldBe s"data:image/jpeg;base64,$imageData"
  }

  it should "correctly serialize with PNG media type" in {
    val model     = "gpt-4-vision-preview"
    val maxTokens = 500
    val prompt    = "What do you see?"
    val imageData = "pngImageData"
    val mediaType = MediaType.Png

    val serialized = OpenAIRequestBody.serialize(model, maxTokens, prompt, imageData, mediaType)
    val json       = ujson.read(serialized)

    json("model").str shouldBe model
    json("max_tokens").num shouldBe maxTokens
    json("messages")(0)("content")(1)("image_url")("url").str shouldBe s"data:image/png;base64,$imageData"
  }

  it should "correctly serialize with WebP media type" in {
    val model     = "gpt-4-vision-preview"
    val maxTokens = 2000
    val prompt    = "Analyze this WebP image"
    val imageData = "webpImageData"
    val mediaType = MediaType.WebP

    val serialized = OpenAIRequestBody.serialize(model, maxTokens, prompt, imageData, mediaType)
    val json       = ujson.read(serialized)

    json("messages")(0)("content")(1)("image_url")("url").str shouldBe s"data:image/webp;base64,$imageData"
  }

  it should "correctly serialize with GIF media type" in {
    val model     = "gpt-4-vision-preview"
    val maxTokens = 1500
    val prompt    = "Describe this animated GIF"
    val imageData = "gifImageData"
    val mediaType = MediaType.Gif

    val serialized = OpenAIRequestBody.serialize(model, maxTokens, prompt, imageData, mediaType)
    val json       = ujson.read(serialized)

    json("messages")(0)("content")(1)("image_url")("url").str shouldBe s"data:image/gif;base64,$imageData"
  }

  it should "be serializable and deserializable" in {
    val body = OpenAIRequestBody(
      model = "test-model",
      messages = List(
        OpenAIMessage(
          role = "user",
          content = ujson.Arr(
            ujson.Obj("type" -> "text", "text" -> "test prompt"),
            ujson.Obj(
              "type" -> "image_url",
              "image_url" -> ujson.Obj(
                "url" -> "data:image/jpeg;base64,test-data"
              )
            )
          )
        )
      ),
      max_tokens = 100
    )

    val serialized   = write(body)
    val deserialized = read[OpenAIRequestBody](serialized)

    deserialized.model shouldBe body.model
    deserialized.max_tokens shouldBe body.max_tokens
    deserialized.messages should have size 1
  }

  it should "produce valid JSON structure for API consumption" in {
    val serialized = OpenAIRequestBody.serialize(
      model = "gpt-4-vision-preview",
      maxTokens = 1000,
      prompt = "What's in this image?",
      base64Image = "imageData",
      mediaType = MediaType.Jpeg
    )

    // Verify it can be parsed as valid JSON
    noException should be thrownBy ujson.read(serialized)

    // Verify the structure matches OpenAI's expected format
    val json = ujson.read(serialized)
    (json.obj should contain).key("model")
    (json.obj should contain).key("max_tokens")
    (json.obj should contain).key("messages")
    json("messages").arr should not be empty

    // Verify the image URL format is correct
    val imageUrl = json("messages")(0)("content")(1)("image_url")("url").str
    imageUrl should startWith("data:image/")
    imageUrl should include("base64,")
  }

  it should "handle all supported media types correctly" in {
    val testCases = List(
      (MediaType.Jpeg, "jpeg"),
      (MediaType.Png, "png"),
      (MediaType.Gif, "gif"),
      (MediaType.WebP, "webp"),
      (MediaType.Bmp, "bmp"),
      (MediaType.Tiff, "tiff")
    )

    testCases.foreach { case (mediaType, expectedFormat) =>
      val serialized = OpenAIRequestBody.serialize(
        model = "gpt-4-vision-preview",
        maxTokens = 1000,
        prompt = "Test",
        base64Image = "data",
        mediaType = mediaType
      )

      val json     = ujson.read(serialized)
      val imageUrl = json("messages")(0)("content")(1)("image_url")("url").str
      imageUrl shouldBe s"data:image/$expectedFormat;base64,data"
    }
  }
}
