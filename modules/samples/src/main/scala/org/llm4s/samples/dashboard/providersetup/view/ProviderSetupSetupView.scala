package org.llm4s.samples.dashboard.providersetup.view

import org.llm4s.samples.dashboard.providersetup.ProviderSetupModel.*
import org.llm4s.samples.dashboard.providersetup.ProviderSetupModel.SetupTabDocIds
import org.llm4s.samples.dashboard.providersetup.{
  ProviderSetupCompare,
  ProviderSetupProviderSelection,
  ProviderSetupSetupPolicy
}
import org.llm4s.samples.dashboard.providersetup.ProviderSetupTabs
import termflow.tui.*
import termflow.tui.TuiPrelude.*

private[providersetup] object ProviderSetupSetupView:
  import ProviderSetupRenderSupport.*
  import ProviderSetupSetupViewSupport.*

  def render(model: Model): RootNode =
    val width            = math.max(model.terminalWidth, 60)
    val height           = math.max(model.terminalHeight, 18)
    val outerWidth       = math.max(56, width - 4)
    val panelTop         = 6
    val panelHeight      = math.max(13, height - 12)
    val promptRow        = panelTop + panelHeight + 3
    val renderedPrompt   = PromptHistory.renderWithPrefix(model.prompt, "setup> ")
    val tabDoc           = ProviderSetupTabs.activeSetupDoc(model)
    val selectedProvider = ProviderSetupProviderSelection.selectedProviderDoc(model)
    val activeDoc =
      if tabDoc.id.is(SetupTabDocIds.Providers) then selectedProvider
      else if ProviderSetupSetupPolicy.isDefaultProviderTab(model) then defaultProviderDoc(model).getOrElse(tabDoc)
      else tabDoc
    val isDefaultTab   = ProviderSetupSetupPolicy.isDefaultProviderTab(model)
    val isProvidersTab = tabDoc.id.is(SetupTabDocIds.Providers)
    val isCompareTab   = tabDoc.id.is(SetupTabDocIds.Compare)
    val selectedTarget = ProviderSetupProviderSelection.selectedSessionOverrideTarget(model)
    val sessionInput   = model.sessionInputs.get(selectedTarget)

    val statusWidth                = weightedWidth(outerWidth, 1, Vector(46, 24, 30), 2)
    val modelWidth                 = weightedWidth(outerWidth, 1, Vector(46, 24, 30), 1)
    val leftWidth                  = weightedWidth(outerWidth, 1, Vector(46, 24, 30), 0)
    val modelX                     = leftWidth + 2
    val statusX                    = leftWidth + modelWidth + 3
    val defaultModelCount          = ProviderSetupProviderSelection.defaultProviderModels(model.configStatus).size
    val selectedProviderModelCount = ProviderSetupProviderSelection.selectedConfiguredProviderModels(model).size

    val tabLine = renderTabs(model, model.activeTab, outerWidth - 4)
    val mainLines =
      if isCompareTab then renderCompareProviderPanel(model, leftWidth - 4)
      else
        renderPrimaryPanel(
          tabDoc,
          activeDoc,
          leftWidth - 4,
          model.configStatus,
          model.chosenModel
        )
    val modelLines =
      if isDefaultTab then renderModelPanel(model, modelWidth - 4)
      else if isProvidersTab then renderProvidersTabPanel(model, modelWidth - 4)
      else if isCompareTab then renderCompareModelPanel(model, modelWidth - 4)
      else renderGenericMiddlePanel(activeDoc, modelWidth - 4)
    val statusLines =
      if isCompareTab then renderCompareSetupSidebar(model, statusWidth - 4)
      else renderStatusSidebar(tabDoc, activeDoc, model, sessionInput, statusWidth - 4)

    val mainCapacity   = math.max(1, panelHeight - 2)
    val modelCapacity  = math.max(1, panelHeight - 2)
    val statusCapacity = math.max(1, panelHeight - 2)
    val visibleMain    = clipPanel(mainLines, mainCapacity, "main panel")
    val visibleModels =
      if isDefaultTab then clipModelPanel(modelLines, modelCapacity, model, "model panel")
      else if isProvidersTab && model.providersPanelMode == ProvidersPanelMode.Models then
        clipProvidersModelPanel(modelLines, modelCapacity, model, "provider model panel")
      else if isCompareTab then clipCompareModelPanel(modelLines, modelCapacity, model, "compare model panel")
      else clipPanel(modelLines, modelCapacity, "middle panel")
    val visibleStatus = clipPanel(statusLines, statusCapacity, "sidebar")

    val topMessage =
      if model.terminalHeight < 26 || model.terminalWidth < 90 then
        "Terminal is small; some content is hidden. Enlarge the window for the full view."
      else model.statusLine
    val topStyle =
      if model.terminalHeight < 26 || model.terminalWidth < 90 then Style(fg = Color.Yellow, bold = true)
      else Style(fg = Color.Green)

    val children: List[VNode] =
      List(
        BoxNode(1.x, 1.y, outerWidth, 4, children = Nil, style = Style(border = true, fg = Color.Cyan)),
        TextNode(
          3.x,
          2.y,
          List("LLM4S Provider Setup".text(Style(fg = Color.Yellow, bold = true, underline = true)))
        ),
        TextNode(3.x, 3.y, List(fixedWidth(topMessage, outerWidth - 4).text(topStyle))),
        TextNode(
          3.x,
          5.y,
          tabLine.map(renderTabText(_, model.focusTarget == FocusTarget.TabBar)) :+
            fixedWidth("", outerWidth - 4 - tabLine.map(_._1.length).sum).text
        ),
      ) ++
        renderBlankArea(1, panelTop, outerWidth, panelHeight) ++
        List(
          BoxNode(
            1.x,
            panelTop.y,
            leftWidth,
            panelHeight,
            children = Nil,
            style = Style(
              border = true,
              fg =
                if model.focusTarget == FocusTarget.Body && model.panelFocus == PanelFocus.Main then Color.Yellow
                else Color.Blue
            )
          ),
          BoxNode(
            modelX.x,
            panelTop.y,
            modelWidth,
            panelHeight,
            children = Nil,
            style = Style(
              border = true,
              fg =
                if model.focusTarget == FocusTarget.Body && model.panelFocus == PanelFocus.Models then Color.Yellow
                else Color.Cyan
            )
          ),
          BoxNode(
            statusX.x,
            panelTop.y,
            statusWidth,
            panelHeight,
            children = Nil,
            style = Style(
              border = true,
              fg =
                if model.focusTarget == FocusTarget.Body && model.panelFocus == PanelFocus.Status then Color.Yellow
                else Color.Magenta
            )
          ),
          TextNode(
            3.x,
            (panelTop + 1).y,
            List(
              fixedWidth(
                if isCompareTab then s"Available (${ProviderSetupCompare.availableCompareProviders(model).size})"
                else activeDoc.title,
                leftWidth - 4
              ).text(Style(fg = Color.Yellow, bold = true))
            )
          ),
          TextNode(
            (modelX + 2).x,
            (panelTop + 1).y,
            List(
              fixedWidth(
                if isDefaultTab then s"Models ($defaultModelCount)"
                else if isProvidersTab then
                  if model.providersPanelMode == ProvidersPanelMode.Providers then
                    s"Providers (${model.configStatus.namedProviders.size})"
                  else s"Models ($selectedProviderModelCount)"
                else if isCompareTab then s"Models (${ProviderSetupCompare.selectedCompareProviderModels(model).size})"
                else "Details",
                modelWidth - 4
              ).text(Style(fg = Color.Yellow, bold = true))
            )
          ),
          TextNode(
            (statusX + 2).x,
            (panelTop + 1).y,
            List(
              fixedWidth(
                if isCompareTab then s"Compare Set (${model.compareSelections.size})" else "Quick Status",
                statusWidth - 4
              ).text(Style(fg = Color.Yellow, bold = true))
            )
          ),
          TextNode(
            3.x,
            (promptRow - 1).y,
            List(
              fixedWidth(footerLine(model), outerWidth - 4)
                .text(Style(fg = Color.Cyan))
            )
          )
        ) ++
        visibleMain.zipWithIndex.map { case (line, idx) =>
          TextNode(3.x, (panelTop + 2 + idx).y, List(fixedWidth(line, leftWidth - 4).text))
        } ++
        visibleModels.zipWithIndex.map { case (line, idx) =>
          TextNode((modelX + 2).x, (panelTop + 2 + idx).y, List(fixedWidth(line, modelWidth - 4).text))
        } ++
        visibleStatus.zipWithIndex.map { case (line, idx) =>
          TextNode((statusX + 2).x, (panelTop + 2 + idx).y, List(fixedWidth(line, statusWidth - 4).text))
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
