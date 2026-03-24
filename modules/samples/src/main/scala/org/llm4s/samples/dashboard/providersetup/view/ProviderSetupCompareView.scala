package org.llm4s.samples.dashboard.providersetup.view

import org.llm4s.samples.dashboard.providersetup.ProviderSetupModel.*
import termflow.tui.*
import termflow.tui.TuiPrelude.*

private[providersetup] object ProviderSetupCompareView:
  import ProviderSetupRenderSupport.*

  def render(model: Model): RootNode =
    val width          = math.max(model.terminalWidth, 72)
    val height         = math.max(model.terminalHeight, 20)
    val outerWidth     = math.max(68, width - 4)
    val leftWidth      = weightedWidth(outerWidth, 1, Vector(42, 26), 0)
    val rightWidth     = weightedWidth(outerWidth, 1, Vector(42, 26), 1)
    val panelTop       = 6
    val panelHeight    = math.max(10, height - 12)
    val panelCapacity  = math.max(1, panelHeight - 2)
    val promptRow      = panelTop + panelHeight + 3
    val renderedPrompt = PromptHistory.renderWithPrefix(model.prompt, "compare> ")
    val compareTabs    = renderCompareResultTabs(model, outerWidth - 4)
    val activeResult   = model.compareResults.lift(model.activeCompareTab)
    val resultRows =
      fixedTranscriptRows(
        renderVisibleCompareResult(model, leftWidth - 4, panelCapacity),
        panelCapacity
      )
    val sidebarLines =
      clipPanel(renderCompareSidebar(model, rightWidth - 4), panelCapacity, "compare sidebar")

    val children: List[VNode] =
      List(
        BoxNode(1.x, 1.y, outerWidth, 4, children = Nil, style = Style(border = true, fg = Color.Cyan)),
        TextNode(3.x, 2.y, List("LLM4S Compare".text(Style(fg = Color.Yellow, bold = true, underline = true)))),
        TextNode(
          3.x,
          3.y,
          List(
            fixedWidth(
              activeResult
                .map(result =>
                  s"Viewing ${result.selection.providerName} / ${result.selection.selectedModel}. Type a prompt below."
                )
                .getOrElse("No compare result is active yet. Type a prompt below."),
              outerWidth - 4
            ).text(Style(fg = Color.Green))
          )
        ),
        TextNode(
          3.x,
          5.y,
          compareTabs.map(tab => renderTabText(tab, true)) :+
            fixedWidth("", outerWidth - 4 - compareTabs.map(_._1.length).sum).text
        ),
        BoxNode(1.x, panelTop.y, leftWidth, panelHeight, children = Nil, style = Style(border = true, fg = Color.Blue)),
        BoxNode(
          (leftWidth + 2).x,
          panelTop.y,
          rightWidth,
          panelHeight,
          children = Nil,
          style = Style(border = true, fg = Color.Magenta)
        ),
        TextNode(3.x, (panelTop + 1).y, List("Result".text(Style(fg = Color.Yellow, bold = true)))),
        TextNode(
          (leftWidth + 4).x,
          (panelTop + 1).y,
          List("Compare Status".text(Style(fg = Color.Yellow, bold = true)))
        ),
        TextNode(
          3.x,
          (promptRow - 1).y,
          List(fixedWidth(compareFooterLine(model), outerWidth - 4).text(Style(fg = Color.Cyan)))
        )
      ) ++
        resultRows.zipWithIndex.map { case (row, idx) =>
          TextNode(
            3.x,
            (panelTop + 2 + idx).y,
            fixedWidthSegments(row.segments, leftWidth - 4, row.padStyle)
          )
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
