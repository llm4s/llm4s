package org.llm4s.samples.dashboard.providersetup

import org.llm4s.config.{ DiscoveredModel, ProvidersConfigModel }
import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.llmconnect.config.ProviderConfig
import org.llm4s.types.ProviderModelTypes.ProviderName
import termflow.tui.PromptHistory

object ProviderSetupModel:

  opaque type SetupTabDocId = String
  object SetupTabDocId:
    def apply(value: String): SetupTabDocId = value

  object SetupTabDocIds:
    val Overview: SetupTabDocId  = SetupTabDocId("overview")
    val Default: SetupTabDocId   = SetupTabDocId("default")
    val Providers: SetupTabDocId = SetupTabDocId("providers")
    val Compare: SetupTabDocId   = SetupTabDocId("compare")
    val Config: SetupTabDocId    = SetupTabDocId("config")
    val Status: SetupTabDocId    = SetupTabDocId("status")

    val Ollama: SetupTabDocId    = SetupTabDocId("ollama")
    val OpenAI: SetupTabDocId    = SetupTabDocId("openai")
    val Azure: SetupTabDocId     = SetupTabDocId("azure")
    val Anthropic: SetupTabDocId = SetupTabDocId("anthropic")
    val Gemini: SetupTabDocId    = SetupTabDocId("gemini")
    val DeepSeek: SetupTabDocId  = SetupTabDocId("deepseek")
    val Cohere: SetupTabDocId    = SetupTabDocId("cohere")
    val Mistral: SetupTabDocId   = SetupTabDocId("mistral")
    val Zai: SetupTabDocId       = SetupTabDocId("zai")

  extension (id: SetupTabDocId)
    def value: String                        = id
    def is(expected: SetupTabDocId): Boolean = id == expected

  enum SetupTabId:
    case Overview
    case DefaultNamedProvider
    case Providers
    case Compare
    case ConfigPaths
    case Status

  val MaxCompareProviders = 5

  enum ScreenMode:
    case Setup
    case Demo
    case Compare

  enum FocusTarget:
    case TabBar
    case Body

  enum PanelFocus:
    case Main
    case Models
    case Status

  enum ProvidersPanelMode:
    case Providers
    case Models

  enum CompareResultStatus:
    case Pending
    case Success
    case Failure

  final case class ConfigStatus(
    headline: String,
    detail: String,
    providerId: Option[String],
    modelName: Option[String],
    providerName: Option[String],
    namedProviders: Vector[ConfiguredProvider]
  )

  final case class ConfiguredProvider(
    name: String,
    providerId: String,
    modelName: String,
    discoveredModels: Vector[String],
    discoveryDetail: String,
    isDefault: Boolean
  )

  final case class ProviderDoc(
    id: SetupTabDocId,
    title: String,
    summary: String,
    highlights: List[String],
    requiredVars: List[String],
    optionalVars: List[String],
    recommendedModels: List[String],
    setupSteps: List[String]
  )

  final case class ActiveSession(
    config: ProviderConfig,
    label: String,
    note: String
  )

  final case class CompareSelection(
    providerName: String,
    providerId: String,
    configuredModel: String,
    selectedModel: String
  )

  final case class CompareResult(
    selection: CompareSelection,
    status: CompareResultStatus,
    content: String = "",
    error: Option[String] = None,
    latencyMs: Option[Long] = None,
    responseChars: Option[Int] = None
  )

  enum DemoRole:
    case System
    case User
    case Assistant

  final case class DemoEntry(
    role: DemoRole,
    content: String
  )

  final case class ProviderSessionInput(
    model: Option[String] = None,
    apiKey: Option[String] = None,
    baseUrl: Option[String] = None,
    organization: Option[String] = None,
    endpoint: Option[String] = None,
    apiVersion: Option[String] = None
  ):
    def hasAnyValue: Boolean =
      model.nonEmpty || apiKey.nonEmpty || baseUrl.nonEmpty || organization.nonEmpty || endpoint.nonEmpty || apiVersion.nonEmpty

  enum SessionOverrideTarget:
    case NamedProvider(name: ProviderName)
    case ProviderKind(id: SetupTabDocId)

  extension (target: SessionOverrideTarget)
    def displayValue: String = target match
      case SessionOverrideTarget.NamedProvider(name) => name.asName
      case SessionOverrideTarget.ProviderKind(id)    => id.value

  final case class DemoAppConfigs(
    docs: Vector[ProviderDoc],
    demoCfg: ProviderSetupDemoConfig,
    providersCfg: ProvidersConfigModel.ProvidersConfig,
    providerConfigs: Map[ProviderName, ProviderConfig],
    defaultProvider: ProviderConfig,
    discoveredModels: Map[ProviderName, List[DiscoveredModel]],
    exchangeLogging: ProviderExchangeLogging
  )

  final case class ShellUiState(
    terminalWidth: Int,
    terminalHeight: Int,
    prompt: PromptHistory.State,
    screenMode: ScreenMode,
    activeTab: SetupTabId,
    focusTarget: FocusTarget,
    panelFocus: PanelFocus
  )

  final case class SetupUiState(
    selectedProviderIndex: Int,
    highlightedModel: Option[String],
    chosenModel: Option[String],
    providersPanelMode: ProvidersPanelMode
  )

  final case class CompareState(
    selections: Vector[CompareSelection],
    highlightedSelectionIndex: Int,
    activeTab: Int,
    results: Vector[CompareResult],
    prompt: Option[String],
    scrollOffsets: Vector[Int]
  )

  final case class DemoState(
    activeSession: Option[ActiveSession],
    entries: Vector[DemoEntry],
    scrollOffset: Int,
    pending: Boolean,
    ticks: Long
  )

  final case class UiState(
    shell: ShellUiState,
    setup: SetupUiState
  )

  final case class Model(
    demoAppConfigs: DemoAppConfigs,
    ui: UiState,
    demoStreamingEnabled: Boolean,
    statusLine: String,
    configStatus: ConfigStatus,
    sessionInputs: Map[SessionOverrideTarget, ProviderSessionInput],
    compare: CompareState,
    demo: DemoState
  )

  extension (model: Model)
    def terminalWidth: Int                          = model.ui.shell.terminalWidth
    def terminalHeight: Int                         = model.ui.shell.terminalHeight
    def prompt: PromptHistory.State                 = model.ui.shell.prompt
    def screenMode: ScreenMode                      = model.ui.shell.screenMode
    def activeTab: SetupTabId                       = model.ui.shell.activeTab
    def focusTarget: FocusTarget                    = model.ui.shell.focusTarget
    def panelFocus: PanelFocus                      = model.ui.shell.panelFocus
    def selectedProviderIndex: Int                  = model.ui.setup.selectedProviderIndex
    def highlightedModel: Option[String]            = model.ui.setup.highlightedModel
    def chosenModel: Option[String]                 = model.ui.setup.chosenModel
    def providersPanelMode: ProvidersPanelMode      = model.ui.setup.providersPanelMode
    def compareSelections: Vector[CompareSelection] = model.compare.selections
    def highlightedCompareSelectionIndex: Int       = model.compare.highlightedSelectionIndex
    def activeCompareTab: Int                       = model.compare.activeTab
    def compareResults: Vector[CompareResult]       = model.compare.results
    def comparePrompt: Option[String]               = model.compare.prompt
    def compareScrollOffsets: Vector[Int]           = model.compare.scrollOffsets
    def activeSession: Option[ActiveSession]        = model.demo.activeSession
    def demoEntries: Vector[DemoEntry]              = model.demo.entries
    def demoScrollOffset: Int                       = model.demo.scrollOffset
    def demoPending: Boolean                        = model.demo.pending
    def demoTicks: Long                             = model.demo.ticks

    def updateShell(f: ShellUiState => ShellUiState): Model =
      model.copy(ui = model.ui.copy(shell = f(model.ui.shell)))

    def updateSetup(f: SetupUiState => SetupUiState): Model =
      model.copy(ui = model.ui.copy(setup = f(model.ui.setup)))

    def updateCompare(f: CompareState => CompareState): Model =
      model.copy(compare = f(model.compare))

    def updateDemo(f: DemoState => DemoState): Model =
      model.copy(demo = f(model.demo))
