package org.llm4s.samples.dashboard.llm4s

import org.llm4s.samples.dashboard.llm4s.Llm4sDashboardApp.DashboardModel
import org.llm4s.samples.dashboard.shared.DashboardSupport
import termflow.tui.TuiPrelude.*
import termflow.tui.*
import termflow.tui.widgets

/**
 * Modernized Llm4sDashboard view using termflow 0.3.0.
 *
 *   - `widgets.LogView` for the recent-events scrollback (scrolling +
 *     mouse-wheel via `LogView.Viewport`).
 *   - `widgets.StatusBar` for the inverse-video footer row.
 *   - `given Theme = Theme.dark` so widgets pick up a coherent palette.
 *
 * The pane layout still uses absolute coordinates rather than `Layout.Border`
 * because of the side-by-side summary/commands panels above a full-width
 * events log; that two-row mixed layout reads more clearly with hand-placed
 * boxes than with a Border + nested Row/Column tree.
 */
def Llm4sDashboardView(model: DashboardModel): RootNode =
  given Theme = Theme.dark

  val width        = math.max(model.terminalWidth, 60)
  val height       = math.max(model.terminalHeight, 16)
  val outerWidth   = math.max(56, width - 2)
  val leftWidth    = math.max(28, (outerWidth - 1) / 2)
  val rightWidth   = math.max(26, outerWidth - leftWidth - 1)
  val titleHeight  = 4
  val titleTop     = 1
  val panelTop     = titleTop + titleHeight
  val panelHeight  = math.max(7, height - 14)
  val eventsTop    = panelTop + panelHeight
  val eventsHeight = math.max(4, height - eventsTop - 4)
  val statusBarRow = height - 1
  val promptRow    = height

  val renderedPrompt = PromptHistory.renderWithPrefix(model.prompt, "dashboard> ")

  val summaryLines = List(
    "llm4s + termflow integration demo",
    s"clock: ${model.now}",
    s"ticks: ${model.ticks}",
    s"terminal: ${model.terminalWidth} x ${model.terminalHeight}"
  ).map(DashboardSupport.truncate(_, leftWidth - 4))

  val commandLines = List(
    "help     show available commands",
    "provider show resolved LLM provider config",
    "pulse    append a dashboard event",
    "clear    clear recent events",
    "quit     exit the dashboard"
  ).map(DashboardSupport.truncate(_, rightWidth - 4))

  // Header box: title + status.
  val headerNodes: List[VNode] = List(
    BoxNode(1.x, titleTop.y, outerWidth, titleHeight, children = Nil, style = Style(border = true, fg = Color.Cyan)),
    TextNode(
      3.x,
      (titleTop + 1).y,
      List("LLM4S Dashboard".text(Style(fg = Color.Yellow, bold = true, underline = true)))
    ),
    TextNode(
      3.x,
      (titleTop + 2).y,
      List(DashboardSupport.truncate(model.status, outerWidth - 4).text(Style(fg = Color.Green)))
    )
  )

  // Two side-by-side panels (summary | commands).
  val panelNodes: List[VNode] = List(
    BoxNode(
      1.x,
      panelTop.y,
      leftWidth,
      panelHeight,
      children = Nil,
      style = Style(border = true, fg = Color.Blue)
    ),
    TextNode(3.x, (panelTop + 1).y, List("Summary".text(Style(fg = Color.Yellow, bold = true)))),
    BoxNode(
      (leftWidth + 2).x,
      panelTop.y,
      rightWidth,
      panelHeight,
      children = Nil,
      style = Style(border = true, fg = Color.Magenta)
    ),
    TextNode((leftWidth + 4).x, (panelTop + 1).y, List("Commands".text(Style(fg = Color.Yellow, bold = true))))
  ) ++
    summaryLines.zipWithIndex.map { case (line, idx) =>
      TextNode(3.x, (panelTop + 2 + idx).y, List(line.text))
    } ++
    commandLines.zipWithIndex.map { case (line, idx) =>
      TextNode((leftWidth + 4).x, (panelTop + 2 + idx).y, List(line.text))
    }

  // Events log (full width) — modern: widgets.LogView with mouse-wheel viewport.
  val logViewportWidth  = math.max(10, outerWidth - 2)
  val logViewportHeight = math.max(2, eventsHeight - 2)
  val logOrigin         = Coord(3.x, (eventsTop + 1).y)
  val eventsBoxNodes: List[VNode] = List(
    BoxNode(1.x, eventsTop.y, outerWidth, eventsHeight, children = Nil, style = Style(border = true, fg = Color.Green)),
    TextNode(3.x, eventsTop.y, List(" Recent events ".text(Style(fg = Color.Yellow, bold = true))))
  ) ++ widgets.LogView(
    lines = model.events,
    width = logViewportWidth,
    height = logViewportHeight,
    scrollOffset = model.eventScrollOffset,
    at = logOrigin,
    wrap = true
  )

  // Modern: widgets.StatusBar replaces the hand-rolled bottom row.
  val statusBarNode = widgets.StatusBar(
    left = s" provider: ${DashboardSupport.truncate(model.providerStatus, math.max(8, outerWidth / 3))} ",
    center = s"events ${model.events.size} • ticks ${model.ticks}",
    right = if model.eventAutoTail then " auto-tail " else " paused ",
    width = width,
    at = Coord(1.x, statusBarRow.y)
  )

  val children: List[VNode] =
    headerNodes ++ panelNodes ++ eventsBoxNodes :+ statusBarNode

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

/** Cell width of the events LogView viewport — kept in lockstep with the renderer above. */
def Llm4sDashboardEventsViewportWidth(model: DashboardModel): Int =
  val width      = math.max(model.terminalWidth, 60)
  val outerWidth = math.max(56, width - 2)
  math.max(10, outerWidth - 2)

/** Cell height of the events LogView viewport. */
def Llm4sDashboardEventsViewportHeight(model: DashboardModel): Int =
  val height       = math.max(model.terminalHeight, 16)
  val titleTop     = 1
  val titleHeight  = 4
  val panelTop     = titleTop + titleHeight
  val panelHeight  = math.max(7, height - 14)
  val eventsTop    = panelTop + panelHeight
  val eventsHeight = math.max(4, height - eventsTop - 4)
  math.max(2, eventsHeight - 2)

/** Top-left of the events LogView viewport in absolute terminal cells. */
def Llm4sDashboardEventsViewportOrigin(model: DashboardModel): Coord =
  val height      = math.max(model.terminalHeight, 16)
  val titleTop    = 1
  val titleHeight = 4
  val panelTop    = titleTop + titleHeight
  val panelHeight = math.max(7, height - 14)
  val eventsTop   = panelTop + panelHeight
  Coord(3.x, (eventsTop + 1).y)

/**
 * Mouse-wheel viewport rectangle for the events LogView (termflow 0.4 feature).
 * Wire this through `LogView.scrollDelta` in the update layer so the wheel
 * only drives scrollback when the cursor is over the events pane.
 */
def Llm4sDashboardEventsViewport(model: DashboardModel): widgets.LogView.Viewport =
  widgets.LogView.Viewport(
    Llm4sDashboardEventsViewportOrigin(model),
    Llm4sDashboardEventsViewportWidth(model),
    Llm4sDashboardEventsViewportHeight(model)
  )

/** Computed max-scroll for the events LogView at the current viewport size. */
def Llm4sDashboardEventsMaxScroll(model: DashboardModel): Int =
  widgets.LogView.maxScroll(
    model.events,
    Llm4sDashboardEventsViewportWidth(model),
    Llm4sDashboardEventsViewportHeight(model),
    wrap = true
  )
