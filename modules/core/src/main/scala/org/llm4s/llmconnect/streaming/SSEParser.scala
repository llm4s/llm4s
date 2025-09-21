package org.llm4s.llmconnect.streaming

import scala.collection.mutable
import scala.util.Try

/**
 * Server-Sent Events (SSE) parser for streaming LLM responses.
 *
 * Handles the SSE format used by both OpenAI and Anthropic streaming endpoints.
 * SSE format consists of fields like:
 * - data: <json content>
 * - event: <event type>
 * - id: <event id>
 * - retry: <retry time>
 * - : <comment>
 */
object SSEParser {

  /**
   * Represents a parsed SSE event
   */
  case class SSEEvent(
    data: Option[String] = None,
    event: Option[String] = None,
    id: Option[String] = None,
    retry: Option[Int] = None
  ) {
    def isEmpty: Boolean = data.isEmpty && event.isEmpty && id.isEmpty && retry.isEmpty

    def isComplete: Boolean = data.isDefined || event.isDefined
  }

  /**
   * Parse a single SSE event from a string.
   * An event is terminated by a double newline.
   */
  def parseEvent(eventString: String): SSEEvent = {
    val lines = eventString.split("\n").filter(_.nonEmpty)
    var event = SSEEvent()

    lines.foreach { line =>
      if (line.startsWith(":")) {
        // Comment line, ignore
      } else {
        val colonIndex = line.indexOf(':')
        if (colonIndex > 0) {
          val field = line.substring(0, colonIndex).trim
          val value = if (colonIndex < line.length - 1) {
            val v = line.substring(colonIndex + 1)
            if (v.startsWith(" ")) v.substring(1) else v
          } else ""

          field match {
            case "data" =>
              // Accumulate data fields (SSE joins multiple data lines with newlines)
              val prev   = event.data.getOrElse("")
              val joined = if (prev.isEmpty) value else prev + "\n" + value
              event = event.copy(data = Some(joined))
            case "event" =>
              event = event.copy(event = Some(value))
            case "id" =>
              event = event.copy(id = Some(value))
            case "retry" =>
              Try(value.toInt).toOption.foreach(retryTime => event = event.copy(retry = Some(retryTime)))
            case _ =>
            // Unknown field, ignore
          }
        }
      }
    }

    event
  }

  /**
   * Parse a stream of SSE data into individual events.
   * Returns an iterator of SSEEvents.
   */
  def parseStream(stream: String): Iterator[SSEEvent] = {
    val events      = mutable.ArrayBuffer[SSEEvent]()
    val eventBuffer = new StringBuilder()

    stream.split("\n").foreach { line =>
      if (line.isEmpty) {
        // Double newline indicates end of event
        if (eventBuffer.nonEmpty) {
          val event = parseEvent(eventBuffer.toString)
          if (!event.isEmpty) {
            events += event
          }
          eventBuffer.clear()
        }
      } else {
        eventBuffer.append(line).append("\n")
      }
    }

    // Parse any remaining content
    if (eventBuffer.nonEmpty) {
      val event = parseEvent(eventBuffer.toString)
      if (!event.isEmpty) {
        events += event
      }
    }

    events.iterator
  }

  /**
   * Parse streaming data with a buffer for incomplete events.
   * This is useful for parsing data as it arrives from a network stream.
   */
  class StreamingParser {
    private val buffer = new StringBuilder()
    private val events = mutable.Queue[SSEEvent]()

    /**
     * Add chunk of data to the parser
     */
    def addChunk(chunk: String): Unit = {
      buffer.append(chunk)
      extractEvents()
    }

    /**
     * Extract complete events from the buffer
     */
    private def extractEvents(): Unit = {
      val content = buffer.toString
      val lines   = content.split("\n", -1) // -1 to keep empty strings

      var i                    = 0
      var eventStart           = 0
      var lastCompleteEventEnd = -1

      while (i < lines.length) {
        if (lines(i).isEmpty && i > eventStart) {
          // Found end of event
          val eventLines = lines.slice(eventStart, i)
          if (eventLines.nonEmpty) {
            val eventString = eventLines.mkString("\n")
            val event       = parseEvent(eventString)
            if (!event.isEmpty) {
              events.enqueue(event)
            }
          }
          lastCompleteEventEnd = i
          eventStart = i + 1
        }
        i += 1
      }

      // Keep incomplete event in buffer
      if (lastCompleteEventEnd >= 0) {
        val remaining = lines.drop(lastCompleteEventEnd + 1).mkString("\n")
        buffer.clear()
        buffer.append(remaining)
      }
    }

    /**
     * Get next available event
     */
    def nextEvent(): Option[SSEEvent] =
      if (events.nonEmpty) Some(events.dequeue()) else None

    /**
     * Check if there are events available
     */
    def hasEvents: Boolean = events.nonEmpty

    /**
     * Get all available events
     */
    def getEvents(): Seq[SSEEvent] = {
      val result = events.toSeq
      events.clear()
      result
    }

    /**
     * Clear the parser state
     */
    def clear(): Unit = {
      buffer.clear()
      events.clear()
    }

    /**
     * Flush any remaining buffered data as an event
     */
    def flush(): Option[SSEEvent] =
      if (buffer.nonEmpty) {
        val event = parseEvent(buffer.toString)
        buffer.clear()
        if (!event.isEmpty) Some(event) else None
      } else {
        None
      }
  }

  /**
   * Create a new streaming parser instance
   */
  def createStreamingParser(): StreamingParser = new StreamingParser()
}
