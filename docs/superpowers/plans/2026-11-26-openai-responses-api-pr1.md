# OpenAI Responses API, PR 1 (transport, config and the unstreamed path) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An `openai` section with `api = "responses"` sends `complete` (and `streamComplete`, as one unstreamed call delivered in chunks) to `/v1/responses`, with tools, structured output, usage and citations mapped.

**Architecture:** `OpenAIClient` keeps its lifecycle, metrics, adjustments, exchange logging and error mapping and branches once per call on an `OpenAIApi` resolved when it is built. The Responses path is `private[provider]` code in two new files (request mapping, reply mapping) over two new methods on the existing `OpenAIClientTransport`. Nothing changes for a section that does not set `api`.

**Tech Stack:** Scala 3.7.1, sbt, ScalaTest, `com.openai:openai-java` 4.69.3 (`com.openai.models.responses.*`), `LocalProviderTestServer` from `llm4s-provider-testkit`.

**Spec:** `docs/superpowers/specs/2026-11-26-openai-responses-api-design.md`, slice 1 of its "Slices". Read "Routing", "API surface", "Request mapping" and "Reply mapping" before any task. Slices 2 to 4 (reasoning items, native streaming, the default) each get their own plan.

## Global Constraints

- Start from `main` after PR #2944 has merged, and rebase `feat/2900-openai-responses-api` onto it first. If `OpenAIModelRules.checkTools` still exists when you start, stop and say so.
- `llm4s-openai`, `llm4s-openai-compatible` and `llm4s-core` are frozen: `sbt mimaReportBinaryIssues` must pass against 1.0.0 with no filter, and `sbt stabilityTierCheck` must pass.
- New public types are exactly `OpenAIApi` and `OpenAIReasoningSummary` (both `@Stable`), plus the members of `OpenAIConfig` named in Task 1. Everything else is `private[provider]` or `private[llm4s]`.
- Every request on the Responses path sends `store: false`. No `previous_response_id`, no `include`, no item ids on input items.
- With `api` unset, behaviour is byte-for-byte what it is today. In this PR Responses is used only when `OpenAIConfig.api` is `Some(OpenAIApi.Responses)`.
- No `try`/`catch`, no exceptions for control flow: `Result[A]`. No infix method calls. Durations are `FiniteDuration`.
- `OpenAIClient.scala` gains only the branch, the delegation and the transport methods. Mapping code goes in the new files.
- Every commit: `git commit -s`, message ending `Co-Authored-By: Claude Fable 5.2 <noreply@anthropic.com>`.
- Filter sbt output: `2>&1 | grep -E '\[error\]|\*\*\* FAILED|Tests: |Failed tests'`.

## Review Focus

Each line has its test in the task named.

1. **A reasoning model's reply contains `reasoning` output items even though this PR asks for none.** They are ignored: the completion has the text and tool calls, no thinking blocks, and no failure. (Task 4)
2. **An output item of a type this client does not map** (`web_search_call`, or one the SDK does not know). Ignored, never a failure. (Task 4)
3. **A gateway that does not serve `/responses` answers 404.** The error is the mapped service error naming the provider, as a 404 on Chat Completions is; it is not swallowed or retried on the other API. (Task 2)
4. **Two tool calls in one reply, and a history that contains them.** Both come back in output order, and the next request sends both `function_call` items before both `function_call_output` items, in the conversation's order. (Tasks 3 and 4)
5. **`function_call.arguments` that is not JSON.** The same failure `extractToolCalls` gives on Chat Completions today. (Task 4)

---

### Task 1: `OpenAIApi`, `OpenAIReasoningSummary` and the two `OpenAIConfig` fields

**Files:**
- Create: `modules/openai-compatible/src/main/scala/org/llm4s/llmconnect/config/OpenAIApi.scala` (both enums)
- Modify: `modules/openai-compatible/src/main/scala/org/llm4s/llmconnect/config/OpenAIConfig.scala`
- Test: `modules/openai-compatible/src/test/scala/org/llm4s/llmconnect/config/OpenAIConfigSpec.scala`

**Interfaces:**
- Produces:

```scala
@Stable enum OpenAIApi:
  case ChatCompletions, Responses
object OpenAIApi:
  private[llm4s] def parse(value: String): Result[OpenAIApi]   // "chat-completions" | "responses", exact, else ConfigurationError
  private[llm4s] def configValue(api: OpenAIApi): String

@Stable enum OpenAIReasoningSummary:
  case Auto, Concise, Detailed
object OpenAIReasoningSummary:
  private[llm4s] def parse(value: String): Result[OpenAIReasoningSummary]   // "auto" | "concise" | "detailed"

// OpenAIConfig: two new constructor fields, after apiKeyProvider
api: Option[OpenAIApi]
reasoningSummary: Option[OpenAIReasoningSummary]
def withApi(api: OpenAIApi): OpenAIConfig
def withApi(api: Option[OpenAIApi]): OpenAIConfig
def withReasoningSummary(summary: OpenAIReasoningSummary): OpenAIConfig
def withReasoningSummary(summary: Option[OpenAIReasoningSummary]): OpenAIConfig

// object OpenAIConfig
val ApiKey: String = "api"
val ApiConfigKey: ProviderConfigKey               // optional
val ReasoningSummaryKey: String = "reasoningSummary"
val ReasoningSummaryConfigKey: ProviderConfigKey  // optional
```

Neither `apply` nor `fromValues` changes signature: like `timeouts`, `network` and `apiKeyProvider`, the new fields are set only through their setters, and both factories pass `None`. `reasoningSummary` is stored and not read by anything in this PR.

- [ ] **Step 1: Write the failing tests** in `OpenAIConfigSpec`:

```scala
"OpenAIConfig" should "have no api and no reasoning summary unless set" in {
  val config = OpenAIConfig(Secret("sk-test"), ModelName("gpt-4o"))
  config.api shouldBe None
  config.reasoningSummary shouldBe None
}

it should "carry the api and the reasoning summary it is given, and clear them" in {
  val config = OpenAIConfig(Secret("sk-test"), ModelName("gpt-4o"))
    .withApi(OpenAIApi.Responses)
    .withReasoningSummary(OpenAIReasoningSummary.Auto)
  config.api shouldBe Some(OpenAIApi.Responses)
  config.reasoningSummary shouldBe Some(OpenAIReasoningSummary.Auto)
  config.withApi(None).api shouldBe None
  config.toString should include("api=Some(Responses)")
}

"OpenAIApi.parse" should "read the two config values and refuse anything else, naming both" in {
  OpenAIApi.parse("responses") shouldBe Right(OpenAIApi.Responses)
  OpenAIApi.parse("chat-completions") shouldBe Right(OpenAIApi.ChatCompletions)
  val error = OpenAIApi.parse("Responses API").left.value
  error.message should include("responses")
  error.message should include("chat-completions")
}

"OpenAIReasoningSummary.parse" should "read auto, concise and detailed and refuse anything else" in {
  OpenAIReasoningSummary.parse("auto") shouldBe Right(OpenAIReasoningSummary.Auto)
  OpenAIReasoningSummary.parse("concise") shouldBe Right(OpenAIReasoningSummary.Concise)
  OpenAIReasoningSummary.parse("detailed") shouldBe Right(OpenAIReasoningSummary.Detailed)
  OpenAIReasoningSummary.parse("full").isLeft shouldBe true
}
```

- [ ] **Step 2: Run and see them fail.** `sbt "openaiCompatible/testOnly *OpenAIConfigSpec"` (the project id is whatever `build.sbt` gives `modules/openai-compatible`; `grep -n 'openai-compatible' build.sbt`). Expected: does not compile, `OpenAIApi` not found.
- [ ] **Step 3: Implement** the enums, fields, setters, keys, and `api` / `reasoningSummary` in `toString` only when set (as `apiKeyProvider` is).
- [ ] **Step 4: Run** the spec again, then `sbt "openaiCompatible/test" stabilityTierCheck mimaReportBinaryIssues`. Expected: all pass. If `OpenAICompatibleGrowthSafeConfigsSpec` enumerates `OpenAIConfig`'s fields, add the two there.
- [ ] **Step 5: Commit** `feat(openai-compatible): OpenAIConfig carries the API a client speaks and a reasoning summary setting (#2900)`.

### Task 2: The section keys, the transport and a plain-text round trip

**Files:**
- Modify: `modules/openai/src/main/scala/org/llm4s/llmconnect/provider/OpenAIProvider.scala` (`configSpec`, `buildConfig`)
- Modify: `modules/openai/src/main/scala/org/llm4s/llmconnect/provider/OpenAIClient.scala` (transport trait and its `sdk` implementation, the client's `api`, the branch in `complete`)
- Create: `modules/openai/src/main/scala/org/llm4s/llmconnect/provider/OpenAIResponsesRequest.scala`
- Create: `modules/openai/src/main/scala/org/llm4s/llmconnect/provider/OpenAIResponsesReply.scala`
- Create: `modules/openai/src/test/scala/org/llm4s/llmconnect/provider/OpenAIResponsesFixtures.scala`
- Test: `modules/openai/src/test/scala/org/llm4s/llmconnect/provider/OpenAIResponsesWireSpec.scala` (new), `modules/openai/src/test/scala/org/llm4s/config/OpenAINamedProviderSpec.scala`

**Interfaces:**
- Consumes: Task 1.
- Produces:

```scala
// OpenAIClientTransport (private[provider]). Abstract, with no throwing default: every existing implementation
// (grep "OpenAIClientTransport", test fakes included) gets a real one.
def createResponse(params: ResponseCreateParams): Response

// OpenAIClient
private val api: OpenAIApi   // resolved once in the constructor, logged at INFO with the reason

private[provider] object OpenAIResponsesRequest:
  final case class Settings(
    model: String,
    providerName: String,
    effort: Option[com.openai.models.ReasoningEffort],
    serviceTier: Option[ResponseCreateParams.ServiceTier],
    omitsSampling: Boolean
  )
  def build(settings: Settings, conversation: Conversation, options: CompletionOptions): Result[ResponseCreateParams]

private[provider] object OpenAIResponsesReply:
  def completion(response: Response, provider: ProviderId): Result[Completion]

// test
object OpenAIResponsesFixtures:
  def reply(output: String, status: String = "completed", extra: String = ""): String  // a full `response` object
  def message(text: String): String                                                    // one output message item
  val Usage: String  // input 9 (cached 0), output 6 (reasoning 2), total 15
```

`api` resolution in this PR: `Responses` when the config is an `OpenAIConfig` whose `api` is `Some(Responses)`; `ChatCompletions` otherwise (Azure included). The `openai` descriptor declares both keys as extras and `buildConfig` sets them with `OpenAIApi.parse` / `OpenAIReasoningSummary.parse`, a failure mapped through `ProviderConfig.inSection` so it names the section. The Requesty descriptor does not declare them.

`OpenAIResponsesFixtures.reply` is the documented `response` object in full (`id`, `object`, `created_at: 1700000000`, `status`, `model`, `output`, `usage`, `parallel_tool_calls`, `tool_choice`, `tools`, `error: null`, `incomplete_details: null`): the SDK throws on access to a missing required field.

- [ ] **Step 1: Write the failing tests.**

In `OpenAINamedProviderSpec` (follow its existing way of loading a section from HOCON):

```scala
"an openai section" should "load api = responses and reasoningSummary = auto into its config" in {
  // section: provider = "openai", model = "gpt-6.1-terra", apiKey = "sk-test", api = "responses", reasoningSummary = "auto"
  config.api shouldBe Some(OpenAIApi.Responses)
  config.reasoningSummary shouldBe Some(OpenAIReasoningSummary.Auto)
}

it should "fail to load with an api it does not know, naming the section and both values" in {
  // api = "response"
  error.message should include("openai-main")
  error.message should include("chat-completions")
}

"a requesty section" should "refuse an api key" in {
  // provider = "requesty", api = "responses": the loader's existing refusal of an undeclared key
  result.isLeft shouldBe true
}
```

In `OpenAIResponsesWireSpec` (same `Seen` / `capture` / `LocalProviderTestServer.withServer` shape as `OpenAIClientWireSpec:44-95`; a `responsesClient(baseUrl, model = "gpt-4o")` helper builds `OpenAIConfig.fromValues(...).value.withApi(OpenAIApi.Responses)`):

```scala
"OpenAIClient on the Responses API" should "post to <baseUrl>/responses with store false and read the reply" in {
  // server answers 200 with reply(message("hi"))
  val completion = client.complete(hello, CompletionOptions(maxTokens = Some(64))).value
  completion.content shouldBe "hi"
  completion.created shouldBe Instant.ofEpochSecond(1700000000L)
  completion.usage.map(_.promptTokens) shouldBe Some(9)
  completion.usage.map(_.completionTokens) shouldBe Some(4)   // 6 output less 2 reasoning
  completion.usage.flatMap(_.thinkingTokens) shouldBe Some(2)
  completion.usage.map(_.totalTokens) shouldBe Some(15)

  request.method shouldBe "POST"
  request.path shouldBe "/v1/responses"
  request.authorization shouldBe Some("Bearer sk-test")
  request.body("model").str shouldBe "gpt-4o"
  request.body("store").bool shouldBe false
  request.body("max_output_tokens").num shouldBe 64
  request.body("input")(0)("role").str shouldBe "user"
  request.body("input")(0)("content").str shouldBe "hello"
  request.body.obj.keySet should contain noneOf ("messages", "max_tokens", "previous_response_id", "include")
}

it should "post to /chat/completions when api is unset or chat-completions" in {
  // the same server, config without withApi and with withApi(OpenAIApi.ChatCompletions): path is /v1/chat/completions
}

it should "report a 404 from a gateway without /responses as the provider's service error" in {
  // server answers 404 {"error":{"message":"Not found","type":"invalid_request_error"}}
  val error = client.complete(hello, CompletionOptions()).left.value
  error shouldBe a[ServiceError]
  error.message should include("404")
  // and exactly one request reached the server
}
```

- [ ] **Step 2: Run and see them fail.** `sbt "openai/testOnly *OpenAIResponsesWireSpec *OpenAINamedProviderSpec"`.
- [ ] **Step 3: Implement.** The transport method calls `client.responses().create(params, requestOptions)` with the same `requestOptions` the chat call gets. `complete` on Responses follows the chat path step for step (`TransformationResult.transform`, adjustments, build, `adjustmentLog.record`, `call("response")`, convert, `filteredReply`, `recordExchange`), delegating only the build and the conversion. `OpenAIResponsesRequest.build` in this task handles system and user text, `store`, `max_output_tokens`, `temperature` and `top_p` (not sent when `omitsSampling`); a single-text user message sends its text as string `content`. `OpenAIResponsesReply.completion` in this task handles output text and usage. Whatever `completionOf` (`OpenAIClient.scala:865`) does beyond field mapping, the Responses path does too, through a shared helper rather than a copy.
- [ ] **Step 4: Run** `sbt openai/test`. Expected: all pass, the 356 existing tests included.
- [ ] **Step 5: Commit** `feat(openai): an openai section with api = "responses" sends complete to /v1/responses (#2900)`.

### Task 3: The whole request

**Files:**
- Modify: `OpenAIResponsesRequest.scala`; `OpenAIServiceTier.scala` if the tier needs a Responses form
- Test: `OpenAIResponsesWireSpec.scala`

**Interfaces:**
- Consumes: `OpenAIResponsesRequest.build`, `Settings` (Task 2).
- Produces: nothing new.

- [ ] **Step 1: Write the failing tests**, one per row of the spec's "Request mapping" table, each asserting on the captured body:

```scala
it should "send a system message as an input message with role system, in its position" in {
  // Conversation(Seq(UserMessage("a"), SystemMessage("be brief"), UserMessage("b")))
  body("input").arr.map(_("role").str) shouldBe Seq("user", "system", "user")
  body.obj.keySet should not contain "instructions"
}

it should "send an image by URL and inline, and a PDF inline and by file id" in {
  // parts: Text, Image(Url), Image(Inline), Document(Inline, Pdf), Document(ProviderFile("openai", "file-1"), Pdf)
  parts.map(_("type").str) shouldBe Seq("input_text", "input_image", "input_image", "input_file", "input_file")
  parts(1)("image_url").str shouldBe "https://example.com/cat.png"
  parts(2)("image_url").str should startWith("data:image/png;base64,")
  parts(3)("file_data").str should startWith("data:application/pdf;base64,")
  parts(3)("filename").str shouldBe "document.pdf"
  parts(4)("file_id").str shouldBe "file-1"
}

it should "refuse a part it cannot send with an UnsupportedContentPartError, before any request" in { /* an audio part; no request reaches the server */ }

it should "send an assistant turn as its text, then one function_call per tool call, and a tool result as function_call_output" in {
  // user, assistant(text "checking", calls call_1 get_weather {"city":"Paris"} and call_2 get_time {"zone":"CET"}),
  // tool(call_1, "18C"), tool(call_2, "12:00"), user
  input.map(i => i.obj.get("type").map(_.str).getOrElse(i("role").str)) shouldBe
    Seq("user", "assistant", "function_call", "function_call", "function_call_output", "function_call_output", "user")
  input(2)("call_id").str shouldBe "call_1"
  input(2)("name").str shouldBe "get_weather"
  input(2)("arguments").str shouldBe """{"city":"Paris"}"""
  input(2).obj.keySet should not contain "id"
  input(4)("call_id").str shouldBe "call_1"
  input(4)("output").str shouldBe "18C"
}

it should "send an assistant turn with tool calls and no text as its function_calls alone" in { /* no assistant message item */ }

it should "send tools in the flat function shape" in {
  tool("type").str shouldBe "function"
  tool("name").str shouldBe "get_weather"
  tool("strict").bool shouldBe true          // as OpenAIToolHelper sends on Chat Completions
  tool("parameters")("type").str shouldBe "object"
  tool.obj.keySet should not contain "function"
}

it should "send a JSON response format as text.format" in {
  // ResponseFormat.Json            -> body("text")("format")("type").str shouldBe "json_object"
  // ResponseFormat.JsonSchema(...) -> type "json_schema", and its name, schema and strict beside it (not nested under json_schema)
}

it should "send the reasoning effort as reasoning.effort, with tools, as asked" in {
  // model gpt-6.1-terra, options with tools and ReasoningEffort.Low
  body("reasoning")("effort").str shouldBe "low"
  completion.adjustments shouldBe empty      // the Chat Completions rule (reasoning_effort none with tools) does not apply
}

it should "send the service tier" in { /* the tier the chat path sends for the same options, under service_tier */ }

it should "not send presence_penalty or frequency_penalty, and report each as Dropped when set" in {
  body.obj.keySet should contain noneOf ("presence_penalty", "frequency_penalty")
  completion.adjustments.collect { case RequestAdjustment.Dropped(option, _) => option } should contain allOf
    (RequestOption.PresencePenalty, RequestOption.FrequencyPenalty)
  // and with neither set: no adjustment
}
```

- [ ] **Step 2: Run and see them fail.** `sbt "openai/testOnly *OpenAIResponsesWireSpec"`.
- [ ] **Step 3: Implement.** On Responses the client computes the effort from `OpenAIReasoning.plan` directly, never through `OpenAIModelRules.forcedToolEffort`. The penalty reports join the client's adjustments for this path (reason: the Responses API has no such parameter). An assistant message's thinking is not sent in this PR, sealed or not.
- [ ] **Step 4: Run** `sbt openai/test`. Expected: all pass.
- [ ] **Step 5: Commit** `feat(openai): the Responses request carries tools, content parts, structured output, effort and tier (#2900)`.

### Task 4: The whole reply

**Files:**
- Modify: `OpenAIResponsesReply.scala`, `OpenAIResponsesFixtures.scala`
- Test: `OpenAIResponsesWireSpec.scala`

**Interfaces:**
- Consumes: `OpenAIResponsesReply.completion` (Task 2).
- Produces: fixtures `functionCall(callId: String, name: String, arguments: String): String`, `reasoningItem(id: String): String`, `refusal(text: String): String`, used by the plans for slices 2 and 3.

- [ ] **Step 1: Write the failing tests:**

```scala
it should "read function_call items as tool calls, in output order" in {
  // output: functionCall("call_1", "get_weather", """{"city":"Paris"}"""), functionCall("call_2", "get_time", """{"zone":"CET"}""")
  completion.toolCalls.map(_.id.asString) shouldBe Seq("call_1", "call_2")   // adapt to ToolCallId's accessor
  completion.toolCalls.map(_.name) shouldBe Seq("get_weather", "get_time")
  completion.finishReason shouldBe FinishReason.ToolCalls
}

it should "fail on function_call arguments that are not JSON as the Chat Completions path does" in {
  // the same error type and message shape OpenAIToolCallExtractionSpec pins for chat completions
}

it should "ignore reasoning items and item types it does not map" in {
  // output: reasoningItem("rs_1"), {"type":"web_search_call","id":"ws_1","status":"completed"}, {"type":"not_a_real_item"}, message("hi")
  completion.content shouldBe "hi"
  completion.message.thinking shouldBe empty
  completion.finishReason shouldBe FinishReason.Stop
}

it should "deliver a refusal's text as the content" in {
  completion.content shouldBe "I can't help with that."
  completion.finishReason shouldBe FinishReason.Stop
}

it should "report an incomplete reply at max_output_tokens as Length, with the text so far" in {
  // status "incomplete", incomplete_details {"reason":"max_output_tokens"}
  completion.finishReason shouldBe FinishReason.Length
}

it should "report an incomplete reply at the content filter as a ContentFilteredError" in {
  // status "incomplete", incomplete_details {"reason":"content_filter"}; the error carries the usage as filteredReply's does
}

it should "report a failed reply as its mapped error" in {
  // status "failed", error {"code":"server_error","message":"boom"}
  error.message should include("boom")
}

it should "read url_citation annotations as citations" in {
  // output_text with annotations [{"type":"url_citation","url":"https://example.com","title":"Example","start_index":0,"end_index":2}]
  completion.citations.map(_.url) shouldBe Seq("https://example.com")   // the fields OpenAIClientCitationsSpec asserts
}

it should "report the cached input tokens" in { /* input_tokens_details.cached_tokens 5 -> the field toTokenUsage fills from cached_tokens today */ }
```

- [ ] **Step 2: Run and see them fail.**
- [ ] **Step 3: Implement.** Finish reason: `completed` with at least one `function_call` is `ToolCalls`, otherwise `Stop`. Text is every `output_text` and `refusal` part of every output message, concatenated in order.
- [ ] **Step 4: Run** `sbt openai/test`. Expected: all pass.
- [ ] **Step 5: Commit** `feat(openai): the Responses reply maps tool calls, finish reasons, refusals, citations and usage (#2900)`.

### Task 5: `streamComplete`, the exchange log and the release notes

**Files:**
- Modify: `OpenAIClient.scala` (`streamComplete`'s branch)
- Modify: `CHANGELOG.md` (`### Providers` under `[Unreleased]`; grep the heading, do not read the file)
- Test: `OpenAIResponsesWireSpec.scala`

**Interfaces:**
- Consumes: Tasks 2 to 4.
- Produces: nothing new. Slice 3 replaces this task's `streamComplete` with native streaming.

- [ ] **Step 1: Write the failing tests:**

```scala
it should "answer streamComplete with one unstreamed call, delivered as chunks" in {
  val chunks = ListBuffer.empty[StreamedChunk]
  val completion = client.streamComplete(hello, CompletionOptions())(chunks += _).value
  completion.content shouldBe "hi"
  chunks.flatMap(_.contentDelta).mkString shouldBe "hi"
  chunks.last.finishReason shouldBe Some(FinishReason.Stop)
  request.path shouldBe "/v1/responses"
  request.body.obj.keySet should not contain "stream"
}

it should "record the Responses request and reply in the exchange log" in {
  // a ProviderExchangeSink as OpenAIClientWireSpec builds one: one exchange, its request body containing "store"
  // and its response body containing "resp_1"; and one failed exchange for a 500
}
```

- [ ] **Step 2: Run and see them fail.**
- [ ] **Step 3: Implement** with the existing `executeFakeStreaming` / `emitCompletionAsChunks` route, whatever `transformed.requiresFakeStreaming` says.
- [ ] **Step 4: Add the CHANGELOG entry** under Providers: `- **Added** an \`openai\` section with \`api = "responses"\` uses OpenAI's Responses API, where reasoning and tools work together; streaming there is one call delivered in chunks until native streaming lands ([#2900](https://github.com/llm4s/llm4s/issues/2900)).` Later slices edit this entry; they do not add another.
- [ ] **Step 5: Run everything:** `sbt scalafmtAll`, then `sbt "openai/test" "openaiCompatible/test" stabilityTierCheck mimaReportBinaryIssues frozenDependencyCheck`, then `scripts/check-docs.sh`. Expected: all pass.
- [ ] **Step 6: Commit** `feat(openai): streamComplete and the exchange log on the Responses API (#2900)`.

### Task 6: Live verification (needs `OPENAI_API_KEY`)

**Files:**
- Modify: `modules/it/src/test/scala/org/llm4s/llmconnect/smoke/OpenAISmokeSpec.scala`

**Interfaces:**
- Consumes: the finished client.
- Produces: the answer to the spec's open question, recorded in the spec.

- [ ] **Step 1: Add the cases**, each on `gpt-6.1-terra` and `gpt-5.6-luna` with `api = "responses"`, built as the suite's existing tool cases are:

```scala
"call a tool at effort low and report reasoning tokens" in {
  completion.toolCalls should not be empty
  completion.usage.flatMap(_.thinkingTokens).exists(_ > 0) shouldBe true
  completion.adjustments shouldBe empty
}

"finish a tool loop whose second request sends the function_call without its reasoning item" in {
  // turn 1 as above; turn 2 sends [user, assistant(tool call), tool result]
  second.isRight shouldBe true
  second.value.content should not be empty
}
```

and one plain completion on a non-reasoning model.

- [ ] **Step 2: Run** `sbt "it/testOnly *OpenAISmokeSpec"` with the key set. Without a key, stop here and report that Task 6 is not done: do not mark it complete.
- [ ] **Step 3: Record the result** in the spec's "Reasoning across turns", replacing the "To verify live in slice 1" bullet with what OpenAI did and the date. **If the second case fails with OpenAI refusing the `function_call`, stop: that is the decision the spec reserves for the maintainer. Report the exact error and do not work around it.**
- [ ] **Step 4: Commit** `test(it): OpenAI tool calls with reasoning on the Responses API, live (#2900)`.
