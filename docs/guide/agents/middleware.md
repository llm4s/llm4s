---
layout: page
title: Writing Middleware
description: "Write your own LLM4S agent middleware: hooks around the turn, model calls and tool calls, state, prompt sections, ordering, errors and tests."
search_title: "Custom agent middleware in Scala: hooks and ordering"
nav_order: 9.6
parent: Agents
grand_parent: User Guide
---

# Writing Middleware
{: .no_toc }

Add a cross-cutting concern to an agent - logging, redaction, a policy on tool calls, context in the
prompt - by wrapping its turn, its model calls and its tool calls.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

Each type of the middleware SPI carries its stability tier as a `@Stable` or `@Experimental` annotation; see
[API Stability](/reference/api-stability).

---

## What middleware is

An `AgentMiddleware` (`org.llm4s.agent.graph.middleware`) is a set of hooks the agent loop calls at fixed
points of every turn. Every hook passes through by default, so a middleware overrides only the ones it
needs. The built-in guardrails, approvals, permissions, context pruning, summarisation and semantic memory are
all middleware.

Write one when the concern applies to every turn or every call, whatever the model decides:

- **A guardrail** is enough when you only check or fix the text going in or the answer coming out. Implement
  `InputGuardrail` or `OutputGuardrail` and give it to `GuardrailMiddleware`; see [Guardrails](guardrails).
- **A tool** is right when the model should decide whether to do something.
- **A handoff** is right when another agent should take the conversation over; see [Handoffs](handoffs).
- **Middleware** is for what sits around those: rewriting or auditing every model request, deciding every
  tool call, adding context to the system prompt, keeping state of its own across a turn, or contributing
  tools together with a prompt that explains them.

## A first middleware

`id` is the only member without a default. This one removes e-mail addresses from the user's text before
it joins the conversation, so the model and the thread's messages never hold them:

```scala
import org.llm4s.agent.graph.middleware.{ AgentMiddleware, BoundaryResult, MiddlewareId, ModelContext }
import org.llm4s.types.Result

final class RedactEmails extends AgentMiddleware:
  val id = MiddlewareId("redact-emails")

  private val Email = """[\w.+-]+@[\w-]+(\.[\w-]+)+""".r

  override def beforeAgent(input: String, context: ModelContext): Result[BoundaryResult] =
    Right(BoundaryResult(Email.replaceAllIn(input, "[email]")))
```

On a durable thread the query as submitted is still in the turn's first checkpoint, which is written before any
hook runs: when sensitive input must never reach storage, redact it before you call the agent.

Attach it with `Agent.builder(...).withMiddleware(new RedactEmails)`. `withMiddleware` appends, in order, to
the agent's stack; the order matters (see [Ordering](#ordering)).

## The hooks

| Hook | When it runs | What it returns |
|---|---|---|
| `beforeAgent(input, context)` | once per turn, on the user's query, before it joins the conversation | `Result[BoundaryResult]`: the query, possibly changed, and a state update |
| `promptSections(context)` | before every model call, to build its system prompt | `Result[Vector[PromptSection]]` |
| `wrapModelCall(request, context)(next)` | around every model call | `Result[ModelResponse]` |
| `wrapToolCall(request, context)(next)` | around every tool call, after its arguments were validated and decoded | `ToolOutcome` |
| `afterAgent(answer, context)` | once per turn, on the final answer | `Result[BoundaryResult]`: the answer, possibly changed, and a state update |

A middleware also declares, with defaults that are empty:

- `writes: Set[StateKey[?, ?]]` - the state keys its hooks may update; see [State](#state).
- `tools: Vector[AgentTool[?]]` - tools it contributes. They join the agent's tool set and are validated like
  any other; a name contributed twice, by one middleware or by two, is refused at `build()`.
- `runsBefore`, `runsAfter` and `placement(other)` - constraints on its position; see [Ordering](#ordering).

### The turn's boundaries

`beforeAgent` runs in stack order, each middleware on the text the previous one returned, before the query is
stored. `BoundaryResult(text, update)` gives the text to store, and an `update` that commits with the query,
so the turn's first model call sees it. `afterAgent` runs in reverse stack order on the final answer; the
text it returns replaces the stored answer, and its update commits with the turn's end. Build a result with
`BoundaryResult(text)` or `BoundaryResult(text, update)`, then `withText`, `withUpdate` (replaces the update)
or `addUpdate` (applies another after it).

A blank query from `beforeAgent`, or a blank answer from `afterAgent`, blocks the run, as a `Left` does (see
[Errors](#errors)).

In an agent family with [handoffs](handoffs), the root agent's boundary hooks guard the whole family: its
`beforeAgent` runs on every turn's query before the active agent's own, and its `afterAgent` on every final
answer after the answering agent's own. A handoff target's boundary hooks apply only while it is active.

### Around a model call

`wrapModelCall` sees a `ModelRequest`: `messages` - the system prompt (the agent's, with every middleware's
prompt sections) followed by the history being sent - and `tools`, the `ToolSet` offered. A wrapper may:

- change the request and pass it on: `next(request.withMessages(...))` or `request.withTools(...)`, as
  `ContextWindowMiddleware` prunes the messages it sends. The stored history is not changed.
- call `next` more than once, to retry or fall back. Every call `next` makes is counted in the thread's usage,
  whichever response the wrapper returns.
- change the response: `response.withCompletion(c)`. The completion the outermost wrapper returns is the one
  the loop checks and stores.
- answer without calling `next`, with `ModelResponse(completion)`.
- add an update to its `writes` with `response.addUpdate(update)`. It commits with the model step; an update
  in a response a wrapper discarded is never committed.
- return `Left`, which fails the run.

Filtering `request.tools` shapes what the model is offered; it is not a permission control. A tool the model
calls anyway still goes through `wrapToolCall`, where denial belongs.

A retry of a transient failure, passing everything else through:

```scala
import org.llm4s.agent.graph.middleware.{ AgentMiddleware, MiddlewareId, ModelContext, ModelRequest, ModelResponse }
import org.llm4s.error.RecoverableError
import org.llm4s.types.Result

final class RetryOnce extends AgentMiddleware:
  val id = MiddlewareId("retry-once")

  override def wrapModelCall(request: ModelRequest, context: ModelContext)(
    next: ModelRequest => Result[ModelResponse]
  ): Result[ModelResponse] =
    next(request) match
      case Left(_: RecoverableError) => next(request)
      case other                     => other
```

A cancellation, a question from a middleware inside and a call refused at the run's token
budget are not `RecoverableError`s, so they pass through as they must. To retry provider errors for every
caller, not only an agent, wrap the client in `ReliableClient` instead (see [Reliability](../reliability)).

### Around a tool call

`wrapToolCall` sees a `ToolCallRequest` - the tool's `spec`, the `call` (its id, name and arguments), and
the `resources` it touches - and a `ToolContext` with the thread's `state`, the call's `idempotencyKey` and
whether it is already `approved`. The request is read-only: a wrapper cannot change a call's arguments. It
returns a `ToolOutcome`:

| Return | Effect |
|---|---|
| `next()` | runs the next wrapper, or the tool, and returns its outcome; transform it before returning if you like |
| `ToolOutcome.Error(message)` without calling `next` | the call is denied: the model sees `message` and the run continues |
| `ToolOutcome.NeedsApproval(reason)` | the turn suspends for a person (unless `context.approved`); see [Human Review](human-review) |
| `ToolOutcome.Success(content, update)` | the call's result, with an update to the middleware's `writes` |
| `ToolOutcome.Fatal(error)` | the run fails with `GraphError.ToolFailed`; `recover` re-runs only that call |

A wrapper may call `next` more than once, to retry. It should pass a `ToolOutcome.Fatal(CancelledError)`
through and not retry it, and pass a question through too - a `ToolOutcome.Ask` - since asking again only
asks again.

A policy that denies some tools and sends others for review:

```scala
import org.llm4s.agent.graph.middleware.{ AgentMiddleware, MiddlewareId, ToolCallRequest }
import org.llm4s.agent.graph.tool.{ ToolContext, ToolOutcome }

final class ToolPolicy(denied: Set[String], reviewed: Set[String]) extends AgentMiddleware:
  val id = MiddlewareId("tool-policy")

  override def wrapToolCall(request: ToolCallRequest, context: ToolContext)(next: () => ToolOutcome): ToolOutcome =
    val name = request.call.name
    if denied.contains(name) then ToolOutcome.Error(s"Denied: '$name' is not available here")
    else if reviewed.contains(name) && !context.approved then ToolOutcome.NeedsApproval(s"'$name' needs review")
    else next()
```

Once a reviewer approves, the whole chain runs again with `context.approved` set, so the policy lets the call
through. For rules over what a call touches (paths, URLs, commands), use `PermissionMiddleware` rather than
writing your own; see [Tool Permissions](index#tool-permissions-timeouts-and-locks).

`wrapToolCall` runs concurrently for the calls of one batch (up to `RunBudgets.maxConcurrency`), on task
threads, so anything a middleware keeps in its own fields must be thread-safe.

### Asking a person

Only a middleware extending `AgentMiddleware.Asking[Q, Ans]` suspends a turn with a question of its own, by
returning `ask(question)` from `beforeAgent`, `afterAgent` or `wrapModelCall`, or `askAbout(question)` from
`wrapToolCall`, and reads the answer with `answered(context)` when the hook runs again. A plain middleware's
`Left` blocks or fails the run instead. See [Questions from middleware](human-review#questions-from-middleware).

## The context a hook sees

`beforeAgent`, `promptSections`, `wrapModelCall` and `afterAgent` get a `ModelContext`:

- `context.state` is the committed `ThreadState`; read a key with `context.state.get(key)`. A hook never sees
  an update made in the same step, its own included: an update is visible from the next one.
- `context.run` is the task's `RunContext`: the run's `config` (its `tenantId`, `budgets`, ...), its
  `position` (`threadId`, `runId`, ...), `emit` and `progress` for events, and `effectKey` and `taskKey` for
  external effects.
- `context.accounted(client)` wraps a client the hook calls a model with itself - or hands to a library that
  does, such as an LLM judge or a summariser - so those calls are counted in the thread's usage and checked
  against the run's token budget. `context.recordUsage(model, usage)` records usage that arrived without a
  client call.

`wrapToolCall` gets the tool's `ToolContext`, whose `run` and `state` are the same.

## State

A middleware keeps what must survive between steps in state keys, never in its own fields: the instance is
shared by every thread and run of the agent, and only state is checkpointed. Declare every key a hook writes
in `writes`; a write to any other key fails the run. Declaring a key registers it with the agent's graph.

This one looks articles up once per turn, keeps them in a key, and shows them to every model call of the turn
as a prompt section:

```scala
import org.llm4s.agent.graph.{ StateKey, StateUpdate }
import org.llm4s.agent.graph.middleware.{ AgentMiddleware, BoundaryResult, MiddlewareId, ModelContext, PromptSection }
import org.llm4s.types.Result

val Articles: StateKey[String, String] = StateKey.replace[String]("kb-articles", "")

final class KnowledgeBase(search: String => String) extends AgentMiddleware:
  val id = MiddlewareId("knowledge-base")

  override def writes: Set[StateKey[?, ?]] = Set(Articles)

  override def beforeAgent(input: String, context: ModelContext): Result[BoundaryResult] =
    Right(BoundaryResult(input, StateUpdate.update(Articles, search(input))))

  override def promptSections(context: ModelContext): Result[Vector[PromptSection]] =
    context.state.get(Articles).map { text =>
      if text.isEmpty then Vector.empty else Vector(PromptSection("kb-articles", s"Relevant articles:\n$text"))
    }

  override def afterAgent(answer: String, context: ModelContext): Result[BoundaryResult] =
    Right(BoundaryResult(answer, StateUpdate.remove(Articles)))
```

The rules for keys:

- Build each key once, in an object or a top-level `val`, not per instance: keys are compared by identity,
  and a graph refuses two distinct keys with the same id.
- `StateKey.replace` keeps the last update, `StateKey.appending` collects every update, and
  `StateKey(id, initial)(applyUpdate)` applies updates of its own type. Updates apply in a deterministic order.
- The loop's own keys (`messages`, `usage`, `turn`, `active-agent`, `transfer`, `tool-results`, `tool-queue`)
  may be read but not declared in `writes`; `build()` refuses it. The stored history is
  `org.llm4s.agent.graph.toolloop.Messages.key`.
- When a guardrail blocks a turn's answer, every key the turn wrote - a middleware's included - returns to its
  value before the turn.

## Prompt sections

`promptSections` is called before every model call, in stack order, from the committed state. The sections
are added to the system prompt of that request and never stored, so a recovered run builds them again from
the same state. Each `PromptSection(id, text, cacheable)`:

- has an `id` unique within the request; two sections with one id fail the run.
- with `cacheable = true` is placed before the other sections, so a provider's prompt cache sees a stable
  prefix. Each group keeps stack order, after the agent's own system prompt.

All sections together, with the blank lines between them, may add at most `maxPromptSectionTokens` estimated
tokens (four characters a token) to a request: 8192 by default, set with
`AgentBuilder.withMaxPromptSectionTokens(n)` (at least 1). A request over it, or with more than 64 sections,
fails the run with a `ValidationError` naming each section's size; nothing is truncated. A `withOutput`
response format's section counts against the same budget. Bound your own section's size, as
`SemanticMemoryMiddleware` bounds its recall.

## Ordering

The first middleware in the stack is the outermost: its `beforeAgent` runs first, its `wrapModelCall` and
`wrapToolCall` wrap all the others, and its `afterAgent` runs last. By default the stack is the order of
`withMiddleware`. A middleware changes that with constraints, which `build()` checks:

- `runsBefore: Set[MiddlewareId]` - middleware this one must wrap; `runsAfter` - middleware that must wrap
  it. Each id must be registered in the same stack. The stack is sorted by these edges, ties broken by
  registration order; a cycle or an unknown id is refused.
- `placement(other): Option[Placement]` - a position this middleware's correctness depends on, relative to
  another found by type. It is checked, never arranged: `Placement.Outside(reason)` refuses a stack in which
  this middleware does not wrap `other`; `Placement.OutsideAtBoundary(reason)` also refuses it on a handoff
  target when the root agent has `other`, since the root's boundary hooks run outside the target's.

```scala
import org.llm4s.agent.graph.middleware.{
  AgentMiddleware,
  ContextWindowMiddleware,
  MiddlewareId,
  Placement
}

final class Metrics extends AgentMiddleware:
  val id = MiddlewareId("metrics")

  // its beforeAgent sees every query, also one the guardrails then block
  override def runsBefore: Set[MiddlewareId] = Set(MiddlewareId("guardrails"))

  override def placement(other: AgentMiddleware): Option[Placement] = other match
    case _: ContextWindowMiddleware => Some(Placement.Outside("it measures the request before pruning"))
    case _                          => None

```

Ids must match `[a-zA-Z0-9_-]{1,64}` and be unique in the stack. Make an id a constructor parameter with a
default, as the built-ins do, so two instances can share an agent. Ids are persisted: in approval
requests, and in the names of the nodes a middleware question waits at. The ids of an agent's middleware, in
order, are part of its graph version, and a thread suspended or `Running` after a crash restores only into
the same version; see [Durable Checkpointers](durable-checkpointers).

A [harness profile](index#harness-profile)'s middleware run outside the builder's own, in a fixed order, and
the `delegation` middleware that offers an agent's [subagents](delegation) runs innermost.

## Errors

| Hook returns | Effect |
|---|---|
| `Left` from `beforeAgent` | the run is blocked: nothing of the turn is stored, and the thread stays usable |
| `Left` from `afterAgent` | the run is blocked: the turn is removed, every key it wrote restored, and its usage kept |
| `Left` from `promptSections` or `wrapModelCall` | the run fails; `recover` runs the model step again |
| `ToolOutcome.Error` from `wrapToolCall` | the model sees the message; the run continues |
| `ToolOutcome.Fatal` from `wrapToolCall` | the run fails with `GraphError.ToolFailed`; `recover` re-runs only that call |
| a thrown exception, from any hook | the run fails with `GraphError.MiddlewareFailed` naming the middleware; a thrown cancellation cancels it |
| an update to a key not in `writes` | the run fails |

A block whose error is a `GuardrailBlocked(guardrail, reason)` ends the turn `AgentStatus.Blocked`; any other
blocking error is returned as the `Left` of `agent.run`. A call through `context.accounted(client)` refused at
the run's token budget ends the turn `AgentStatus.TokenLimitReached` when it was made from `wrapModelCall` or
`promptSections`, whatever the hook does with the refusal.

## Running again: replay and determinism

A hook can run more than once for one step of a turn, and must give the same result each time:

- `recover` re-runs a task that failed, was cancelled or was cut short by a crash; the tasks that finished
  before are not run again.
- After a middleware question is answered, the asking hook's whole stack runs again from the outermost
  middleware, so a middleware outside the asking one runs twice.
- After an approval, a tool call's whole chain runs again, with `context.approved` set.
- An outer wrapper may call `next` more than once.

So:

- Keep what later steps need in state keys. Updates and events commit with the task: a task that fails
  commits neither, and one that runs again commits them once.
- Emit audit or progress records with `emit` (or an `EventType`), which are durable, rather than writing
  them somewhere yourself; see [Streaming Events](streaming).
- Give an external system a key that is the same in every run of the step: `context.run.taskKey` in a
  boundary or model hook, `context.idempotencyKey` in a tool wrapper. Perform an effect on a target that
  takes no key through `ReplayGuard`, keyed with `context.run.effectKey(operation, discriminator*)`; see
  [Tool Side Effects](index#tool-side-effects).
- Build prompt sections from state, not from fields that change, since they are rebuilt for every model
  call.
- A guardrail block restores thread state, not an external system: an effect that applied stays applied.

## A complete example: an audit trail

This middleware records every hook as a durable custom event, so the trail is stored with the thread,
replayed to later subscribers, and never duplicated by a `recover`:

```scala
import org.llm4s.agent.Agent
import org.llm4s.agent.graph.{ EventType, ThreadId }
import org.llm4s.agent.graph.middleware.{
  AgentMiddleware,
  BoundaryResult,
  MiddlewareId,
  ModelContext,
  ModelRequest,
  ModelResponse,
  ToolCallRequest
}
import org.llm4s.agent.graph.tool.{ ToolContext, ToolOutcome }
import org.llm4s.types.Result
import upickle.default.ReadWriter

final case class AuditEntry(hook: String, detail: String) derives ReadWriter

val Audited = EventType[AuditEntry]("myapp.audit", 1)

final class AuditTrail(val id: MiddlewareId = MiddlewareId("audit")) extends AgentMiddleware:

  override def beforeAgent(input: String, context: ModelContext): Result[BoundaryResult] =
    Audited.emit(context.run, AuditEntry("beforeAgent", s"query of ${input.length} characters"))
    Right(BoundaryResult(input))

  override def wrapModelCall(request: ModelRequest, context: ModelContext)(
    next: ModelRequest => Result[ModelResponse]
  ): Result[ModelResponse] =
    val result = next(request)
    val outcome = result.fold(
      error => s"failed: ${error.message}",
      response => s"${response.completion.model}, ${response.completion.message.toolCalls.size} tool calls"
    )
    Audited.emit(context.run, AuditEntry("wrapModelCall", s"${request.messages.size} messages sent; $outcome"))
    result

  override def wrapToolCall(request: ToolCallRequest, context: ToolContext)(next: () => ToolOutcome): ToolOutcome =
    val outcome = next()
    val kind = outcome match
      case _: ToolOutcome.Success       => "succeeded"
      case ToolOutcome.Error(message)   => s"error: $message"
      case _: ToolOutcome.NeedsApproval => "needs approval"
      case _: ToolOutcome.Ask[?]        => "asked a question"
      case _: ToolOutcome.Fatal         => "fatal"
    Audited.emit(context.run, AuditEntry("wrapToolCall", s"${request.call.name}: $kind"))
    outcome

  override def afterAgent(answer: String, context: ModelContext): Result[BoundaryResult] =
    Audited.emit(context.run, AuditEntry("afterAgent", s"answer of ${answer.length} characters"))
    Right(BoundaryResult(answer))

val audited = for
  agent <- Agent.builder("assistant", client).withMiddleware(new AuditTrail()).build()
  run <- agent.stream(ThreadId("audit-demo"), "What is 2 + 2?") {
    case Audited(entry) => println(s"${entry.hook}: ${entry.detail}")
    case _              => ()
  }
  result <- run.await()
yield result
```

Notes on what it relies on:

- It declares no `writes`: events are not state.
- It emits only sizes and names, never message content, so the trail holds no personal data.
- Its `wrapToolCall` runs on several threads at once for a batch of calls; `emit` is safe to call from each.
- Registered first, it wraps every other middleware: its `wrapModelCall` sees the request before any other
  wrapper changes it, and the response after all of them.

## Built-in middleware

| Middleware | Hooks it uses | Guide |
|---|---|---|
| `GuardrailMiddleware` | `beforeAgent`, `afterAgent`: runs input and output guardrails, blocks on failure | [Guardrails](guardrails) |
| `GuardrailReviewMiddleware` | as `GuardrailMiddleware`, but asks a reviewer instead of blocking | [Human Review](human-review#reviewing-what-guardrails-refuse) |
| `ApprovalMiddleware` | `wrapToolCall`: asks for approval when its function gives a reason | [Human Review](human-review#five-ways-a-turn-waits) |
| `PermissionMiddleware` | `wrapToolCall`: allows, denies or asks by rules over each call's resources | [Tool Permissions](index#tool-permissions-timeouts-and-locks) |
| `ContextWindowMiddleware` | `wrapModelCall`: prunes the messages sent, not the stored history | [Context Window Management](index#context-window-management) |
| `SummarizationMiddleware` | `wrapModelCall`: replaces older messages with a summary kept in the thread | [Summarisation](index#summarisation) |
| `ToolOutputOffload` | `wrapToolCall`, `wrapModelCall`: keeps large tool results out of the conversation | [Large tool results](index#large-tool-results) |
| `TodoMiddleware` | `tools`, `promptSections`: a `write_todos` tool and the plan in the prompt | [Todos](index#todos) |
| `SkillsMiddleware` | `promptSections`: lists the agent's skills | [Skills](index#skills) |
| `InstructionMemoryMiddleware` | `promptSections`: shows `AGENTS.md` files | [Instruction Memory](index#instruction-memory) |
| `SemanticMemoryMiddleware` (`llm4s-agent-memory`) | `beforeAgent`, `promptSections`, `afterAgent`: recalls and records long-term memory | [Semantic Memory Middleware](memory#semantic-memory-middleware) |

Their sources, in `modules/agent` and `modules/agent-memory`, are the best examples of each hook.

## Attaching middleware

```scala
import org.llm4s.agent.Agent
import org.llm4s.agent.graph.middleware.{ ApprovalMiddleware, GuardrailMiddleware }
import org.llm4s.agent.guardrails.builtin.LengthCheck

val built = Agent
  .builder("assistant", client)
  .withMiddleware(
    ApprovalMiddleware.unlessReadOnly,                                   // outermost
    new GuardrailMiddleware(input = Seq(new LengthCheck(1, 10000)), output = Nil)
  )
  .withMaxPromptSectionTokens(2048)
  .build()
```

Middleware belong to the agent, not to a run: give each agent of a family its own with its own builder.
`build()` returns a `Left(ValidationError)` listing every problem with a stack: an invalid or duplicate id, a
constraint naming an unknown middleware, a cycle, a clashing tool name, a misplaced middleware, or a key the
loop owns in `writes`.

## Testing a middleware

A hook's contexts are built by the agent loop, so test a middleware through an agent: script the model with
`ScriptedLLMClient` from `llm4s-testkit` (see [Testing with the testkit](../testing-with-the-testkit)), run a
turn, and check what the model was sent and what the turn returned:

```scala
import org.llm4s.agent.Agent
import org.llm4s.agent.graph.middleware.{ AgentMiddleware, BoundaryResult, MiddlewareId, ModelContext, PromptSection }
import org.llm4s.llmconnect.model.SystemMessage
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.types.Result

val houseStyle = new AgentMiddleware {
  val id = MiddlewareId("house-style")
  override def promptSections(context: ModelContext): Result[Vector[PromptSection]] =
    Right(Vector(PromptSection("style", "Answer in one sentence.")))
  override def afterAgent(answer: String, context: ModelContext): Result[BoundaryResult] =
    Right(BoundaryResult(s"$answer (automated reply)"))
}

val scripted = ScriptedLLMClient.sequence(Reply.text("Paris."))
val checked = for
  agent  <- Agent.builder("assistant", scripted).withMiddleware(houseStyle).build()
  result <- agent.run("What is the capital of France?")
yield
  val system = scripted.calls.head.messages.collect { case s: SystemMessage => s.content }
  assert(system == Seq("Answer in one sentence."))
  assert(result.answer.contains("Paris. (automated reply)"))
```

- `scripted.calls` records every request, so a `wrapModelCall` that rewrites messages or filters tools is
  checked on `calls(n).messages` and `calls(n).options`.
- A tool wrapper is tested with a scripted tool call: `Reply.toolCall("name", """{"arg": 1}""")`, then
  `Reply.text(...)`; the tool's result, or your denial, is the `ToolMessage` in the second call's messages.
- Test ordering and constraints by building an agent: `Agent.builder(...).withMiddleware(a, b).build()` returns
  the `ValidationError` for a cycle, an unknown id or a refused placement, with no model call.
- Test that a hook gives the same result when it runs again: script a failure (`Reply.failure(error)`) so the
  run fails, then `agent.recover(threadId)` and check the thread and the calls.
