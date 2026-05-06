package org.llm4s.samples.chat.tui

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Conversation, StreamedChunk, ToolCall }
import termflow.tui.*

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import scala.annotation.unused

/** UI-visible turn in the transcript. Decoupled from `org.llm4s.llmconnect.model.Message`. */
enum Role:
  case System, User, Assistant, Tool

  def label: String = this match
    case System    => "system"
    case User      => "you"
    case Assistant => "assistant"
    case Tool      => "tool"

/**
 * Single transcript line. `toolCall` is set on assistant entries that
 * triggered a tool round-trip so the renderer can show the inline
 * "⚙ tool: …" annotation from the spec mockup.
 */
final case class Entry(
  role: Role,
  content: String,
  toolCall: Option[ToolCallSummary] = None
)

final case class ToolCallSummary(
  name: String,
  args: String,
  outcome: Option[ToolOutcome]
)

enum ToolOutcome:
  case Ok(summary: String)
  case Err(message: String)
  case Denied

/** Pending streaming / tool state. Drives Esc behaviour and the pump's lifecycle. */
enum PendingState:
  case Idle
  case Streaming(intoIdx: Int, generation: Long)
  case AwaitingToolApproval(call: ToolCall, intoIdx: Int)
  case ExecutingTool(call: ToolCall, intoIdx: Int)

/**
 * Shared mailbox between the streaming background thread and the UI's
 * `Sub.Every` pump tick. The queue is unbounded — providers do not flood
 * fast enough to make a hard cap meaningful in practice, and dropping
 * tokens silently would corrupt the reply more than a brief stall would.
 */
final class TokenPump:
  private val queue   = new ConcurrentLinkedQueue[StreamedChunk]()
  private val stopped = new AtomicBoolean(false)

  def offer(chunk: StreamedChunk): Unit =
    if !stopped.get() then queue.offer(chunk): @unused

  /** Drain up to `maxPerFrame` chunks. Called once per pump tick. */
  def drain(maxPerFrame: Int = 64): Vector[StreamedChunk] =
    val buf = Vector.newBuilder[StreamedChunk]
    var i   = 0
    var c   = queue.poll()
    while c != null && i < maxPerFrame do
      buf += c
      i += 1
      c = if i < maxPerFrame then queue.poll() else null
    buf.result()

  /** Drain everything currently in the queue. Used on stream-complete. */
  def drainAll(): Vector[StreamedChunk] =
    val buf = Vector.newBuilder[StreamedChunk]
    var c   = queue.poll()
    while c != null do
      buf += c
      c = queue.poll()
    buf.result()

  def stop(): Unit = stopped.set(true)

object ChatTuiModel:

  /**
   * Mutable cell holding the live token pump for the current streaming
   * turn (if any) and the pump's `Sub.Every` handle so the update layer
   * can cancel it on `StreamComplete` / abort. Both fields are only
   * touched from the runtime thread, so plain vars are sufficient.
   */
  final class StreamSession:
    var pump: TokenPump  = null
    var sub: Sub[Msg]    = Sub.NoSub
    var generation: Long = 0L

    /**
     * Tool calls drained during PumpTick. The pump's queue holds raw
     * `StreamedChunk` values; once we drain them to extract text we lose
     * the `toolCall` field unless we capture it here. `handleStreamComplete`
     * inspects this list alongside the post-completion final drain so a
     * tool call emitted mid-stream isn't dropped.
     */
    var toolCalls: Vector[org.llm4s.llmconnect.model.ToolCall] = Vector.empty

    def reset(): Unit =
      pump = null
      if sub != Sub.NoSub then sub.cancel()
      sub = Sub.NoSub
      toolCalls = Vector.empty

  final case class Model(
    width: Int,
    height: Int,
    config: ChatTuiConfig,
    client: LLMClient,
    conversation: Conversation,
    entries: Vector[Entry],
    scrollOffset: Int,
    autoTail: Boolean,
    prompt: PromptHistory.State,
    pending: PendingState,
    theme: Theme,
    status: String,
    session: StreamSession
  )

  enum Msg:
    case Resize(width: Int, height: Int)
    case ConsoleInputKey(key: KeyDecoder.InputKey)
    case ConsoleInputError(error: Throwable)

    // Slash commands / submission
    case Submit(text: String)
    case AppendHelpEntry
    case AppendToolsEntry
    case ClearConversation
    case SetModel(name: String)
    case ToggleTheme
    case SetTheme(theme: Theme)
    case SetSystem(prompt: String)

    // Streaming pipeline
    case PumpTick
    case StreamComplete(generation: Long, finalChunks: Vector[StreamedChunk])
    case StreamError(generation: Long, err: TermFlowError)

    // Tool flow
    case ToolApprove
    case ToolDeny
    case ToolResult(outcome: ToolOutcome)
    case ResumeAfterTool

    // UI
    case ScrollBy(delta: Int)
    case ScrollToEnd
    case Quit
    case NoOp
