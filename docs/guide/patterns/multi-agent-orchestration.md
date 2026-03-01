---
layout: page
title: Multi-Agent Orchestration
parent: Real-World Application Patterns
nav_order: 1
---

# Multi-Agent Orchestration Patterns

> **Note:** Code examples in this guide are illustrative pseudocode showing recommended patterns. For working examples using the actual LLM4S API, see [modules/samples](../../../modules/samples/).

Learn how to design systems where multiple agents collaborate, delegate tasks, and handle complex workflows. This guide covers agent communication patterns, handoff mechanisms, and failure recovery strategies.

## Overview

Multi-agent systems allow you to decompose complex tasks into specialized agents. Instead of one agent doing everything, each agent focuses on specific domain expertise:

- **Routing Agent**: Routes requests to appropriate specialists
- **Domain Specialists**: Domain-specific knowledge (finance, legal, technical)
- **Aggregator Agent**: Combines results from multiple agents
- **Quality Assurance Agent**: Validates outputs before returning to user

---

## Pattern 1: Sequential Delegation

### Use Case
Simple workflows where tasks need to execute in order, each building on previous results.

### Implementation

```text
import org.llm4s.agents._
import org.llm4s.Result

object SequentialDelegation {
  
  // Agent for research
  val researchAgent = Agent(
    name = "Research Agent",
    model = openaiClient,
    systemPrompt = "You are a research specialist. Find relevant information."
  )
  
  // Agent for analysis
  val analysisAgent = Agent(
    name = "Analysis Agent",
    model = openaiClient,
    systemPrompt = "You are an analyst. Analyze provided information critically."
  )
  
  // Agent for summarization
  val summaryAgent = Agent(
    name = "Summary Agent",
    model = openaiClient,
    systemPrompt = "You are a technical writer. Create clear, concise summaries."
  )
  
  // Sequential workflow
  def processQuery(query: String): Result[String] = for {
    // Step 1: Research
    researchResult <- researchAgent.run(query)
    researchText = researchResult.message
    
    // Step 2: Analyze findings
    analysisResult <- analysisAgent.run(
      s"Analyze this research: $researchText"
    )
    analysisText = analysisResult.message
    
    // Step 3: Summarize
    summaryResult <- summaryAgent.run(
      s"Create a summary of this analysis: $analysisText"
    )
  } yield summaryResult.message
}
```

### Advantages
- ✅ Simple to understand and implement
- ✅ Clear data flow and dependencies
- ✅ Easy to debug individual steps

### Disadvantages
- ❌ Slower due to sequential execution
- ❌ Single point of failure (one agent fails, whole pipeline fails)
- ❌ No parallelization opportunities

---

## Pattern 2: Parallel Delegation with Aggregation

### Use Case
When multiple independent tasks can run in parallel, then results are combined.

### Implementation

```text
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

object ParallelDelegation {
  
  val financialAgent = Agent(
    name = "Financial Agent",
    model = openaiClient,
    systemPrompt = "You are a financial analyst."
  )
  
  val technicalAgent = Agent(
    name = "Technical Agent",
    model = openaiClient,
    systemPrompt = "You are a technical expert."
  )
  
  val marketAgent = Agent(
    name = "Market Agent",
    model = openaiClient,
    systemPrompt = "You are a market researcher."
  )
  
  val aggregatorAgent = Agent(
    name = "Aggregator",
    model = openaiClient,
    systemPrompt = "Synthesize viewpoints from multiple experts."
  )
  
  def analyzeCompany(company: String): Result[String] = {
    // Run three agents in parallel
    val financialFuture = Future(
      financialAgent.run(s"Analyze finances of $company")
    )
    val technicalFuture = Future(
      technicalAgent.run(s"Analyze tech stack of $company")
    )
    val marketFuture = Future(
      marketAgent.run(s"Analyze market position of $company")
    )
    
    // Wait for all results
    val allResults = for {
      fin <- financialFuture
      tech <- technicalFuture
      market <- marketFuture
    } yield (fin, tech, market)
    
    // Convert Future to Result and aggregate
    allResults.toResult.flatMap { case (fin, tech, market) =>
      aggregatorAgent.run(
        s"""
        |Financial Analysis: ${fin.message}
        |Technical Analysis: ${tech.message}
        |Market Analysis: ${market.message}
        |
        |Provide a comprehensive assessment combining all three perspectives.
        """.stripMargin
      ).map(_.message)
    }
  }
}
```

### Advantages
- ✅ Parallel execution reduces total time
- ✅ Independent agents can scale separately
- ✅ Natural load distribution

### Disadvantages
- ❌ Increased complexity
- ❌ Harder to debug concurrent issues
- ❌ Requires aggregation logic

---

## Pattern 3: Handoff Mechanism

### Use Case
Transferring control from one agent to another when current agent reaches its limits or recognizes need for specialist.

### Implementation

```text
import org.llm4s.agents.handoff._

object HandoffPattern {
  
  // Routing agent that delegates to specialists
  val routingAgent = Agent(
    name = "Routing Agent",
    model = openaiClient,
    systemPrompt = """
      You are a routing agent. Analyze the request and determine 
      which specialist agent should handle it:
      - Technical questions → technical_specialist
      - Account issues → account_specialist
      - Billing issues → billing_specialist
      - Escalated issues → escalation_team
    """
  )
  
  // Specialist agents
  val technicalSpecialist = Agent(
    name = "Technical Specialist",
    model = openaiClient,
    systemPrompt = "Solve technical problems with deep expertise."
  )
  
  val accountSpecialist = Agent(
    name = "Account Specialist",
    model = openaiClient,
    systemPrompt = "Help with account management and user issues."
  )
  
  val billingSpecialist = Agent(
    name = "Billing Specialist",
    model = openaiClient,
    systemPrompt = "Resolve billing and payment issues."
  )
  
  // Handoff decision
  sealed trait HandoffTarget
  case object Technical extends HandoffTarget
  case object Account extends HandoffTarget
  case object Billing extends HandoffTarget
  case object EscalationTeam extends HandoffTarget
  
  def routeAndHandle(userRequest: String): Result[String] = for {
    // Step 1: Route to determine target
    routing <- routingAgent.run(
      s"Request: $userRequest\nDetermine target: technical_specialist, account_specialist, billing_specialist, or escalation_team"
    )
    
    // Step 2: Extract routing decision
    target = routing.message match {
      case s if s.contains("technical_specialist") => Technical
      case s if s.contains("account_specialist") => Account
      case s if s.contains("billing_specialist") => Billing
      case _ => EscalationTeam
    }
    
    // Step 3: Hand off to appropriate specialist
    response <- target match {
      case Technical => technicalSpecialist.run(userRequest)
      case Account => accountSpecialist.run(userRequest)
      case Billing => billingSpecialist.run(userRequest)
      case EscalationTeam => 
        Result.failure(
          "This issue requires human escalation. Ticket created."
        )
    }
  } yield response.message
}
```

### Advantages
- ✅ Automatic routing based on request type
- ✅ Specialists have focused expertise
- ✅ Scales to many agent types
- ✅ Built on LLM4S handoff support

### Disadvantages
- ❌ Routing errors can send request to wrong agent
- ❌ Context loss during handoff
- ❌ Overhead of routing step

---

## Pattern 4: Hierarchical Agent Teams

### Use Case
Complex systems with multiple management levels (team leads, managers, executives) with clear authority chains.

### Implementation

```text
object HierarchicalTeams {
  
  // Team leads (specialists)
  val engineeringLead = Agent(name = "Engineering Lead")
  val designLead = Agent(name = "Design Lead")
  val productLead = Agent(name = "Product Lead")
  
  // Manager (coordinates across teams)
  val projectManager = Agent(
    name = "Project Manager",
    systemPrompt = """You coordinate across engineering, design, and product teams.
      You can delegate to leads and synthesize their feedback."""
  )
  
  // Executive (makes final decisions)
  val directorAgent = Agent(
    name = "Director",
    systemPrompt = """You review project status and make strategic decisions.
      You receive summaries from the project manager."""
  )
  
  def planNewFeature(featureDescription: String): Result[String] = for {
    // Level 1: Team leads provide input
    engineeringPlan <- engineeringLead.run(
      s"Plan engineering approach for: $featureDescription"
    )
    designPlan <- designLead.run(
      s"Plan design approach for: $featureDescription"
    )
    productPlan <- productLead.run(
      s"Plan product approach for: $featureDescription"
    )
    
    // Level 2: Manager synthesizes feedback
    managerSummary <- projectManager.run(
      s"""Coordinate feedback on feature:
        |Engineering: ${engineeringPlan.message}
        |Design: ${designPlan.message}
        |Product: ${productPlan.message}
        |
        |Create a comprehensive plan addressing all perspectives.""".stripMargin
    )
    
    // Level 3: Executive makes decision
    executiveDecision <- directorAgent.run(
      s"Review this plan and approve/reject: ${managerSummary.message}"
    )
  } yield executiveDecision.message
}
```

### Advantages
- ✅ Clear organization mirroring real teams
- ✅ Scalable to deep hierarchies
- ✅ Natural delegation and authority
- ✅ Easy to understand and maintain

### Disadvantages
- ❌ More agents = more cost
- ❌ Deeper hierarchies = slower execution
- ❌ Potential for bottlenecks at higher levels

---

## Pattern 5: Context Management in Handoffs

### Use Case
Ensuring context is preserved and relevant when transferring between agents.

### Implementation

```text
import org.llm4s.agents.memory._

object ContextPreservation {
  
  case class HandoffContext(
    conversationHistory: List[String],
    relevantDocuments: List[String],
    userProfile: Option[String],
    constraints: List[String]
  )
  
  def handoffWithContext(
    fromAgent: Agent,
    toAgent: Agent,
    context: HandoffContext
  ): Result[String] = {
    // Build rich context for receiving agent
    val contextPrompt = s"""
      |## Conversation Context
      |${context.conversationHistory.mkString("\n")}
      |
      |## Relevant Information
      |${context.relevantDocuments.mkString("\n")}
      |
      |## User Context
      |${context.userProfile.getOrElse("No profile available")}
      |
      |## Constraints
      |${context.constraints.mkString("\n")}
      """.stripMargin
    
    // Continue conversation with full context
    toAgent.run(contextPrompt).map(_.message)
  }
  
  // Practical example: Customer support escalation
  def supportEscalation(
    userMessage: String,
    previousInteraction: String
  ): Result[String] = {
    val context = HandoffContext(
      conversationHistory = List(previousInteraction, userMessage),
      relevantDocuments = List("Knowledge base article on billing"),
      userProfile = Some("Premium customer, high lifetime value"),
      constraints = List("No refunds without authorization")
    )
    
    val level1Agent = Agent(name = "Level 1 Support")
    val level2Agent = Agent(name = "Level 2 Support")
    
    // First attempt
    level1Agent.run(userMessage).flatMap { result =>
      if (result.message.contains("escalate")) {
        // Escalate with full context
        handoffWithContext(level1Agent, level2Agent, context)
      } else {
        Result.success(result.message)
      }
    }
  }
}
```

### Advantages
- ✅ Prevents context loss during handoffs
- ✅ Receiving agent has full information
- ✅ Better quality responses in second agent
- ✅ Improved user experience

### Disadvantages
- ❌ Larger context = higher token cost
- ❌ Must carefully select relevant information
- ❌ Requires memory/context management system

---

## Failure Handling in Multi-Agent Systems

### Circuit Breaker for Agents

```text
object AgentCircuitBreaker {
  
  case class CircuitBreakerState(
    isOpen: Boolean = false,
    failureCount: Int = 0,
    successCount: Int = 0,
    lastFailureTime: Long = 0
  )
  
  def runWithCircuitBreaker(
    agent: Agent,
    message: String,
    state: CircuitBreakerState
  ): Result[String] = {
    // Check if circuit is open
    if (state.isOpen) {
      val timeSinceFailure = System.currentTimeMillis() - state.lastFailureTime
      if (timeSinceFailure < 60000) { // 1 minute window
        return Result.failure("Circuit breaker is open, agent temporarily unavailable")
      }
    }
    
    // Try to execute agent
    agent.run(message) match {
      case Result.Success(response) =>
        Result.success(response.message)
      case Result.Failure(error) =>
        val newFailureCount = state.failureCount + 1
        if (newFailureCount >= 5) { // Open after 5 failures
          Result.failure(
            s"Agent failed 5 times, circuit breaker opened: $error"
          )
        } else {
          Result.failure(error)
        }
    }
  }
}
```

### Fallback Agents

```text
object FallbackAgents {
  
  def runWithFallback(
    primaryAgent: Agent,
    fallbackAgent: Agent,
    message: String
  ): Result[String] = {
    primaryAgent.run(message) match {
      case Result.Success(response) => Result.success(response.message)
      case Result.Failure(error) =>
        println(s"Primary agent failed: $error. Using fallback...")
        fallbackAgent.run(message).map(_.message)
    }
  }
}
```

---

## Best Practices

### ✅ Do's

- **Clear responsibilities**: Each agent should have a well-defined role
- **Preserve context**: Pass relevant information during handoffs
- **Monitor agent health**: Track success/failure rates
- **Implement timeouts**: Prevent agents from hanging indefinitely
- **Log all transitions**: Track which agent handled what for auditing
- **Test agent chains**: Verify entire workflows work end-to-end
- **Handle failures gracefully**: Always have fallback paths

### ❌ Don'ts

- **Don't create circular dependencies**: Avoid Agent A calling Agent B calling Agent A
- **Don't lose context**: Never handoff without relevant information
- **Don't ignore timeouts**: Always set execution time limits
- **Don't forget to validate**: Verify outputs before passing between agents
- **Don't create too many levels**: Deep hierarchies become hard to manage
- **Don't forget cost tracking**: Monitor token usage across agents
- **Don't hardcode routing**: Make routing decisions learnable

---

## Performance Characteristics

| Pattern | Latency | Cost | Complexity |
|---------|---------|------|-----------|
| Sequential | High | Low | Low |
| Parallel | Low | Medium | Medium |
| Handoff | Medium | Medium | Medium |
| Hierarchical | Very High | High | High |
| Context Preservation | Medium | High | Medium |

---

## Common Pitfalls & Solutions

| Pitfall | Problem | Solution |
|---------|---------|----------|
| Context loss | Agent doesn't know conversation history | Explicit context passing |
| Routing errors | Request goes to wrong agent | Validate routing decision + add fallback |
| Infinite loops | Agent A → B → A → ... | Graph cycle detection, timeout guards |
| Cost explosion | Too many agents or deep chains | Monitor tokens, limit depth |
| Slow execution | Sequential chains take too long | Parallelize when possible |

---

## Example: Complete Support System

```text
object CompleteSupportSystem {
  
  // Routing agent determines issue type
  val routingAgent = Agent(name = "Support Router")
  
  // Specialist agents for different issue types
  val technicalAgent = Agent(name = "Technical Support")
  val billingAgent = Agent(name = "Billing Support")
  val accountAgent = Agent(name = "Account Support")
  
  // Escalation agent for complex issues
  val escalationAgent = Agent(name = "Escalation Team")
  
  def handleSupportRequest(userMessage: String): Result[String] = {
    // Step 1: Route the request
    routingAgent.run(userMessage).flatMap { routing =>
      val target = routing.message
      
      // Step 2: Handle based on routing decision
      (if (target.contains("technical")) {
        technicalAgent.run(userMessage)
      } else if (target.contains("billing")) {
        billingAgent.run(userMessage)
      } else if (target.contains("account")) {
        accountAgent.run(userMessage)
      } else {
        escalationAgent.run(userMessage)
      }).flatMap { response =>
        // Step 3: Check if further escalation needed
        if (response.message.contains("escalate")) {
          escalationAgent.run(s"${response.message}\n\nPlease escalate to human team.")
        } else {
          Result.success(response.message)
        }
      }
    }
  }
}
```

---

## See Also

- [Error Recovery Patterns](./error-recovery.md) - Handle agent failures
- [Production Monitoring](./production-monitoring.md) - Monitor multi-agent systems
- [Scaling Strategies](./scaling-strategies.md) - Scale agent deployments
- [Agent Framework Documentation](../agents/)

---

**Last Updated:** February 2026  
**Status:** Production Ready
