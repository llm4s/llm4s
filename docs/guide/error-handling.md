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

Every snippet below is compiled and run by
[`ErrorHandlingGuideSpec`](https://github.com/llm4s/llm4s/blob/main/modules/core/src/test/scala/org/llm4s/error/ErrorHandlingGuideSpec.scala),
so it matches the current API. If you change one, change the other.

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

All errors extend `LLMError` and carry a `message`, an optional `code`, and a `context` map. Each
type is also marked as one of two kinds:

- **Recoverable** (`RecoverableError`): the same call may succeed if tried again.
- **Non-recoverable** (`NonRecoverableError`): trying again will not help; something has to change.

`LLMError.isRecoverable(error)` tells you which.

| Error | Recoverable | Raised when |
|---|---|---|
| `AuthenticationError` | no | The provider rejects the credentials (HTTP 401 or 403). |
| `ConfigurationError` | no | Configuration is missing or invalid: no provider section, no API key, an unknown model. |
| `ValidationError` | no | A request fails validation before or at the provider (HTTP 400 becomes a `ValidationError` on field `request`). |
| `InvalidInputError` | no | An input value is rejected, with the `field`, the `value` and the `reason`. |
| `RateLimitError` | yes | The provider answers HTTP 429, or `ReliableClient`'s own limiter throttles the call. It carries `retryAfter` when the provider says how long to wait. |
| `ServiceError` | yes | Any other non-2xx status from a provider. It carries `httpStatus`; see the note below. |
| `NetworkError` | yes | A connection fails, a host is unknown, or I/O breaks. |
| `TimeoutError` | yes | A connect, request or socket timeout elapses. |
| `APIError` | yes | A provider call fails with a message and, optionally, a status code. |
| `ExecutionError`, `SystemError` | yes | A command or process fails, or something unexpected goes wrong that may be transient. |
| `OptimisticLockFailure` | yes | Two writers modify the same memory record; re-read it and try again. |
| `CancelledError` | no | The thread was interrupted. Interruption is how llm4s cancels work, and a cancelled call is never retried. |
| `ProcessingError` | no | Image or audio processing fails before anything is sent. |
| `NotFoundError` | no | A required key or resource does not exist. |
| `ContextError`, `TokenizerError` | no | A conversation does not fit the context window, or the tokenizer fails. |
| `SimpleError`, `UnknownError` | no | A bare message, or an unexpected exception that was wrapped. |

All of them live in `org.llm4s.error`. The table is the current set; the `org.llm4s.error`
package is the source of truth.

**`ServiceError` and its status.** The marker says a `ServiceError` is recoverable, but a 404 is not
going to fix itself. When it matters, look at `httpStatus`: `error.isRecoverableStatus` (from
`ServiceError.ServiceErrorOps`) is true for 5xx, 429 and 408.

## 5. Handling specific error types

Match on the type to react differently to each failure. Put the specific cases first and finish with
a catch-all:

```scala
import org.llm4s.error._
import org.llm4s.types.Result

import scala.concurrent.duration._

def describe(result: Result[String]): String =
  result match {
    case Right(text) => s"ok: $text"
    case Left(e: RateLimitError) =>
      s"wait ${e.retryDelay.getOrElse(RateLimitError.DefaultRetryDelay)}, then retry"
    case Left(e: AuthenticationError)         => s"fix the credentials for ${e.provider}"
    case Left(e) if LLMError.isRecoverable(e) => s"transient, may succeed on retry: ${e.message}"
    case Left(e)                              => s"permanent, do not retry: ${e.message}"
  }
```

`RateLimitError.retryDelay` is the delay the provider asked for, or 30 seconds when it did not say.

Two things to know:

- **`LLMError` is not sealed**, so the compiler cannot tell you that a match is complete. Always end
  with a `case Left(e)`.
- **A custom error must say what kind it is.** If you define your own error type, mix in
  `RecoverableError` or `NonRecoverableError` as well as `LLMError`.
  `LLMError.isRecoverable` throws a `MatchError` for a type that is neither.

```scala
final case class VendorError(message: String) extends LLMError with NonRecoverableError
```

## 6. Converting to exceptions (when you must)

Sometimes a framework expects an exception: a test setup, a `main` that should crash on bad
configuration, an API you do not control. Convert at the edge, in one place, and keep the error's
details:

```scala
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
import org.llm4s.error.ThrowableOps._
import org.llm4s.types.{ OptionOps, TryOps }

import scala.util.Try

Try("123".toInt).toResult                                  // Right(123)
Try("abc".toInt).toResult                                  // Left(...), the exception mapped to an LLMError
Result.safely(1 / 0)                                       // Left(...), a throwing block captured
new IllegalStateException("boom").toLLMError               // a Throwable as an LLMError
Option.empty[String].toResult(NotFoundError("no such key", "model")) // Left(NotFoundError)
```

`toResult` and `toLLMError` map an exception to the closest `LLMError`. `toLLMError` turns an
`InterruptedException` into a `CancelledError`; `Try` itself never captures one (Scala treats it as
fatal), so let an interrupt propagate or map it yourself with `toLLMError`.

To make an error yourself, use the type's smart constructor:

```scala
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

- `Result.traverse` and `Result.sequence` stop at the first failure.
- `Result.validateAll(items)(check)` runs every check and returns **all** the failures as a
  `Left(List[LLMError])`: use it when you want to report every problem at once.
- `Result.combine(a, b)` joins two independent results into a tuple.

## 9. Recovering from failures

A recoverable error is worth a retry. `ErrorRecovery.recoverWithBackoff` retries an operation with
exponential backoff:

```scala
import org.llm4s.error.ErrorRecovery

import scala.concurrent.duration._

val result = ErrorRecovery.recoverWithBackoff(
  () => client.complete(conversation),
  maxAttempts = 3,
  baseDelay = 1.second
)
```

It retries `RateLimitError` (waiting for the provider's `retryAfter` when there is one),
`ServiceError` and `TimeoutError`. Any other error comes straight back, and a
`CancelledError` is never wrapped or retried. When the attempts run out, you get an
`ExecutionError` that names the last error.

To stop calling a service that keeps failing, wrap the call in an `ErrorRecovery.CircuitBreaker`.
After `failureThreshold` failures it opens, and further calls fail fast with a `ServiceError`
without reaching the service, until `recoveryTimeout` has passed and a single probe call is allowed.

For production use, `ReliableClient` wraps a client with retry, a circuit breaker and rate limiting
in one place. See [Error Recovery](patterns/error-recovery.md) for retry strategies, fallbacks and
graceful degradation.

## 10. Testing code that returns Result

Check the `Right` and the `Left` the same way you would any value. With ScalaTest's `EitherValues`,
`.value` unwraps a `Right` and `.left.value` unwraps a `Left`, failing the test with a clear message
if it is the other one:

```scala
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers

val ok: Result[String]     = Right("expected")
val failed: Result[String] = Left(ValidationError("model", "empty"))

ok.value shouldBe "expected"
ok.map(_.toUpperCase) shouldBe Right("EXPECTED")
failed.left.value shouldBe a[ValidationError]
failed.left.value.message should include("empty")
```

To test code that calls an LLM without a network, give it a client that returns what you choose.
See the [Testing Guide](../getting-started/testing-guide.md).

## Rules of thumb

- Return `Result` from your own functions; do not throw.
- Convert exceptions to errors where they enter your code, and errors to exceptions only where a
  framework forces you to, in one place.
- Match on specific types first, then `LLMError.isRecoverable`, then a catch-all.
- Retry only what is recoverable, with a limit and a delay; never retry a `CancelledError`.
- Log `error.formatted`, show `error.message`, and never put an API key in either.

## See also

- [Basic Usage](basic-usage.md): your first calls and the `Result` type
- [Error Recovery](patterns/error-recovery.md): retries, circuit breakers and fallbacks
- [Configuration](../getting-started/configuration.md): named provider sections and API keys
- [Testing Guide](../getting-started/testing-guide.md): testing with mock clients
