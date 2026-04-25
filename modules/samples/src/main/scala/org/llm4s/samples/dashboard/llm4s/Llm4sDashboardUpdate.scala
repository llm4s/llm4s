package org.llm4s.samples.dashboard.llm4s

import org.llm4s.samples.dashboard.llm4s.Llm4sDashboardApp.DashboardModel
import org.llm4s.samples.dashboard.llm4s.Llm4sDashboardApp.Msg
import org.llm4s.samples.dashboard.shared.DashboardSupport
import termflow.tui.Cmd
import termflow.tui.PromptHistory
import termflow.tui.Tui
import termflow.tui.Tui._

private[llm4s] object Llm4sDashboardUpdate:

  def apply(model: DashboardModel, msg: Msg): Tui[DashboardModel, Msg] =
    msg match
      case Msg.Tick =>
        model.copy(now = Llm4sDashboardRuntime.currentTime(), ticks = model.ticks + 1).tui

      case Msg.Resize(width, height) =>
        model.copy(terminalWidth = width, terminalHeight = height).tui

      case Msg.ConsoleInputKey(key) =>
        val (nextPrompt, maybeCmd) = PromptHistory.handleKey[Msg](model.prompt, key)(Llm4sDashboardApp.toMsg)
        maybeCmd match
          case Some(cmd) => Tui(model.copy(prompt = nextPrompt), cmd)
          case None      => model.copy(prompt = nextPrompt).tui

      case Msg.ConsoleInputError(error) =>
        appendEvent(
          model.copy(status = s"input error: ${DashboardSupport.safeMessage(error)}"),
          s"input-error: ${DashboardSupport.safeMessage(error)}"
        ).tui

      case Msg.RunCommand(command) =>
        handleCommand(model, command)

      case Msg.ExitRequested =>
        Tui(model, Cmd.Exit)

  private def appendEvent(model: DashboardModel, event: String): DashboardModel =
    model.copy(events = (model.events :+ event).takeRight(8))

  private def handleCommand(model: DashboardModel, command: String): Tui[DashboardModel, Msg] =
    command match
      case "help" =>
        model
          .copy(
            status = "Commands: help, provider, pulse, clear, quit.",
            events = (model.events :+ "help: command list requested").takeRight(8)
          )
          .tui

      case "provider" =>
        appendEvent(
          model.copy(status = s"Provider status: ${model.providerStatus}"),
          s"provider: ${model.providerStatus}"
        ).tui

      case "pulse" =>
        appendEvent(
          model.copy(status = s"Pulse recorded at ${model.now}"),
          s"pulse: dashboard heartbeat at ${model.now}"
        ).tui

      case "clear" =>
        model
          .copy(
            status = "Recent events cleared.",
            events = Vector("events: cleared by operator")
          )
          .tui

      case other =>
        appendEvent(
          model.copy(status = s"Unhandled command: $other"),
          s"warn: unhandled command $other"
        ).tui
