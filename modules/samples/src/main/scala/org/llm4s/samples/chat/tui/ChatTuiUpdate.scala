package org.llm4s.samples.chat.tui

import org.llm4s.llmconnect.model.{
  AssistantMessage,
  CompletionOptions,
  Conversation,
  StreamedChunk,
  SystemMessage,
  ToolCall,
  ToolMessage,
  UserMessage
}
import org.llm4s.samples.chat.tui.ChatTuiModel.{ Model, Msg }
import org.llm4s.toolapi.{ ToolCallRequest, ToolRegistry }
import termflow.tui.*
import termflow.tui.Tui.*
import termflow.tui.TuiPrelude.*

import scala.concurrent.{ ExecutionContext, Future }

/**
 * The big match — translates one [[Msg]] into a `Tui[Model, Msg]`. Kept
 * as a private object so the public surface stays just `ChatTuiApp`.
 */
private[tui] object ChatTuiUpdate:

  def apply(
    registry: ToolRegistry,
    model: Model,
    msg: Msg,
    ctx: RuntimeCtx[Msg]
  )(using ec: ExecutionContext): Tui[Model, Msg] =
    msg match {
      case Msg.Resize(w, h) =>
        retailIfFollowing(model.copy(width = w, height = h)).tui

      case Msg.ConsoleInputKey(key) =>
        handleKey(model, key, ctx)

      case Msg.ConsoleInputError(err) =>
        model
          .copy(status = s"input error: ${Option(err.getMessage).getOrElse("unknown")}")
          .tui

      case Msg.NoOp =>
        model.tui

      case Msg.Submit(text) =>
        startStreaming(model, registry, withUserMessage = Some(text), ctx)

      case Msg.AppendHelpEntry =>
        appendSystemEntry(model, helpText).tui

      case Msg.AppendToolsEntry =>
        appendSystemEntry(model, toolsText(registry)).tui

      case Msg.ClearConversation =>
        clearConversation(model).tui

      case Msg.SetModel(name) =>
        model.copy(config = model.config.copy(modelName = name), status = s"Model: $name").tui

      case Msg.ToggleTheme =>
        val nextTheme = if model.theme == Theme.dark then Theme.light else Theme.dark
        model.copy(theme = nextTheme, status = s"Theme: ${if nextTheme == Theme.dark then "dark" else "light"}").tui

      case Msg.SetTheme(t) =>
        model.copy(theme = t, status = s"Theme: ${if t == Theme.dark then "dark" else "light"}").tui

      case Msg.SetSystem(prompt) =>
        val nextEntries = model.entries match {
          case h +: tail if h.role == Role.System =>
            h.copy(content = prompt) +: tail
          case other =>
            Entry(Role.System, prompt) +: other
        }
        val nextConv = updateOrPrependSystemMessage(model.conversation, prompt)
        retailIfFollowing(
          model.copy(
            config = model.config.copy(systemPrompt = prompt),
            entries = nextEntries,
            conversation = nextConv,
            status = "System prompt updated."
          )
        ).tui

      case Msg.PumpTick =>
        pumpTick(model)

      case Msg.StreamComplete(generation, finalChunks) =>
        handleStreamComplete(model, registry, generation, finalChunks)

      case Msg.StreamError(generation, err) =>
        handleStreamError(model, generation, err)

      case Msg.ToolApprove =>
        approveTool(model, registry, ctx)

      case Msg.ToolDeny =>
        denyTool(model, registry, ctx)

      case Msg.ToolResult(outcome) =>
        completeToolRoundTrip(model, outcome, ctx, registry)

      case Msg.ResumeAfterTool =>
        startStreaming(model, registry, withUserMessage = None, ctx)

      case Msg.ScrollBy(delta) =>
        scrollBy(model, delta).tui

      case Msg.ScrollToEnd =>
        val mx = ChatTuiView.maxScroll(model)
        model.copy(scrollOffset = mx, autoTail = true).tui

      case Msg.Quit =>
        Tui(model, Cmd.Exit)
    }

  // ---- Key handling -------------------------------------------------------

  private def handleKey(model: Model, key: KeyDecoder.InputKey, ctx: RuntimeCtx[Msg]): Tui[Model, Msg] =
    val transcriptHeight = ChatTuiView.transcriptHeight(model)
    key match {
      case KeyDecoder.InputKey.PageUp   => scrollBy(model, -transcriptHeight).tui
      case KeyDecoder.InputKey.PageDown => scrollBy(model, transcriptHeight).tui
      case KeyDecoder.InputKey.End =>
        val mx = ChatTuiView.maxScroll(model)
        model.copy(scrollOffset = mx, autoTail = true).tui

      case KeyDecoder.InputKey.Mouse(ev) =>
        widgets.LogView.scrollDelta(ev, ChatTuiView.transcriptViewport(model)) match {
          case Some(d) => scrollBy(model, d).tui
          case None    => model.tui
        }

      case KeyDecoder.InputKey.Ctrl('L') =>
        clearConversation(model).tui

      case KeyDecoder.InputKey.Ctrl('T') =>
        val nextTheme = if model.theme == Theme.dark then Theme.light else Theme.dark
        model.copy(theme = nextTheme).tui

      case KeyDecoder.InputKey.Escape =>
        handleEscape(model, ctx)

      case KeyDecoder.InputKey.Enter if model.pending.isInstanceOf[PendingState.AwaitingToolApproval] =>
        Tui(model, Cmd.GCmd(Msg.ToolApprove))

      case _ =>
        val (nextPrompt, maybeCmd) =
          PromptHistory.handleKey[Msg](model.prompt, key)(ChatTuiCommands.toMsg(model))
        maybeCmd match {
          case Some(cmd) => Tui(model.copy(prompt = nextPrompt), cmd)
          case None      => model.copy(prompt = nextPrompt).tui
        }
    }

  private def handleEscape(model: Model, ctx: RuntimeCtx[Msg]): Tui[Model, Msg] =
    model.pending match {
      case PendingState.Idle =>
        Tui(model, Cmd.Exit)

      case PendingState.Streaming(intoIdx, _) =>
        // Abort: bump the generation so any in-flight StreamComplete is
        // ignored, cancel the pump, and mark the partial reply.
        model.session.reset()
        val updated    = appendSuffix(model.entries, intoIdx, " …(aborted)")
        val nextStatus = "Stream aborted."
        retailIfFollowing(
          model.copy(
            entries = updated,
            pending = PendingState.Idle,
            status = nextStatus,
            conversation = model.conversation.addMessage(
              AssistantMessage(updated(intoIdx).content)
            )
          )
        ).tui

      case _: PendingState.AwaitingToolApproval =>
        Tui(model, Cmd.GCmd(Msg.ToolDeny))

      case _: PendingState.ExecutingTool =>
        // Best-effort: tool results still arrive; just leave it running.
        ctx.publish(Cmd.NoCmd)
        model.tui
    }

  // ---- Streaming lifecycle -----------------------------------------------

  private def startStreaming(
    model: Model,
    registry: ToolRegistry,
    withUserMessage: Option[String],
    ctx: RuntimeCtx[Msg]
  )(using ec: ExecutionContext): Tui[Model, Msg] =
    val withUser =
      withUserMessage match {
        case Some(text) =>
          val conv = model.conversation.addMessage(UserMessage(text))
          val ent  = model.entries :+ Entry(Role.User, text)
          (conv, ent)
        case None =>
          (model.conversation, model.entries)
      }
    val (conv, withUserEntries) = withUser
    val assistantIdx            = withUserEntries.length
    val entriesForStream        = withUserEntries :+ Entry(Role.Assistant, "")

    val pump       = TokenPump()
    val generation = model.session.generation + 1L
    model.session.pump = pump
    model.session.generation = generation

    val pumpSub = Sub.Every(33L, () => Msg.PumpTick, ctx)
    model.session.sub = pumpSub

    val options = CompletionOptions(tools = registry.tools)
    val task    = ChatTuiStreaming.start(model.client, conv, options, pump)

    val nextModel = model.copy(
      conversation = conv,
      entries = entriesForStream,
      pending = PendingState.Streaming(assistantIdx, generation),
      status = "Streaming…",
      autoTail = true,
      scrollOffset = ChatTuiView.maxScroll(model)
    )

    // Capture the pump itself, not `model.session`. If the user aborts
    // and starts a new turn before this future resolves, `session.pump`
    // would point at the *new* turn's pump and a stale callback would
    // drain its tokens. The closure must drain only the pump it owns.
    val capturedPump = pump
    val cmd = Cmd.asyncResult[org.llm4s.llmconnect.model.Completion, Msg](
      task = task,
      onSuccess = (_: org.llm4s.llmconnect.model.Completion) => Msg.StreamComplete(generation, capturedPump.drainAll()),
      onError = (err: TermFlowError) => Msg.StreamError(generation, err)
    )

    Tui(nextModel, cmd)

  private def pumpTick(model: Model): Tui[Model, Msg] =
    model.pending match {
      case PendingState.Streaming(intoIdx, _) =>
        val pump = model.session.pump
        if pump == null then model.tui
        else
          val chunks = pump.drain()
          if chunks.isEmpty then model.tui
          else applyChunks(model, intoIdx, chunks).tui
      case _ =>
        model.tui
    }

  private def applyChunks(model: Model, intoIdx: Int, chunks: Vector[StreamedChunk]): Model =
    // Capture tool-call deltas onto the session so handleStreamComplete
    // sees them even when they were drained mid-stream by a pump tick.
    val toolCalls = chunks.flatMap(_.toolCall)
    if toolCalls.nonEmpty then model.session.toolCalls = model.session.toolCalls ++ toolCalls
    val newText = chunks.flatMap(_.content).mkString
    if newText.isEmpty then model
    else
      val updated = appendToEntry(model.entries, intoIdx, newText)
      retailIfFollowing(model.copy(entries = updated))

  private def handleStreamComplete(
    model: Model,
    @scala.annotation.unused registry: ToolRegistry,
    generation: Long,
    finalChunks: Vector[StreamedChunk]
  ): Tui[Model, Msg] =
    model.pending match {
      case PendingState.Streaming(intoIdx, gen) if gen == generation =>
        // Apply any leftover tokens captured between the last pump tick and
        // the completion arrival. `applyChunks` also harvests `toolCall`
        // deltas from `finalChunks` onto `session.toolCalls`.
        val flushed = applyChunks(model, intoIdx, finalChunks)

        // Snapshot the buffered tool calls before `reset()` clears them.
        // This includes calls drained by earlier pump ticks plus any
        // captured from `finalChunks` above.
        val bufferedToolCalls = flushed.session.toolCalls

        // Cancel the pump; we're done with the live stream.
        flushed.session.reset()

        val accumulatedText  = flushed.entries(intoIdx).content
        val toolCallFromMsgs = bufferedToolCalls.headOption

        toolCallFromMsgs match {
          case Some(call) =>
            val annotated = decorateAssistantWithTool(flushed.entries, intoIdx, call)
            val convWithAssistant =
              flushed.conversation.addMessage(AssistantMessage(accumulatedText, Seq(call)))
            flushed
              .copy(
                entries = annotated,
                conversation = convWithAssistant,
                pending = PendingState.AwaitingToolApproval(call, intoIdx),
                status = s"Tool requested: ${call.name}"
              )
              .tui

          case None =>
            val convWithAssistant =
              flushed.conversation.addMessage(AssistantMessage(accumulatedText))
            val notify =
              if !flushed.autoTail then Cmd.RequestAttention else Cmd.NoCmd
            Tui(
              flushed.copy(
                conversation = convWithAssistant,
                pending = PendingState.Idle,
                status = "Ready."
              ),
              notify
            )
        }

      case _ =>
        // Stale completion — the user aborted or another turn already
        // started. Just drop it.
        model.tui
    }

  private def handleStreamError(model: Model, generation: Long, err: TermFlowError): Tui[Model, Msg] =
    model.pending match {
      case PendingState.Streaming(_, gen) if gen == generation =>
        model.session.reset()
        Tui(
          model.copy(pending = PendingState.Idle, status = "Stream failed."),
          Cmd.TermFlowErrorCmd(err)
        )
      case _ =>
        model.tui
    }

  // ---- Tool flow ---------------------------------------------------------

  private def approveTool(
    model: Model,
    registry: ToolRegistry,
    @scala.annotation.unused ctx: RuntimeCtx[Msg]
  )(using ec: ExecutionContext): Tui[Model, Msg] =
    model.pending match {
      case PendingState.AwaitingToolApproval(call, intoIdx) =>
        val nextModel = model.copy(
          pending = PendingState.ExecutingTool(call, intoIdx),
          status = s"Running ${call.name}…"
        )
        val task: AsyncResult[ToolOutcome] = Future {
          registry.execute(ToolCallRequest(call.name, call.arguments)) match {
            case Right(json) => Right(toolOutcomeFromJson(json))
            case Left(err)   => Right(ToolOutcome.Err(err.getMessage))
          }
        }
        val cmd = Cmd.asyncResult[ToolOutcome, Msg](
          task = task,
          onSuccess = (outcome: ToolOutcome) => Msg.ToolResult(outcome),
          onError = (err: TermFlowError) => Msg.StreamError(0L, err)
        )
        Tui(nextModel, cmd)

      case _ =>
        model.tui
    }

  private def denyTool(
    model: Model,
    registry: ToolRegistry,
    ctx: RuntimeCtx[Msg]
  ): Tui[Model, Msg] =
    model.pending match {
      case PendingState.AwaitingToolApproval(call, intoIdx) =>
        val outcome = ToolOutcome.Denied
        completeToolRoundTrip(
          model.copy(pending = PendingState.ExecutingTool(call, intoIdx)),
          outcome,
          ctx,
          registry
        )
      case _ =>
        model.tui
    }

  private def completeToolRoundTrip(
    model: Model,
    outcome: ToolOutcome,
    @scala.annotation.unused ctx: RuntimeCtx[Msg],
    @scala.annotation.unused registry: ToolRegistry
  ): Tui[Model, Msg] =
    model.pending match {
      case PendingState.ExecutingTool(call, intoIdx) =>
        val annotated    = annotateOutcome(model.entries, intoIdx, outcome)
        val toolJson     = renderToolMessageContent(outcome)
        val convWithTool = model.conversation.addMessage(ToolMessage(toolJson, call.id))
        val withRow      = annotated :+ Entry(Role.Tool, summaryFor(outcome, call.name))
        val resumed = model.copy(
          conversation = convWithTool,
          entries = withRow,
          pending = PendingState.Idle,
          status = s"Tool ${call.name}: ${labelFor(outcome)}"
        )
        // Resume streaming on the augmented conversation.
        Tui(resumed, Cmd.GCmd(Msg.ResumeAfterTool))

      case _ =>
        model.tui
    }

  private def labelFor(outcome: ToolOutcome): String = outcome match {
    case ToolOutcome.Ok(_)  => "ok"
    case ToolOutcome.Err(_) => "error"
    case ToolOutcome.Denied => "denied"
  }

  private def summaryFor(outcome: ToolOutcome, name: String): String = outcome match {
    case ToolOutcome.Ok(s)  => s"$name → $s"
    case ToolOutcome.Err(m) => s"$name failed: $m"
    case ToolOutcome.Denied => s"$name denied"
  }

  private def renderToolMessageContent(outcome: ToolOutcome): String = outcome match {
    case ToolOutcome.Ok(summary) =>
      ujson.Obj("ok" -> true, "summary" -> summary).render()
    case ToolOutcome.Err(message) =>
      ujson.Obj("ok" -> false, "error" -> message).render()
    case ToolOutcome.Denied =>
      ujson.Obj("ok" -> false, "denied" -> true, "reason" -> "user denied").render()
  }

  /** Convert the raw json result returned by `ToolRegistry.execute` into a UI outcome. */
  private def toolOutcomeFromJson(json: ujson.Value): ToolOutcome =
    json match {
      case obj: ujson.Obj =>
        val truncated = obj.value.get("truncated").flatMap(_.boolOpt).contains(true)
        val size      = obj.value.get("sizeBytes").flatMap(_.numOpt).map(_.toLong).getOrElse(0L)
        val suffix    = if truncated then " (truncated)" else ""
        ToolOutcome.Ok(s"${formatBytes(size)} read$suffix")
      case _ =>
        ToolOutcome.Ok(json.render().take(80))
    }

  private def formatBytes(bytes: Long): String =
    if bytes < 1024L then s"$bytes B"
    else if bytes < 1024L * 1024L then f"${bytes / 1024.0}%.1f KB"
    else f"${bytes / 1024.0 / 1024.0}%.1f MB"

  private def annotateOutcome(entries: Vector[Entry], intoIdx: Int, outcome: ToolOutcome): Vector[Entry] =
    if intoIdx < 0 || intoIdx >= entries.size then entries
    else
      val current = entries(intoIdx)
      current.toolCall match {
        case Some(summary) =>
          entries.updated(intoIdx, current.copy(toolCall = Some(summary.copy(outcome = Some(outcome)))))
        case None =>
          entries
      }

  private def decorateAssistantWithTool(
    entries: Vector[Entry],
    intoIdx: Int,
    call: ToolCall
  ): Vector[Entry] =
    if intoIdx < 0 || intoIdx >= entries.size then entries
    else
      val argsStr = call.arguments.render()
      entries.updated(intoIdx, entries(intoIdx).copy(toolCall = Some(ToolCallSummary(call.name, argsStr, None))))

  // ---- Conversation helpers ----------------------------------------------

  private def clearConversation(model: Model): Model =
    val systemEntry = Entry(Role.System, model.config.systemPrompt)
    val systemConv  = Conversation(Seq(SystemMessage(model.config.systemPrompt)))
    model.session.reset()
    model.copy(
      conversation = systemConv,
      entries = Vector(systemEntry),
      scrollOffset = 0,
      autoTail = true,
      pending = PendingState.Idle,
      status = "Conversation cleared."
    )

  private def appendSystemEntry(model: Model, text: String): Model =
    retailIfFollowing(model.copy(entries = model.entries :+ Entry(Role.System, text)))

  private def updateOrPrependSystemMessage(conv: Conversation, prompt: String): Conversation =
    conv.messages match {
      case (_: SystemMessage) +: rest =>
        Conversation(SystemMessage(prompt) +: rest)
      case rest =>
        Conversation(SystemMessage(prompt) +: rest)
    }

  private def appendToEntry(entries: Vector[Entry], idx: Int, text: String): Vector[Entry] =
    if idx < 0 || idx >= entries.size then entries
    else entries.updated(idx, entries(idx).copy(content = entries(idx).content + text))

  private def appendSuffix(entries: Vector[Entry], idx: Int, suffix: String): Vector[Entry] =
    if idx < 0 || idx >= entries.size then entries
    else entries.updated(idx, entries(idx).copy(content = entries(idx).content + suffix))

  // ---- Scrolling ---------------------------------------------------------

  private def retailIfFollowing(model: Model): Model =
    val mx = ChatTuiView.maxScroll(model)
    if model.autoTail then model.copy(scrollOffset = mx)
    else model.copy(scrollOffset = math.min(model.scrollOffset, mx))

  private def scrollBy(model: Model, delta: Int): Model =
    val mx   = ChatTuiView.maxScroll(model)
    val next = math.max(0, math.min(mx, model.scrollOffset + delta))
    model.copy(scrollOffset = next, autoTail = next >= mx)

  // ---- Help / tools listing ----------------------------------------------

  private val helpText: String =
    "Commands: /help · /tools · /clear · /quit · /model <name> · /theme [dark|light] · /system <prompt>. " +
      "Keys: ↑/↓ scroll, PgUp/PgDn page, End tail, Ctrl+L clear, Ctrl+T theme, Esc cancel/quit, Enter submit/approve."

  private def toolsText(registry: ToolRegistry): String =
    if registry.tools.isEmpty then "Tools: (none registered)"
    else
      val list = registry.tools.map(t => s"${t.name} — ${t.description}").mkString("; ")
      s"Tools: $list"
