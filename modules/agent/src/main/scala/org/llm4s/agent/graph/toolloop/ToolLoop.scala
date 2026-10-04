package org.llm4s.agent.graph.toolloop

import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.tool.{ AgentTool, ToolContext, ToolOutcome, ToolQuestion, ToolSet }
import org.llm4s.error.{ LLMError, ValidationError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.types.{ Result, TryOps }
import upickle.default.ReadWriter

import scala.util.Try

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

/**
 * The question a tool's `Ask` interrupt carries: the call that asked, from one assistant message,
 * the question encoded with the tool's declared question codec, and whether the call was approved
 * before it asked - `resume` sees the same `ToolContext.approved`. Read it with
 * [[ToolLoop.questions]] and answer it with [[ToolLoop.answer]].
 */
final case class ToolQuestionRequest(
  assistantMessageId: String,
  call: ToolCall,
  question: ujson.Value,
  approved: Boolean = false
) derives ReadWriter

/** The result the loop wrote for one call, waiting for the batch barrier. */
final case class ToolResult(assistantMessageId: String, toolCallId: String, content: String, isError: Boolean)
    derives ReadWriter

/** The model call: the conversation so far, and the tools on offer, to the next assistant message. */
trait ModelStep:
  def next(messages: Vector[Message], tools: ToolSet): Result[AssistantMessage]

object ModelStep:

  /** Calls `client` with `options`, its `tools` replaced by the loop's [[ToolSet.toolFunctions]]. */
  def fromClient(client: LLMClient, options: CompletionOptions = CompletionOptions()): ModelStep =
    (messages, tools) => client.complete(Conversation(messages), options.withTools(tools.toolFunctions)).map(_.message)

/**
 * The model/tool loop as a graph - the Stage 0 proof of runtime-owned tool results and resumable
 * approval ((#1269)), running [[org.llm4s.agent.graph.tool.AgentTool]]s ((#1278)):
 *
 * {{{
 * input -> model --fan-out, one task per call--> call-tool --(dynamic join "tool-batch")--> collect -> model
 *                                                    \--suspend--> approval  --/
 *                                                    \--suspend--> ask/<tool> --/
 * }}}
 *
 *  - Each call is its own task. Its tool is looked up, its raw arguments validated against the
 *    tool's provider schema, decoded and checked by the tool's `validateDecoded` - any failure is an
 *    error result the model sees, before the policy or the tool runs. Then the policy, then the tool.
 *  - A policy `RequireApproval` and a tool's `NeedsApproval` both suspend the call with an
 *    [[ApprovalRequest]] and resume at the one `approval` node; an edited approval's arguments are
 *    checked again. A tool's `Ask` suspends with a [[ToolQuestionRequest]] at that tool's
 *    `ask/<name>` node, which calls the tool's `resume` with the decoded answer.
 *  - The loop, not the tool, records exactly one [[ToolResult]] per call - for success, failure,
 *    denial, rejection and unknown tools alike; the results key refuses a second one. A thrown
 *    exception is that call's error result. `Fatal`, an update to a key the tool does not declare,
 *    and a question it does not declare fail the run with `GraphError.ToolFailed`, leaving the
 *    checkpoint `Running` so `recover` re-runs only that call.
 *  - `collect` runs only when the barrier releases, checks every call of the batch has exactly one
 *    result, and appends the `ToolMessage`s in call order. The model never sees a partial batch.
 *  - The model node re-checks the history with `Message.validateConversation` before every call.
 */
final class ToolLoop private (
  val graph: CompiledGraph[String, String],
  val approval: ResumeRef[ApprovalRequest, ApprovalDecision],
  askNodes: Set[NodeId]
):

  /** The approval requests a suspended run is waiting on. */
  def requests(suspended: RunResult.Suspended): Result[Vector[(InterruptId, ApprovalRequest)]] =
    pending[ApprovalRequest](suspended, _ == approval.node.id)

  /** The tool questions a suspended run is waiting on; decode each `question` with the asking tool's type. */
  def questions(suspended: RunResult.Suspended): Result[Vector[(InterruptId, ToolQuestionRequest)]] =
    pending[ToolQuestionRequest](suspended, askNodes.contains)

  /** Answers for [[GraphRuntime.resume]] or [[CompiledGraph.resume]]. */
  def answers(decisions: (InterruptId, ApprovalDecision)*): Map[InterruptId, ujson.Value] =
    decisions.map((id, decision) => id -> approval.answer(decision)).toMap

  /**
   * One question's answer, of the asking tool's answer type, to merge with [[answers]] into one
   * resume map. An answer that does not decode as the tool's type becomes that call's error result.
   */
  def answer[Ans: ReadWriter](id: InterruptId, answer: Ans): (InterruptId, ujson.Value) =
    id -> upickle.default.writeJs(answer)

  private def pending[Q: ReadWriter](
    suspended: RunResult.Suspended,
    at: NodeId => Boolean
  ): Result[Vector[(InterruptId, Q)]] =
    suspended.interrupts
      .filter(i => at(i.resumeNode))
      .foldLeft[Result[Vector[(InterruptId, Q)]]](Right(Vector.empty)) { (acc, i) =>
        acc.flatMap(found => Try(upickle.default.read[Q](i.question)).toResult.map(r => found :+ (i.id -> r)))
      }

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
    tools: ToolSet,
    policy: ToolCallPolicy = ToolCallPolicy.allowAll
  ): Result[ToolLoop] =
    val messages = Messages.key
    val b        = GraphBuilder(id, version)
    // the call-tool, approval and ask nodes may write any tool's keys; each call is checked against its own tool's
    val callWrites: Set[StateKey[?, ?]] = tools.tools.flatMap(_.writes).toSet ++ Set(results, messages)

    val modelNode = b.declare[Unit]("model")
    val collect   = b.declare[Unit]("collect")
    val callTool  = b.declare[ToolTask]("call-tool")
    val approval  = b.declareResume[ApprovalRequest, ApprovalDecision]("approval")
    val batch     = b.dynamicJoin("tool-batch", collect)
    val asking    = tools.tools.filter(_.spec.question.isDefined)
    val askRefs: Map[String, ResumeRef[ToolQuestionRequest, ujson.Value]] =
      asking.map(t => t.spec.name -> b.declareResume[ToolQuestionRequest, ujson.Value](s"ask/${t.spec.name}")).toMap

    val pipeline = Pipeline(tools, policy, approval, askRefs)

    b.implement(callTool, writes = callWrites) { (task, state, context) =>
      tools.get(task.call.name) match
        case None       => pipeline.error(task, s"Unknown tool '${task.call.name}'")
        case Some(tool) => pipeline.admit(tool, task, state, context)
    }

    b.implement(approval.node, writes = callWrites) { (resumed, state, context) =>
      val request = resumed.question
      val task    = ToolTask(request.assistantMessageId, request.call)
      resumed.answer match
        case ApprovalDecision.Approve        => pipeline.approved(task, request.call, state, context)
        case ApprovalDecision.Reject(reason) => pipeline.error(task, s"Rejected: $reason")
        case ApprovalDecision.Edit(arguments) =>
          val edit = StateUpdate.update(
            messages,
            MessageUpdate.EditToolCall(request.assistantMessageId, request.call.id, arguments)
          )
          pipeline.approved(task, request.call.copy(arguments = arguments), state, context, edited = true) match
            case NodeResult.Continue(command) =>
              NodeResult.Continue(command.copy(update = edit.combine(command.update)))
            case NodeResult.Suspend(update, q, resume) => NodeResult.Suspend(edit.combine(update), q, resume)
            case failed                                => failed
    }

    asking.foreach { tool =>
      b.implement(askRefs(tool.spec.name).node, writes = callWrites) { (resumed, state, context) =>
        pipeline.answered(tool, resumed.question, resumed.answer, state, context)
      }
    }

    b.implement(modelNode, writes = Set(messages)) { (_, state, context) =>
      NodeResult.fromResult(for
        history   <- state.get(messages)
        _         <- Message.validateConversation(history.map(_.message).toList)
        assistant <- model.next(history.map(_.message), tools)
      yield
        val stored   = StoredMessage(s"${context.position.taskId.value}/assistant", assistant)
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
          StoredMessage(s"${context.position.taskId.value}/tool/${r.toolCallId}", ToolMessage(content, r.toolCallId))
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
          .update(
            messages,
            MessageUpdate.Append(StoredMessage(s"${context.position.taskId.value}/user", UserMessage(text)))
          )
          .goto(modelNode)
      )
    }

    b.compile(input) { state =>
      state.get(messages).flatMap { history =>
        history.lastOption.map(_.message) match
          case Some(answer: AssistantMessage) if answer.toolCalls.isEmpty => Right(answer.content)
          case _ => Left(ValidationError("tool-loop", "the run ended without a final assistant message"))
      }
    }.map(new ToolLoop(_, approval, askRefs.values.map(_.node.id).toSet))

  /** The call pipeline (design #1278, "The call pipeline"): one call's checks, policy, tool and outcome. */
  final private class Pipeline(
    tools: ToolSet,
    policy: ToolCallPolicy,
    approval: ResumeRef[ApprovalRequest, ApprovalDecision],
    askRefs: Map[String, ResumeRef[ToolQuestionRequest, ujson.Value]]
  ):

    def error(task: ToolTask, message: String): NodeResult = record(task, message, isError = true)

    private def record(task: ToolTask, content: String, isError: Boolean): NodeResult =
      NodeResult.Continue(
        Command.empty.update(results, ToolResult(task.assistantMessageId, task.call.id, content, isError))
      )

    private def suspend(task: ToolTask, call: ToolCall, reason: String, source: ApprovalSource): NodeResult =
      NodeResult.Suspend(StateUpdate.empty, ApprovalRequest(task.assistantMessageId, call, reason, source), approval)

    /** A new call: steps 2-5, then the tool. */
    def admit[A](tool: AgentTool[A], task: ToolTask, state: ThreadState, context: RunContext): NodeResult =
      checked(tool, task.call) match
        case Left(message) => error(task, message)
        case Right(args) =>
          policy.decide(task.call) match
            case PolicyDecision.Deny(reason)            => error(task, s"Denied: $reason")
            case PolicyDecision.RequireApproval(reason) => suspend(task, task.call, reason, ApprovalSource.Policy)
            case PolicyDecision.Allow                   => execute(tool, task, task.call, args, state, context)

    /**
     * An approved call, as approved or with edited arguments: checked again, and an edit offered to
     * the policy, which can still deny it outright; the reviewer's approval stands for the rest.
     */
    def approved(
      task: ToolTask,
      call: ToolCall,
      state: ThreadState,
      context: RunContext,
      edited: Boolean = false
    ): NodeResult =
      tools.get(call.name) match
        case None => error(task, s"Unknown tool '${call.name}'")
        case Some(tool) =>
          checked(tool, call) match
            case Left(message) => error(task, message)
            case Right(args) =>
              val denied = if edited then policy.decide(call) else PolicyDecision.Allow
              denied match
                case PolicyDecision.Deny(reason) => error(task, s"Denied: $reason")
                case _                           => execute(tool, task, call, args, state, context, approved = true)

    /** An answered question: decodes the stored call, question and answer, and resumes the tool. */
    def answered[A](
      tool: AgentTool[A],
      request: ToolQuestionRequest,
      answer: ujson.Value,
      state: ThreadState,
      context: RunContext
    ): NodeResult =
      val task = ToolTask(request.assistantMessageId, request.call)
      val name = tool.spec.name
      tool.spec.question match
        case Some(declared: ToolQuestion[q, ans]) =>
          val decoded = for
            args <- decode(tool, request.call).left.map(m => s"Invalid arguments for '$name': $m")
            question <- read(request.question)(using declared.questionCodec).left
              .map(m => s"Invalid question for '$name': $m")
            reply <- read(answer)(using declared.answerCodec).left.map(m => s"Invalid answer for '$name': $m")
          yield (args, question, reply)
          decoded match
            case Left(message) => error(task, message)
            case Right((args, question, reply)) =>
              val toolContext = ToolContext(context, request.call.id, state, request.approved)
              outcome(tool, task, request.call, request.approved)(
                AgentTool.resumeWith(tool, args, question, reply, toolContext)
              )
        case _ => error(task, s"Tool '$name' does not take answers")

    /** Steps 2-4: validate the raw arguments, decode them, run the tool's own check. */
    private def checked[A](tool: AgentTool[A], call: ToolCall): Either[String, A] =
      val invalid = s"Invalid arguments for '${tool.spec.name}': "
      tools.validator.validate(tool.spec.providerSchema, call.arguments) match
        case Vector() =>
          for
            args <- decode(tool, call).left.map(invalid + _)
            _    <- Try(tool.spec.validateDecoded(args)).toResult.flatten.left.map(e => invalid + e.message)
          yield args
        case violations => Left(invalid + violations.mkString("; "))

    private def decode[A](tool: AgentTool[A], call: ToolCall): Either[String, A] =
      read(call.arguments)(using tool.spec.codec)

    /** Decodes `json`, describing a failure by the JSON path it happened at and its cause. */
    private def read[T: ReadWriter](json: ujson.Value): Either[String, T] =
      Try(upickle.default.read[T](json)).toEither.left.map {
        case traced: upickle.core.TraceVisitor.TraceException =>
          s"${traced.jsonPath}: ${Option(traced.getCause).fold("does not decode")(describe)}"
        case other => describe(other)
      }

    private def describe(thrown: Throwable): String = Option(thrown.getMessage).getOrElse(thrown.toString)

    private def execute[A](
      tool: AgentTool[A],
      task: ToolTask,
      call: ToolCall,
      args: A,
      state: ThreadState,
      context: RunContext,
      approved: Boolean = false
    ): NodeResult =
      val toolContext = ToolContext(context, call.id, state, approved)
      outcome(tool, task, call, approved)(tool.execute(args, toolContext))

    /**
     * Step 6: maps what the tool did. A thrown NonFatal exception is an error result; an
     * `InterruptedException` is not caught, so the task is cancelled and records nothing.
     */
    private def outcome[A](tool: AgentTool[A], task: ToolTask, call: ToolCall, approved: Boolean)(
      run: => ToolOutcome
    ): NodeResult =
      val name                                 = tool.spec.name
      def failRun(error: LLMError): NodeResult = NodeResult.Fail(GraphError.ToolFailed(name, call.id, error))
      Try(run).toResult match
        case Left(thrown) => error(task, s"Tool '$name' failed: ${thrown.message}")
        case Right(ToolOutcome.Success(content, update)) =>
          val undeclared =
            update.operations.map(_.key.id).filterNot(k => tool.writes.exists(_.id == k)).map(_.value).distinct
          if undeclared.isEmpty then
            NodeResult.Continue(
              Command(update, Nil)
                .update(results, ToolResult(task.assistantMessageId, call.id, rendered(content), isError = false))
            )
          else
            failRun(
              ValidationError(
                "tool update",
                s"tool '$name' updated ${undeclared.map(k => s"'$k'").mkString(", ")}, which it does not declare"
              )
            )
        case Right(ToolOutcome.Error(message)) => error(task, message)
        case Right(ToolOutcome.NeedsApproval(reason)) =>
          if approved then error(task, s"Tool '$name' asked for approval again: $reason")
          else suspend(task, call, reason, ApprovalSource.Tool)
        case Right(ToolOutcome.Ask(question)) =>
          (tool.spec.question, askRefs.get(name)) match
            case (Some(declared: ToolQuestion[q, ?]), Some(ref)) =>
              // a question of another type than the declared one fails to encode: a tool bug, like an undeclared one
              Try(upickle.default.writeJs(question.asInstanceOf[q])(using declared.questionCodec)).toResult match
                case Right(json) =>
                  NodeResult.Suspend(
                    StateUpdate.empty,
                    ToolQuestionRequest(task.assistantMessageId, call, json, approved),
                    ref
                  )
                case Left(e) =>
                  failRun(
                    ValidationError("tool question", s"tool '$name' asked a question of another type: ${e.message}")
                  )
            case _ => failRun(ValidationError("tool question", s"tool '$name' asked a question it does not declare"))
        case Right(ToolOutcome.Fatal(error)) => failRun(error)

    /** A string result is the string itself, not a quoted JSON string. */
    private def rendered(content: ujson.Value): String = content match
      case ujson.Str(s) => s
      case other        => other.render()
