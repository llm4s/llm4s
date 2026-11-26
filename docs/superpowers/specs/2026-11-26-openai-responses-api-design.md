# OpenAI Responses API in `llm4s-openai`, the default for api.openai.com, sliced

Issue #2900 (option 2 of #2886). It builds on #2944 (#2940), which makes the Chat Completions tool rule one rule by
generation; this work starts from `main` after #2944 merges. Slices 1 to 3 are merged (#2957, #2959, #2961); slice 4 is this PR.

## Intent

Tool-using agents cannot reason on current OpenAI models through llm4s. On Chat Completions a `gpt-5.6`, `gpt-6` or
`gpt-6.1` model calls a function only with `reasoning_effort: none`, so `OpenAIModelRules.forcedToolEffort` turns
reasoning off for every request with tools. Reasoning together with tools exists only on the Responses API, which is
also where OpenAI ships new features. `llm4s-openai` should speak it, and use it by default against OpenAI itself.

## Decided by the maintainer

1. **Responses is the default for api.openai.com now**, for every model, not only the restricted ones and not as a
   later flip (2026-11-26).
2. **The per-model facts come from the LiteLLM model file llm4s already bundles**, not from a list kept in
   `llm4s-openai`: its `mode` and `supported_endpoints` say which API a model is served on (2026-11-26). LiteLLM's
   routing behaviour (Chat Completions by default, Responses per request) is not followed; see Approaches.

## Proposed by the implementation plans, for the maintainer to confirm

Recorded by PR 4's plan (`docs/superpowers/plans/2026-11-26-openai-responses-api-pr4.md`). None of these is decided
by the maintainer yet.

- **P1. `keepReasoning`**: `OpenAIConfig.keepReasoning`, default `true`, section key `keepReasoning`. When `false` a
   request sends no `include`, a reply's reasoning items are not stored as opaque blocks (summary text still is), and
   stored `openai` opaque blocks are not sent back. It is the size knob and the recovery hatch.
- **P2. A rejected reasoning item is an explanatory error, with no automatic retry**: OpenAI's message, then the likely
   causes (another key or organisation, a moved model alias, an old thread) and the two remedies (`keepReasoning =
   false`, or continuing from an edited turn). A silent retry would hide that a thread's reasoning has gone and would
   double the request.
- **P3. Positional patterns on growth-safe classes are outside the source promise**: the compatibility policy says
   so, the migration guide notes each gain (`OpenAIConfig` 11 to 14 fields, `ModelMetadata` 8 to 9), and the Scalafix
   umbrellas rewrite them with `PositionalPatternsGainFields`: `Llm4sMigrateFrom_1_0` and `Llm4sMigrateFrom_0_7` both
   classes, `Llm4sMigrateFrom_0_6` and `Llm4sMigrateFrom_0_5` only `OpenAIConfig` (to its current 14 fields), since
   0.5.1's and 0.6.0's `ModelMetadata` had two fields since removed.
- **P4. Routing is data-positive**: a model the registry knows but does not list for `/v1/responses`, whether it lists
   other endpoints or none, stays on Chat Completions. Live evidence (2026-11-26, `POST /v1/responses`, `store:
   false`): `gpt-4o-search-preview`, `gpt-4o-mini-search-preview` and `gpt-5-search-api` answered 400 "not supported
   with the Responses API", and so did `gpt-audio-1.5`; `gpt-4o` answered 200 (OpenAI has since retired the two
   search-preview models: a later run on 2026-11-26 got 404 "has been deprecated"). The bundled file has 48 OpenAI chat
   entries with no `supported_endpoints`. **This narrows decision 1**: the models the registry knows but does not list
   (`gpt-4o`, `gpt-4o-mini`, `o1`, `o3-mini`, `o4-mini`, the dated preview snapshots) stay on Chat Completions until
   the data lists the endpoint or the section sets `api = "responses"`. A model id the registry does not hold (an
   undated alias such as `gpt-4o-audio-preview`, a newer snapshot) uses Responses.
- **P5. Left out of this PR, as follow-ups** (issues for the maintainer to file): the Java facade's and Spring AI's
   message views dropping thinking blocks when a caller rebuilds a conversation; `DeterministicCompressor` unsealing
   tool-call-only messages; Azure OpenAI on Responses; OpenAI's hosted tools.

## Goals

- `OpenAIClient` sends `complete` and `streamComplete` to `/v1/responses` when its section points at OpenAI.
- Reasoning and tools work together: the configured effort is sent with tools, and the reasoning a turn produced is
  carried into the next turn of the same thread.
- A caller can pin either API per section.
- No `@Stable` signature changes; no new stored-JSON shape.

## Non-goals

- Azure OpenAI and Requesty on Responses. Both stay on Chat Completions (see Routing), including the Azure
  entries the model file marks `mode: responses`, which stay unusable as they are today.
- OpenAI's hosted tools (web search, file search, code interpreter, computer use), background mode, the
  Conversations API, `previous_response_id`.
- A public `responses()` entry point. The surface stays `LLMClient`.
- Anything in `llm4s-openai-compatible` beyond the config field below. xAI's Responses API is a separate question.

## Anti-goals

- **Server-side conversation state.** Every request is `store: false` and carries its whole history. A thread's
  history is owned by llm4s (checkpoints, replay, pruning, handoffs); a `previous_response_id` would make a thread
  unreplayable from its checkpoint and would expire on OpenAI's schedule.
- **A second public client class.** `OpenAIClient` is `@Stable` and what `OpenAIClient.apply` returns.
- **A thread that alternates APIs.** The API is fixed per client, never chosen per request.

## Approaches considered

1. **Per request**: Responses only when a restricted model is sent tools together with an effort. This is what
   LiteLLM's `completion()` does (it also routes by an `openai/responses/` prefix, a per-model `mode`, and a global
   `route_all_chat_openai_to_responses` flag that Azure is exempt from). It changes least, but a thread then
   alternates APIs turn by turn, and the sealed reasoning of a Responses turn cannot be sent on a Chat Completions
   turn, so the thread loses it on each switch.
2. **Per model**: the restricted generations always on Responses, everything else on Chat Completions, with a
   config override. No wire change for other models; two code paths stay in everyday use indefinitely.
3. **Responses for everything that points at OpenAI, Chat Completions elsewhere, with a config override.**
   **Picked** (decision 1): it is where OpenAI directs new work, it leaves one everyday path for OpenAI itself, and
   the cost is a wire change for existing OpenAI users in 1.1 plus two options Responses does not take.

## Routing

`OpenAIConfig` gains `api: Option[OpenAIApi]`; `None` means "decide from where the config points". The choice is
`OpenAIApiRouting.resolve`, made once when the client is built and logged at INFO with the reason. First match wins:

| # | When | API |
|---|---|---|
| 1 | The config is not an `OpenAIConfig` (Azure's `AzureConfig`) | Chat Completions |
| 2 | `api` is set | as set |
| 3 | The client's provider is not `openai` (Requesty), or the config's own provider id is not `openai` (a hand-built config pointed at OpenRouter) | Chat Completions |
| 4 | The registry marks the model `mode: responses` (the pro, codex, deep-research and healthcare models, each listing only `/v1/responses`) | Responses, wherever the base URL points |
| 5 | The base URL is not OpenAI's own: `https`, host `api.openai.com` (any case), port absent or 443, no user info | Chat Completions |
| 6 | The registry knows the model and its entry does not list `/v1/responses`, whether it lists other endpoints or none | Chat Completions |
| 7 | Otherwise: the entry lists `/v1/responses`, or the registry does not know the model | Responses |

- The per-model facts come from the model registry (decision 2), looked up as `OpenAIReasoning.openAIEntry` (an
  entry whose `litellm_provider` is `openai`, `azure` or `text-completion-openai`); a lookup that fails counts as a model the registry does not know. There is no model
  list in `llm4s-openai`. A registry of the user's own (`llm4s.modelRegistry`) that lists `/v1/responses` for a
  model opts it in.
- Rule 6 is data-positive (P4): 48 older chat entries list no endpoints, and some of them (the search
  previews) are refused on `/v1/responses`.
- OpenAI's regional hosts (`us.`, `eu.`, `ae.api.openai.com`) are not treated as the default base URL, so they stay
  on Chat Completions unless `api` is set.
- **Verified live on 2026-11-26** (slice 4, `OpenAISmokeSpec`, request bodies read from a `ProviderExchangeSink`): with no `api`,
  `gpt-6.1-terra` went to Responses (`input` in the body, reasoning tokens reported, no adjustment); `gpt-6.1-terra` with
  `api = "chat-completions"` went to Chat Completions (`messages`, an `Adjusted` adjustment, 0 reasoning tokens);
  `gpt-4o-mini`, no `api`, went to Chat Completions. `gpt-4o-search-preview` and `gpt-4o-mini-search-preview`
  could not be tried: OpenAI answered 404 "has been deprecated" for both, so whether a search-preview model still works
  on the default client was not shown, and neither request body was recorded (the failed exchange carried none).
- With `api` set explicitly the request goes where it was told and OpenAI's error is reported. An explicit `api` on
  an `OpenAIConfig` is honoured by whichever client is built from it, a Requesty-labelled one included: a caller who
  builds the config in code and sets it has asked for it. Only the `openai` descriptor declares the section key.
- Section key: `api = "responses"` or `api = "chat-completions"`, a declared extra of the `openai` descriptor
  (`ProviderConfigKey.optional`), never a field of `NamedProviderConfig`. Any other value fails the section load,
  naming both values.

On Responses no Chat Completions tool rule applies: `forcedToolEffort` is skipped and the effort is sent as asked.
On Chat Completions the rule applies as #2944 leaves it.

## API surface (all in frozen modules)

- `llm4s-core`, `org.llm4s.model`: `ModelMode.Responses` (name `responses`; today that value parses to
  `ModelMode.Unknown`). `ModelMode` is listed under the compatibility policy's "Sealed types that may gain a case".
  `ModelMetadata` gains `supportedEndpoints: Seq[String]`, read from `supported_endpoints`, empty when the entry
  lists none, with `withSupportedEndpoints` and the previous `apply` kept as a forwarding overload. Entries of other
  providers with `mode: responses` (Azure, Perplexity and others) become `ModelMode.Responses` too; the builder
  checks nothing in the repo relied on their being `Unknown`.

- `org.llm4s.llmconnect.config.OpenAIApi` in `llm4s-openai-compatible`, beside `OpenAIConfig`: a `@Stable` `enum`
  with `ChatCompletions` and `Responses`. *My call*: `@Stable` rather than `@Experimental`, since a config value
  users write into `application.conf` should not change in a minor release.
- `org.llm4s.llmconnect.config.OpenAIReasoningSummary`, beside it: a `@Stable` `enum` with `Auto`, `Concise` and
  `Detailed` (see Reasoning across turns).
- `OpenAIConfig`: two fields, `api: Option[OpenAIApi]` and `reasoningSummary: Option[OpenAIReasoningSummary]`,
  each with a `with*` setter taking the value or an `Option`, and the previous `apply` and `fromValues` kept as
  forwarding overloads (CLAUDE.md, growth-prone data types). **Both fields are added in slice 1**, so there is one
  round of kept overloads, although `reasoningSummary` is first read in slice 2. MiMa against 1.0.0 must pass with
  no filter.
- `OpenAIConfig.ApiSelectionKey` / `ApiSelectionConfigKey` and `ReasoningSummaryKey` / `ReasoningSummaryConfigKey`, as
  `OrganizationKey` / `OrganizationConfigKey` are; both are extras of the `openai` descriptor only.
- Nothing else public. Core changes nothing for reasoning: `ThinkingBlock.Opaque` and `ThinkingReplay` already
  exist.

## Structure

`OpenAIClient.scala` is 1,577 lines and is not grown. `OpenAIClient` keeps its lifecycle, metrics, adjustment log,
exchange logging and error mapping, and branches once in `complete` and `streamComplete`. The Responses path is
`private[provider]` code in new files:

- `OpenAIClientTransport` gains `createResponse(ResponseCreateParams): Response` and
  `createResponseStream(ResponseCreateParams): StreamResponse[ResponseStreamEvent]`, implemented by the same SDK
  client (`client.responses()`), so timeouts, network settings, workload identity and proxying are shared.
- `OpenAIResponsesRequest`: conversation and options to `ResponseCreateParams`.
- `OpenAIResponsesReply`: `Response` to `Completion`.
- `OpenAIResponsesStream`: stream events to `StreamedChunk`s and the final `Completion`.

How these are factored beyond that, and what is shared with the Chat Completions path, is the builder's.

## Request mapping

| llm4s | Responses |
|---|---|
| `SystemMessage` | an input message with role `system`, in its position (not `instructions`: positions are part of what `ThinkingReplay` fingerprints, and the Chat Completions path sends them inline too) |
| `UserMessage` parts | `input_text`; `input_image` (URL or `data:` URL); `input_file` (inline PDF, or `file_id` for this provider's own `ProviderFile`). Anything else: `UnsupportedContentPartError`, as now |
| `AssistantMessage` | an assistant message with `output_text` when it has text, then one `function_call` per tool call (`call_id` = `ToolCall.id`), with each reasoning item placed immediately before the item it preceded in the reply (below) |
| `ToolMessage` | `function_call_output` with that `call_id` |
| `options.tools` | function tools in the Responses (flat) shape: `name`, `description`, `parameters`, `strict` |
| `responseFormat` | `text.format`: `json_object`, or `json_schema` with `name`, `schema`, `strict` |
| `maxTokens` | `max_output_tokens` |
| `temperature`, `topP` | sent when set, except to a reasoning model that restricts sampling, as now |
| `presencePenalty`, `frequencyPenalty` | not parameters of this API: `RequestAdjustment.Dropped`, each when the caller set it |
| `reasoning` / `thinking` | `reasoning.effort`, from the existing `OpenAIReasoning.plan` |
| service tier | `service_tier`, from the existing `OpenAIServiceTier.plan` |
| always | `store: false` |

Message, `function_call` and `function_call_output` input items are sent without item ids (`store: false` gives
OpenAI nothing to resolve them against, and llm4s does not store them). A replayed reasoning item is sent exactly
as OpenAI returned it, its `id` included.
`OpenAIModelRules.transformer` still runs first. Whether a model needs `requiresFakeStreaming` on Responses is
checked per model by the builder rather than inherited from the Chat Completions rule.

## Reasoning across turns

- A request to a reasoning model (as `sendsEffort` / the registry decide today) sets
  `include: ["reasoning.encrypted_content"]`.
- Each `reasoning` output item becomes one `ThinkingBlock.Opaque(ProviderId("openai"), data)`, in output order.
  `data` is a JSON envelope of llm4s's own, as Gemini's opaque blocks are: `item`, the reasoning item as OpenAI
  returned it (`id`, `encrypted_content`, `summary`), and `before`, the `call_id` of the `function_call` that
  followed it in the output, absent when the next item was the message text or nothing. A reply can interleave
  reasoning and calls (reasoning, call, reasoning, call), and OpenAI checks a reasoning item against the item after
  it, so replay puts each item back before its anchor; one whose anchor call is no longer in the message is not
  sent. The envelope is inside the opaque string, so the stored shape is still the existing one. When a reasoning item
  carries summary text, a `ThinkingBlock.Text` holds it too, so `Completion.thinking` reports it and it survives
  unsealing; the summary is not sent back twice (it travels inside the opaque item).
- The message is bound with `ThinkingReplay.bindOrigin` and replayed with `replayableOrigin`: OpenAI's encrypted
  reasoning is tied to the provider and model, not to a signature over the prefix. *My call*; if live testing shows
  OpenAI rejects a reasoning item after an earlier turn was edited, switch to `bind` / `replayable`.
- A message whose thinking is unsealed (edited content or tool calls, another provider or model, no binding) is
  sent without its reasoning items. So is every message on the Chat Completions path, which has nowhere to put
  them.
- **Verified live on 2026-11-26** (`gpt-6.1-terra`, `gpt-5.6-luna`, `store: false`): OpenAI accepts a `function_call`
  input item sent back without the reasoning item that preceded it, and returns the final answer. Slice 1's tool loop
  needs no decision; unsealed turns can be sent as items. One of four runs of the luna case failed before request
  acceptance could be told from the model not calling the tool; the case now cancels in that situation.
- **Verified live on 2026-11-26** (slice 2; `gpt-6.1-terra`, `gpt-5.6-luna`, `store: false`): a reply with one tool call
  and one reasoning item, sent back with its `id` and `encrypted_content`, was accepted on both models, and so was the
  same turn edited so its reasoning was dropped. An interleaved reply (two calls, each with its own reasoning) did not
  occur in these runs; it is covered by wire tests only. `reasoningSummary = auto` returned summary text as thinking on both models. One
  `gpt-5.6-luna` run returned an accepted but empty turn 2 (no text, no tool call) after its reasoning was replayed; it
  was not reproduced, and the live turn-2 assertions check acceptance only.
- **Measured live on 2026-11-26** (slice 4, default client, effort low, `keepReasoning` true against false, five
  counted loops per arm, every turn 2 accepted; "empty" is no text and no tool call). Run 1 `gpt-5.6-luna`: replay 3
  empty of 5, no-replay 3 of 5. Run 2 `gpt-5.6-luna`: replay 1 of 5, no-replay 1 of 5. Run 1 and run 2 `gpt-6.1-terra`:
  replay 0 of 5 and 0 of 5, no-replay 0 of 5 and 0 of 5. Most non-empty `gpt-5.6-luna` replies were the one character
  `0`, and on `gpt-6.1-terra` the replay arm answered in a sentence in 9 of 10 loops against `0` in all 10 no-replay loops.
- Reasoning summaries are opt-in: section key `reasoningSummary = "auto" | "concise" | "detailed"`
  (`OpenAIConfig.reasoningSummary`), unset by default, sent as `reasoning.summary`. *My call*: OpenAI answers a summary request from an unverified organisation with a 400, which would
  break every reasoning request for those accounts if llm4s asked by default. Without it, a turn has reasoning
  tokens and opaque blocks but no thinking text, as on Chat Completions today.

Stored JSON: an assistant message gains `opaque` thinking blocks with provider `openai`, a shape that already
exists and already round-trips. A test pins that a stored message carrying one reads back and is replayed.

## Reply mapping

- Text: the `output_text` parts of the output messages, concatenated. A `refusal` part's text is delivered as
  content too, with finish reason `Stop`, and streamed (`response.refusal.delta`) as a `contentDelta`. *My call*:
  a refusal is the model's answer, not a filter, so it is not a `ContentFilteredError`; the Chat Completions path
  has no refusal handling to match (it drops `message.refusal`), and that is left as it is.
- Tool calls: each `function_call` item, `ToolCall.id` = `call_id`, arguments parsed as today's
  `extractToolCalls` does (same failure for unparsable arguments).
- Finish reason: `completed` with tool calls is `ToolCalls`, otherwise `Stop`; `incomplete` with
  `max_output_tokens` is `Length`; `incomplete` with `content_filter` is a `ContentFilteredError` carrying the
  usage, as `filteredReply` does; `failed` maps its `error` through the existing error mapping.
- Usage: `input_tokens` and `output_tokens`; `output_tokens_details.reasoning_tokens` becomes `thinkingTokens`
  and is taken out of `completionTokens`; `input_tokens_details.cached_tokens` as the cached count; `totalTokens`
  as reported.
- Citations: `url_citation` annotations on `output_text`, to the existing `Citation`.
- Adjustments, service tier and estimated cost: as on the Chat Completions path.

## Streaming

- `response.output_text.delta` is a `contentDelta`.
- `response.output_item.added` for a `function_call` starts a tool call (id `call_id`, name);
  `response.function_call_arguments.delta` continues it. Every delta chunk carries its call's id
  (`StreamingAccumulator` keys calls by id; the events identify the call by `item_id` / `output_index`, so the
  stream keeps that map).
- `response.reasoning_summary_text.delta` is a `thinkingDelta`.
- `response.completed` and `response.incomplete` carry the full `Response`: the final `Completion` is built from
  it by `OpenAIResponsesReply`, so streamed and unstreamed replies cannot differ in usage, reasoning items or
  finish reason. `response.failed` and `error` end the stream with the mapped error; text already delivered stays
  delivered.
- A stream that ends with no terminal event is `OpenAIClient.unfinished`, as now.
- Verified live on 2026-11-26 against OpenAI: on `gpt-4o-mini` a plain-text stream arrived as many content chunks
  whose concatenation equals the final content, the finish reason on the last chunk. On `gpt-6.1-terra` and
  `gpt-5.6-luna` at effort low a streamed tool call carried its call's id on every delta chunk, the ids equal to the
  completion's, with reasoning tokens and a sealed reasoning block; a streamed tool loop on both sent that block back
  and the second streamed turn was accepted; on `gpt-5.6-luna` that reply was empty (one chunk, no content, finish `Stop`), the second such observation after a replay (the first is recorded under "Reasoning across turns"), which PR 4's live comparison measures. On `gpt-5.6-luna` with `reasoningSummary = auto`, summary text arrived as
  thinking deltas.

## Errors, logging, metrics

Unchanged in kind: `mapError`, `recordExchange` (the body is the Responses request and reply), `completeWithMetrics`.
The exchange log's format does not change; its content for OpenAI does, which the migration note says.

The code of a `failed` reply's `error`, or of an `error` event (also one the SDK throws as an `SseException`), is classified:
- request-fault codes (`invalid_prompt`, the image codes, `data_residency_mismatch`) are a non-retryable 400-class error;
- policy refusals (`bio_policy`, `misalignment_policy_violation`, `image_content_policy_violation`) are a `ContentFilteredError`, carrying the reply's usage when it reports one;
- `rate_limit_exceeded` is a `RateLimitError`;
- service codes are a retryable 502.

A 200 reply that maps to an error keeps its request and response bodies in the exchange log on the Responses path.

## Testing

- **Wire**: a new `OpenAIResponsesWireSpec` over a fake `OpenAIClientTransport`, asserting the serialised request
  and the mapped reply for each row above. *My call*: a new spec rather than `OpenAIClientWireSpec`, which the
  issue names but which is already 545 lines of Chat Completions cases.
- **Streaming**, **reasoning replay** (sealed, unsealed, other model, stored-and-read-back) and **routing** (the
  table above, including an invalid `api`) each have a spec.
- `Llm4sOpenAIModuleSpec` runs `assertModelCallContract` for the `openai` descriptor on both APIs.
- Existing Chat Completions specs keep testing Chat Completions: where they build a client on the default base
  URL they pin `api`, rather than being rewritten.
- **Live** (`modules/it`, `@Cloud`, `OpenAISmokeSpec`): on `gpt-6.1-terra` and `gpt-5.6-luna`, a tool call at
  effort `low` with reasoning tokens reported, sync and streamed; a two-turn tool loop that sends the encrypted
  reasoning back; the unsealed case from "Reasoning across turns"; one non-reasoning model.
- `DocumentedProviderConfigSpec` loads the documented `api`, `reasoningSummary` and `keepReasoning` keys.

## Docs and release notes

- `docs/guide/providers.md` and `docs/getting-started/configuration.md`: which API a section uses, the three keys,
  what differs (penalties, reasoning with tools, encrypted reasoning in stored threads).
- CHANGELOG, Providers: one **Changed** entry. No `@Stable` API breaks, but the wire and a positional pattern
  change, so, as 1.0.0 listed its default changes, *Breaking changes* gets one "Behaviour, no signature" line, and
  `docs/reference/migration.md` gets a note: what changes on the wire for an `openai` section on upgrade, and
  `api = "chat-completions"` to keep 1.0.x behaviour.
- The `reference.conf` comment block in `llm4s-openai` shows the key.
- Released as 1.1.0.

## Slices

Four PRs, each green on its own, the default flipped last so `main` is releasable throughout.

1. **Transport, routing and the unstreamed path.** `OpenAIApi`, both config fields and keys, the transport
   methods, request and reply mapping including tools, adjustments and citations, and the first live verification
   above. Responses only when `api = "responses"`.
2. **Reasoning across turns.** `include`, opaque blocks with their anchors, binding and replay, sending
   `reasoningSummary`, the second live verification.
3. **Streaming.**
4. **The default.** `ModelMode.Responses` and `ModelMetadata.supportedEndpoints` in core, routing by base URL and
   by the registry, the docs, CHANGELOG and migration note, the full
   live suite.

## Carried into later slices

Open after slice 4:
- The follow-ups of P5: the Java facade (and so the Kotlin API, which wraps it) and Spring AI message views drop opaque blocks when a caller
  rebuilds a conversation; `DeterministicCompressor`'s `withContent(f(content))` turns a tool-call-only message's
  `None` into `Some("")` and so unseals it; Azure OpenAI on Responses; hosted tools, which would need a reasoning item
  anchored to a non-function item.
- Verify live what an in-stream error looks like on the wire (flat or nested): one probe (an image part with an unreachable URL, streamed on `gpt-4.1-mini`) was refused with an HTTP-level `Invalid request: Failed to download file.` before any event, 0 chunks, so it did not answer this.
- `response.output_item.added` already carrying arguments would double them in a consumer that accumulates chunks.
- Whether the models marked `requiresFakeStreaming` (a Chat Completions rule) can stream on Responses: not examined in slice 4's live run.
- A failed reply (HTTP 200, `status: failed`) with `invalid_encrypted_content` gets no remedy hint; only an HTTP error
  or an in-stream error event does.
- Chat Completions' fake-streaming route lets a throwing `onChunk` escape (pre-existing).

## Left to the builder

File and method factoring inside `org.llm4s.llmconnect.provider`; what the two paths share; test fixtures; wording of errors, log lines and docs.
