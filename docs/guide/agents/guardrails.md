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

Guardrails belong to the agent, not to a run: give them to the builder in a `GuardrailMiddleware`. The examples on
this page assume an `LLMClient` named `client`, a `ToolRegistry` named `tools`, and the user's text as `userInput` or
`query`; the shorter ones also assume the imports of the examples before them.

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

| Guardrail | Purpose | Use as | Example |
|-----------|---------|--------|---------|
| `LengthCheck` | Enforce min/max length | input or output | `new LengthCheck(1, 10000)` |
| `ProfanityFilter` | Block profane content | input | `new ProfanityFilter()` |
| `JSONValidator` | Ensure valid JSON output | output | `new JSONValidator()` |
| `RegexValidator` | Pattern matching | input or output | `RegexValidator("\\d{3}-\\d{4}")` |
| `ToneValidator` | Simple tone detection | output | `new ToneValidator(Set(Tone.Professional))` |
| `PIIDetector` | Detect PII (email, SSN, etc.) | input | `new PIIDetector()` |
| `PIIMasker` | Mask detected PII | input or output | `new PIIMasker()` |
| `PromptInjectionDetector` | Detect injection attempts | input | `new PromptInjectionDetector()` |

`GuardrailMiddleware` takes `Seq[InputGuardrail]` for `input` and `Seq[OutputGuardrail]` for `output`, so a guardrail
in the wrong list does not compile. `RegexValidator` takes its pattern as a `String` through its companion
(`RegexValidator("...")`), or as a `Regex` through its constructor (`new RegexValidator("...".r)`).

### LLM-as-Judge Guardrails

These use an LLM to evaluate subjective qualities:

| Guardrail | Purpose | Example |
|-----------|---------|---------|
| `LLMSafetyGuardrail` | Content safety check | `new LLMSafetyGuardrail(client)` |
| `LLMFactualityGuardrail` | Verify factual accuracy against a reference text | `new LLMFactualityGuardrail(client, referenceContext)` |
| `LLMQualityGuardrail` | Assess how well a response answers a query | `new LLMQualityGuardrail(client, originalQuery)` |
| `LLMToneGuardrail` | Validate tone compliance | `new LLMToneGuardrail(client, Set("professional"))` |

Each judge scores the content and passes it when the score reaches its `threshold`. The threshold must be between 0.0 and 1.0: for any other value, or NaN, `validate` returns a `ValidationError` on the field `threshold` without calling the judge.

### RAG-Specific Guardrails

For retrieval-augmented generation:

| Guardrail | Purpose | Checks |
|-----------|---------|--------|
| `GroundingGuardrail` | Verify answers are grounded in retrieved context | an answer, with `validateWithContext` |
| `ContextRelevanceGuardrail` | Check context relevance to query | an answer, with `validateWithContext` |
| `SourceAttributionGuardrail` | Ensure sources are cited | an answer, with `validateWithContext` |
| `TopicBoundaryGuardrail` | Prevent off-topic queries | a query: an `InputGuardrail` |

The first three need the retrieved chunks, which an agent's guardrail middleware does not have: see
[RAG Guardrails](#rag-guardrails) before you add one to an agent.

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
  RegexValidator("^[A-Z].*"),  // Starts with capital
  RegexValidator("^\\d.*")     // Starts with digit
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

### What an agent runs, and what it does not

`GroundingGuardrail`, `ContextRelevanceGuardrail` and `SourceAttributionGuardrail` are `RAGGuardrail`s: they judge an
answer against the chunks a retrieval returned, and they check only through
`validateWithContext(answer, RAGContext(query, chunks, sources))`. `GuardrailMiddleware` has no retrieval to give them:
it calls each output guardrail's `validate`, with the answer and the model context but no chunks, and for these three
`validate` passes every answer unchanged. **A
`RAGGuardrail` in an agent's `output` list checks nothing.** Call `validateWithContext` yourself, where your code has
the chunks the answer was written from:

```scala
import org.llm4s.agent.guardrails.rag.{ GroundingGuardrail, RAGContext }
import org.llm4s.types.Result

val grounding = GroundingGuardrail.balanced(client)

def checkedAnswer(question: String, chunks: Seq[String], answer: String): Result[String] =
  grounding.validateWithContext(answer, RAGContext(question, chunks))
```

`TopicBoundaryGuardrail` is different: it is an `InputGuardrail` that judges the query on its own, so it works in an
agent's `input` list like any other.

### Preset Configurations

`RAGGuardrails` presets return a `GuardrailConfig` of three lists: `inputGuardrails` and `outputGuardrails`, which go to
`GuardrailMiddleware`, and `ragGuardrails`, which you run with `validateWithContext` as above.

```scala
import org.llm4s.agent.Agent
import org.llm4s.agent.graph.middleware.GuardrailMiddleware
import org.llm4s.agent.guardrails.rag.{ RAGContext, RAGGuardrail, RAGGuardrails }
import org.llm4s.types.Result

val config = RAGGuardrails.standard(client)

val agent = Agent
  .builder("rag", client)
  .withTools(tools)
  .withMiddleware(GuardrailMiddleware(config.inputGuardrails, config.outputGuardrails))
  .build()

// the retrieval-aware checks, on an answer and the chunks it was given
def grounded(question: String, chunks: Seq[String], answer: String): Result[String] =
  RAGGuardrail.all(config.ragGuardrails).validateWithContext(answer, RAGContext(question, chunks))
```

| Preset | Input | Output | RAG |
|--------|-------|--------|-----|
| `RAGGuardrails.minimal` | PII detection, prompt injection | PII masking | none |
| `RAGGuardrails.standard(client)` | PII detection, prompt injection | PII masking | grounding, context relevance |
| `RAGGuardrails.standardWithTopics(client, topics)` | as `standard`, plus a topic boundary | PII masking | grounding, context relevance |
| `RAGGuardrails.strict(client, topics)` | strict PII, high-sensitivity injection, strict topic boundary | PII masking | strict grounding, context relevance and source attribution |
| `RAGGuardrails.monitoring(client)` | every check with `GuardrailAction.Warn`: logs, never blocks | | |

`customerSupport`, `softwareDocumentation`, `research` and `financial` are domain presets, and `custom(...)` takes the
three lists yourself.

### Individual RAG Guardrails

Each takes the judge's `llmClient`, a threshold and an `onFail` action, and has `strict`, `balanced`, `lenient` and
`monitoring` factories:

```scala
import org.llm4s.agent.guardrails.GuardrailAction
import org.llm4s.agent.guardrails.rag._

// Is every claim of the answer supported by the retrieved chunks?
val grounding = new GroundingGuardrail(llmClient = client, threshold = 0.8)

// Are the retrieved chunks relevant to the query?
val relevance = ContextRelevanceGuardrail.balanced(client)

// Does the answer cite its sources? `Warn` logs an unattributed answer and passes it
val attribution = new SourceAttributionGuardrail(llmClient = client, onFail = GuardrailAction.Warn)

// Is the query about one of these topics? (an InputGuardrail: give it to GuardrailMiddleware's `input`)
val topicBoundary = TopicBoundaryGuardrail(client, Seq("programming", "software engineering"))
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

### What a Failing Check Does: `GuardrailAction`

A guardrail that takes an `onFail` parameter - `PIIDetector`, `PromptInjectionDetector`, `SecretLeakGuardrail` and the
RAG guardrails - decides with it what a failed check does:

| `GuardrailAction` | On a failed check |
|-------------------|-------------------|
| `Block` (the default) | `validate` returns a `Left`: in an agent, the turn ends `AgentStatus.Blocked` |
| `Fix` | the guardrail repairs the value where it can (`PIIDetector` masks the PII) and passes it on; where it cannot, it falls back to blocking (`PromptInjectionDetector`) or to warning (the RAG guardrails) |
| `Warn` | the guardrail logs a warning and passes the value unchanged |

```scala
import org.llm4s.agent.guardrails.GuardrailAction
import org.llm4s.agent.guardrails.builtin.{ PIIDetector, PromptInjectionDetector }

val maskInput       = new PIIDetector(onFail = GuardrailAction.Fix)            // mask PII in the query, never block
val watchInjections = new PromptInjectionDetector(onFail = GuardrailAction.Warn) // log suspected injections only
```

The other built-in guardrails always block on a failed check; wrap one in your own guardrail to change that.

`ValidationMode` is something else: the mode of a `CompositeGuardrail` - `All` (every guardrail must pass) and `Any`
(one must), which `CompositeGuardrail.all` and `.any` choose for you, or `First` (only the first guardrail runs). See
[Composite Guardrails](#composite-guardrails).

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
| RAG application | `TopicBoundaryGuardrail` on input; `GroundingGuardrail`, `SourceAttributionGuardrail` through `validateWithContext` ([RAG Guardrails](#rag-guardrails)) |
| Content moderation | `PIIDetector`, `ProfanityFilter`, `LLMSafetyGuardrail` |
| Form processing | `RegexValidator`, `LengthCheck` |

### 3. Test Your Guardrails

```scala
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class GuardrailSpec extends AnyFlatSpec with Matchers {
  "LengthCheck" should "reject empty input" in {
    val guardrail = new LengthCheck(min = 1, max = 100)
    guardrail.validate("") shouldBe a[Left[?, ?]]
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
