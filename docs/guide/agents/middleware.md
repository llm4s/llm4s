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

> **Experimental at 1.0.** The middleware SPI on this page (`AgentMiddleware`, its request, response and context types, `withMiddleware` and the built-in middleware) is `@Experimental`: usable, but outside the 1.x compatibility promise, so it can change in a minor release, with a migration note. The stable core of `llm4s-agent` - building an agent, running, continuing and recovering a thread, handoffs and guardrails (`withInputGuardrails`, `withOutputGuardrails`) - is listed in [API Stability](../../reference/api-stability#tiers-in-the-code).

---

## What middleware is

An `AgentMiddleware` (`org.llm4s.agent.graph.middleware`) is a set of hooks the agent loop calls at fixed
points of every turn. Every hook passes through by default, so a middleware overrides only the ones it
needs. The built-in guardrails, approvals, permissions, context pruning, summarisation and semantic memory are
all middleware.

Write one when the concern applies to every turn or every call, whatever the model decides:

- **A guardrail** is enough when you only check or fix the text going in or the answer coming out. Implement
  `InputGuardrail` or `OutputGuardrail` and give it to `AgentBuilder.withInputGuardrails` or
  `withOutputGuardrails`; see [Guardrails](guardrails).
- **A tool** is right when the model should decide whether to do something.
- **A handoff** is right when another agent should take the conversation over; see [Handoffs](handoffs).
- **Middleware** is for what sits around those: rewriting or auditing every model request, deciding every
  tool call, adding context to the system prompt, keeping state of its own across a turn, or contributing
  tools together with a prompt that explains them.

## A first middleware

`id` is the only member without a default. This one removes e-mail addresses from the user's text as the turn
is admitted, so the model, the thread's messages and the checkpoint store never hold them:

```scala
import org.llm4s.agent.graph.middleware.{ AdmissionContext, AgentMiddleware, MiddlewareId }
import org.llm4s.types.Result

final class RedactEmails extends AgentMiddleware:
  val id = MiddlewareId("redact-emails")

  private val Email = """[\w.+-]+@[\w-]+(\.[\w-]+)+""".r

  override def admit(query: String, context: AdmissionContext): Result[String] =
    Right(Email.replaceAllIn(query, "[email]"))
```

`admit` runs before anything of the turn is stored, so the query as submitted is in no checkpoint, event or trace.
The same rewrite in `beforeAgent` would reach the model and the thread's messages, but too late for the store: the
turn's first checkpoint, which holds the query, is written before `beforeAgent` runs. See [Admission](#admission).

Attach it with `Agent.builder(...).withMiddleware(new RedactEmails)`. `withMiddleware` appends, in order, to
the agent's stack; the order matters (see [Ordering](#ordering)).

## The hooks

| Hook | When it runs | What it returns |
|---|---|---|
| `admit(query, context)` | once per turn, on the user's query, before anything of the turn is stored | `Result[String]`: the query, possibly rewritten |
| `beforeAgent(input, context)` | once per turn, on the admitted query, before it joins the conversation | `Result[BoundaryResult]`: the query, possibly changed |
| `promptSections(context)` | before every model call, to build its system prompt | `Result[Vector[PromptSection]]` |
| `wrapModelCall(request, context)(next)` | around every model call | `Result[ModelResponse]`: the completion |
| `wrapToolCall(request, context)(next)` | around every tool call, after its arguments were validated and decoded | `ToolOutcome`: what a tool returns |
| `afterAgent(answer, context)` | once per turn, on the final answer | `Result[BoundaryResult]`: the answer, possibly changed |

Two things are the same for every hook but `admit`, which returns only the query:

- **A state update** travels in the hook's result: build the result with it, or add it with `addUpdate`. See
  [State](#state).
- **A failure** is a value of the hook's return type - a `Left`, or `ToolOutcome.Fatal` from `wrapToolCall` -
  and a throw becomes that value. See [Errors](#errors).

A middleware also declares, with defaults that are empty:

- `writes: Set[StateKey[?, ?]]` - the state keys its hooks may update; see [State](#state).
- `tools: Vector[AgentTool[?]]` - tools it contributes. They join the agent's tool set and are validated like
  any other; a name contributed twice, by one middleware or by two, is refused at `build()`.
- `runsBefore`, `runsAfter` and `placement(other)` - constraints on its position; see [Ordering](#ordering).

### Admission

`admit` decides whether a turn's query is taken at all, and as what. It runs in stack order, each middleware on the
query the previous one returned, before every `beforeAgent` and before the runtime stores anything of the turn. It
is the one place that can keep text out of storage:

| `admit` returns | The turn | What is stored |
|---|---|---|
| `Right(query)` | runs with that query | only the query returned: in the first checkpoint, the thread's messages, a queue entry |
| `block(reason)` | ends `AgentStatus.Blocked(id, Some(reason))` | no query. The blocked run is on record by name: its `agent.guardrail_blocked` event and its closing checkpoint |
| any other `Left`, a throw, a blank query | does not start: `agent.run` returns the `Left` | nothing, and the thread is unchanged |

It can decide and rewrite, and nothing else. Its `AdmissionContext` gives the turn's `threadId`, `tenantId` and
`principal`, the `agent` whose stack the stage is in, and `accounted(client)` for a model call of its own (an LLM
judge's is counted in the thread's usage, and announced, with the turn's first step). There is no thread state, no
update to return, no event to emit and no tool to call, and a middleware that asks a question from `admit` is refused:
a stage that needs any of those belongs in `beforeAgent`.

It also runs before the thread is claimed. A turn the runtime then refuses - a busy thread, a pending interrupt,
another tenant's thread - has had its stages run all the same, and a model call a stage made for it is counted
nowhere, since there is no turn to count it in.

Input guardrails run here: `withInputGuardrails` and a `GuardrailMiddleware`'s input side are `admit` stages. So a
guardrail sees the query as submitted, after the `admit` stages of the middleware outside it, and never what a
`beforeAgent` hook makes of it afterwards.

When the turn is stored before the agent sees it, `admit` cannot run first.

**A queued run.** `QueuedInput.query(query)` puts the query in the run queue as you submit it. Admit it first, with
the agent, and queue the result:

```scala
import org.llm4s.agent.{ AdmittedQuery, Agent }
import org.llm4s.agent.graph.ThreadId
import org.llm4s.agent.service.{ QueuedInput, RunSubmitter, Ticket }
import org.llm4s.types.Result

def submit(agent: Agent, submitter: RunSubmitter, threadId: ThreadId, query: String): Result[Option[Ticket]] =
  agent.admit(threadId, query).flatMap {
    // refused: the reason is here and nowhere else. Queue it instead to record the blocked turn on the thread
    case AdmittedQuery.Refused(_, _, _) => Right(None)
    case accepted => submitter.submit(threadId, agent.id, QueuedInput.admitted(accepted)).map(Some(_))
  }
```

The entry then holds the query only as admitted, and the worker that takes it does not run the stages again. A query
queued as it is, is admitted by the worker: it is kept out of the checkpoint store, and stays in the queue entry until
the entry is reclaimed.

**A delegated task.** A subagent's query is its parent's tool call, already in the parent's thread. The child's
`admit` stages run as the first thing of its input step, with the same verdicts.

### The turn's boundaries

`beforeAgent` runs in stack order, each middleware on the text the previous one returned, after the turn's first
checkpoint - which holds the admitted query - is stored and before the query joins the conversation.
`BoundaryResult(text)` gives the text to add to the thread's messages. `afterAgent` runs in reverse stack order on the final
answer; the text it returns replaces the stored answer.

A boundary hook can end the turn: return `block(reason)` and the turn ends `AgentStatus.Blocked` with the
middleware's id and your reason, as a guardrail's block does. A blank query from `beforeAgent`, or a blank
answer from `afterAgent`, blocks the turn too (see [Errors](#errors)).

In an agent family with [handoffs](handoffs), the root agent's boundary hooks guard the whole family: its
`admit` and `beforeAgent` run on every turn's query before the active agent's own, and its `afterAgent` on every
final answer after the answering agent's own. A handoff target's `admit` and boundary hooks apply only while it is
active.

### Around a model call

`wrapModelCall` sees a `ModelRequest`:

- `systemPrompt: Option[String]` - the prompt as assembled for this request: the agent's, then every
  middleware's [prompt sections](#prompt-sections). It is a field of its own, never one of the messages, so
  rewriting the messages cannot drop it.
- `messages` - the history being sent, without the system prompt.
- `tools` - the `ToolSet` offered.

`request.sentMessages` is what the model receives: the system prompt as a `SystemMessage`, then `messages`.
A wrapper may:

- change the request and pass it on: `next(request.withMessages(...))`, `request.withTools(...)` or
  `request.withSystemPrompt(...)`, as `ContextWindowMiddleware` prunes the messages it sends. The stored
  history is not changed.
- call `next` more than once, to retry or fall back. Every call `next` makes is counted in the thread's usage,
  whichever response the wrapper returns.
- change the response: `response.withCompletion(c)`. The completion the outermost wrapper returns is the one
  the loop checks and stores.
- answer without calling `next`, with `ModelResponse(completion)`.
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
whether it is already `approved`. The request is read-only, and `next` takes none: the arguments a tool runs
with are the ones that were validated, and the resources are the ones a permission was decided on. The wrapper
returns a `ToolOutcome`, as a tool does:

| Return | Effect |
|---|---|
| `next()` | runs the next wrapper, or the tool, and returns its outcome; transform it before returning if you like |
| `ToolOutcome.Error(message)` without calling `next` | the call is denied: the model sees `message` and the run continues |
| `ToolOutcome.NeedsApproval(reason)` | the turn suspends for a person (unless `context.approved`); see [Human Review](human-review) |
| `ToolOutcome.success(content)` without calling `next` | the call's result, without running the tool |
| `ToolOutcome.Fatal(error)` | the wrapper's failure: the run fails with `GraphError.ToolFailed`; `recover` re-runs only that call |

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

`admit` gets an `AdmissionContext`, described under [Admission](#admission). `beforeAgent`, `promptSections`,
`wrapModelCall` and `afterAgent` get a `ModelContext`:

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

Every hook returns its update the same way: the result is built with the update, read with `update`, and
added to with `addUpdate`, which applies after what is already there. Nothing replaces an update, so a wrapper
cannot drop what the tool or the hooks inside it wrote.

| Hook | Built with an update | Added to | Commits |
|---|---|---|---|
| `beforeAgent` | `BoundaryResult(text, update)` | `result.addUpdate(update)` | with the query, so the turn's first model call sees it |
| `wrapModelCall` | `ModelResponse(completion, update)` | `response.addUpdate(update)` | with the model step |
| `wrapToolCall` | `ToolOutcome.success(content, update)` | `outcome.addUpdate(update)` | with the call, when it succeeded |
| `afterAgent` | `BoundaryResult(text, update)` | `result.addUpdate(update)` | with the turn's end |

`ToolOutcome.addUpdate` adds to a `Success` and returns any other outcome as it is: only a successful call
commits an update. An update in a response or an outcome a wrapper discarded is never committed.

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
- When a turn's answer is blocked, every key the turn wrote - a middleware's included - returns to its
  value before the turn.

## Prompt sections

`promptSections` is called before every model call, in stack order, from the committed state. The sections
are added to the system prompt of that request and never stored, so a recovered run builds them again from
the same state. Each `PromptSection(id, text, cacheable)`:

- has an `id` unique within the request; two sections with one id fail the run. `response-format` is taken
  by an agent with a response format (`withOutput`).
- with `cacheable = true` is placed before the other sections, so a provider's prompt cache sees a stable
  prefix. Each group keeps stack order, after the agent's own system prompt.

All sections together, with the blank lines between them, may add at most `maxPromptSectionTokens` estimated
tokens to a request: 8192 by default, set with `AgentBuilder.withMaxPromptSectionTokens(n)` (at least 1). A
request over it, or with more than 64 sections, fails the run with a `ValidationError` naming each section's
size; nothing is truncated. A `withOutput` response format's section counts against the same budget.

So bound your own section's size, by the same estimate the agent uses - four characters a token:
`PromptSection.estimatedTokens(chars)` gives the tokens a text is counted as, and `PromptSection.charsFor(tokens)`
the most characters that fit a budget:

```scala
import org.llm4s.agent.graph.middleware.PromptSection

def bounded(id: String, text: String, maxTokens: Int): PromptSection =
  PromptSection(id, text.take(PromptSection.charsFor(maxTokens).toInt))

val section = bounded("kb-articles", "a long list of articles ...", maxTokens = 500)
assert(PromptSection.estimatedTokens(section.text.length.toLong) <= 500)
```

## Ordering

The first middleware in the stack is the outermost: its `beforeAgent` runs first, its `wrapModelCall` and
`wrapToolCall` wrap all the others, and its `afterAgent` runs last. By default the stack is the order of
`withMiddleware`. A middleware changes that with constraints, which `build()` checks:

- `runsBefore: Set[MiddlewareRef]` - middleware this one must wrap; `runsAfter` - middleware that must wrap
  it. The stack is sorted by these edges, ties broken by registration order; a cycle is refused.
- `placement(other): Option[Placement]` - a position this middleware's correctness depends on. It is checked,
  never arranged: `Placement.Outside(reason)` refuses a stack in which this middleware does not wrap `other`;
  `Placement.OutsideAtBoundary(reason)` also refuses it on a handoff target when the root agent has `other`,
  since the root's boundary hooks run outside the target's - an order no constraint within one stack can
  arrange.

There is one way to name another middleware, a `MiddlewareRef`:

| Reference | Names |
|---|---|
| `MiddlewareRef.ofType[GuardrailMiddleware]` | every middleware of that type in the stack, whatever id each was given |
| `MiddlewareRef(MiddlewareId("guardrails"))` | the middleware with that id |
| either, followed by `.ifPresent` | the same, but only when it is there |

Prefer the type: an id is its user's to choose. A reference is required by default - one that names nothing
in the stack is refused, so a mistyped id cannot silently leave an order unarranged - and `.ifPresent` makes it
optional, which is how a reusable middleware says "outside the guardrails, if there are any". `placement`
is given the other middleware itself, so it matches the same two ways: by type, or by `other.id`.

```scala
import org.llm4s.agent.graph.middleware.{
  AgentMiddleware,
  ContextWindowMiddleware,
  GuardrailMiddleware,
  MiddlewareId,
  MiddlewareRef,
  Placement
}

final class Metrics extends AgentMiddleware:
  val id = MiddlewareId("metrics")

  // its beforeAgent sees every query, also one the guardrails then block; fine without guardrails too
  override def runsBefore: Set[MiddlewareRef] = Set(MiddlewareRef.ofType[GuardrailMiddleware].ifPresent)

  override def placement(other: AgentMiddleware): Option[Placement] = other match
    case _: ContextWindowMiddleware => Some(Placement.Outside("it measures the request before pruning"))
    case _                          => None

```

Ids must match `[a-zA-Z0-9_-]{1,64}` and be unique in the stack. Make an id a constructor parameter with a
default, as the built-ins do, so two instances can share an agent. Ids are persisted: in approval
requests, and in the names of the nodes a middleware question waits at. The ids of an agent's middleware, in
order, are part of its graph version, and a thread suspended or `Running` after a crash restores only into
the same version; see [Durable Checkpointers](durable-checkpointers).

### Reserved ids

The agent uses four ids itself. `build()` refuses a middleware of yours with one of them, by name, whether or
not the agent installs its own; `MiddlewareId.reserved` lists them in code.

| Id | Reserved for |
|---|---|
| `agent-guardrails` | the guardrails given to `withInputGuardrails` and `withOutputGuardrails`, which run innermost |
| `delegation` | the middleware that offers an agent's [subagents](delegation), which runs innermost |
| `ancestor-permissions` | a subagent's ancestors' permission rules, which run outermost |
| `response-format` | the response format's prompt section (`withOutput`); the section's id too |

A constraint may name one: `MiddlewareRef(MiddlewareId.Delegation)`. A
[harness profile](index#harness-profile)'s middleware run outside the builder's own, in a fixed order, under
their own default ids (`permissions`, `summarization`, ...), which are not reserved: giving yours the same id
beside a harness is an ordinary duplicate.

## Errors

A hook fails by value: a `Left` from the hooks that return `Result`, and `ToolOutcome.Fatal(error)` from
`wrapToolCall`, which returns what a tool returns. A hook that throws has failed with that value, holding a
`GraphError.MiddlewareFailed` that names the middleware; a thrown cancellation cancels the run. What a
failure does depends only on where the hook runs:

| Where | Hooks | A failure |
|---|---|---|
| at admission | `admit` | **refuses the turn**: no run starts and nothing is stored; `block(reason)` instead ends the turn `Blocked`, with no query stored |
| at the turn's boundary | `beforeAgent`, `afterAgent` | **blocks the turn**: nothing of it is kept - an input block stores nothing, an output block removes the turn and restores every key it wrote - its usage is kept, the thread stays usable, and there is nothing to recover |
| inside a step | `promptSections`, `wrapModelCall`, `wrapToolCall` | **fails the run**: the thread keeps its last checkpoint, a new turn is refused until `recover` runs the step again (for a tool, only that call) |

What the caller sees:

- `block(reason)` from `admit` or a boundary hook is an outcome: `agent.run` returns `Right`, with
  `AgentStatus.Blocked(id, Some(reason))`. A guardrail's block is the same, with the guardrail's name. The reason is
  on that result and is never stored: a status read back from the thread later has `None`.
- Any other boundary failure is the `Left` of `agent.run`: the hook's error, or `MiddlewareFailed` for a throw.
- A step's failure is the `Left` of `agent.run` too: the error, inside `GraphError.NodeFailed`, and inside
  `GraphError.ToolFailed` for a wrapper's `Fatal`.

Three things look like failures and are not:

- `ToolOutcome.Error(message)` is a tool call's result: the model reads it, and the run continues.
- An update to a key not in `writes` fails the run, whatever the hook returned.
- A call through `context.accounted(client)` refused at the run's token budget ends the turn
  `AgentStatus.TokenLimitReached` when it was made from `wrapModelCall` or `promptSections`, whatever the hook
  does with the refusal.

```scala
import org.llm4s.agent.graph.middleware.{ AgentMiddleware, BoundaryResult, MiddlewareId, ModelContext }
import org.llm4s.types.Result

final class OfficeHours(open: () => Boolean) extends AgentMiddleware:
  val id = MiddlewareId("office-hours")

  override def beforeAgent(input: String, context: ModelContext): Result[BoundaryResult] =
    if open() then Right(BoundaryResult(input)) else block("the assistant answers on weekdays")
```

## Running again: replay and determinism

`admit` runs once for a turn: `recover` and `resume` continue from the turn's first checkpoint, which holds the
query as admitted, and never admit it again. Every other hook can run more than once for one step of a turn, and
must give the same result each time:

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
- A block restores thread state, not an external system: an effect that applied stays applied.

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
| `GuardrailMiddleware` | `admit`, `afterAgent`: runs input and output guardrails, blocks on failure | [Guardrails](guardrails) |
| `GuardrailReviewMiddleware` | as `GuardrailMiddleware`, but asks a reviewer instead of blocking | [Human Review](human-review#reviewing-what-guardrails-refuse) |
| `ApprovalMiddleware` | `wrapToolCall`: asks for approval when its function gives a reason | [Human Review](human-review#five-ways-a-turn-waits) |
| `PermissionMiddleware` | `wrapToolCall`: allows, denies or asks by rules over each call's resources | [Tool Permissions](index#tool-permissions-timeouts-and-locks) |
| `ContextWindowMiddleware` | `wrapModelCall`: prunes the messages sent, not the stored history | [Context Window Management](index#context-window-management) |
| `SummarizationMiddleware` | `wrapModelCall`: replaces older messages with a summary kept in the thread | [Summarisation](index#summarisation) |
| `ToolOutputOffload` | `wrapToolCall`, `wrapModelCall`: keeps large tool results out of the conversation | [Large tool results](index#large-tool-results) |
| `TodoMiddleware` | `tools`, `promptSections`: a `write_todos` tool and the plan in the prompt | [Todos](index#todos) |
| `SkillsMiddleware` | `promptSections`: lists the agent's skills | [Skills](index#skills) |
| `InstructionMemoryMiddleware` | `promptSections`: shows `AGENTS.md` files | [Instruction Memory](index#instruction-memory) |
| `SemanticMemoryMiddleware` (`llm4s-agent-memory`) | `beforeAgent`, `promptSections`, `afterAgent`: recalls and records long-term memory; `runsBefore` the guardrail middleware, if any | [Semantic Memory Middleware](memory#semantic-memory-middleware) |

Their sources, in `modules/agent` and `modules/agent-memory`, are the best examples of each hook.

## Attaching middleware

```scala
import org.llm4s.agent.Agent
import org.llm4s.agent.graph.middleware.ApprovalMiddleware
import org.llm4s.agent.guardrails.builtin.LengthCheck

val built = Agent
  .builder("assistant", client)
  .withMiddleware(ApprovalMiddleware.unlessReadOnly) // outermost of the builder's own
  .withInputGuardrails(LengthCheck(1, 10000))        // innermost, whatever the order of these calls
  .withMaxPromptSectionTokens(2048)
  .build()
```

Middleware belong to the agent, not to a run: give each agent of a family its own with its own builder.
`build()` returns a `Left(ValidationError)` listing every problem with a stack: an invalid, duplicate or
reserved id, a required constraint naming a middleware that is absent, a cycle, a clashing tool name, a
misplaced middleware, or a key the loop owns in `writes`.

## Testing a middleware

### A hook on its own

`MiddlewareTestKit` (`llm4s-agent-testkit`, a test dependency) builds what the agent loop would hand a hook,
so you call the hook directly: no agent, no runtime, no model.

```scala
libraryDependencies += "org.llm4s" %% "llm4s-agent-testkit" % llm4sVersion % Test
```

- `MiddlewareTestKit.state(keys, update)` is a thread state that registers `keys` - your middleware's
  `writes` - with `update` applied; `MiddlewareTestKit.updated(state, update)` applies what a hook returned, as
  the runtime commits it, for the next hook.
- `MiddlewareTestKit.modelContext(state, config, threadId)` is the `ModelContext` of `beforeAgent`,
  `promptSections`, `wrapModelCall` and `afterAgent`; `config` is the run's `RunConfig`, so its tenant,
  principal, run id and budgets are yours to set.
- `MiddlewareTestKit.admissionContext(config, threadId)` is the `AdmissionContext` of `admit`:
  `new RedactEmails().admit("write to ada@example.com", MiddlewareTestKit.admissionContext())`.
- `MiddlewareTestKit.toolContext(call, state, config, threadId, approved)` is the `ToolContext` of
  `wrapToolCall`, with the idempotency key the loop would derive for `call`. Build the request with
  `ToolCallRequest(spec, call)`.
- `MiddlewareTestKit.usage(context)` is the usage a hook's `context.accounted(client)` calls recorded; a
  token budget on `config` refuses a call at the limit, as in a run.
- `MiddlewareTestKit.order(middleware*)` is the ids in the order a stack runs them, or the
  `ValidationError` `build()` would return.

```scala
import org.llm4s.agent.graph.{ RunConfig, StateKey, StateUpdate, TenantId }
import org.llm4s.agent.graph.middleware.{ AgentMiddleware, BoundaryResult, MiddlewareId, ModelContext, PromptSection }
import org.llm4s.agent.testkit.MiddlewareTestKit
import org.llm4s.types.Result

val Tier: StateKey[String, String] = StateKey.replace[String]("customer-tier", "")

// looks the tenant's tier up once a turn, and tells the model
final class CustomerTier(lookup: String => String) extends AgentMiddleware:
  val id = MiddlewareId("customer-tier")

  override def writes: Set[StateKey[?, ?]] = Set(Tier)

  override def beforeAgent(input: String, context: ModelContext): Result[BoundaryResult] =
    context.run.config.tenantId match
      case Some(tenant) => Right(BoundaryResult(input, StateUpdate.update(Tier, lookup(tenant.value))))
      case None         => block("no tenant on the run")

  override def promptSections(context: ModelContext): Result[Vector[PromptSection]] =
    context.state.get(Tier).map(tier => Vector(PromptSection("tier", s"The customer's plan is $tier.")))

val middleware = new CustomerTier(tenant => if tenant == "acme" then "enterprise" else "free")
val config     = RunConfig().withTenantId(TenantId("acme"))

val checked = for
  state  <- MiddlewareTestKit.state(middleware.writes)
  before <- middleware.beforeAgent("hello", MiddlewareTestKit.modelContext(state, config))
  next   <- MiddlewareTestKit.updated(state, before.update)
  prompt <- middleware.promptSections(MiddlewareTestKit.modelContext(next, config))
yield
  assert(before.text == "hello")
  assert(prompt.map(_.text) == Vector("The customer's plan is enterprise."))

// without a tenant the hook blocks: a Left, which an agent reports as AgentStatus.Blocked
assert(middleware.beforeAgent("hello", MiddlewareTestKit.modelContext()).isLeft)

// the order a stack resolves to, and the error for one that cannot be built
assert(MiddlewareTestKit.order(middleware).map(_.map(_.value)) == Right(Vector("customer-tier")))
assert(MiddlewareTestKit.order(middleware, middleware).isLeft) // a duplicate id
```

The contexts are outside any runtime: an event a hook emits goes nowhere, and no timeout is enforced.

### Through an agent

What a turn stores, what the model is sent, events and recovery are tested through an agent: script the model
with `ScriptedLLMClient` from `llm4s-testkit` (see [Testing with the testkit](../testing-with-the-testkit)),
run a turn, and check what the model was sent and what the turn returned:

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
- A tool wrapper's effect on a whole turn is tested with a scripted tool call:
  `Reply.toolCall("name", """{"arg": 1}""")`, then `Reply.text(...)`; the tool's result, or your denial, is the
  `ToolMessage` in the second call's messages.
- An agent's whole stack - a harness's middleware, the builder's guardrails and `delegation` included, and a
  placement across a handoff family - is checked by `Agent.builder(...).withMiddleware(a, b).build()`, which
  returns the `ValidationError` with no model call.
- Test that a hook gives the same result when it runs again: script a failure (`Reply.failure(error)`) so the
  run fails, then `agent.recover(threadId)` and check the thread and the calls.
