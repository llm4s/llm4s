---
layout: page
title: Guardrails
nav_order: 2
parent: Agents
grand_parent: User Guide
---

# Guardrails
{: .no_toc }

Validate agent inputs and outputs for safety, quality, and compliance.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Overview

Guardrails are validation functions that run before (input) and after (output) agent processing. They help ensure:

- **Safety** - Block harmful or inappropriate content
- **Quality** - Enforce response standards
- **Compliance** - Meet business requirements
- **Security** - Detect prompt injection and PII

Guardrails belong to the agent, not to a run: give them to the builder in a `GuardrailMiddleware`.

```scala
import org.llm4s.agent.Agent
import org.llm4s.agent.graph.middleware.GuardrailMiddleware

val agent = Agent.builder("assistant", client)
  .withTools(tools)
  .withMiddleware(
    GuardrailMiddleware(
      input = Seq(...),   // Validate each query before the LLM call
      output = Seq(...)   // Validate each final answer
    )
  )
  .build()
```

A guardrail that refuses does not make `run` return a `Left`: the turn ends with
`AgentStatus.Blocked(guardrail, reason)` - the first failing guardrail's name, and every failure's
error - and the thread stays usable, so the next `run` or `continueConversation` on it works. The
blocked turn is never kept: an input block stores nothing for that turn, and an output block removes
the whole turn - the query, any tool calls and results, and the answer - so `result.messages` is the
conversation as it was before the turn and the model never sees the blocked answer again. `usage`
still counts the blocked turn's model calls. A guardrail that transforms (`PIIMasker`) changes the
stored query or answer instead.

Underneath, a block is the graph runtime's *Block*: the run ends as a finished failure
(`RunResult.Failed` with a `GuardrailBlocked` error, the thread's checkpoint `Failed`), which
`recover` has nothing to continue and `start` accepts like a completed thread. A `Left` from another
middleware's `beforeAgent` or `afterAgent` blocks the same way, but is returned as that `Left`.

A list of guardrails runs completely: each guardrail, in order, on the value the previous one
returned, **even after one has failed**. That is why the block carries every failure's error, and why a
guardrail that costs a call - an LLM judge - runs on every turn that reaches its list. To stop at the first
failure, use [`CompositeGuardrail.sequential`](#sequential-short-circuit); the block then names the composite
as the guardrail.

A block sends one durable `agent.guardrail_blocked` event, naming the first failing guardrail and its phase
(`Input` or `Output`), and no event comes from a guardrail that passed or that ran after the first failure.
The run then ends with the kernel's `RunFailed`; `RunCompleted` is never sent for a blocked turn. An input
block comes before any model call. An output block comes after the turn's `ModelCallCompleted` events, since
the answer it refuses has been produced. See [Streaming](streaming) for the event stream.

Guardrails on the root agent guard its whole handoff family: they apply to every turn's query and
every final answer, whichever agent is active after a handoff. See
[Guardrails and Middleware Across Handoffs](handoffs#guardrails-and-middleware-across-handoffs).

---

## Built-in Guardrails

### Simple Validators

These guardrails run locally without LLM calls:

| Guardrail | Purpose | Example |
|-----------|---------|---------|
| `LengthCheck` | Enforce min/max length | `new LengthCheck(1, 10000)` |
| `ProfanityFilter` | Block profane content | `new ProfanityFilter()` |
| `JSONValidator` | Ensure valid JSON output | `new JSONValidator()` |
| `RegexValidator` | Pattern matching | `new RegexValidator("\\d{3}-\\d{4}")` |
| `ToneValidator` | Simple tone detection | `new ToneValidator(Tone.Professional)` |
| `PIIDetector` | Detect PII (email, SSN, etc.) | `new PIIDetector()` |
| `PIIMasker` | Mask detected PII | `new PIIMasker()` |
| `PromptInjectionDetector` | Detect injection attempts | `new PromptInjectionDetector()` |

### LLM-as-Judge Guardrails

These use an LLM to evaluate subjective qualities:

| Guardrail | Purpose | Example |
|-----------|---------|---------|
| `LLMSafetyGuardrail` | Content safety check | `new LLMSafetyGuardrail(client)` |
| `LLMFactualityGuardrail` | Verify factual accuracy | `new LLMFactualityGuardrail(client)` |
| `LLMQualityGuardrail` | Assess response quality | `new LLMQualityGuardrail(client)` |
| `LLMToneGuardrail` | Validate tone compliance | `new LLMToneGuardrail(client, "professional")` |

### RAG-Specific Guardrails

For retrieval-augmented generation:

| Guardrail | Purpose |
|-----------|---------|
| `GroundingGuardrail` | Verify answers are grounded in retrieved context |
| `ContextRelevanceGuardrail` | Check context relevance to query |
| `SourceAttributionGuardrail` | Ensure sources are cited |
| `TopicBoundaryGuardrail` | Prevent off-topic responses |

---

## Basic Usage

### Input Validation

```scala
import org.llm4s.agent.{ Agent, AgentStatus }
import org.llm4s.agent.graph.middleware.GuardrailMiddleware
import org.llm4s.agent.guardrails.builtin._

val result = for {
  agent <- Agent.builder("assistant", client)
    .withTools(tools)
    .withMiddleware(
      GuardrailMiddleware(
        input = Seq(
          new LengthCheck(min = 1, max = 10000),
          new ProfanityFilter(),
          new PromptInjectionDetector()
        ),
        output = Nil
      )
    )
    .build()
  result <- agent.run(userInput)
} yield result

result match {
  case Right(r) =>
    r.status match {
      case AgentStatus.Blocked(guardrail, reason) => println(s"Input rejected by $guardrail: $reason")
      case AgentStatus.Completed(answer)          => println(answer)
      case other                                  => println(s"Run ended: $other")
    }
  case Left(error) =>
    println(s"Run failed: ${error.message}")
}
```

### Output Validation

```scala
val agent = Agent.builder("assistant", client)
  .withTools(tools)
  .withMiddleware(GuardrailMiddleware(input = Nil, output = Seq(new JSONValidator(), new PIIMasker())))
  .build()

val result = agent.flatMap(_.run("Generate a JSON response with user data"))
```

### Combined Input/Output

```scala
val agent = Agent.builder("assistant", client)
  .withTools(tools)
  .withMiddleware(
    GuardrailMiddleware(
      input = Seq(
        new LengthCheck(1, 5000),
        new ProfanityFilter()
      ),
      output = Seq(
        new LLMSafetyGuardrail(client),
        new ToneValidator(Set(Tone.Professional))
      )
    )
  )
  .build()

val result = agent.flatMap(_.run(userInput))
```

---

## LLM-as-Judge Examples

### Safety Check

```scala
import org.llm4s.agent.guardrails.builtin.LLMSafetyGuardrail

val safetyGuardrail = new LLMSafetyGuardrail(client)

val agent = Agent.builder("storyteller", client)
  .withMiddleware(GuardrailMiddleware(input = Nil, output = Seq(safetyGuardrail)))
  .build()

agent.flatMap(_.run("Write a story"))
```

### Factuality Check

Verify responses are grounded in source documents:

```scala
import org.llm4s.agent.guardrails.builtin.LLMFactualityGuardrail

val factualityGuardrail = LLMFactualityGuardrail.strict(
  client = client,
  referenceContext = "The capital of France is Paris. Paris has a population of 2.1 million."
)

val agent = Agent.builder("geography", client)
  .withMiddleware(GuardrailMiddleware(input = Nil, output = Seq(factualityGuardrail)))
  .build()

agent.flatMap(_.run("What is the capital of France?"))
```

### Tone Validation

```scala
import org.llm4s.agent.guardrails.builtin.LLMToneGuardrail

val toneGuardrail = new LLMToneGuardrail(
  llmClient = client,
  allowedTones = Set("professional", "helpful")
)

val agent = Agent.builder("support", client)
  .withMiddleware(GuardrailMiddleware(input = Nil, output = Seq(toneGuardrail)))
  .build()

agent.flatMap(_.run("Help with customer complaint"))
```

---

## Composite Guardrails

Combine multiple guardrails with different strategies:

### All Must Pass (AND)

```scala
import org.llm4s.agent.guardrails.CompositeGuardrail

val strictValidation = CompositeGuardrail.all(Seq(
  new LengthCheck(1, 5000),
  new ProfanityFilter(),
  new PIIDetector()
))

// All guardrails must pass for input to be accepted
```

### Any Must Pass (OR)

```scala
val flexibleValidation = CompositeGuardrail.any(Seq(
  new RegexValidator("^[A-Z].*"),  // Starts with capital
  new RegexValidator("^\\d.*")     // Starts with digit
))

// At least one guardrail must pass
```

### Sequential (Short-Circuit)

```scala
val sequentialValidation = CompositeGuardrail.sequential(Seq(
  new LengthCheck(1, 10000),  // Check length first
  new ProfanityFilter(),      // Then profanity
  new PIIDetector()           // Then PII
))

// Stops at first failure, more efficient
```

### Using a composite in an agent

A composite is a `Guardrail[String]`, while `GuardrailMiddleware` takes `InputGuardrail`s and
`OutputGuardrail`s, so wrap it. A cast does not work: it throws a `ClassCastException`.

```scala
import org.llm4s.agent.graph.middleware.GuardrailMiddleware
import org.llm4s.agent.guardrails.{ CompositeGuardrail, Guardrail, InputGuardrail }
import org.llm4s.types.Result

def asInput(guardrail: Guardrail[String]): InputGuardrail = new InputGuardrail {
  val name: String                            = guardrail.name
  def validate(value: String): Result[String] = guardrail.validate(value)
}

val agent = Agent
  .builder("assistant", client)
  .withMiddleware(GuardrailMiddleware(Seq(asInput(sequentialValidation)), Seq.empty))
  .build()
```

`all` runs every guardrail and reports every failure; `any` stops at the first guardrail that passes, so
those after it do not run; `sequential` stops at the first failure.

---

## Custom Guardrails

### Basic Custom Guardrail

```scala
import org.llm4s.agent.guardrails.InputGuardrail
import org.llm4s.types.Result

class KeywordRequirementGuardrail(requiredKeywords: Set[String]) extends InputGuardrail {
  val name: String = "keyword-requirement"

  def validate(value: String): Result[String] = {
    val found = requiredKeywords.filter(kw => value.toLowerCase.contains(kw.toLowerCase))
    if (found.nonEmpty) {
      Right(value)
    } else {
      Left(LLMError.validation(
        s"Input must contain at least one of: ${requiredKeywords.mkString(", ")}"
      ))
    }
  }
}

// Usage
val guardrail = new KeywordRequirementGuardrail(Set("scala", "java", "kotlin"))
```

### Custom Output Guardrail

```scala
import org.llm4s.agent.guardrails.OutputGuardrail

class MaxSentenceCountGuardrail(maxSentences: Int) extends OutputGuardrail {
  val name: String = "max-sentence-count"

  def validate(value: String): Result[String] = {
    val sentenceCount = value.split("[.!?]+").length
    if (sentenceCount <= maxSentences) {
      Right(value)
    } else {
      Left(LLMError.validation(
        s"Response has $sentenceCount sentences, max allowed is $maxSentences"
      ))
    }
  }
}
```

### Custom LLM-Based Guardrail

```scala
import org.llm4s.agent.guardrails.LLMGuardrail

class CustomLLMGuardrail(client: LLMClient) extends LLMGuardrail(client) {
  val name: String = "custom-llm-check"

  override def buildPrompt(content: String): String = {
    s"""Evaluate if the following content is appropriate for a children's website.
       |Respond with only "PASS" or "FAIL" followed by a brief explanation.
       |
       |Content: $content""".stripMargin
  }

  override def parseResponse(response: String): Result[Boolean] = {
    if (response.trim.startsWith("PASS")) Right(true)
    else if (response.trim.startsWith("FAIL")) Right(false)
    else Left(LLMError.parsing("Unexpected response format"))
  }
}
```

---

## RAG Guardrails

### Basic RAG Setup

```scala
import org.llm4s.agent.guardrails.rag._

val ragGuardrails = RAGGuardrails.standard(client)

val agent = Agent.builder("rag", client)
  .withTools(tools)
  .withMiddleware(GuardrailMiddleware(ragGuardrails.inputGuardrails, ragGuardrails.outputGuardrails))
  .build()

agent.flatMap(_.run(question))
```

### Preset Configurations

```scala
// Minimal - basic safety only
val minimal = RAGGuardrails.minimal()

// Standard - balanced for production
val standard = RAGGuardrails.standard(client)

// Strict - maximum safety
val strict = RAGGuardrails.strict(client)

// Monitoring - warn mode, doesn't block
val monitoring = RAGGuardrails.monitoring(client)
```

### Individual RAG Guardrails

```scala
// Verify answer is grounded in retrieved context
val grounding = new GroundingGuardrail(
  client = client,
  retrievedContext = retrievedDocuments
)

// Check retrieved context is relevant to query
val relevance = new ContextRelevanceGuardrail(
  client = client,
  query = userQuery
)

// Ensure sources are properly cited
val attribution = new SourceAttributionGuardrail(
  client = client,
  sourceDocuments = sources
)

// Prevent off-topic responses
val topicBoundary = new TopicBoundaryGuardrail(
  client = client,
  allowedTopics = Set("programming", "software engineering")
)
```

---

## PII Detection and Masking

### Detect PII

```scala
import org.llm4s.agent.guardrails.builtin.PIIDetector

val piiDetector = new PIIDetector()

// Detects: emails, SSNs, credit cards, phone numbers, etc.
val agent = Agent.builder("assistant", client)
  .withTools(tools)
  .withMiddleware(GuardrailMiddleware(input = Seq(piiDetector), output = Nil))
  .build()

agent.flatMap(_.run(userInput))
```

### Mask PII in Output

```scala
import org.llm4s.agent.guardrails.builtin.PIIMasker

val piiMasker = new PIIMasker()

// Replaces PII with [REDACTED_EMAIL], [REDACTED_SSN], etc. - the stored answer is the masked one
val agent = Agent.builder("assistant", client)
  .withTools(tools)
  .withMiddleware(GuardrailMiddleware(input = Nil, output = Seq(piiMasker)))
  .build()

agent.flatMap(_.run("Get user details"))
```

### Supported PII Types

| Type | Pattern | Masked As |
|------|---------|-----------|
| Email | `user@domain.com` | `[REDACTED_EMAIL]` |
| SSN | `123-45-6789` | `[REDACTED_SSN]` |
| Credit Card | `4111-1111-1111-1111` | `[REDACTED_CC]` |
| Phone | `(555) 123-4567` | `[REDACTED_PHONE]` |
| IP Address | `192.168.1.1` | `[REDACTED_IP]` |

---

## Prompt Injection Protection

```scala
import org.llm4s.agent.guardrails.builtin.PromptInjectionDetector

val injectionDetector = new PromptInjectionDetector()

val agent = Agent.builder("assistant", client)
  .withTools(tools)
  .withMiddleware(GuardrailMiddleware(input = Seq(injectionDetector), output = Nil))
  .build()

agent.flatMap(_.run(userInput))

// Detects patterns like:
// - "Ignore previous instructions..."
// - "System: You are now..."
// - "---\nNew instructions:"
// - Base64 encoded payloads
```

---

## Error Handling

### Blocked Turns

A block is an outcome, not an error: `run` returns `Right` with `AgentStatus.Blocked`, the blocked
turn absent from `result.messages`, and the thread can take another turn.

```scala
agent.run(query) match {
  case Right(result) =>
    result.status match {
      case AgentStatus.Blocked(guardrail, reason) =>
        println(s"Guardrail '$guardrail' blocked the turn: $reason")
        // Take appropriate action (rephrase, notify user, log); agent.continueConversation(result, ...) still works
      case AgentStatus.Completed(answer) =>
        println(answer)
      case other =>
        println(s"Run ended: $other")
    }

  case Left(error) =>
    // a provider error, a tool's failure, a blank query, or another middleware's beforeAgent/afterAgent Left
    println(s"Run failed: ${error.message}")
}
```

### Validation Mode

Control how guardrail results are handled:

```scala
import org.llm4s.agent.guardrails.ValidationMode

// Block on failure (default)
val blocking = ValidationMode.Block

// Warn only, continue processing
val warn = ValidationMode.Warn

// Log and continue
val log = ValidationMode.Log
```

---

## Best Practices

### 1. Layer Your Guardrails

```scala
// Fast, local checks first
val input = Seq(
  new LengthCheck(1, 10000),        // Cheapest first
  new ProfanityFilter(),             // Still fast
  new PromptInjectionDetector(),     // Pattern matching
  new PIIDetector()                  // More complex but local
)

// LLM checks for output only (expensive)
val output = Seq(
  new JSONValidator(),               // Fast, local
  new LLMSafetyGuardrail(client)    // Expensive, last
)

val agent = Agent.builder("assistant", client)
  .withMiddleware(GuardrailMiddleware(input, output))
  .build()
```

### 2. Use Appropriate Guardrails for Each Use Case

| Use Case | Recommended Guardrails |
|----------|------------------------|
| Customer support | `ProfanityFilter`, `ToneValidator`, `LLMSafetyGuardrail` |
| Code generation | `LengthCheck`, `JSONValidator` (for structured output) |
| RAG application | `GroundingGuardrail`, `SourceAttributionGuardrail` |
| Content moderation | `PIIDetector`, `ProfanityFilter`, `LLMSafetyGuardrail` |
| Form processing | `RegexValidator`, `LengthCheck` |

### 3. Test Your Guardrails

```scala
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class GuardrailSpec extends AnyFlatSpec with Matchers {
  "LengthCheck" should "reject empty input" in {
    val guardrail = new LengthCheck(min = 1, max = 100)
    guardrail.validate("") shouldBe a[Left[_, _]]
  }

  it should "accept valid input" in {
    val guardrail = new LengthCheck(min = 1, max = 100)
    guardrail.validate("Hello") shouldBe Right("Hello")
  }
}
```

---

## Examples

| Example | Description |
|---------|-------------|
| [BasicInputValidationExample](/examples/#basic) | Length and profanity checks |
| [JSONOutputValidationExample](/examples/#guardrails-examples) | JSON output validation |
| [LLMJudgeGuardrailExample](/examples/#guardrails-examples) | LLM-as-Judge patterns |
| [CompositeGuardrailExample](/examples/#composite) | Combining guardrails |
| [CustomGuardrailExample](/examples/#custom) | Building custom validators |
| [FactualityGuardrailExample](/examples/#guardrails-examples) | RAG factuality checking |

[Browse all examples →](/examples/)

---

## Next Steps

- [Memory Guide](memory) - Persistent context across conversations
- [Handoffs Guide](handoffs) - Agent-to-agent delegation
- [Streaming Guide](streaming) - Real-time execution events
