package org.llm4s.llmconnect.model

/**
 * Maps [[ResponseFormat]] to provider-specific request payloads.
 *
 * Used internally by provider clients; no provider names leak in the public API.
 */
object ResponseFormatMapper {

  /**
   * Produces the OpenAI-compatible `response_format` JSON value.
   *
   * Used by OpenAI, Azure, OpenRouter, DeepSeek, Zai (OpenAI-compatible APIs).
   *
   * @param format the requested response format
   * @return the JSON value to set as `response_format` in the request body, or None to omit
   */
  def toOpenAIResponseFormat(format: ResponseFormat): Option[ujson.Value] =
    format match {
      case ResponseFormat.Json =>
        Some(ujson.Obj("type" -> "json_object"))
      case js: ResponseFormat.JsonSchema =>
        Some(
          ujson.Obj(
            "type" -> "json_schema",
            "json_schema" -> ujson.Obj(
              "name"   -> js.name,
              "strict" -> js.strict,
              "schema" -> js.schema
            )
          )
        )
    }
}
