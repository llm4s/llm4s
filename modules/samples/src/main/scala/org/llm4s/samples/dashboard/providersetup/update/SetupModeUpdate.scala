package org.llm4s.samples.dashboard.providersetup.update

import org.llm4s.llmconnect.config.ProviderConfig
import org.llm4s.samples.dashboard.providersetup.ProviderSetupMessages.*
import org.llm4s.samples.dashboard.providersetup.ProviderSetupModel.*
import org.llm4s.samples.dashboard.providersetup.{
  ProviderSetupCompare,
  ProviderSetupModeTransitions,
  ProviderSetupProviderSelection,
  ProviderSetupRuntime,
  ProviderSetupSetupPolicy
}
import org.llm4s.samples.dashboard.providersetup.ProviderSetupTabs
import org.llm4s.types.ProviderModelTypes.ProviderName
import termflow.tui.Cmd
import termflow.tui.KeyDecoder
import termflow.tui.RuntimeCtx
import termflow.tui.Tui
import termflow.tui.Tui.*
import termflow.tui.PromptHistory

private[providersetup] object SetupModeUpdate:

  def handle(
    model: Model,
    msg: Msg,
    @scala.annotation.unused ctx: RuntimeCtx[Msg]
  ): Tui[Model, Msg] =
    msg match
      case Msg.Setup(SetupMsg.RunCommand(command)) =>
        handleSetupCommand(model, command)

      case Msg.Setup(SetupMsg.ConsoleInputKey(key)) if ProviderSetupTabs.shouldHandleNavKeys(model.prompt, key) =>
        ProviderSetupTabs.handleNavKey(model, key, model.demoAppConfigs.docs).tui

      case Msg.Setup(SetupMsg.ConsoleInputKey(KeyDecoder.InputKey.Enter | KeyDecoder.InputKey.Ctrl('M')))
          if ProviderSetupSetupPolicy.shouldRouteEnterToModelAction(model) &&
            ProviderSetupSetupPolicy.shouldConfirmHighlightedModel(
              model.prompt,
              model
            ) =>
        ProviderSetupSetupPolicy.handleSetupEnter(model).tui

      case Msg.Setup(SetupMsg.ConsoleInputKey(key)) =>
        val (nextPrompt, maybeCmd) =
          PromptHistory.handleKey[Msg](model.prompt, key)(ProviderSetupInputSupport.inputToMsg)
        maybeCmd match
          case Some(cmd) => Tui(model.updateShell(_.copy(prompt = nextPrompt)), cmd)
          case None      => model.updateShell(_.copy(prompt = nextPrompt)).tui

      case _ =>
        model.tui

  private def handleSetupCommand(model: Model, raw: String): Tui[Model, Msg] =
    val lower = raw.trim.toLowerCase

    lower match
      case "help" =>
        model
          .copy(
            statusLine =
              "Commands: help, next, prev, tab <name>, reload, use, set <field> <value>, clear session, quit."
          )
          .tui

      case "next" =>
        val nextIndex = (ProviderSetupTabs.setupTabIndex(model.activeTab) + 1) % ProviderSetupTabs.setupTabCount
        ProviderSetupTabs.selectTab(model, ProviderSetupTabs.setupTabAt(nextIndex)).tui

      case "prev" =>
        val currentIndex = ProviderSetupTabs.setupTabIndex(model.activeTab)
        val nextIndex =
          if currentIndex == 0 then ProviderSetupTabs.setupTabCount - 1 else currentIndex - 1
        ProviderSetupTabs.selectTab(model, ProviderSetupTabs.setupTabAt(nextIndex)).tui

      case "reload" | "status" =>
        Tui(model, Cmd.GCmd(Msg.Global(GlobalMsg.RefreshStatus)))

      case "clear session" =>
        val target = ProviderSetupProviderSelection.selectedSessionOverrideTarget(model)
        model
          .copy(
            sessionInputs = model.sessionInputs - target,
            statusLine = s"Cleared session-only values for ${target.displayValue}."
          )
          .tui

      case "focus main" =>
        ProviderSetupTabs.focusPanel(model, PanelFocus.Main).tui

      case "focus models" =>
        ProviderSetupTabs.focusPanel(model, PanelFocus.Models).tui

      case "focus status" =>
        ProviderSetupTabs.focusPanel(model, PanelFocus.Status).tui

      case "use" =>
        if ProviderSetupSetupPolicy.isCompareTab(model) then activateCompareSession(model)
        else activateDemoSession(model, model.demoAppConfigs.providerConfigs)

      case remove if remove.startsWith("remove ") && ProviderSetupSetupPolicy.canRemoveCompareEntry(model) =>
        val requested = remove.stripPrefix("remove ").trim
        model.compareSelections.indexWhere(_.providerName == requested) match
          case -1 =>
            model.copy(statusLine = s"Compare entry not found: $requested").tui
          case idx =>
            ProviderSetupCompare
              .removeHighlightedCompareEntry(model.updateCompare(_.copy(highlightedSelectionIndex = idx)))
              .tui

      case select if select.startsWith("select ") && ProviderSetupSetupPolicy.canSelectModelByCommand(model) =>
        ProviderSetupProviderSelection.selectHighlightedModel(model, select.stripPrefix("select ").trim)

      case set if set.startsWith("set ") =>
        updateSessionInput(model, set.stripPrefix("set ").trim)

      case tab if tab.startsWith("tab ") =>
        ProviderSetupTabs.selectTabTarget(model, tab.stripPrefix("tab ")).tui

      case other =>
        model.copy(statusLine = s"Unknown command: $other").tui

  private def activateCompareSession(model: Model): Tui[Model, Msg] =
    if model.compareSelections.lengthCompare(2) < 0 then
      model.copy(statusLine = "Add at least two provider/model entries before starting compare mode.").tui
    else
      val initialResults =
        model.compareSelections.map(selection => CompareResult(selection, CompareResultStatus.Success))
      ProviderSetupModeTransitions
        .enterCompareMode(
          model,
          initialResults,
          s"Compare mode ready with ${model.compareSelections.size} providers. Type one prompt to run them in parallel."
        )
        .tui

  private def activateDemoSession(
    model: Model,
    providerConfigs: Map[ProviderName, ProviderConfig]
  ): Tui[Model, Msg] =
    ProviderSetupProviderSelection.currentSetupSessionRequest(model).left.map(_.formatted).flatMap { sessionRequest =>
      ProviderSetupRuntime.resolveSession(
        providerConfigs,
        model.demoAppConfigs.defaultProvider,
        isDefaultProviderTab = sessionRequest.isDefaultProviderTab,
        activeTab = sessionRequest.activeTab,
        activeDocId = sessionRequest.activeDocId,
        selectedProviderKind = sessionRequest.selectedProviderKind,
        selectedConfiguredProvider = sessionRequest.selectedConfiguredProvider,
        sessionInput = model.sessionInputs.get(sessionRequest.sessionTarget)
      )
    } match
      case Left(message) =>
        model.copy(statusLine = message).tui
      case Right(session) =>
        ProviderSetupModeTransitions
          .enterDemoMode(
            model,
            session,
            s"Using ${session.label} for this app session. Type a prompt to start the demo."
          )
          .updateDemo(_.copy(entries = ProviderSetupInputSupport.welcomeEntries(session)))
          .tui

  private def updateSessionInput(model: Model, raw: String): Tui[Model, Msg] =
    raw.split("\\s+", 2).toList match
      case field :: value :: Nil if value.trim.nonEmpty =>
        val target  = ProviderSetupProviderSelection.selectedSessionOverrideTarget(model)
        val current = model.sessionInputs.getOrElse(target, ProviderSessionInput())
        val trimmed = value.trim
        val next =
          field.toLowerCase match
            case "model"       => current.copy(model = Some(trimmed))
            case "api-key"     => current.copy(apiKey = Some(trimmed))
            case "base-url"    => current.copy(baseUrl = Some(trimmed))
            case "org-id"      => current.copy(organization = Some(trimmed))
            case "endpoint"    => current.copy(endpoint = Some(trimmed))
            case "api-version" => current.copy(apiVersion = Some(trimmed))
            case other =>
              return model.copy(statusLine = s"Unsupported field for session override: $other").tui

        model
          .copy(
            sessionInputs = model.sessionInputs.updated(target, next),
            statusLine = s"Set session-only $field for ${target.displayValue}."
          )
          .tui
      case _ =>
        model.copy(statusLine = "Use: set <field> <value>. Example: set api-key sk-...").tui
