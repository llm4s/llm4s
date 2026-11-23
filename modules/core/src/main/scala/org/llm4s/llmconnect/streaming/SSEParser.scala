package org.llm4s.llmconnect.streaming

import org.llm4s.annotation.Stable

import scala.collection.mutable
import scala.concurrent.duration.*
import scala.util.Try

/**
 * Server-Sent Events (SSE) parser for streaming LLM responses.
 *
 * Handles the SSE format used by both OpenAI and Anthropic streaming endpoints.
 * SSE format consists of fields like:
 * - data: <json content>
 * - event: <event type>
 * - id: <event id>
 * - retry: <reconnection time, in milliseconds>
 * - : <comment>
 *
 * Lines may end in LF, CRLF or CR, as the SSE specification allows.
 */
@Stable
object SSEParser {

  /**
   * Represents a parsed SSE event.
   *
   * @param retry the reconnection time the server asked for, from a `retry` field of ASCII digits
   */
  final case class SSEEvent private (
    data: Option[String],
    event: Option[String],
    id: Option[String],
    retry: Option[FiniteDuration]
  ) {
    def isEmpty: Boolean = data.isEmpty && event.isEmpty && id.isEmpty && retry.isEmpty

    def isComplete: Boolean = data.isDefined || event.isDefined

    def withData(data: String): SSEEvent                   = copy(data = Some(data))
    def withData(data: Option[String]): SSEEvent           = copy(data = data)
    def withEvent(event: String): SSEEvent                 = copy(event = Some(event))
    def withEvent(event: Option[String]): SSEEvent         = copy(event = event)
    def withId(id: String): SSEEvent                       = copy(id = Some(id))
    def withId(id: Option[String]): SSEEvent               = copy(id = id)
    def withRetry(retry: FiniteDuration): SSEEvent         = copy(retry = Some(retry))
    def withRetry(retry: Option[FiniteDuration]): SSEEvent = copy(retry = retry)
  }

  object SSEEvent {

    /** Creates an [[SSEEvent]]; every field defaults to absent. */
    def apply(
      data: Option[String] = None,
      event: Option[String] = None,
      id: Option[String] = None,
      retry: Option[FiniteDuration] = None
    ): SSEEvent = new SSEEvent(data, event, id, retry)
  }

  /** An SSE line ending: CRLF, LF or a lone CR. */
  private val LineEnding = "\r\n|\r|\n"

  /**
   * Parse a single SSE event from a string, the event's lines without the blank line that ends it.
   * Lines may end in LF, CRLF or CR.
   */
  def parseEvent(eventString: String): SSEEvent = {
    val lines = eventString.split(LineEnding).filter(_.nonEmpty)
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
              val joined = if (prev.isEmpty) value else s"$prev\n$value"
              event = event.withData(joined)
            case "event" =>
              event = event.withEvent(value)
            case "id" =>
              event = event.withId(value)
            case "retry" =>
              // the specification ignores a value that is not all ASCII digits
              if (value.nonEmpty && value.forall(c => c >= '0' && c <= '9'))
                Try(value.toLong).toOption.foreach(millis => event = event.withRetry(millis.millis))
            case _ =>
            // Unknown field, ignore
          }
        }
      }
    }

    event
  }

  /**
   * Parse streaming data with a buffer for incomplete events.
   * This is useful for parsing data as it arrives from a network stream.
   *
   * A chunk may end anywhere, between the CR and LF of a CRLF included. Mutable and not
   * thread-safe: one parser per stream. Created by [[SSEParser.createStreamingParser]].
   */
  final class StreamingParser private[SSEParser] () {
    private val buffer = new StringBuilder()
    private val events = mutable.Queue[SSEEvent]()
    // the previous chunk ended in CR, so an LF starting this one finishes that CRLF
    private var afterCR = false

    /**
     * Add chunk of data to the parser
     */
    def addChunk(chunk: String): Unit = {
      val rest = if (afterCR && chunk.startsWith("\n")) chunk.substring(1) else chunk
      if (chunk.nonEmpty) afterCR = chunk.endsWith("\r")
      buffer.append(rest.replace("\r\n", "\n").replace('\r', '\n'))
      extractEvents()
    }

    /**
     * Extract complete events from the buffer
     */
    private def extractEvents(): Unit = {
      val content = buffer.toString
      val lastEnd = content.lastIndexOf('\n')
      if (lastEnd >= 0) {
        // only the lines before the last line ending are complete; the rest may still grow
        val tail    = content.substring(lastEnd + 1)
        val pending = mutable.ArrayBuffer[String]()
        content.substring(0, lastEnd).split("\n", -1).foreach { line =>
          if (line.nonEmpty) pending += line
          else if (pending.nonEmpty) {
            // a blank line ends the event
            val event = parseEvent(pending.mkString("\n"))
            if (!event.isEmpty) events.enqueue(event)
            pending.clear()
          }
        }
        buffer.clear()
        pending.foreach(line => buffer.append(line).append('\n'))
        buffer.append(tail)
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
     * Clear the parser state
     */
    def clear(): Unit = {
      buffer.clear()
      events.clear()
      afterCR = false
    }

    /**
     * Flush any remaining buffered data as an event
     */
    def flush(): Option[SSEEvent] = {
      afterCR = false
      if (buffer.nonEmpty) {
        val event = parseEvent(buffer.toString)
        buffer.clear()
        if (!event.isEmpty) Some(event) else None
      } else {
        None
      }
    }
  }

  /**
   * Create a new streaming parser instance
   */
  def createStreamingParser(): StreamingParser = new StreamingParser()
}
