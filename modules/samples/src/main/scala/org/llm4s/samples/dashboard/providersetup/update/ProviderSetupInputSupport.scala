package org.llm4s.samples.dashboard.providersetup.update

import org.llm4s.samples.dashboard.providersetup.ProviderSetupMessages.*
import org.llm4s.samples.dashboard.providersetup.ProviderSetupModel.*
import termflow.tui.*
import termflow.tui.Tui.*
import termflow.tui.TuiPrelude.*

private[providersetup] object ProviderSetupInputSupport:

  def inputToMsg(input: PromptLine): Result[Msg] =
    input.value.trim match
      case ""                     => Right(Msg.Setup(SetupMsg.RunCommand("help")))
      case "quit" | "exit" | ":q" => Right(Msg.Global(GlobalMsg.ExitRequested))
      case other                  => Right(Msg.Setup(SetupMsg.RunCommand(other)))

  def handlePromptInput(model: Model, msg: Msg): Tui[Model, Msg] =
    msg match
      case Msg.Setup(SetupMsg.ConsoleInputKey(key)) =>
        val (nextPrompt, maybeCmd) = PromptHistory.handleKey[Msg](model.prompt, key)(inputToMsg)
        maybeCmd match
          case Some(cmd) => Tui(model.updateShell(_.copy(prompt = nextPrompt)), cmd)
          case None      => model.updateShell(_.copy(prompt = nextPrompt)).tui
      case _ =>
        model.tui

  def welcomeEntries(session: ActiveSession): Vector[DemoEntry] =
    Vector(
      DemoEntry(DemoRole.System, s"Demo ready with ${session.label}."),
      DemoEntry(DemoRole.System, session.note)
    )

  def handleDemoScrollKey(model: Model, key: KeyDecoder.InputKey): Option[Tui[Model, Msg]] =
    key match
      case KeyDecoder.InputKey.ArrowUp =>
        Some(scrollDemoTranscript(model, 1, "Scrolled transcript older."))
      case KeyDecoder.InputKey.ArrowDown =>
        Some(scrollDemoTranscript(model, -1, "Scrolled transcript newer."))
      case KeyDecoder.InputKey.Ctrl('B') =>
        Some(scrollDemoTranscript(model, demoScrollPageSize(model), "Paged transcript older."))
      case KeyDecoder.InputKey.Ctrl('F') =>
        Some(scrollDemoTranscript(model, -demoScrollPageSize(model), "Paged transcript newer."))
      case KeyDecoder.InputKey.Home =>
        Some(scrollDemoToOffset(model, demoMaxScrollOffset(model), "Jumped to oldest visible transcript."))
      case KeyDecoder.InputKey.End =>
        Some(scrollDemoToOffset(model, 0, "Returned transcript to latest."))
      case _ =>
        None

  def handleHistoryRecallKey(model: Model, key: KeyDecoder.InputKey): Option[Tui[Model, Msg]] =
    val remapped =
      key match
        case KeyDecoder.InputKey.Ctrl('P') => Some(KeyDecoder.InputKey.ArrowUp)
        case KeyDecoder.InputKey.Ctrl('N') => Some(KeyDecoder.InputKey.ArrowDown)
        case _                             => None

    remapped.map { historyKey =>
      val (nextPrompt, maybeCmd) = PromptHistory.handleKey[Msg](model.prompt, historyKey)(inputToMsg)
      maybeCmd match
        case Some(cmd) => Tui(model.updateShell(_.copy(prompt = nextPrompt)), cmd)
        case None      => model.updateShell(_.copy(prompt = nextPrompt)).tui
    }

  def preserveDemoViewport(model: Model, appendedEntry: DemoEntry, nextEntries: Vector[DemoEntry]): Int =
    if model.demoScrollOffset <= 0 then 0
    else
      val requested = model.demoScrollOffset + demoTranscriptBlockLineCount(appendedEntry, demoTranscriptWidth(model))
      clampDemoScrollOffset(model.updateDemo(_.copy(entries = nextEntries)), requested)

  def demoTranscriptCapacity(model: Model): Int =
    val height      = math.max(model.terminalHeight, 20)
    val panelHeight = math.max(10, height - 12)
    math.max(1, panelHeight - 2)

  private def scrollDemoTranscript(model: Model, delta: Int, message: String): Tui[Model, Msg] =
    scrollDemoToOffset(model, model.demoScrollOffset + delta, message)

  private def scrollDemoToOffset(model: Model, requestedOffset: Int, message: String): Tui[Model, Msg] =
    val nextOffset = clampDemoScrollOffset(model, requestedOffset)
    val nextStatus =
      if nextOffset == 0 then "Transcript pinned to latest messages."
      else s"$message ${nextOffset} line(s) above latest."
    model
      .updateDemo(_.copy(scrollOffset = nextOffset))
      .copy(statusLine = nextStatus)
      .tui

  private def clampDemoScrollOffset(model: Model, requestedOffset: Int): Int =
    math.max(0, math.min(requestedOffset, demoMaxScrollOffset(model)))

  private def demoMaxScrollOffset(model: Model): Int =
    math.max(0, demoTranscriptLineCount(model) - demoTranscriptCapacity(model))

  private def demoScrollPageSize(model: Model): Int =
    math.max(1, demoTranscriptCapacity(model) - 2)

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
