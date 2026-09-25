package org.llm4s.samples.dashboard.providersetup

import org.scalatest.EitherValues
import org.llm4s.config.ProvidersConfigModel
import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.llmconnect.config.{ ContextWindowResolver, OpenAIConfig }
import org.llm4s.model.{ ModelRegistryConfig, ModelRegistryService }
import org.llm4s.samples.dashboard.providersetup.ProviderSetupModel.*
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.ProviderModelTypes.ProviderName
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import termflow.tui.FileHistoryStore
import termflow.tui.PromptHistory

import java.nio.file.Path

class ProviderSetupSessionTargetSpec extends AnyFlatSpec with Matchers with EitherValues:

  private given ContextWindowResolver =
    ContextWindowResolver(ModelRegistryService.fromConfig(ModelRegistryConfig.default).toOption.get)

  "currentSetupSessionRequest" should "use the named provider as the session target on the Providers tab" in {
    val model = baseModel.copy(
      configStatus = configuredStatus,
      ui = baseModel.ui.copy(
        shell = baseModel.ui.shell.copy(activeTab = SetupTabId.Providers),
        setup = baseModel.ui.setup.copy(selectedProviderIndex = 1)
      )
    )

    val request = ProviderSetupProviderSelection.currentSetupSessionRequest(model).toOption.get

    request.isDefaultProviderTab shouldBe false
    request.selectedProviderId shouldBe Some(ProviderId("ollama"))
    request.selectedConfiguredProvider.map(_.name) shouldBe Some("ollama-qwen")
    request.sessionTarget shouldBe SessionOverrideTarget.NamedProvider(ProviderName("ollama-qwen"))
  }

  it should "use the default named provider as the session target on the default tab" in {
    val model = baseModel.copy(
      configStatus = configuredStatus,
      ui = baseModel.ui.copy(shell = baseModel.ui.shell.copy(activeTab = SetupTabId.DefaultNamedProvider))
    )

    val request = ProviderSetupProviderSelection.currentSetupSessionRequest(model).toOption.get

    request.isDefaultProviderTab shouldBe true
    request.selectedProviderId shouldBe None
    request.selectedConfiguredProvider.map(_.name) shouldBe Some("anthropic-main")
    request.sessionTarget shouldBe SessionOverrideTarget.NamedProvider(ProviderName("anthropic-main"))
  }

  it should "resolve a configured provider that this sample ships no doc page for" in {
    // openrouter, requesty and vertexai are all configurable but have no entry in
    // `ProviderSetupContent.providerDocs`. Validating configured ids against that list would
    // refuse to open a session for them - providers are an open vocabulary.
    val model = baseModel.copy(
      configStatus = undocumentedStatus,
      ui = baseModel.ui.copy(
        shell = baseModel.ui.shell.copy(activeTab = SetupTabId.Providers),
        setup = baseModel.ui.setup.copy(selectedProviderIndex = 0)
      )
    )

    val request = ProviderSetupProviderSelection.currentSetupSessionRequest(model).toOption.get

    request.selectedProviderId shouldBe Some(ProviderId("openrouter"))
    request.selectedConfiguredProvider.map(_.name) shouldBe Some("openrouter-main")
  }

  it should "resolve a configured provider this build has never heard of" in {
    val model = baseModel.copy(
      configStatus = undocumentedStatus,
      ui = baseModel.ui.copy(
        shell = baseModel.ui.shell.copy(activeTab = SetupTabId.Providers),
        setup = baseModel.ui.setup.copy(selectedProviderIndex = 1)
      )
    )

    val request = ProviderSetupProviderSelection.currentSetupSessionRequest(model).toOption.get

    request.selectedProviderId shouldBe Some(ProviderId("bedrock"))
  }

  it should "reject a configured provider with a blank provider id" in {
    val model = baseModel.copy(
      configStatus = undocumentedStatus,
      ui = baseModel.ui.copy(
        shell = baseModel.ui.shell.copy(activeTab = SetupTabId.Providers),
        setup = baseModel.ui.setup.copy(selectedProviderIndex = 2)
      )
    )

    ProviderSetupProviderSelection.currentSetupSessionRequest(model).isLeft shouldBe true
  }

  it should "use the provider kind doc id as the session target on provider doc tabs" in {
    val model = baseModel.copy(
      configStatus = configuredStatus,
      ui = baseModel.ui.copy(shell = baseModel.ui.shell.copy(activeTab = SetupTabId.Status))
    )

    val request = ProviderSetupProviderSelection.currentSetupSessionRequest(model).toOption.get

    request.isDefaultProviderTab shouldBe false
    request.activeDocId shouldBe SetupTabDocIds.Status
    request.selectedProviderId shouldBe None
    request.selectedConfiguredProvider shouldBe None
    request.sessionTarget shouldBe SessionOverrideTarget.ProviderKind(SetupTabDocIds.Status)
  }

  private def undocumentedStatus =
    ConfigStatus(
      headline = "configured",
      detail = "configured detail",
      providerId = Some("openrouter"),
      modelName = Some("openai/gpt-4o-mini"),
      providerName = Some("openrouter-main"),
      namedProviders = Vector(
        ConfiguredProvider(
          name = "openrouter-main",
          providerId = "openrouter",
          modelName = "openai/gpt-4o-mini",
          discoveredModels = Vector("openai/gpt-4o-mini"),
          discoveryDetail = "openrouter models",
          isDefault = true
        ),
        ConfiguredProvider(
          name = "bedrock-main",
          providerId = "bedrock",
          modelName = "anthropic.claude-sonnet-4",
          discoveredModels = Vector.empty,
          discoveryDetail = "no discovery",
          isDefault = false
        ),
        ConfiguredProvider(
          name = "broken",
          providerId = "  ",
          modelName = "whatever",
          discoveredModels = Vector.empty,
          discoveryDetail = "no discovery",
          isDefault = false
        )
      )
    )

  private def configuredStatus =
    ConfigStatus(
      headline = "configured",
      detail = "configured detail",
      providerId = Some("anthropic"),
      modelName = Some("claude-sonnet-4-20250514"),
      providerName = Some("anthropic-main"),
      namedProviders = Vector(
        ConfiguredProvider(
          name = "anthropic-main",
          providerId = "anthropic",
          modelName = "claude-sonnet-4-20250514",
          discoveredModels = Vector("claude-sonnet-4-20250514"),
          discoveryDetail = "anthropic models",
          isDefault = true
        ),
        ConfiguredProvider(
          name = "ollama-qwen",
          providerId = "ollama",
          modelName = "qwen3:8b",
          discoveredModels = Vector("qwen3:8b"),
          discoveryDetail = "ollama models",
          isDefault = false
        )
      )
    )

  private def baseModel =
    Model(
      demoAppConfigs = DemoAppConfigs(
        docs = ProviderSetupContent.docs,
        demoCfg = ProviderSetupDemoConfig(
          streamingEnabled = true,
          debugLog = ProviderSetupDebugLogConfig(enabled = false, path = "/tmp/provider-setup-debug.log"),
          historyBasePath = "/tmp"
        ),
        providersCfg = ProvidersConfigModel.ProvidersConfig(
          selectedProvider = Some(ProviderName("anthropic-main")),
          namedProviders = Map.empty
        ),
        providerConfigs = Map.empty,
        defaultProvider = OpenAIConfig.fromValues("gpt-4o-mini", "test-key", None, "https://api.openai.com/v1").value,
        discoveredModels = Map.empty,
        exchangeLogging = ProviderExchangeLogging.Disabled
      ),
      ui = UiState(
        shell = ShellUiState(
          terminalWidth = 120,
          terminalHeight = 40,
          prompt = PromptHistory.initial(
            FileHistoryStore(
              Path.of(System.getProperty("java.io.tmpdir"), "provider-setup-session-target-spec.txt"),
              10
            )
          ),
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
      demoStreamingEnabled = true,
      statusLine = "",
      configStatus = configuredStatus,
      sessionInputs = Map.empty,
      compare = CompareState(Vector.empty, 0, 0, Vector.empty, None, Vector.empty),
      demo = DemoState(None, Vector.empty, 0, pending = false, ticks = 0L)
    )
