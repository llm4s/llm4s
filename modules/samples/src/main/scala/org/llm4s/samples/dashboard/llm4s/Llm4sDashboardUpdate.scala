package org.llm4s.samples.dashboard.llm4s

import org.llm4s.samples.dashboard.llm4s.Llm4sDashboardApp.DashboardModel
import org.llm4s.samples.dashboard.llm4s.Llm4sDashboardApp.Msg
import org.llm4s.samples.dashboard.shared.DashboardSupport
import termflow.tui.Cmd
import termflow.tui.KeyDecoder
import termflow.tui.PromptHistory
import termflow.tui.Tui
import termflow.tui.Tui._
import termflow.tui.widgets

private[llm4s] object Llm4sDashboardUpdate:

  def apply(model: DashboardModel, msg: Msg): Tui[DashboardModel, Msg] =
    msg match
      case Msg.Tick =>
        retailIfFollowing(model.copy(now = Llm4sDashboardRuntime.currentTime(), ticks = model.ticks + 1)).tui

      case Msg.Resize(width, height) =>
        retailIfFollowing(model.copy(terminalWidth = width, terminalHeight = height)).tui

      case Msg.ConsoleInputKey(key) =>
        handleConsoleInputKey(model, key)

      case Msg.ConsoleInputError(error) =>
        appendEvent(
          model.copy(status = s"input error: ${DashboardSupport.safeMessage(error)}"),
          s"input-error: ${DashboardSupport.safeMessage(error)}"
        ).tui

      case Msg.RunCommand(command) =>
        handleCommand(model, command)

      case Msg.ScrollEvents(delta) =>
        scrollEvents(model, delta).tui

      case Msg.ScrollEventsToEnd =>
        val maxScroll = Llm4sDashboardEventsMaxScroll(model)
        model.copy(eventScrollOffset = maxScroll, eventAutoTail = true).tui

      case Msg.ExitRequested =>
        Tui(model, Cmd.Exit)

  private def handleConsoleInputKey(model: DashboardModel, key: KeyDecoder.InputKey): Tui[DashboardModel, Msg] =
    val capacity = Llm4sDashboardEventsViewportHeight(model)
    key match
      case KeyDecoder.InputKey.PageUp =>
        scrollEvents(model, -capacity).tui
      case KeyDecoder.InputKey.PageDown =>
        scrollEvents(model, capacity).tui
      case KeyDecoder.InputKey.End =>
        val maxScroll = Llm4sDashboardEventsMaxScroll(model)
        model.copy(eventScrollOffset = maxScroll, eventAutoTail = true).tui
      case KeyDecoder.InputKey.Mouse(ev) =>
        // Modern (termflow 0.4): wheel scrolls the events log only when the
        // cursor is over its viewport rect. Outside-the-pane scrolls are
        // dropped so wheel events over the prompt don't page through history.
        widgets.LogView.scrollDelta(ev, Llm4sDashboardEventsViewport(model)) match
          case Some(d) => scrollEvents(model, d).tui
          case None    => model.tui
      case _ =>
        val (nextPrompt, maybeCmd) = PromptHistory.handleKey[Msg](model.prompt, key)(Llm4sDashboardApp.toMsg)
        maybeCmd match
          case Some(cmd) => Tui(model.copy(prompt = nextPrompt), cmd)
          case None      => model.copy(prompt = nextPrompt).tui

  private def appendEvent(model: DashboardModel, event: String): DashboardModel =
    val nextEvents = (model.events :+ event).takeRight(64)
    retailIfFollowing(model.copy(events = nextEvents))

  private def retailIfFollowing(model: DashboardModel): DashboardModel =
    val maxScroll = Llm4sDashboardEventsMaxScroll(model)
    if model.eventAutoTail then model.copy(eventScrollOffset = maxScroll)
    else model.copy(eventScrollOffset = math.min(model.eventScrollOffset, maxScroll))

  private def scrollEvents(model: DashboardModel, delta: Int): DashboardModel =
    val maxScroll = Llm4sDashboardEventsMaxScroll(model)
    val next      = math.max(0, math.min(maxScroll, model.eventScrollOffset + delta))
    model.copy(eventScrollOffset = next, eventAutoTail = next >= maxScroll)

  private def handleCommand(model: DashboardModel, command: String): Tui[DashboardModel, Msg] =
    command match
      case "help" =>
        appendEvent(
          model.copy(status = "Commands: help, provider, pulse, clear, quit."),
          "help: command list requested"
        ).tui

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
        retailIfFollowing(
          model.copy(
            status = "Recent events cleared.",
            events = Vector("events: cleared by operator")
          )
        ).tui

      case other =>
        appendEvent(
          model.copy(status = s"Unhandled command: $other"),
          s"warn: unhandled command $other"
        ).tui
