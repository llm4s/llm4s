package org.llm4s.agent

import org.llm4s.llmconnect.model.*
import org.llm4s.trace.TraceEvent
import org.llm4s.types.{ Result, TryOps }
import upickle.default.*

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Paths }
import scala.util.Try

/**
 * One interrupt a suspended turn is parked on, as data: the interrupt's id, the node it resumes at, and its question
 * as JSON. [[AgentThread.approvals]] reads the approval requests among them.
 */
final case class SuspendedOn(interruptId: String, node: String, question: ujson.Value) derives ReadWriter

/**
 * Where a turn ended, as data.
 *
 *  - `Completed`: the model gave a final answer.
 *  - `Failed(error)`: the turn ended without one - `"Maximum step limit reached"` when `maxSteps` ran out. An error
 *    from the model, a guardrail or a handoff target is a `Left` from the `Agent` call, not a status.
 *  - `Suspended(on)`: the turn is parked on approvals or tool questions; answer them with `Agent.resume`.
 */
enum ThreadStatus derives ReadWriter:
  case Completed
  case Failed(error: String)
  case Suspended(on: Vector[SuspendedOn])

/**
 * A conversation with an agent, as data: its messages, the system message and completion options it runs with, the
 * usage it has accumulated, and where its last turn ended. It holds no tools, handoffs, guardrails or clients - those
 * are live and are supplied again on every [[Agent]] call - so a thread serializes ([[AgentThread.toJson]]), moves
 * between processes, and continues in any `Agent`: [[Agent#continueConversation]] seeds a runtime thread from it.
 *
 * It replaces `AgentState`. The tools, the handoffs, the execution log and the initial query are not part of it (the
 * run events carry what the log did; the first user message is `messages.head`).
 *
 * @param threadId the id of the runtime thread the last turn ran on; [[Agent#recover]] and [[Agent#resume]] act on it
 * @param messages the conversation, without the system message
 * @param systemMessage the system message each model call is given, injected at call time and never stored in `messages`
 * @param completionOptions the model parameters each call uses; its `tools` are not part of the thread
 * @param usage the token usage and cost of every model call in the conversation so far
 * @param status where the last turn ended
 */
final case class AgentThread private (
  threadId: String,
  messages: Vector[Message],
  systemMessage: Option[SystemMessage],
  completionOptions: CompletionOptions,
  usage: UsageSummary,
  status: ThreadStatus
) {

  /** The conversation, without the system message. */
  def conversation: Conversation = Conversation(messages)

  /** The conversation as a model call sees it: the system message first, then the messages. */
  def toApiConversation: Conversation = Conversation(systemMessage.toSeq ++ messages)

  /** The final answer of a `Completed` thread: the last assistant message's content. */
  def answer: Option[String] = status match {
    case ThreadStatus.Completed =>
      messages.reverseIterator.collectFirst { case a: AssistantMessage if a.toolCalls.isEmpty => a.content }
    case _ => None
  }

  /** Whether the last turn ended, so the conversation can continue: `Completed` or `Failed`. */
  def isFinished: Boolean = status match {
    case ThreadStatus.Suspended(_) => false
    case _                         => true
  }

  /** The approval requests a `Suspended` thread is parked on, with the interrupt each answers. */
  def approvals: Result[Vector[(String, org.llm4s.agent.graph.toolloop.ApprovalRequest)]] = status match {
    case ThreadStatus.Suspended(on) =>
      on.filter(_.node == "approval")
        .foldLeft[Result[Vector[(String, org.llm4s.agent.graph.toolloop.ApprovalRequest)]]](Right(Vector.empty)) {
          (found, one) =>
            found.flatMap(done =>
              Try(read[org.llm4s.agent.graph.toolloop.ApprovalRequest](one.question)).toResult
                .map(request => done :+ (one.interruptId -> request))
            )
        }
    case _ => Right(Vector.empty)
  }

  def withThreadId(value: String): AgentThread                     = copy(threadId = value)
  def withMessages(value: Seq[Message]): AgentThread               = copy(messages = value.toVector)
  def withSystemMessage(value: Option[SystemMessage]): AgentThread = copy(systemMessage = value)
  def withSystemMessage(value: SystemMessage): AgentThread         = copy(systemMessage = Some(value))
  def withCompletionOptions(value: CompletionOptions): AgentThread = copy(completionOptions = value)
  def withUsage(value: UsageSummary): AgentThread                  = copy(usage = value)
  def withStatus(value: ThreadStatus): AgentThread                 = copy(status = value)

  /** This thread with its conversation pruned by `config`; a pure function, the input is not changed. */
  def pruned(
    config: ContextWindowConfig,
    tokenCounter: Message => Int = ConversationPruning.defaultTokenCounter
  ): AgentThread = {
    val kept = ConversationPruning.prune(messages, config, tokenCounter)
    if (kept eq messages) this else copy(messages = kept.toVector)
  }

  /**
   * This thread as a trace event, for `tracing.traceEvent(thread.toTraceEvent)`: the status as a string, the message
   * count and the messages. `logCount` is 0: a thread has no log.
   */
  def toTraceEvent: TraceEvent.AgentStateUpdated =
    TraceEvent.AgentStateUpdated(
      status = status.toString,
      messageCount = messages.length,
      logCount = 0,
      messages = messages
    )

  /** Prints a detailed dump of the thread for debugging. */
  def dump(): Unit = {
    val separator = "=" * 80

    println(separator)
    println(s"AGENT THREAD DUMP - Status: $status")
    println(separator)
    println("CONVERSATION FLOW:")
    println(separator)

    messages.zipWithIndex.foreach { case (message, index) =>
      val roleMarker = message.role match {
        case MessageRole.User      => "👤 USER"
        case MessageRole.Assistant => "🤖 ASSISTANT"
        case MessageRole.System    => "⚙️ SYSTEM"
        case MessageRole.Tool      => "🛠️ TOOL"
      }

      println(s"STEP ${index + 1}: $roleMarker")

      message match {
        case msg: AssistantMessage if msg.toolCalls.nonEmpty =>
          println(s"Content: ${msg.content}")
          println("Tool Calls:")
          msg.toolCalls.foreach { tc =>
            println(s"  - ID: ${tc.id}")
            println(s"    Tool: ${tc.name}")
            println(s"    Args: ${tc.arguments}")
          }
        case msg: ToolMessage =>
          println(s"Tool Call ID: ${msg.toolCallId}")
          println(s"Result: ${msg.content}")
        case msg =>
          println(s"Content: ${msg.content}")
      }

      println(separator)
    }

    println(s"END OF AGENT THREAD DUMP - Status: $status")
    println(separator)
  }
}

object AgentThread {

  /**
   * A thread over `messages`. Everything but the id has a default: no system message, default completion options,
   * no usage, and `Completed` - the status of a conversation that can continue.
   */
  def apply(
    threadId: String,
    messages: Seq[Message] = Vector.empty,
    systemMessage: Option[SystemMessage] = None,
    completionOptions: CompletionOptions = CompletionOptions(),
    usage: UsageSummary = UsageSummary(),
    status: ThreadStatus = ThreadStatus.Completed
  ): AgentThread =
    new AgentThread(threadId, messages.toVector, systemMessage, completionOptions, usage, status)

  /** The thread as JSON. Tools, handoffs and the completion options' tools are not serialized: they are live. */
  def toJson(thread: AgentThread): ujson.Value =
    ujson.Obj(
      "threadId"          -> thread.threadId,
      "conversation"      -> writeJs(thread.conversation),
      "status"            -> writeJs(thread.status),
      "systemMessage"     -> thread.systemMessage.map(msg => ujson.Str(msg.content)).getOrElse(ujson.Null),
      "completionOptions" -> serializeCompletionOptions(thread.completionOptions),
      "usageSummary"      -> writeJs(thread.usage)
    )

  private def serializeCompletionOptions(opts: CompletionOptions): ujson.Value =
    ujson.Obj(
      "temperature"      -> ujson.Num(opts.temperature),
      "topP"             -> ujson.Num(opts.topP),
      "maxTokens"        -> opts.maxTokens.map(ujson.Num(_)).getOrElse(ujson.Null),
      "presencePenalty"  -> ujson.Num(opts.presencePenalty),
      "frequencyPenalty" -> ujson.Num(opts.frequencyPenalty),
      "reasoning"        -> opts.reasoning.map(r => writeJs(r)).getOrElse(ujson.Null),
      "budgetTokens"     -> opts.budgetTokens.map(ujson.Num(_)).getOrElse(ujson.Null)
    )

  private def deserializeCompletionOptions(json: ujson.Value): CompletionOptions =
    CompletionOptions(
      temperature = json("temperature").num,
      topP = json("topP").num,
      maxTokens = json("maxTokens") match {
        case ujson.Num(n) => Some(n.toInt)
        case _            => None
      },
      presencePenalty = json("presencePenalty").num,
      frequencyPenalty = json("frequencyPenalty").num,
      reasoning = json.obj.get("reasoning").flatMap {
        case ujson.Null => None
        case v          => Some(read[ReasoningEffort](v))
      },
      budgetTokens = json.obj.get("budgetTokens").flatMap {
        case ujson.Num(n) => Some(n.toInt)
        case _            => None
      }
    )

  /** The thread from its JSON; the inverse of [[toJson]]. */
  def fromJson(json: ujson.Value): Result[AgentThread] =
    Try {
      new AgentThread(
        threadId = json("threadId").str,
        messages = read[Conversation](json("conversation")).messages.toVector,
        systemMessage = json("systemMessage") match {
          case ujson.Str(content) => Some(SystemMessage(content))
          case _                  => None
        },
        completionOptions = deserializeCompletionOptions(json("completionOptions")),
        usage = json.obj.get("usageSummary") match {
          case Some(v) => read[UsageSummary](v)
          case None    => UsageSummary()
        },
        status = read[ThreadStatus](json("status"))
      )
    }.toResult

  /** Writes the thread as indented JSON to `path`; a filesystem error is a `Left`. */
  def saveToFile(thread: AgentThread, path: String): Result[Unit] =
    Try {
      Files.write(Paths.get(path), write(toJson(thread), indent = 2).getBytes(StandardCharsets.UTF_8))
      ()
    }.toResult

  /** Reads a thread from `path`; a filesystem or parse error is a `Left`. */
  def loadFromFile(path: String): Result[AgentThread] =
    for {
      text   <- Try(new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8)).toResult
      json   <- Try(ujson.read(text)).toResult
      thread <- fromJson(json)
    } yield thread
}
