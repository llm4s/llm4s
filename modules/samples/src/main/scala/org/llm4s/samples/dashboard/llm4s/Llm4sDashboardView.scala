package org.llm4s.samples.dashboard.llm4s

import org.llm4s.samples.dashboard.llm4s.Llm4sDashboardApp.DashboardModel
import org.llm4s.samples.dashboard.shared.DashboardSupport
import termflow.tui.Color
import termflow.tui.PromptHistory
import termflow.tui.TuiPrelude.*
import termflow.tui.*

def Llm4sDashboardView(model: DashboardModel): RootNode =
  val width       = math.max(model.terminalWidth, 72)
  val height      = math.max(model.terminalHeight, 20)
  val outerWidth  = math.max(68, width - 4)
  val leftWidth   = math.max(30, (outerWidth - 3) / 2)
  val rightWidth  = math.max(30, outerWidth - leftWidth - 1)
  val bodyTop     = 6
  val panelHeight = math.max(8, height - 11)
  val visibleEvents =
    model.events.takeRight(math.max(1, panelHeight - 2)).map(line => DashboardSupport.truncate(line, outerWidth - 4))
  val promptRow      = bodyTop + panelHeight + visibleEvents.length + 3
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

  val children: List[VNode] =
    List(
      BoxNode(1.x, 1.y, outerWidth, 4, children = Nil, style = Style(border = true, fg = Color.Cyan)),
      TextNode(3.x, 2.y, List("LLM4S Dashboard".text(Style(fg = Color.Yellow, bold = true, underline = true)))),
      TextNode(3.x, 3.y, List(DashboardSupport.truncate(model.status, outerWidth - 4).text(Style(fg = Color.Green)))),
      BoxNode(
        1.x,
        bodyTop.y,
        leftWidth,
        panelHeight,
        children = Nil,
        style = Style(border = true, fg = Color.Blue)
      ),
      TextNode(3.x, (bodyTop + 1).y, List("Summary".text(Style(fg = Color.Yellow, bold = true)))),
      BoxNode(
        (leftWidth + 2).x,
        bodyTop.y,
        rightWidth,
        panelHeight,
        children = Nil,
        style = Style(border = true, fg = Color.Magenta)
      ),
      TextNode((leftWidth + 4).x, (bodyTop + 1).y, List("Commands".text(Style(fg = Color.Yellow, bold = true)))),
      TextNode(
        3.x,
        (bodyTop + panelHeight + 1).y,
        List("Recent events".text(Style(fg = Color.Yellow, bold = true)))
      )
    ) ++
      summaryLines.zipWithIndex.map { case (line, idx) =>
        TextNode(3.x, (bodyTop + 2 + idx).y, List(line.text))
      } ++
      commandLines.zipWithIndex.map { case (line, idx) =>
        TextNode((leftWidth + 4).x, (bodyTop + 2 + idx).y, List(line.text))
      } ++
      visibleEvents.zipWithIndex.map { case (line, idx) =>
        TextNode(3.x, (bodyTop + panelHeight + 2 + idx).y, List(line.text))
      } ++
      List(
        TextNode(
          3.x,
          (promptRow - 1).y,
          List(
            DashboardSupport
              .truncate(s"provider: ${model.providerStatus}", outerWidth - 4)
              .text(Style(fg = Color.Cyan))
          )
        )
      )

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
        cursor = renderedPrompt.cursorIndex
      )
    )
  )
