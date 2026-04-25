package org.llm4s.samples.dashboard.providersetup

import org.llm4s.config.{ DiscoveredModel, ProvidersConfigModel }
import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.llmconnect.config.ProviderConfig
import org.llm4s.samples.dashboard.providersetup.ProviderSetupMessages.*
import org.llm4s.samples.dashboard.providersetup.ProviderSetupModel.*
import org.llm4s.samples.dashboard.providersetup.update.{ ProviderSetupInputSupport, ProviderSetupUpdate }
import org.llm4s.samples.dashboard.providersetup.view.ProviderSetupView
import org.llm4s.types.ProviderModelTypes.ProviderName

import java.nio.file.Path
import termflow.tui.FileHistoryStore
import termflow.tui.Sub
import termflow.tui.Tui
import termflow.tui.TuiApp
import termflow.tui.TuiPrelude.*
import termflow.tui.*

/**
 * First-run provider onboarding sample for llm4s + termflow.
 *
 * Run with:
 * `sbt "samples/runMain org.llm4s.samples.dashboard.providersetup.ProviderSetupDemoMain"`
 */
object ProviderSetupDemoApp:
  export ProviderSetupMessages.*
  export ProviderSetupModel.*

  def promptHistoryPath(historyBasePath: String): Path =
    Path.of(historyBasePath, "history", "provider-setup-demo-history.txt")

  private val docs: Vector[ProviderDoc] = ProviderSetupContent.docs

  def App(
    demoCfg: ProviderSetupDemoConfig,
    providersCfg: ProvidersConfigModel.ProvidersConfig,
    providerConfigs: Map[ProviderName, ProviderConfig],
    defaultProvider: ProviderConfig,
    discoveredModels: Map[ProviderName, List[DiscoveredModel]],
    exchangeLogging: ProviderExchangeLogging
  ): TuiApp[Model, Msg] =
    new TuiApp[Model, Msg]:
      override def init(ctx: RuntimeCtx[Msg]): Tui[Model, Msg] =
        Sub.InputKey(
          key => Msg.Setup(SetupMsg.ConsoleInputKey(key)),
          error => Msg.Global(GlobalMsg.ConsoleInputError(error)),
          ctx
        )
        Sub.Every(150L, () => Msg.Global(GlobalMsg.Tick), ctx)
        Sub.TerminalResize(250L, (width, height) => Msg.Global(GlobalMsg.Resize(width, height)), ctx)

        Tui(
          Model(
            DemoAppConfigs(
              docs,
              demoCfg,
              providersCfg,
              providerConfigs,
              defaultProvider,
              discoveredModels,
              exchangeLogging
            ),
            ui = UiState(
              shell = ShellUiState(
                terminalWidth = ctx.terminal.width,
                terminalHeight = ctx.terminal.height,
                prompt =
                  PromptHistory.initial(FileHistoryStore(promptHistoryPath(demoCfg.historyBasePath), maxEntries = 150)),
                screenMode = ScreenMode.Setup,
                activeTab = SetupTabId.Overview,
                focusTarget = FocusTarget.TabBar,
                panelFocus = PanelFocus.Main
              ),
              setup = SetupUiState(
                selectedProviderIndex = 0,
                highlightedModel = None,
                chosenModel = None,
                providersPanelMode = ProvidersPanelMode.Providers
              )
            ),
            demoStreamingEnabled = demoCfg.streamingEnabled,
            statusLine = "Selected Overview.",
            configStatus = ProviderSetupRuntime.detectConfigStatus(providersCfg, discoveredModels),
            sessionInputs = Map.empty,
            compare = CompareState(
              selections = Vector.empty,
              highlightedSelectionIndex = 0,
              activeTab = 0,
              results = Vector.empty,
              prompt = None,
              scrollOffsets = Vector.empty
            ),
            demo = DemoState(
              activeSession = None,
              entries = Vector.empty,
              scrollOffset = 0,
              pending = false,
              ticks = 0L
            )
          ),
          ProviderSetupRuntime.refreshStatusCmd(providersCfg, discoveredModels)
        )

      override def update(model: Model, msg: Msg, ctx: RuntimeCtx[Msg]): Tui[Model, Msg] =
        ProviderSetupUpdate(model, msg, ctx)

      override def view(model: Model): RootNode =
        ProviderSetupView.render(model)

      override def toMsg(input: PromptLine): Result[Msg] =
        ProviderSetupInputSupport.inputToMsg(input)
