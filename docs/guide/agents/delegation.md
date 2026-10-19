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
tools and middleware. Only typed data crosses: the parent sends `I`, which becomes the child's first user message, and
gets back `O`. The child's messages never enter the parent's thread, and the parent's never enter the child's.

The API is `@Experimental` (design [§4.44](https://github.com/llm4s/llm4s/blob/main/docs/design/typed-agent-runtime/4.44-stage-4-slice-d1-typed-agent-and-task.md)).

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
report is that answer decoded. The child uses the parent's client unless `withModel` sets another.

## Bounds

`DelegationConfig` bounds delegation, each limit on its own:

| Setting | Default | What it bounds |
|---|---|---|
| `maxConcurrentChildren` | 4 | children one parent run has live at once; a call over it waits |
| `maxTasksPerTurn` | 16 | task calls per parent turn, by their order in the turn's messages; a call over it is a tool error |
| `maxDepth` | 1 | how deep delegation nests |
| `maxReportBytes` | 64 KiB | every task tool message, at least 64 bytes: a larger report is a tool error, and an error's reason is cut to fit |
| `childRetention` | 64 | settled child threads each parent thread keeps |

Task calls in one model message run in parallel, as any tool calls do, bounded by the run's `maxConcurrency` and then
by `maxConcurrentChildren`.

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

`typedAgent.bind(client, runtime)` compiles a definition; `BoundAgent.run(input)` runs it once on a new top-level
thread and returns its `TaskOutcome`. It is for graph nodes and tests. `bound.resume(outcome.child, answers)` continues
a run that came back `Suspended`, taking the same answers as `Agent.resume`, and `bound.recover(outcome.child)` one that
came back `Refused(TaskRefusal.Failed(_))`; both return a `TaskOutcome` too. Only a standalone run's ref is accepted: a
delegated child is continued through its parent.

## Delegation or handoffs

A [handoff](handoffs) moves the conversation to another agent of the same family, which then owns it. Delegation
starts another thread, and the parent keeps control and sees only the report. Use handoffs for conversational routing,
delegation for bounded, independent work, especially work that can run in parallel.
