package org.llm4s.samples.chat.tui

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Conversation, SystemMessage }
import org.llm4s.samples.chat.tui.ChatTuiModel.{ Model, Msg, StreamSession }
import org.llm4s.toolapi.ToolRegistry
import termflow.tui.*
import termflow.tui.Tui.*
import termflow.tui.TuiPrelude.*

import scala.concurrent.ExecutionContext

/**
 * `TuiApp` for the chat-tui demo. Threads the immutable [[ChatTuiConfig]],
 * the resolved [[LLMClient]], and the registered tools through the runtime
 * via constructor parameters; every `update` call has access to all three
 * without further env wiring.
 */
final class ChatTuiApp(
  config: ChatTuiConfig,
  client: LLMClient,
  registry: ToolRegistry
)(using ec: ExecutionContext)
    extends TuiApp[Model, Msg]:

  override def init(ctx: RuntimeCtx[Msg]): Tui[Model, Msg] =
    Sub.InputKey(Msg.ConsoleInputKey.apply, Msg.ConsoleInputError.apply, ctx)
    Sub.TerminalResize(250L, Msg.Resize.apply, ctx)

    val systemEntry = Entry(Role.System, config.systemPrompt)
    val initialConv = Conversation(Seq(SystemMessage(config.systemPrompt)))

    Model(
      width = ctx.terminal.width,
      height = ctx.terminal.height,
      config = config,
      client = client,
      conversation = initialConv,
      entries = Vector(systemEntry),
      scrollOffset = 0,
      autoTail = true,
      prompt = PromptHistory.initial(InMemoryHistoryStore(maxEntries = 200)),
      pending = PendingState.Idle,
      theme = Theme.dark,
      status = "Ready. Type a message or /help.",
      session = new StreamSession()
    ).tui

  override def update(model: Model, msg: Msg, ctx: RuntimeCtx[Msg]): Tui[Model, Msg] =
    ChatTuiUpdate(registry, model, msg, ctx)

  override def view(model: Model): RootNode = ChatTuiView.view(model)

  override def toMsg(input: PromptLine): Result[Msg] =
    // Note: this is the fallback used when the runtime parses input outside
    // of a focused PromptHistory — the prompt itself routes through
    // PromptHistory.handleKey which calls ChatTuiCommands.toMsg with the
    // full model. Without a model here we can only do the static parse.
    val raw = input.value.trim
    raw match {
      case ""                     => Right(Msg.NoOp)
      case "/quit" | "/exit"      => Right(Msg.Quit)
      case s if s.startsWith("/") => Left(TermFlowError.CommandError(s))
      case other                  => Right(Msg.Submit(other))
    }
