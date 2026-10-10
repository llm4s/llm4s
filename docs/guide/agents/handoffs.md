---
layout: page
title: Handoffs
nav_order: 4
parent: Agents
grand_parent: User Guide
---

# Agent Handoffs
{: .no_toc }

Delegate queries to specialist agents for domain expertise.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Overview

Handoffs enable LLM-driven agent-to-agent delegation. When a primary agent determines that a query requires specialist expertise, it can hand off the conversation to another agent.

A handoff is a route inside one graph. The root agent and every agent reachable through its handoffs are compiled together, by agent id, and one thread runs across them: the thread records which agent is active, and the next turn starts with that agent.

**Key Benefits:**

- **Specialization** - Route queries to domain experts
- **Modularity** - Build focused, maintainable agents
- **Scalability** - Add specialists without modifying the main agent
- **Context Control** - Choose what context to preserve

---

## Quick Start

```scala
import org.llm4s.agent.{Agent, Handoff}
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect

val result = for {
  providerConfig <- Llm4sConfig.provider()
  client <- LLMConnect.getClient(providerConfig)

  // The specialist is a builder, not yet a built agent
  mathAgent = Agent.builder("math", client)
    .withSystemPrompt("You are a math expert. Solve problems step-by-step.")

  // The main agent lists it as a handoff target
  mainAgent <- Agent.builder("main", client)
    .withHandoffs(Handoff.to("math", mathAgent, "Math expertise required"))
    .build()

  result <- mainAgent.run("What is the integral of x^2?")
} yield result
```

The handoff id must equal the target builder's agent id (`"math"` above): the graph is keyed by agent id.

---

## Handoff Configuration

### Basic Handoff

```scala
import org.llm4s.agent.Handoff

val handoff = Handoff.to(
  "specialist",          // equals the target builder's id
  specialistBuilder,
  "Domain expertise required"
)
```

`Handoff.of(id, builder, reason)` returns a `Result[Handoff]` instead of throwing for an invalid id
(`[a-zA-Z0-9_-]{1,52}`).

### Handing Back (Cycles)

A target that must hand back to an agent that is already in the graph names it by id, with
`Handoff.toId`, because two builders cannot each hold the other:

```scala
val specialist = Agent.builder("specialist", client)
  .withHandoffs(Handoff.toId("triage", "Not a specialist question"))

val triage = Agent.builder("triage", client)
  .withHandoffs(Handoff.to("specialist", specialist, "Needs the specialist"))
  .build()
```

`build()` refuses an id that no builder in the family defines, and one id reached with two
different builders. A self-handoff is refused too.

### Without Context

```scala
// The specialist is sent the user's last question, then the transfer onwards
val handoff = Handoff.to("specialist", specialistBuilder, "Fresh analysis needed", preserveContext = false)
```

`Handoff.of(id, builder, reason, preserveContext = false)` takes it too, and, for an agent defined
elsewhere in the family, `Handoff.toId("specialist", Some("Fresh analysis needed"), preserveContext = false)`.

---

## Handoff Options

| Option | Default | Description |
|--------|---------|-------------|
| `id` | Required | The target agent's id; the tool is `handoff_to_<id>` |
| `target` | by `to`/`of`: the builder | The target's builder; `None` (`toId`) names an agent defined elsewhere in the family |
| `transferReason` | `None` | Description shown to the LLM for routing |
| `preserveContext` | `true` | `false` sends the target only the last user message before the transfer, then the transfer onwards |

`transferSystemMessage` no longer exists: each agent sends its own system prompt, and a prompt is
never stored in the thread.

---

## Multiple Handoffs

Provide multiple specialists for intelligent routing:

```scala
val mainAgent = Agent.builder("main", client)
  .withHandoffs(
    Handoff.to("math", mathAgent, "Mathematical calculations and proofs"),
    Handoff.to("code", codeAgent, "Programming and code review"),
    Handoff.to("legal", legalAgent, "Legal questions and contracts"),
    Handoff.to("medical", medicalAgent, "Health and medical information")
  )
  .build()
```

The LLM sees descriptions and chooses the appropriate specialist.

---

## How Handoffs Work

### 1. Tools are Generated

Each handoff target is offered to the model as a tool named `handoff_to_<id>`, described by the transfer reason.

### 2. LLM Decides

The LLM can choose to:
- Answer directly (no handoff)
- Call a regular tool
- Hand off by calling a handoff tool

A handoff must be the **only** tool call in its message. A handoff mixed with other tool calls, or two handoffs in one message, is refused: every call in the message gets an error result explaining the rule, and the same agent's model is asked again.

### 3. The Transfer

The loop stores the assistant message, appends a tool result `Transferred to <target>`, makes the target the active agent and continues in the target's model node. The target sends its own system prompt and offers its own tools and handoffs. The handoff counts as a step, and the step limit is shared by the whole turn: it is the current agent's `maxSteps` against the turn's total step count.

### 4. Response Returns

The target's answer is the run's result. `AgentResult.activeAgent` names the agent that answered, and the next turn starts with it, so a conversation that moved to a specialist stays there until the specialist hands it back.

---

## Specialist Agent Patterns

### Domain Expert

```scala
val physicsAgent = Agent.builder("physics", client)
  .withSystemPrompt(
    """You are a physics expert with deep knowledge of:
      |- Classical mechanics
      |- Quantum mechanics
      |- Thermodynamics
      |- Electromagnetism
      |
      |Provide detailed explanations with equations when helpful.""".stripMargin
  )

val mainAgent = Agent.builder("main", client)
  .withHandoffs(Handoff.to("physics", physicsAgent, "Physics questions requiring expert explanation"))
  .build()

mainAgent.run("Explain quantum entanglement")
```

### Tool Specialist

Tools belong to the agent that uses them:

```scala
val dataAgent = Agent.builder("data", client)
  .withSystemPrompt("You are a data analysis specialist.")
  .withTools(new ToolRegistry(Seq(queryDatabaseTool, generateChartTool, exportCSVTool)))

val mainAgent = Agent.builder("main", client)
  .withTools(new ToolRegistry(basicTools))
  .withHandoffs(Handoff.to("data", dataAgent, "Data analysis with database access"))
  .build()

mainAgent.flatMap(_.run("Analyze our Q4 sales data"))
```

### Customer Support Triage

```scala
val billingAgent = Agent.builder("billing", client)
  .withSystemPrompt("You handle billing and payment issues.")

val technicalAgent = Agent.builder("technical", client)
  .withSystemPrompt("You solve technical problems.")

val salesAgent = Agent.builder("sales", client)
  .withSystemPrompt("You help with purchases and upgrades.")

val triageAgent = Agent.builder("triage", client)
  .withSystemPrompt("You are a customer support triage agent. Route queries to the appropriate specialist.")
  .withHandoffs(
    Handoff.to("billing", billingAgent, "Billing, payments, and subscription issues"),
    Handoff.to("technical", technicalAgent, "Technical problems and account access"),
    Handoff.to("sales", salesAgent, "Purchases, upgrades, and pricing questions")
  )
  .build()

triageAgent.flatMap(_.run("I can't log into my account and my payment failed"))
```

---

## Guardrails and Middleware Across Handoffs

The root agent's run-boundary middleware guards the whole family. Its `beforeAgent` hooks - input
guardrails among them - run on every turn's query, even when a specialist is active, before the
specialist's own; its `afterAgent` hooks - output guardrails - run on every final answer, after the
answering specialist's own. So guardrails given to the root keep applying after a handoff:

```scala
import org.llm4s.agent.graph.middleware.GuardrailMiddleware
import org.llm4s.agent.guardrails.builtin.{ PIIMasker, ProfanityFilter }

val triage = Agent.builder("triage", client)
  .withMiddleware(GuardrailMiddleware(input = Seq(new ProfanityFilter()), output = Seq(new PIIMasker())))
  .withHandoffs(Handoff.to("billing", billingAgent, "Billing questions"))
  .build()
// a query to billing two turns later still passes ProfanityFilter; billing's answers still pass PIIMasker
```

A specialist's own boundary middleware applies only while it is active, inside the root's. A block
from either ends the turn `Blocked`; an output block removes the whole turn, a handoff made in it
included, so the next turn starts with the agent that was active before it. Model and tool wrappers (`wrapModelCall`, `wrapToolCall`, such as
`ContextWindowMiddleware` and `ApprovalMiddleware`) are per agent: give them to each agent that
needs them. Do not give the root's guardrails to its specialists too: they would run twice.

---

## Handling Handoff Results

```scala
mainAgent.run(query) match {
  case Right(result) =>
    result.status match {
      case AgentStatus.Completed(answer) =>
        println(s"${result.activeAgent} answered: $answer")
      case AgentStatus.StepLimitReached =>
        println("Hit the step limit")
      case other =>
        println(s"Run ended: $other")
    }

  case Left(error) =>
    println(s"Error: $error")
}
```

A handoff is not a status: the run continues in the target and ends as any run does.
A handoff is reported by the durable `AgentEvents.HandedOff(from, to)` event on the agent's event
stream (the old `HandoffStarted` / `HandoffCompleted`); see [Streaming Events](streaming).

---

## Context Preservation Examples

### Full Context (Default)

```scala
// Specialist sees the entire conversation
Handoff.to("specialist", specialist, "Needs the specialist")

// User: "I'm building a Scala app"
// Assistant: "Great! What kind of app?"
// User: "A REST API with database access"
// <handoff to database specialist>
// Specialist sees all messages above, then its own reply
```

### Fresh Context

```scala
// Specialist starts from the last question
Handoff.toId("specialist", Some("Independent review"), preserveContext = false)

// User: "I'm building a Scala app"
// Assistant: "Great! What kind of app?"
// User: "A REST API with database access"
// <handoff to database specialist>
// Specialist is sent: "A REST API with database access", then the transfer
```

The stored history is the same either way: `preserveContext = false` changes only what the target is sent, and the transfers are recorded by the loop, so a non-root agent is never sent the full history by mistake.

---

## Handoffs vs Graphs

| Use Case | Approach |
|----------|----------|
| 2-3 specialists, LLM-driven routing | **Handoffs** |
| Complex multi-agent workflows | **Graph (`GraphBuilder`)** |
| Dynamic specialist selection | **Handoffs** |
| Parallel agent execution | **Graph** |
| Simple delegation | **Handoffs** |
| Type-safe data flow | **Graph** |

For complex workflows, see the [multi-agent graph recipe](../../examples/cookbook/multi-agent-graph.html): agents as nodes of a typed graph, run in parallel and joined.

---

## Best Practices

### 1. Clear Transfer Reasons

```scala
// Good - specific and actionable
Handoff.to("db", agent, "Database queries and SQL optimization")

// Bad - vague
Handoff.to("db", agent, "Technical stuff")
```

### 2. Focused Specialists

```scala
// Good - focused specialist
val sqlAgent = Agent.builder("sql", client)
  .withSystemPrompt("You are a SQL expert. Optimize queries and explain execution plans.")

// Bad - too broad
val everythingAgent = Agent.builder("everything", client)
  .withSystemPrompt("You know everything about databases, APIs, UI, and infrastructure.")
```

### 3. Changing a Family Changes Its Threads

The graph's version covers each agent's id, tools, middleware, handoffs and system prompt. If you
rebuild the root without a handoff that a stored thread's active agent was, `run` refuses that
thread rather than routing to a missing node.

### 4. Don't Overuse Handoffs

```scala
// Good - meaningful specialization
.withHandoffs(
  Handoff.to("math", mathAgent, "Complex mathematical calculations"),
  Handoff.to("legal", legalAgent, "Legal analysis and compliance")
)

// Bad - too granular
.withHandoffs(
  Handoff.to("addition", additionAgent, "Adding numbers"),
  Handoff.to("subtraction", subtractionAgent, "Subtracting numbers")
)
```

---

## Examples

| Example | Description |
|---------|-------------|
| [SimpleTriageHandoffExample](/examples/#handoff-examples) | Basic query routing |
| [MathSpecialistHandoffExample](/examples/#handoff-examples) | Math specialist delegation |
| [ContextPreservationExample](/examples/#handoff-examples) | Context preservation patterns |

[Browse all examples →](/examples/)

---

## Next Steps

- [Streaming Guide](streaming) - Real-time execution events
- [Memory Guide](memory) - Persistent context
- [Guardrails Guide](guardrails) - Input/output validation
