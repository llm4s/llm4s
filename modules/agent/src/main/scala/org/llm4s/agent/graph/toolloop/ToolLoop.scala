package org.llm4s.agent.graph.toolloop

import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.middleware.{
  AgentMiddleware,
  MiddlewareId,
  MiddlewareStack,
  ModelRequest,
  ToolCallRequest
}
import org.llm4s.agent.graph.tool.{ AgentTool, ToolContext, ToolOutcome, ToolQuestion, ToolSet }
import org.llm4s.error.{ CancelledError, LLMError, ValidationError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.types.{ Result, TryOps }
import upickle.default.ReadWriter

import java.util.concurrent.atomic.AtomicReference
import scala.util.{ Failure, Try }

/** Who asked for an approval: the tool itself, or a middleware's tool wrapper. All resume at the same approval node. */
enum ApprovalSource derives ReadWriter:
  case Tool
  case Middleware(id: MiddlewareId)

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
 * before it asked - `resume` sees the same `ToolContext.approved`. Find it with
 * [[ToolLoop.questions]], read its question as the tool's type with [[ToolLoop.question]], and
 * answer it with [[ToolLoop.answer]].
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

  /**
   * Calls `client` with `options`, its `tools` replaced by the loop's
   * [[org.llm4s.agent.graph.tool.ToolSet.toolFunctions]].
   */
  def fromClient(client: LLMClient, options: CompletionOptions = CompletionOptions()): ModelStep =
    (messages, tools) => client.complete(Conversation(messages), options.withTools(tools.toolFunctions)).map(_.message)

/**
 * The model/tool loop as a graph - the Stage 0 proof of runtime-owned tool results and resumable
 * approval ((#1269)), running [[org.llm4s.agent.graph.tool.AgentTool]]s ((#1278)):
 *
 * {{{
 * input -> model --fan-out, one task per call--> call-tool --(dynamic join "tool-batch")--> collect -> model
 *            \                                       \--suspend--> approval  --/
 *             \--final answer--> finish               \--suspend--> ask/<tool> --/
 * }}}
 *
 *  - The middleware stack (#1279) runs at the run's boundaries and around each call: `input` runs
 *    every `beforeAgent` on the user's text, `model` runs `ModelStep.next` inside every
 *    `wrapModelCall`, and `finish` runs every `afterAgent` (in reverse) on the final answer,
 *    replacing the stored answer's content when it changes; a blank answer or a `Left` fails the
 *    run. A middleware's `tools` join the loop's [[org.llm4s.agent.graph.tool.ToolSet]], and its
 *    `writes` are keys its `wrapToolCall` may add to a `Success` update.
 *  - Each call is its own task. Its tool is looked up, its raw arguments validated against the
 *    tool's `argumentSchema`, decoded and checked by the tool's `validateDecoded` - any failure is an
 *    error result the model sees, before any middleware or the tool runs. Then the middleware
 *    stack's `wrapToolCall` chain, the first middleware outermost, with the tool at its centre.
 *  - A `NeedsApproval`, from a wrapper or the tool, suspends the call with an [[ApprovalRequest]]
 *    naming its [[ApprovalSource]], and resumes at the one `approval` node. `Approve` and `Edit`
 *    check the arguments again (an edit's are new) and run the whole chain again with
 *    `ToolContext.approved`, so a deny rule still refuses an edit; a second `NeedsApproval` from an
 *    approved call is an error result. A tool's `Ask` suspends with a [[ToolQuestionRequest]] at
 *    that tool's `ask/<name>` node, which runs the tool's `resume` with the decoded answer inside the
 *    same chain, with the approval the call asked with.
 *  - The loop, not the tool, records exactly one [[ToolResult]] per call - for success, failure,
 *    denial, rejection and unknown tools alike, however often a wrapper ran the tool; the results key
 *    refuses a second one. A tool's thrown exception is that call's error result. `Fatal`, an update
 *    to a key neither the tool nor any middleware declares, and a question it does not declare fail the run with
 *    `GraphError.ToolFailed`; a throwing wrapper fails it with `GraphError.MiddlewareFailed`. Either
 *    leaves the checkpoint `Running`, so `recover` re-runs only that call.
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

  /** The tool questions a suspended run is waiting on; read each one's question with [[ToolLoop.question]]. */
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

  /** A pending question, decoded as the asking tool's question type `Q`. */
  def question[Q: ReadWriter](request: ToolQuestionRequest): Result[Q] =
    Try(upickle.default.read[Q](request.question)).toResult

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
    middleware: Seq[AgentMiddleware] = Nil
  ): Result[ToolLoop] =
    for
      stack <- MiddlewareStack.of(middleware*)
      // a middleware's tools join the user's, checked as one set: a name clash or invalid schema is refused
      toolSet <- ToolSet.of(tools.validator, (tools.tools ++ stack.tools)*)
      _       <- ownedKeysUntouched(toolSet, stack)
      loop    <- assemble(id, version, model, toolSet, stack)
    yield loop

  /** The loop alone writes results and messages: a tool or middleware writing either could break one result per call. */
  private def ownedKeysUntouched(tools: ToolSet, stack: MiddlewareStack): Result[Unit] =
    val owned                              = Set(results.id, Messages.key.id)
    def touches(keys: Set[StateKey[?, ?]]) = keys.exists(k => owned.contains(k.id))
    val clause                             = "declares a key the loop owns ('tool-results' or 'messages')"
    val byTools      = tools.tools.filter(t => touches(t.writes)).map(t => s"tool '${t.spec.name}' $clause")
    val byMiddleware = stack.ordered.filter(m => touches(m.writes)).map(m => s"middleware '${m.id.value}' $clause")
    (byTools ++ byMiddleware).toList match
      case Nil      => Right(())
      case problems => Left(ValidationError("tool loop", problems))

  private def assemble(
    id: String,
    version: String,
    model: ModelStep,
    tools: ToolSet,
    stack: MiddlewareStack
  ): Result[ToolLoop] =
    val messages = Messages.key
    val b        = GraphBuilder(id, version)
    // the call-tool, approval and ask nodes may write any tool's keys; each call is checked against its own tool's
    val callWrites: Set[StateKey[?, ?]] = tools.tools.flatMap(_.writes).toSet ++ stack.writes ++ Set(results, messages)

    val modelNode = b.declare[Unit]("model")
    val finish    = b.declare[Unit]("finish")
    val collect   = b.declare[Unit]("collect")
    val callTool  = b.declare[ToolTask]("call-tool")
    val approval  = b.declareResume[ApprovalRequest, ApprovalDecision]("approval")
    val batch     = b.dynamicJoin("tool-batch", collect)
    val asking    = tools.tools.filter(_.spec.question.isDefined)
    val askRefs: Map[String, ResumeRef[ToolQuestionRequest, ujson.Value]] =
      asking.map(t => t.spec.name -> b.declareResume[ToolQuestionRequest, ujson.Value](s"ask/${t.spec.name}")).toMap

    val pipeline = Pipeline(tools, stack, approval, askRefs)

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
          pipeline.approved(task, request.call.copy(arguments = arguments), state, context) match
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
        assistant <- stack.wrapModelCall(ModelRequest(history.map(_.message), tools), context)(callModel(model))
      yield
        val stored   = StoredMessage(s"${context.position.taskId.value}/assistant", assistant)
        val appended = Command.empty.update(messages, MessageUpdate.Append(stored))
        if assistant.toolCalls.isEmpty then appended.goto(finish)
        else appended.fanOut(batch, callTool, assistant.toolCalls.toVector.map(ToolTask(stored.id, _)))
      )
    }

    // the final answer, through every afterAgent; a changed answer replaces the stored message's content
    b.implement(finish, writes = Set(messages)) { (_, state, context) =>
      NodeResult.fromResult(for
        history <- state.get(messages)
        last <- history.lastOption
          .collect { case StoredMessage(id, a: AssistantMessage) if a.toolCalls.isEmpty => id -> a }
          .toRight(ValidationError("tool-loop", "finish found no final assistant message"))
        (answerId, assistant) = last
        changed <- stack.afterAgent(assistant.content, context)
        command <-
          if changed == assistant.content then Right(Command.empty)
          else if changed.trim.isEmpty then Left(ValidationError("tool-loop", "afterAgent returned a blank answer"))
          else
            val replaced = StoredMessage(answerId, assistant.copy(contentOpt = Some(changed)))
            Right(Command.empty.update(messages, MessageUpdate.Replace(answerId, replaced)))
      yield command)
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
      NodeResult.fromResult(stack.beforeAgent(text, context).map { transformed =>
        Command.empty
          .update(
            messages,
            MessageUpdate.Append(StoredMessage(s"${context.position.taskId.value}/user", UserMessage(transformed)))
          )
          .goto(modelNode)
      })
    }

    b.compile(input) { state =>
      state.get(messages).flatMap { history =>
        history.lastOption.map(_.message) match
          case Some(answer: AssistantMessage) if answer.toolCalls.isEmpty => Right(answer.content)
          case _ => Left(ValidationError("tool-loop", "the run ended without a final assistant message"))
      }
    }.map(new ToolLoop(_, approval, askRefs.values.map(_.node.id).toSet))

  /**
   * The model wrappers' innermost function: `model.next`, guarded, since the stack guards only its
   * hooks. A NonFatal throw is `Left`; a thrown cancellation restores the interrupt flag and is
   * `Left(CancelledError)`. Either way it is the model's failure, not a wrapper's.
   */
  private def callModel(model: ModelStep)(request: ModelRequest): Result[AssistantMessage] =
    Try(model.next(request.messages, request.tools)).toEither match
      case Right(result) => result
      case Left(thrown) =>
        CancelledError.fromThrowable(thrown, "model") match
          case Some(cancellation) =>
            Thread.currentThread().interrupt()
            Left(cancellation)
          case None => Failure[AssistantMessage](thrown).toResult

  /** The call pipeline (design #1278, "The call pipeline"; #1279): one call's checks, chain, tool and outcome. */
  final private class Pipeline(
    tools: ToolSet,
    stack: MiddlewareStack,
    approval: ResumeRef[ApprovalRequest, ApprovalDecision],
    askRefs: Map[String, ResumeRef[ToolQuestionRequest, ujson.Value]]
  ):

    def error(task: ToolTask, message: String): NodeResult = record(task, message, isError = true)

    /** Restores the interrupt flag, so the runtime cancels the task and it records nothing. */
    private def cancelled(error: CancelledError): NodeResult =
      Thread.currentThread().interrupt()
      NodeResult.Fail(error)

    private def record(task: ToolTask, content: String, isError: Boolean): NodeResult =
      NodeResult.Continue(
        Command.empty.update(results, ToolResult(task.assistantMessageId, task.call.id, content, isError))
      )

    private def suspend(task: ToolTask, call: ToolCall, reason: String, source: ApprovalSource): NodeResult =
      NodeResult.Suspend(StateUpdate.empty, ApprovalRequest(task.assistantMessageId, call, reason, source), approval)

    /** A new call: steps 2-4, then the middleware chain around the tool. */
    def admit[A](tool: AgentTool[A], task: ToolTask, state: ThreadState, context: RunContext): NodeResult =
      checked(tool, task, task.call) match
        case Left(refused) => refused
        case Right(args)   => execute(tool, task, task.call, args, state, context)

    /**
     * An approved call, as approved or with edited arguments: checked again, then run through the
     * whole chain with `approved = true`, so every wrapper - a deny rule too - sees it again.
     */
    def approved(task: ToolTask, call: ToolCall, state: ThreadState, context: RunContext): NodeResult =
      tools.get(call.name) match
        case None => error(task, s"Unknown tool '${call.name}'")
        case Some(tool) =>
          checked(tool, task, call) match
            case Left(refused) => refused
            case Right(args)   => execute(tool, task, call, args, state, context, approved = true)

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
              val chain = stack.wrapToolCall(ToolCallRequest(tool.spec, request.call), toolContext)(
                innermost(name)(AgentTool.resumeWith(tool, args, question, reply, toolContext))
              )
              outcome(tool, task, request.call, request.approved, chain, resumed = true)
        case _ => error(task, s"Tool '$name' does not take answers")

    /**
     * Steps 2-4: validate the raw arguments, decode them, run the tool's own check. `Left` is the
     * task's result instead: an error result, or a cancellation.
     */
    private def checked[A](tool: AgentTool[A], task: ToolTask, call: ToolCall): Either[NodeResult, A] =
      val name                                 = tool.spec.name
      def refused(message: String): NodeResult = error(task, s"Invalid arguments for '$name': $message")
      // the validator and the check are caller code: a throw refuses the call rather than failing the
      // run, unless it is a cancellation, which cancels the task as a throwing tool's does
      def guarded[T](run: => T): Either[NodeResult, T] =
        Try(run).toEither.left.map { thrown =>
          CancelledError.fromThrowable(thrown, s"tool $name").fold(refused(describe(thrown)))(cancelled)
        }
      for
        violations <- guarded(tools.validator.validate(tool.spec.argumentSchema, argumentsOf(tool, call)))
        _          <- Either.cond(violations.isEmpty, (), refused(violations.mkString("; ")))
        args       <- decode(tool, call).left.map(refused)
        check      <- guarded(tool.spec.validateDecoded(args))
        _          <- check.left.map(e => refused(e.message))
      yield args

    private def decode[A](tool: AgentTool[A], call: ToolCall): Either[String, A] =
      read(argumentsOf(tool, call))(using tool.spec.codec)

    /**
     * The call's arguments as the tool sees them. As core's `ToolFunction.execute` does, `null` for a
     * tool that requires nothing is the empty object; for a tool with required fields it stays `null`
     * and fails validation.
     */
    private def argumentsOf(tool: AgentTool[?], call: ToolCall): ujson.Value =
      call.arguments match
        case ujson.Null =>
          tool.spec.argumentSchema match
            case schema: ujson.Obj
                if schema.value.get("type").contains(ujson.Str("object")) &&
                  schema.value.get("required").forall { case ujson.Arr(names) => names.isEmpty; case _ => false } =>
              ujson.Obj()
            case _ => ujson.Null
        case other => other

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
      val chain = stack.wrapToolCall(ToolCallRequest(tool.spec, call), toolContext)(
        innermost(tool.spec.name)(tool.execute(args, toolContext))
      )
      outcome(tool, task, call, approved, chain)

    /**
     * The chain's innermost function, for one chain invocation: the tool's `execute` or `resume`,
     * [[guarded]]. Once it has produced `Fatal(CancelledError)`, every later call - a wrapper
     * retrying - returns that same outcome without running the tool again.
     */
    private def innermost(name: String)(run: => ToolOutcome): () => ToolOutcome =
      val cancelledWith = new AtomicReference[Option[ToolOutcome]](None)
      () =>
        cancelledWith.get.getOrElse {
          val result = guarded(name)(run)
          result match
            case ToolOutcome.Fatal(_: CancelledError) => cancelledWith.set(Some(result))
            case _                                    => ()
          result
        }

    /**
     * Guards the tool so that wrappers see a throwing tool as an outcome. A thrown NonFatal exception
     * is an error result; a thrown cancellation (an interrupt wrapped in another exception, or one
     * thrown with the flag set) restores the interrupt flag at once and is `Fatal(CancelledError)`.
     * An `InterruptedException` is not caught.
     */
    private def guarded(name: String)(run: => ToolOutcome): ToolOutcome =
      Try(run).toEither match
        case Right(result) => result
        case Left(thrown) =>
          CancelledError.fromThrowable(thrown, s"tool $name") match
            case Some(cancellation) =>
              Thread.currentThread().interrupt()
              ToolOutcome.Fatal(cancellation)
            case None => ToolOutcome.Error(s"Tool '$name' failed: ${describe(thrown)}")

    /**
     * Step 6: maps what the chain returned. `Fatal(CancelledError)` restores the interrupt flag, so
     * the runtime cancels the task and it records nothing; a wrapper's `MiddlewareFailed` fails the
     * run as itself, any other `Fatal` as `ToolFailed`. A `NeedsApproval` is attributed to the tool
     * or to the wrapper that raised it. `resumed` is true when the tool is continuing after a question.
     */
    private def outcome[A](
      tool: AgentTool[A],
      task: ToolTask,
      call: ToolCall,
      approved: Boolean,
      chain: MiddlewareStack.ToolChainResult,
      resumed: Boolean = false
    ): NodeResult =
      val name                                 = tool.spec.name
      def failRun(error: LLMError): NodeResult = NodeResult.Fail(GraphError.ToolFailed(name, call.id, error))
      chain.outcome match
        case ToolOutcome.Success(content, update) =>
          val allowed = tool.writes ++ stack.writes
          val undeclared =
            update.operations.map(_.key.id).filterNot(k => allowed.exists(_.id == k)).map(_.value).distinct
          if undeclared.isEmpty then
            NodeResult.Continue(
              Command(update, Nil)
                .update(results, ToolResult(task.assistantMessageId, call.id, rendered(content), isError = false))
            )
          else
            failRun(
              ValidationError(
                "tool update",
                s"tool '$name' updated ${undeclared.map(k => s"'$k'").mkString(", ")}, which neither it nor any middleware declares"
              )
            )
        case ToolOutcome.Error(message) => error(task, message)
        case ToolOutcome.NeedsApproval(reason) =>
          val (asker, source) = chain.raisedBy match
            case None     => (s"Tool '$name'", ApprovalSource.Tool)
            case Some(id) => (s"Middleware '${id.value}'", ApprovalSource.Middleware(id))
          // approving would run `execute` again and lose the answer
          if resumed then error(task, s"$asker asked for approval after a question: $reason")
          else if approved then error(task, s"$asker asked for approval again: $reason")
          else suspend(task, call, reason, source)
        case ToolOutcome.Ask(question) =>
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
        case ToolOutcome.Fatal(cancellation: CancelledError)        => cancelled(cancellation)
        case ToolOutcome.Fatal(failed: GraphError.MiddlewareFailed) => NodeResult.Fail(failed)
        case ToolOutcome.Fatal(error)                               => failRun(error)

    /**
     * A string result is the string itself, not a quoted JSON string - except a blank one, which a
     * `ToolMessage` refuses, so it is recorded in its quoted JSON form.
     */
    private def rendered(content: ujson.Value): String = content match
      case ujson.Str(s) if s.trim.nonEmpty => s
      case other                           => other.render()
