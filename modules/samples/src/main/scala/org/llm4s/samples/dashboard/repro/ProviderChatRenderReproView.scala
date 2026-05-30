package org.llm4s.samples.dashboard.repro

import org.llm4s.samples.dashboard.repro.ProviderChatRenderReproApp.{ Entry, Model, Role }
import termflow.tui.*
import termflow.tui.TuiPrelude.*
import termflow.tui.widgets

/**
 * Modernized repro view using termflow 0.3.0 widgets:
 *
 *   - `widgets.LogView` renders the transcript with built-in wrap, scrollback,
 *     and `LogView.Viewport` for mouse-wheel routing.
 *   - `widgets.StatusBar` for the bottom inverse-video footer.
 *   - `given Theme = Theme.dark` for a coherent palette across widgets.
 *
 * The two-panel transcript+sidebar layout is kept on absolute coordinates
 * because the panel borders read more clearly than nested `Layout.Row` /
 * `Layout.Column` for this case.
 */
private def ProviderChatRenderReproView(model: Model): RootNode =
  given Theme = Theme.dark

  val width        = math.max(model.terminalWidth, 60)
  val height       = math.max(model.terminalHeight, 16)
  val outerWidth   = math.max(56, width - 2)
  val leftWidth    = weightedWidth(outerWidth, 1, Vector(42, 26), 0)
  val rightWidth   = weightedWidth(outerWidth, 1, Vector(42, 26), 1)
  val panelTop     = 5
  val panelHeight  = math.max(10, height - 9)
  val statusBarRow = height - 1
  val promptRow    = height

  val transcriptOrigin = transcriptOriginFor(model)
  val transcriptWidth  = transcriptWidthFor(model)
  val transcriptHeight = transcriptHeightFor(model)

  val renderedPrompt = PromptHistory.renderWithPrefix(model.prompt, "repro> ")

  val sidebarLines = renderSidebar(model, rightWidth - 4).take(panelHeight - 2)

  val children: List[VNode] =
    List(
      // Header.
      BoxNode(1.x, 1.y, outerWidth, 4, children = Nil, style = Style(border = true, fg = Color.Cyan)),
      TextNode(
        3.x,
        2.y,
        List("Provider Chat Render Repro".text(Style(fg = Color.Yellow, bold = true, underline = true)))
      ),
      TextNode(
        3.x,
        3.y,
        List(fixedWidth(model.status, outerWidth - 4).text(Style(fg = Color.Green)))
      ),
      // Two-panel body: transcript (left) + session info (right).
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
      TextNode(3.x, panelTop.y, List(" Transcript ".text(Style(fg = Color.Yellow, bold = true)))),
      TextNode(
        (leftWidth + 4).x,
        panelTop.y,
        List(" Session ".text(Style(fg = Color.Yellow, bold = true)))
      )
    ) ++
      // Modern: widgets.LogView replaces the hand-rolled transcript paginator.
      widgets.LogView(
        lines = transcriptLines(model.entries),
        width = transcriptWidth,
        height = transcriptHeight,
        scrollOffset = model.scrollOffset,
        at = transcriptOrigin,
        wrap = true
      ) ++
      sidebarLines.zipWithIndex.map { case (line, idx) =>
        TextNode(
          (leftWidth + 4).x,
          (panelTop + 1 + idx).y,
          List(fixedWidth(line, rightWidth - 4).text)
        )
      } :+
      widgets.StatusBar(
        left = " repro ",
        center = s"entries ${model.entries.size}  •  seed-round ${model.seedRound}",
        right = if model.autoTail then " auto-tail " else " paused ",
        width = width,
        at = Coord(1.x, statusBarRow.y)
      )

  RootNode(
    width = width,
    height = height,
    children = children,
    input = Some(
      InputNode(
        1.x,
        promptRow.y,
        renderedPrompt.text,
        Style(fg = Color.Green),
        cursor = renderedPrompt.cursorIndex,
        lineWidth = math.max(1, width - 1),
        prefixLength = renderedPrompt.prefixLength
      )
    )
  )

/** Convert the entries vector to a flat display-line buffer for `LogView.expand`. */
def transcriptLines(entries: Vector[Entry]): Vector[String] =
  if entries.isEmpty then Vector("No transcript entries yet. Type something or use `seed`.")
  else
    entries
      .flatMap(e => Vector(s"${e.label}: ${e.content}", ""))
      .dropRight(1)

/** Top-left of the transcript pane, including a 1-cell border + 1-cell header offset. */
def transcriptOriginFor(@scala.annotation.unused model: Model): Coord =
  Coord(3.x, 6.y)

/** Cell width of the transcript LogView viewport. */
def transcriptWidthFor(model: Model): Int =
  val width      = math.max(model.terminalWidth, 60)
  val outerWidth = math.max(56, width - 2)
  val leftWidth  = weightedWidth(outerWidth, 1, Vector(42, 26), 0)
  math.max(8, leftWidth - 4)

/** Cell height of the transcript LogView viewport. */
def transcriptHeightFor(model: Model): Int =
  val height      = math.max(model.terminalHeight, 16)
  val panelHeight = math.max(10, height - 9)
  math.max(2, panelHeight - 2)

/**
 * Mouse-wheel viewport rectangle for the transcript (termflow 0.4 feature).
 * `LogView.scrollDelta` returns `Some(delta)` only when the wheel lands inside
 * this rect — wheel events over the sidebar / prompt are ignored.
 */
def transcriptViewport(model: Model): widgets.LogView.Viewport =
  widgets.LogView.Viewport(
    transcriptOriginFor(model),
    transcriptWidthFor(model),
    transcriptHeightFor(model)
  )

/** Maximum scroll offset for the current entries + viewport size. */
def transcriptMaxScroll(model: Model): Int =
  widgets.LogView.maxScroll(
    transcriptLines(model.entries),
    transcriptWidthFor(model),
    transcriptHeightFor(model),
    wrap = true
  )

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
    "Scroll:",
    "↑/↓  PgUp/PgDn",
    "wheel · End",
    "",
    s"Seed round: ${model.seedRound}",
    s"Entries: ${model.entries.length}"
  ).flatMap(line => wrap(line, width))

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

private[repro] val seedEntries: Vector[Entry] = Vector(
  Entry(Role.System, "Repro ready. Use this sample to observe redraw behavior without any real llm requests."),
  Entry(Role.System, "Resize the terminal, type quickly, and use `seed` to grow the transcript.")
)
