# OpenAI Responses API in `llm4s-openai`, the default for api.openai.com, sliced

Issue #2900 (option 2 of #2886). It builds on #2944 (#2940), which makes the Chat Completions tool rule one rule by
generation; this work starts from `main` after #2944 merges. Nothing here is built yet.

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

`OpenAIConfig` gains `api: Option[OpenAIApi]`; `None` means "decide from where the config points".

| Client | `api` unset | `api` set |
|---|---|---|
| `openai`, base URL is `OpenAIProvider.DEFAULT_BASE_URL` | Responses | as set |
| `openai`, any other base URL (a gateway, a proxy, a mock) | Chat Completions | as set |
| `requesty` | Chat Completions | not a key of its section |
| `azure` (`AzureConfig`) | Chat Completions | no such field |

- The comparison is on the normalised base URL (trailing slash, case of the host).
- With `api` unset, two facts from the model registry (decision 2) adjust the table for an `openai` client, looked
  up as `registryService.lookup(ProviderId("openai"), model)`:
  - **`mode: responses`** (28 OpenAI entries in the bundled file: the pro, codex, deep-research and healthcare
    models, each listing only `/v1/responses`): Responses, whatever the base URL. Chat Completions cannot serve
    these, so a gateway that fronts one must speak Responses.
  - **`supported_endpoints` present and without `/v1/responses`** (one chat entry today, `gpt-audio-1.5`): Chat
    Completions, even on the default base URL.
  - A model the registry does not know, or an entry that lists no endpoints (48 older chat entries), follows the
    table: Responses on the default base URL.
- With `api` set explicitly the request goes where it was told and OpenAI's error is reported.
- There is no model list in `llm4s-openai` for this. A registry override (`Llm4sConfig.modelRegistryService()`'s
  user file) corrects a wrong or missing entry, as it does for capabilities.
- Section key: `api = "responses"` or `api = "chat-completions"`, a declared extra of the `openai` descriptor
  (`ProviderConfigKey.optional`), never a field of `NamedProviderConfig`. Any other value fails the section load,
  naming both values.
- The choice is resolved once, when the client is built, and logged at INFO with the reason.
- An explicit `api` on an `OpenAIConfig` is honoured by whichever client is built from it, a Requesty-labelled
  one included: a caller who builds the config in code and sets it has asked for it. Only the unset default looks
  at the provider id and base URL, and it picks Responses only for provider `openai`. *My call.*

On Responses no Chat Completions tool rule applies: `forcedToolEffort` is skipped and the effort is sent as asked,
and if `checkTools` / `ToolCalls.ResponsesApiOnly` still exist when this is built (#2944 deletes them) they are
skipped too. On Chat Completions the rule applies as #2944 leaves it.

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
- `OpenAIConfig.ApiKey` / `ApiConfigKey` and `ReasoningSummaryKey` / `ReasoningSummaryConfigKey`, as
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
- **To verify live in slice 1, before building on it**: that with `store: false` a `function_call` input item is
  accepted without the reasoning item that preceded it. Slice 1 ships tools without reasoning items, so every
  second turn of its tool loop on a reasoning model is this case. If OpenAI refuses, an unsealed turn cannot be
  sent as items, and the design needs a decision before slice 1 goes further (the candidates: send such turns as
  plain assistant text, or fail the request with an error that says why).
- **To verify live in slice 2**: that an interleaved reply (two tool calls, each with its own reasoning item) is
  accepted back in its original order, and that a reasoning item is accepted with its `id` under `store: false`.
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

## Errors, logging, metrics

Unchanged in kind: `mapError`, `recordExchange` (the body is the Responses request and reply), `completeWithMetrics`.
The exchange log's format does not change; its content for OpenAI does, which the migration note says.

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
- `DocumentedProviderConfigSpec` loads the documented `api` and `reasoningSummary` keys.

## Docs and release notes

- `docs/guide/providers.md` and `docs/getting-started/configuration.md`: which API a section uses, the two keys,
  what differs (penalties, reasoning with tools, encrypted reasoning in stored threads).
- CHANGELOG, Providers: one **Changed** entry. It is not an API break, so no *Breaking changes* line, but
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

## Left to the builder

File and method factoring inside `org.llm4s.llmconnect.provider`; what the two paths share; test fixtures; wording of errors, log lines and docs.
