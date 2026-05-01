package org.llm4s.samples.chat.tui

import termflow.tui.*

/**
 * Rich-text transcript renderer for the chat TUI.
 *
 * Replaces `widgets.LogView` for the transcript pane because LogView
 * only wraps by width and doesn't break on embedded `\n`, and it
 * applies a single style to every row. We need:
 *
 *   - Per-role prefix colours (system / you / assistant / tool).
 *   - Embedded newline handling — a streamed `\n` in the assistant's
 *     reply must produce a visible line break, not get eaten by the
 *     terminal.
 *   - Inline `**bold**` markdown rendered as bold + foreground accent
 *     so the model's natural Markdown isn't rendered as raw asterisks.
 *
 * The viewport / wrap / max-scroll math mirrors `widgets.LogView` so
 * the existing scroll-by-line, auto-tail, and mouse-wheel routing
 * continue to work unchanged.
 */
private[tui] object ChatTuiTranscript:

  /** A line composed of styled segments. */
  type RichLine = Vector[Text]

  /**
   * Expand the conversation entries into rich display lines, applying
   * role-based colours, splitting body text on `\n`, and parsing
   * inline `**bold**` spans.
   */
  def renderEntries(entries: Vector[Entry], theme: Theme): Vector[RichLine] =
    if entries.isEmpty then Vector(Vector(Text("(no messages yet — type below to start)", Style(fg = theme.secondary))))
    else
      val out     = Vector.newBuilder[RichLine]
      val lastIdx = entries.size - 1
      entries.zipWithIndex.foreach { case (entry, i) =>
        out ++= renderEntry(entry, theme)
        if i < lastIdx then out += Vector.empty // blank spacer between turns
      }
      out.result()

  private def renderEntry(entry: Entry, theme: Theme): Vector[RichLine] =
    // Pick distinct slots so the four roles stay visually separate on
    // both `Theme.dark` and `Theme.light`. (`secondary` and `info` alias
    // to the same Cyan in the built-in palettes — avoid one of them.)
    val (labelColor, bodyColor, bold) = entry.role match {
      case Role.System    => (theme.secondary, theme.secondary, false)
      case Role.User      => (theme.success, theme.foreground, true)
      case Role.Assistant => (theme.primary, theme.foreground, true)
      case Role.Tool      => (theme.warning, theme.warning, false)
    }
    val labelStyle = Style(fg = labelColor, bold = bold)
    val prefix     = s"${entry.role.label}: "

    // Split body on '\n' so embedded newlines become real line breaks.
    val rawLines =
      if entry.content.isEmpty then Vector("")
      else entry.content.split("\n", -1).toVector

    val indent = " " * prefix.length
    val bodyLines: Vector[RichLine] = rawLines.zipWithIndex.map { case (line, idx) =>
      val parsed = parseInline(line, bodyColor, theme)
      if idx == 0 then Text(prefix, labelStyle) +: parsed
      else Text(indent, Style(fg = bodyColor)) +: parsed
    }

    val toolLines: Vector[RichLine] = entry.toolCall.toVector.flatMap { summary =>
      renderToolCall(summary, theme, indent)
    }

    bodyLines ++ toolLines

  private def renderToolCall(summary: ToolCallSummary, theme: Theme, indent: String): Vector[RichLine] =
    val args =
      if summary.args.length > 80 then summary.args.take(77) + "..."
      else summary.args
    val callLine: RichLine = Vector(
      Text(s"$indent  ⚙ ${summary.name}(", Style(fg = theme.secondary)),
      Text(args, Style(fg = theme.foreground)),
      Text(")", Style(fg = theme.secondary))
    )
    val tailLine: Option[RichLine] = summary.outcome.map {
      case ToolOutcome.Ok(s)  => Vector(Text(s"$indent  ✓ $s", Style(fg = theme.success)))
      case ToolOutcome.Err(m) => Vector(Text(s"$indent  ✗ $m", Style(fg = theme.error)))
      case ToolOutcome.Denied => Vector(Text(s"$indent  ⊘ denied by user", Style(fg = theme.warning)))
    }
    callLine +: tailLine.toVector

  /**
   * Parse `**bold**` spans into bold styled segments. Unterminated
   * runs of `**` are rendered as literal text (failure-safe).
   */
  private def parseInline(line: String, baseColor: Color, theme: Theme): RichLine =
    if !line.contains("**") then Vector(Text(line, Style(fg = baseColor)))
    else
      val baseStyle = Style(fg = baseColor)
      val boldStyle = Style(fg = theme.foreground, bold = true)
      val out       = Vector.newBuilder[Text]
      var i         = 0
      while i < line.length do
        val start = line.indexOf("**", i)
        if start < 0 then
          if i < line.length then out += Text(line.substring(i), baseStyle)
          i = line.length
        else
          if start > i then out += Text(line.substring(i, start), baseStyle)
          val end = line.indexOf("**", start + 2)
          if end < 0 || end == start + 2 then
            // Unterminated or empty bold — render the rest as plain text.
            out += Text(line.substring(start), baseStyle)
            i = line.length
          else
            out += Text(line.substring(start + 2, end), boldStyle)
            i = end + 2
      out.result()

  /** Greedy word-boundary wrap, falling back to char break for words longer than width. */
  def wrap(line: RichLine, width: Int): Vector[RichLine] =
    val w = math.max(1, width)
    if line.isEmpty then Vector(Vector.empty)
    else
      val out                 = Vector.newBuilder[RichLine]
      var current             = Vector.empty[Text]
      var col                 = 0
      var pendingGap          = 0 // unrendered whitespace sitting between words
      var pendingStyle: Style = null

      def appendText(txt: String, style: Style): Unit =
        current.lastOption match {
          case Some(last) if last.style == style =>
            current = current.dropRight(1) :+ Text(last.txt + txt, style)
          case _ =>
            current = current :+ Text(txt, style)
        }

      def flush(): Unit =
        out += current
        current = Vector.empty
        col = 0
        pendingGap = 0
        pendingStyle = null

      def charBreak(word: String, style: Style): Unit =
        var k = 0
        while k < word.length do
          val take = math.max(1, math.min(w - col, word.length - k))
          appendText(word.substring(k, k + take), style)
          col += take
          k += take
          if col >= w && k < word.length then flush()

      def placeWord(word: String, style: Style): Unit =
        if col == 0 then
          if word.length <= w then
            appendText(word, style)
            col += word.length
          else charBreak(word, style)
          pendingGap = 0
          pendingStyle = null
        else if col + pendingGap + word.length <= w then
          if pendingGap > 0 then
            val gapStyle = if pendingStyle != null then pendingStyle else style
            appendText(" " * pendingGap, gapStyle)
            col += pendingGap
            pendingGap = 0
            pendingStyle = null
          appendText(word, style)
          col += word.length
        else
          flush()
          if word.length <= w then
            appendText(word, style)
            col += word.length
          else charBreak(word, style)

      // Tokenise each segment into word and whitespace runs, preserving style.
      line.foreach { seg =>
        var idx = 0
        val s   = seg.txt
        while idx < s.length do
          val ch = s.charAt(idx)
          if ch == ' ' || ch == '\t' then
            var j = idx
            while j < s.length && (s.charAt(j) == ' ' || s.charAt(j) == '\t') do j += 1
            pendingGap += (j - idx)
            pendingStyle = seg.style
            idx = j
          else
            var j = idx
            while j < s.length && s.charAt(j) != ' ' && s.charAt(j) != '\t' do j += 1
            placeWord(s.substring(idx, j), seg.style)
            idx = j
      }

      // Trailing whitespace is dropped — matches natural chat rendering.
      out += current
      out.result()

  /** Expand a sequence of rich lines into wrapped display lines. */
  def expand(lines: Vector[RichLine], width: Int): Vector[RichLine] =
    lines.flatMap(line => wrap(line, width))

  /** Slice the wrapped lines into the visible viewport, padded at the top when short. */
  def viewport(displayLines: Vector[RichLine], height: Int, scrollOffset: Int): Vector[RichLine] =
    val h     = math.max(0, height)
    val total = displayLines.size
    if h == 0 then Vector.empty
    else
      val offset    = math.max(0, math.min(math.max(0, total - h), scrollOffset))
      val tailIndex = math.max(0, total - h - offset)
      val available = displayLines.slice(tailIndex, tailIndex + h)
      val pad       = h - available.size
      if pad > 0 then Vector.fill(pad)(Vector.empty[Text]) ++ available
      else available

  /** Maximum scroll offset for `lines` at this viewport size. */
  def maxScroll(lines: Vector[RichLine], width: Int, height: Int): Int =
    math.max(0, expand(lines, width).size - math.max(1, height))

  /** Produce TextNodes positioned starting at `at`, one per visible row. */
  def toNodes(visible: Vector[RichLine], at: Coord): List[VNode] =
    visible.zipWithIndex.toList.map { case (line, i) =>
      val segments = if line.isEmpty then List(Text("", Style())) else line.toList
      TextNode(at.x, at.y + i, segments)
    }
