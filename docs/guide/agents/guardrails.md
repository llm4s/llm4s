---
layout: page
title: Guardrails
description: "Validate an agent's inputs and outputs with LLM4S guardrails: length, profanity, JSON and regex checks, LLM-as-judge and RAG guardrails."
search_title: "Agent guardrails in Scala: validate LLM input and output"
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
failure, use [`CompositeGuardrail.sequential`](#sequential-short-circuit) (adapted with `InputGuardrail.from`,
see [Using a composite in an agent](#using-a-composite-in-an-agent)); the block then names the composite as the
guardrail. Running the whole list is the settled default ([#1521](https://github.com/llm4s/llm4s/issues/1521)): it
reports every reason a value was refused in one block, and an early stop is one composite away, while the opposite
default would hide the other failures with no way to ask for them.

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

Each judge scores the content and passes it when the score reaches its `threshold`. The threshold must be between 0.0 and 1.0: for any other value, or NaN, `validate` returns a `ValidationError` on the field `threshold` without calling the judge.

### RAG-Specific Guardrails

For retrieval-augmented generation (experimental). The first three check an answer against the context retrieved for
it, and do nothing until they are given that context: see [RAG Guardrails](#rag-guardrails).

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
`OutputGuardrail`s, so adapt it with `InputGuardrail.from` or `OutputGuardrail.from`. A cast does not work: it
throws a `ClassCastException`.

```scala
import org.llm4s.agent.graph.middleware.GuardrailMiddleware
import org.llm4s.agent.guardrails.InputGuardrail

val agent = Agent
  .builder("assistant", client)
  .withMiddleware(GuardrailMiddleware(Seq(InputGuardrail.from(sequentialValidation)), Seq.empty))
  .build()
```

`from` keeps the guardrail's name and description and passes the hook's context on, so an LLM judge inside the
composite has its model calls counted in the thread's usage (a hand-written wrapper that overrides only
`validate(value)` loses that). An `InputGuardrail` passed to `InputGuardrail.from` comes back unchanged.

`all` runs every guardrail and reports every failure; `any` stops at the first guardrail that passes, so
those after it do not run; `sequential` stops at the first failure.

---

## Custom Guardrails

### Basic Custom Guardrail

```scala
import org.llm4s.agent.guardrails.InputGuardrail
import org.llm4s.error.ValidationError
import org.llm4s.types.Result

class KeywordRequirementGuardrail(requiredKeywords: Set[String]) extends InputGuardrail {
  val name: String = "keyword-requirement"

  def validate(value: String): Result[String] = {
    val found = requiredKeywords.filter(kw => value.toLowerCase.contains(kw.toLowerCase))
    if (found.nonEmpty) {
      Right(value)
    } else {
      Left(ValidationError.invalid(
        "input",
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
import org.llm4s.error.ValidationError
import org.llm4s.types.Result

class MaxSentenceCountGuardrail(maxSentences: Int) extends OutputGuardrail {
  val name: String = "max-sentence-count"

  def validate(value: String): Result[String] = {
    val sentenceCount = value.split("[.!?]+").length
    if (sentenceCount <= maxSentences) {
      Right(value)
    } else {
      Left(ValidationError.invalid(
        "output",
        s"Response has $sentenceCount sentences, max allowed is $maxSentences"
      ))
    }
  }
}
```

### Custom LLM-Based Guardrail

```scala
import org.llm4s.agent.guardrails.LLMGuardrail
import org.llm4s.llmconnect.LLMClient

class CustomLLMGuardrail(client: LLMClient) extends LLMGuardrail {
  val name: String             = "custom-llm-check"
  val llmClient: LLMClient     = client
  val evaluationPrompt: String = "Rate if this content is appropriate for a children's website (0-1)"
  override val threshold       = 0.8
}
```

---

## RAG Guardrails

`org.llm4s.agent.guardrails.rag` is **experimental**: its API may change in a minor release.

Three of these guardrails are `RAGGuardrail`s - `GroundingGuardrail`, `ContextRelevanceGuardrail` and
`SourceAttributionGuardrail`. Each judges an answer against what was retrieved for it, so each needs a
`RAGContext`: the query, the retrieved chunks and, optionally, a source per chunk. The context is never a
constructor argument. It is given with the answer, to `validateWithContext`:

```scala
import org.llm4s.agent.Agent
import org.llm4s.agent.graph.middleware.GuardrailMiddleware
import org.llm4s.agent.guardrails.GuardrailAction
import org.llm4s.agent.guardrails.rag._
import org.llm4s.toolapi.ToolRegistry
```

```scala
val context = RAGContext.withSources(
  query = "What is the refund window?",
  chunks = Seq("Refunds are accepted within 30 days of purchase."),
  sources = Seq("refund-policy.md")
)

val grounding = new GroundingGuardrail(llmClient = judge, threshold = 0.8)

// Right(answer) when the judge scores it at or above the threshold, Left(ValidationError) otherwise
val checked = grounding.validateWithContext("You can get a refund within 30 days.", context)
```

`judge` is the `LLMClient` that scores the answer; it can be the agent's own client or a cheaper model.

{: .warning }
> The plain `validate(answer)` of a `RAGGuardrail` has no context to check against, so it **passes every answer**
> and calls no judge. Put one in a `GuardrailMiddleware` as it is and it checks nothing: wrap it with
> `RAGGuardrail.withTurnContext` first, as below.

### In an agent

An agent retrieves by calling a tool, so the retrieved text of a turn is in that turn's tool results.
`RAGGuardrail.withTurnContext` turns a `RAGGuardrail` into an output guardrail for `GuardrailMiddleware`: you give it
a function from the turn's messages to the turn's `RAGContext`, and it validates each final answer with that context.
`RAGContext.fromToolResults` is the usual function: the query is the turn's user message, and each result of the
tools you name is one chunk.

```scala
// The turn's context: its user message is the query, each result of the "search_docs" tool is a chunk
val grounded = RAGGuardrail.withTurnContext(GroundingGuardrail.balanced(judge)) { turn =>
  Some(RAGContext.fromToolResults(turn, Set("search_docs")))
}

val agent = Agent
  .builder("support", client)
  .withTools(new ToolRegistry(Seq(searchDocs)))
  .withMiddleware(new GuardrailMiddleware(input = Seq.empty, output = Seq(grounded)))
  .build()

val result = agent.flatMap(_.run("What is the refund window?"))
```

What to know:

- **The turn** is the current turn only, as the thread stores it: the user message, each assistant message with its
  tool calls, each tool result, and the final answer last. Results retrieved in an earlier turn are not in it.
- **A chunk is a tool's whole result**, as the model read it (the tool's JSON, or the error it returned).
  `fromToolResults` sets no sources. A tool that returns several passages, or their sources, needs your own function:
  parse the `ToolMessage`s of the turn and build the context with `RAGContext.withSources`.
- **Returning `None`** skips the check for that turn, and the answer passes. Returning a context with no chunks does
  not: the guardrail's own rule applies, and a blocking `GroundingGuardrail` or `ContextRelevanceGuardrail` rejects an
  answer to a turn that retrieved nothing. Return `None` for the turns that may answer without retrieving.
- **A block** ends the turn `AgentStatus.Blocked("GroundingGuardrail", reason)`, like any output guardrail's.
- **The judge's calls are counted** in the run's `usage`, and held to its token budget.

Nothing here depends on `llm4s-rag`: the function you pass is the only thing that knows where retrieval happens.

### Preset Configurations

A preset is a `RAGGuardrails.GuardrailConfig`: `inputGuardrails`, `outputGuardrails` and `ragGuardrails`.
`outputGuardrailsWithContext` gives the last two, in that order, as the output list of a `GuardrailMiddleware`, each
RAG guardrail wrapped with `RAGGuardrail.withTurnContext`.

```scala
val minimal    = RAGGuardrails.minimal           // no LLM calls
val standard   = RAGGuardrails.standard(judge)
val strict     = RAGGuardrails.strict(judge, allowedTopics = Seq("refunds", "shipping"))
val monitoring = RAGGuardrails.monitoring(judge) // warns, never blocks

val guardrails = new GuardrailMiddleware(
  input = standard.inputGuardrails,
  output = standard.outputGuardrailsWithContext(turn => Some(RAGContext.fromToolResults(turn, Set("search_docs"))))
)
```

| Preset | Input | Output | RAG |
|--------|-------|--------|-----|
| `minimal` | `PIIDetector`, `PromptInjectionDetector` | `PIIMasker` | none |
| `standard(judge)` | `PIIDetector`, `PromptInjectionDetector` | `PIIMasker` | grounding, context relevance |
| `strict(judge, allowedTopics)` | strict PII and injection checks, `TopicBoundaryGuardrail` | `PIIMasker` | strict grounding, context relevance, source attribution |
| `monitoring(judge)` | PII and injection checks that only warn | `PIIMasker` | all three, in warn mode |

`standardWithTopics`, `customerSupport`, `softwareDocumentation`, `research`, `financial` and `custom` are the others.
Each LLM-judged guardrail is one model call per turn.

### Individual RAG Guardrails

The constructor parameters, with their defaults:

```scala
// Is the answer supported by the retrieved chunks? strictMode fails on any unsupported claim, whatever the score
val grounding = new GroundingGuardrail(
  llmClient = judge,
  threshold = 0.7,
  onFail = GuardrailAction.Block,
  strictMode = false
)

// Are the retrieved chunks relevant to the query? A chunk scoring at least `threshold` is relevant
val relevance = new ContextRelevanceGuardrail(
  llmClient = judge,
  threshold = 0.5,
  minRelevantRatio = 0.5,
  onFail = GuardrailAction.Block
)

// Does the answer cite its sources?
val attribution = new SourceAttributionGuardrail(
  llmClient = judge,
  requireAttributions = true,
  minAttributionScore = 0.5,
  onFail = GuardrailAction.Block
)

// Is the query about an allowed topic? An input guardrail: it needs no RAGContext
val topicBoundary = new TopicBoundaryGuardrail(
  llmClient = judge,
  allowedTopics = Seq("programming", "software engineering"),
  threshold = 0.5,
  onFail = GuardrailAction.Block
)
```

Each has the factories `strict`, `balanced`, `lenient` and `monitoring` (`GroundingGuardrail.strict(judge)`,
`TopicBoundaryGuardrail.balanced(judge, allowedTopics)`).

- `ContextRelevanceGuardrail` judges the retrieval, not the answer: it fails when fewer than `minRelevantRatio` of the
  chunks are relevant to the query.
- `SourceAttributionGuardrail` passes without a judge call when the context has no chunks or `requireAttributions` is
  `false`. Give it a context with `sources`, or the judge has no source names to look for.
- `GuardrailAction.Warn` logs and passes. None of them can repair an answer, so `GuardrailAction.Fix` does not fix:
  `GroundingGuardrail` and `TopicBoundaryGuardrail` block as `Block` does, and the other two log and pass as `Warn`
  does.
- `TopicBoundaryGuardrail` is an ordinary `InputGuardrail` on the query: pass it in `input`, with no wrapping.

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
| Credit Card | `4111-1111-1111-1111`, `3782 822463 10005` (15-digit American Express) | `[REDACTED_CARD]` |
| Phone | `(555) 123-4567`, `+44 20 7946 0958` | `[REDACTED_PHONE]` |
| IP Address | `192.168.1.1` | `[REDACTED_IP]` |

A phone number is a US number (with an optional `+1` or `1` prefix) or an international number that starts with `+`
and has 8 to 15 digits, with spaces, dashes, dots or parentheses between them. Digits without a `+` are not treated
as an international number. A card number is recognised by its shape (a Visa, MasterCard, Amex or Discover prefix and
the right number of digits); the check digit is not validated. When two types match overlapping text (a plain card
number is also account-shaped, and a phone number can run into the email address after it), the overlapping text is
masked once, under the type whose match starts first. Detection does not merge them: `detect` returns one match per
type, so `summarize` counts that text once for each type (`123456789` gives `SSN -> 1, Passport -> 1` with
`PIIType.all`), and `PIIDetector`'s block message lists both types. A finding is one match of one type, so the sum of
the counts is how often the patterns fired, not how many stretches of PII the text holds.

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
