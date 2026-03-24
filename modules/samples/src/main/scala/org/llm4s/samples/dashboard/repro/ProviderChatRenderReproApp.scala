package org.llm4s.samples.dashboard.repro
import termflow.tui.*
import termflow.tui.Tui.*
import termflow.tui.TuiPrelude.*

object ProviderChatRenderReproApp:

  enum Role:
    case System
    case User
    case Assistant

  final case class Entry(role: Role, content: String)

  final case class Model(
    terminalWidth: Int,
    terminalHeight: Int,
    prompt: PromptHistory.State,
    entries: Vector[Entry],
    seedRound: Int,
    status: String
  )

  enum Msg:
    case Resize(width: Int, height: Int)
    case ConsoleInputKey(key: KeyDecoder.InputKey)
    case ConsoleInputError(error: Throwable)
    case RunCommand(command: String)
    case ExitRequested

  import Msg._

  object App extends TuiApp[Model, Msg]:
    override def init(ctx: RuntimeCtx[Msg]): Tui[Model, Msg] =
      Sub.InputKey(ConsoleInputKey.apply, ConsoleInputError.apply, ctx)
      Sub.TerminalResize(250L, Resize.apply, ctx)

      Model(
        terminalWidth = ctx.terminal.width,
        terminalHeight = ctx.terminal.height,
        prompt = PromptHistory.initial(InMemoryHistoryStore(maxEntries = 100)),
        entries = seedEntries,
        seedRound = 0,
        status = "Type text to append a fake turn. Commands: seed, clear, help, quit."
      ).tui

    override def update(model: Model, msg: Msg, ctx: RuntimeCtx[Msg]): Tui[Model, Msg] =
      ProviderChatRenderReproUpdate(model, msg, ctx)

    override def view(model: Model): RootNode =
      ProviderChatRenderReproView(model)

    override def toMsg(input: PromptLine): Result[Msg] =
      input.value.trim match
        case ""                     => Right(RunCommand("help"))
        case "quit" | "exit" | ":q" => Right(ExitRequested)
        case other                  => Right(RunCommand(other))
