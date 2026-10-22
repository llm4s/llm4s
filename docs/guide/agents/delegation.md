---
layout: page
title: Delegation
nav_order: 8
parent: Agents
grand_parent: User Guide
---

# Delegation to subagents
{: .no_toc }

Hand a bounded task to another agent and get back only its typed report.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Overview

A **subagent** is a `TypedAgent[I, O]`: an agent that takes a typed input `I` and gives back a typed report `O`. A
parent offers its subagents with `AgentBuilder.withSubagents`, and its model delegates through the `task` tools. Each
delegated task runs on a **child thread** of the parent's thread, with a fresh conversation and only the child's own
tools and middleware, plus what its [inheritance](#inheritance) grants. Only typed data crosses: the parent sends `I`, which becomes the child's first user message, and
gets back `O`. The child's messages never enter the parent's thread, and the parent's never enter the child's.

The API is `@Experimental` (design [§4.44](https://github.com/llm4s/llm4s/blob/main/docs/design/typed-agent-runtime/4.44-stage-4-slice-d1-typed-agent-and-task.md), [§4.45](https://github.com/llm4s/llm4s/blob/main/docs/design/typed-agent-runtime/4.45-stage-4-slice-d2-inheritance.md)).

## Defining subagents

```scala
import org.llm4s.agent.Agent
import org.llm4s.agent.delegation.{ DelegationConfig, TypedAgent }
import org.llm4s.schema.SchemaOf

final case class ResearchQuestion(topic: String) derives ReadWriter, SchemaOf
final case class Findings(summary: String, sources: Vector[String]) derives ReadWriter, SchemaOf

// a typed subagent: its own tool, task_researcher, whose arguments are ResearchQuestion's schema
val researcher = TypedAgent("researcher", "Researches one topic and cites its sources",
    SchemaOf[ResearchQuestion].definition, SchemaOf[Findings].definition)
  .withSystemPrompt("You research one topic. Answer with your findings.")
  .withTools(researchTools)

// a text subagent: offered through the shared `task` tool, {subagent_type, description}
val general = TypedAgent.text("general-purpose", "Any self-contained task")

for
  lead   <- Agent.builder("lead", client)
              .withSubagents(researcher, general)
              .withDelegation(DelegationConfig().withMaxConcurrentChildren(2))
              .build()
  result <- lead.run(ThreadId("brief-1"), "Write a brief on tidal power")
yield result.answer
```

- **`task`** takes `{subagent_type, description}` and covers every subagent made with `TypedAgent.text` (or whose
  input is a `TaskRequest`), so prompts written for the Deep Agents `task` tool carry over.
- **`task_<id>`** is the tool of each other subagent; its arguments are the subagent's input schema.
- A cacheable prompt section lists each subagent's id, tool and description.

A text subagent's report is its answer. A typed subagent answers in `O`'s schema (`AgentBuilder.withOutput`), and its
report is that answer decoded. The child uses the parent's client and completion options unless `withModel` sets
others: a model is not a capability, so it is inherited.

## Inheritance

A child takes nothing of its parent's unless granted. `withInheritance` says what it takes:

```scala
import org.llm4s.agent.delegation.{ Inheritance, TaskBudget, ToolGrant }
import scala.concurrent.duration.*

researcher.withInheritance(
  Inheritance()
    .withTools(ToolGrant.Only(Set("fetch_page")))       // or ToolGrant.AllExcept(...), ToolGrant.None (default)
    .withMiddleware(MiddlewareId("semantic-memory"))    // the parent's instance: shared memory, same tenant
    .withBudget(TaskBudget().withMaxSteps(20).withTimeout(2.minutes).withMaxTokens(50_000))
    .withDelegate(false)                                // its own subagents only when true, to maxDepth
)
```

- **Tools and middleware** are the parent's instances, run against the child's thread. A tool over a
  `StateFileBackend` sees the child's own (empty) files; one over a host, workspace or store backend shares the
  parent's, so give parallel children distinct paths. A name the parent does not have is refused at `build()`. The
  task tools are never granted as tools: `withDelegate(true)` lets a child offer its own `withSubagents`, and only
  while its depth is below `maxDepth`.
- **Identity is never granted.** Tenant, principal, tracing, the runtime and the checkpointer are always the parent's.
- **Budgets** are capped by what the parent has left when the task is called: steps by the parent turn's remaining
  steps (`RunContext.remainingSteps`; the parent's `maxSteps` when the budget sets none), tokens by the parent run's
  `maxTokens` less its usage, time by the parent's deadline, concurrency by the parent's `maxConcurrency`. A parent
  with no steps or tokens left starts no child: the call is refused (`TaskOutcome.NotStarted`) naming only the
  subagent. A parent past its deadline fails the call with its own `DeadlineExceeded`. Each limit is the child's
  whole life: a child recovered after a crash has its grant less what it used, and its time runs from its first start.
  Parallel children each get the same remainder, so together they can overshoot the parent's tokens; the parent's
  next model call then stops at `TokenLimitReached`.

`Agent.delegation` is the `DelegationPlan`: for each subagent its tools (own or granted), middleware in order, the
ancestors' permission rules, its model and whether it is inherited, its budget, and whether it may delegate. Log it
with `toString`.

## Bounds

`DelegationConfig` bounds delegation, each limit on its own:

| Setting | Default | What it bounds |
|---|---|---|
| `maxConcurrentChildren` | 4 | children one parent run has live at once; a call over it waits |
| `maxTasksPerTurn` | 16 | task calls per parent turn, by their order in the turn's messages, a handoff's agents' included; a call over it is a tool error |
| `maxDepth` | 1 | how deep delegation nests |
| `maxReportBytes` | 64 KiB | every task tool message, at least 64 bytes: a larger report is a tool error, and an error's reason is cut to fit |
| `childRetention` | 64 | settled child threads each parent thread keeps |

Task calls in one model message run in parallel, as any tool calls do, bounded by the run's `maxConcurrency` and then
by `maxConcurrentChildren`.

## Subagents in a harness

A `HarnessProfile` carries subagents too, as `subagents: SubagentConfig` ([design §4.48](https://github.com/llm4s/llm4s/blob/main/docs/design/typed-agent-runtime/4.48-stage-4-slice-p1-harness-subagents.md)).
`HarnessProfile()` has `SubagentConfig.none`: no subagents, because delegation is never granted silently.
`HarnessProfile.deepAgents` turns on the **general-purpose subagent**, `general-purpose`, which the model calls through
`task` like any `TypedAgent.text` subagent:

- Its own harness is the minimal profile: the file tools over its own `StateFileBackend`. Its files are its own; only
  its report comes back.
- Its grant is every tool of the parent except the file tools and `execute`, and no delegation. The preset computes it
  from the parent's actual tools when the agent is built, so a `deepAgents` agent with or without a sandbox builds.
  `Agent.delegation` shows it.

```scala
import org.llm4s.agent.harness.{GeneralPurposeConfig, HarnessProfile, SubagentConfig}

val profile = HarnessProfile.deepAgents
// specialists only, no general-purpose subagent
val specialists = profile.withSubagents(SubagentConfig.none.withSpecialists(researcher, critic))
// change the general-purpose subagent: its model, description, prompt or grant
val cheaper = profile.withSubagents(
  profile.subagents.withGeneralPurpose(GeneralPurposeConfig().withModel(smallClient, CompletionOptions()))
)
// the bounds
val wider = profile.withSubagents(profile.subagents.withDelegation(DelegationConfig().withMaxConcurrentChildren(8)))
```

- The profile's subagents come first, then any added with `AgentBuilder.withSubagents`. `SubagentConfig.delegation`
  bounds them all; `withDelegation` beside a harness is refused.
- The `delegation` middleware runs inside the harness's permissions, so with permission rules `task` needs an
  `Invoke` rule (`task:general-purpose`).
- The `deepAgents` prompt gains a part on delegating: independent, bounded work, a self-contained description, parallel
  tasks in one message, and checking a report before relying on it.
- `llm4s.agent.harness.subagents` in `reference.conf` holds `generalPurpose.enabled` and the `DelegationConfig` bounds,
  which `HarnessConfigLoader` reads. Specialists and models are code.

## When a child does not report

A child that is blocked by a guardrail, reaches its step limit, gives a typed answer that fails its schema twice, or
fails, gives no report. With `ChildFailure.Report`, the default, the parent model sees a tool error naming the
subagent, the child thread and the reason, and can compose without it. With `withFailure(ChildFailure.Fail)` the
parent run fails instead, and the parent's `recover` continues the child from its own checkpoint.

A task call runs again on `recover`. It never starts its child twice: a completed child's report is read back from
the child thread, and a child that was still running continues from its checkpoint.

## Approvals and questions through the parent

A child that stops for an approval, a tool's or a middleware's question, or a breakpoint suspends its parent too. The
parent's other calls in the batch finish first. The parent's `AgentStatus.Suspended` lists each child interrupt in
`delegated`, under a composite id `<parent interrupt>~<child interrupt>` (a grandchild's is `p~c~g`), with the
`ChildRef` of the child that waits and its request as the child gives it (`ChildInterrupt`). A delegated interrupt is
never also in `questions`.

Answer them with the usual helpers, by the composite id, and resume the parent:

```scala
agent.run(threadId, "research tides").flatMap { result =>
  result.status match
    case AgentStatus.Suspended(_, _, _, _, delegated) =>
      val answers = delegated.map {
        case (id, DelegatedRequest(_, _: ChildInterrupt.Approval))   => result.approve(id)
        case (id, DelegatedRequest(_, _: ChildInterrupt.Breakpoint)) => result.proceed(id)
        // a tool's or a middleware's question: reply with its answer type (here a tool's `UnitChoice`)
        case (id, DelegatedRequest(_, _))                            => result.reply(id, UnitChoice("km"))
      }
      agent.resume(threadId, answers.toMap)
    case _ => Right(result)
}
```

`approve`, `reject`, `edit`, `reply` and `proceed` encode the answer the child expects; `Agent.resume` routes it to
the child through its `task` call, which resumes the child and reports to the parent model once the child ends. A
rejection reaches the child's model as at top level. Answer some and the rest stay pending: the call asks again, under
a new parent interrupt id. A composite id that is not pending is refused with `GraphError.InvalidResume`. A parent
that stops after its child resumed never resumes the child twice: its `recover` reads the child first.

## Permissions

Each task call's resource is `(Invoke, task:<id>)`, so a `PermissionMiddleware` on the parent can allow, ask or deny
each subagent:

```scala
new PermissionMiddleware(Seq(PermissionRule.allow(Operation.Invoke, "task:researcher")))
```

Permissions only narrow. A child's call runs only if every ancestor's `PermissionMiddleware` rules allow it, and then
the child's own: a `Deny` at any level denies, and an `Ask` at any level asks once, the approval covering every level.
So rules on the parent are the whole policy for the subagents below it too: allow there what a child's tools touch.

## Inspecting children

`Agent.children(threadId)` lists the child threads a thread's tasks ran on, each with its origin and status. Read a
child's history through the runtime by its id. A child thread cannot be run, resumed or forgotten directly; forgetting
its parent forgets it.

## Following children

Every `task` call sends the parent's live `SubagentEvents.TaskStarted` as its child starts, and commits one durable
`SubagentEvents.TaskEnded` with its result: the child, a status label (`completed`, `blocked:<guardrail>`,
`step_limit_reached`, `token_limit_reached`, `output_invalid`, `failed`; a call waiting on a suspended child commits none until the child settles), the child's whole usage and its
duration. It carries no request, report or reason, so it is the event to count, time and cost subagents by.

A subscription made with `nested = true` also receives every event of each child, as `StreamEvent.Nested(child,
event)`, after the child's `TaskStarted` and before its `TaskEnded`; a grandchild's arrive nested twice. Match them
with `EventHandlers.onChild`, which gives the innermost child:

```scala
agent.stream(threadId, "Research these three topics", nested = true)(
  EventHandlers
    .on(SubagentEvents.TaskStarted)(s => println(s"-> ${s.child.agent}"))
    .onChild(AgentEvents.TextDelta)((child, d) => print(s"[${child.agent}] ${d.text}"))
    .on(SubagentEvents.TaskEnded)(e => println(s"<- ${e.child.agent}: ${e.status} in ${e.duration}"))
)
```

A child's text deltas arrive only if the subagent streams (`TypedAgent.withStreaming()`). Nested events are live: they
take the subscription's live slots, so a slow listener loses them as a `LiveGap`, and a child this process cannot
watch - one another process holds, or one that had already finished when a replay reattached to it - shows as one
`Nested(child, LiveGap(0))`. Its durable events are kept: `agent.subscribeChild(ref)` replays and follows one child's
own log, tenant-checked like any read of a thread. The cats-effect, ZIO and Pekko modules offer the merged stream as
`nestedEvents(run, handlers)`.

A subagent traces to its parent's `Tracing`; its `AgentRunEnded` names the parent thread and run
(`parentThreadId`, `parentRunId`), which OpenTelemetry puts on the `invoke_agent <agent>` span and Langfuse in the
trace metadata.

## Running a subagent alone

`typedAgent.bind(client, runtime)` compiles a definition (pass `options` for non-default `CompletionOptions`; `withModel` overrides both); `BoundAgent.run(input)` runs it once on a new top-level
thread and returns its `TaskOutcome`, for tests and one-off calls. A graph node calls `bound.run(threadId, input)`
instead, with a `threadId` derived from its own replay-stable key (one that identifies the input too): a thread
already there is reattached, never run twice - a settled one returns its stored outcome with no model call, a failed
or crashed one is recovered without repeating completed tool effects, a suspended one returns `Suspended` again, and
one another caller is running is waited on. `bound.resume(outcome.child, answers)` continues
a run that came back `Suspended`, taking the same answers as `Agent.resume`, and `bound.recover(outcome.child)` one that
came back `Refused(TaskRefusal.Failed(_))`; both return a `TaskOutcome` too. Only a standalone run's ref is accepted: a
delegated child is continued through its parent.

## A worked example

[`ParallelDeepResearchExample`](/examples/#parallel-deep-research) puts these together: a lead on
`HarnessProfile.deepAgents` with the general-purpose subagent off, a typed `researcher` granted the lead's
`fetch_page` and a tool-less `critic`, `maxConcurrentChildren = 2`, and permission rules that ask before a fetch from
an unknown site, wherever in the tree it is made. Its acceptance suite, `ParallelDeepResearchAcceptanceSpec`, runs it
on a scripted model through a parallel batch, a crash and `recover`, a child's failure, a child's approval, a cancel,
the merged stream and `forget` ([design §4.49](https://github.com/llm4s/llm4s/blob/main/docs/design/typed-agent-runtime/4.49-stage-4-slice-p2-exit-example.md)).

## Delegation or handoffs

A [handoff](handoffs) moves the conversation to another agent of the same family, which then owns it. Delegation
starts another thread, and the parent keeps control and sees only the report. Use handoffs for conversational routing,
delegation for bounded, independent work, especially work that can run in parallel.
