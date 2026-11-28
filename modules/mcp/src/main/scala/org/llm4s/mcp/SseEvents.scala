package org.llm4s.mcp

import org.llm4s.types.Result

/**
 * Reads Server-Sent Events (W3C) from a [[BoundedResponseBody]], for both HTTP transports (#3073): the event stream of
 * HTTP+SSE and a Streamable HTTP reply sent as a stream.
 *
 * A line `field: value` or `field:value` sets a field (one space after the colon is dropped, as the standard says);
 * several `data` lines are joined with `\n`; a line starting `:` is a comment; an empty line ends an event, and the end
 * of the stream ends the last one. `id` and `retry` are read and ignored. An event whose data, joined, would pass the
 * body's size bound is an error, from [[BoundedResponseBody.tooLarge]].
 */
private[mcp] object SseEvents {

  /** One event: its `event` field (`None` for the default, `message`), and its data. */
  final case class Event(eventType: Option[String], data: String) {

    /** Whether this is a message event: named `message`, or unnamed. */
    def isMessage: Boolean = eventType.forall(_ == "message")
  }

  /**
   * The next event of `body`; `None` once the stream has ended. An event with no data and no type is skipped.
   * `ignored` sees each line of a field other than `data`, `event`, `id` and `retry`, for a debug log.
   */
  def next(
    body: BoundedResponseBody,
    ignored: String => Unit = _ => (),
    maxChars: Int = BoundedResponseBody.MaxReplyChars
  ): Result[Option[Event]] = {
    val data                      = new java.lang.StringBuilder
    var hasData                   = false
    var eventType: Option[String] = None

    def event: Option[Event] =
      if (hasData || eventType.isDefined) Some(Event(eventType, data.toString)) else None

    @scala.annotation.tailrec
    def loop(): Result[Option[Event]] =
      body.readLine() match {
        case Left(error) => Left(error)
        case Right(None) => Right(event)
        case Right(Some(line)) if line.isEmpty =>
          if (hasData || eventType.isDefined) Right(event) else loop()
        case Right(Some(line)) if line.startsWith(":") => loop()
        case Right(Some(line)) =>
          val colon = line.indexOf(':')
          val field = if (colon < 0) line else line.substring(0, colon)
          val raw   = if (colon < 0) "" else line.substring(colon + 1)
          val value = if (raw.startsWith(" ")) raw.substring(1) else raw
          field match {
            case "data" =>
              if (data.length + value.length + 1 > maxChars) Left(body.tooLarge())
              else {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
                loop()
              }
            case "event" =>
              eventType = Some(value)
              loop()
            case "id" | "retry" => loop()
            case _ =>
              ignored(line)
              loop()
          }
      }

    loop()
  }
}
