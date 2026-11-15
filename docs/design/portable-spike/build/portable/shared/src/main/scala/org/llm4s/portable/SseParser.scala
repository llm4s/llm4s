package org.llm4s.portable

/**
 * An incremental server-sent-events parser: feed it the body in any pieces and it returns the `data` of each event
 * the piece completes, multi-line data joined with `\n`. Only `data:` fields matter to chat completions; `event:`,
 * `id:`, `retry:` and comments are skipped. Lines may end in `\n`, `\r\n` or `\r`.
 */
final class SseParser {
  private val pending = new StringBuilder
  private val data    = Vector.newBuilder[String]
  private var hasData = false

  def feed(piece: String): Vector[String] = {
    pending.append(piece)
    val events = Vector.newBuilder[String]
    var start  = 0
    var i      = 0
    val buf    = pending.toString
    while (i < buf.length) {
      val c = buf.charAt(i)
      if (c == '\n' || c == '\r') {
        // A `\r` at the very end may be the first half of `\r\n`: wait for the next piece.
        if (c == '\r' && i + 1 == buf.length) i = buf.length
        else {
          line(buf.substring(start, i)).foreach(events += _)
          i += (if (c == '\r' && buf.charAt(i + 1) == '\n') 2 else 1)
          start = i
        }
      } else i += 1
    }
    pending.clear()
    pending.append(buf.substring(start))
    events.result()
  }

  private def line(text: String): Option[String] =
    if (text.isEmpty) {
      if (!hasData) None
      else {
        val joined = data.result().mkString("\n")
        data.clear()
        hasData = false
        Some(joined)
      }
    } else {
      if (text.startsWith("data:")) {
        data += text.stripPrefix("data:").stripPrefix(" ")
        hasData = true
      }
      None
    }
}
