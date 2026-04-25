package org.llm4s.samples.dashboard.llm4s
import org.llm4s.llmconnect.config.ProviderConfig
import termflow.tui.*
import termflow.tui.Tui.*
import termflow.tui.TuiPrelude.*

import scala.annotation.unused

object Llm4sDashboardApp:

  final case class DashboardModel(
    terminalWidth: Int,
    terminalHeight: Int,
    now: String,
    prompt: PromptHistory.State,
    status: String,
    providerStatus: String,
    events: Vector[String],
    ticks: Long
  )

  enum Msg:
    case Tick
    case Resize(width: Int, height: Int)
    case ConsoleInputKey(key: KeyDecoder.InputKey)
    case ConsoleInputError(error: Throwable)
    case RunCommand(command: String)
    case ExitRequested

  import Msg._

  def App(providerConfig: ProviderConfig): TuiApp[DashboardModel, Msg] =
    new TuiApp[DashboardModel, Msg]:
      override def init(ctx: RuntimeCtx[Msg]): Tui[DashboardModel, Msg] =
        Sub.InputKey(ConsoleInputKey.apply, ConsoleInputError.apply, ctx)
        Sub.Every(1000L, () => Tick, ctx)
        Sub.TerminalResize(250L, Resize.apply, ctx)

        DashboardModel(
          terminalWidth = ctx.terminal.width,
          terminalHeight = ctx.terminal.height,
          now = Llm4sDashboardRuntime.currentTime(),
          prompt = PromptHistory.initial(InMemoryHistoryStore(maxEntries = 100)),
          status = "Dashboard ready. Type help, provider, pulse, clear, or quit.",
          providerStatus = s"${providerConfig.provider} / ${providerConfig.model}",
          events = Vector(
            "boot: llm4s dashboard launched",
            "integration: termflow resolved from published dependency"
          ),
          ticks = 0L
        ).tui

      override def update(model: DashboardModel, msg: Msg, @unused ctx: RuntimeCtx[Msg]): Tui[DashboardModel, Msg] =
        Llm4sDashboardUpdate(model, msg)

      override def view(model: DashboardModel): RootNode =
        Llm4sDashboardView(model)

      override def toMsg(input: PromptLine): Result[Msg] =
        Llm4sDashboardApp.toMsg(input)

  def toMsg(input: PromptLine): Result[Msg] =
    input.value.trim match
      case ""                                      => Right(RunCommand("help"))
      case "quit" | "exit" | ":q"                  => Right(ExitRequested)
      case "help" | "provider" | "pulse" | "clear" => Right(RunCommand(input.value.trim))
      case other                                   => Left(TermFlowError.Validation(s"Unknown command: $other"))
