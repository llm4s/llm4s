package org.llm4s.samples.dashboard.providersetup.update

import org.llm4s.samples.dashboard.providersetup.ProviderSetupMessages.*
import org.llm4s.samples.dashboard.providersetup.ProviderSetupModel.*
import termflow.tui.Tui
import termflow.tui.Tui.*

private[providersetup] object ProviderSetupAsyncSupport:

  def handleTick(model: Model): Tui[Model, Msg] =
    if hasPendingActivity(model) then model.updateDemo(_.copy(ticks = model.demoTicks + 1)).tui
    else model.tui

  def handleDemoResponse(model: Model, result: Either[String, String]): Tui[Model, Msg] =
    result match
      case Right(response) =>
        val nextEntries =
          if model.demoStreamingEnabled then replacePendingAssistant(model.demoEntries, response)
          else model.demoEntries :+ DemoEntry(DemoRole.Assistant, response)
        model
          .updateDemo(
            _.copy(
              entries = nextEntries,
              scrollOffset =
                if model.demoStreamingEnabled then
                  clampDemoScrollOffset(model.updateDemo(_.copy(entries = nextEntries)), model.demoScrollOffset)
                else
                  ProviderSetupInputSupport.preserveDemoViewport(
                    model,
                    DemoEntry(DemoRole.Assistant, response),
                    nextEntries
                  )
              ,
              pending = false
            )
          )
          .copy(
            statusLine = "Demo response received."
          )
          .tui
      case Left(error) =>
        val nextEntry   = DemoEntry(DemoRole.System, s"Demo error: $error")
        val nextEntries = model.demoEntries :+ nextEntry
        model
          .updateDemo(
            _.copy(
              entries = nextEntries,
              scrollOffset = ProviderSetupInputSupport.preserveDemoViewport(model, nextEntry, nextEntries),
              pending = false
            )
          )
          .copy(
            statusLine = s"Demo request failed: $error"
          )
          .tui

  def handleCompareResponse(
    model: Model,
    providerName: String,
    latencyMs: Long,
    result: Either[String, String]
  ): Tui[Model, Msg] =
    val nextResults = model.compareResults.map { current =>
      if current.selection.providerName == providerName then
        result match
          case Right(text) =>
            current.copy(
              status = CompareResultStatus.Success,
              content = text,
              error = None,
              latencyMs = Some(latencyMs),
              responseChars = Some(text.length)
            )
          case Left(error) =>
            current.copy(
              status = CompareResultStatus.Failure,
              content = "",
              error = Some(error),
              latencyMs = Some(latencyMs),
              responseChars = None
            )
      else current
    }
    val pending = nextResults.count(_.status == CompareResultStatus.Pending)
    val nextStatus =
      result match
        case Right(_) if pending > 0 => s"${providerName} finished. Waiting for $pending more provider(s)."
        case Right(_)                => s"Compare run complete across ${nextResults.size} provider(s)."
        case Left(error) if pending > 0 =>
          s"${providerName} failed: $error. Waiting for $pending more provider(s)."
        case Left(error) =>
          s"Compare finished with failures. Last error from $providerName: $error"
    model
      .updateCompare(_.copy(results = nextResults))
      .copy(statusLine = nextStatus)
      .tui

  def handleDemoChunk(model: Model, chunk: String): Tui[Model, Msg] =
    if chunk.trim.isEmpty then model.tui
    else
      val nextEntries = appendAssistantChunk(model.demoEntries, chunk)
      model
        .updateDemo(
          _.copy(
            entries = nextEntries,
            scrollOffset =
              clampDemoScrollOffset(model.updateDemo(_.copy(entries = nextEntries)), model.demoScrollOffset)
          )
        )
        .copy(
          statusLine = "Streaming assistant reply..."
        )
        .tui

  private def hasPendingActivity(model: Model): Boolean =
    model.demoPending || model.compareResults.exists(_.status == CompareResultStatus.Pending)

  private def appendAssistantChunk(entries: Vector[DemoEntry], chunk: String): Vector[DemoEntry] =
    entries.lastOption match
      case Some(DemoEntry(DemoRole.Assistant, content)) =>
        entries.updated(entries.length - 1, DemoEntry(DemoRole.Assistant, content + chunk))
      case _ =>
        entries :+ DemoEntry(DemoRole.Assistant, chunk)

  private def replacePendingAssistant(entries: Vector[DemoEntry], response: String): Vector[DemoEntry] =
    entries.lastOption match
      case Some(DemoEntry(DemoRole.Assistant, _)) =>
        entries.updated(entries.length - 1, DemoEntry(DemoRole.Assistant, response))
      case _ =>
        entries :+ DemoEntry(DemoRole.Assistant, response)

  private def clampDemoScrollOffset(model: Model, requestedOffset: Int): Int =
    math.max(0, math.min(requestedOffset, demoMaxScrollOffset(model)))

  private def demoMaxScrollOffset(model: Model): Int =
    math.max(0, demoTranscriptLineCount(model) - demoTranscriptCapacity(model))

  private def demoTranscriptCapacity(model: Model): Int =
    val height      = math.max(model.terminalHeight, 20)
    val panelHeight = math.max(10, height - 12)
    math.max(1, panelHeight - 2)

  private def demoTranscriptWidth(model: Model): Int =
    val width      = math.max(model.terminalWidth, 72)
    val outerWidth = math.max(68, width - 4)
    val leftWidth  = weightedWidth(outerWidth, 1, Vector(42, 26), 0)
    math.max(1, leftWidth - 4)

  private def demoTranscriptLineCount(model: Model): Int =
    if model.demoEntries.isEmpty then
      wrap("No demo messages yet. Type a prompt below to start.", demoTranscriptWidth(model)).length
    else model.demoEntries.map(entry => demoTranscriptBlockLineCount(entry, demoTranscriptWidth(model))).sum

  private def demoTranscriptBlockLineCount(entry: DemoEntry, width: Int): Int =
    val label = entry.role match
      case DemoRole.System    => "system"
      case DemoRole.User      => "you"
      case DemoRole.Assistant => "assistant"
    val rolePrefix         = s"$label: "
    val continuationPrefix = " " * rolePrefix.length

    formattedTranscriptLinesCount(
      entry.content.split("\n", -1).toList,
      width,
      rolePrefix,
      continuationPrefix
    ) + 1

  private def formattedTranscriptLinesCount(
    logicalLines: List[String],
    width: Int,
    rolePrefix: String,
    continuationPrefix: String
  ): Int =
    var count  = 0
    var index  = 0
    var isHead = true

    while index < logicalLines.length do
      val line = logicalLines(index)
      val displayPrefix =
        if isHead then rolePrefix
        else continuationPrefix

      setextHeadingContent(line, logicalLines.lift(index + 1)) match
        case Some(content) =>
          count += formattedTranscriptLineCount(content, width, displayPrefix, continuationPrefix)
          index += 2
        case None =>
          count += formattedTranscriptLineCount(line, width, displayPrefix, continuationPrefix)
          index += 1

      isHead = false

    count

  private def formattedTranscriptLineCount(
    line: String,
    width: Int,
    firstPrefix: String,
    continuationPrefix: String
  ): Int =
    if line.trim.isEmpty then 1
    else
      val (linePrefix, lineContinuationPrefix, content) = transcriptLinePrefixes(line)
      wrapWithPrefixes(
        stripInlineMarkers(content),
        width,
        firstPrefix + linePrefix,
        continuationPrefix + lineContinuationPrefix
      ).length

  private def transcriptLinePrefixes(line: String): (String, String, String) =
    val Heading       = """^(\s{0,3})(#{1,4})\s+(.*?)\s*#*\s*$""".r
    val UnorderedList = """^(\s*[-*]\s+)(.*)$""".r
    val OrderedList   = """^(\s*\d+\.\s+)(.*)$""".r

    line match
      case Heading(indent, _, content) =>
        (indent, indent, content)
      case UnorderedList(prefix, content) =>
        (prefix, " " * prefix.length, content)
      case OrderedList(prefix, content) =>
        (prefix, " " * prefix.length, content)
      case _ =>
        ("", "", line)

  private def setextHeadingContent(line: String, nextLine: Option[String]): Option[String] =
    nextLine.flatMap {
      case underline if underline.matches("""^\s*[=-]+\s*$""") && line.trim.nonEmpty =>
        Some(line)
      case _ =>
        None
    }

  private def stripInlineMarkers(text: String): String =
    text.replace("**", "").replace("`", "")

  private def wrap(text: String, width: Int): List[String] =
    wrapWithPrefixes(text, width, "", "")

  private def wrapWithPrefixes(
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

  private def weightedWidth(totalWidth: Int, gapWidth: Int, weights: Vector[Int], index: Int): Int =
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
