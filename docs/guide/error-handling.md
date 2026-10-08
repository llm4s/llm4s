---
layout: page
title: Error Handling
parent: User Guide
nav_order: 5
---

# Error Handling with Result
{: .no_toc }

How to work with `Result[A]` and `LLMError` in practice.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

LLM4S does not throw exceptions for failures it expects: a missing API key, a rate limit, a timeout
and a rejected request all come back as values. If you know Java or Python, this replaces
`try`/`catch` with something the compiler checks. This page shows how to use it day to day.

Every snippet after section 1 (which only shows definitions) is compiled and run by
[`ErrorHandlingGuideSpec`](https://github.com/llm4s/llm4s/blob/main/modules/core/src/test/scala/org/llm4s/error/ErrorHandlingGuideSpec.scala),
so the names and calls in it match the current API, and each snippet shows the imports it needs. If you
change one, change the other.

## 1. What is `Result[A]`?

```scala
type Result[+A] = Either[LLMError, A]
```

A call either succeeds with a `Right(value)` or fails with a `Left(error)`, where `error` is an
`LLMError`. There is no third outcome: nothing is thrown past you, and a `null` is never returned in
place of an error.

Two imports come up. The type alias is in `org.llm4s.types`; the helper object (`Result.success`,
`Result.traverse` and friends, used in [section 8](#8-combining-results)) is `org.llm4s.Result`:

```scala
import org.llm4s.Result                 // the helper object
import org.llm4s.types.{ Result, TryOps } // the type alias, and Try-to-Result syntax
```

## 2. The basic pattern

Match on the result. `Right` holds the value and `Left` holds the error:

```scala
import org.llm4s.llmconnect.model.Completion
import org.llm4s.types.Result

def basicPattern(result: Result[Completion]): String =
  result match {
    case Right(completion) => s"Response: ${completion.content}"
    case Left(error)       => s"Error: ${error.message}"
  }
```

A completion's text is `completion.content`. `error.message` is for people;
`error.formatted` adds the error's type, code and context, which is what to log.

## 3. Chaining with for-comprehensions

Most programs make several calls that can each fail. A `for` comprehension runs them in order and
stops at the first `Left`, which becomes the result. The calls after it do not run:

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.Conversation
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result

def chained(): Result[String] =
  for {
    providerConfig <- Llm4sConfig.defaultProvider()
    registry       <- Llm4sConfig.modelRegistryService()
    given ModelRegistryService = registry
    client       <- LLMConnect.getClient(providerConfig)
    conversation <- Conversation.userOnly("What is Scala?")
    completion   <- client.complete(conversation)
  } yield completion.content
```

Loading the configuration, building the client, building the conversation (which validates it) and
making the call are four steps that can each fail, and you handle them once, at the end.
`Llm4sConfig.defaultProvider()` reads the section `llm4s.providers.provider` names; see
[Configuration](../getting-started/configuration.md#named-provider-sections).

## 4. Error types and when each is raised

Every error is an `LLMError`, carrying a `message`, an optional `code` and a `context` map. The types in
`org.llm4s.error` are each also marked as one of two kinds:

- **Recoverable** (`RecoverableError`): the call may succeed if tried again, perhaps after you do
  something first (wait, re-read a record, correct the request).
- **Non-recoverable** (`NonRecoverableError`): trying again will not help; something has to change.

`LLMError.isRecoverable(error)` tells you which. It answers for every error: one that carries neither marker,
as some errors from other modules do (see [errors defined by other modules](#errors-defined-by-other-modules)),
is not recoverable, since nothing says a retry can help.

| Error | Recoverable | Where the library raises it |
|---|---|---|
| `AuthenticationError` | no | A provider rejects the credentials: HTTP 401 or 403, Anthropic's `UnauthorizedException`, Bedrock's access-denied or missing-credentials exceptions, Gemini's invalid-key 400. Also when Vertex AI or watsonx cannot obtain a token, and from `DefaultErrorMapper` for an exception whose message names HTTP status 401 (`HTTP 401`, `401 Unauthorized`, `status code: 401`). |
| `ConfigurationError` | no | Configuration is missing or invalid: no provider section, no API key, a provider id that no module on the classpath registers, an embedding model with no known dimensions, or a block a config loader cannot read (providers, embeddings, tracing, metrics, tools, RAG, speech). |
| `ValidationError` | no | A request or value is rejected: an invalid message or conversation, a model the model registry does not know, a guardrail rejecting input or output (the built-in guardrails reject with one), HTTP 400 (on field `request`), Anthropic's or Bedrock's invalid-request exceptions, or a response body that cannot be parsed. |
| `InvalidInputError` | no | Only `llm4s-image`'s image processing (`LocalImageProcessor`, saving an image): an unreadable path, a bad resize or crop, a path traversal. It carries the `field`, the `value` and the `reason`. |
| `RateLimitError` | yes | A provider's rate limit: HTTP 429, or Anthropic's or Bedrock's throttling exception. Also `ReliableClient`'s own limiter, and `DefaultErrorMapper` for an exception whose message names HTTP status 429 (`HTTP 429`, `429 Too Many Requests`, `status code: 429`). It carries `retryAfter` when the provider says how long to wait. |
| `ServiceError` | yes | Any other non-2xx status from a provider (through `HttpErrorMapper` or `Llm4sHttpClient`, and from Bedrock and watsonx), or a call rejected by an open circuit breaker (`ReliableClient` or `ErrorRecovery.CircuitBreaker`, status 503). It carries `httpStatus`; see the note below. |
| `NetworkError` | yes | A connection fails, a host is unknown or I/O breaks, in llm4s's HTTP client or the Bedrock client; `DefaultErrorMapper`, which the OpenAI and Anthropic clients use for I/O failures, gives one for a socket timeout or a refused connection. Also a URL refused by the SSRF check, and a failed `llm4s-rag` URL, web-crawl or S3 load, including a non-2xx answer to `UrlLoader`. |
| `TimeoutError` | yes | A connect, request or socket timeout elapses in llm4s's own HTTP client, or a `ReliableClient` deadline passes. The vendor-SDK clients map timeouts themselves: OpenAI and Bedrock report a `NetworkError`, and Anthropic maps its exceptions through `DefaultErrorMapper`. |
| `APIError` | yes | Only `llm4s-image`'s vision clients (OpenAI, Anthropic, Gemini), through `LLMError.apiCallFailed`, when the vision API call fails or returns no text; it carries the provider and, optionally, a status code. Image generation has its own errors (see below). |
| `ExecutionError` | yes | Only `ErrorRecovery.recoverWithBackoff`, when it runs out of attempts ([section 9](#9-recovering-from-failures)). Tool, MCP and orchestration failures are other types; see the note below. |
| `SystemError` | yes | Not raised by the library; available for your own code, for an unexpected failure that may be transient. |
| `OptimisticLockFailure` | yes | Only `llm4s-memory-postgres`'s `PostgresMemoryStore`, when another writer updated the same memory record first; re-read it and try again. |
| `CancelledError` | no | The thread was interrupted. Interruption is how llm4s cancels work, and a cancelled call is never retried. |
| `ProcessingError` | no | A storage, parsing or processing step fails: a vector-store, keyword-index or memory-store operation, RAG document loading, extraction or permissions, knowledge-graph storage, extraction or queries, speech audio processing, image encoding or saving. |
| `NotFoundError` | no | A memory store (in-memory, SQLite, Postgres) is asked to update a memory it does not hold, or `VectorStoreFactory` is given an unknown backend name. |
| `ContextError` | no | Context compression fails: `LLMCompressor`'s LLM call fails, or `ToolOutputCompressor` cannot store an artifact or parse JSON content. |
| `TokenizerError` | no | `ConversationTokenCounter` has no tokenizer for the requested id. |
| `SimpleError` | no | The MCP client and its transports (`llm4s-mcp`): connection, session, JSON-RPC and server-process failures. |
| `UnknownError` | no | An unexpected exception, wrapped: the fallback of `DefaultErrorMapper` (and so of `toResult` and `toLLMError`), an unexpected failure in llm4s's HTTP client, or a tracing backend that fails to start or to export. |

The table lists the `LLMError` types in `org.llm4s.error`, the package that is the source of truth for this core
set. Other modules define errors of their own, below.

**Same name, different type.** A failed tool call is not an `org.llm4s.error.ExecutionError`.
`ToolRegistry.execute`, and the MCP tool registry, return a `ToolCallError` (`org.llm4s.toolapi`), whose
case for a tool that threw is `ToolCallError.ExecutionError`. `ToolCallError` is not an `LLMError`, and the
agent hands it back to the model as the tool's result instead of failing the run. Orchestration fails with
`OrchestrationError.NodeExecutionError` or `PlanExecutionError`, and a graph's tool loop with
`GraphError.ToolFailed`; both are described below.

**`ServiceError` and its status.** The marker says a `ServiceError` is recoverable, but a 404 is not
going to fix itself. When it matters, look at `httpStatus`: `error.isRecoverableStatus` (from
`ServiceError.ServiceErrorOps`) is true for 5xx, 429 and 408. The library's automatic retries retry a
`ServiceError` only when it is; see the next paragraph.

**Recoverable is not the same as retried automatically.** Every retry in the library -
`recoverWithBackoff`, `ReliableClient`'s `RetryPolicy`, `LLMClientRetry` and the agent graph's default node
retry - uses one rule, `RetryPolicy.isRetryable`: it resends the identical request with nobody in the loop,
so it retries a recoverable error unless repeating the request cannot help. That leaves out two recoverable
cases: a `ServiceError` or an `APIError` with a client-error status (any 4xx but 408 and 429), because the
request itself is wrong, and an `OptimisticLockFailure`, because you must re-read the record first. An
`APIError` with no status is retried. A non-recoverable error is never retried, and neither is an error that
carries no marker.

**Which status becomes which error.** The status mapping in the table is `HttpErrorMapper`'s: 401 and
403 to `AuthenticationError`, 429 to `RateLimitError`, 400 to `ValidationError`, any other non-2xx to
`ServiceError`. The OpenAI, Azure, Requesty, Gemini, Vertex AI, Ollama, watsonx and OpenAI-compatible
clients use it; Gemini first turns a 400 that reports an invalid API key into an `AuthenticationError`.
The Anthropic client maps its SDK's exceptions instead: unauthorized (401) to `AuthenticationError`, rate
limited to `RateLimitError` (with no `retryAfter`), invalid data to a `ValidationError` on field `input`,
and anything else, including other HTTP statuses, through `DefaultErrorMapper`
([section 7](#7-turning-exceptions-into-errors)), usually to an `UnknownError`. The Bedrock client maps the
AWS SDK's exceptions: throttling or an exceeded quota to `RateLimitError`, an invalid request to
`ValidationError`, access denied, a 401 or 403, or missing credentials to `AuthenticationError`, any other
service status to `ServiceError`, and any other client failure to `NetworkError`.

### Errors defined by other modules

Some modules add their own `LLMError` subtypes. The errors below carry **neither** marker, so
`LLMError.isRecoverable` reports them as not recoverable and the library's automatic retries leave them alone.
For each, the right answer depends on more than the type (a status inside a string `code`, a flag, a wrapped
cause, which of several failures it reports), so none carries a marker:

| Module | Package | Errors |
|---|---|---|
| `llm4s-core` | `org.llm4s.llmconnect.model` | `EmbeddingError`: how `EmbeddingClient.embed` and the embedding providers (OpenAI, Ollama, Voyage, Cohere, Jina) report a failure other than cancellation, except that the Cohere provider reports a 429 as a `RateLimitError` |
| `llm4s-agent` | `org.llm4s.agent.orchestration` | `OrchestrationError.PlanExecutionError` from `PlanRunner`; `NodeExecutionError` from `PlanRunner` and `TypedAgent`, which has its own `recoverable` flag (`Policies.withRetry` reads it) |
| `llm4s-rag` | `org.llm4s.rag.evaluation`, `org.llm4s.reranker` | `EvaluationError` from RAGAS evaluation and the RAG benchmark tools; `RerankError` from the Cohere and LLM rerankers (`Reranker.rerank`) |
| `llm4s-speech` | `org.llm4s.speech.stt`, `.tts` | `STTError.ProcessingFailed` from the speech-to-text clients (an empty transcription, no recognisable speech, an unparseable response; its `retryable` flag says `true`); `TTSError.SynthesisFailed` from the text-to-speech clients (an empty audio body) |

The rest of those families are marked:

| Module | Recoverable | Non-recoverable |
|---|---|---|
| `llm4s-agent` (`org.llm4s.agent.orchestration`) | `OrchestrationError.AgentTimeoutError`, from `Policies.withTimeout` | `PlanValidationError` and `TypeMismatchError`, from `PlanRunner` |
| `llm4s-speech` (`org.llm4s.speech.stt`, `.tts`, `.io`) | `STTError.EngineNotAvailable`, `TTSError.EngineNotAvailable` | `STTError.UnsupportedFormat`, `STTError.InvalidInput`, `WavFileGenerator.WavError`, `AudioIO.AudioIOError` |

So are two whole families: `GraphError` in `llm4s-agent`
(`org.llm4s.agent.graph`, from the graph runtime; every case is non-recoverable except `DeadlineExceeded`)
and `ImageGenerationError` in `llm4s-image` (`org.llm4s.imagegeneration`, from the image-generation
clients; a `ServiceError` there is recoverable only for a transient status). The image module reuses the
names `AuthenticationError`, `RateLimitError`, `ServiceError`, `ValidationError` and `UnknownError`, so
import those by package instead of with a wildcard next to `org.llm4s.error._`. Its vision clients
(`org.llm4s.imageprocessing`) return the core errors in the table above.

## 5. Handling specific error types

Match on the type to react differently to each failure. Put the specific cases first and finish with
a catch-all:

```scala
import org.llm4s.error._
import org.llm4s.types.Result

def describe(result: Result[String]): String =
  result match {
    case Right(text) => s"ok: $text"
    case Left(e: RateLimitError) =>
      s"wait ${e.retryDelay.getOrElse(RateLimitError.DefaultRetryDelay)}, then retry"
    case Left(e: AuthenticationError) => s"fix the credentials for ${e.provider}"
    case Left(e: RecoverableError)    => s"transient, may succeed on retry: ${e.message}"
    case Left(e)                      => s"not retried (permanent, or not marked either way): ${e.message}"
  }
```

`RateLimitError.retryDelay` is the delay the provider asked for, or 30 seconds when it did not say.

Two things to know:

- **`LLMError` is not sealed**, so the compiler cannot tell you that a match is complete. Always end
  with a `case Left(e)`.
- **A custom error must say what kind it is.** If you define your own error type, mix in
  `RecoverableError` or `NonRecoverableError` as well as `LLMError`.
  `LLMError.isRecoverable` reports a type that is neither as not recoverable, so a transient failure that
  forgets its marker is never retried.

```scala
import org.llm4s.error.{ LLMError, NonRecoverableError }

final case class VendorError(message: String) extends LLMError with NonRecoverableError
```

## 6. Converting to exceptions (when you must)

Sometimes a framework expects an exception: a test setup, a `main` that should crash on bad
configuration, an API you do not control. Convert at the edge, in one place, and keep the error's
details:

```scala
import org.llm4s.types.Result

def orThrow[A](result: Result[A]): A =
  result.fold(error => throw new RuntimeException(error.formatted), identity)
```

`error.formatted` carries the error's type and context, so the stack trace says what went wrong.

Prefer `fold` to `result.getOrElse(throw new RuntimeException("LLM call failed"))`. The second
compiles, but it discards the error, so the exception cannot say what went wrong. And do not use
`result.getOrElse(default)` unless a silent fallback is what you want: a failure becomes the default
with no trace.

Do not throw from library code you write: return a `Result` and let the caller decide.

## 7. Turning exceptions into errors

The other direction matters just as much, because the JDK and other libraries throw. Wrap them at
the boundary so the rest of your code only sees `Result`:

```scala
import org.llm4s.Result
import org.llm4s.error.NotFoundError
import org.llm4s.error.ThrowableOps._
import org.llm4s.types.{ OptionOps, TryOps }

import scala.util.Try

Try("123".toInt).toResult                                  // Right(123)
Try("abc".toInt).toResult                                  // Left(...), the exception mapped to an LLMError
Result.safely(1 / 0)                                       // Left(...), a throwing block captured
new IllegalStateException("boom").toLLMError               // a Throwable as an LLMError
Option.empty[String].toResult(NotFoundError("no such key", "model")) // Left(NotFoundError)
```

`toResult` and `toLLMError` map an exception through `DefaultErrorMapper`: an interrupt becomes a
`CancelledError`, a socket timeout or a refused connection a `NetworkError`, an exception whose message
names HTTP status 401 or 429 an `AuthenticationError` (code `401`, with the redacted message) or a
`RateLimitError`, and anything else an `UnknownError` that keeps the exception. A message names a status
only in an HTTP context, at a word boundary - after `HTTP`, `status`, `status code`, `error code` or
`response code`, before its reason phrase (`401 Unauthorized`, `429 Too Many Requests`), or leading the
message as `openai-java` and `anthropic-java` write it (`401: <body>`) - so `Index 4012 out of bounds` or
`port 14290` is an `UnknownError`. Pass your own `ErrorMapper` for a finer mapping. `Try` (and so
`Result.safely`) never captures an `InterruptedException`, which Scala treats as fatal, so let an
interrupt propagate or map it yourself with `toLLMError`.

To make an error yourself, use the type's smart constructor:

```scala
import org.llm4s.error.{ ConfigurationError, NotFoundError, ValidationError }

ValidationError("model", "must not be empty")
ConfigurationError("no provider configured", List("llm4s.providers.provider"))
NotFoundError("no such key", "model")
```

## 8. Combining results

When you have a list of things to do, `Result` has helpers so you do not write the loop:

```scala
import org.llm4s.Result
import org.llm4s.types.{ Result, TryOps }

import scala.util.Try

def parseAll(inputs: List[String]): Result[List[Int]] =
  Result.traverse(inputs)(s => Try(s.toInt).toResult)

parseAll(List("1", "2", "3")) // Right(List(1, 2, 3))
parseAll(List("1", "x", "y")) // Left(...), the first failure
```

- `Result.traverse` stops at the first failure: it does not call the function on the elements after
  it, so side effects and expensive work stop there too. `Result.sequence` returns the first failure
  in a list of results that have already been computed.
- `Result.validateAll(items)(check)` runs every check and returns **all** the failures as a
  `Left(List[LLMError])`: use it when you want to report every problem at once.
- `Result.combine(a, b)` joins two independent results into a tuple.

## 9. Recovering from failures

A recoverable error is worth a retry. `ErrorRecovery.recoverWithBackoff` calls an operation up to
`maxAttempts` times in all, waiting between attempts. Here `client` is any `LLMClient` and `conversation` a `Conversation`, built as in
[section 3](#3-chaining-with-for-comprehensions):

```scala
import org.llm4s.error.ErrorRecovery

import scala.concurrent.duration._

val result = ErrorRecovery.recoverWithBackoff(
  () => client.complete(conversation),
  maxAttempts = 3,
  baseDelay = 1.second
)
```

It retries what every retry in the library retries (`RetryPolicy.isRetryable`; see
[section 4](#4-error-types-and-when-each-is-raised)), each error on its own schedule. The delays are not
exponential:

| Error | Wait before the next attempt |
|---|---|
| `RateLimitError` | Its `retryDelay`: the provider's `retryAfter` when it gave one, else 30 seconds (`RateLimitError.DefaultRetryDelay`). `baseDelay` is not used. |
| `ServiceError` with a 5xx, 429 or 408 status | The provider's `retryAfter` when it gave one, else `baseDelay` times the number of the attempt that failed: `baseDelay`, then `2 * baseDelay`, and so on. |
| `TimeoutError` | `baseDelay`, every time. |
| `NetworkError`, `ExecutionError`, `SystemError`, and an `APIError` with no status or a 5xx, 429 or 408 one | `baseDelay` times the number of the attempt that failed, as for a `ServiceError` without a hint. |

Every other error comes back unchanged straight away, whichever attempt it happens on: a
`ValidationError` on the last attempt, after a retried timeout, is still a `ValidationError`. That
includes a `ServiceError` or an `APIError` with any other status and an `OptimisticLockFailure`. A
`CancelledError` is never retried or wrapped, and an interrupt during a wait returns one.

When the last attempt fails with a retried error, you get an `ExecutionError` whose
message gives the number of attempts and the last error's message, and whose `operation` is the last
error's `formatted` text; the original error's type is not kept. `ExecutionError` is itself a
`RecoverableError` that the rule retries, so an outer `recoverWithBackoff` or `RetryPolicy` will retry it. The operation
always runs at least once, even if `maxAttempts` is below 1.

To stop calling a service that keeps failing, wrap the call in an `ErrorRecovery.CircuitBreaker`.
After `failureThreshold` consecutive failures (a success resets the count) it opens, and further calls fail fast with a `ServiceError`
without reaching the service, until `recoveryTimeout` has passed and a single probe call is allowed.

For production use, `ReliableClient` wraps a client with retry (its own `RetryPolicy`, with
configurable backoff), a circuit breaker, and an optional deadline and rate limit, in one place. See [Error Recovery](patterns/error-recovery.md) for retry strategies, fallbacks and
graceful degradation.

## 10. Testing code that returns Result

Check the `Right` and the `Left` the same way you would any value. With ScalaTest's `EitherValues`,
`.value` unwraps a `Right` and `.left.value` unwraps a `Left`, failing the test with a clear message
if it is the other one:

```scala
import org.llm4s.error.ValidationError
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ResultSpec extends AnyWordSpec with Matchers with EitherValues {
  "a Result" should {
    "unwrap with EitherValues, and check the error type" in {
      val ok: Result[String]     = Right("expected")
      val failed: Result[String] = Left(ValidationError("model", "empty"))

      ok.value shouldBe "expected"
      ok.map(_.toUpperCase) shouldBe Right("EXPECTED")
      failed.left.value shouldBe a[ValidationError]
      failed.left.value.message should include("empty")
    }
  }
}
```

To test code that calls an LLM without a network, give it a client that returns what you choose.
See the [Testing Guide](../getting-started/testing-guide.md).

## Rules of thumb

- Return `Result` from your own functions; do not throw.
- Convert exceptions to errors where they enter your code, and errors to exceptions only where a
  framework forces you to, in one place.
- Match on specific types first, then on `RecoverableError`, then a catch-all. `LLMError.isRecoverable`
  asks the same question of any error.
- Retry only what is recoverable, with a limit and a delay; never retry a `CancelledError`. The
  library's own retries also leave out a client-error status and an `OptimisticLockFailure`.
- Log `error.formatted`, show `error.message`, and never put an API key in either.

## See also

- [Basic Usage](basic-usage.md): your first calls and the `Result` type
- [Error Recovery](patterns/error-recovery.md): retries, circuit breakers and fallbacks
- [Configuration](../getting-started/configuration.md): named provider sections and API keys
- [Testing Guide](../getting-started/testing-guide.md): testing with mock clients
