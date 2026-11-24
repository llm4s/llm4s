package org.llm4s.llmconnect.model

import org.llm4s.annotation.Stable

/**
 * The provider's identifier of one tool call: [[ToolCall.id]], echoed back as [[ToolMessage.toolCallId]] so the
 * result is matched to its call. Unique within a conversation, and opaque: only the provider that issued it knows
 * what it means, so the library forwards it unchanged.
 *
 * An opaque type over `String`: no wrapper at runtime, and a plain JSON string on the wire. Java and Kotlin see a
 * `String`.
 */
opaque type ToolCallId = String

/** Companion for the [[ToolCallId]] opaque type. */
@Stable
object ToolCallId:

  /** Wraps a provider's raw call id as a [[ToolCallId]]. */
  def apply(value: String): ToolCallId = value

  /** Unwraps a [[ToolCallId]] to the provider's raw string. */
  extension (id: ToolCallId) def asString: String = id

  /**
   * Encodes as a plain JSON string. Built from upickle's string codecs: inside this scope a `ToolCallId` is a
   * `String`, so summoning `ReadWriter[String]` would find this given.
   */
  given upickle.default.ReadWriter[ToolCallId] =
    upickle.default.ReadWriter.join(upickle.default.StringReader, upickle.default.StringWriter)
