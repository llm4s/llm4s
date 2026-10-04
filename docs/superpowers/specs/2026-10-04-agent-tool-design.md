# Agent tool contract - design (#1278)

Slice 2 of [#1271](https://github.com/llm4s/llm4s/issues/1271), Stage 0 of the typed agent runtime
([#1266](https://github.com/llm4s/llm4s/issues/1266)). Module `llm4s-agent`. Builds on #1277's
`RunContext`. Slice 3 ([#1279](https://github.com/llm4s/llm4s/issues/1279), `AgentMiddleware`) builds
on the types defined here.

## Goal

Replace the prototype `LoopTool` with the agent tool contract that Stage 1's `Agent` and #1279's
middleware build on:

- `AgentTool[A]` and `AgentToolSpec[A]`: typed, validated arguments; `RunContext` and thread state
  in; content, state updates, approval requests, typed questions and failures out, as data.
- An agent-local `ToolArgumentValidator` that checks raw arguments against the exact schema sent to
  the provider, before policy or any side effect, and refuses unsupported constraints when a tool set
  is built.
- A tool-level failure (the model sees it) distinct from an infrastructure failure (the run fails).
- A `ToolFunction` adapter, so core's stateless tools run in the loop.
- Stable, explicit handoff IDs.
- The stop handshake carried forward from #1277.

Nothing in `llm4s-core` changes. Nothing is frozen: replaced API is deleted, with migration notes.

## Decisions

| Question | Decision |
|---|---|
| What a tool may do | Return content and a state update to keys it declares, ask for approval, ask a typed question, report a tool-level error, or fail the run. No routing. |
| Validation | An in-house validator for exactly the JSON Schema subset core's `SchemaDefinition` emits, behind a `ToolArgumentValidator` trait. Unsupported keywords are refused by `ToolSet.of`, never at call time. Violations become one tool-level error listing every violation by JSON path. |
| Questions | Approval stays built in (`NeedsApproval`, `ToolContext.approved`, the loop's approval node). Other questions are declared on the spec (`withQuestion[Q, A]`), returned as `Ask(q)`, and answered through `resume`. A tool cannot hold a builder-issued `ResumeRef`, so the §4 sketch's `Suspend(question, resumeAt)` is replaced. |
| Handoff IDs | Explicit and required: `Handoff.to(id, agent, ...)`, tool name `handoff_to_<id>`. |
| Policy metadata | Side-effect class, permissions, timeout and idempotency metadata are deferred to #1279, which adds them with the middleware that reads them. |

## Types (package `org.llm4s.agent.graph.tool`)

```scala
final case class AgentToolSpec[A] private (
  name: String,
  description: String,
  schema: SchemaDefinition[A],
  validateDecoded: A => Result[Unit],
  question: Option[ToolQuestion[?, ?]]
)(using val codec: ReadWriter[A]):
  def withValidation(check: A => Result[Unit]): AgentToolSpec[A]
  private[tool] def withQuestion[Q: ReadWriter, Ans: ReadWriter]: AgentToolSpec[A]   // only Asking; see "Typed questions"
  def providerSchema: ujson.Value      // schema.toJsonSchema(strict = true), rendered once; the validator's input
  def toolDefinition: ujson.Value      // same shape as ToolFunction.toOpenAITool(true), with a copy of providerSchema

object AgentToolSpec:
  def apply[A: ReadWriter](name: String, description: String, schema: SchemaDefinition[A]): AgentToolSpec[A]
```

- `name` must match `[a-zA-Z0-9_-]{1,64}`. `apply` throws `IllegalArgumentException` for an invalid
  name (a programming error); `ToolSet.of` reports it as a `Left` too, so a tool built another way is
  still checked. `copy` is private (repo pattern for growth-prone types).
- There is no `strict` field: core's clients always send tools strict (`ToolRegistry.getOpenAITools()`
  defaults to it; the others convert that schema), so the schema is always rendered strict.
  `toolDefinition` embeds a copy of `providerSchema`, because clients rewrite schemas in place (such
  as removing `additionalProperties`), which would otherwise loosen validation.

```scala
final case class ToolQuestion[Q, Ans](questionCodec: ReadWriter[Q], answerCodec: ReadWriter[Ans])

final case class ToolContext(run: RunContext, toolCallId: String, state: ThreadState, approved: Boolean)

enum ToolOutcome:
  case Success(content: ujson.Value, update: StateUpdate = StateUpdate.empty)
  case Error(message: String)
  case NeedsApproval(reason: String)
  case Ask[Q](question: Q)
  case Fatal(error: LLMError)

trait AgentTool[A]:
  def spec: AgentToolSpec[A]
  def writes: Set[StateKey[?, ?]] = Set.empty
  def execute(args: A, context: ToolContext): ToolOutcome
  private[tool] def resumeErased(args: A, question: Any, answer: Any, context: ToolContext): ToolOutcome

object AgentTool:
  def apply[A](spec: AgentToolSpec[A], writes: Set[StateKey[?, ?]] = Set.empty)(run: (A, ToolContext) => ToolOutcome): AgentTool[A]
  def fromToolFunction(tool: ToolFunction[?, ?]): AgentTool[ujson.Value]
```

### Typed questions

A tool that asks questions extends `AgentTool.Asking[A, Q, Ans]`, which fixes the question and answer
types and supplies the codecs to its spec:

```scala
abstract class Asking[A, Q: ReadWriter, Ans: ReadWriter] extends AgentTool[A]:
  def resume(args: A, question: Q, answer: Ans, context: ToolContext): ToolOutcome
  protected final def ask(question: Q): ToolOutcome   // ToolOutcome.Ask(question)
  // implements resumeErased by casting through the declared codecs; spec.question is Some(ToolQuestion[Q, Ans])
```

A plain `AgentTool` that returns `Ask` without declaring a question is a tool bug: the loop turns it
into `Fatal(GraphError.ToolFailed(...))` ("tool 'x' asked a question it does not declare"). Tool
authors never see `Any`.

### ToolSet

```scala
final class ToolSet private (val tools: Vector[AgentTool[?]], validator: ToolArgumentValidator):
  def get(name: String): Option[AgentTool[?]]
  def definitions: Vector[ujson.Value]          // provider-facing tool definitions, in order
  def toolFunctions: Seq[ToolFunction[?, ?]]    // the bridge to CompletionOptions.tools, in order
object ToolSet:
  def of(tools: AgentTool[?]*): Result[ToolSet]
  def of(validator: ToolArgumentValidator, tools: AgentTool[?]*): Result[ToolSet]
  val empty: ToolSet
```

`of` refuses, with one `ValidationError` listing every problem: an invalid tool name; duplicate names;
any keyword in a tool's `providerSchema` that `validator.unsupported` reports.

`toolFunctions` is what the loop passes as `CompletionOptions.tools`: a tool made by
`AgentTool.fromToolFunction` is its original `ToolFunction`; any other is a stand-in with the spec's
name, description and schema whose handler fails ("executed by ToolLoop, not directly"). Their
`toOpenAITool(true)` equals `definitions`.

### ToolArgumentValidator

```scala
trait ToolArgumentValidator:
  /** Keywords this validator cannot check, as JSON paths into `schema` (empty when fully supported). */
  def unsupported(schema: ujson.Value): Vector[String]
  /** Every violation of `schema` by `arguments`, as messages prefixed with a JSON path (`$.limit: ...`). */
  def validate(schema: ujson.Value, arguments: ujson.Value): Vector[String]

object ToolArgumentValidator:
  val default: ToolArgumentValidator   // the in-house subset validator
```

The default supports exactly: `type` (a string, or an array such as `["string","null"]`),
`description` (ignored), `properties`, `required`, `additionalProperties` (boolean), `enum`,
`minLength`, `maxLength`, `minimum`, `maximum`, `exclusiveMinimum`, `exclusiveMaximum`, `multipleOf`,
`items`, `minItems`, `maxItems`, `uniqueItems`. Any other keyword is unsupported. Types: `string`,
`number` (any JSON number), `integer` (a number with no fractional part), `boolean`, `array`,
`object`, `null`. Lengths count Unicode code points. `multipleOf` on non-integers uses a relative
tolerance of 1e-9. Messages: `$.path: <what is wrong>`, e.g. `$.limit: 500 is above maximum 100`,
`$.query: required property missing`, `$.extra: property not allowed`.

## The call pipeline

Each tool-call task (the `call-tool` node), in order. Steps 1-4 produce only an error result the
model sees; none runs a tool or fails the run.

1. **Look up** the tool by name: unknown → `Error("Unknown tool 'x'")`.
2. **Validate** the raw arguments against `spec.providerSchema` with the set's validator: violations →
   `Error("Invalid arguments for 'x': <violation>; <violation>")`. The validator is pluggable, so a
   validator that throws → `Error("Invalid arguments for 'x': <exception message>")`.
3. **Decode** with `spec.codec`: failure → `Error("Invalid arguments for 'x': <decode message>")`.
4. **`validateDecoded`**: `Left(e)` → `Error("Invalid arguments for 'x': <e.message>")`.
5. **Policy** (`ToolCallPolicy` until #1279): `Deny` → `Error("Denied: ...")`; `RequireApproval` →
   suspend at the approval node.
6. **Execute** and map the outcome:
   - `Success(content, update)`: the call's result is `content.render()` (a `ujson.Str` renders its
     string value, not a quoted JSON string); `update` commits with the superstep. An update touching a
     key outside the tool's `writes` → `Fatal(GraphError.ToolFailed(tool, callId, cause))`, where `cause`
     is a `ValidationError` naming the undeclared keys.
   - `Error(msg)`: an error result, rendered `{"error": msg}` as today.
   - `NeedsApproval(reason)`: suspend at the approval node; if the call was already approved → error
     result "Tool 'x' asked for approval again: ...". If `resume` returns it after a question → error
     result "Tool 'x' asked for approval after a question: ...", because an approval would run
     `execute` again and lose the answer.
   - `Ask(q)`: encode `q` with the declared question codec and suspend at that tool's resume node
     (`ask/<tool name>`, one per asking tool). The question carried in the interrupt is a
     `ToolQuestionRequest(assistantMessageId, call, question: ujson.Value, approved: Boolean = false)`;
     `approved` records the asking call's `ToolContext.approved`, and `resume` sees the same value, so
     an approved call that asks is not asked for approval again. The resume node decodes the answer,
     re-decodes the original arguments from the stored call (no re-validation), and calls `resume`;
     its outcome is handled by this same step, so a second `Ask` is allowed. An answer that does not
     decode with the tool's answer codec becomes an error result for that call ("Invalid answer for
     'x': ..."); the resume itself is not refused, since the graph-level answer type is JSON.
   - An `Ask` from a tool that declares no question, or whose question does not encode with the
     declared codec, → `Fatal(GraphError.ToolFailed(tool, callId, cause))`, where `cause` is a
     `ValidationError` ("tool 'x' asked a question it does not declare").
   - `Fatal(error)`: the task fails with `GraphError.ToolFailed(tool, callId, error)`, which the run
     reports as `GraphError.NodeFailed(call-tool, task, ToolFailed(...))` (the graph wraps every node
     failure); the run fails with its checkpoint `Running`; `recover` re-runs only that call task. Tools that return `Fatal`
     after a side effect must be idempotent; `(run.position.threadId, run.position.checkpointId,
     toolCallId)` is the key.
   - A thrown NonFatal exception → error result `"Tool 'x' failed: <message>"`. Throwing never fails
     the run; `Fatal` does.
   - An interrupt (`CancelledError` or `InterruptedException`) follows #1270: the task records nothing
     and the run is cancelled. This includes a thrown exception that `CancelledError.fromThrowable`
     classifies as a cancellation (an interrupt wrapped in another exception, or any throw while the
     flag is set) and `Fatal(e: CancelledError)`: the loop restores the interrupt flag and the task is
     cancelled, not given an error result and not failed with `ToolFailed`.
7. **Edited approval** (`ApprovalDecision.Edit(arguments)`): the assistant message is amended first, as
   today, then the edited arguments go through steps 2-5 again before execution. In step 5 only `Deny`
   refuses an edit; `RequireApproval` counts as satisfied, because the reviewer has just approved
   these arguments. A plain `Approve` re-runs steps 2-4 but does not consult the policy again.

Exactly one result per call, written by the loop, is unchanged from #1269.

`ToolLoop.build(id, version, model, tools: ToolSet, policy = ToolCallPolicy.allowAll)`. The
`call-tool`, approval and `ask/<name>` nodes declare `writes = results ∪ messages ∪` the union of the
tools' `writes`; the per-tool check in step 6 enforces each tool's own set. `ToolLoop` exposes typed
helpers to read pending questions and answer them, alongside the existing approval helpers. `build`
refuses, with a `ValidationError` naming each offending tool, a tool whose `writes` contains
`ToolLoop.results` or `Messages.key`: the loop alone writes those, so that every call gets exactly
one result.

### `AgentTool.fromToolFunction`

Adapts a core `ToolFunction[T, R]` as `AgentTool[ujson.Value]`: the spec uses the function's name,
description and schema (erased to `SchemaDefinition[ujson.Value]`), rendered strict, so its arguments
are validated like any other tool's; `execute` calls `tool.execute(arguments)`. `Right(json)` →
`Success(json)`; `Left(error)` → `Error(error.getFormattedMessage)`. No state, never asks.

## Handoffs (legacy `org.llm4s.agent`)

- `Handoff(id: String, targetAgent: Agent, transferReason: Option[String] = None, preserveContext:
  Boolean = true, transferSystemMessage: Boolean = false)`.
- `Handoff.to(id, agent)` and `Handoff.to(id, agent, reason)` throw `IllegalArgumentException` for an
  invalid id; `Handoff.of(id, agent, reason = None): Result[Handoff]` returns `Left(ValidationError)`.
  Valid: `[a-zA-Z0-9_-]{1,52}`, so `handoff_to_<id>` fits the 64-character tool-name limit.
- `handoffId` is `s"handoff_to_$id"`. `handoffName` uses the reason, else `s"Handoff to $id"`.
- An agent run given two handoffs with the same id fails with `ValidationError` before any model
  call.
- `AgentState` and `HandoffExecutor` keep matching on `handoffId`, now stable across processes.

## Stop handshake (carried forward from #1277)

`DefaultRunHandle.stop` records the cause and interrupts in two steps; `Run.cancelled()` can clear
the flag between them, so the interrupt lands on the closing commit. Fix: a lock-guarded
`acknowledged` flag on the handle. `stop` records the cause (CAS), then, holding the lock, interrupts
only if `!acknowledged`. `cancelled()` takes the lock and sets `acknowledged` before it clears the
interrupt flag. No interrupt can arrive after acknowledgement. The design doc §4.8 row (currently
§4.7) closes.

## Migration

- `LoopTool` → `AgentTool[A]` (`AgentTool(spec)(fn)`); `LoopTool.fromToolFunction` →
  `AgentTool.fromToolFunction`.
- `toolloop.ToolOutcome` (`Completed`, `Failed`, `NeedsApproval`) → `tool.ToolOutcome` (`Success`,
  `Error`, `NeedsApproval`, `Ask`, `Fatal`).
- `ToolLoop.build(..., tools: Seq[LoopTool], ...)` → `ToolLoop.build(..., tools: ToolSet, ...)`.
- `Handoff(agent, ...)` / `Handoff.to(agent, ...)` → `Handoff(id, agent, ...)` /
  `Handoff.to(id, agent, ...)`; `handoffId` is `handoff_to_<id>`.

## Design doc changes (`docs/design/typed-agent-runtime-design.md`)

- New §4.7 "Stage 0 prototype: agent tool contract ([#1278](...))", in the style of §4.4-4.6. The
  carry-forward becomes §4.8 and the durable workflow API §4.9; fix cross-references.
- Carry-forward: the `AgentTool` row and the stop-handshake row close.
- §4 sketch and §5.2: `ToolOutcome` as above (`Ask` replaces `Suspend`; no routing); `ToolContext`
  carries state and `approved`; policy metadata deferred to #1279.

## Tests

- `ToolArgumentValidatorSpec`: each supported keyword accepted and refused; nested objects, arrays,
  nullable type arrays; `strict` and non-strict `required`; several violations reported together with
  paths; code-point lengths; integer vs number; `multipleOf` with decimals; `unsupported` reports
  unknown keywords by path; a generated case per `SchemaDefinition` constructor proving every keyword
  core emits is supported.
- `ToolSetSpec`: invalid names, duplicates, unsupported keywords - all reported in one error.
- `ToolLoopSpec` (ported and extended): invalid arguments never reach policy or the tool (counting
  fakes); decode and `validateDecoded` failures; `Success` with an update; an undeclared key → run
  fails `ToolFailed`; `Fatal` → run fails, `recover` re-runs only that call; a thrown exception → error
  result; `Ask` answered and resumed, then a second `Ask`; an undeclared `Ask` → `ToolFailed`; edited
  approval arguments re-validated; `fromToolFunction` round trip.
- `HandoffSpec`: id validation; duplicates refused; `handoffId` equal for two instances with the same id.
- `RunHandleSpec`: the stop handshake - a gate forces stop's interrupt after `cancelled()`
  acknowledges; the closing commit is not interrupted and the run reports `Cancelled`.
