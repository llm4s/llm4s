package org.llm4s.samples.dashboard.providersetup.update

import org.llm4s.samples.dashboard.providersetup.{
  ProviderSetupCompare,
  ProviderSetupDemoConfig,
  ProviderSetupModeTransitions,
  ProviderSetupRuntime
}
import org.llm4s.samples.dashboard.providersetup.view.ProviderSetupView
import org.llm4s.samples.dashboard.providersetup.ProviderSetupMessages.*
import org.llm4s.samples.dashboard.providersetup.ProviderSetupModel.*
import org.llm4s.model.ModelRegistryService
import termflow.tui.KeyDecoder
import termflow.tui.PromptHistory
import termflow.tui.RuntimeCtx
import termflow.tui.Tui
import termflow.tui.Tui.*

private[providersetup] object CompareModeUpdate:

  def handle(
    model: Model,
    msg: Msg,
    ctx: RuntimeCtx[Msg]
  )(using ModelRegistryService): Tui[Model, Msg] =
    msg match
      case Msg.Compare(CompareMsg.RunCommand(command)) =>
        handleCompareCommand(model, command, model.demoAppConfigs.demoCfg, ctx)

      case Msg.Compare(CompareMsg.ConsoleInputKey(key)) =>
        ProviderSetupInputSupport.handleHistoryRecallKey(model, key).orElse(handleCompareNavKey(model, key)).getOrElse {
          val (nextPrompt, maybeCmd) =
            PromptHistory.handleKey[Msg](model.prompt, key)(ProviderSetupInputSupport.inputToMsg)
          maybeCmd match
            case Some(cmd) => Tui(model.updateShell(_.copy(prompt = nextPrompt)), cmd)
            case None      => model.updateShell(_.copy(prompt = nextPrompt)).tui
        }

      case _ =>
        model.tui

  private def handleCompareCommand(
    model: Model,
    raw: String,
    config: ProviderSetupDemoConfig,
    ctx: RuntimeCtx[Msg]
  )(using ModelRegistryService): Tui[Model, Msg] =
    val trimmed = raw.trim
    val lower   = trimmed.toLowerCase

    lower match
      case "help" =>
        model
          .copy(statusLine =
            "Compare commands: help, setup, clear, quit. Any other input runs the prompt across the compare set in parallel."
          )
          .tui

      case "setup" | "back" =>
        ProviderSetupModeTransitions
          .enterSetupMode(model, "Returned to setup. Compare set is still available.")
          .tui

      case "clear" =>
        model
          .updateCompare(
            _.copy(
              results = Vector.empty,
              activeTab = 0,
              prompt = None,
              scrollOffsets = Vector.empty
            )
          )
          .copy(
            statusLine = "Cleared compare results."
          )
          .tui

      case _ if model.compareResults.exists(_.status == CompareResultStatus.Pending) =>
        model.copy(statusLine = "A compare run is already in progress. Please wait for it to finish.").tui

      case _ =>
        val userEntry   = DemoEntry(DemoRole.User, trimmed)
        val baseEntries = Vector(userEntry)
        val pendingResults =
          model.compareSelections.map(selection => CompareResult(selection, CompareResultStatus.Pending))
        Tui(
          model
            .updateCompare(
              _.copy(
                results = pendingResults,
                activeTab = 0,
                prompt = Some(trimmed),
                scrollOffsets = Vector.fill(pendingResults.size)(0)
              )
            )
            .copy(
              statusLine = s"Running compare prompt across ${model.compareSelections.size} provider(s) in parallel..."
            ),
          ProviderSetupRuntime.compareCompletionStartCmd(
            model.demoAppConfigs,
            model.compareSelections,
            baseEntries,
            config,
            model.demoAppConfigs.exchangeLogging,
            ctx
          )
        )

  private def handleCompareNavKey(model: Model, key: KeyDecoder.InputKey): Option[Tui[Model, Msg]] =
    if model.prompt.prompt.buffer.nonEmpty then None
    else
      key match
        case KeyDecoder.InputKey.ArrowUp =>
          Some(scrollCompareResult(model, 1, "Scrolled compare result older."))
        case KeyDecoder.InputKey.ArrowDown =>
          Some(scrollCompareResult(model, -1, "Scrolled compare result newer."))
        case KeyDecoder.InputKey.ArrowLeft =>
          Some(ProviderSetupCompare.moveActiveCompareTab(model, -1).tui)
        case KeyDecoder.InputKey.ArrowRight =>
          Some(ProviderSetupCompare.moveActiveCompareTab(model, 1).tui)
        case KeyDecoder.InputKey.Ctrl('B') =>
          Some(scrollCompareResult(model, compareResultPageSize(model), "Paged compare result older."))
        case KeyDecoder.InputKey.Ctrl('F') =>
          Some(scrollCompareResult(model, -compareResultPageSize(model), "Paged compare result newer."))
        case KeyDecoder.InputKey.Home =>
          Some(
            scrollCompareToOffset(
              model,
              ProviderSetupView.compareRenderedMaxScrollOffset(model),
              "Jumped to oldest visible compare lines."
            )
          )
        case KeyDecoder.InputKey.End if model.compareResults.nonEmpty =>
          Some(scrollCompareToOffset(model, 0, "Returned compare result to latest."))
        case _ =>
          None

  private def scrollCompareResult(model: Model, delta: Int, message: String): Tui[Model, Msg] =
    scrollCompareToOffset(model, currentCompareScrollOffset(model) + delta, message)

  private def scrollCompareToOffset(model: Model, requestedOffset: Int, message: String): Tui[Model, Msg] =
    val nextOffset = clampCompareScrollOffset(model, requestedOffset)
    val nextStatus =
      if nextOffset == 0 then "Compare result pinned to latest lines."
      else s"$message ${nextOffset} line(s) above latest."
    model
      .updateCompare(_.copy(scrollOffsets = updateCompareScrollOffset(model, nextOffset)))
      .copy(statusLine = nextStatus)
      .tui

  private def currentCompareScrollOffset(model: Model): Int =
    model.compareScrollOffsets.lift(model.activeCompareTab).getOrElse(0)

  private def updateCompareScrollOffset(model: Model, nextOffset: Int): Vector[Int] =
    if model.compareResults.isEmpty then Vector.empty
    else
      val base =
        if model.compareScrollOffsets.length == model.compareResults.length then model.compareScrollOffsets
        else Vector.fill(model.compareResults.length)(0)
      val index = math.max(0, math.min(model.activeCompareTab, model.compareResults.length - 1))
      base.updated(index, nextOffset)

  private def clampCompareScrollOffset(model: Model, requestedOffset: Int): Int =
    math.max(0, math.min(requestedOffset, ProviderSetupView.compareRenderedMaxScrollOffset(model)))

  private def compareResultPageSize(model: Model): Int =
    math.max(1, ProviderSetupInputSupport.demoTranscriptCapacity(model) - 2)
