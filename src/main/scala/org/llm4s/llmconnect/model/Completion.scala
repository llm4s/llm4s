package org.llm4s.llmconnect.model

/**
 * Represents a completion response from an LLM.
 *  This includes the ID, creation timestamp, the assistant's message, and optional token usage statistics.
 *
 * @param id Unique identifier for the completion.
 * @param created Timestamp of when the completion was created.
 * @param message The assistant's message in response to the user's input.
 * @param usage Optional token usage statistics for the completion.
 */
case class Completion(
  id: String,
  created: Long,
  message: AssistantMessage,
  usage: Option[TokenUsage] = None
)

case class TokenUsage(
  promptTokens: Int,
  completionTokens: Int,
  totalTokens: Int
)

case class StreamedChunk(
  id: String,
  content: Option[String],
  toolCall: Option[ToolCall] = None,
  finishReason: Option[String] = None
)
