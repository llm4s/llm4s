package org.llm4s.samples.dashboard.repro

import org.llm4s.samples.dashboard.repro.ProviderChatRenderReproApp.{ Entry, Model, Role }
import termflow.tui.{ Color, PromptHistory, RootNode, Style, VNode }
import termflow.tui.*
import termflow.tui.TuiPrelude.*

private def ProviderChatRenderReproView(model: Model) = {
  val width              = math.max(model.terminalWidth, 72)
  val height             = math.max(model.terminalHeight, 20)
  val outerWidth         = math.max(68, width - 4)
  val leftWidth          = weightedWidth(outerWidth, 1, Vector(42, 26), 0)
  val rightWidth         = weightedWidth(outerWidth, 1, Vector(42, 26), 1)
  val panelTop           = 6
  val panelHeight        = math.max(10, height - 12)
  val transcriptCapacity = math.max(1, panelHeight - 2)
  val promptRow          = panelTop + panelHeight + 3
  val renderedPrompt     = PromptHistory.renderWithPrefix(model.prompt, "repro> ")

  val transcriptLines =
    fixedPanelRows(renderVisibleTranscript(model.entries, leftWidth - 4, transcriptCapacity), transcriptCapacity)
  val sidebarLines =
    fixedPanelRows(
      clipPanel(renderSidebar(model, rightWidth - 4), transcriptCapacity, "sidebar"),
      transcriptCapacity
    )

  val children: List[VNode] =
    List(
      BoxNode(1.x, 1.y, outerWidth, 4, children = Nil, style = Style(border = true, fg = Color.Cyan)),
      TextNode(
        3.x,
        2.y,
        List("Provider Chat Render Repro".text(Style(fg = Color.Yellow, bold = true, underline = true)))
      ),
      TextNode(3.x, 3.y, List(fixedWidth(model.status, outerWidth - 4).text(Style(fg = Color.Green)))),
      BoxNode(
        1.x,
        panelTop.y,
        leftWidth,
        panelHeight,
        children = Nil,
        style = Style(border = true, fg = Color.Blue)
      ),
      BoxNode(
        (leftWidth + 2).x,
        panelTop.y,
        rightWidth,
        panelHeight,
        children = Nil,
        style = Style(border = true, fg = Color.Magenta)
      ),
      TextNode(3.x, (panelTop + 1).y, List("Transcript".text(Style(fg = Color.Yellow, bold = true)))),
      TextNode((leftWidth + 4).x, (panelTop + 1).y, List("Session".text(Style(fg = Color.Yellow, bold = true)))),
      TextNode(
        3.x,
        (promptRow - 1).y,
        List(
          fixedWidth(
            "Repro: seed, clear, help, quit | ArrowUp/Down recall prompt history",
            outerWidth - 4
          ).text(Style(fg = Color.Cyan))
        )
      )
    ) ++
      transcriptLines.zipWithIndex.map { case (line, idx) =>
        TextNode(3.x, (panelTop + 2 + idx).y, List(fixedWidth(line, leftWidth - 4).text))
      } ++
      sidebarLines.zipWithIndex.map { case (line, idx) =>
        TextNode((leftWidth + 4).x, (panelTop + 2 + idx).y, List(fixedWidth(line, rightWidth - 4).text))
      }

  RootNode(
    width = width,
    height = height,
    children = children,
    input = Some(
      InputNode(
        3.x,
        promptRow.y,
        renderedPrompt.text,
        Style(fg = Color.Green),
        cursor = renderedPrompt.cursorIndex,
        lineWidth = outerWidth - 4
      )
    )
  )
}

private def renderVisibleTranscript(entries: Vector[Entry], width: Int, capacity: Int): List[String] =
  if entries.isEmpty then
    clipPanelFromEnd(wrap("No transcript entries yet. Type something or use `seed`.", width), capacity, "transcript")
  else
    val visibleLines = scala.collection.mutable.ListBuffer.empty[String]
    var hiddenLines  = 0

    entries.reverseIterator.foreach { entry =>
      val label = entry.role match
        case Role.System    => "system"
        case Role.User      => "you"
        case Role.Assistant => "assistant"
      val block = wrap(s"$label: ${entry.content}", width) ++ List("")
      if visibleLines.length + block.length <= capacity then visibleLines.prependAll(block.reverseIterator)
      else hiddenLines += block.length
    }

    val lines = visibleLines.toList
    if hiddenLines > 0 && capacity > 1 then
      overflowLineFromTop(hiddenLines, "transcript") +: lines.takeRight(capacity - 1)
    else lines.takeRight(capacity)

private def renderSidebar(model: Model, width: Int): List[String] =
  List(
    "Mode: repro",
    "",
    "Purpose:",
    "Isolate redraw behavior",
    "for transcript + sidebar",
    "+ bottom prompt layout.",
    "",
    "Commands:",
    "help",
    "seed",
    "clear",
    "quit",
    "",
    "Prompt history:",
    "ArrowUp / ArrowDown",
    "",
    s"Seed round: ${model.seedRound}",
    "",
    s"Entries: ${model.entries.length}"
  ).flatMap(line => wrap(line, width))

private def clipPanel(lines: List[String], capacity: Int, area: String): List[String] =
  val overflow = math.max(0, lines.length - capacity)
  if overflow > 0 && capacity > 1 then lines.take(capacity - 1) :+ overflowLine(overflow, area)
  else lines.take(capacity)

private def clipPanelFromEnd(lines: List[String], capacity: Int, area: String): List[String] =
  val overflow = math.max(0, lines.length - capacity)
  if overflow > 0 && capacity > 1 then overflowLineFromTop(overflow, area) +: lines.takeRight(capacity - 1)
  else lines.takeRight(capacity)

private def fixedPanelRows(lines: List[String], capacity: Int): List[String] =
  if capacity <= 0 then Nil
  else
    val filled = lines.take(capacity)
    filled ++ List.fill(math.max(0, capacity - filled.length))("")

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

private def wrap(text: String, width: Int): List[String] =
  if width <= 0 then Nil
  else
    val words = text.split("\\s+").toList.filter(_.nonEmpty)
    if words.isEmpty then List("")
    else
      val lines   = scala.collection.mutable.ListBuffer.empty[String]
      val current = new StringBuilder
      words.foreach { word =>
        val candidate = if current.isEmpty then word else s"${current.toString} $word"
        if candidate.length <= width then
          current.clear()
          current.append(candidate)
        else if current.nonEmpty then
          lines += current.toString
          current.clear()
          if word.length <= width then current.append(word)
          else
            val chunks = word.grouped(math.max(1, width - 1)).toList
            chunks.dropRight(1).foreach(chunk => lines += chunk)
            current.append(chunks.last)
        else
          val chunks = word.grouped(math.max(1, width - 1)).toList
          chunks.dropRight(1).foreach(chunk => lines += chunk)
          current.append(chunks.last)
      }
      if current.nonEmpty then lines += current.toString
      lines.toList

private def fixedWidth(text: String, width: Int): String =
  val clipped = truncate(text, width)
  if width <= 0 then "" else clipped.padTo(width, ' ')

private def truncate(text: String, width: Int): String =
  if width <= 0 then ""
  else if text.length <= width then text
  else if width == 1 then "…"
  else text.take(width - 1) + "…"

private def overflowLine(hiddenLines: Int, area: String): String =
  s"... $hiddenLines more line(s) hidden in $area; enlarge terminal for more"

private def overflowLineFromTop(hiddenLines: Int, area: String): String =
  s"... $hiddenLines older line(s) hidden in $area; enlarge terminal for more"

private val seedEntries: Vector[Entry] = Vector(
  Entry(Role.System, "Repro ready. Use this sample to observe redraw behavior without any real llm requests."),
  Entry(Role.System, "Resize the terminal, type quickly, and use `seed` to grow the transcript.")
)
