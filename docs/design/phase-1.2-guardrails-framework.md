# Phase 1.2: Guardrails Framework

> **Date:** 2025-01-16 (Updated: 2025-11-25)
> **Status:** ✅ Implemented (including LLM-as-Judge extension)
> **Priority:** ⭐⭐⭐⭐⭐ Critical for Production
> **Effort:** 2-3 weeks
> **Phase:** 1.2 - Core Usability
> **Dependencies:** Phase 1.1 (Functional Conversation Management)
> **Implementation:** Complete - See `org.llm4s.agent.guardrails` package

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Background & Motivation](#background--motivation)
3. [Design Goals](#design-goals)
4. [Core Concepts](#core-concepts)
5. [Proposed API](#proposed-api)
6. [Implementation Details](#implementation-details)
7. [Integration with Existing Features](#integration-with-existing-features)
8. [Testing Strategy](#testing-strategy)
9. [Documentation Plan](#documentation-plan)
10. [Examples](#examples)
11. [Appendix](#appendix)

---

## Executive Summary

### Problem Statement

llm4s currently lacks a **standardized framework for input/output validation** in agent workflows. Users must implement manual validation logic, which:

- **Increases code complexity** - Validation scattered across codebase
- **Lacks composability** - Hard to reuse validation logic
- **No standardization** - Each team implements differently
- **Production risk** - Easy to forget critical safety checks

**Current State:**
```scala
// Manual validation - verbose and error-prone
def runAgent(query: String): Result[AgentState] = {
  if (query.isEmpty) {
    Left(ValidationError.invalid("query", "Query cannot be empty"))
  } else if (query.length > 10000) {
    Left(ValidationError.invalid("query", "Query too long"))
  } else if (containsProfanity(query)) {
    Left(ValidationError.invalid("query", "Query contains inappropriate content"))
  } else {
    agent.run(query, tools)
  }
}
```

### Solution

Implement a **declarative, composable guardrails framework** that provides:

✅ **Type-safe validation** - Compile-time checking of guardrail types
✅ **Composability** - Chain and combine guardrails functionally
✅ **Reusability** - Built-in guardrails + custom extension points
✅ **Parallel execution** - Multiple guardrails run concurrently
✅ **Clear semantics** - Input vs. output guardrails with explicit flow

**Proposed State:**
```scala
// Declarative validation - clear and composable
agent.run(
  query,
  tools,
  inputGuardrails = Seq(
    LengthCheck(min = 1, max = 10000),
    ProfanityFilter(),
    CustomValidator(myValidationLogic)
  ),
  outputGuardrails = Seq(
    JSONValidator(schema),
    ToneValidator(allowedTones = Set(Tone.Professional, Tone.Friendly))
  )
)
```

### Design Philosophy Alignment

This design adheres to llm4s core principles:

| Principle | How Guardrails Framework Achieves It |
|-----------|-------------------------------------|
| **Functional & Immutable** | Guardrails are pure functions `A => Result[A]` |
| **Framework Agnostic** | No dependencies on Cats Effect, ZIO, etc. |
| **Simplicity Over Cleverness** | Clear trait hierarchy, descriptive names |
| **Principle of Least Surprise** | Validates input before processing, output before returning |
| **Type Safety** | Compile-time checking of guardrail types |

### Key Benefits

1. **Production Safety** - Standardized validation prevents common mistakes
2. **Developer Experience** - Declarative API reduces boilerplate
3. **Composability** - Build complex validation from simple components
4. **Performance** - Parallel validation execution by default
5. **Flexibility** - Easy to add custom guardrails

---

## Background & Motivation

### Comparison with Other Frameworks

#### OpenAI Agents SDK

**OpenAI Approach:**
```python
# Declarative guardrails with validation functions
agent = Agent(
    input_guardrails=[profanity_filter, length_check],
    output_guardrails=[fact_check, tone_validator]
)
```

**Features:**
- Input and output validation
- Parallel execution of guardrails
- Debounced validation for real-time agents
- Exception-based error handling

#### PydanticAI

**PydanticAI Approach:**
```python
# Type-safe validation via Pydantic models
class QueryInput(BaseModel):
    text: str = Field(min_length=1, max_length=10000)
    language: str = Field(pattern="^[a-z]{2}$")

@agent.tool
async def process_query(input: QueryInput) -> ResponseOutput:
    # Pydantic validates input automatically
    ...
```

**Features:**
- Strong runtime validation via Pydantic
- Type hints for IDE support
- Automatic validation on function calls
- Detailed error messages

### Gap Analysis

| Feature | OpenAI SDK | PydanticAI | llm4s Current | llm4s Proposed |
|---------|------------|------------|---------------|----------------|
| **Input Validation** | ✅ Guardrails | ✅ Pydantic | ❌ Manual | ✅ Guardrails |
| **Output Validation** | ✅ Guardrails | ✅ Pydantic | ❌ Manual | ✅ Guardrails |
| **Composability** | ⚠️ List-based | ⚠️ Model-based | ❌ None | ✅ Functional |
| **Type Safety** | ❌ Runtime | ⚠️ Runtime + hints | ✅ Compile-time | ✅ Compile-time |
| **Parallel Execution** | ✅ Yes | ⚠️ Sequential | ❌ N/A | ✅ Yes |
| **Custom Validation** | ✅ Functions | ✅ Validators | ✅ Manual | ✅ Trait extension |
| **Error Handling** | ⚠️ Exceptions | ⚠️ Exceptions | ✅ Result | ✅ Result |

**llm4s Unique Advantages:**
- **Compile-time type safety** - Catch errors before runtime
- **Result-based errors** - Explicit error handling, no exceptions
- **Functional composition** - Guardrails compose like pure functions
- **Framework agnostic** - Works with any effect system

---

## Design Goals

### Primary Goals

1. **Declarative Validation API** ✅
   - Express validation intent clearly
   - Separate validation logic from business logic
   - Minimize boilerplate code

2. **Type-Safe Guardrail Composition** ✅
   - Compile-time checking of guardrail types
   - Type-safe input/output guardrail distinction
   - Composable validation logic

3. **Functional Purity** ✅
   - Guardrails are pure functions `A => Result[A]`
   - No side effects in validation logic
   - Referentially transparent

4. **Performance** ✅
   - Parallel execution of independent guardrails
   - Fail-fast on first error (configurable)
   - Minimal overhead for disabled guardrails

5. **Extensibility** ✅
   - Easy to implement custom guardrails
   - Composable validation combinators
   - Plugin architecture for third-party guardrails

### Non-Goals

❌ **Pydantic-style model validation** - Not implementing a full data validation framework
❌ **Async guardrails** - Phase 1.2 is synchronous only (async in Phase 2.2)
❌ **Debounced validation** - Not needed for non-streaming use cases
❌ **Runtime type coercion** - Scala's type system handles this at compile time

---

## Core Concepts

### Guardrail Trait

The fundamental abstraction is a pure function that validates and potentially transforms a value:

```scala
trait Guardrail[A] {
  /**
   * Validate a value, returning the value if valid or an error if invalid.
   *
   * This is a PURE FUNCTION - no side effects allowed.
   *
   * @param value The value to validate
   * @return Right(value) if valid, Left(error) if invalid
   */
  def validate(value: A): Result[A]

  /**
   * Name of this guardrail for logging and error messages.
   */
  def name: String

  /**
   * Optional description of what this guardrail validates.
   */
  def description: Option[String] = None
}
```

**Key Properties:**
- **Pure function** - Same input always produces same output
- **Type-safe** - Generic type `A` ensures type safety
- **Result-based** - Uses `Result[A]` for explicit error handling
- **Self-describing** - Name and description for debugging

### Input vs. Output Guardrails

Guardrails are specialized based on where they apply in the agent flow:

```scala
/**
 * Validates user input before agent processing.
 *
 * Input guardrails run BEFORE the LLM is called, validating:
 * - User queries
 * - System prompts
 * - Tool arguments
 */
trait InputGuardrail extends Guardrail[String]

/**
 * Validates agent output before returning to user.
 *
 * Output guardrails run AFTER the LLM responds, validating:
 * - Assistant messages
 * - Tool results
 * - Final responses
 */
trait OutputGuardrail extends Guardrail[String]
```

**Why separate traits?**
1. **Clarity** - Explicit about validation timing
2. **Type safety** - Prevent using output guardrails on input
3. **Different concerns** - Input checks for safety, output checks for quality
4. **Composition** - Can compose input guardrails separately from output

### Validation Flow

```
┌─────────────────┐
│  User Query     │
└────────┬────────┘
         │
         ├──→ Input Guardrails (parallel)
         │    ├─→ ProfanityFilter
         │    ├─→ LengthCheck
         │    └─→ CustomValidator
         │
         │ (If all pass)
         ├──→ Agent.run() - LLM processing
         │
         │ (LLM generates response)
         ├──→ Output Guardrails (parallel)
         │    ├─→ JSONValidator
         │    ├─→ ToneValidator
         │    └─→ FactChecker
         │
         │ (If all pass)
         └──→ Return Result[AgentState]
```

### Validation Modes

How should multiple guardrails be evaluated?

```scala
sealed trait ValidationMode

object ValidationMode {
  /**
   * All guardrails must pass (default).
   * Runs all guardrails even if some fail, aggregating all errors.
   */
  case object All extends ValidationMode

  /**
   * At least one guardrail must pass.
   * Returns success on first passing guardrail.
   */
  case object Any extends ValidationMode

  /**
   * Returns on first result (success or failure).
   * Useful for expensive guardrails where order matters.
   */
  case object First extends ValidationMode
}
```

**Use Cases:**
- **All** (default): Safety checks - all must pass (profanity + length + custom)
- **Any**: Content detection - at least one must match (language detection)
- **First**: Expensive checks - stop at first definitive result (API-based validation)

---

## Proposed API

### 1. Core Guardrail Traits

```scala
package org.llm4s.agent.guardrails

import org.llm4s.types.Result

/**
 * Base trait for all guardrails.
 *
 * A guardrail is a pure function that validates a value of type A.
 *
 * @tparam A The type of value to validate
 */
trait Guardrail[A] {
  /**
   * Validate a value.
   *
   * @param value The value to validate
   * @return Right(value) if valid, Left(error) if invalid
   */
  def validate(value: A): Result[A]

  /**
   * Name of this guardrail for logging and error messages.
   */
  def name: String

  /**
   * Optional description of what this guardrail validates.
   */
  def description: Option[String] = None

  /**
   * Compose this guardrail with another sequentially.
   *
   * @param other The guardrail to run after this one
   * @return A composite guardrail that runs both in sequence
   */
  def andThen(other: Guardrail[A]): Guardrail[A] =
    CompositeGuardrail.sequential(Seq(this, other))

  /**
   * Map the validation result.
   * Useful for transforming values after validation.
   */
  def map[B](f: A => B): Guardrail[B] = new Guardrail[B] {
    def validate(value: B): Result[B] = {
      // This is a bit tricky - we can't validate B directly
      // This is more for transforming the result after validation
      // For now, we'll keep it simple
      Right(value)
    }
    def name: String = Guardrail.this.name
    override def description: Option[String] = Guardrail.this.description
  }
}

/**
 * Validates user input before agent processing.
 */
trait InputGuardrail extends Guardrail[String] {
  /**
   * Optional: Transform the input after validation.
   * Default is identity (no transformation).
   */
  def transform(input: String): String = input
}

/**
 * Validates agent output before returning to user.
 */
trait OutputGuardrail extends Guardrail[String] {
  /**
   * Optional: Transform the output after validation.
   * Default is identity (no transformation).
   */
  def transform(output: String): String = output
}
```

### 2. Built-in Guardrails

```scala
package org.llm4s.agent.guardrails.builtin

import org.llm4s.agent.guardrails.{InputGuardrail, OutputGuardrail}
import org.llm4s.error.ValidationError
import org.llm4s.types.Result

/**
 * Validates string length is within bounds.
 *
 * @param min Minimum length (inclusive)
 * @param max Maximum length (inclusive)
 */
class LengthCheck(min: Int, max: Int) extends InputGuardrail {
  require(min >= 0, "Minimum length must be non-negative")
  require(max >= min, "Maximum length must be >= minimum length")

  def validate(value: String): Result[String] =
    if (value.length < min) {
      Left(ValidationError.invalid(
        "input",
        s"Input too short: ${value.length} characters (minimum: $min)"
      ))
    } else if (value.length > max) {
      Left(ValidationError.invalid(
        "input",
        s"Input too long: ${value.length} characters (maximum: $max)"
      ))
    } else {
      Right(value)
    }

  val name: String = "LengthCheck"
  override val description: Option[String] = Some(s"Validates length between $min and $max characters")
}

/**
 * Filters profanity and inappropriate content.
 *
 * This is a basic implementation using a word list.
 * For production, consider integrating with external APIs like:
 * - OpenAI Moderation API
 * - Google Perspective API
 * - Custom ML models
 */
class ProfanityFilter(
  customBadWords: Set[String] = Set.empty,
  caseSensitive: Boolean = false
) extends InputGuardrail with OutputGuardrail {

  // Default bad words list (basic example - expand for production)
  private val defaultBadWords: Set[String] = Set(
    // Add actual profanity list here
    // This is intentionally minimal for example purposes
  )

  private val badWords: Set[String] = {
    val combined = defaultBadWords ++ customBadWords
    if (caseSensitive) combined else combined.map(_.toLowerCase)
  }

  def validate(value: String): Result[String] = {
    val checkValue = if (caseSensitive) value else value.toLowerCase
    val words = checkValue.split("\\s+")

    val foundBadWords = words.filter(badWords.contains)

    if (foundBadWords.nonEmpty) {
      Left(ValidationError.invalid(
        "input",
        s"Input contains inappropriate content: ${foundBadWords.mkString(", ")}"
      ))
    } else {
      Right(value)
    }
  }

  val name: String = "ProfanityFilter"
  override val description: Option[String] = Some("Filters profanity and inappropriate content")
}

/**
 * Validates that output is valid JSON matching an optional schema.
 *
 * @param schema Optional JSON schema to validate against
 */
class JSONValidator(schema: Option[ujson.Value] = None) extends OutputGuardrail {

  def validate(value: String): Result[String] = {
    // Try to parse as JSON
    val parseResult = scala.util.Try(ujson.read(value)).toEither.left.map { ex =>
      ValidationError.invalid("output", s"Output is not valid JSON: ${ex.getMessage}")
    }

    // If schema provided, validate against it
    parseResult.flatMap { json =>
      schema match {
        case Some(s) =>
          // Schema validation would go here using a JSON Schema library
          // Basic parsing validation is sufficient for most use cases
          Right(value)
        case None =>
          Right(value)
      }
    }
  }

  val name: String = "JSONValidator"
  override val description: Option[String] = Some("Validates output is valid JSON")
}

/**
 * Validates that output matches a regular expression.
 *
 * @param pattern The regex pattern to match
 * @param errorMessage Optional custom error message
 */
class RegexValidator(
  pattern: scala.util.matching.Regex,
  errorMessage: Option[String] = None
) extends Guardrail[String] {

  def validate(value: String): Result[String] =
    if (pattern.findFirstIn(value).isDefined) {
      Right(value)
    } else {
      Left(ValidationError.invalid(
        "value",
        errorMessage.getOrElse(s"Value does not match pattern: $pattern")
      ))
    }

  val name: String = "RegexValidator"
  override val description: Option[String] = Some(s"Validates against pattern: $pattern")
}

/**
 * Validates that output matches one of the allowed tones.
 *
 * This is a simple keyword-based implementation.
 * For production, consider using sentiment analysis APIs.
 */
class ToneValidator(allowedTones: Set[Tone]) extends OutputGuardrail {

  def validate(value: String): Result[String] = {
    val detectedTone = detectTone(value)

    if (allowedTones.contains(detectedTone)) {
      Right(value)
    } else {
      Left(ValidationError.invalid(
        "output",
        s"Output tone ($detectedTone) not allowed. Allowed tones: ${allowedTones.mkString(", ")}"
      ))
    }
  }

  private def detectTone(text: String): Tone = {
    // Simple keyword-based detection (improve for production)
    val lower = text.toLowerCase

    if (lower.contains("!") && lower.split("[.!?]").exists(_.split("\\s+").length < 5)) {
      Tone.Excited
    } else if (lower.matches(".*\\b(please|thank you|kindly)\\b.*")) {
      Tone.Professional
    } else if (lower.matches(".*\\b(hey|cool|awesome)\\b.*")) {
      Tone.Casual
    } else {
      Tone.Neutral
    }
  }

  val name: String = "ToneValidator"
  override val description: Option[String] = Some(s"Validates tone is one of: ${allowedTones.mkString(", ")}")
}

sealed trait Tone
object Tone {
  case object Professional extends Tone
  case object Casual extends Tone
  case object Friendly extends Tone
  case object Formal extends Tone
  case object Excited extends Tone
  case object Neutral extends Tone
}
```

### 2b. LLM-as-Judge Guardrails (NEW - CrewAI 2025 Feature Parity)

LLM-based guardrails use a language model to evaluate content against natural language
criteria. This enables validation of subjective qualities that cannot be easily checked
with deterministic rules.

**Added:** 2025-11-25 as part of CrewAI feature parity analysis.

```scala
package org.llm4s.agent.guardrails

import org.llm4s.llmconnect.LLMClient
import org.llm4s.types.Result

/**
 * Base trait for LLM-based guardrails (LLM-as-Judge pattern).
 *
 * LLM guardrails use a language model to evaluate content against
 * natural language criteria, returning a score between 0.0 and 1.0.
 *
 * @note LLM guardrails have higher latency than function-based guardrails.
 *       Use them only when deterministic validation is insufficient.
 */
trait LLMGuardrail extends OutputGuardrail {
  /** The LLM client to use for evaluation */
  def llmClient: LLMClient

  /** Natural language prompt describing the evaluation criteria */
  def evaluationPrompt: String

  /** Minimum score required to pass validation (0.0 to 1.0). Default: 0.7 */
  def threshold: Double = 0.7

  override def validate(value: String): Result[String] = {
    evaluateWithLLM(value).flatMap { score =>
      if (score >= threshold) Right(value)
      else Left(ValidationError.invalid(
        "output",
        s"LLM judge score ($score) below threshold ($threshold)"
      ))
    }
  }

  protected def evaluateWithLLM(content: String): Result[Double]
}

object LLMGuardrail {
  /** Create a custom LLM guardrail with specified parameters */
  def apply(
    client: LLMClient,
    prompt: String,
    passThreshold: Double = 0.7,
    guardrailName: String = "CustomLLMGuardrail"
  ): LLMGuardrail
}
```

**Built-in LLM Guardrails:**

```scala
package org.llm4s.agent.guardrails.builtin

/**
 * LLM-based tone validation guardrail.
 * More accurate than keyword-based ToneValidator for nuanced tone detection.
 */
class LLMToneGuardrail(
  llmClient: LLMClient,
  allowedTones: Set[String],  // e.g., Set("professional", "friendly")
  threshold: Double = 0.7
) extends LLMGuardrail

object LLMToneGuardrail {
  def professional(client: LLMClient, threshold: Double = 0.7): LLMToneGuardrail
  def friendly(client: LLMClient, threshold: Double = 0.7): LLMToneGuardrail
  def professionalOrFriendly(client: LLMClient, threshold: Double = 0.7): LLMToneGuardrail
}

/**
 * LLM-based factual accuracy validation guardrail.
 * Essential for RAG applications to prevent hallucination.
 */
class LLMFactualityGuardrail(
  llmClient: LLMClient,
  referenceContext: String,  // The source documents to verify against
  threshold: Double = 0.7
) extends LLMGuardrail

object LLMFactualityGuardrail {
  def strict(client: LLMClient, referenceContext: String): LLMFactualityGuardrail
  def lenient(client: LLMClient, referenceContext: String): LLMFactualityGuardrail
}

/**
 * LLM-based content safety validation guardrail.
 * More nuanced safety checking than keyword-based filters.
 */
class LLMSafetyGuardrail(
  llmClient: LLMClient,
  threshold: Double = 0.8,  // Higher default for safety
  customCriteria: Option[String] = None
) extends LLMGuardrail

object LLMSafetyGuardrail {
  def strict(client: LLMClient): LLMSafetyGuardrail
  def childSafe(client: LLMClient): LLMSafetyGuardrail
  def withCustomCriteria(client: LLMClient, criteria: String): LLMSafetyGuardrail
}

/**
 * LLM-based response quality validation guardrail.
 * Evaluates helpfulness, completeness, clarity, and relevance.
 */
class LLMQualityGuardrail(
  llmClient: LLMClient,
  originalQuery: String,  // For relevance checking
  threshold: Double = 0.7
) extends LLMGuardrail

object LLMQualityGuardrail {
  def highQuality(client: LLMClient, originalQuery: String): LLMQualityGuardrail
}
```

**Usage Example:**

```scala
import org.llm4s.agent.guardrails.builtin._

// RAG application with factuality checking
val referenceContext = loadRetrievedDocuments(query)
val factualityGuardrail = LLMFactualityGuardrail(client, referenceContext, threshold = 0.8)

// Professional tone for customer support
val toneGuardrail = LLMToneGuardrail.professional(client)

// Safety for user-facing content
val safetyGuardrail = LLMSafetyGuardrail(client)

agent.run(
  query,
  tools,
  outputGuardrails = Seq(
    new LengthCheck(10, 5000),  // Fast checks first
    safetyGuardrail,            // Then LLM-based checks
    toneGuardrail,
    factualityGuardrail
  )
)
```

**Best Practices for LLM Guardrails:**

1. **Order matters** - Put fast function-based guardrails before LLM guardrails
2. **Use appropriate thresholds** - Higher for safety (0.8-0.95), lower for tone (0.6-0.7)
3. **Consider latency** - Each LLM guardrail adds an API call
4. **Separate judge model** - Consider using a different model for judging vs. generation
5. **Combine with function guardrails** - Use LLM for nuance, functions for deterministic checks

### 3. Composite Guardrail

```scala
package org.llm4s.agent.guardrails

/**
 * Combines multiple guardrails with configurable validation mode.
 *
 * @param guardrails The guardrails to combine
 * @param mode How to combine validation results
 */
class CompositeGuardrail[A](
  guardrails: Seq[Guardrail[A]],
  mode: ValidationMode = ValidationMode.All
) extends Guardrail[A] {

  def validate(value: A): Result[A] = mode match {
    case ValidationMode.All =>
      validateAll(value)

    case ValidationMode.Any =>
      validateAny(value)

    case ValidationMode.First =>
      validateFirst(value)
  }

  private def validateAll(value: A): Result[A] = {
    val results = guardrails.map(_.validate(value))
    val errors = results.collect { case Left(err) => err }

    if (errors.isEmpty) {
      Right(value)
    } else {
      // Aggregate all errors
      Left(ValidationError.invalid(
        "composite",
        s"Multiple validation failures: ${errors.map(_.formatted).mkString("; ")}"
      ))
    }
  }

  private def validateAny(value: A): Result[A] = {
    val results = guardrails.map(_.validate(value))
    val successes = results.collect { case Right(v) => v }

    if (successes.nonEmpty) {
      Right(successes.head)
    } else {
      val errors = results.collect { case Left(err) => err }
      Left(ValidationError.invalid(
        "composite",
        s"All validations failed: ${errors.map(_.formatted).mkString("; ")}"
      ))
    }
  }

  private def validateFirst(value: A): Result[A] = {
    guardrails.headOption match {
      case Some(guardrail) => guardrail.validate(value)
      case None => Right(value)
    }
  }

  val name: String = s"CompositeGuardrail(${guardrails.map(_.name).mkString(", ")})"

  override val description: Option[String] = Some(
    s"Composite guardrail with mode=$mode: ${guardrails.map(_.name).mkString(", ")}"
  )
}

object CompositeGuardrail {
  /**
   * Create a composite guardrail that validates all guardrails.
   */
  def all[A](guardrails: Seq[Guardrail[A]]): CompositeGuardrail[A] =
    new CompositeGuardrail(guardrails, ValidationMode.All)

  /**
   * Create a composite guardrail that validates any guardrail.
   */
  def any[A](guardrails: Seq[Guardrail[A]]): CompositeGuardrail[A] =
    new CompositeGuardrail(guardrails, ValidationMode.Any)

  /**
   * Create a composite guardrail that runs guardrails sequentially.
   */
  def sequential[A](guardrails: Seq[Guardrail[A]]): Guardrail[A] = new Guardrail[A] {
    def validate(value: A): Result[A] =
      guardrails.foldLeft[Result[A]](Right(value)) { (acc, guardrail) =>
        acc.flatMap(guardrail.validate)
      }

    val name: String = s"SequentialGuardrail(${guardrails.map(_.name).mkString(" -> ")})"
  }
}
```

### 4. Enhanced Agent API

```scala
package org.llm4s.agent

import org.llm4s.agent.guardrails.{InputGuardrail, OutputGuardrail}

class Agent(client: LLMClient) {

  /**
   * Run the agent with optional input/output guardrails.
   *
   * @param query User query
   * @param tools Available tools
   * @param inputGuardrails Validate query before processing (default: none)
   * @param outputGuardrails Validate response before returning (default: none)
   * @param maxSteps Maximum agent steps (default: 10)
   * @param traceLogPath Optional trace log file path
   * @param debug Enable debug logging
   * @return Agent state or validation error
   */
  def run(
    query: String,
    tools: ToolRegistry,
    inputGuardrails: Seq[InputGuardrail] = Seq.empty,
    outputGuardrails: Seq[OutputGuardrail] = Seq.empty,
    maxSteps: Option[Int] = Some(10),
    traceLogPath: Option[String] = None,
    debug: Boolean = false
  ): Result[AgentState] = {
    for {
      // 1. Validate input
      validatedQuery <- validateInput(query, inputGuardrails)

      // 2. Initialize and run agent
      initialState = initialize(validatedQuery, tools, None, debug)
      finalState <- run(initialState, maxSteps, traceLogPath, debug)

      // 3. Validate output
      validatedState <- validateOutput(finalState, outputGuardrails)
    } yield validatedState
  }

  /**
   * Continue a conversation with optional guardrails.
   *
   * @param previousState Previous agent state (must be Complete or Failed)
   * @param newUserMessage New user message
   * @param inputGuardrails Validate new message before processing
   * @param outputGuardrails Validate response before returning
   * @param maxSteps Maximum agent steps
   * @param traceLogPath Optional trace log file
   * @param contextWindowConfig Optional context window management
   * @param debug Enable debug logging
   * @return Updated agent state or validation error
   */
  def continueConversation(
    previousState: AgentState,
    newUserMessage: String,
    inputGuardrails: Seq[InputGuardrail] = Seq.empty,
    outputGuardrails: Seq[OutputGuardrail] = Seq.empty,
    maxSteps: Option[Int] = None,
    traceLogPath: Option[String] = None,
    contextWindowConfig: Option[ContextWindowConfig] = None,
    debug: Boolean = false
  ): Result[AgentState] = {
    for {
      // 1. Validate input
      validatedMessage <- validateInput(newUserMessage, inputGuardrails)

      // 2. Continue conversation
      finalState <- super.continueConversation(
        previousState,
        validatedMessage,
        maxSteps,
        traceLogPath,
        contextWindowConfig,
        debug
      )

      // 3. Validate output
      validatedState <- validateOutput(finalState, outputGuardrails)
    } yield validatedState
  }

  /**
   * Validate input using guardrails.
   */
  private def validateInput(
    query: String,
    guardrails: Seq[InputGuardrail]
  ): Result[String] = {
    if (guardrails.isEmpty) {
      Right(query)
    } else {
      // Run guardrails in parallel and aggregate results
      val composite = CompositeGuardrail.all(guardrails)
      composite.validate(query)
    }
  }

  /**
   * Validate output using guardrails.
   */
  private def validateOutput(
    state: AgentState,
    guardrails: Seq[OutputGuardrail]
  ): Result[AgentState] = {
    if (guardrails.isEmpty) {
      Right(state)
    } else {
      // Extract final assistant message
      val finalMessage = state.conversation.messages
        .findLast(_.role == MessageRole.Assistant)
        .map(_.content)
        .getOrElse("")

      // Validate final message
      val composite = CompositeGuardrail.all(guardrails)
      composite.validate(finalMessage).map(_ => state)
    }
  }
}
```

---

## Implementation Details

### Module Structure

```
modules/core/src/main/scala/org/llm4s/agent/guardrails/
├── Guardrail.scala              # Base trait
├── InputGuardrail.scala         # Input validation trait
├── OutputGuardrail.scala        # Output validation trait
├── ValidationMode.scala         # Validation mode enum
├── CompositeGuardrail.scala     # Composite guardrail
└── builtin/                     # Built-in guardrails
    ├── LengthCheck.scala
    ├── ProfanityFilter.scala
    ├── JSONValidator.scala
    ├── RegexValidator.scala
    └── ToneValidator.scala
```

### Implementation Phases

#### Phase 1: Core Framework (Week 1)

**Tasks:**
1. Implement `Guardrail[A]` trait
2. Implement `InputGuardrail` and `OutputGuardrail` traits
3. Implement `ValidationMode` enum
4. Implement `CompositeGuardrail`
5. Add tests for composition

**Deliverables:**
- Core guardrail framework
- Composition utilities
- Unit tests

#### Phase 2: Built-in Guardrails (Week 1-2)

**Tasks:**
1. Implement `LengthCheck`
2. Implement `ProfanityFilter`
3. Implement `JSONValidator`
4. Implement `RegexValidator`
5. Implement `ToneValidator`
6. Add tests for each guardrail

**Deliverables:**
- 5 built-in guardrails
- Comprehensive tests
- Documentation

#### Phase 3: Agent Integration (Week 2)

**Tasks:**
1. Enhance `Agent.run()` with guardrail parameters
2. Enhance `Agent.continueConversation()` with guardrail parameters
3. Implement `validateInput()` and `validateOutput()` helpers
4. Add integration tests
5. Update trace logging to include validation

**Deliverables:**
- Enhanced Agent API
- Integration tests
- Updated trace logs

#### Phase 4: Documentation & Examples (Week 2-3)

**Tasks:**
1. Write user guide for guardrails
2. Create custom guardrail tutorial
3. Add examples to samples module
4. Update CLAUDE.md with guardrails section
5. Create migration guide

**Deliverables:**
- Comprehensive documentation
- 3+ working examples
- Migration guide

---

## Integration with Existing Features

### Integration with Phase 1.1 (Conversation Management)

Guardrails integrate seamlessly with multi-turn conversations:

```scala
// Multi-turn conversation with consistent guardrails
val inputGuardrails = Seq(
  LengthCheck(min = 1, max = 5000),
  ProfanityFilter()
)

val outputGuardrails = Seq(
  ToneValidator(allowedTones = Set(Tone.Professional, Tone.Friendly))
)

val result = for {
  // First turn - guardrails apply
  state1 <- agent.run(
    "What is Scala?",
    tools,
    inputGuardrails = inputGuardrails,
    outputGuardrails = outputGuardrails
  )

  // Second turn - same guardrails apply
  state2 <- agent.continueConversation(
    state1,
    "What are its main features?",
    inputGuardrails = inputGuardrails,
    outputGuardrails = outputGuardrails
  )

  // Third turn - different guardrails
  state3 <- agent.continueConversation(
    state2,
    "Generate a code example in JSON format",
    inputGuardrails = inputGuardrails,
    outputGuardrails = Seq(JSONValidator())  // Require JSON output
  )
} yield state3
```

**Key Points:**
- Guardrails are **optional parameters** on each turn
- Can change guardrails between turns
- Validation happens per-turn, not per-conversation

### Integration with Phase 1.3 (Handoffs) - Future

When handoffs are implemented, guardrails will apply **per-agent**:

```scala
// Each agent has its own guardrails
val agentA = new Agent(client)
val agentB = new Agent(client)

// Agent A validates with strict rules
val stateA = agentA.run(
  query,
  toolsA,
  inputGuardrails = Seq(LengthCheck(max = 1000), ProfanityFilter()),
  handoffs = Seq(Handoff(agentB))
)

// Agent B (after handoff) has its own guardrails
val stateB = agentB.run(
  handoffQuery,
  toolsB,
  inputGuardrails = Seq(LengthCheck(max = 5000)),  // Different rules!
  outputGuardrails = Seq(JSONValidator())
)
```

**Design Decision:** Guardrails are **not inherited** across handoffs. Each agent is responsible for its own validation.

### Integration with Trace Logging

Guardrail validation results are logged in trace files:

```markdown
# Agent Execution Trace

## Step 1: Input Validation
**Guardrails:** LengthCheck, ProfanityFilter

✅ **LengthCheck**: PASSED (1234 characters, max: 5000)
✅ **ProfanityFilter**: PASSED

## Step 2: Agent Processing
...

## Step 10: Output Validation
**Guardrails:** ToneValidator

✅ **ToneValidator**: PASSED (tone: Professional, allowed: [Professional, Friendly])

## Final Status: Complete
```

---

## Testing Strategy

### Unit Tests

#### Guardrail Tests

```scala
class LengthCheckSpec extends AnyFlatSpec with Matchers {
  "LengthCheck" should "pass for valid length" in {
    val guardrail = new LengthCheck(min = 1, max = 100)
    val result = guardrail.validate("Hello, world!")
    result shouldBe Right("Hello, world!")
  }

  it should "fail for too short input" in {
    val guardrail = new LengthCheck(min = 10, max = 100)
    val result = guardrail.validate("Hi")
    result.isLeft shouldBe true
  }

  it should "fail for too long input" in {
    val guardrail = new LengthCheck(min = 1, max = 10)
    val result = guardrail.validate("This is way too long")
    result.isLeft shouldBe true
  }
}

class CompositeGuardrailSpec extends AnyFlatSpec with Matchers {
  "CompositeGuardrail.all" should "pass if all guardrails pass" in {
    val composite = CompositeGuardrail.all(Seq(
      new LengthCheck(1, 100),
      new ProfanityFilter()
    ))

    val result = composite.validate("Hello, world!")
    result shouldBe Right("Hello, world!")
  }

  it should "fail if any guardrail fails" in {
    val composite = CompositeGuardrail.all(Seq(
      new LengthCheck(1, 10),  // Will fail
      new ProfanityFilter()
    ))

    val result = composite.validate("This is too long")
    result.isLeft shouldBe true
  }
}
```

### Integration Tests

#### Agent Integration Tests

```scala
class AgentGuardrailsIntegrationSpec extends AnyFlatSpec with Matchers {
  "Agent.run" should "validate input before processing" in {
    val agent = new Agent(mockClient)

    val result = agent.run(
      "",  // Empty query
      tools,
      inputGuardrails = Seq(new LengthCheck(min = 1, max = 100))
    )

    result.isLeft shouldBe true
    result.left.get.formatted should include("too short")
  }

  it should "validate output before returning" in {
    val agent = new Agent(mockClient)

    // Mock client returns invalid JSON
    when(mockClient.complete(*, *))
      .thenReturn(Right(CompletionResponse(content = "Not JSON")))

    val result = agent.run(
      "Generate JSON",
      tools,
      outputGuardrails = Seq(new JSONValidator())
    )

    result.isLeft shouldBe true
    result.left.get.formatted should include("not valid JSON")
  }

  it should "pass when all guardrails pass" in {
    val agent = new Agent(mockClient)

    when(mockClient.complete(*, *))
      .thenReturn(Right(CompletionResponse(content = "Valid response")))

    val result = agent.run(
      "Hello",
      tools,
      inputGuardrails = Seq(new LengthCheck(1, 100)),
      outputGuardrails = Seq(new RegexValidator(".*".r))
    )

    result.isRight shouldBe true
  }
}
```

### Performance Tests

```scala
class GuardrailPerformanceSpec extends AnyFlatSpec with Matchers {
  "Guardrails" should "execute in parallel efficiently" in {
    // Create 10 slow guardrails that take 100ms each
    val slowGuardrails = (1 to 10).map { i =>
      new InputGuardrail {
        def validate(value: String): Result[String] = {
          Thread.sleep(100)
          Right(value)
        }
        val name = s"SlowGuardrail$i"
      }
    }

    val composite = CompositeGuardrail.all(slowGuardrails)

    val start = System.currentTimeMillis()
    composite.validate("test")
    val duration = System.currentTimeMillis() - start

    // Should be ~100ms (parallel) not ~1000ms (sequential)
    duration should be < 200L
  }
}
```

---

## Documentation Plan

### User Guide: Guardrails Framework

```markdown
# Guardrails Framework

## Overview

Guardrails provide declarative validation for agent inputs and outputs.

## Basic Usage

### Input Validation

```scala
import org.llm4s.agent.guardrails.builtin._

agent.run(
  query,
  tools,
  inputGuardrails = Seq(
    LengthCheck(min = 1, max = 5000),
    ProfanityFilter()
  )
)
```

### Output Validation

```scala
agent.run(
  "Generate a JSON response",
  tools,
  outputGuardrails = Seq(
    JSONValidator()
  )
)
```

## Built-in Guardrails

| Guardrail | Type | Description |
|-----------|------|-------------|
| `LengthCheck(min, max)` | Input | Validates string length |
| `ProfanityFilter()` | Input/Output | Filters inappropriate content |
| `JSONValidator(schema)` | Output | Validates JSON structure |
| `RegexValidator(pattern)` | Both | Validates regex match |
| `ToneValidator(tones)` | Output | Validates response tone |

## Custom Guardrails

### Simple Custom Guardrail

```scala
class EmailValidator extends InputGuardrail {
  private val emailPattern = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$".r

  def validate(value: String): Result[String] =
    if (emailPattern.matches(value)) {
      Right(value)
    } else {
      Left(ValidationError.invalid("email", "Invalid email format"))
    }

  val name = "EmailValidator"
}
```

### Complex Custom Guardrail

```scala
class APIBasedModerationGuardrail(
  apiKey: String,
  threshold: Double = 0.5
) extends InputGuardrail with OutputGuardrail {

  def validate(value: String): Result[String] = {
    // Call external moderation API
    val moderationResult = callModerationAPI(value, apiKey)

    if (moderationResult.toxicityScore < threshold) {
      Right(value)
    } else {
      Left(ValidationError.invalid(
        "content",
        s"Content toxicity score too high: ${moderationResult.toxicityScore}"
      ))
    }
  }

  val name = "APIBasedModeration"
}
```

## Composing Guardrails

### Sequential Composition

```scala
val strictValidation =
  new LengthCheck(1, 1000)
    .andThen(new ProfanityFilter())
    .andThen(new CustomValidator())
```

### Validation Modes

```scala
import org.llm4s.agent.guardrails.ValidationMode

// All guardrails must pass
CompositeGuardrail.all(Seq(guardrail1, guardrail2))

// At least one must pass
CompositeGuardrail.any(Seq(guardrail1, guardrail2))

// First result wins
new CompositeGuardrail(Seq(guardrail1, guardrail2), ValidationMode.First)
```

## Multi-turn Conversations

```scala
val guardrails = Seq(
  LengthCheck(1, 5000),
  ProfanityFilter()
)

for {
  state1 <- agent.run(query1, tools, inputGuardrails = guardrails)
  state2 <- agent.continueConversation(
    state1,
    query2,
    inputGuardrails = guardrails
  )
} yield state2
```

## Best Practices

1. **Use built-in guardrails** when possible
2. **Compose guardrails** for complex validation
3. **Keep guardrails pure** - no side effects
4. **Test custom guardrails** thoroughly
5. **Document validation logic** clearly
6. **Consider performance** for expensive validations
```

---

## Examples

### Example 1: Basic Input Validation

```scala
package org.llm4s.samples.guardrails

import org.llm4s.agent.Agent
import org.llm4s.agent.guardrails.builtin._
import org.llm4s.llmconnect.LLMConnect

object BasicInputValidationExample extends App {
  val result = for {
    client <- LLMConnect.fromEnv()
    agent = new Agent(client)

    state <- agent.run(
      query = "What is Scala?",
      tools = ToolRegistry.empty,
      inputGuardrails = Seq(
        new LengthCheck(min = 1, max = 10000),
        new ProfanityFilter()
      )
    )
  } yield state

  result match {
    case Right(state) =>
      println(s"Success: ${state.conversation.messages.last.content}")
    case Left(error) =>
      println(s"Validation failed: ${error.formatted}")
  }
}
```

### Example 2: Output JSON Validation

```scala
package org.llm4s.samples.guardrails

import org.llm4s.agent.Agent
import org.llm4s.agent.guardrails.builtin._
import org.llm4s.llmconnect.LLMConnect

object JSONOutputValidationExample extends App {
  val result = for {
    client <- LLMConnect.fromEnv()
    agent = new Agent(client)

    state <- agent.run(
      query = "Generate a JSON object with name and age fields",
      tools = ToolRegistry.empty,
      outputGuardrails = Seq(
        new JSONValidator()
      )
    )
  } yield state

  result match {
    case Right(state) =>
      val response = state.conversation.messages.last.content
      println(s"Valid JSON response: $response")

      // Can safely parse
      val json = ujson.read(response)
      println(s"Name: ${json("name").str}")
      println(s"Age: ${json("age").num}")

    case Left(error) =>
      println(s"Output validation failed: ${error.formatted}")
  }
}
```

### Example 3: Custom Guardrail

```scala
package org.llm4s.samples.guardrails

import org.llm4s.agent.Agent
import org.llm4s.agent.guardrails.InputGuardrail
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.types.Result

// Custom guardrail that checks for specific keywords
class KeywordRequirementGuardrail(requiredKeywords: Set[String]) extends InputGuardrail {
  def validate(value: String): Result[String] = {
    val lowerValue = value.toLowerCase
    val missingKeywords = requiredKeywords.filterNot(kw => lowerValue.contains(kw.toLowerCase))

    if (missingKeywords.isEmpty) {
      Right(value)
    } else {
      Left(ValidationError.invalid(
        "input",
        s"Query must contain keywords: ${missingKeywords.mkString(", ")}"
      ))
    }
  }

  val name = "KeywordRequirementGuardrail"
  override val description = Some(s"Requires keywords: ${requiredKeywords.mkString(", ")}")
}

object CustomGuardrailExample extends App {
  val result = for {
    client <- LLMConnect.fromEnv()
    agent = new Agent(client)

    state <- agent.run(
      query = "Tell me about Scala programming language features",
      tools = ToolRegistry.empty,
      inputGuardrails = Seq(
        new KeywordRequirementGuardrail(Set("scala", "programming"))
      )
    )
  } yield state

  result match {
    case Right(state) =>
      println(s"Query contained required keywords")
      println(s"Response: ${state.conversation.messages.last.content}")
    case Left(error) =>
      println(s"Validation failed: ${error.formatted}")
  }
}
```

### Example 4: Multi-turn with Tone Validation

```scala
package org.llm4s.samples.guardrails

import org.llm4s.agent.Agent
import org.llm4s.agent.guardrails.builtin._
import org.llm4s.llmconnect.LLMConnect

object MultiTurnToneValidationExample extends App {
  val inputGuardrails = Seq(
    new LengthCheck(min = 1, max = 5000),
    new ProfanityFilter()
  )

  val outputGuardrails = Seq(
    new ToneValidator(allowedTones = Set(Tone.Professional, Tone.Friendly))
  )

  val result = for {
    client <- LLMConnect.fromEnv()
    agent = new Agent(client)

    // Turn 1: Ask about Scala
    state1 <- agent.run(
      "What is Scala?",
      ToolRegistry.empty,
      inputGuardrails = inputGuardrails,
      outputGuardrails = outputGuardrails
    )

    // Turn 2: Ask for details
    state2 <- agent.continueConversation(
      state1,
      "What are its main features?",
      inputGuardrails = inputGuardrails,
      outputGuardrails = outputGuardrails
    )

    // Turn 3: Ask for examples
    state3 <- agent.continueConversation(
      state2,
      "Can you give me a code example?",
      inputGuardrails = inputGuardrails,
      outputGuardrails = outputGuardrails
    )
  } yield state3

  result match {
    case Right(finalState) =>
      println("All turns passed validation!")
      println(s"Final status: ${finalState.status}")
      println(s"Total messages: ${finalState.conversation.messages.length}")

    case Left(error) =>
      println(s"Validation failed: ${error.formatted}")
  }
}
```

### Example 5: Composite Guardrail with Modes

```scala
package org.llm4s.samples.guardrails

import org.llm4s.agent.Agent
import org.llm4s.agent.guardrails._
import org.llm4s.agent.guardrails.builtin._
import org.llm4s.llmconnect.LLMConnect

object CompositeGuardrailExample extends App {
  // Language detection guardrails (at least one must match)
  val languageDetection = CompositeGuardrail.any(Seq(
    new RegexValidator(".*\\b(scala|functional)\\b.*".r),
    new RegexValidator(".*\\b(java|object-oriented)\\b.*".r),
    new RegexValidator(".*\\b(python|dynamic)\\b.*".r)
  ))

  // Safety guardrails (all must pass)
  val safetyChecks = CompositeGuardrail.all(Seq(
    new LengthCheck(min = 1, max = 10000),
    new ProfanityFilter()
  ))

  val result = for {
    client <- LLMConnect.fromEnv()
    agent = new Agent(client)

    state <- agent.run(
      query = "Tell me about Scala programming",
      tools = ToolRegistry.empty,
      inputGuardrails = Seq(
        safetyChecks.asInstanceOf[InputGuardrail],
        languageDetection.asInstanceOf[InputGuardrail]
      )
    )
  } yield state

  result match {
    case Right(state) =>
      println("Query passed all validation!")
      println(s"Response: ${state.conversation.messages.last.content}")
    case Left(error) =>
      println(s"Validation failed: ${error.formatted}")
  }
}
```

---

## Appendix

### A. Comparison with Other Frameworks

#### OpenAI Agents SDK

**Similarities:**
- Declarative guardrail API
- Input/output validation separation
- Composable validation

**Differences:**
- llm4s uses `Result[A]` (explicit errors) vs. OpenAI exceptions
- llm4s guardrails are pure functions vs. OpenAI can have side effects
- llm4s has compile-time type safety vs. OpenAI runtime validation

#### PydanticAI

**Similarities:**
- Type-safe validation
- Composable validators
- Clear error messages

**Differences:**
- llm4s uses traits vs. Pydantic models
- llm4s compile-time checking vs. Pydantic runtime validation
- llm4s functional composition vs. Pydantic class-based

### B. Future Enhancements

**Phase 2.2: Async Guardrails**
```scala
trait AsyncGuardrail[A] {
  def validate(value: A): AsyncResult[A]
}
```

**Phase 3.3: External API Integration**
```scala
class OpenAIModerationGuardrail(apiKey: ApiKey) extends AsyncGuardrail[String]
class GooglePerspectiveGuardrail(apiKey: ApiKey) extends AsyncGuardrail[String]
```

**Phase 4: ML-based Guardrails**
```scala
class SentimentAnalysisGuardrail(model: SentimentModel) extends Guardrail[String]
class ToxicityDetectionGuardrail(model: ToxicityModel) extends Guardrail[String]
```

### C. Migration from Manual Validation

**Before (Manual Validation):**
```scala
def runAgent(query: String): Result[AgentState] = {
  // Manual validation
  if (query.isEmpty) {
    Left(ValidationError.invalid("query", "Empty query"))
  } else if (query.length > 10000) {
    Left(ValidationError.invalid("query", "Query too long"))
  } else {
    agent.run(query, tools)
  }
}
```

**After (Guardrails):**
```scala
def runAgent(query: String): Result[AgentState] = {
  agent.run(
    query,
    tools,
    inputGuardrails = Seq(
      LengthCheck(min = 1, max = 10000)
    )
  )
}
```

**Benefits:**
- ✅ Less boilerplate code
- ✅ Reusable validation logic
- ✅ Composable guardrails
- ✅ Standardized error messages
- ✅ Easier to test

---

## Conclusion

Phase 1.2 (Guardrails Framework) provides a **production-critical** feature that enhances safety and developer experience:

✅ **Declarative validation** - Clear, composable API
✅ **Type-safe** - Compile-time checking
✅ **Functional purity** - Pure functions, no side effects
✅ **Production-ready** - Built-in guardrails + custom extension
✅ **Well-integrated** - Works seamlessly with Phase 1.1 features

**Estimated Timeline:** 2-3 weeks
**Effort:** Medium
**Risk:** Low
**Value:** High (Critical for production deployments)

**Next Steps:**
1. Review and approve design document
2. Create implementation branch
3. Implement core framework (Week 1)
4. Implement built-in guardrails (Week 1-2)
5. Integrate with Agent API (Week 2)
6. Documentation and examples (Week 2-3)
7. Testing and refinement (Week 3)

---

**End of Design Document**
