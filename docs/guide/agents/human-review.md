---
layout: page
title: Human Review
nav_order: 6
parent: Agents
grand_parent: User Guide
---

# Human Review
{: .no_toc }

Pause an agent's turn for a person: to approve a tool call, answer a question, review an answer, or
look at what a step did before the next one runs.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Four ways a turn waits

A turn that waits for a person ends `AgentStatus.Suspended`. Everything it waits for is an
*interrupt* with its own `InterruptId`, in one of four lists:

| List | Raised by | Answer with |
|---|---|---|
| `approvals` | a tool's `ToolOutcome.NeedsApproval`, or a tool wrapper such as `ApprovalMiddleware` | `result.approve(id)`, `reject(id, reason)`, `edit(id, arguments)` |
| `questions` | a tool extending `AgentTool.Asking[A, Q, Ans]` | `result.reply(id, answer)`, the tool's `Ans` |
| `middlewareQuestions` | a middleware extending `AgentMiddleware.Asking[Q, Ans]` | `result.reply(id, answer)`, the middleware's `Ans` |
| `breakpoints` | a static breakpoint on one of the agent's nodes | `result.proceed(id)` |

`agent.resume(threadId, answers)` takes any non-empty subset of them. The ones you do not answer stay
pending, so the result can be `Suspended` again with what is left: this is incremental approval. An
unknown id, or an answer that does not decode as the asker's answer type, is refused and the thread
is unchanged. While a thread waits, `run` and `recover` on it are refused with `PendingInterrupts`.

```scala
agent.run(threadId, "Deploy the release").flatMap { result =>
  result.status match
    case AgentStatus.Suspended(approvals, questions, asked, held) =>
      val answers = approvals.map((id, _) => result.approve(id)) ++ held.map((id, _) => result.proceed(id))
      agent.resume(threadId, answers.toMap) // questions and middleware questions stay pending
    case _ => Right(result)
}
```

The waiting thread is persisted before `run` returns, under every durability mode, so another process
with the same agent and checkpointer can resume it.

## Static breakpoints

A breakpoint holds a task of one of the agent's nodes, before it runs or after it ran:

```scala
val agent = Agent
  .builder("assistant", client)
  .withTools(tools)
  .withInterruptBefore(AgentNode.Tool)   // each tool call, before it runs
  .withInterruptAfter(AgentNode.Model)   // each model call, after its message is stored
  .build()
```

| `AgentNode` | Node | Before | After |
|---|---|---|---|
| `Model` | `<id>/model` | the model is not called yet | the model's message is stored; its tool calls, or the answer's guarding, wait |
| `Tool` | `<id>/call-tool` | the call has not run | its result is recorded; the next model call waits for it |
| `Finish` | `<id>/finish` | the answer is stored; its `afterAgent` hooks (guardrails) have not run | the answer is guarded; the turn ends once answered |

Each held task is an interrupt of its own. The tool calls of one message are held one per call, so a
reviewer can let them through one at a time. `AgentStatus.Suspended.breakpoints` lists them as
`BreakpointRequest(node, phase, call)`; `call` is the tool call, for a call held before it runs.
`result.proceed(id)` continues one: a task held before it runs is run, and the same breakpoint does not
hold it again; a task held after it ran is not run again - what follows it is released. A breakpoint
takes no other answer.

A call continued after an approval or a question runs at the agent's approval or question node, which
the `Tool` breakpoint does not hold: the reviewer has just seen it.

Breakpoints are not part of the agent's graph version, like streaming: a thread an agent holds can be
continued by the same agent built without them. A run can add its own with a `RunConfig`, naming the
node ids: `agent.run(threadId, query, RunConfig(interruptBefore = Set(NodeId("assistant/model"))))`. A
node the agent does not have is refused with a `ValidationError` before the thread is touched.

### On any graph

Breakpoints belong to the graph runtime, not only to agents. A `RunConfig`'s `interruptBefore` and
`interruptAfter` name nodes of any compiled graph; each `start`, `resume` and `recover` brings its own.
A held task is parked like a task that returned `NodeResult.Suspend`, keyed by its task id, and the run
pauses at the end of the superstep - its siblings run and commit. `RunResult.Suspended` reports it as a
`PendingInterrupt` with `breakpoint = Some(BreakpointPhase.Before)` (its `question` is the task's input)
or `Some(BreakpointPhase.After)` (the node's update is committed; its routes, static edges and join
arrivals wait). Answer it with `Breakpoint.proceed`:

```scala
val config = RunConfig(interruptAfter = Set(NodeId("plan")))
for
  held   <- runtime.start(threadId, graph, input, config).flatMap(_.await())
  resumed <- runtime.resume(threadId, graph, Map(InterruptId("0.0") -> Breakpoint.proceed), config)
  done   <- resumed.await()
yield done
```

Cancelling a run is unaffected: a cancelled run leaves its thread for `recover`, which holds a task the
breakpoint would hold, and runs a continuation that already passed its breakpoint without holding it
again.

## Questions from middleware

A middleware that needs a person - to review an answer, to edit it, or to supply missing information -
extends `AgentMiddleware.Asking[Q, Ans]`, as an asking tool extends `AgentTool.Asking`. A hook asks by
returning `ask(question)` (from `beforeAgent`, `afterAgent` or `wrapModelCall`) or
`askAbout(question)` (from `wrapToolCall`), and reads its answer with `answered(context)` when it runs
again:

```scala
final case class Need(what: String) derives ReadWriter
final case class Info(value: String) derives ReadWriter

final class Region extends AgentMiddleware.Asking[Need, Info]:
  val id = MiddlewareId("region")
  override def beforeAgent(input: String, context: RunContext): Result[String] =
    if input.contains("region") then Right(input)
    else answered(context).fold(ask(Need("region")))(r => Right(s"$input (region ${r.answer.value})"))
```

The turn suspends with a `MiddlewareQuestionRequest` - the agent and middleware that asked, the hook,
and the question as JSON (read it with `ToolLoop.middlewareQuestion[Need](request)`). Once answered
with `result.reply(id, Info("eu"))`, the asking task runs again from its start, and the whole stack of
that hook runs again from the outermost middleware; nothing about a stack's position is stored. What
runs again depends on the hook:

| Hook | While it waits | Once answered |
|---|---|---|
| `beforeAgent` | nothing of the turn is stored | the turn's input runs again |
| `wrapModelCall` | nothing of the model call is stored | the model step runs again: a wrapper that asked after calling `next` calls the model again, unless it returns a completion of its own |
| `wrapToolCall` | the call has no result | the call runs again: its arguments are checked again, with the approval it had |
| `afterAgent` | the answer is stored, the turn has no outcome yet | the answer is guarded again |

Answers given earlier in the same stack run are kept while it runs again, so two asking middleware in
one stack each ask once, and a tool call's answer is kept through a later approval or tool question.
Because the stack runs again, a middleware outside the asking one runs twice and must not depend on
running once. A tool wrapper's questions are one per call, so the calls of one message can be answered
one at a time. A middleware that asks while the tool continues after its own question gets an error
result instead, since running the call again would lose the tool's answer.

### Reviewing what guardrails refuse

`GuardrailReviewMiddleware(input, output)` runs guardrails as `GuardrailMiddleware` does, but a refusal
asks a reviewer instead of blocking. The question is a `GuardrailReview(phase, guardrail, reason,
text)`; the answer a `GuardrailVerdict`: `Allow` lets the text through, `Edit(text)` replaces it, and
`Block` blocks the turn as `GuardrailMiddleware` would have (`AgentStatus.Blocked`).

## From Java and Kotlin

`JAgentStatus.pending()` lists every interrupt as a `PendingInterrupt`, approvals first, then tool
questions, middleware questions and breakpoints. `kind()` is the Java enum `InterruptKind`: `APPROVAL`,
`QUESTION`, `MIDDLEWARE_QUESTION` or `BREAKPOINT`. A field that only some kinds have is an `Optional`:
`toolName()` and `argumentsJson()` when a tool call waits; `reason()` for an approval; `questionJson()`
for a question; `middleware()` for a middleware question; `node()` and `phase()` (the Java enum
`BreakpointPhase`, `BEFORE` or `AFTER`) for a breakpoint. `Answer.proceed(id)` continues a breakpoint;
`Answer.reply(id, json)` answers either kind of question. An agent with breakpoints or asking
middleware is built with `Agent.builder` and wrapped with `Llm4s.wrapAgent`. See
[Suspended turns from Java and Kotlin](index#suspended-turns-from-java-and-kotlin).
