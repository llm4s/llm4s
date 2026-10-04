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
import org.llm4s.llmconnect.model.SystemMessage

val result = for {
  providerConfig <- Llm4sConfig.provider()
  client <- LLMConnect.getClient(providerConfig)

  // Create specialist agent
  mathAgent = new Agent(
    client = client,
    systemMessage = Some(SystemMessage(
      "You are a math expert. Solve problems step-by-step."
    ))
  )

  // Create main agent with handoffs
  mainAgent = new Agent(client)

  // Run with handoff capability
  state <- mainAgent.run(
    query = "What is the integral of x^2?",
    tools = ToolRegistry.empty,
    handoffs = Seq(
      Handoff.to("math", mathAgent, "Math expertise required")
    )
  )
} yield state
```

---

## Handoff Configuration

### Basic Handoff

```scala
import org.llm4s.agent.Handoff

val handoff = Handoff.to(
  "specialist",
  specialistAgent,
  "Domain expertise required"
)
```

### With Context Preservation

```scala
// Preserve full conversation history
val handoff = Handoff(
  id = "specialist",
  targetAgent = specialistAgent,
  transferReason = Some("Specialist needed"),
  preserveContext = true,        // Keep conversation history
  transferSystemMessage = false  // Use target's system message
)
```

### Fresh Start (No Context)

```scala
// Start fresh with specialist
val handoff = Handoff(
  id = "specialist",
  targetAgent = specialistAgent,
  transferReason = Some("Fresh analysis needed"),
  preserveContext = false,        // Don't transfer history
  transferSystemMessage = false   // Use target's system message
)
```

### Transfer System Message

```scala
// Transfer original instructions to specialist
val handoff = Handoff(
  id = "specialist",
  targetAgent = specialistAgent,
  transferReason = Some("Continue with same instructions"),
  preserveContext = true,
  transferSystemMessage = true  // Keep original system message
)
```

---

## Handoff Options

| Option | Default | Description |
|--------|---------|-------------|
| `targetAgent` | Required | The agent to hand off to |
| `transferReason` | `None` | Description shown to LLM for routing |
| `preserveContext` | `true` | Transfer conversation history |
| `transferSystemMessage` | `false` | Transfer original system message |

---

## Multiple Handoffs

Provide multiple specialists for intelligent routing:

```scala
val result = mainAgent.run(
  query = userQuery,
  tools = tools,
  handoffs = Seq(
    Handoff.to("math", mathAgent, "Mathematical calculations and proofs"),
    Handoff.to("code", codeAgent, "Programming and code review"),
    Handoff.to("legal", legalAgent, "Legal questions and contracts"),
    Handoff.to("medical", medicalAgent, "Health and medical information")
  )
)
```

The LLM sees descriptions and chooses the appropriate specialist.

---

## How Handoffs Work

### 1. Tools are Generated

When handoffs are provided, the agent generates handoff tools:

```scala
// Internal tool generated for each handoff
ToolFunction(
  name = "handoff_to_math_expert",
  description = "Transfer to specialist: Mathematical calculations and proofs",
  function = () => RequestHandoff(targetAgent, transferReason, ...)
)
```

### 2. LLM Decides

The LLM can choose to:
- Answer directly (no handoff)
- Call a regular tool
- Request a handoff by calling the handoff tool

### 3. Handoff Executes

When handoff is requested:
1. Agent status changes to `HandoffRequested`
2. Context is prepared based on settings
3. Target agent receives the query
4. Target agent processes and returns response

### 4. Response Returns

The target agent's response becomes part of the conversation.

---

## Specialist Agent Patterns

### Domain Expert

```scala
val physicsAgent = new Agent(
  client = client,
  systemMessage = Some(SystemMessage(
    """You are a physics expert with deep knowledge of:
      |- Classical mechanics
      |- Quantum mechanics
      |- Thermodynamics
      |- Electromagnetism
      |
      |Provide detailed explanations with equations when helpful.""".stripMargin
  ))
)

mainAgent.run(
  query = "Explain quantum entanglement",
  tools = tools,
  handoffs = Seq(
    Handoff.to("physics", physicsAgent, "Physics questions requiring expert explanation")
  )
)
```

### Tool Specialist

```scala
// Agent with specialized tools
val dataAgent = new Agent(
  client = client,
  systemMessage = Some(SystemMessage("You are a data analysis specialist."))
)

val dataTools = new ToolRegistry(Seq(
  queryDatabaseTool,
  generateChartTool,
  exportCSVTool
))

// Main agent can hand off data queries
mainAgent.run(
  query = "Analyze our Q4 sales data",
  tools = basicTools,
  handoffs = Seq(
    Handoff(
      id = "data",
      targetAgent = dataAgent,
      transferReason = Some("Data analysis with database access")
    )
  )
)
```

### Customer Support Triage

```scala
// Specialist agents
val billingAgent = new Agent(
  client = client,
  systemMessage = Some(SystemMessage("You handle billing and payment issues."))
)

val technicalAgent = new Agent(
  client = client,
  systemMessage = Some(SystemMessage("You solve technical problems."))
)

val salesAgent = new Agent(
  client = client,
  systemMessage = Some(SystemMessage("You help with purchases and upgrades."))
)

// Triage agent
val triageAgent = new Agent(
  client = client,
  systemMessage = Some(SystemMessage(
    "You are a customer support triage agent. Route queries to the appropriate specialist."
  ))
)

triageAgent.run(
  query = "I can't log into my account and my payment failed",
  tools = ToolRegistry.empty,
  handoffs = Seq(
    Handoff.to("billing", billingAgent, "Billing, payments, and subscription issues"),
    Handoff.to("technical", technicalAgent, "Technical problems and account access"),
    Handoff.to("sales", salesAgent, "Purchases, upgrades, and pricing questions")
  )
)
```

---

## Handling Handoff Results

### Check for Handoff Status

```scala
val result = mainAgent.run(query, tools, handoffs)

result match {
  case Right(state) if state.status == AgentStatus.Complete =>
    println(s"Completed: ${state.lastAssistantMessage}")

  case Right(state) if state.status == AgentStatus.HandoffRequested =>
    // Handoff was requested but you're handling manually
    val handoffInfo = state.requestedHandoff
    println(s"Handoff to: ${handoffInfo.targetAgentName}")

  case Left(error) =>
    println(s"Error: $error")
}
```

### With Streaming Events

```scala
import org.llm4s.agent.streaming._

mainAgent.runWithEvents(query, tools, handoffs) {
  case HandoffStarted(targetName, reason, preserveContext, _) =>
    println(s"Handing off to $targetName: $reason")

  case HandoffCompleted(targetName, success, _) =>
    println(s"Handoff to $targetName: ${if (success) "success" else "failed"}")

  case AgentCompleted(state, _, _, _) =>
    println(s"Final: ${state.lastAssistantMessage}")

  case _ => ()
}
```

---

## Context Preservation Examples

### Full Context (Default)

```scala
// Specialist sees entire conversation
val handoff = Handoff(
  id = "specialist",
  targetAgent = specialist,
  preserveContext = true,
  transferSystemMessage = false
)

// User: "I'm building a Scala app"
// Assistant: "Great! What kind of app?"
// User: "A REST API with database access"
// <handoff to database specialist>
// Specialist sees all messages above
```

### Fresh Context

```scala
// Specialist starts fresh
val handoff = Handoff(
  id = "specialist",
  targetAgent = specialist,
  preserveContext = false,
  transferSystemMessage = false
)

// User: "I'm building a Scala app"
// Assistant: "Great! What kind of app?"
// User: "A REST API with database access"
// <handoff to database specialist>
// Specialist only sees: "A REST API with database access"
```

### With System Message

```scala
// Specialist inherits original instructions
val handoff = Handoff(
  id = "specialist",
  targetAgent = specialist,
  preserveContext = true,
  transferSystemMessage = true
)

// Original agent's system message is prepended to specialist's
```

---

## Handoffs vs Orchestration

| Use Case | Approach |
|----------|----------|
| 2-3 specialists, LLM-driven routing | **Handoffs** |
| Complex multi-agent workflows | **Orchestration (DAGs)** |
| Dynamic specialist selection | **Handoffs** |
| Parallel agent execution | **Orchestration** |
| Simple delegation | **Handoffs** |
| Type-safe data flow | **Orchestration** |

For complex workflows, see [Orchestration documentation](/design/agent-framework-roadmap#orchestration).

---

## Best Practices

### 1. Clear Transfer Reasons

```scala
// Good - specific and actionable
Handoff.to("target", agent, "Database queries and SQL optimization")

// Bad - vague
Handoff.to("target", agent, "Technical stuff")
```

### 2. Focused Specialists

```scala
// Good - focused specialist
val sqlAgent = new Agent(
  systemMessage = Some(SystemMessage(
    "You are a SQL expert. Optimize queries and explain execution plans."
  ))
)

// Bad - too broad
val everythingAgent = new Agent(
  systemMessage = Some(SystemMessage(
    "You know everything about databases, APIs, UI, and infrastructure."
  ))
)
```

### 3. Appropriate Context Decisions

```scala
// Preserve context when history matters
Handoff(id = "follow-up", targetAgent = followUpAgent, preserveContext = true)

// Fresh start for independent analysis
Handoff(id = "review", targetAgent = reviewAgent, preserveContext = false)
```

### 4. Don't Overuse Handoffs

```scala
// Good - meaningful specialization
handoffs = Seq(
  Handoff.to("math", mathAgent, "Complex mathematical calculations"),
  Handoff.to("legal", legalAgent, "Legal analysis and compliance")
)

// Bad - too granular
handoffs = Seq(
  Handoff.to("addition", additionAgent, "Adding numbers"),
  Handoff.to("subtraction", subtractionAgent, "Subtracting numbers"),
  Handoff.to("multiplication", multiplicationAgent, "Multiplying numbers"),
  // ...
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
