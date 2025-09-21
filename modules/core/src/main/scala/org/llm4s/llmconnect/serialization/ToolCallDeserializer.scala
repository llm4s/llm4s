package org.llm4s.llmconnect.serialization

import org.llm4s.llmconnect.model.ToolCall
import ujson._

/**
 * Abstraction for deserializing tool calls from different LLM providers
 */
trait ToolCallDeserializer {
  def deserializeToolCalls(toolCallsJson: Value): Vector[ToolCall]
}

/**
 * OpenRouter-specific tool call deserializer
 * Handles OpenRouter's double-nested array structure (array of arrays of tool calls)
 * rather than a single-level array of tool calls.
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
 * Standard tool call deserializer for most providers
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
