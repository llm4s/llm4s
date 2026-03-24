package org.llm4s.samples.dashboard.providersetup.update

import org.llm4s.samples.dashboard.providersetup.ProviderSetupMessages.*
import org.llm4s.samples.dashboard.providersetup.ProviderSetupModel.*
import termflow.tui.*

private[providersetup] object ProviderSetupUpdate:

  def apply(
    model: Model,
    msg: Msg,
    ctx: RuntimeCtx[Msg]
  ): Tui[Model, Msg] =
    ProviderSetupGlobalUpdate
      .handle(model, msg)
      .orElse(dispatchModeMessage(model, msg, ctx))
      .getOrElse(ProviderSetupInputSupport.handlePromptInput(model, msg))

  private def dispatchModeMessage(
    model: Model,
    msg: Msg,
    ctx: RuntimeCtx[Msg]
  ): Option[Tui[Model, Msg]] =
    msg match
      case Msg.Setup(input) =>
        Some(routeBoundaryInput(model, input, ctx))

      case Msg.Demo(_) =>
        Some(DemoModeUpdate.handle(model, msg, ctx))

      case Msg.Compare(_) =>
        Some(CompareModeUpdate.handle(model, msg, ctx))

      case _ =>
        None

  private def routeBoundaryInput(
    model: Model,
    input: SetupMsg,
    ctx: RuntimeCtx[Msg]
  ): Tui[Model, Msg] =
    model.screenMode match
      case ScreenMode.Setup =>
        SetupModeUpdate.handle(model, Msg.Setup(input), ctx)
      case ScreenMode.Demo =>
        input match
          case SetupMsg.ConsoleInputKey(key) =>
            DemoModeUpdate.handle(model, Msg.Demo(DemoMsg.ConsoleInputKey(key)), ctx)
          case SetupMsg.RunCommand(command) =>
            DemoModeUpdate.handle(model, Msg.Demo(DemoMsg.RunCommand(command)), ctx)
      case ScreenMode.Compare =>
        input match
          case SetupMsg.ConsoleInputKey(key) =>
            CompareModeUpdate.handle(model, Msg.Compare(CompareMsg.ConsoleInputKey(key)), ctx)
          case SetupMsg.RunCommand(command) =>
            CompareModeUpdate.handle(model, Msg.Compare(CompareMsg.RunCommand(command)), ctx)
