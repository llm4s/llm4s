package org.llm4s.llmconnect.streaming

import org.llm4s.llmconnect.model.{ StreamedChunk, Completion }
import org.llm4s.error.LLMError

/**
 * Options for streaming completions with callbacks for different events.
 * Provides a more flexible alternative to the simple onChunk callback.
 */
case class StreamingOptions(
  onStart: () => Unit = () => (),
  onChunk: StreamedChunk => Unit = _ => (),
  onError: LLMError => Unit = _ => (),
  onComplete: Completion => Unit = _ => (),
  bufferSize: Int = 8192,
  connectionTimeout: Long = 30000, // 30 seconds
  readTimeout: Long = 300000       // 5 minutes
) {

  /**
   * Create a copy with a different onChunk handler
   */
  def withOnChunk(handler: StreamedChunk => Unit): StreamingOptions =
    copy(onChunk = handler)

  /**
   * Create a copy with a different onError handler
   */
  def withOnError(handler: LLMError => Unit): StreamingOptions =
    copy(onError = handler)

  /**
   * Create a copy with a different onComplete handler
   */
  def withOnComplete(handler: Completion => Unit): StreamingOptions =
    copy(onComplete = handler)

  /**
   * Create a copy with a different onStart handler
   */
  def withOnStart(handler: () => Unit): StreamingOptions =
    copy(onStart = handler)

  /**
   * Create a copy with different timeout settings
   */
  def withTimeouts(connection: Long, read: Long): StreamingOptions =
    copy(connectionTimeout = connection, readTimeout = read)
}

/**
 * Companion object with convenience constructors
 */
object StreamingOptions {

  /**
   * Create options with just a chunk handler (most common use case)
   */
  def apply(onChunk: StreamedChunk => Unit): StreamingOptions =
    new StreamingOptions(onChunk = onChunk)

  /**
   * Create options for simple console output
   */
  def console: StreamingOptions = StreamingOptions(
    onChunk = chunk => chunk.content.foreach(print),
    onError = error => System.err.println(s"Streaming error: ${error.message}"),
    onComplete = _ => println() // New line after streaming
  )

  /**
   * Create options for collecting all content
   */
  def collector(builder: StringBuilder): StreamingOptions = StreamingOptions(
    onChunk = chunk => chunk.content.foreach(builder.append),
    onError = error => builder.append(s"[ERROR: ${error.message}]")
  )

  /**
   * Create options with full lifecycle logging
   */
  def withLogging(logger: String => Unit = println): StreamingOptions = StreamingOptions(
    onStart = () => logger("Starting stream..."),
    onChunk = chunk => {
      chunk.content.foreach(content => logger(s"Chunk: $content"))
      chunk.toolCall.foreach(call => logger(s"Tool call: ${call.name}"))
    },
    onError = error => logger(s"Error: ${error.message}"),
    onComplete = completion => logger(s"Complete: ${completion.id}")
  )

  /**
   * Builder for creating options fluently
   */
  class Builder {
    private var onStart: () => Unit            = () => ()
    private var onChunk: StreamedChunk => Unit = _ => ()
    private var onError: LLMError => Unit      = _ => ()
    private var onComplete: Completion => Unit = _ => ()
    private var bufferSize: Int                = 8192
    private var connectionTimeout: Long        = 30000
    private var readTimeout: Long              = 300000

    def withOnStart(handler: () => Unit): Builder = {
      onStart = handler
      this
    }

    def withOnChunk(handler: StreamedChunk => Unit): Builder = {
      onChunk = handler
      this
    }

    def withOnError(handler: LLMError => Unit): Builder = {
      onError = handler
      this
    }

    def withOnComplete(handler: Completion => Unit): Builder = {
      onComplete = handler
      this
    }

    def withBufferSize(size: Int): Builder = {
      bufferSize = size
      this
    }

    def withConnectionTimeout(timeout: Long): Builder = {
      connectionTimeout = timeout
      this
    }

    def withReadTimeout(timeout: Long): Builder = {
      readTimeout = timeout
      this
    }

    def build(): StreamingOptions = StreamingOptions(
      onStart = onStart,
      onChunk = onChunk,
      onError = onError,
      onComplete = onComplete,
      bufferSize = bufferSize,
      connectionTimeout = connectionTimeout,
      readTimeout = readTimeout
    )
  }

  /**
   * Create a new builder
   */
  def builder(): Builder = new Builder()
}
