---
layout: page
title: Provider Exchange Logging
parent: Monitoring
grand_parent: User Guide
nav_order: 1
---

# Provider Exchange Logging
{: .no_toc }

Capture the raw request and response of each provider call, to a file or to your own code, to debug what was sent and what came back.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

> **Privacy warning.** An exchange holds the request and response bodies as the provider saw them: your
> prompts, the conversation history, tool arguments and results, any documents you retrieved into the
> prompt, and the model's answers. They routinely contain personal data and business data. The file sink
> redacts credentials by pattern, on a best-effort basis, and cannot recognise personal data; **a sink
> you write receives the bodies untouched.** Treat everything this feature writes as sensitive: keep it
> off unless you are debugging, protect and delete what it writes, and do not share it unreviewed.
> See [Redaction and truncation](#redaction-and-truncation) and [Using it safely](#using-it-safely).

## What it is

Exchange logging records, for each call a client makes to a provider, one `ProviderExchange`: the JSON
body it sent, the body it got back (or the error), the timing and the outcome. It answers "what exactly
did the provider receive, and what exactly did it say?", which tracing does not: tracing records what an
agent did, not the wire.

It is **off by default** and **opt in per client**. You turn it on with a `ProviderExchangeLogging`
setting carried by `LlmClientOptions`, and the setting names a `ProviderExchangeSink` that receives each
completed exchange. LLM4S ships a sink that appends JSON Lines to a file; you can write your own.

## What is recorded

A `ProviderExchange` has these fields:

| Field | Type | What it holds |
|-------|------|---------------|
| `exchangeId` | `String` | A random UUID for this exchange |
| `provider` | `String` | The client's provider name, such as `deepseek` |
| `model` | `Option[String]` | The model the client is **configured** with, not the model the response reports (a router can serve another) |
| `requestId`, `correlationId` | `Option[String]` | Reserved: no built-in client sets them, so they are always `None` |
| `startedAt`, `completedAt` | `Instant` | When the call began and when it finished |
| `duration` | `FiniteDuration` | The time between the two |
| `outcome` | `ProviderExchangeOutcome` | `Success` or `Error`; see the limitations for `Cancelled` |
| `requestBody` | `String` | The JSON body sent to the provider |
| `responseBody` | `Option[String]` | The body received: for a streamed call, the raw server-sent events; for an HTTP error, the error body; `None` when no response arrived |
| `errorMessage` | `Option[String]` | The error's message, when the call failed |

Headers are not recorded, so an API key that travels in a header is not in an exchange.

These clients record both `complete` and `streamComplete`: OpenAI, Azure and Requesty
(`llm4s-openai`); Anthropic; Gemini and Vertex AI; Ollama; the OpenAI-compatible clients (DeepSeek, Z.ai,
OpenRouter, Mistral, Cohere and the generic `openai-compatible` provider); Bedrock; and watsonx.
Embedding, image and speech clients do not record exchanges.

## Turning it on

### From configuration

```hocon
llm4s {
  exchangeLogging {
    enabled = true
    dir     = "exchange-logs"
  }
}
```

The two keys have variables bound in `reference.conf`: `LLM4S_EXCHANGE_LOGGING_ENABLED` and
`LLM4S_EXCHANGE_LOGGING_DIR`. `enabled` defaults to `false`, and there is no default directory:
`enabled = true` without a `dir` is a `ConfigurationError` ("Provider exchange logging is enabled but
llm4s.exchangeLogging.dir is missing").

**Nothing reads this section on its own.** `LLMConnect.getClient(config)` uses
`LlmClientOptions.default`, which has logging disabled. Read the setting with
`Llm4sConfig.exchangeLogging()` and hand it to the client:

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.{ LLMClient, LLMConnect, LlmClientOptions }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result

def fromConfiguration(): Result[LLMClient] =
  for {
    providerConfig <- Llm4sConfig.defaultProvider()
    registry       <- Llm4sConfig.modelRegistryService()
    given ModelRegistryService = registry
    exchangeLogging <- Llm4sConfig.exchangeLogging()
    client <- LLMConnect.getClient(providerConfig, LlmClientOptions(exchangeLogging = exchangeLogging))
  } yield client
```

When logging is enabled, `Llm4sConfig.exchangeLogging()` creates the directory if it is missing and the
file for this run immediately, so a directory that cannot be written is reported at start-up, not at the
first call.

### From code

`ProviderExchangeSink.createRunScopedJsonl` creates the file sink; `ProviderExchangeLogging.enabled` wraps
a sink in the setting:

```scala
import java.nio.file.Path
import org.llm4s.llmconnect.{ ProviderExchangeLogging, ProviderExchangeSink }
import org.llm4s.types.Result

def jsonlLogging(dir: Path): Result[ProviderExchangeLogging] =
  ProviderExchangeSink.createRunScopedJsonl(dir).map(ProviderExchangeLogging.enabled)
```

Any sink works the same way: wrap it in `ProviderExchangeLogging.enabled` and give it to the client in
`LlmClientOptions`:

```scala
import org.llm4s.llmconnect.{ LLMClient, LLMConnect, LlmClientOptions, ProviderExchangeLogging, ProviderExchangeSink }
import org.llm4s.llmconnect.config.ProviderConfig
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result

def clientFor(providerConfig: ProviderConfig, sink: ProviderExchangeSink)(using
  ModelRegistryService
): Result[LLMClient] =
  LLMConnect.getClient(providerConfig, LlmClientOptions(exchangeLogging = ProviderExchangeLogging.enabled(sink)))
```

## The JSON Lines file

`createRunScopedJsonl` creates one file for the run, named from the time it was created:
`provider-exchanges-2026-10-07T10-20-30Z.jsonl`. If that name is taken (two runs in the same second), it
adds a number: `...Z-2.jsonl`, `...Z-3.jsonl`. It creates missing directories, and returns a
`ConfigurationError` if the path is a file. The file exists, empty, as soon as the sink is created; each
exchange is appended as one line when the call completes. A line, from a real run against a local test
server that answered 401:

```json
{"exchange_id":"bd1e7d34-3c95-44e5-a9be-428bf3ca85f1","provider":"deepseek","model":"deepseek-chat","request_id":"","correlation_id":"","started_at":"2026-10-07T10:27:19.668236Z","completed_at":"2026-10-07T10:27:19.676649Z","duration_ms":"8","outcome":"Error","request_body":"{\"model\":\"deepseek-chat\",\"messages\":[{\"role\":\"user\",\"content\":\"will fail\"}],\"temperature\":0.7,\"top_p\":1}","response_body":"{\"error\":\"Unauthorized\"}","error_message":"Authentication failed for deepseek: Unauthorized"}
```

The keys are always these twelve, in this order. A value that is absent is an empty string, not a missing
key or `null`. `duration_ms` is a JSON **string** (`"8"`), so read it as one. `request_body`,
`response_body` and `error_message` are the text of the exchange, escaped into a JSON string, after the
redaction and truncation below. The sink does not rotate or limit the file, and does not set permissions
on it.

## Redaction and truncation

The file sink changes `request_body`, `response_body` and `error_message` before writing them:

- **Redaction.** It replaces, with `[REDACTED]`: an `Authorization` header and bearer tokens, URL query
  parameters with sensitive names (`api_key`, `token`, `password` and similar), JSON fields with sensitive
  names (also when the JSON sits inside a prompt or response string with escaped quotes,
  `\"api_key\": \"...\"`, or is single-quoted, or is cut off before its closing quote, as a truncated
  payload leaves it), numbers under such a key (including exponent forms such as `1e10`, and inside a
  string too; the number is written back as the string `"[REDACTED]"`, so the JSON still parses), arrays
  and objects under such a key (`{"token": ["..."]}`, `{"credentials": {"user": "...", "pass": "..."}}`:
  every leaf under the key is replaced, however deep - strings, numbers and, outside a string, bare
  words - also inside a string and when the key or the leaves are single-quoted, as a Python dict is,
  `{'token': ['...']}`; the brackets, the keys of nested objects, `true`, `false` and `null` are kept, so
  the JSON still parses and keeps its shape; inside a string a single-quoted container ends where the
  string does, so a `'token': [` that a message merely mentions does not take the fields after it, nor
  the prose after an apostrophe in it (`it's`) unless escaped JSON (`\"`) follows it, and an unclosed
  `'password': '` there ends where the string ends rather than taking the rest of the document - but only
  where no `'` that could close it follows anywhere in the input, and not where that end would leave the
  value empty (`'password': '",`); otherwise it runs to the next `'`, or to the end of the input, as it
  does outside a string, so a credential that holds a `"` (`'Qx"]]9secret'`) is redacted whole; outside
  any string, the bare words after an unclosed `'token': [` are replaced to the end of the input, since nothing tells them from leaves; and
  a `\"`-quoted leaf under a single-quoted key there, `\"{'token': [\\\"...\\\"]}\"`, is left, since a
  `"` inside a string may be the end of a string inside it), `key=value` pairs and quoted
  `KEY="value"` / `KEY='value'` assignments outside a query string (for example `password=...`,
  `spring.datasource.password=...`, `PASSWORD="..."`), `key: value` header lines (for example
  `x-api-key: ...`), and strings shaped like known provider API keys (for example `sk-` keys). A quoted
  value is redacted whole, escaped quotes included. A key is sensitive when its whole name, lower-cased
  with `_` and `-` dropped, is `token`, `authorization` or `credential(s)`, or ends in `apikey`, `secret`,
  `password`, `passwd`, `privatekey`, `accesstoken`, `refreshtoken`, `idtoken`, `authtoken`,
  `sessiontoken` or `bearertoken`: `client_secret`, `x-api-key`, `refresh_token` and `db_password` are
  redacted; `max_tokens`, `prompt_tokens`, `token_count` and `next_page_token` are not, because the match
  is never on a substring.
- **Truncation.** It keeps the first 1000 characters, after redaction, and appends
  `... [truncated, N chars omitted]` with the count it dropped. Redact the full text before you cut it, as
  this sink and every other llm4s call site do: redaction of text that is already cut off is weaker. An
  unclosed single-quoted value inside a raw (unescaped) double-quoted string, holding a `"` followed by what
  follows a string's end and with no `'` after it that could close it, ends at that `"`, so the part after
  it is shown - `msg="{'password': 'Qx"]]9secretPW` cut off there becomes
  `msg="{'password': '[REDACTED]"]]9secretPW`. A closing `'` with a letter or digit on both sides
  (`'Qx"]]9SECRETPW'it"`) reads as an apostrophe, not as the end of the value, so the same happens there.

Both are limits you should know about:

- **The file is not a full record.** A prompt or an answer longer than 1000 characters is cut, so a long
  conversation or a long completion is only partly in the file. For complete bodies, write your own sink.
- **Redaction recognises patterns, not meaning.** It does not look for personal data: an email address or
  a phone number is written as it is. Text you put in a prompt is inside a JSON string in the request
  body, and a credential there is not guaranteed to match a pattern: a secret that is not under a key (a
  key pasted into a prompt as prose), and JSON escaped twice (JSON inside a string inside a string,
  `\\\"api_key\\\": ...`) are not redacted, by decision ([#1576](https://github.com/llm4s/llm4s/issues/1576)):
  a pattern cannot know what a secret is, and only one level of escaping is recognised. A URL query value
  that holds a `'` before `,`, `)`, `;` or `:` (`?token=ab',cd`) is redacted only up to that quote, since it
  cannot be told from the quote that ends a string, as in `fetch('...?token=ab')`. Do not rely on
  redaction to make the file safe to share.

A sink you write is **not** redacted or truncated: it is handed the exchange exactly as the client built
it, including the full bodies. If your sink writes bodies anywhere, redaction is your job.

## Writing your own sink

A sink implements one method:

```scala
trait ProviderExchangeSink:
  def record(exchange: ProviderExchange): Unit
```

The client calls it once the call has finished, inline, on the thread that made the call (the tests behind
this guide check that for the OpenAI-compatible clients), so keep it fast. If it throws, the client discards the exception: the call still succeeds, and nothing logs the failure, so a
broken sink is silent. Catch and report your own errors.

A sink that keeps the metadata and never touches a body is the safest way to see what your application
sends and how long it takes:

```scala
import org.llm4s.llmconnect.{ ProviderExchange, ProviderExchangeSink }

final class MetadataOnlySink(out: String => Unit) extends ProviderExchangeSink:
  def record(exchange: ProviderExchange): Unit =
    out(s"${exchange.provider} ${exchange.model.getOrElse("-")} ${exchange.outcome} ${exchange.duration.toMillis}ms")
```

With `out = println`, a call prints `deepseek deepseek-chat Success 180ms`. To keep bodies only when
something went wrong, wrap another sink:

```scala
import org.llm4s.llmconnect.{ ProviderExchange, ProviderExchangeOutcome, ProviderExchangeSink }

final class ErrorsOnlySink(delegate: ProviderExchangeSink) extends ProviderExchangeSink:
  def record(exchange: ProviderExchange): Unit =
    if exchange.outcome == ProviderExchangeOutcome.Error then delegate.record(exchange)
```

## Using it safely

- **Keep it off in production** unless you are chasing a specific problem, and turn it off again after.
  For production monitoring use [tracing and metrics](index), which record what an agent did and what
  it cost rather than the provider's wire format.
- **Protect the directory.** The sink creates the file with the process's default permissions; restrict
  access to the directory yourself, and delete old files, because nothing rotates or expires them.
- **Keep the logs out of version control.** The samples' default directory,
  `.devlog/logs/provider-exchanges`, is not in the repository's `.gitignore`; if you enable logging while
  running the samples, add the directory to yours before you commit.
- **Mind the cost.** The file sink opens, appends and closes the file for each exchange, on the thread
  making the call.

## Known limitations

- The file sink truncates bodies to 1000 characters; see above.
- Redaction is pattern-based and best effort; it does not detect personal data. A secret that is not
  under a key, and JSON escaped twice, are not redacted.
- `requestId` and `correlationId` are never filled by the built-in clients.
- `ProviderExchangeOutcome` declares `Cancelled`, but the recorder produces only `Success` and `Error`: a
  cancelled call is recorded as an `Error` carrying the cancellation message.
- A sink that throws fails silently.
- Embedding, image and speech clients do not record exchanges.
- Nothing enables logging from configuration automatically; you pass the setting to the client.
- `duration_ms` in the file is a string.

## Related

- [Monitoring](index): tracing, logging and health checks for production
- [Writing a Provider](../writing-a-provider): how a provider records its exchanges with
  `ProviderExchangeRecorder`
- [Configuration](../../getting-started/configuration): the full configuration reference
