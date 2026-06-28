package org.llm4s.samples.dashboard.providersetup.update

import org.llm4s.samples.dashboard.providersetup.ProviderSetupMessages.*
import org.llm4s.samples.dashboard.providersetup.ProviderSetupModel.*
import org.llm4s.samples.dashboard.providersetup.{ ProviderSetupProviderSelection, ProviderSetupRuntime }
import org.llm4s.samples.dashboard.providersetup.ProviderSetupTabs
import org.llm4s.samples.dashboard.shared.DashboardSupport
import termflow.tui.*
import termflow.tui.Tui.*

private[providersetup] object ProviderSetupGlobalUpdate:

  def handle(model: Model, msg: Msg): Option[Tui[Model, Msg]] =
    msg match
      case Msg.Global(GlobalMsg.NoOp) =>
        Some(model.tui)

      case Msg.Global(GlobalMsg.Tick) =>
        Some(ProviderSetupAsyncSupport.handleTick(model))

      case Msg.Global(GlobalMsg.DemoChunkReceived(chunk)) =>
        Some(ProviderSetupAsyncSupport.handleDemoChunk(model, chunk))

      case Msg.Global(GlobalMsg.DemoResponseReceived(result)) =>
        Some(ProviderSetupAsyncSupport.handleDemoResponse(model, result))

      case Msg.Global(GlobalMsg.CompareResponseReceived(providerName, latencyMs, result)) =>
        Some(ProviderSetupAsyncSupport.handleCompareResponse(model, providerName, latencyMs, result))

      case Msg.Global(GlobalMsg.Resize(width, height)) =>
        Some(model.updateShell(_.copy(terminalWidth = width, terminalHeight = height)).tui)

      case Msg.Global(GlobalMsg.ConsoleInputError(error)) =>
        Some(model.copy(statusLine = s"input error: ${DashboardSupport.safeMessage(error)}").tui)

      case Msg.Global(GlobalMsg.RefreshStatus) =>
        Some(
          Tui(
            model.copy(statusLine = "Reloading llm4s provider status..."),
            ProviderSetupRuntime.refreshStatusCmd(
              model.demoAppConfigs.providersCfg,
              model.demoAppConfigs.discoveredModels
            )
          )
        )

      case Msg.Global(GlobalMsg.StatusRefreshed(configStatus)) =>
        val nextHighlighted =
          ProviderSetupProviderSelection.chooseModel(model.highlightedModel, configStatus)
        val nextChosen = ProviderSetupProviderSelection.chooseModel(model.chosenModel, configStatus)
        val nextSelectedProviderIndex =
          ProviderSetupProviderSelection.chooseSelectedProviderIndex(model.selectedProviderIndex, configStatus)
        val activeDocId = ProviderSetupTabs.activeSetupDoc(model).id
        Some(
          model
            .updateSetup(
              _.copy(
                selectedProviderIndex = nextSelectedProviderIndex,
                highlightedModel =
                  if activeDocId.is(SetupTabDocIds.Providers) then model.highlightedModel
                  else nextHighlighted,
                chosenModel =
                  if activeDocId.is(SetupTabDocIds.Providers) then model.chosenModel
                  else nextChosen
              )
            )
            .copy(
              configStatus = configStatus,
              statusLine = s"Reloaded status"
            )
            .tui
        )

      case Msg.Global(GlobalMsg.ExitRequested) =>
        Some(Tui(model, Cmd.Exit))

      case _ =>
        None
