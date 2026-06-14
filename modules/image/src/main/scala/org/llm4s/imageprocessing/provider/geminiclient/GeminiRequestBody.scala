package org.llm4s.imageprocessing.provider.geminiclient

import org.llm4s.imageprocessing.MediaType

/**
 * Serialises the Gemini generateContent request body for vision calls.
 *
 * Gemini's multimodal API wraps parts inside a `contents` array:
 * {{{
 *   {
 *     "contents": [{
 *       "parts": [
 *         {"text": "<prompt>"},
 *         {"inlineData": {"mimeType": "image/png", "data": "<base64>"}}
 *       ]
 *     }]
 *   }
 * }}}
 */
private[geminiclient] object GeminiRequestBody {

  /**
   * Serialises a single-turn vision request to a JSON string.
   *
   * @param prompt       The text prompt accompanying the image.
   * @param base64Image  Base64-encoded image bytes (no data-URI prefix).
   * @param mediaType    MIME type of the image.
   * @return             A JSON string ready to POST to the Gemini generateContent endpoint.
   */
  def serialize(prompt: String, base64Image: String, mediaType: MediaType): String = {
    val textPart = ujson.Obj("text" -> prompt)
    val imagePart = ujson.Obj(
      "inlineData" -> ujson.Obj(
        "mimeType" -> mediaType.value,
        "data"     -> base64Image
      )
    )
    val body = ujson.Obj(
      "contents" -> ujson.Arr(
        ujson.Obj("parts" -> ujson.Arr(textPart, imagePart))
      )
    )
    ujson.write(body)
  }
}
