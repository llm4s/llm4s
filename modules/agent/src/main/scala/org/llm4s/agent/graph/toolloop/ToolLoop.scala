package org.llm4s.agent.graph.toolloop

import org.llm4s.agent.graph.*
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.toolapi.ToolFunction
import org.llm4s.types.{ Result, TryOps }
import upickle.default.ReadWriter

import scala.util.Try

/**
 * A tool the loop can call. Stage 0 prototype of the agent-level tool contract: (#1271) replaces
 * it with the typed, schema-validated `AgentTool[A]`. `approved` is true when a human approved this
 * call, so a tool that asks for approval must not ask again.
 */
trait LoopTool:
  def name: String
  def execute(call: ToolCall, approved: Boolean): ToolOutcome

object LoopTool:
  def apply(toolName: String)(run: (ToolCall, Boolean) => ToolOutcome): LoopTool = new LoopTool:
    val name: String                                            = toolName
    def execute(call: ToolCall, approved: Boolean): ToolOutcome = run(call, approved)

  /** A core [[org.llm4s.toolapi.ToolFunction]] as a loop tool: stateless, never asks for approval. */
  def fromToolFunction(tool: ToolFunction[?, ?]): LoopTool =
    apply(tool.name)((call, _) =>
      tool
        .execute(call.arguments)
        .fold(e => ToolOutcome.Failed(e.getFormattedMessage), json => ToolOutcome.Completed(json.render()))
    )

/** What a tool did. Tools return content, never `ToolMessage`s: the loop writes every result message. */
enum ToolOutcome:
  case Completed(content: String)
  case Failed(message: String)

  /** The call must be approved by a human before it runs; the loop suspends it. */
  case NeedsApproval(reason: String)

/** Decides each call before it runs - the policy-middleware stand-in until (#1271)'s `AgentMiddleware`. */
trait ToolCallPolicy:
  def decide(call: ToolCall): PolicyDecision

object ToolCallPolicy:
  val allowAll: ToolCallPolicy = _ => PolicyDecision.Allow

enum PolicyDecision:
  case Allow
  case Deny(reason: String)
  case RequireApproval(reason: String)

/** Who asked for an approval. Both kinds resume at the same approval node. */
enum ApprovalSource derives ReadWriter:
  case Policy, Tool

/** The question an approval interrupt asks: one tool call, from one assistant message. */
final case class ApprovalRequest(assistantMessageId: String, call: ToolCall, reason: String, source: ApprovalSource)
    derives ReadWriter

/** A reviewer's answer. `Edit` runs the call with new arguments, recording them in the assistant message first. */
enum ApprovalDecision derives ReadWriter:
  case Approve
  case Edit(arguments: ujson.Value)
  case Reject(reason: String)

/** One model-issued tool call, scheduled as its own task. */
final case class ToolTask(assistantMessageId: String, call: ToolCall) derives ReadWriter

/** The result the loop wrote for one call, waiting for the batch barrier. */
final case class ToolResult(assistantMessageId: String, toolCallId: String, content: String, isError: Boolean)
    derives ReadWriter

/** The model call: the conversation so far to the next assistant message. */
trait ModelStep:
  def next(messages: Vector[Message]): Result[AssistantMessage]

object ModelStep:
  def fromClient(client: LLMClient, options: CompletionOptions = CompletionOptions()): ModelStep =
    messages => client.complete(Conversation(messages), options).map(_.message)

/**
 * The model/tool loop as a graph - the Stage 0 proof of runtime-owned tool results and resumable
 * approval ((#1269)):
 *
 * {{{
 * input -> model --fan-out, one task per call--> call-tool --(dynamic join "tool-batch")--> collect -> model
 *                                                    \--suspend--> approval --/
 * }}}
 *
 *  - Each call is its own task: policy first, then the tool. A policy `RequireApproval` and a
 *    tool's `NeedsApproval` both suspend the call with an [[ApprovalRequest]] and resume at the one
 *    `approval` node, whose continuation fills that call's slot in the batch barrier.
 *  - The loop, not the tool, records exactly one [[ToolResult]] per call - for success, failure,
 *    denial, rejection and unknown tools alike; the results key refuses a second one.
 *  - `collect` runs only when the barrier releases, checks every call of the batch has exactly one
 *    result, and appends the `ToolMessage`s in call order. The model never sees a partial batch.
 *  - The model node re-checks the history with `Message.validateConversation` before every call.
 */
final class ToolLoop private (
  val graph: CompiledGraph[String, String],
  val approval: ResumeRef[ApprovalRequest, ApprovalDecision]
):

  /** The approval requests a suspended run is waiting on. */
  def requests(suspended: RunResult.Suspended): Result[Vector[(InterruptId, ApprovalRequest)]] =
    suspended.interrupts.foldLeft[Result[Vector[(InterruptId, ApprovalRequest)]]](Right(Vector.empty)) { (acc, i) =>
      acc.flatMap(found =>
        Try(upickle.default.read[ApprovalRequest](i.question)).toResult.map(r => found :+ (i.id -> r))
      )
    }

  /** Answers for [[GraphRuntime.resume]] or [[CompiledGraph.resume]]. */
  def answers(decisions: (InterruptId, ApprovalDecision)*): Map[InterruptId, ujson.Value] =
    decisions.map((id, decision) => id -> approval.answer(decision)).toMap

object ToolLoop:

  /** The results recorded for the current batch, at most one per call; removed when the batch is collected. */
  val results: StateKey[Vector[ToolResult], ToolResult] =
    StateKey[Vector[ToolResult], ToolResult]("tool-results", Vector.empty) { (recorded, result) =>
      if recorded.exists(r => r.assistantMessageId == result.assistantMessageId && r.toolCallId == result.toolCallId)
      then Left(ValidationError("tool-results", s"tool call '${result.toolCallId}' already has a result"))
      else Right(recorded :+ result)
    }

  def build(
    id: String,
    version: String,
    model: ModelStep,
    tools: Seq[LoopTool],
    policy: ToolCallPolicy = ToolCallPolicy.allowAll,
    maxSupersteps: Int = 200
  ): Result[ToolLoop] =
    val byName   = tools.map(t => t.name -> t).toMap
    val messages = Messages.key
    val b        = GraphBuilder(id, version)

    val modelNode = b.declare[Unit]("model")
    val collect   = b.declare[Unit]("collect")
    val callTool  = b.declare[ToolTask]("call-tool")
    val approval  = b.declareResume[ApprovalRequest, ApprovalDecision]("approval")
    val batch     = b.dynamicJoin("tool-batch", collect)

    def record(task: ToolTask, content: String, isError: Boolean): NodeResult =
      NodeResult.Continue(
        Command.empty.update(results, ToolResult(task.assistantMessageId, task.call.id, content, isError))
      )

    def suspend(task: ToolTask, call: ToolCall, reason: String, source: ApprovalSource): NodeResult =
      NodeResult.Suspend(StateUpdate.empty, ApprovalRequest(task.assistantMessageId, call, reason, source), approval)

    def execute(task: ToolTask, call: ToolCall, approved: Boolean): NodeResult =
      byName.get(call.name) match
        case None => record(task, s"Unknown tool '${call.name}'", isError = true)
        case Some(tool) =>
          Try(tool.execute(call, approved)).toResult match
            case Left(thrown) => record(task, s"Tool '${call.name}' failed: ${thrown.message}", isError = true)
            case Right(ToolOutcome.Completed(content)) => record(task, content, isError = false)
            case Right(ToolOutcome.Failed(message))    => record(task, message, isError = true)
            case Right(ToolOutcome.NeedsApproval(reason)) =>
              if approved then record(task, s"Tool '${call.name}' asked for approval again: $reason", isError = true)
              else suspend(task, call, reason, ApprovalSource.Tool)

    b.implement(callTool, writes = Set(results)) { (task, _, _) =>
      policy.decide(task.call) match
        case PolicyDecision.Deny(reason)            => record(task, s"Denied: $reason", isError = true)
        case PolicyDecision.RequireApproval(reason) => suspend(task, task.call, reason, ApprovalSource.Policy)
        case PolicyDecision.Allow                   => execute(task, task.call, approved = false)
    }

    b.implement(approval.node, writes = Set(results, messages)) { (resumed, _, _) =>
      val request = resumed.question
      val task    = ToolTask(request.assistantMessageId, request.call)
      resumed.answer match
        case ApprovalDecision.Approve        => execute(task, request.call, approved = true)
        case ApprovalDecision.Reject(reason) => record(task, s"Rejected: $reason", isError = true)
        case ApprovalDecision.Edit(arguments) =>
          val edited = request.call.copy(arguments = arguments)
          val edit = StateUpdate.update(
            messages,
            MessageUpdate.EditToolCall(request.assistantMessageId, request.call.id, arguments)
          )
          // the reviewer approved these arguments; the policy can still deny them outright
          val outcome = policy.decide(edited) match
            case PolicyDecision.Deny(reason) => record(task, s"Denied: $reason", isError = true)
            case _                           => execute(task, edited, approved = true)
          outcome match
            case NodeResult.Continue(command) =>
              NodeResult.Continue(command.copy(update = edit.combine(command.update)))
            case NodeResult.Suspend(update, q, resume) => NodeResult.Suspend(edit.combine(update), q, resume)
            case failed                                => failed
    }

    b.implement(modelNode, writes = Set(messages)) { (_, state, context) =>
      NodeResult.fromResult(for
        history   <- state.get(messages)
        _         <- Message.validateConversation(history.map(_.message).toList)
        assistant <- model.next(history.map(_.message))
      yield
        val stored   = StoredMessage(s"${context.taskId.value}/assistant", assistant)
        val appended = Command.empty.update(messages, MessageUpdate.Append(stored))
        if assistant.toolCalls.isEmpty then appended
        else appended.fanOut(batch, callTool, assistant.toolCalls.toVector.map(ToolTask(stored.id, _)))
      )
    }

    b.implement(collect, writes = Set(messages, results)) { (_, state, context) =>
      NodeResult.fromResult(for
        history  <- state.get(messages)
        recorded <- state.get(results)
        source <- history.reverseIterator
          .collectFirst { case StoredMessage(id, a: AssistantMessage) if a.toolCalls.nonEmpty => id -> a }
          .toRight(ValidationError("tool-batch", "no assistant message with tool calls"))
        (sourceId, assistant) = source
        ordered <- assistant.toolCalls.toVector.foldLeft[Result[Vector[ToolResult]]](Right(Vector.empty)) {
          (acc, call) =>
            acc.flatMap { found =>
              recorded.filter(r => r.assistantMessageId == sourceId && r.toolCallId == call.id) match
                case Vector(one) => Right(found :+ one)
                case none if none.isEmpty =>
                  Left(ValidationError("tool-batch", s"tool call '${call.id}' has no result"))
                case _ => Left(ValidationError("tool-batch", s"tool call '${call.id}' has more than one result"))
            }
        }
        _ <- Either.cond(
          recorded.size == ordered.size,
          (),
          ValidationError("tool-batch", "results recorded for calls outside the batch")
        )
      yield
        val toolMessages = ordered.map { r =>
          val content = if r.isError then ujson.Obj("error" -> r.content).render() else r.content
          StoredMessage(s"${context.taskId.value}/tool/${r.toolCallId}", ToolMessage(content, r.toolCallId))
        }
        toolMessages
          .foldLeft(Command.empty)((command, m) => command.update(messages, MessageUpdate.Append(m)))
          .remove(results)
          .goto(modelNode)
      )
    }

    val input = b.node[String]("input", writes = Set(messages)) { (text, _, context) =>
      NodeResult.Continue(
        Command.empty
          .update(messages, MessageUpdate.Append(StoredMessage(s"${context.taskId.value}/user", UserMessage(text))))
          .goto(modelNode)
      )
    }

    b.compile(input, maxSupersteps) { state =>
      state.get(messages).flatMap { history =>
        history.lastOption.map(_.message) match
          case Some(answer: AssistantMessage) if answer.toolCalls.isEmpty => Right(answer.content)
          case _ => Left(ValidationError("tool-loop", "the run ended without a final assistant message"))
      }
    }.map(new ToolLoop(_, approval))
