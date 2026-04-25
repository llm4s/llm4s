package org.llm4s.samples.dashboard.providersetup.update

import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.samples.dashboard.providersetup.ProviderSetupMessages.*
import org.llm4s.samples.dashboard.providersetup.ProviderSetupModel.*
import org.llm4s.samples.dashboard.providersetup.{
  ProviderSetupDemoConfig,
  ProviderSetupModeTransitions,
  ProviderSetupRuntime
}
import termflow.tui.RuntimeCtx
import termflow.tui.PromptHistory
import termflow.tui.Tui
import termflow.tui.Tui.*

private[providersetup] object DemoModeUpdate:

  def handle(
    model: Model,
    msg: Msg,
    ctx: RuntimeCtx[Msg]
  ): Tui[Model, Msg] =
    msg match
      case Msg.Demo(DemoMsg.RunCommand(command)) =>
        handleDemoCommand(model, command, model.demoAppConfigs.demoCfg, model.demoAppConfigs.exchangeLogging, ctx)

      case Msg.Demo(DemoMsg.ConsoleInputKey(key)) =>
        ProviderSetupInputSupport
          .handleHistoryRecallKey(model, key)
          .orElse(ProviderSetupInputSupport.handleDemoScrollKey(model, key))
          .getOrElse {
            val (nextPrompt, maybeCmd) =
              PromptHistory.handleKey[Msg](model.prompt, key)(ProviderSetupInputSupport.inputToMsg)
            maybeCmd match
              case Some(cmd) => Tui(model.updateShell(_.copy(prompt = nextPrompt)), cmd)
              case None      => model.updateShell(_.copy(prompt = nextPrompt)).tui
          }

      case _ =>
        model.tui

  private def handleDemoCommand(
    model: Model,
    raw: String,
    config: ProviderSetupDemoConfig,
    exchangeLogging: ProviderExchangeLogging,
    ctx: RuntimeCtx[Msg]
  ): Tui[Model, Msg] =
    val trimmed = raw.trim
    val lower   = trimmed.toLowerCase

    lower match
      case "help" =>
        model
          .copy(statusLine = "Demo commands: help, setup, clear, quit. Any other input is sent to the active model.")
          .tui

      case "setup" | "back" =>
        ProviderSetupModeTransitions
          .enterSetupMode(model, "Returned to setup. Session choice is still available for reuse.")
          .tui

      case "clear" =>
        val preservedEntries = model.activeSession.toVector.flatMap(ProviderSetupInputSupport.welcomeEntries)
        model
          .updateDemo(_.copy(entries = preservedEntries, scrollOffset = 0))
          .copy(statusLine = "Demo transcript cleared.")
          .tui

      case _ if model.demoPending =>
        model.copy(statusLine = "A demo reply is already in progress. Please wait for it to finish.").tui

      case _ =>
        model.activeSession match
          case None =>
            model.copy(statusLine = "No session provider is active. Return to setup and use a provider first.").tui
          case Some(session) =>
            val nextEntry   = DemoEntry(DemoRole.User, trimmed)
            val baseEntries = model.demoEntries :+ nextEntry
            val nextEntries =
              if config.streamingEnabled then baseEntries :+ DemoEntry(DemoRole.Assistant, "")
              else baseEntries
            val nextScroll =
              if config.streamingEnabled then
                val afterUser = ProviderSetupInputSupport.preserveDemoViewport(model, nextEntry, baseEntries)
                ProviderSetupInputSupport.preserveDemoViewport(
                  model.updateDemo(_.copy(entries = baseEntries, scrollOffset = afterUser)),
                  DemoEntry(DemoRole.Assistant, ""),
                  nextEntries
                )
              else ProviderSetupInputSupport.preserveDemoViewport(model, nextEntry, nextEntries)
            Tui(
              model
                .updateDemo(
                  _.copy(
                    entries = nextEntries,
                    scrollOffset = nextScroll,
                    pending = true
                  )
                )
                .copy(statusLine = s"Asking ${session.label}..."),
              ProviderSetupRuntime.demoCompletionCmd(session.config, baseEntries, config, exchangeLogging, ctx)
            )
