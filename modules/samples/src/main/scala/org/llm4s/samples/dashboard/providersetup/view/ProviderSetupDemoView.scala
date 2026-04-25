package org.llm4s.samples.dashboard.providersetup.view

import org.llm4s.samples.dashboard.providersetup.ProviderSetupModel.*
import termflow.tui.*
import termflow.tui.TuiPrelude.*

private[providersetup] object ProviderSetupDemoView:
  import ProviderSetupRenderSupport.*

  def render(model: Model): RootNode =
    val width              = math.max(model.terminalWidth, 72)
    val height             = math.max(model.terminalHeight, 20)
    val outerWidth         = math.max(68, width - 4)
    val leftWidth          = weightedWidth(outerWidth, 1, Vector(42, 26), 0)
    val rightWidth         = weightedWidth(outerWidth, 1, Vector(42, 26), 1)
    val panelTop           = 6
    val panelHeight        = math.max(10, height - 12)
    val transcriptCapacity = math.max(1, panelHeight - 2)
    val promptRow          = panelTop + panelHeight + 3
    val renderedPrompt     = PromptHistory.renderWithPrefix(model.prompt, "demo> ")
    val session            = model.activeSession
    val spinner            = waitingSpinner(model)

    val transcriptRows =
      fixedTranscriptRows(
        renderVisibleDemoTranscript(model, leftWidth - 4, transcriptCapacity),
        transcriptCapacity
      )
    val sidebarLines =
      fixedPanelTextRows(
        clipPanel(renderDemoSidebar(model, rightWidth - 4), transcriptCapacity, "demo sidebar"),
        transcriptCapacity
      )

    val children: List[VNode] =
      List(
        BoxNode(1.x, 1.y, outerWidth, 4, children = Nil, style = Style(border = true, fg = Color.Cyan)),
        TextNode(
          3.x,
          2.y,
          List("LLM4S Demo Chat".text(Style(fg = Color.Yellow, bold = true, underline = true)))
        ),
        TextNode(
          3.x,
          3.y,
          List(
            fixedWidth(
              session
                .map(active =>
                  s"Using ${active.label}. ${
                      if model.demoPending then s"$spinner Waiting for reply..." else "Type a prompt below."
                    }"
                )
                .getOrElse("No active session. Return to setup and use a provider first."),
              outerWidth - 4
            ).text(Style(fg = Color.Green))
          )
        ),
      ) ++
        List(
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
                demoFooterLine(model),
                outerWidth - 4
              ).text(Style(fg = Color.Cyan))
            )
          )
        ) ++
        transcriptRows.zipWithIndex.map { case (row, idx) =>
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
