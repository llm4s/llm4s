---
layout: page
title: "RFC 0009: The model call contract"
parent: RFCs
grand_parent: Reference
nav_order: 9
---

# RFC 0009: The model call contract

- **Status:** Proposed
- **Author(s):** @Somay-kousis
- **Tracking issue:** [#2613](https://github.com/llm4s/llm4s/issues/2613)
- **Pull request:** #NNNN

## Summary

`Right(completion)` from any `LLMClient` should mean one thing: the provider said the model's turn ended, and
`completion` says how. Today it does not. A tool call whose arguments were cut comes back as `Right` with `{}` and
can run (run). A stream that was cut, or that carried an error, came back as `Right` from the OpenAI-compatible client
until #2550 (run). By the code, a cut stream still does from Anthropic, Gemini, Vertex AI and Ollama, and an error
event mid-stream does from Ollama (read). This RFC writes the contract
down and makes every chat provider keep it. It has five **parts**, shipped as the PRs in [Delivery](#delivery).

- **Part 1: A typed finish reason.** `Completion` gains `finishReason: FinishReason` (`Stop`, `ToolCalls`, `Length`,
  `ContentFilter`, `Other(raw)`). `StreamedChunk.finishReason` changes from `Option[String]` to
  `Option[FinishReason]`. Each client maps its provider's reason; none hardcodes one.
- **Part 2: Stream endings.** A stream is `Right` only if the provider sent its end marker and no error. #2550 did
  this for the OpenAI-compatible client; Bedrock, watsonx and Perplexity already did. The rest follow.
- **Part 3: Tool call arguments.** Arguments that are not a complete JSON object are a `Left`, never `{}`, never
  dropped. A reply that ended on `Length` with a tool call in it is a `Left`.
- **Part 4: Retries.** A layer that retries `streamComplete` retries only while no chunk has reached the caller.
- **Part 5: A conformance suite** in `llm4s-provider-testkit` that every chat provider module must pass: clean end,
  cut by limit, stream cut short, error event, cut tool call.

Error endings stay `Left`, as they are today where they are detected. `FinishReason` has no `Error` case.

## Motivation

Evidence is labelled: **(run)** reproduced on main with a local HTTP server replaying the bytes described;
**(live)** captured from a real provider and replayed through the llm4s clients; **(read)** from reading the code on
main, not run; **(bytecode)** from the SDK jar the build resolves, not run.

**A caller can't tell a finished answer from a cut one, and a cut tool call can run.** Reproduced on main on
2026-11-05 (run). The first three rows are tests in `OpenAICompatibleStreamEndingSpec` (#2550) and the last in
`ReliableClientStreamReplaySpec` (#2562, open); the `complete` and agent rows use the inputs shown and become tests in PR 3:

| Input from provider | Expected | Got |
|---|---|---|
| Stream ends, no `finish_reason`, no `[DONE]` | `Left` | `Right`, content "The answer is" |
| Stream cut after the tool name, before the arguments | `Left` | `Right`, `purge_files({})` |
| Stream carries an `{"error":...}` event | `Left` | `Right`, content "par" |
| `complete` with arguments `{"older_than_days": 3` and `finish_reason: length` | not `{}` | `purge_files({})` |
| An agent receives that `{}` call for a tool whose parameters are all optional | tool not run | tool ran once, run `Completed` |
| `ReliableClient` retries after a partial stream | caller sees "Hello" | caller saw "HelHello", completion says "Hello" |

The first three rows are fixed for the OpenAI-compatible client by #2550 (merged as a2edf440b). The last three are
not fixed anywhere. The `complete` row is the ordinary case of a model reaching `max_tokens` inside a tool call. It was
replayed from a local server; which real providers send that reply as HTTP 200 is not known yet (Groq answers
`complete` with HTTP 400 for it, see below).

**Real providers send these replies.** Captured from Groq (`openai/gpt-oss-20b`) and Gemini (`gemini-3.5-flash`) and
replayed through the llm4s clients (live; captured with plain HTTP calls on 2026-11-05, so no llm4s request reached a
live server; the table describes each response, and the same request reproduces it):

| Case | Real server sends | llm4s main | after #2550 |
|---|---|---|---|
| Groq tool stream, token limit | HTTP 200, an `error` event with `status_code` 400, no `finish_reason`, no `[DONE]` | `Right`, 0 chars, no tools | `Left`, not retried |
| Groq text stream, token limit | `length`, `[DONE]` | `Right`, 0 chars | `Right`, 0 chars |
| Gemini `complete`, `MAX_TOKENS` before any text | `content` with no `parts`, 531 thought tokens | `Left(UnknownError: key not found: parts)` | not touched |
| Gemini stream, same | `MAX_TOKENS`, no text | `Right`, 0 chars | not touched |

Two things follow. A reply can honestly be `Right("")` because the model spent its whole budget thinking, and
`Completion` cannot say so. And the same Gemini situation is an `UnknownError` from `complete` and an empty success
from `streamComplete`. A caller can't write one handler for both.

**`Completion` has no finish reason.** `Completion` (`Completion.scala:27-37`) has none. Only `StreamedChunk` has one,
as a raw provider string (`Completion.scala:219`). `StreamingAccumulator` records it (`StreamingAccumulator.scala:82`)
and drops it: `toCompletion` builds the `Completion` without it and always returns `Right`
(`StreamingAccumulator.scala:138-173`). Tracing shows the gap. `TracingLLMClient` reports `finishReasons` from the
stream's chunks and `None` for every `complete` call (`TracingLLMClient.scala:42`), so the OpenTelemetry attribute
`gen_ai.response.finish_reasons` (`OpenTelemetryTracing.scala:223`) is empty for non-streamed calls.

**The repo already holds this rule in places.** Bedrock fails a stream that ended without `messageStop`, with the
comment that "a truncated tool call is worse" (`BedrockClient.scala:383-386`). watsonx fails a stream with no
`finish_reason` or an error-class one (`WatsonXClient.scala:475-490`). Perplexity fails a stream without
`response.completed` (`PerplexityClient.scala:216-217`). #2550 brought the OpenAI-compatible client in line. The
other clients, and the tool-argument path, do not hold it.

**No provider is held to any of this.** `ProviderModuleChecks.assertStreams` passes when a client returns `Right` and
delivered one chunk (`ProviderModuleChecks.scala:233-238`). Nothing checks how a stream ended, what the finish reason
was, or what a cut tool call becomes.

**Consumers waiting on a typed reason.** #2278 (Spring AI bridge) needs a finish reason in `ChatResponse`
metadata; today there is none to map from a non-streamed `Completion`.

### Where each client stands today

Read on main d928be4f4. "Ending enforced" means a stream with no end marker is a `Left`.

| Client | `complete` reads the reason | Stream reason | Ending enforced | Bad or cut tool arguments |
|---|---|---|---|---|
| `OpenAIClient` (OpenAI, Azure, Requesty; openai-java 4.69.3) | No (`OpenAIClient.scala:665-692`) | Raw string (`:364`). Fake streaming hardcodes `"stop"` (`:440`) | Not in llm4s (`:298-334`). The SDK stops at `[DONE]` and throws `SseException` on an `error` payload (bytecode). A stream that ends with neither: unverified | `complete` drops the call silently (`:733-752`). Stream: the accumulator, below |
| `OpenAICompatibleClient` (DeepSeek, Mistral, Cohere, OpenRouter, xAI, Z.ai, generic) | No (`OpenAICompatibleClient.scala:539-565`) | Raw string (`:647`) | Yes, since #2550 (`:239-243`, `:253-256`) | `complete`: `{}` through `lenientToolCalls` (`OpenAICompatibleDialect.scala:191`, `:231-239`), pinned by `OpenAICompatibleDialectToolCallsSpec.scala:26-28`. OpenRouter overrides it and fails instead (`OpenRouterClient.scala:285-286`, `ToolCallDeserializer.scala:50-53`). Stream: the accumulator |
| `AnthropicClient` (anthropic-java 2.65.0) | No, `stop_reason` is never read (`AnthropicClient.scala:725-773`) | Hardcoded `"stop"` on `message_stop` (`:485-494`); `message_delta`'s `stop_reason` is not passed on | Not in llm4s. The SDK throws `SseException` on an `error` event (bytecode). A stream that ends without `message_stop`: unverified | `complete`: `Left` on unreadable input (`:793-806`). Stream: a tool block starts as `ujson.Obj()` (`:477`), then the accumulator |
| `GeminiClient`, `VertexAIClient` | No. A candidate with no `parts` throws, giving `UnknownError` (`GeminiClient.scala:459`, `VertexAIClient.scala:401`) (live) | Read (`GeminiClient.scala:520`, `VertexAIClient.scala:455`), but a chunk with no `parts` is dropped whole by the surrounding `Try` (`GeminiClient.scala:513`, `:526`; `VertexAIClient.scala:452`, `:459`), so `MAX_TOKENS` with no text is lost (live) | No (`GeminiClient.scala:249`, `VertexAIClient.scala:238`). An unreadable chunk is skipped (`GeminiClient.scala:240`, `VertexAIClient.scala:229`) | Arguments are native JSON in the envelope; too deep is refused (#1562). A cut function call: untested (Gemini returned 429 before capture) |
| `OllamaClient` | No, `done_reason` is not read (`OllamaClient.scala:367-380`) | Made up from `done`: `"tool_calls"` or `"stop"` (`:238`, `:251`), so a `length` end reads as `"stop"` | No (`:291`). A `{"error":...}` line is not checked for (read) | `Left` for anything but a JSON object (`:534-564`) |
| `BedrockClient` | Only into the exchange log (`BedrockClient.scala:702`) | Raw `stopReason` (`:288`, `:375-378`) | Yes (`:383-386`) | `complete`: `Left` only when too deep (`:862-870`). Stream: a tool starts as `ujson.Obj()` (`:368`), then the accumulator. A `max_tokens` stop after a tool start gives `{}` (read) |
| `WatsonXClient` | Checks error-class reasons (`WatsonXClient.scala:576`, `:488-490`), does not return the reason | Normalised string (`:642-655`) | Yes (`:475-485`) | `Left` unless a JSON object; absent or blank is `{}` (`:767-777`) |
| `PerplexityClient` | Accepts `incomplete` (`PerplexityClient.scala:459`, `:505`) and does not report it | Made up from status: `tool_calls`, `length` or `stop` (`:224`) | Yes (`:216-217`) | `Left` unless a JSON object; absent or blank is `{}` (`:520-531`) |

**The shared accumulator.** Every streamed tool call goes through `StreamingToolArgumentParser.parse`
(`StreamingToolArgumentParser.scala:18-19`): empty arguments become `{}`, unparseable ones stay a raw `ujson.Str`.
So a stream cut after the tool name gives `{}` (run), whichever client produced the chunks.

**If nothing changes**, an agent on DeepSeek, Mistral, Cohere, xAI, Z.ai or a generic endpoint can run a tool with
arguments the model never finished writing, and report the run `Completed` (run). A caller on any provider can't tell
a cut answer from a whole one without parsing a provider string from a stream, and can't do it at all for `complete`.

## Detailed design

### Part 1: A typed finish reason

```scala
package org.llm4s.llmconnect.model

/** Why the model's turn ended, as the provider reported it, mapped to one vocabulary. */
@Stable
enum FinishReason derives ReadWriter {
  /** The model ended its turn: a natural stop, or a stop sequence. */
  case Stop

  /** The model asked for one or more tool calls, each with complete arguments. */
  case ToolCalls

  /** An output limit was reached: max tokens, or the context window. The content is cut short. */
  case Length

  /** The provider stopped or withheld output for safety, policy or recitation. */
  case ContentFilter

  /** A reason this client has no mapping for, kept as the provider sent it. */
  case Other(raw: String)
}
```

There is no `Error` case. An error ending is a `Left`, in every client. A `Right` with an error inside it is the
thing this RFC removes.

**`Completion`**, a growth-prone `@Stable` type, gains the field under the RFC 0002 pattern:

```scala
final case class Completion private (
  id: String,
  created: Instant,
  content: String,
  model: String,
  message: AssistantMessage,
  toolCalls: List[ToolCall],
  usage: Option[TokenUsage],
  estimatedCost: Option[Double],
  citations: List[Citation],
  finishReason: FinishReason           // new; FinishReason.Stop in apply (open question 1)
)
def withFinishReason(finishReason: FinishReason): Completion
```

The previous `apply` stays as an overload without defaults that forwards, as `CLAUDE.md` requires for a new field.

**`StreamedChunk.finishReason`** becomes `Option[FinishReason]`, with `withFinishReason(FinishReason)` and
`withFinishReason(Option[FinishReason])`. A stream and a `complete` then speak one vocabulary.
`StreamingAccumulator` keeps the last reason it saw and puts it on the `Completion` it builds.

**Mapping.** Each client maps its own provider's values, in its own module. No shared mapper trait.

| `FinishReason` | Chat completions (OpenAI, compatible, watsonx) | Anthropic `stop_reason` | Gemini `finishReason` | Bedrock `stopReason` | Ollama `done_reason` | Perplexity `status` |
|---|---|---|---|---|---|---|
| `Stop` | `stop` | `end_turn`, `stop_sequence` | `STOP` with no function call | `end_turn`, `stop_sequence` | `stop` | `completed`, no call |
| `ToolCalls` | `tool_calls`, `function_call` | `tool_use` | `STOP` with a function call | `tool_use` | `stop` with a call | `completed`, with a call |
| `Length` | `length` | `max_tokens`, `model_context_window_exceeded` | `MAX_TOKENS` | `max_tokens`, `model_context_window_exceeded` | `length` | `incomplete` |
| `ContentFilter` | `content_filter` | `refusal` | `SAFETY`, `RECITATION`, `BLOCKLIST`, `PROHIBITED_CONTENT`, `SPII` | `guardrail_intervened`, `content_filtered` | none | none |
| `Left` | `error` (and watsonx's `cancelled`, `time_limit`, as today) | an `error` event | `MALFORMED_FUNCTION_CALL` | an exception from the SDK | an `error` line | `failed` |

The vendor values in this table come from the vendors' documentation, except those the clients already read
(`WatsonXClient.scala:648-655`, Groq's `length` and Gemini's `MAX_TOKENS` (live)). Each mapping PR checks its row
against captured replies. Any other value is `Other(raw)`, so a new provider value never fails a call.

**Gemini, specifically.** A candidate with a `finishReason` and no `parts` is a reply, not a malformed one. `complete`
returns `Right` with empty content, `finishReason = Length` and the reported thought tokens, instead of
`UnknownError` (live). The stream records the reason from a chunk that has no `parts`, instead of dropping the chunk.
Both paths then give the same answer for the same reply. #2563 (open) already fixes `complete` for Gemini and Vertex AI; PR 2 builds on it.

**Where it is read.**

- `TracingLLMClient` reports the `Completion`'s reason for `complete` as well as for streams, so
  `TraceEvent.CompletionReceived.finishReasons` (`TraceEvent.scala:109`) and the OpenTelemetry attribute stop being
  empty for non-streamed calls. `finishReasons` stays `Seq[String]`, filled with the mapped name (`stop`, `length`,
  `tool_calls`, `content_filter`, or `Other`'s raw value).
- **Agent runs.** `AgentEvents.ModelCallCompleted` (`AgentEventPayloads.scala:33-41`) gains
  `finishReason: Option[FinishReason] = None`, filled from the call's `Completion`. A stored event written before the
  field existed still reads, as `None`. This follows RFC 0008's `adjustments` on the same event. The agent's own
  behaviour on a `Length` text answer does not change here (open question 6).

### Part 2: Stream endings

Rules for every `streamComplete`:

1. **The end marker is required.** The stream is `Right` only if the provider sent its end marker: `finish_reason`
   or `[DONE]` (chat completions; a server may send only one), `message_stop` (Anthropic), `messageStop` (Bedrock), a
   candidate `finishReason` (Gemini), `done: true` (Ollama), `response.completed` (Perplexity). A stream that ends
   without it is a `Left`: a retryable `NetworkError`, as #2550 does, because a reconnect can succeed. watsonx and
   Perplexity already return `ServiceError(502)` here (`WatsonXClient.scala:477-482`, `PerplexityClient.scala:216-217`);
   this RFC does not change them, and the suite accepts either.
2. **An error ends the call.** An error event, or an error-class reason, is a `Left` carrying the provider's error,
   even after chunks were delivered. Reading stops there.
3. **The reason is the provider's.** The `Completion`'s `finishReason` is the mapped provider value. A client never
   writes `"stop"` because the stream ended.
4. **Chunks already delivered stay delivered.** A `Left` after chunks does not unsend them. The caller learns the
   reply was incomplete from the `Left`.

What each client needs:

| Client | Rule 1 | Rule 2 | Rule 3 |
|---|---|---|---|
| `OpenAICompatibleClient` | Done (#2550) | Done (#2550) | PR 1 |
| `BedrockClient` | Done (`BedrockClient.scala:383-386`) | Done (`:382`) | PR 2 |
| `WatsonXClient` | Done (`WatsonXClient.scala:475-485`) | Done (`:365-414`, `event: error`) | PR 1 |
| `PerplexityClient` | Done (`PerplexityClient.scala:216-217`) | Done (`:201-205`) | PR 1 |
| `OpenAIClient` | Unverified for a stream that ends with neither `[DONE]` nor a reason. PR 4 checks it in llm4s rather than relying on the SDK | SDK throws (bytecode); covered by the suite in PR 5 | PR 1 (fake streaming stops hardcoding `"stop"`) |
| `AnthropicClient` | Missing in llm4s; PR 4 requires `message_stop` | SDK throws (bytecode); covered by the suite | PR 2 (read `message_delta.stop_reason`) |
| `GeminiClient`, `VertexAIClient` | Missing (`GeminiClient.scala:249`); PR 4 | Unverified: llm4s has no handling for an error object mid-stream. PR 4 adds it | PR 2 |
| `OllamaClient` | Missing (`OllamaClient.scala:291`); PR 4 requires `done: true` | Missing (read); PR 4 | PR 2 (read `done_reason`) |

### Part 3: Tool call arguments

Rules for `complete` and `streamComplete` alike:

1. **Never `{}` for a broken call.** A tool call whose arguments are not a complete JSON object is a `Left`, using
   the malformed-tool-call `ProcessingError` the Anthropic, Ollama, watsonx and Perplexity clients already return. It
   is never replaced by `{}`, never dropped, and never passed on as a raw string.
2. **Empty means "no arguments" only on a clean end.** Absent or blank arguments are `{}` when the reply ended with
   `ToolCalls` or `Stop`: a call to a tool with no parameters. watsonx and Perplexity already read it this way
   (`WatsonXClient.scala:770-772`, `PerplexityClient.scala:522-524`).
3. **A tool call in a `Length` reply is a `Left`.** The model was cut while writing calls. A later call may be
   missing, or a call's arguments may be a valid prefix of what it meant. Running any of them acts on intent the
   model did not finish stating.

Changes:

- `OpenAICompatibleDialect.lenientToolCalls` and `parseToolCalls` return `Result[Seq[ToolCall]]`, with a `Left` for
  unparseable or too-deep arguments. The arguments stay unparsed when too deep, so #1562's protection is kept: the
  difference is that the call fails instead of becoming `{}`.
- `OpenAIClient.extractToolCalls` returns a `Left` instead of dropping the call (`OpenAIClient.scala:733-752`). Today
  a reply whose only call was cut comes back as a text answer with no tool call, and the caller can't see why.
- `StreamingToolArgumentParser.parse` returns `Result[ujson.Value]`. `StreamingAccumulator.toCompletion`, which
  already returns `Result`, returns the `Left` for any call that fails rules 1 to 3. Every streaming client gets the
  rule from the accumulator, including Anthropic and Bedrock, whose streamed calls start as `ujson.Obj()`.

**A spec that pins today's behaviour changes.** `OpenAICompatibleDialectToolCallsSpec.scala:26-28` asserts that
`{not json` becomes `{}`, and `:31-35` and `:44-45` assert the same for arguments nested too deeply; `:37-43` accepts a 512-deep array, which rule 1
makes a `Left` (it is not an object). The reason to
change them: they encode the defect this part removes, and an agent run on 2026-11-05 ran a tool on that `{}` (run).
The depth limit itself stays tested. The new assertions are that each case is a `Left` and that nothing deeper than
512 levels is parsed. This needs the module owner's agreement before the PR.

### Part 4: Retries

**Rule.** A layer that retries `streamComplete` retries only while no chunk has reached the caller's `onChunk`.
After the first chunk, the failure is returned as is.

`LLMClientRetry.streamCompleteWithRetry` keeps this rule with its `chunkEmitted` flag
(`LLMClientRetry.scala:121-133`). `ReliableClient.streamComplete` does not: it passes the caller's `onChunk` straight
to each attempt (`ReliableClient.scala:89-97`), so a retry after a partial stream delivers the start again. On main,
the caller saw "HelHello" while the `Completion` said "Hello" (run). The fix is #2562 (open). This RFC
states the rule so that every retrying layer, now and later, is held to it.

Part 2 makes this rule matter more. Once a cut stream is a `Left(NetworkError)`, which is retryable, more streams reach
the retry path.

### Part 5: The conformance suite

`llm4s-provider-testkit` gains a check that every chat provider module runs from its `Llm4s<Name>ModuleSpec`, next
to `assertStreams`:

```scala
enum ContractCase {
  case CleanText           // Right, FinishReason.Stop, the text
  case TextCutByLimit      // Right, FinishReason.Length, the partial text
  case ToolCall            // Right, FinishReason.ToolCalls, arguments a JSON object
  case NoArgumentToolCall  // Right, arguments {}
  case ToolCallCutByLimit  // Left; no ToolCall in any Right
  case MalformedArguments  // Left
  case StreamEndsEarly     // stream only: Left
  case ErrorEvent          // stream only: Left
  case ContentFiltered     // Right, FinishReason.ContentFilter, where the provider has one
}

def assertModelCallContract(
  client: LLMClient,
  server: RecordedReplies,                 // serves the module's captured bytes for each case
  cases: Map[ContractCase, Applicability]  // Applies, or NotApplicable(reason): no case is left out silently
)(using Position): Assertion
```

Each module supplies the bytes its provider sends for each case, captured from the real service where possible, and
served by the testkit's local server (`LocalProviderTestServer` already serves chat-completions and Anthropic
shapes). The check runs `complete` and `streamComplete` for each case, and for the stream also checks that the chunks
delivered match the `Completion`. The Groq and Gemini captures behind the live evidence above are the first fixtures.

Until a case lands, every provider declares it `NotApplicable("PR n")`. A case a provider cannot produce is declared `NotApplicable` with a reason, so a gap shows in the spec instead of
being missed. Whether the SDK clients can point their SDK at the local server needs checking per client: OpenAI and
Anthropic take a base URL; Bedrock's endpoint override is unverified.

### Not changed

- **Agent statuses.** A `Length` text answer still ends an agent run `Completed`. The event carries the reason
  (Part 1); whether the run should end differently is open question 6.
- **`RequestAdjustment`** (RFC 0008). It describes the request as sent. `finishReason` describes how the reply ended.
  Neither replaces the other.
- **Usage on a failed call.** A billed call that ends in `Left` reports no usage. That is a separate problem
  (read) and not part of this contract.

## Alternatives

- **Do nothing per provider; let callers read `StreamedChunk.finishReason`.** It works only for streams, only with a
  raw string per provider, and not at all for `complete`. It also leaves the cut tool call running.
- **`finishReason: Option[String]` on `Completion`.** Smallest change. Callers would still match on provider strings
  (`"length"`, `"max_tokens"`, `"MAX_TOKENS"`), which drift and never fail a match. RFC 0008 chose an enum over a
  string for `RequestAdjustment.option` for the same reason.
- **An `Error` case in `FinishReason`.** It would let a client return `Right` for an error ending. That is the bug.
- **`Length` as a `Left` for text too.** Simpler to state ("`Right` means a natural end"), but it throws away a
  usable partial answer and a usable usage report, and Groq and Gemini send `length` and `MAX_TOKENS` as ordinary
  replies (live). A caller that wants a strict rule checks `finishReason`.
- **Drop a cut tool call and return the rest.** This is what `OpenAIClient` does today. The caller gets a text answer
  with no sign that the model asked for a tool.
- **Make a cut tool call `{}` and let the tool's schema reject it.** It fails for any tool whose parameters are all
  optional, which is the 2026-11-05 run.
- **Fix stream endings in the accumulator for everyone.** The accumulator can't know a provider's end marker, and a
  stream can carry a reason on a chunk and still be cut afterwards. Each client knows its own protocol. The
  accumulator does take the tool-argument rule (Part 3), which is protocol-free.
- **Retry with de-duplication of chunks** instead of no retry after the first chunk. Chunks of two attempts don't
  align: a model can phrase the second attempt differently. Not retrying is the only rule a caller can rely on.

## Compatibility impact

- **Modules and tiers:** `llm4s-core` (Frozen at 1.0): `Completion`, `StreamedChunk`, `StreamingAccumulator`,
  `StreamingToolArgumentParser` and `TracingLLMClient` change; `FinishReason` is new and `@Stable`, because `Completion` is `@Stable` and `stabilityTierCheck` (#1818) refuses a
  Stable signature that names an Experimental type; RFC 0008 settled its new types the same way (#2532). `Other(raw)`
  is what lets a case be added later: a new provider value maps to `Other` until it gets its own case.
  `llm4s-openai-compatible`: `OpenAICompatibleDialect.parseToolCalls` and `lenientToolCalls` change type, which
  affects anyone who wrote a dialect. `llm4s-agent`: `ModelCallCompleted` gains a defaulted field.
  `llm4s-provider-testkit`: a new check. `llm4s-testkit` (`ScriptedLLMClient.scala:177`) and `llm4s-java-api`
  (`FakeLlmClients.scala:71`) set typed reasons. Provider modules change internally. The Java facade does not expose
  a finish reason today (no reference in `modules/java-api/src/main` beyond `FakeLlmClients`), so it gains no API.
- **Binary:** there is no baseline yet (#1281), so no MiMa filter is needed.
- **Source:**
  - Code that reads `StreamedChunk.finishReason` as a `String` breaks. The samples that print it keep compiling.
  - **A silent case:** `chunk.finishReason.contains("stop")` still compiles, because `Option.contains` widens its
    argument, and is now always `false`. The migration note calls this out. Main has no such comparison outside tests
    (searched); 75 references in 32 test files need updating.
  - `StreamingToolArgumentParser.parse` and the dialect methods return `Result`.
  - A positional pattern match on `Completion` changes arity, as with any growth-pattern field.
- **Behaviour:** calls that returned `Right` can now return `Left`:
  - a stream that ends without its end marker (Anthropic, Gemini, Vertex AI, Ollama, and OpenAI if its SDK does not
    already fail it);
  - an error event or line on Gemini, Vertex AI or Ollama streams;
  - any tool call with unparseable arguments, or in a `Length` reply, on every client. On `OpenAIClient` such a call
    was dropped; on the OpenAI-compatible clients it was `{}`.

  One call that was a `Left` becomes a `Right`: Gemini and Vertex AI `complete` on a candidate with no `parts`
  (`UnknownError` today, `Right` with `Length` after). Retries change for `ReliableClient` streams: none after the
  first chunk.
- **Wire formats:** a `ModelCallCompleted` written before the change reads with `finishReason = None`. No checkpoint
  or config key changes.

## Migration

```scala
// Before: a provider string, streams only
client.streamComplete(conversation, options, chunk => chunk.finishReason.foreach(r => if (r == "length") warn()))

// After: one type, on the Completion too
client.complete(conversation, options).map { c =>
  c.finishReason match {
    case FinishReason.Length        => warn()            // content is cut short
    case FinishReason.ContentFilter => refuse()
    case FinishReason.Stop | FinishReason.ToolCalls | FinishReason.Other(_) => ()
  }
}
```

Check any comparison of a finish reason against a string literal: it compiles and is now always false.

```scala
// Before: compiles, was true on a normal end
chunk.finishReason.contains("stop")
// After
chunk.finishReason.contains(FinishReason.Stop)
```

A custom `OpenAICompatibleDialect` that overrides `parseToolCalls` returns `Result[Seq[ToolCall]]` and returns a
`Left` for arguments it can't parse, instead of `{}`.

Code that relied on a cut stream or a cut tool call coming back as `Right` gets a `Left`. For a stream it is a
retryable `NetworkError`; for a tool call it is a `ProcessingError`. Raising `maxTokens` is the usual fix for the
second.

The CHANGELOG gets **Added** (`FinishReason`, `Completion.finishReason`), **Changed** (`StreamedChunk.finishReason`,
the tool-argument rule, stream endings per client) and **Fixed** (`ReliableClient` re-delivery, Gemini `parts`)
entries, and lines under `### Breaking changes` for `StreamedChunk.finishReason`, `StreamingToolArgumentParser.parse`
and the dialect methods. `docs/reference/migration.md` gets one section. `docs/guide/writing-a-provider.md` states
the contract and the conformance check.

## Delivery

Already done or in progress, no RFC needed:

- **#2550 (merged, a2edf440b).** The OpenAI-compatible client fails a stream with no `finish_reason` or `[DONE]`, or
  with an error event.
- **`ReliableClient` re-delivery (#2562, open).** No retry after the first chunk.

Under this RFC, each PR with its own review:

1. **PR 1 (Part 1, core).** `FinishReason`, `Completion.finishReason`, typed `StreamedChunk.finishReason`, the
   accumulator carrying it, `TracingLLMClient` reading it for `complete`. Mapped for the chat-completions clients
   (OpenAI-compatible, OpenAI including fake streaming, watsonx) and Perplexity. The conformance suite lands here with
   the `CleanText`, `TextCutByLimit` and `ToolCall` cases. Typing `StreamedChunk.finishReason` touches every producer
   (Anthropic, Gemini and Vertex AI, Ollama, Bedrock, `ScriptedLLMClient`, `FakeLlmClients`), so PR 1 changes them all, but the
   clients PR 2 maps write `Other(raw)` (or `Other("")` with no raw value) on both paths until then. No release between
   PR 1 and PR 2 reports a `Stop` the provider didn't send.
2. **PR 2 (Part 1, the rest).** Anthropic (`stop_reason`, `message_delta`), Gemini and Vertex AI (the `parts` fix,
   `MAX_TOKENS`), Ollama (`done_reason`), Bedrock. Each adds its fixtures to the suite.
3. **PR 3 (Part 3).** The tool-argument rules, in the accumulator, the dialect and `OpenAIClient`. The spec change
   above, agreed first. Adds `NoArgumentToolCall`, `ToolCallCutByLimit` and `MalformedArguments` to the suite.
4. **PR 4 (Part 2).** End markers and error events for Anthropic, OpenAI, Gemini, Vertex AI and Ollama. Adds
   `StreamEndsEarly` and `ErrorEvent` to the suite.
5. **PR 5 (Part 5).** `assertStreams` is replaced by the full check in every chat provider's module spec, and
   `docs/guide/writing-a-provider.md` documents it.
6. **PR 6 (agent).** `ModelCallCompleted.finishReason`.

**Sequencing with RFC 0008.** #2532 (RFC 0008 PR 1, open) adds `Completion.adjustments` and touches `Completion.scala`; `ModelCallCompleted.adjustments` is still only proposed (RFC 0008 PR 2).
PR 1 here adds another field to the same type. Whichever lands second rebases, keeping one forwarding `apply`
overload per the growth pattern. The two fit together: RFC 0008 reports what was changed in the request, and this
RFC reports how the reply ended. The Gemini case links them. A reply that ended on `Length` with empty content after
531 thought tokens is the case where a caller wants both: `finishReason = Length` says the budget ran out, and #2115's
`Thinking.Budget` and the `MaxTokens` adjustment say what to change. RFC 0008 is not blocked by this one, nor this one
by it.

This RFC's own PR also adds its row to `docs/rfcs/README.md`.

## Open questions

1. **Default in `Completion.apply`.** `FinishReason.Stop` keeps every existing construction site compiling, but a
   client that forgets to set the reason then reports `Stop`, which the conformance suite has to catch. The other
   choice is no default, which breaks every test that builds a `Completion`. Maintainers decide.
2. **A `complete` reply with no reason.** watsonx accepts a missing `finish_reason` (`WatsonXClient.scala:576`). Should
   the client infer `ToolCalls` or `Stop` from the body, which is whole, or report `Other("")`?
3. **The error for a cut tool call.** The existing `ProcessingError`, or a new error that carries the partial
   `Completion` so a caller can log or show what arrived? A new error type is a public addition to core.
4. **Where the chat-completions mapping lives.** It is shared by `llm4s-openai-compatible`, `llm4s-openai` (which
   depends on it) and `llm4s-watsonx` (which depends only on core). In core as a wire-format helper, or a small copy
   in watsonx?
5. **Unreadable chunks.** Gemini and Vertex AI skip a data line that is not JSON (`GeminiClient.scala:240`). Is that a
   `Left` under rule 2, or a tolerated keep-alive? There is no evidence either way yet.
6. **Agent behaviour on `Length`.** Should an agent run whose last answer was cut end `Completed`, or should
   `AgentStatus` say so? This touches the agent runtime's owners and could be its own RFC.
7. **`ContentFilter` as `Right`.** The draft returns it as a `Right` with whatever content arrived. A `Left` is also
   defensible: the caller asked for an answer and got none. Maintainers decide.
8. **A Scalafix rule** in `modules/scalafix-rules` for the silent `contains("stop")` case, or the migration note only?
