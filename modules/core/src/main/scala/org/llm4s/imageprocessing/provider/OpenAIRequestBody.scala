package org.llm4s.imageprocessing.provider

import org.llm4s.imageprocessing.MediaType
import upickle.default.{ macroRW, ReadWriter => RW, _ }

private[provider] case class OpenAIMessage(
  role: String = "user",
  content: ujson.Value
)

object OpenAIMessage {
  implicit val rw: RW[OpenAIMessage] = readwriter[ujson.Value].bimap[OpenAIMessage](
    msg => ujson.Obj("role" -> msg.role, "content" -> msg.content),
    json => OpenAIMessage(json("role").str, json("content"))
  )
}

private[provider] case class OpenAIRequestBody(
  model: String,
  messages: List[OpenAIMessage],
  max_tokens: Int
)

object OpenAIRequestBody {
  implicit val rw: RW[OpenAIRequestBody] = macroRW

  /**
   * Creates a request body for OpenAI Vision API calls with a single image and prompt.
   *
   * @param model The OpenAI model to use (e.g., "gpt-4-vision-preview")
   * @param maxTokens Maximum tokens in the response
   * @param prompt The text prompt for image analysis
   * @param base64Image The base64-encoded image data
   * @param mediaType The media type of the image
   * @return Serialized JSON string for the API request
   */
  def serialize(
    model: String,
    maxTokens: Int,
    prompt: String,
    base64Image: String,
    mediaType: MediaType
  ): String = {
    // OpenAI expects the media type in the data URL format
    val mimeType = mediaType match {
      case MediaType.Jpeg => "jpeg"
      case MediaType.Png  => "png"
      case MediaType.Gif  => "gif"
      case MediaType.WebP => "webp"
      case MediaType.Bmp  => "bmp"
      case MediaType.Tiff => "tiff"
    }

    val textContent = ujson.Obj(
      "type" -> "text",
      "text" -> prompt
    )

    val imageContent = ujson.Obj(
      "type" -> "image_url",
      "image_url" -> ujson.Obj(
        "url" -> s"data:image/$mimeType;base64,$base64Image"
      )
    )

    val requestBody = OpenAIRequestBody(
      model = model,
      messages = List(
        OpenAIMessage(
          content = ujson.Arr(textContent, imageContent)
        )
      ),
      max_tokens = maxTokens
    )

    write(requestBody)
  }
}
