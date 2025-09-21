package org.llm4s.imageprocessing.provider.anthropicclient

import org.llm4s.imageprocessing.MediaType
import upickle.default.{ macroRW, ReadWriter => RW, _ }

private[anthropicclient] case class TextContent(
  `type`: String = "text",
  text: String
)

object TextContent {
  implicit val rw: RW[TextContent] = macroRW
}

private[anthropicclient] case class ImageSource(
  `type`: String = "base64",
  media_type: String,
  data: String
)

object ImageSource {
  implicit val rw: RW[ImageSource] = macroRW
}

private[anthropicclient] case class ImageContent(
  `type`: String = "image",
  source: ImageSource
)

object ImageContent {
  implicit val rw: RW[ImageContent] = macroRW
}

private[anthropicclient] case class Message(
  role: String = "user",
  content: ujson.Value
)

object Message {
  implicit val rw: RW[Message] = readwriter[ujson.Value].bimap[Message](
    msg => ujson.Obj("role" -> msg.role, "content" -> msg.content),
    json => Message(json("role").str, json("content"))
  )
}

private[anthropicclient] case class AnthropicRequestBody(
  model: String,
  max_tokens: Int,
  messages: List[Message]
)

object AnthropicRequestBody {
  implicit val rw: RW[AnthropicRequestBody] = macroRW

  /**
   * Creates a request body for vision API calls with a single image and prompt.
   *
   * @param model The Claude model to use
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
    val textContent = ujson.Obj(
      "type" -> "text",
      "text" -> prompt
    )

    val imageContent = ujson.Obj(
      "type" -> "image",
      "source" -> ujson.Obj(
        "type"       -> "base64",
        "media_type" -> mediaType.value,
        "data"       -> base64Image
      )
    )

    val requestBody = AnthropicRequestBody(
      model = model,
      max_tokens = maxTokens,
      messages = List(
        Message(
          content = ujson.Arr(textContent, imageContent)
        )
      )
    )

    write(requestBody)
  }
}
