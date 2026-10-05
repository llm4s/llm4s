# Agent Loop Cutover Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the graph runtime the sole agent loop. `Agent` is rebuilt as a builder-made facade over a generalised multi-agent `ToolLoop` on `GraphRuntime`, the legacy loop and `AgentState` are deleted, and every caller migrates (#1328).

**Architecture:** `ToolLoop` grows from one model and tool set into an *agent family*. Each `LoopAgent` contributes its node set under an `<agentId>/` prefix, and all of them share `messages`, plus new `active-agent`, `usage` and `turn` keys. Handoffs are routes between families' model nodes. A guardrail block becomes a `TurnOutcome`. `org.llm4s.agent.Agent` becomes a thin facade: `AgentBuilder` compiles the family once, `Agent` owns a `GraphRuntime` and maps `RunResult` to `AgentResult`. Callers in other modules move in later tasks.

**Tech Stack:** Scala 3.7.1, upickle/ujson, ScalaTest, sbt, Ox (already a dependency of `llm4s-agent`), cats-effect (`llm4s-effect`), ZIO (`llm4s-zio`).

**Spec:** `docs/superpowers/specs/2026-10-05-agent-loop-cutover-design.md`. Read it in full before any task; it is the source of truth for names, messages and semantics. Also read `docs/design/typed-agent-runtime-design.md` §4.5-4.8 and the current `modules/agent/src/main/scala/org/llm4s/agent/graph/toolloop/ToolLoop.scala`.

## Global Constraints

- New loop types live in `org.llm4s.agent.graph.toolloop`, and new public agent types in `org.llm4s.agent`. `GuardrailBlocked` is in `org.llm4s.agent.graph.middleware`, and `ContextWindowMiddleware` in `org.llm4s.agent.graph.middleware`.
- Nothing in `llm4s-core` changes. `TraceEvent.AgentStateUpdated` stays in core, untouched, for slice 3.
- `Result[A]` for fallible operations. No `try/catch/finally` (use `Try(...).toResult`, and `CancelledError.fromThrowable` as `ToolLoop.Pipeline` does), no infix calls, no `synchronized`, no `sys.env`.
- Nothing is frozen: delete replaced API outright, with no shims, `@deprecated` or old loop beside the new one.
- Growth-prone data types (`AgentResult`, `LoopAgent`) follow the CLAUDE.md pattern: `final case class X private (...)`, companion `apply`, `with*` setters, private `copy`.
- Main sources use Scala 3 indentation syntax, as in `graph/`. Test sources follow their neighbours: braces, as in `ToolLoopSpec`.
- `llm4s-agent` coverage floor is 80% (`coverageFloor(80)` in `build.sbt`); never lower it, or any other floor.
- Every commit: `git commit -s`, message ending with a blank line then `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`. Run `sbt scalafmtAll` before committing.
- `sbt agent/test` is green at every commit from Task 1. Modules that depend on `llm4s-agent` (`llm4s-effect`, `llm4s-zio`, `workspaceClient`, `observability` tests, `it`, `samples`) stop compiling at Task 6 and are green again at the end of Task 10. The PR is squash-merged, so this is acceptable on the branch only. Each of Tasks 8-10 states which modules it brings back.

## Review Focus

- **`history` that ends in an assistant message with tool calls but no results** (an exported conversation cut mid-turn): `run(threadId, query, history = ...)` returns `Left(ValidationError)` from `Message.validateConversation`, and no thread is created. Pinned in Task 6.
- **`continueConversation` on a `Suspended` result** (a caller ignoring the status): `Left` with the runtime's `PendingInterrupts`, not a new turn layered on parked work. Pinned in Task 6.
- **An input guardrail that transforms rather than blocks** (`PIIMasker`): the stored user message is the transformed text and the outcome is `Completed`, never `Blocked`. Pinned in Task 4.
- **Pruning that would cut between an assistant tool call and its `ToolMessage`**: the pruned list keeps both or drops both, and passes `Message.validateConversation`. Pinned in Task 5.
- **A thread whose active agent was a handoff target, then the app rebuilds the root without that handoff**: the graph version differs and `run` refuses the thread, rather than routing to a missing node. Pinned in Task 7.

---

### Task 1: `ModelStep` returns `Completion`; usage key

**Files:**
- Modify: `modules/agent/src/main/scala/org/llm4s/agent/graph/toolloop/ToolLoop.scala`
- Create: `modules/agent/src/main/scala/org/llm4s/agent/graph/toolloop/LoopKeys.scala`
- Modify: `modules/agent/src/main/scala/org/llm4s/agent/graph/middleware/AgentMiddleware.scala` (`wrapModelCall`'s `next` and result type)
- Modify: `modules/agent/src/main/scala/org/llm4s/agent/graph/middleware/MiddlewareStack.scala`
- Test: `modules/agent/src/test/scala/org/llm4s/agent/graph/toolloop/ToolLoopSpec.scala`, `.../graph/middleware/MiddlewareStackSpec.scala`

**Interfaces (produces):**
```scala
trait ModelStep:
  def next(messages: Vector[Message], tools: ToolSet): Result[Completion]
object ModelStep:
  def fromClient(client: LLMClient, options: CompletionOptions = CompletionOptions()): ModelStep
  // calls client.complete(Conversation(messages), options.withTools(tools.toolFunctions))

// AgentMiddleware
def wrapModelCall(request: ModelRequest, context: RunContext)(
  next: ModelRequest => Result[Completion]): Result[Completion] = next(request)

object LoopKeys:
  /** Usage accumulated over the thread, per model. */
  val usage: StateKey[UsageSummary, UsageSummary]   // update = a one-call summary; apply = UsageSummary.merge
```
Check `org.llm4s.llmconnect.model.UsageSummary` for the existing merge or add operation (it moved there in slice 7) and use it; do not write a second one. Build a one-call summary from `Completion.model` and `Completion.usage` (`TokenUsage`) and `Completion.estimatedCost` if present, with the constructor `UsageSummary` already offers. The model node writes `LoopKeys.usage` after a successful call. `usage` is a loop-owned key: add it to `ownedKeysUntouched`.

- [ ] **Step 1: Failing tests.**
  - `ToolLoopSpec`: change `ScriptedModel.next` to return `Result[Completion]`. Add a helper `completion(msg: AssistantMessage, prompt: Int = 10, completion: Int = 5): Completion`, built with `Completion(...)`'s companion `apply` (read `Completion.scala` for the required fields).
  - A new test "accumulates usage over every model call": two tool rounds and a final answer give `state.get(LoopKeys.usage)` with request count 3 and the summed tokens for model `"scripted"`.
  - A new test "refuses a tool that declares the usage key".
  - In `MiddlewareStackSpec`, update the wrappers to the `Completion` type.
- [ ] **Step 2: Run, expect compile failure.** `sbt "agent/testOnly org.llm4s.agent.graph.toolloop.ToolLoopSpec org.llm4s.agent.graph.middleware.MiddlewareStackSpec"`
- [ ] **Step 3: Implement.** In the model node, `callModel` returns `Result[Completion]`. Then `assistant = completion.message`, followed by the existing `assistant.validate`, and the usage update added to the same `Command`.
- [ ] **Step 4: Run** `sbt scalafmtAll agent/test`. Expect all green.
- [ ] **Step 5: Commit** `refactor(agent): ModelStep returns the Completion; loop records usage (#1328)`

---

### Task 2: The agent family loop: `AgentId`, `LoopAgent`, prefixes, turn state, system prompt, max steps, history

**Files:**
- Create: `modules/agent/src/main/scala/org/llm4s/agent/AgentId.scala`
- Create: `modules/agent/src/main/scala/org/llm4s/agent/graph/toolloop/LoopAgent.scala`
- Modify: `modules/agent/src/main/scala/org/llm4s/agent/graph/toolloop/LoopKeys.scala`
- Modify: `modules/agent/src/main/scala/org/llm4s/agent/graph/toolloop/ToolLoop.scala`
- Test: `modules/agent/src/test/scala/org/llm4s/agent/graph/toolloop/ToolLoopSpec.scala`, new `.../graph/toolloop/AgentFamilySpec.scala`

**Interfaces (produces):**
```scala
// org.llm4s.agent
opaque type AgentId = String
object AgentId:
  def of(value: String): Result[AgentId]        // [a-zA-Z0-9_-]{1,52}, else ValidationError("agent.id", ...)
  private[llm4s] def unsafe(value: String): AgentId
  extension (id: AgentId) def value: String
  given ReadWriter[AgentId]

// org.llm4s.agent.graph.toolloop
final case class LoopAgent private (
  id: AgentId, model: ModelStep, tools: ToolSet, middleware: Seq[AgentMiddleware],
  systemPrompt: Option[String], maxSteps: Int, handoffs: Vector[LoopHandoff]):
  def withSystemPrompt(p: Option[String]): LoopAgent; def withMaxSteps(n: Int): LoopAgent
  def withMiddleware(m: Seq[AgentMiddleware]): LoopAgent; def withHandoffs(h: Vector[LoopHandoff]): LoopAgent
object LoopAgent:
  def apply(id: AgentId, model: ModelStep, tools: ToolSet = ToolSet.empty): LoopAgent  // maxSteps = 50, no prompt
final case class LoopHandoff(target: AgentId, reason: Option[String], preserveContext: Boolean)  // used in Task 3

final case class AgentInput(query: String, history: Vector[Message] = Vector.empty) derives ReadWriter
enum TurnOutcome derives ReadWriter:
  case Completed
  case Blocked(guardrail: String, reason: String)
  case StepLimitReached
final case class TurnState(steps: Int, outcome: Option[TurnOutcome]) derives ReadWriter
final case class TurnOutput(outcome: TurnOutcome, activeAgent: AgentId) derives ReadWriter

object LoopKeys:
  val usage: StateKey[UsageSummary, UsageSummary]           // Task 1
  val activeAgent: StateKey[Option[AgentId], AgentId]       // replace semantics
  val turn: StateKey[TurnState, TurnState]                  // StateKey.replace, initial TurnState(0, None)

final class ToolLoop private (val graph: CompiledGraph[AgentInput, TurnOutput], ...):
  def requests(suspended: RunResult.Suspended): Result[Vector[(InterruptId, ApprovalRequest)]]   // across all agents
  def questions(suspended: RunResult.Suspended): Result[Vector[(InterruptId, ToolQuestionRequest)]]
  def answers(decisions: (InterruptId, ApprovalDecision)*): Map[InterruptId, ujson.Value]
  def answer[Ans: ReadWriter](id: InterruptId, answer: Ans): (InterruptId, ujson.Value)
object ToolLoop:
  def build(id: String, version: String, root: AgentId, agents: Vector[LoopAgent]): Result[ToolLoop]
```

Behaviour, from the spec's "Graph and thread state":
- **Node layout.** Every agent gets `<id>/model`, `<id>/finish`, `<id>/collect`, `<id>/call-tool`, `<id>/approval`, `<id>/ask/<tool>` and a join `<id>/tool-batch`. The one shared entry is `input`. `assemble` becomes a per-agent function adding its nodes to one shared `GraphBuilder`. `Pipeline` is per agent.
- **`ApprovalDecision` answers are agent-independent.** `answers` encodes with any agent's approval `ResumeRef`, because they share the codec. `requests` and `questions` filter interrupts whose resume node is any agent's approval or ask node.
- **`build` refuses** an empty `agents`, a `root` not in `agents`, and a duplicate `AgentId`. Each agent's tool set, and its middleware's tools, is checked as today. `ownedKeysUntouched` adds `usage`, `active-agent` and `turn`.
- **`input`:**
  1. Read `activeAgent`. If it is `None` (a new thread), require `messages` to be empty when `history` is non-empty, refuse a `SystemMessage` in `history` (`ValidationError("history", "system messages are not imported; prompts belong to agents")`), run `Message.validateConversation(history)`, append each as `StoredMessage(s"${taskId}/history/$i", m)`, and set `activeAgent = root`.
  2. If `activeAgent` is `Some`, refuse a non-empty `history` with `ValidationError("history", "history is imported only into a new thread")`.
  3. Then run that agent's `beforeAgent`, append the user message, set `turn = TurnState(0, None)`, and `goto(<active>/model)`.
- **`<id>/model`:** if `turn.steps >= maxSteps`, update `turn` to `steps, Some(StepLimitReached)` with no route (the run ends). Otherwise update `turn.steps + 1`, call the wrapped model with `systemPrompt.map(SystemMessage(_)).toVector ++ history`, and continue as today. `Message.validateConversation` still checks the stored history only.
- **`<id>/finish`:** as today, plus `turn` with `outcome = Some(Completed)`.
- **Graph output:** `TurnOutput(turn.outcome, activeAgent.getOrElse(root))`. The fallback covers an input block on a new thread (Task 4), which writes nothing but `turn`. No outcome is `Left(ValidationError("tool loop", "the run ended without an outcome"))`.

- [ ] **Step 1: Failing tests.**
  - Port `ToolLoopSpec` to the family API with a local helper:
    ```scala
    private def single(model: ModelStep, tools: ToolSet, middleware: Seq[AgentMiddleware] = Nil): ToolLoop =
      ToolLoop.build("loop", "1", AgentId.unsafe("a"),
        Vector(LoopAgent(AgentId.unsafe("a"), model, tools).withMiddleware(middleware))).value
    ```
    Inputs become `AgentInput("...")`, and outputs `TurnOutput(TurnOutcome.Completed, a)`. Read the answer from the last message in state. Node-ID assertions gain the `a/` prefix.
  - New `AgentFamilySpec` covers:
    - the system prompt is sent first on every model call and never in `state.get(Messages.key)`
    - `maxSteps = 2` against a model that always calls a tool ends with `StepLimitReached` after exactly two model calls, with the thread `Completed`
    - a second `start` on the same thread resets `turn.steps` and appends after the previous answer
    - `history` seeds a new thread before the user message
    - `history` on an existing thread is refused, and the thread is unchanged
    - `history` containing a `SystemMessage` is refused
    - `build` refuses an empty family, an unknown root and a duplicate ID
    - a tool declaring `LoopKeys.turn` is refused
  - New `AgentIdSpec`: valid, invalid and too-long IDs; ReadWriter round trip.
- [ ] **Step 2: Run, expect compile failure.** `sbt "agent/testOnly org.llm4s.agent.graph.toolloop.* org.llm4s.agent.AgentIdSpec"`
- [ ] **Step 3: Implement** as above.
- [ ] **Step 4: Run** `sbt scalafmtAll agent/test`. Expect all green, the legacy `Agent` specs included (they don't use `ToolLoop`).
- [ ] **Step 5: Commit** `feat(agent): ToolLoop runs an agent family with turn state, prompts and step limits (#1328)`

---

### Task 3: Handoffs as routes

**Files:**
- Modify: `modules/agent/src/main/scala/org/llm4s/agent/graph/toolloop/ToolLoop.scala`
- Create: `modules/agent/src/main/scala/org/llm4s/agent/graph/toolloop/HandoffTools.scala`
- Test: new `modules/agent/src/test/scala/org/llm4s/agent/graph/toolloop/HandoffRouteSpec.scala`

**Interfaces:**
- Consumes: `LoopAgent.handoffs: Vector[LoopHandoff]` and `LoopKeys.activeAgent` (Task 2).
- Produces:
```scala
private[toolloop] object HandoffTools:
  val Prefix = "handoff_to_"
  def toolName(target: AgentId): String = s"$Prefix${target.value}"
  /** A stand-in AgentTool per handoff, so the model is offered it; ToolLoop intercepts the call. */
  def tools(handoffs: Vector[LoopHandoff]): Vector[AgentTool[ujson.Value]]
  // schema: object with required string field "reason"; description:
  //   reason.fold("Hand off this query to a specialist agent.")(r => s"Hand off this query to a specialist agent. $r")
```

Behaviour, from the spec's "Handoffs":
- **`build` refuses:** a handoff target not in the family; a handoff tool name clashing with one of the agent's tools; two handoffs to the same target from one agent.
- **The model node.** Classify the assistant's tool calls:
  - **No handoff call:** as today.
  - **Exactly one call, and it is a handoff:** store the assistant message, then append `StoredMessage(s"$taskId/tool/${call.id}", ToolMessage("Transferred to <target>", call.id))`. Set `activeAgent = target`, `goto(<target>/model)`, and don't fan out.
  - **A handoff mixed with other calls, or more than one handoff:** store the assistant message, then append one error `ToolMessage` per call, in call order, rendered as `ujson.Obj("error" -> msg)` as `collect` does. The message is: `"A handoff must be the only tool call in a message; no call in this message was run"`. Then `goto(<same>/model)`. The step was already counted.
- **The step limit counts each agent's model call against `turn.steps`.** The handoff message is one step, and the target's first call another; `turn` is shared, not per agent.
- **`preserveContext = false`:** the target's model node sends only the messages from the transferring assistant message onwards. Find the last `StoredMessage` whose message is an assistant with a handoff call to this agent. Compute this view at the model node from history; it is not stored. Store the handoff's `preserveContext` per (source, target) pair in a lookup built at `build`.

- [ ] **Step 1: Failing tests in `HandoffRouteSpec`:**
  - triage hands off to `physics`; physics answers with its own prompt (asserted on what its scripted model saw) and its own tools offered, and the triage tools not offered
  - the `ToolMessage("Transferred to physics")` sits in history
  - a second turn starts at `physics/model` (the triage model is not called)
  - physics hands back to triage (an A-B cycle) within one turn
  - a mixed batch gets error results for every call, nothing runs (a counting tool's count stays 0), and the same agent is asked again
  - with `preserveContext = false`, physics sees only the messages from the transfer onwards
  - `maxSteps = 2` with triage handing off to physics, which calls a tool, ends `StepLimitReached`
  - `build` refuses an unknown target and a name clash
- [ ] **Step 2: Run, expect failures.** `sbt "agent/testOnly org.llm4s.agent.graph.toolloop.HandoffRouteSpec"`
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `sbt scalafmtAll agent/test`.
- [ ] **Step 5: Commit** `feat(agent): handoffs route between agents of one loop (#1328)`

---

### Task 4: Guardrail blocks as an outcome

**Files:**
- Create: `modules/agent/src/main/scala/org/llm4s/agent/graph/middleware/GuardrailBlocked.scala`
- Modify: `modules/agent/src/main/scala/org/llm4s/agent/graph/middleware/GuardrailMiddleware.scala`
- Modify: `modules/agent/src/main/scala/org/llm4s/agent/graph/toolloop/ToolLoop.scala` (`input`, `finish`)
- Test: `modules/agent/src/test/scala/org/llm4s/agent/graph/middleware/GuardrailMiddlewareSpec.scala`, `.../graph/toolloop/ToolLoopSpec.scala`

**Interfaces (produces):**
```scala
final case class GuardrailBlocked(guardrail: String, reason: String) extends NonRecoverableError:
  val message: String = s"Guardrail '$guardrail' blocked: $reason"
  // implement whatever else LLMError requires; read org.llm4s.error.LLMError and copy a simple case such as ValidationError

final class GuardrailMiddleware(
  input: Seq[InputGuardrail],
  output: Seq[OutputGuardrail],
  val id: MiddlewareId = MiddlewareId("guardrails"),
  refusal: (String, String) => String = GuardrailMiddleware.defaultRefusal
) extends AgentMiddleware:
  def refusalFor(blocked: GuardrailBlocked): String = refusal(blocked.guardrail, blocked.reason)
object GuardrailMiddleware:
  val defaultRefusal: (String, String) => String = (g, r) => s"Response withheld by guardrail `$g`: $r"
```
- **`GuardrailMiddleware.run` returns `GuardrailBlocked`.** It carries the first failing guardrail's `name`, and the reason is every failing guardrail's `formatted` error joined with `"; "`. The `CompositeGuardrail.multipleFailures` path in this middleware goes; leave `CompositeGuardrail` itself alone.
- **`input`:** when `beforeAgent` returns `Left(b: GuardrailBlocked)`, return `Command.empty.update(LoopKeys.turn, TurnState(0, Some(TurnOutcome.Blocked(b.guardrail, b.reason))))` with no route and nothing else. Any other `Left` fails, as today.
- **`finish`:** when `afterAgent` returns `Left(b: GuardrailBlocked)`, replace the answer's stored message content with the refusal text and set the `Blocked` outcome.
  - The refusal text comes from the `GuardrailMiddleware` whose `id` raised it. `MiddlewareStack.afterAgent` must report which middleware returned the `Left`; it already tracks `raisedBy` for tool chains, so add the same for the agent hooks. Return `Left((MiddlewareId, LLMError))` from a new private `[toolloop]` variant, or wrap the error.
  - If a different middleware returns `GuardrailBlocked`, use `GuardrailMiddleware.defaultRefusal`.

- [ ] **Step 1: Failing tests.**
  - `GuardrailMiddlewareSpec`: `beforeAgent` with `LengthCheck(1, 5)` on `"too long input"` is `Left(GuardrailBlocked("LengthCheck"-or-its-name, ...))`; two failing guardrails give the first's name and both reasons.
  - `ToolLoopSpec`, input block: after a blocked second turn, messages equal the first turn's, `usage` is unchanged, the thread is `Completed`, the output is `Blocked`, and a third turn succeeds.
  - `ToolLoopSpec`, output block: the stored answer's content is ``Response withheld by guardrail `<name>`: ...``, the original text appears nowhere in state, and the output is `Blocked`. A custom `refusal` function is used when given.
  - `ToolLoopSpec`, transforming guardrail (Review Focus): an input `PIIMasker` that redacts an email stores the redacted user message, and the outcome is `Completed`.
  - `ToolLoopSpec`: a non-guardrail `Left` from `beforeAgent` still fails the run.
- [ ] **Step 2: Run, expect failures.** `sbt "agent/testOnly org.llm4s.agent.graph.middleware.GuardrailMiddlewareSpec org.llm4s.agent.graph.toolloop.ToolLoopSpec"`
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `sbt scalafmtAll agent/test`.
- [ ] **Step 5: Commit** `feat(agent): a guardrail block ends the turn as an outcome (#1328)`

---

### Task 5: `ContextWindowMiddleware`

**Files:**
- Create: `modules/agent/src/main/scala/org/llm4s/agent/graph/middleware/ContextWindowMiddleware.scala`
- Create: `modules/agent/src/main/scala/org/llm4s/agent/ContextPruning.scala` (the pruning functions moved out of `AgentState`'s companion, `private[agent]`)
- Modify: `modules/agent/src/main/scala/org/llm4s/agent/AgentState.scala`. `pruneConversation` delegates to `ContextPruning` until Task 6 deletes the file.
- Test: new `modules/agent/src/test/scala/org/llm4s/agent/graph/middleware/ContextWindowMiddlewareSpec.scala`

**Interfaces (produces):**
```scala
private[agent] object ContextPruning:
  def prune(messages: Seq[Message], config: ContextWindowConfig, tokenCounter: Message => Int): Seq[Message]
  // the needsPruning check + strategy dispatch from AgentState.pruneConversation, then keepToolPairs
  private[agent] def keepToolPairs(pruned: Seq[Message]): Seq[Message]
  // drops a ToolMessage whose assistant call was pruned, and an assistant-with-calls whose results were pruned

final class ContextWindowMiddleware(
  config: ContextWindowConfig,
  tokenCounter: Message => Int = ContextPruning.defaultTokenCounter,
  val id: MiddlewareId = MiddlewareId("context-window")
) extends AgentMiddleware:
  override def wrapModelCall(request: ModelRequest, context: RunContext)(next: ModelRequest => Result[Completion]) =
    next(request.copy(messages = <system messages kept> ++ ContextPruning.prune(<non-system>, config, tokenCounter).toVector))
```
`ModelRequest` reaches the middleware with the system prompt prepended (Task 2), so keep a leading `SystemMessage` out of pruning and put it back first. Move `defaultTokenCounter` and the five strategies' code verbatim. Only `keepToolPairs` is new.

- [ ] **Step 1: Failing tests in `ContextWindowMiddlewareSpec`:**
  - each of `OldestFirst`, `MiddleOut`, `RecentTurnsOnly(1)`, `AdaptiveWindowing(...)` and `Custom` trims the messages the scripted model sees, while `state.get(Messages.key)` keeps all of them
  - the system prompt survives pruning, first
  - Review Focus: a history where the cut falls between an assistant tool call and its `ToolMessage` yields a list that passes `Message.validateConversation` and contains both or neither
  - port the strategy cases of `AdaptiveWindowingSpec` and `ContextWindowConfigSpec` that test pruning outcomes, calling `ContextPruning.prune` directly
- [ ] **Step 2: Run, expect compile failure.** `sbt "agent/testOnly org.llm4s.agent.graph.middleware.ContextWindowMiddlewareSpec"`
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `sbt scalafmtAll agent/test`.
- [ ] **Step 5: Commit** `feat(agent): ContextWindowMiddleware prunes what is sent, not what is stored (#1328)`

---

### Task 6: The new `Agent`; legacy loop deleted; `org.llm4s.assistant` migrated

This is the cutover commit for `llm4s-agent`. Dependent modules stop compiling here (see Global Constraints).

**Files:**
- Rewrite: `modules/agent/src/main/scala/org/llm4s/agent/Agent.scala`
- Create: `modules/agent/src/main/scala/org/llm4s/agent/AgentBuilder.scala`, `AgentResult.scala` (with the new `AgentStatus`)
- Modify: `modules/agent/src/main/scala/org/llm4s/agent/Handoff.scala`. The target becomes `AgentBuilder`, and `transferSystemMessage` goes.
- Delete: `AgentState.scala` (`ContextPruning` already holds the pruning), `AgentStreamingExecutor.scala`, `AgentTraceFormatter.scala`, `GuardrailApplicator.scala`, `HandoffExecutor.scala`, `ToolProcessor.scala`, `streaming/AgentEvent.scala`, `streaming/package.scala`. Delete `ToolExecutionStrategy` wherever it is defined (`grep -rn "ToolExecutionStrategy" modules/agent/src/main`).
- Modify: `modules/agent/src/main/scala/org/llm4s/assistant/AssistantAgent.scala`, `SessionState.scala`, `SessionManager.scala`
- Delete tests of deleted code: `AgentStateSpec`, `AgentStateSerializationSpec`, `AgentStatusSpec`, `AgentTraceFormatterSpec`, `GuardrailApplicatorSpec`, `HandoffExecutorSpec`, `ToolProcessorSpec`, everything under `src/test/.../agent/streaming/`, `AdaptiveWindowingSpec` (ported in Task 5)
- Test: new `AgentRunSpec`, `AgentConversationSpec`, `AgentGuardrailSpec` in `modules/agent/src/test/scala/org/llm4s/agent/`; update `assistant/*Spec`

**Interfaces (produces):**
```scala
final class AgentBuilder private (...):
  def withTools(tools: ToolSet): AgentBuilder
  def withTools(registry: ToolRegistry): AgentBuilder        // AgentTool.fromToolFunction per tool, ToolSet.of at build
  def withSystemPrompt(prompt: String): AgentBuilder
  def withCompletionOptions(options: CompletionOptions): AgentBuilder
  def withMiddleware(middleware: AgentMiddleware*): AgentBuilder  // appends
  def withHandoffs(handoffs: Handoff*): AgentBuilder              // appends
  def withMaxSteps(n: Int): AgentBuilder
  def withRuntime(runtime: GraphRuntime): AgentBuilder
  def withTracing(tracing: Tracing): AgentBuilder
  def build(): Result[Agent]
  private[agent] def id: String
object Agent:
  val DefaultMaxSteps: Int = 50
  def builder(id: String, client: LLMClient): AgentBuilder

final class Agent private[agent] (...):
  def id: AgentId
  def loop: ToolLoop
  def run(query: String, config: RunConfig = RunConfig()): Result[AgentResult]
  def run(threadId: ThreadId, query: String, config: RunConfig, history: Seq[Message]): Result[AgentResult]
  def run(threadId: ThreadId, query: String): Result[AgentResult]
  def continueConversation(previous: AgentResult, query: String, config: RunConfig = RunConfig()): Result[AgentResult]
  def runMultiTurn(first: String, followUps: Seq[String], config: RunConfig = RunConfig()): Result[AgentResult]
  def recover(threadId: ThreadId, config: RunConfig = RunConfig()): Result[AgentResult]
  def resume(threadId: ThreadId, answers: Map[InterruptId, ujson.Value], config: RunConfig = RunConfig()): Result[AgentResult]
  def start(threadId: ThreadId, query: String, config: RunConfig = RunConfig(), history: Seq[Message] = Nil): Result[RunHandle[AgentResult]]

final case class Handoff(id: String, target: AgentBuilder, transferReason: Option[String] = None, preserveContext: Boolean = true)
// Handoff.of / Handoff.to keep their shapes with AgentBuilder in place of Agent

final case class AgentResult private (threadId: ThreadId, runId: RunId, activeAgent: AgentId,
  status: AgentStatus, messages: Vector[Message], usage: UsageSummary):
  def answer: Option[String]
  def approve(id: InterruptId): (InterruptId, ujson.Value)
  def reject(id: InterruptId, reason: String): (InterruptId, ujson.Value)
  def edit(id: InterruptId, arguments: ujson.Value): (InterruptId, ujson.Value)
  def reply[Ans: ReadWriter](id: InterruptId, value: Ans): (InterruptId, ujson.Value)
object AgentResult:
  private[agent] def apply(...): AgentResult

enum AgentStatus:
  case Completed(answer: String)
  case Blocked(guardrail: String, reason: String)
  case StepLimitReached
  case Suspended(approvals: Vector[(InterruptId, ApprovalRequest)], questions: Vector[(InterruptId, ToolQuestionRequest)])
```
`run(threadId, ...)` is split into overloads, not default arguments, because Scala allows default arguments on only one overload of a method.

**Building:**
- `build()` walks the handoff graph from the root by builder `id`, breadth-first, to collect the family. The same ID reached through two builders that are not `eq` and whose settings differ is refused: `ValidationError("handoffs", s"agent id '$id' is defined by two different builders")`. Compare by reference first, then by the fingerprint inputs.
- Each builder becomes a `LoopAgent`: `ModelStep.fromClient(client, completionOptions)`, its tools, middleware, prompt, max steps, and its handoffs as `LoopHandoff(AgentId(h.id), h.transferReason, h.preserveContext)`. The handoff `id` *is* the target's agent ID. Refuse a handoff whose `id` differs from `target.id`, with `ValidationError("handoffs", ...)`.
- **The graph version** is the hex SHA-256 of a canonical string listing, per agent sorted by ID:
  - the ID, the system prompt and the max steps
  - sorted tool names with each `toolDefinition.render()`
  - middleware IDs in stack order
  - handoff targets with `preserveContext`

  The graph ID is the root's ID. Implement it in `AgentBuilder` as `private[agent] def fingerprint(family): String`.

**Running:**
- `start` calls `runtime.start(threadId, loop.graph, AgentInput(query, history.toVector), config)` and maps the handle. `RunHandle[TurnOutput]` becomes `RunHandle[AgentResult]` through a small private adapter class whose `await()` maps the `RunResult`:
  - `Completed(state, out, _)` gives `status` from `out.outcome`: `Completed` uses the last assistant message's content as the answer, `Blocked` and `StepLimitReached` map directly. `messages` come from `Messages.key`, `usage` from `LoopKeys.usage`, and `activeAgent` from `out`.
  - `Suspended(state, _, _)` gives `AgentStatus.Suspended(loop.requests(s), loop.questions(s))`, with `activeAgent` from state.
  - `Failed(_, error)` gives `Left(error)`.
- `run(query)` uses `ThreadId(java.util.UUID.randomUUID().toString)`.
- `recover` and `resume` call the runtime with `loop.graph` and map the same way.
- `runMultiTurn` folds `continueConversation` and stops at the first result whose status is not `Completed`.
- `withTracing`: `start` subscribes with `TracingSubscriber.attach(runtime, threadId, tracing, afterSeq)` before starting, and cancels the subscription when `await` returns. Read `TracingSubscriber.scala` for its exact signature and how `afterSeq` is found (the latest sequence of the thread, or 0).

**Migrating `org.llm4s.assistant`:**
- `AssistantAgent` builds its `Agent` once from its client and tools, and keeps `threadId: Option[ThreadId]` and `last: Option[AgentResult]` in `SessionState` in place of `agentState`.
- Its `runSteps` loop becomes one `agent.run(...)`.
- `SessionManager` writes `last.messages` where it wrote `agentState.conversation`. Loading reads the messages and passes them as `history` on the next turn's new thread.
- Keep `SessionState` and `SessionManager` otherwise as they are. Keep `private[assistant]` on what pass 8 made private.

- [ ] **Step 1: Failing tests.** All of these use `org.llm4s.testutil.FixtureChatProvider`'s client or a local scripted `LLMClient` whose `complete` returns queued `Completion`s. Read `ToolLoopSpec` and the existing `AgentSpec` for how they script clients, and reuse that.
  - `AgentRunSpec`:
    - a single turn returns `Completed(answer)` with `messages` = user, assistant
    - two parallel tool calls each get one result
    - usage is summed over three model calls
    - the system prompt is not in `messages`
    - `run(query)` makes a fresh thread each time
    - `build()` refuses a tool name clash and a duplicate ID with different builders
  - `AgentConversationSpec`:
    - `continueConversation` appends to the same thread
    - `runMultiTurn` with three queries gives six messages
    - `runMultiTurn` stops at a blocked turn
    - `run(threadId, q, config, history)` seeds history, and is refused on an existing thread
    - Review Focus: `history` ending in an assistant tool call without its result is `Left(ValidationError)`, and `runtime` has no checkpoint for that thread
    - Review Focus: `continueConversation` on a `Suspended` result is `Left` with `PendingInterrupts`
  - `AgentGuardrailSpec`: input block, output block, a following turn working, and a non-guardrail failure followed by `recover` completing. Port the behaviour of `guardrails/AgentGuardrailsIntegrationSpec` that the new loop keeps, delete what tests removed behaviour, and move the rest into this spec.
  - Port the cases of `AgentSpec`, `AgentErrorPropagationSpec`, `AgentToolFailureTest`, `AgentUsage*Spec` and `AgentTracingSpec` that describe kept behaviour to the new API, and delete the rest. Each deleted case is listed in the commit message body, one line each, with the reason ("tests `runStep`", "tests `AgentState.dump`").
  - Update `assistant/*Spec` to the new session state.
- [ ] **Step 2: Run, expect compile failure.** `sbt agent/test`
- [ ] **Step 3: Implement** the files above. Delete the legacy files, then `grep -rn "AgentState\|AgentContext\|AgentEvent\|ToolProcessor\|GuardrailApplicator\|HandoffExecutor\|AgentStreamingExecutor\|ToolExecutionStrategy" modules/agent/src` returns nothing.
- [ ] **Step 4: Run** `sbt scalafmtAll agent/test` (all green), then `sbt "coverage; agent/test; agent/coverageReport"` (at or above 80%).
- [ ] **Step 5: Commit** `feat(agent)!: Agent runs on the graph runtime; AgentState and the legacy loop removed (#1328)`

---

### Task 7: Agent handoffs, recovery, suspension and fingerprint specs

**Files:**
- Test: new `AgentHandoffSpec`, `AgentRecoverySpec`, `AgentSuspensionSpec`, `AgentFingerprintSpec` in `modules/agent/src/test/scala/org/llm4s/agent/`. Port `HandoffSpec` and `HandoffIntegrationSpec` into `AgentHandoffSpec` and delete them.
- Modify: main sources only to fix what these specs find.

**Interfaces:** consumes Task 6's API only.

- [ ] **Step 1: Write the specs.**
  - `AgentHandoffSpec`:
    - `Handoff.to("physics", physicsBuilder, "...")` runs physics with its own prompt and tools
    - `result.activeAgent == physics`, and the next turn goes to physics
    - a triage ↔ physics cycle builds and runs
    - `preserveContext = false`
    - a handoff whose `id` differs from its target's ID is refused
    - the `Handoff.of` ID validation cases from `HandoffSpec`
  - `AgentRecoverySpec`:
    - two tools, one throwing a non-cancellation `Fatal`: `Left(ToolFailed)`, then `recover` after fixing the tool's behaviour (a flag the test flips) completes, and the succeeding tool ran exactly once
    - `start` then `handle.cancel()` while a tool blocks on a latch gives `Left(Cancelled)`, then `recover` completes
    - `RunBudgets(timeout = Some(50.millis))` against a slow tool gives `DeadlineExceeded`, then `recover` with no timeout completes
    - `StepLimitReached`, then a further `continueConversation` completes
  - `AgentSuspensionSpec`:
    - `ApprovalMiddleware` on a tool gives `Suspended` with one approval
    - `resume(threadId, Map(result.approve(id)))` completes
    - two approvals, resume with one: still `Suspended` with one left
    - `edit` and `reject`
    - an asking tool, answered with `reply`
  - `AgentFingerprintSpec`:
    - the same builders give the same `loop.graph` version across two `build()`s
    - changing the prompt, a tool, a middleware ID or a handoff each changes it
    - Review Focus: run a thread through a handoff, rebuild the root without the handoff on the *same* runtime, and `run` on that thread is a `Left` (the runtime's graph or fingerprint mismatch), not a routing error
- [ ] **Step 2: Run** `sbt "agent/testOnly org.llm4s.agent.Agent*Spec"`. Fix main code for any failure, with the fix in this commit.
- [ ] **Step 3: Run** `sbt scalafmtAll agent/test`.
- [ ] **Step 4: Commit** `test(agent): handoff, recovery, suspension and fingerprint specs for the new Agent (#1328)`

---

### Task 8: `llm4s-effect` and `llm4s-zio`

**Files:**
- Modify: `modules/llm4s-effect/src/main/scala/org/llm4s/effect/cats/AgentIO.scala`, `LLMClientIO.scala`
- Modify: `modules/llm4s-zio/src/main/scala/org/llm4s/zio/AgentZ.scala`, `LLMClientZ.scala`
- Test: their `AgentIO*Spec`/`AgentZ*Spec` and `*ToolValidationSpec`; one new cancellation test each

**Interfaces (produces):**
```scala
trait AgentIO[F[_]]:
  def run(query: String, config: RunConfig = RunConfig()): F[AgentResult]
  def continueConversation(previous: AgentResult, query: String, config: RunConfig = RunConfig()): F[AgentResult]
  def recover(threadId: ThreadId, config: RunConfig = RunConfig()): F[AgentResult]
  def resume(threadId: ThreadId, answers: Map[InterruptId, ujson.Value], config: RunConfig = RunConfig()): F[AgentResult]
object AgentIO:
  def apply[F[_]: Async](agent: Agent): AgentIO[F]
// LLMClientIO: def agent(id: String)(configure: AgentBuilder => AgentBuilder = identity): F[AgentIO[F]]  (build Left raised as LLMException)
// AgentZ / LLMClientZ: the same, ZIO[Any, LLMError, AgentResult]; LLMClientZ.agent returns IO[LLMError, AgentZ]
```
- **Each call starts, then awaits.** In cats-effect: `Async[F].cancelable(F.blocking(handle.await()), F.delay(handle.cancel()))` after `F.blocking(agent.start(...))`. Check that cats-effect version's API for `cancelable` or `onCancel` and use the idiomatic one; `F.interruptible` on a blocked call is not enough. In ZIO: `ZIO.attemptBlocking(handle.await()).onInterrupt(ZIO.succeed(handle.cancel()))`.
- `Left(error)` is raised as `LLMException` (cats) or failed with `error` (ZIO), as today.
- `continueConversation` and `run` that need `start` with a thread use the `Agent.start` overloads from Task 6. For `continueConversation`, use `previous.threadId`.

- [ ] **Step 1: Failing tests.**
  - Port the fidelity and tool-validation specs: replace `continueConversation(first.copy(tools = tools), ...)` with an agent built `withTools(tools)`.
  - Add "fiber cancellation cancels the run": a tool blocking on a latch; cancel the fiber; the tool observes interruption (a flag set in its catch path via `CancelledError.fromThrowable`), and a following `recover` completes.
- [ ] **Step 2: Run, expect compile failure.** `sbt llm4sEffect/test llm4sZio/test`
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `sbt scalafmtAll llm4sEffect/test llm4sZio/test`. Both green.
- [ ] **Step 5: Commit** `feat(effect,zio)!: AgentIO and AgentZ wrap the graph-backed Agent (#1328)`

---

### Task 9: `workspaceClient`, `observability` and `it`

**Files:**
- Modify: `modules/workspace/workspaceClient/src/main/scala/org/llm4s/codegen/CodeWorker.scala`, `CodeGenExample.scala`
- Modify tests: `modules/observability/src/test/.../LangfuseTracingAgentRunSpec.scala`, `LangfuseTracingEdgeCasesSpec.scala`, and any other `observability` spec that `grep -rln "AgentState\|new Agent(\|AgentContext" modules/observability` finds
- Modify: `modules/it/src/test/.../LangfuseSmokeSpec.scala`, `WorkspaceAgentIntegrationSpec.scala`, `AgentMCPServerSpec.scala`

Changes:
- **`CodeWorker`** builds `Agent.builder("codegen", client).withTools(tools).withMaxSteps(maxSteps).build()`, returns `Result[AgentResult]`, and tests `status == AgentStatus.Completed(_)` by pattern. `traceLogPath` goes. If `CodeWorker` exposes it, remove the parameter and say so in the migration note (Task 11).
- **`LangfuseTracingAgentRunSpec`** builds the agent `withTracing(langfuse)` and asserts on the `graph.*` custom events the Langfuse backend receives, not on `AgentStateUpdated`.
- **`LangfuseTracingEdgeCasesSpec`** constructs `TraceEvent.AgentStateUpdated(...)` directly.
- **`AgentMCPServerSpec`** passes its `MCPToolRegistry` to `withTools(registry)`.
- **`it` suites** keep their tier tags; `sbt it/itTierCheck` must still pass.

- [ ] **Step 1: Update the specs** to the new API (they fail to compile until step 2).
- [ ] **Step 2: Implement** the `CodeWorker` change.
- [ ] **Step 3: Run** `sbt scalafmtAll workspaceClient/test observability/test it/Test/compile it/itTierCheck`. Expect all green. The `it` suites compile but are not run here; their tiers need services.
- [ ] **Step 4: Commit** `refactor(workspace,observability,it)!: move to the graph-backed Agent (#1328)`

---

### Task 10: Samples

**Files:** every file `grep -rln "new Agent(\|AgentState\|AgentContext\|runStep\|runWithEvents\|runWithStrategy\|initializeSafe\|writeTraceLog" modules/samples/src` lists (about 33), plus `modules/samples/src/test/.../TracingExampleTest.scala`.

Changes:
- **Mechanical:** `new Agent(client).run(q, tools, ...)` becomes `Agent.builder("<sample-name>", client).withTools(tools)...build().flatMap(_.run(q))`. Guardrail arguments become `.withMiddleware(GuardrailMiddleware(input, output))`, and `AgentStatus.Complete` checks become `status` pattern matches. Reading `.conversation.messages` becomes `.messages`, and reading `.logs` is dropped.
- **The `runStep` samples:** `SingleStepAgentExample`, `MultiStepAgentExample`, `AgentLLMCallingExample` and `PlaywrightExample` become plain runs that print `messages`. `MultiStepAgentExample` also becomes the suspension example: it adds `ApprovalMiddleware` on one tool, prints the pending approval, and resumes with `result.approve(id)`.
- **`ConversationPersistenceExample`** saves `result.messages` with `upickle` to a file, then loads them and runs `agent.run(newThread, q, RunConfig(), history = loaded)`.
- **`ContextPreservationExample`** and the handoff samples (`SimpleTriageHandoffExample`, `MathSpecialistHandoffExample`) use builder targets. `ContextPreservationExample` uses `history` where it built an `AgentState` by hand.
- **The `runMultiTurn` samples** with `ContextWindowConfig` move it to `.withMiddleware(ContextWindowMiddleware(config))`.
- **Delete** the two `runWithEvents` samples and the `runWithStrategy` sample. Remove their entries in `docs/examples/index.md` (or wherever they are listed; grep the sample names under `docs/`), and add one line: "Streaming agent events: see #1329."
- **`TracingExampleTest`** constructs `TraceEvent.AgentStateUpdated` directly.

- [ ] **Step 1: Migrate** the files.
- [ ] **Step 2: Run** `sbt scalafmtAll samples/compile samples/test`. Expect green. Then run `sbt buildAll`; the whole build is green again here.
- [ ] **Step 3: Commit** `refactor(samples)!: move samples to the graph-backed Agent (#1328)`

---

### Task 11: Docs, migration note, design doc

**Files:**
- Modify: `docs/design/typed-agent-runtime-design.md`. Add a new §4.10, "Stage 1: agent loop on the runtime ([#1328](https://github.com/llm4s/llm4s/issues/1328))", in §4.8's style: what changed, bulleted decisions, specs list. Renumber the old §4.10 to §4.11 and fix references to it (`grep -n "4\.10" docs/`). In §4.9's table, mark the rows this slice closes (the `Agent.run` row, the guardrail Block row) as closed by #1328, and add the "Closed by #1328" paragraph in the style of the others.
- Modify: `CLAUDE.md` "Agent Framework" section. Basic usage, multi-turn, guardrails, handoffs and memory examples use `Agent.builder(...)`, `AgentResult` and `continueConversation(result, ...)`. Remove the "Streaming Events" subsection, and put a one-line pointer to #1329 in its place.
- Modify: the `docs/guide` agent pages and any other docs page that `grep -rln "new Agent(\|AgentState\|runWithEvents\|continueConversation(state" docs/ README.md` lists.
- Modify: `CHANGELOG.md`, under the unreleased section. Add one "Stage 1 migration: agent runtime" note with direct replacements for:
  - `new Agent(client).run(q, tools, ...)`
  - per-run guardrails
  - `continueConversation(state, ...)`
  - `runMultiTurn` with `contextWindowConfig`
  - `AgentState` fields (`conversation`, `status`, `logs`, `usageSummary`)
  - `AgentStatus` cases
  - `AgentContext`
  - `Handoff(agent)` becoming `Handoff(id, builder)` and `transferSystemMessage` removed
  - `runStep`, `runWithStrategy` and `ToolExecutionStrategy`
  - `runWithEvents` (pending #1329)
  - session files (`AgentState.saveToFile`/`loadFromFile` becoming saved messages plus `history`)
  - `AgentIO` and `AgentZ`
  - `CodeWorker`

  State that slices 3 and 4 extend the note.
- Run `gh issue comment 1281 --body "Do not cut 0.5.0 between #1328 and #1329: #1328 removes the agent event-streaming API and #1329 adds its replacement."`

- [ ] **Step 1: Write the docs.**
- [ ] **Step 2: Run** `sbt docs/doc` (Scaladoc builds) and `sbt buildAll`.
- [ ] **Step 3: Commit** `docs(agent): Stage 1 agent loop design section, migration note and guides (#1328)`

---

## Verification before the PR

- [ ] `sbt buildAll`, green
- [ ] `sbt "coverage; agent/test; agent/coverageReport"`, at or above 80%, and the effect/zio floors unchanged
- [ ] `sbt docs/doc it/itTierCheck`, green
- [ ] Manual, with Ollama running (`ollama pull llama3`): `sbt "samples/runMain org.llm4s.samples.basic.BasicLLMCallingExample"` and one handoff sample complete
- [ ] `grep -rn "AgentState\b\|AgentContext\|AgentEvent\|runStep\|runWithEvents" modules docs CLAUDE.md` matches nothing but core's `TraceEvent.AgentStateUpdated`, its consumers and the CHANGELOG note
