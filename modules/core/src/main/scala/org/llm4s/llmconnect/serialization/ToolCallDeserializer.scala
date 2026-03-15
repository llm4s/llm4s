package org.llm4s.llmconnect.serialization

import org.llm4s.llmconnect.model.ToolCall
import ujson._

/**
 * Abstraction for deserializing tool calls from different LLM provider response formats.
 *
 * Each provider may encode tool calls differently in its JSON responses.
 * Implementations convert the provider-specific JSON structure into a
 * uniform `Vector[ToolCall]`.
 */
trait ToolCallDeserializer {

  /**
   * Parse a JSON value containing tool calls into a uniform sequence.
   *
   * @param toolCallsJson the raw JSON value from the provider's response
   * @return parsed tool calls with id, function name, and arguments
   */
  def deserializeToolCalls(toolCallsJson: Value): Vector[ToolCall]
}

/**
 * OpenRouter-specific tool call deserializer.
 *
 * Handles OpenRouter's double-nested array structure where tool calls are
 * encoded as an array of arrays, rather than the flat array used by most providers.
 */
object OpenRouterToolCallDeserializer extends ToolCallDeserializer {

  def deserializeToolCalls(toolCallsJson: Value): Vector[ToolCall] =
    toolCallsJson.arr.flatMap { callArray =>
      callArray.arr.map { call =>
        ToolCall(
          id = call("id").str,
          name = call("function")("name").str,
          arguments = ujson.read(call("function")("arguments").str)
        )
      }
    }.toVector
}

/**
 * Standard tool call deserializer for most LLM providers (OpenAI, Anthropic, etc.).
 *
 * Expects a flat JSON array of tool call objects, each containing an `id` and
 * a `function` object with `name` and `arguments` fields.
 */
object StandardToolCallDeserializer extends ToolCallDeserializer {

  def deserializeToolCalls(toolCallsJson: Value): Vector[ToolCall] =
    toolCallsJson.arr.map { call =>
      ToolCall(
        id = call("id").str,
        name = call("function")("name").str,
        arguments = ujson.read(call("function")("arguments").str)
      )
    }.toVector
}
