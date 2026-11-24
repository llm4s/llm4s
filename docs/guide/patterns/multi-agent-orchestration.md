---
layout: page
title: Multi-Agent Orchestration
description: "Choose how several LLM4S agents work together in Scala: handoffs for routing, subagent delegation for bounded tasks, and agent graphs for parallel work."
search_title: "Multi-agent orchestration patterns for Scala LLM apps"
parent: Real-World Application Patterns
grand_parent: Operations
nav_order: 1
---

# Multi-Agent Orchestration
{: .no_toc }

Several agents with different instructions, tools or models often answer better than one agent that does everything.
LLM4S ships three ways to combine them. Pick by who should own the conversation, not by how the agents talk: the
runtime does the routing, context passing and step accounting for you.
{: .fs-6 .fw-300 }

1. TOC
{:toc}

---

## Which mechanism

| You want | Use | Who owns the conversation |
|---|---|---|
| A front agent that routes each question to a specialist, which then answers the user | [Handoffs](../agents/handoffs): `Handoff.to`, `Handoff.toId` | The specialist, until it hands on or back |
| A lead agent that farms out bounded, independent tasks and combines the results | [Delegation](../agents/delegation): `withSubagents`, `TypedAgent` | The lead: each task runs on a child thread and returns only its report |
| A fixed workflow: specialists in parallel, a join, an editor | An agent graph: `GraphBuilder` ([cookbook recipe](../../examples/cookbook/multi-agent-graph)) | The graph you define |

The [delegation guide](../agents/delegation#delegation-or-handoffs) compares the first two in more detail.

## Routing with handoffs

A handoff is a route inside one graph, keyed by agent id: the id given to `Handoff.to` must equal the target builder's
id. The target is offered as a tool; when the model calls it, the target becomes the active agent and continues the
turn with its own system prompt, tools and handoffs.

```scala
import org.llm4s.agent.{ Agent, Handoff }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.types.Result

def supportDesk(client: LLMClient): Result[Agent] = {
  // A specialist that can hand back to the triage agent: a cycle, named by id
  val billing = Agent.builder("billing", client)
    .withSystemPrompt("You answer questions about invoices and payments.")
    .withHandoffs(Handoff.toId("triage", "Not a billing question"))
    .withMaxSteps(10)

  Agent.builder("triage", client)
    .withSystemPrompt("Route each question to the right specialist, or answer it yourself.")
    .withHandoffs(Handoff.to("billing", billing, "Invoices, refunds and payment problems"))
    .withMaxSteps(10)
    .build()
}
```

- **Cycles are supported.** A specialist hands back with `Handoff.toId`, because two builders cannot each hold the
  other. A handoff counts as a step and the step limit is shared by the whole turn, so a turn that bounces between
  agents ends `AgentStatus.StepLimitReached` at the active agent's `withMaxSteps` (50 by default) rather than looping.
- **Context.** By default the target sees the whole conversation. `Handoff.to(id, builder, reason,
  preserveContext = false)` sends it only the user's last question and the transfer.
- **Guardrails** on the root agent's middleware still apply after a handoff: see
  [guardrails and middleware across handoffs](../agents/handoffs#guardrails-and-middleware-across-handoffs).
- A handoff must be the only tool call in its message.

## Bounded tasks with delegation

`AgentBuilder.withSubagents` offers each `TypedAgent` to the lead's model as a tool (`task`, or `task_<id>` for a typed
subagent). Each call runs on a child thread with a fresh conversation, and only typed data crosses: the parent sends
the input and gets back the report. `DelegationConfig` bounds the work, for example
`DelegationConfig().withMaxConcurrentChildren(2)`. Subagents can run in parallel, which is how a lead agent fans work
out; approvals and questions raised by a child surface through the parent. The API is `@Experimental`; the
[delegation guide](../agents/delegation) has the full example, inheritance rules and bounds.

## Fixed workflows with a graph

When the shape of the work is known in advance - two specialists in parallel, then an editor once both have answered -
build it as a graph rather than asking a model to orchestrate. The graph runtime runs parallel nodes in one superstep
and a static join holds the next node until its inputs have committed. The runnable
[several agents in one graph](../../examples/cookbook/multi-agent-graph) recipe shows it end to end, and runs with no
API key.

## Failures

A provider, tool or middleware failure is a `Left(GraphError...)` from `run`; a guardrail block is an outcome,
`AgentStatus.Blocked`. With a [durable checkpointer](../agents/durable-checkpointers), `agent.recover(threadId)`
continues a failed run, re-running only the failed or unstarted work. To retry or fall back between providers, give the
agents a wrapped client: see [Error Recovery](error-recovery).

## See also

- [Agents](../agents/), [Handoffs](../agents/handoffs), [Delegation](../agents/delegation)
- [Human Review](../agents/human-review): pause a multi-agent turn for approval
- [Streaming](../agents/streaming): follow which agent is active as events arrive
- [Running agents on several machines](../agents/multiple-machines)
