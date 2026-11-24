package org.llm4s.toolapi

import org.llm4s.annotation.Stable

/**
 * The key a tool gives an external system so that a second run of the same tool call does not repeat its side
 * effect: one key per model-issued call, the same in every run of that call (a retrying wrapper, an approval, an
 * answered question, `recover` after a failure, a cancellation or a crash), and different for a call from another
 * model request, even one that reuses the provider's call id. A tool reads it as [[ToolCallInfo.idempotencyKey]].
 *
 * The agent runtime (`llm4s-agent`) derives it from the model request that issued the call and the call's id, name
 * and arguments, and records it with the call. It de-duplicates only where the external system honours it: a tool
 * runs at least once, never exactly once.
 *
 * An opaque type over `String`: no wrapper at runtime, and a plain JSON string on the wire. Java and Kotlin see a
 * `String`.
 */
opaque type IdempotencyKey = String

/** Companion for the [[IdempotencyKey]] opaque type. */
@Stable
object IdempotencyKey:

  /** A key with this exact value, such as one read back from an external system's log. */
  def apply(value: String): IdempotencyKey = value

  /** Unwraps an [[IdempotencyKey]] to the string to send: a header value, a client token, a unique column. */
  extension (key: IdempotencyKey) def asString: String = key

  /**
   * Encodes as a plain JSON string. Built from upickle's string codecs: inside this scope an `IdempotencyKey` is a
   * `String`, so summoning `ReadWriter[String]` would find this given.
   */
  given upickle.default.ReadWriter[IdempotencyKey] =
    upickle.default.ReadWriter.join(upickle.default.StringReader, upickle.default.StringWriter)
