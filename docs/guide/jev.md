---
layout: page
title: Jev decision model
parent: User Guide
nav_order: 12
---

# Jev, TypeSafe's decision model

`llm4s-jev` is a client for [Jev](https://docs.typesafe.ai/introduction), TypeSafe's System One model. Jev is **not a chat
model**. You send it a *state* and named, typed *questions*, and it answers each question with a typed answer your code can
act on directly: the probability of a yes, the selected option with a distribution over the options, or a score along ordered
levels. Your code stays in control: it asks narrow questions and decides what to do with the answers.

That is why this module has its own client. Jev is not an `LLMClient`, returns no `Completion`, and has no conversation and no
streaming. Sending a prompt through a chat client and parsing generated JSON would lose the typed question and answer
contract; so would treating Jev as an OpenAI-compatible provider.

{: .note }
> Not yet published. `llm4s-jev` exists in the build as of [#1265](https://github.com/llm4s/llm4s/issues/1265) but ships in
> the next release.

```scala
// same version as llm4s-core
libraryDependencies += "org.llm4s" %% "llm4s-jev" % llm4sVersion
```

It brings no dependency beyond `llm4s-core`, and is a Beta module under `modules/providers/jev`.

## Configure it

Set your TypeSafe API key in `TYPESAFE_API_KEY`, the variable TypeSafe's own SDKs read. Nothing else is needed:

```scala
import org.llm4s.config.JevConfigLoader

// Result[JevClient]: Left(ConfigurationError) when no key is set
val client = JevConfigLoader.default().flatMap(JevClient(_))
```

`JevConfigLoader` (in `org.llm4s.config`, like LLM4S's other loaders) reads the `llm4s.jev` block of your
`application.conf` at the application edge; `JevClient` itself reads no configuration and takes only the `JevConfig` it is
given. Every setting has a default:

```hocon
llm4s.jev {
  model   = "jev-1.13.0"   # pin the answers; the default is the alias jev-latest, which moves when a release ships
  timeout = 20 seconds     # per HTTP attempt; the default is 30 seconds
  retry {
    maxRetries     = 3     # after the first attempt; defaults: 2, 500 ms, 5 s, 0.25, 30 s
    backoffInitial = 500 milliseconds
    backoffMax     = 5 seconds
    jitter         = 0.25
    budget         = 30 seconds
  }
  # apiKey  = ...          # wins over llm4s.credentials.jev.apiKey, which TYPESAFE_API_KEY binds
  # baseUrl = ...          # TYPESAFE_BASE_URL; default https://api.typesafe.ai
}
```

| Setting | Environment variable | Default |
|---|---|---|
| `llm4s.credentials.jev.apiKey` (or `llm4s.jev.apiKey`) | `TYPESAFE_API_KEY` | none: required |
| `llm4s.jev.baseUrl` | `TYPESAFE_BASE_URL` | `https://api.typesafe.ai` |
| `llm4s.jev.model` | `TYPESAFE_DEFAULT_MODEL` | `jev-latest` |

Or build a config in code:

```scala
val config = JevConfig(apiKey = "tsk-...")
  .withModel("jev-1.13.0") // pin the answers; the default is the alias jev-latest
  .withTimeout(20.seconds)
  .withRetry(JevRetryPolicy(maxRetries = 3))

val client = JevClient(config) // validates the config
```

The key is a bearer token, so the client refuses to send it in the clear: the base URL must be `https`, except for a loopback
host (`localhost`, `127.0.0.0/8`, `::1`), which is how a test points the client at a local server. A URL that carries
credentials, such as `http://localhost@evil.example/`, is refused. The key never appears in `toString`, in an error or in a log
line.

## Ask questions

A request is a `state` and a map of questions. All the questions are answered against the one state in a single call, so ask
everything you might need at once and let your code decide which answers matter.

```scala
val decision = for {
  client <- JevClient(config)
  response <- client.evaluate(
    JevRequest(
      "Help! My payouts have been failing for 3 days.",
      Map(
        "department" -> JevQuestion.choice(
          "Which team should handle this?",
          "billing"   -> "Payments, invoicing, refunds",
          "technical" -> "Bugs, outages, integrations",
          "sales"     -> "Pricing, upgrades, new accounts"
        ),
        "urgent"      -> JevQuestion.noul("Does this need immediate human attention?"),
        "frustration" -> JevQuestion.score("How frustrated is the customer?", "Calm", "Frustrated", "Very angry")
      )
    )
  )
  department  <- response.choice("department")
  urgent      <- response.noul("urgent")
  frustration <- response.score("frustration")
} yield (department.choice, department.confidence, urgent.probability, frustration.score)
```

| Question | You give | The answer carries |
|---|---|---|
| `JevQuestion.Noul` | a yes/no question, optionally what yes and no mean | `probability` of yes, 0 to 1 |
| `JevQuestion.Choice` | options, each with an optional description (at most 255) | `choice`, a `probabilities` map and a `confidence` |
| `JevQuestion.Score` | 2 to 10 ordered level descriptions | `score` (it can land between levels), `levels` with their probabilities, and a `confidence` |

The ids (`"department"`, ...) are yours; the answers come back under the same ids and Jev never sees them. `state` and
`instructions` can be a string, or JSON structure when a question refers to data: see TypeSafe's
[API reference](https://docs.typesafe.ai/api). A request that breaks a documented limit is refused with a `ValidationError`
before anything is sent.

`confidence` is not the probability of the answer. It says how certain the model is, derived from the whole distribution, and
is what you gate an automatic action on. A Noul has no confidence on the wire. Each response also carries `model`, the
versioned model that answered (`jev-1.13.0` for `jev-latest`), which you should log, and `usage`.

### A worked example

[`JevTicketTriageExample`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/jev/JevTicketTriageExample.scala)
asks the three questions above about a support ticket and routes it with thresholds that are ordinary code: escalate when the
ticket looks urgent, queue it with its department otherwise, and hand it to a person when Jev is not sure which department.

```
TYPESAFE_API_KEY=... sbt "samples/runMain org.llm4s.samples.jev.JevTicketTriageExample"
```

It is a plain workflow step. Handing a routed ticket to a specialist agent is a `Handoff` at the point where the route is used,
and needs an LLM provider as well, so the sample stops at the decision.

## Errors

`evaluate` blocks and returns every failure as a `Result`:

```scala
def describe(error: LLMError): String = error match {
  case _: ValidationError     => "the request is wrong: fix it, retrying will not help"
  case _: AuthenticationError => "the key was rejected"
  case rate: RateLimitError   => s"rate limited, retry after ${rate.retryAfter.getOrElse(30.seconds)}"
  case svc: ServiceError      => s"TypeSafe failed with ${svc.httpStatus}"
  case _: TimeoutError        => "no answer in time"
  case _: NetworkError        => "could not reach TypeSafe"
  case _: CancelledError      => "the calling thread was interrupted"
  case other                  => other.message
}
```

| What happened | Error |
|---|---|
| The request breaks a documented limit | `ValidationError`, before anything is sent |
| 401, 403 | `AuthenticationError` |
| 400, 422 | `ValidationError`, carrying the server's explanation |
| 429 | `RateLimitError`, carrying the delay the server asked for |
| 408, 5xx including `529 Overloaded`, and any other status | `ServiceError` |
| No connection, or no answer within `timeout` | `NetworkError`, `TimeoutError` |
| A 200 whose body is not the documented shape, that leaves a question unanswered, or that answers one with another type, an option not offered or a level not described | `ProcessingError` |
| The calling thread is interrupted | `CancelledError`, with the interrupt flag kept |

TypeSafe does not document the JSON shape of an error body, so the text in the error is a best effort (a `message`, an
`error.message`, ...), truncated, and never the whole body. If a server echoes your API key in an error body, the key is
removed before anything is read from it.

## Retries, and what is not de-duplicated

Transient failures are retried the way TypeSafe's own SDKs do (`JevRetryPolicy.default`): a 408, 429 or 5xx, a connection
failure or a timeout, up to two retries, waiting 0.5 s doubling to at most 5 s with a quarter of each wait randomly taken off,
within a 30 s budget for the whole call. A delay the server asks for (`Retry-After`, or `retry-after-ms`, which wins when both
are sent) replaces the computed one, and no retry is started whose wait would reach the budget. A rejected key, an invalid
request and an interrupt are never retried, and the last error is returned as it is.

**There is no idempotency key.** TypeSafe's documentation describes no idempotency key, and no other mechanism to de-duplicate
a request, so this client does not invent one: each attempt of a retried request is a separate, billable call. If TypeSafe
documents a header for it, attach it to the request, and it is sent unchanged on every attempt:

```scala
val request = JevRequest("...", Map("urgent" -> JevQuestion.noul("Is it?")))
  .withHeader("X-Correlation-Id", "order-4711")
```

Headers a client sets itself (`Authorization`, `Content-Type`, `Accept`, ...) cannot be replaced, and a header name or value
with a line break is refused. Header names are case-insensitive: a request header replaces a configured one of the same name
in any case, and a map naming one header twice (`X-Trace` and `x-trace`) is refused.

## Limits and what is not verified

- Jev is text only (a string, or JSON of text values), has no streaming, and its context is 64k tokens per request, 32k for
  the `state` plus the longest question. TypeSafe's rate limits can change without notice. See [Models](https://docs.typesafe.ai/models).
- The client is tested against a local fake server built from TypeSafe's published API reference. **It has not been run
  against the live API.** Three details are not in that documentation and are assumptions: the JSON shape of an error body,
  whether `Retry-After` is seconds or a date (both are read), and which of `Retry-After` and `retry-after-ms` wins when both
  are sent (this client prefers `retry-after-ms`).
- Each HTTP attempt has a 30 s timeout by default, an LLM4S choice (the API documents none). The shared HTTP client reads a
  response in full, so the 16 MiB cap on a response refuses an oversized body before it is parsed, not before it is read.
