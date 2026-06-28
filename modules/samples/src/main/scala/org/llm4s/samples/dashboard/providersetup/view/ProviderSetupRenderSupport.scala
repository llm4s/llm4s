package org.llm4s.samples.dashboard.providersetup.view

import org.llm4s.samples.dashboard.providersetup.ProviderSetupModel.*
import org.llm4s.samples.dashboard.providersetup.{
  ProviderSetupCompare,
  ProviderSetupContent,
  ProviderSetupProviderSelection
}
import org.llm4s.samples.dashboard.providersetup.ProviderSetupTabs
import org.llm4s.samples.dashboard.shared.DashboardSupport
import termflow.tui.*
import termflow.tui.TuiPrelude.*

private[providersetup] object ProviderSetupRenderSupport:

  final case class TranscriptRow(segments: List[Text], padStyle: Style = Style())

  object TranscriptRow:
    def plain(text: String, style: Style = Style()): TranscriptRow =
      TranscriptRow(List(text.text(style)), style)

  def comparePanelCapacity(model: Model): Int =
    val height      = math.max(model.terminalHeight, 20)
    val panelHeight = math.max(10, height - 12)
    math.max(1, panelHeight - 2)

  def compareResultWidth(model: Model): Int =
    val width      = math.max(model.terminalWidth, 72)
    val outerWidth = math.max(68, width - 4)
    val leftWidth  = weightedWidth(outerWidth, 1, Vector(42, 26), 0)
    math.max(1, leftWidth - 4)

  def renderTabs(
    model: Model,
    activeTab: SetupTabId,
    width: Int
  ): List[(String, Boolean)] =
    val labels = ProviderSetupTabs.setupTabs.zipWithIndex.map { case (_, idx) =>
      val tabId = ProviderSetupTabs.setupTabAt(idx)
      val title = ProviderSetupTabs.tabTitle(model, tabId)
      (s"${idx + 1}:$title", tabId == activeTab)
    }.toList

    val rendered = labels.flatMap { case (label, active) =>
      List((if active then s"[ $label ]" else s"  $label  ", active))
    }

    truncateStyled(rendered, width)

  def renderTabText(tab: (String, Boolean), tabBarFocused: Boolean): Text =
    val (label, active) = tab
    if active && tabBarFocused then label.text(Style(fg = Color.Black, bg = Color.Yellow, bold = true))
    else if active then label.text(Style(fg = Color.Yellow, bold = true))
    else label.text(Style(fg = Color.White))

  def clipPanel(lines: List[String], capacity: Int, area: String): List[String] =
    val overflow = math.max(0, lines.length - capacity)
    if overflow > 0 && capacity > 1 then lines.take(capacity - 1) :+ overflowLine(overflow, area)
    else lines.take(capacity)

  def clipProvidersModelPanel(lines: List[String], capacity: Int, model: Model, area: String): List[String] =
    clipChosenModelPanel(
      lines,
      capacity,
      model.highlightedModel,
      ProviderSetupProviderSelection.selectedConfiguredProviderModels(model),
      area
    )

  def clipCompareModelPanel(lines: List[String], capacity: Int, model: Model, area: String): List[String] =
    clipChosenModelPanel(
      lines,
      capacity,
      model.highlightedModel,
      ProviderSetupCompare.selectedCompareProviderModels(model),
      area
    )

  def clipModelPanel(lines: List[String], capacity: Int, model: Model, area: String): List[String] =
    clipChosenModelPanel(
      lines,
      capacity,
      model.highlightedModel,
      ProviderSetupProviderSelection.defaultProviderModels(model.configStatus),
      area
    )

  def clipChosenModelPanel(
    lines: List[String],
    capacity: Int,
    highlightedModel: Option[String],
    models: Vector[String],
    area: String
  ): List[String] =
    if capacity <= 0 then Nil
    else
      val highlightedIndex =
        for
          highlighted <- highlightedModel
          idx <- models.indexOf(highlighted) match
            case -1  => None
            case pos => Some(pos)
        yield 7 + idx

      highlightedIndex match
        case Some(index) if lines.length > capacity =>
          val start       = math.max(0, math.min(index - (capacity / 2), lines.length - capacity))
          val window      = lines.slice(start, start + capacity)
          val olderHidden = start
          val newerHidden = math.max(0, lines.length - (start + window.length))
          if olderHidden > 0 && newerHidden > 0 && capacity > 2 then
            overflowLineFromTop(olderHidden, area) :: window.slice(1, capacity - 1) ::: List(
              overflowLine(newerHidden, area)
            )
          else if olderHidden > 0 && capacity > 1 then
            overflowLineFromTop(olderHidden, area) :: window.takeRight(capacity - 1)
          else if newerHidden > 0 && capacity > 1 then window.take(capacity - 1) :+ overflowLine(newerHidden, area)
          else window
        case _ =>
          clipPanel(lines, capacity, area)

  def renderTranscriptViewport(
    lines: List[TranscriptRow],
    capacity: Int,
    scrollOffset: Int,
    area: String
  ): List[TranscriptRow] =
    if capacity <= 0 then Nil
    else
      val maxOffset   = math.max(0, lines.length - capacity)
      val clamped     = math.max(0, math.min(scrollOffset, maxOffset))
      val baseStart   = math.max(0, lines.length - capacity - clamped)
      val baseWindow  = lines.slice(baseStart, math.min(lines.length, baseStart + capacity))
      val olderHidden = baseStart
      val newerHidden = math.max(0, lines.length - (baseStart + baseWindow.length))

      if olderHidden > 0 && newerHidden > 0 && capacity > 2 then
        TranscriptRow.plain(overflowLineFromTop(olderHidden, area), transcriptMetaStyle) +:
          baseWindow.slice(1, capacity - 1) :+
          TranscriptRow.plain(overflowLine(newerHidden, area), transcriptMetaStyle)
      else if olderHidden > 0 && capacity > 1 then
        TranscriptRow.plain(overflowLineFromTop(olderHidden, area), transcriptMetaStyle) +: baseWindow.takeRight(
          capacity - 1
        )
      else if newerHidden > 0 && capacity > 1 then
        baseWindow.take(capacity - 1) :+ TranscriptRow.plain(overflowLine(newerHidden, area), transcriptMetaStyle)
      else baseWindow

  def renderBlankArea(x: Int, y: Int, width: Int, height: Int): List[VNode] =
    if width <= 0 || height <= 0 then Nil
    else (0 until height).toList.map(row => TextNode(x.x, (y + row).y, List(fixedWidth("", width).text)))

  def fixedTranscriptRows(lines: List[TranscriptRow], capacity: Int): List[TranscriptRow] =
    if capacity <= 0 then Nil
    else
      val filled = lines.take(capacity)
      filled ++ List.fill(math.max(0, capacity - filled.length))(TranscriptRow.plain("", transcriptSpacerStyle))

  def fixedPanelTextRows(lines: List[String], capacity: Int): List[String] =
    if capacity <= 0 then Nil
    else
      val filled = lines.take(capacity)
      filled ++ List.fill(math.max(0, capacity - filled.length))("")

  def weightedWidth(totalWidth: Int, gapWidth: Int, weights: Vector[Int], index: Int): Int =
    val gaps       = math.max(0, weights.length - 1) * math.max(0, gapWidth)
    val usable     = math.max(weights.length, totalWidth - gaps)
    val totalShare = math.max(1, weights.sum)
    val raw        = weights.map(weight => usable.toDouble * weight.toDouble / totalShare.toDouble)
    val base       = raw.map(math.floor(_).toInt)
    val remainder  = math.max(0, usable - base.sum)
    val ranked =
      raw.zipWithIndex
        .sortBy { case (value, idx) => (-1.0 * (value - math.floor(value)), idx) }
        .take(remainder)
        .map(_._2)
        .toSet
    val widths = base.zipWithIndex.map { case (value, idx) =>
      value + (if ranked.contains(idx) then 1 else 0)
    }

    widths.lift(index).getOrElse(0)

  def truncateStyled(parts: List[(String, Boolean)], width: Int): List[(String, Boolean)] =
    if width <= 0 then Nil
    else
      val builder = List.newBuilder[(String, Boolean)]
      var used    = 0
      parts.foreach { case (chunk, active) =>
        if used < width then
          val remaining = width - used
          val clipped =
            if chunk.length <= remaining then chunk
            else if remaining == 1 then "…"
            else chunk.take(remaining - 1) + "…"
          if clipped.nonEmpty then
            builder += clipped -> active
            used += clipped.length
      }
      builder.result()

  def wrap(text: String, width: Int): List[String] =
    wrapWithPrefixes(text, width, "", "")

  def renderVisibleDemoTranscript(model: Model, width: Int, capacity: Int): List[TranscriptRow] =
    if model.demoEntries.isEmpty then
      renderTranscriptViewport(
        wrap("No demo messages yet. Type a prompt below to start.", width).map(
          TranscriptRow.plain(_, transcriptMetaStyle)
        ),
        capacity,
        0,
        "demo transcript"
      )
    else
      val allLines = model.demoEntries.toList.flatMap(entry => renderDemoTranscriptBlock(entry, width))
      renderTranscriptViewport(allLines, capacity, model.demoScrollOffset, "demo transcript")

  def renderVisibleCompareResult(model: Model, width: Int, capacity: Int): List[TranscriptRow] =
    val allRows = renderCompareResultRows(model, width)
    renderTranscriptViewport(
      allRows,
      capacity,
      model.compareScrollOffsets.lift(model.activeCompareTab).getOrElse(0),
      "compare result"
    )

  def renderDemoTranscriptBlock(entry: DemoEntry, width: Int): List[TranscriptRow] =
    val label = entry.role match
      case DemoRole.System    => "system"
      case DemoRole.User      => "you"
      case DemoRole.Assistant => "assistant"

    val rolePrefix         = s"$label: "
    val continuationPrefix = " " * rolePrefix.length
    val rowStyle           = transcriptStyle(entry.role)
    val logicalLines       = entry.content.split("\n", -1).toList

    val renderedLines = renderFormattedTranscriptLines(logicalLines, width, rolePrefix, continuationPrefix, rowStyle)

    renderedLines :+ TranscriptRow.plain("", transcriptSpacerStyle)

  def renderDemoSidebar(model: Model, width: Int): List[String] =
    val session = model.activeSession
    val spinner = waitingSpinner(model)
    val base = List(
      s"Mode: ${model.screenMode}",
      "",
      "Active session:",
      session.map(_.label).getOrElse("none"),
      "",
      "Session note:",
      session.map(_.note).getOrElse("Return to setup and use a provider first."),
      "",
      "Completion mode:",
      if model.demoStreamingEnabled then "streaming enabled" else "streaming disabled",
      "",
      "Commands:",
      "help",
      "setup",
      "clear",
      "quit",
      "",
      "Prompt history:",
      "Ctrl-P / Ctrl-N",
      "",
      "Transcript scroll:",
      "ArrowUp / ArrowDown",
      "Ctrl-B / Ctrl-F",
      "Home / End",
      "",
      "State:",
      if model.demoPending then s"$spinner waiting for reply" else "ready"
    )
    base.flatMap(line => wrap(line, width))

  def renderFormattedTranscriptLines(
    logicalLines: List[String],
    width: Int,
    rolePrefix: String,
    continuationPrefix: String,
    baseStyle: Style
  ): List[TranscriptRow] =
    val rows   = List.newBuilder[TranscriptRow]
    var index  = 0
    var isHead = true

    while index < logicalLines.length do
      val line = logicalLines(index)
      val displayPrefix =
        if isHead then rolePrefix
        else continuationPrefix

      setextHeadingContent(line, logicalLines.lift(index + 1)) match
        case Some((content, level)) =>
          rows ++= renderFormattedTranscriptLine(
            content,
            width,
            displayPrefix,
            continuationPrefix,
            headingStyle(baseStyle, level)
          )
          index += 2
        case None =>
          rows ++= renderFormattedTranscriptLine(line, width, displayPrefix, continuationPrefix, baseStyle)
          index += 1

      isHead = false

    rows.result()

  def renderFormattedTranscriptLine(
    line: String,
    width: Int,
    firstPrefix: String,
    continuationPrefix: String,
    baseStyle: Style
  ): List[TranscriptRow] =
    if line.trim.isEmpty then List(TranscriptRow.plain(firstPrefix, baseStyle))
    else
      val (linePrefix, lineContinuationPrefix, content, contentStyle) = transcriptLineFormatting(line, baseStyle)
      val inlineSegments                                              = parseInlineSegments(content, contentStyle)
      wrapSegmentsWithPrefixes(
        inlineSegments,
        width,
        firstPrefix + linePrefix,
        continuationPrefix + lineContinuationPrefix,
        baseStyle
      ).map(TranscriptRow(_, baseStyle))

  def transcriptLineFormatting(line: String, baseStyle: Style): (String, String, String, Style) =
    val Heading       = """^(\s{0,3})(#{1,4})\s+(.*?)\s*#*\s*$""".r
    val UnorderedList = """^(\s*[-*]\s+)(.*)$""".r
    val OrderedList   = """^(\s*\d+\.\s+)(.*)$""".r

    line match
      case Heading(indent, hashes, content) =>
        val level  = hashes.length
        val prefix = indent
        (prefix, prefix, content, headingStyle(baseStyle, level))
      case UnorderedList(prefix, content) =>
        (prefix, " " * prefix.length, content, baseStyle)
      case OrderedList(prefix, content) =>
        (prefix, " " * prefix.length, content, baseStyle)
      case _ =>
        ("", "", line, baseStyle)

  def parseInlineSegments(text: String, baseStyle: Style): List[Text] =
    val codeStyle = Style(fg = Color.Yellow, bg = baseStyle.bg)

    @annotation.tailrec
    def loop(remaining: String, acc: List[Text]): List[Text] =
      if remaining.isEmpty then acc.reverse
      else if remaining.startsWith("**") then
        val end = remaining.indexOf("**", 2)
        if end > 1 then
          loop(
            remaining.drop(end + 2),
            Text(remaining.slice(2, end), baseStyle.copy(bold = true)) :: acc
          )
        else loop(remaining.drop(2), Text("**", baseStyle) :: acc)
      else if remaining.startsWith("`") then
        val end = remaining.indexOf("`", 1)
        if end > 0 then
          loop(
            remaining.drop(end + 1),
            Text(remaining.slice(1, end), codeStyle) :: acc
          )
        else loop(remaining.drop(1), Text("`", baseStyle) :: acc)
      else
        val nextBold = remaining.indexOf("**")
        val nextCode = remaining.indexOf("`")
        val next =
          List(nextBold, nextCode).filter(_ >= 0) match
            case Nil    => remaining.length
            case values => values.min

        loop(remaining.drop(next), Text(remaining.take(next), baseStyle) :: acc)

    mergeAdjacentSegments(loop(text, Nil))

  def mergeAdjacentSegments(segments: List[Text]): List[Text] =
    segments
      .foldLeft(List.empty[Text]) {
        case (Text(headTxt, headStyle) :: tail, Text(txt, style)) if headStyle == style =>
          Text(headTxt + txt, style) :: tail
        case (acc, segment) if segment.txt.nonEmpty =>
          segment :: acc
        case (acc, _) =>
          acc
      }
      .reverse

  def setextHeadingContent(line: String, nextLine: Option[String]): Option[(String, Int)] =
    nextLine.flatMap {
      case underline if underline.matches("""^\s*=+\s*$""") && line.trim.nonEmpty =>
        Some(line -> 1)
      case underline if underline.matches("""^\s*-+\s*$""") && line.trim.nonEmpty =>
        Some(line -> 2)
      case _ =>
        None
    }

  def headingStyle(baseStyle: Style, level: Int): Style =
    baseStyle.copy(
      bold = true,
      underline = level <= 2
    )

  def wrapSegmentsWithPrefixes(
    segments: List[Text],
    width: Int,
    firstPrefix: String,
    continuationPrefix: String,
    prefixStyle: Style
  ): List[List[Text]] =
    if width <= 0 then Nil
    else
      val words = segments.flatMap(segmentToWords)

      if words.isEmpty then List(List(firstPrefix.text(prefixStyle)))
      else
        val lines           = scala.collection.mutable.ListBuffer.empty[List[Text]]
        var currentPrefix   = firstPrefix
        var currentSegments = List(currentPrefix.text(prefixStyle))
        var currentLength   = currentPrefix.length
        var hasContent      = false

        def appendWord(word: Text, lineWidth: Int): Unit =
          val available = math.max(1, lineWidth - currentLength)
          if word.txt.length <= available then
            currentSegments = currentSegments :+ word
            currentLength += word.txt.length
            hasContent = true
          else
            val chunkWidth = math.max(1, lineWidth - currentPrefix.length)
            val chunks     = word.txt.grouped(chunkWidth).toList

            if hasContent then
              lines += currentSegments
              resetLine(continuationPrefix)

            chunks.dropRight(1).foreach { chunk =>
              lines += List(currentPrefix.text(prefixStyle), Text(chunk, word.style))
              resetLine(continuationPrefix)
            }

            currentSegments = currentSegments :+ Text(chunks.last, word.style)
            currentLength += chunks.last.length
            hasContent = true

        def resetLine(prefix: String): Unit =
          currentPrefix = prefix
          currentSegments = List(prefix.text(prefixStyle))
          currentLength = prefix.length
          hasContent = false

        def pushCurrent(): Unit =
          lines += currentSegments
          resetLine(continuationPrefix)

        words.foreach { word =>
          val separatorLength = if hasContent then 1 else 0
          val candidateLength = currentLength + separatorLength + word.txt.length

          if candidateLength <= width then
            if hasContent then
              currentSegments = currentSegments :+ Text(" ", prefixStyle)
              currentLength += 1
            currentSegments = currentSegments :+ word
            currentLength += word.txt.length
            hasContent = true
          else if hasContent then
            pushCurrent()
            appendWord(word, width)
          else appendWord(word, width)
        }

        if currentSegments.nonEmpty then lines += currentSegments
        lines.toList

  def segmentToWords(segment: Text): List[Text] =
    segment.txt.split("\\s+").toList.filter(_.nonEmpty).map(Text(_, segment.style))

  def wrapWithPrefixes(
    text: String,
    width: Int,
    firstPrefix: String,
    continuationPrefix: String
  ): List[String] =
    if width <= 0 then Nil
    else
      val words = text.split("\\s+").toList.filter(_.nonEmpty)
      if words.isEmpty then List(firstPrefix)
      else
        val lines   = scala.collection.mutable.ListBuffer.empty[String]
        val current = new StringBuilder
        var prefix  = firstPrefix

        words.foreach { word =>
          val candidate =
            if current.isEmpty then s"$prefix$word"
            else s"${current.toString} $word"

          if candidate.length <= width then
            current.clear()
            current.append(candidate)
          else if current.nonEmpty then
            lines += current.toString
            current.clear()
            prefix = continuationPrefix
            if (prefix + word).length <= width then current.append(prefix).append(word)
            else
              val chunkWidth = math.max(1, width - prefix.length)
              val chunks     = word.grouped(chunkWidth).toList
              chunks.dropRight(1).foreach(chunk => lines += s"$prefix$chunk")
              current.append(prefix).append(chunks.last)
          else
            val chunkWidth = math.max(1, width - prefix.length)
            val chunks     = word.grouped(chunkWidth).toList
            chunks.dropRight(1).foreach(chunk => lines += s"$prefix$chunk")
            current.append(prefix).append(chunks.last)
        }

        if current.nonEmpty then lines += current.toString
        lines.toList

  def truncate(text: String, width: Int): String =
    DashboardSupport.truncate(text, width)

  def fixedWidth(text: String, width: Int): String =
    val clipped = truncate(text, width)
    if width <= 0 then ""
    else clipped.padTo(width, ' ')

  def fixedWidthSegments(segments: List[Text], width: Int, padStyle: Style): List[Text] =
    if width <= 0 then Nil
    else
      val clipped = takeSegments(segments, width)
      val used    = clipped.map(_.txt.length).sum
      if used >= width then clipped
      else clipped :+ Text(" " * (width - used), padStyle)

  def takeSegments(segments: List[Text], width: Int): List[Text] =
    if width <= 0 then Nil
    else
      val buffer    = scala.collection.mutable.ListBuffer.empty[Text]
      var remaining = width

      segments.foreach { segment =>
        if remaining > 0 then
          val chunk = segment.txt.take(remaining)
          if chunk.nonEmpty then
            buffer += Text(chunk, segment.style)
            remaining -= chunk.length
      }

      buffer.toList

  def overflowLine(hiddenLines: Int, area: String): String =
    s"... $hiddenLines more line(s) hidden in $area; enlarge terminal for more"

  def overflowLineFromTop(hiddenLines: Int, area: String): String =
    s"... $hiddenLines older line(s) hidden in $area; enlarge terminal for more"

  def demoFooterLine(model: Model): String =
    val base =
      if model.demoPending then
        s"Demo: ${waitingSpinner(model)} waiting for assistant reply | setup returns to provider setup"
      else
        s"Demo: ${if model.demoStreamingEnabled then "streaming" else "buffered"} | help, setup, clear, quit | Ctrl-P/N prompt history | Up/Down, Ctrl-B/F, Home/End transcript"

    if model.demoScrollOffset > 0 then s"$base | viewing ${model.demoScrollOffset} line(s) above latest"
    else base

  def compareFooterLine(model: Model): String =
    val pending = model.compareResults.count(_.status == CompareResultStatus.Pending)
    val base =
      if pending > 0 then
        s"Compare: ${waitingSpinner(model)} running $pending pending provider(s) | setup returns to builder | Left/Right switch result tabs | Ctrl-P/N prompt history"
      else "Compare: parallel mode | help, setup, clear, quit | Left/Right switch result tabs | Ctrl-P/N prompt history"
    val compareOffset = model.compareScrollOffsets.lift(model.activeCompareTab).getOrElse(0)
    val withScroll =
      if compareOffset > 0 then s"$base | viewing ${compareOffset} line(s) above latest"
      else base
    model.comparePrompt match
      case Some(prompt) => s"$withScroll | prompt: ${truncate(prompt, 40)}"
      case None         => withScroll

  def transcriptStyle(role: DemoRole): Style = role match
    case DemoRole.System =>
      Style(fg = Color.White, bg = Color.Blue)
    case DemoRole.User =>
      Style(fg = Color.Black, bg = Color.Cyan)
    case DemoRole.Assistant =>
      Style(fg = Color.White, bg = Color.Magenta)

  def waitingSpinner(model: Model): String =
    val frames = Vector("|", "/", "-", "\\")
    frames((model.demoTicks % frames.length).toInt)

  val transcriptMetaStyle   = Style(fg = Color.Cyan, bold = true)
  val transcriptSpacerStyle = Style()

  def modelProviderKindId(providerKey: String): Option[String] =
    ProviderSetupContent.providerDocs.find(_.id.value == providerKey).map(_.id.value)

  def renderCompareResultTabs(model: Model, width: Int): List[(String, Boolean)] =
    val labels = model.compareResults.zipWithIndex.flatMap { case (result, idx) =>
      val status  = compareStatusGlyph(result.status, model)
      val current = List((s"${idx + 1}:${result.selection.providerName} $status", idx == model.activeCompareTab))
      if idx == model.compareResults.length - 1 then current
      else current :+ ("  ", false)
    }.toList
    truncateStyled(labels, width)

  def renderCompareResultRows(model: Model, width: Int): List[TranscriptRow] =
    model.compareResults.lift(model.activeCompareTab) match
      case None =>
        wrap("No compare results yet. Return to setup and build a compare set first.", width)
          .map(TranscriptRow.plain(_, transcriptMetaStyle))
      case Some(result) =>
        val promptStyle = Style(fg = Color.Black, bg = Color.Yellow, bold = true)
        val promptRows =
          model.comparePrompt match
            case Some(prompt) =>
              TranscriptRow.plain("Prompt:", promptStyle) ::
                renderFormattedTranscriptLines(
                  prompt.split("\n", -1).toList,
                  width,
                  "",
                  "",
                  promptStyle
                ) ::: List(TranscriptRow.plain("", transcriptSpacerStyle))
            case None =>
              List(
                TranscriptRow.plain("Prompt:", promptStyle),
                TranscriptRow.plain("none yet", promptStyle),
                TranscriptRow.plain("", transcriptSpacerStyle)
              )

        val latencyLabel =
          result.latencyMs match
            case Some(ms) => s"$ms ms"
            case None     => "running..."

        val responseSizeLabel =
          result.responseChars match
            case Some(chars) => s"$chars chars"
            case None        => "pending"

        val headerRows = List(
          TranscriptRow.plain(""),
          TranscriptRow.plain(s"Provider: ${result.selection.providerName}"),
          TranscriptRow.plain(s"Model: ${result.selection.selectedModel}"),
          TranscriptRow.plain(s"Status: ${compareStatusLabel(result.status)}"),
          TranscriptRow.plain(s"Latency: $latencyLabel"),
          TranscriptRow.plain(s"Response size: $responseSizeLabel"),
          TranscriptRow.plain("")
        )

        val bodyRows = result.status match
          case CompareResultStatus.Pending =>
            wrap(s"${waitingSpinner(model)} Waiting for response...", width).map(TranscriptRow.plain(_))
          case CompareResultStatus.Failure =>
            renderCompareBodyRows(result.error.getOrElse("Unknown compare failure."), width)
          case CompareResultStatus.Success =>
            renderCompareBodyRows(if result.content.nonEmpty then result.content else "No content returned.", width)

        promptRows ++ headerRows ++ bodyRows

  def renderCompareSidebar(model: Model, width: Int): List[String] =
    val pending            = model.compareResults.count(_.status == CompareResultStatus.Pending)
    val successes          = model.compareResults.count(_.status == CompareResultStatus.Success)
    val failures           = model.compareResults.count(_.status == CompareResultStatus.Failure)
    val completedLatencies = model.compareResults.flatMap(_.latencyMs)
    val averageLatencyLabel =
      if completedLatencies.nonEmpty then s"${completedLatencies.sum / completedLatencies.size} ms"
      else "pending"
    val fastestProvider =
      model.compareResults
        .flatMap(result => result.latencyMs.map(ms => result.selection.providerName -> ms))
        .sortBy(_._2)
        .headOption
    val slowestProvider =
      model.compareResults
        .flatMap(result => result.latencyMs.map(ms => result.selection.providerName -> ms))
        .sortBy(_._2)
        .lastOption
    val longestResponse =
      model.compareResults
        .flatMap(result => result.responseChars.map(chars => result.selection.providerName -> chars))
        .sortBy(_._2)
        .lastOption
    val fastestLabel =
      fastestProvider match
        case Some((name, ms)) => s"$name ($ms ms)"
        case None             => "pending"
    val slowestLabel =
      slowestProvider match
        case Some((name, ms)) => s"$name ($ms ms)"
        case None             => "pending"
    val largestReplyLabel =
      longestResponse match
        case Some((name, chars)) => s"$name ($chars chars)"
        case None                => "pending"
    val compareSet =
      if model.compareSelections.isEmpty then List("Compare set is empty.")
      else
        model.compareSelections.zipWithIndex.map { case (selection, idx) =>
          val highlighted = idx == model.activeCompareTab && model.compareResults.nonEmpty
          val marker      = if highlighted then " >" else "  "
          s"$marker ${selection.providerName} / ${selection.selectedModel}"
        }.toList

    (List(
      "Run status:",
      s"pending: $pending",
      s"success: $successes",
      s"failure: $failures",
      s"avg latency: $averageLatencyLabel",
      s"fastest: $fastestLabel",
      s"slowest: $slowestLabel",
      s"largest reply: $largestReplyLabel",
      "",
      "Compare set:"
    ) ++ compareSet ++ List(
      "",
      "Commands:",
      "help",
      "setup",
      "clear",
      "quit",
      "",
      "Navigation:",
      "Left/Right switch result tabs",
      "Up/Down scroll result",
      "Home/End jump oldest/latest",
      "",
      "Prompt history:",
      "Ctrl-P / Ctrl-N"
    )).flatMap(line => wrap(line, width))

  def compareStatusGlyph(status: CompareResultStatus, model: Model): String =
    status match
      case CompareResultStatus.Pending => waitingSpinner(model)
      case CompareResultStatus.Success => "✓"
      case CompareResultStatus.Failure => "✗"

  def compareStatusLabel(status: CompareResultStatus): String =
    status match
      case CompareResultStatus.Pending => "pending"
      case CompareResultStatus.Success => "success"
      case CompareResultStatus.Failure => "failure"

  def renderCompareBodyRows(content: String, width: Int): List[TranscriptRow] =
    renderFormattedTranscriptLines(
      content.split("\n", -1).toList,
      width,
      "",
      "",
      Style()
    )
