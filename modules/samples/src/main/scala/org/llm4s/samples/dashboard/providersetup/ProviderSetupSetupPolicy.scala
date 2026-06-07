package org.llm4s.samples.dashboard.providersetup

import org.llm4s.samples.dashboard.providersetup.ProviderSetupModel.*
import org.llm4s.types.ProviderModelTypes.ProviderKind
import termflow.tui.PromptHistory

object ProviderSetupSetupPolicy:

  final case class SetupSessionRequest(
    isDefaultProviderTab: Boolean,
    activeTab: SetupTabId,
    activeDocId: SetupTabDocId,
    selectedProviderKind: Option[ProviderKind],
    selectedConfiguredProvider: Option[ConfiguredProvider],
    sessionTarget: SessionOverrideTarget
  )

  def resetProvidersPanel(model: Model): Model =
    model.updateSetup(_.copy(providersPanelMode = ProvidersPanelMode.Providers, highlightedModel = None))

  def isDefaultProviderTab(model: Model): Boolean =
    ProviderSetupTabs.activeSetupTab(model) == SetupTabId.DefaultNamedProvider

  def isProvidersTab(model: Model): Boolean =
    ProviderSetupTabs.activeSetupTab(model) == SetupTabId.Providers

  def isCompareTab(model: Model): Boolean =
    ProviderSetupTabs.activeSetupTab(model) == SetupTabId.Compare

  def canUseCurrentSetupTab(model: Model): Boolean =
    true

  def canRemoveCompareEntry(model: Model): Boolean =
    isCompareTab(model)

  def canSelectModelByCommand(model: Model): Boolean =
    isDefaultProviderTab(model)

  def shouldRouteEnterToModelAction(model: Model): Boolean =
    isDefaultProviderTab(model) || isProvidersTab(model) || isCompareTab(model)

  def shouldConfirmHighlightedModel(prompt: PromptHistory.State, model: Model): Boolean =
    prompt.prompt.buffer.isEmpty &&
      model.focusTarget == FocusTarget.Body &&
      (model.panelFocus == PanelFocus.Models ||
        (ProviderSetupTabs.activeSetupTab(model) == SetupTabId.Compare &&
          model.panelFocus == PanelFocus.Status))

  def exitBodyFocus(model: Model): Model =
    if isProvidersTab(
        model
      ) && model.panelFocus == PanelFocus.Models && model.providersPanelMode == ProvidersPanelMode.Models
    then
      model
        .updateSetup(_.copy(providersPanelMode = ProvidersPanelMode.Providers, highlightedModel = None))
        .copy(statusLine = "Back to configured providers. Press Enter to open the selected provider's models.")
    else
      model
        .updateShell(_.copy(focusTarget = FocusTarget.TabBar))
        .copy(statusLine = s"Tab bar focused. ${ProviderSetupTabs.tabTitle(model, model.activeTab)} is selected.")

  def handleSetupEnter(model: Model): Model =
    if isProvidersTab(model) then
      if model.providersPanelMode == ProvidersPanelMode.Providers then
        ProviderSetupProviderSelection.openSelectedConfiguredProviderModels(model)
      else ProviderSetupProviderSelection.confirmHighlightedConfiguredModel(model)
    else if isCompareTab(model) then
      model.panelFocus match
        case PanelFocus.Models => ProviderSetupCompare.addSelectedCompareEntry(model)
        case PanelFocus.Status => ProviderSetupCompare.removeHighlightedCompareEntry(model)
        case _ => model.copy(statusLine = "Move right to models, then press Enter to add a compare entry.")
    else ProviderSetupProviderSelection.confirmHighlightedModel(model)

  def moveBodySelection(model: Model, direction: Int): Model =
    if isDefaultProviderTab(model) && model.panelFocus == PanelFocus.Models then
      ProviderSetupProviderSelection.moveHighlightedModelValue(model, direction)
    else if isProvidersTab(model) && model.panelFocus == PanelFocus.Models then
      if model.providersPanelMode == ProvidersPanelMode.Providers then
        ProviderSetupProviderSelection.moveHighlightedProvider(model, direction)
      else ProviderSetupProviderSelection.moveHighlightedConfiguredModelValue(model, direction)
    else if isCompareTab(model) then
      model.panelFocus match
        case PanelFocus.Main   => ProviderSetupCompare.moveHighlightedCompareProvider(model, direction)
        case PanelFocus.Models => ProviderSetupCompare.moveHighlightedCompareModelValue(model, direction)
        case PanelFocus.Status => ProviderSetupCompare.moveHighlightedCompareSelection(model, direction)
    else model
