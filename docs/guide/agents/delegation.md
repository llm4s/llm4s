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

The API is `@Experimental` (design [§4.44](../../design/typed-agent-runtime/4.44-stage-4-slice-d1-typed-agent-and-task.md)).

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
| `maxReportBytes` | 64 KiB | the report, as the JSON the parent model reads; a larger one is a tool error |
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

A child that stops for approval or a question is, for now, reported to the parent model as an error and left
suspended; answering it through the parent is the next slice.

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

## Running a subagent alone

`typedAgent.bind(client, runtime)` compiles a definition; `BoundAgent.run(input)` runs it once on a new top-level
thread and returns its `TaskOutcome`. It is for graph nodes and tests.

## Delegation or handoffs

A [handoff](handoffs.md) moves the conversation to another agent of the same family, which then owns it. Delegation
starts another thread, and the parent keeps control and sees only the report. Use handoffs for conversational routing,
delegation for bounded, independent work, especially work that can run in parallel.
