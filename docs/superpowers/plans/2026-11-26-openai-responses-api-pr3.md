# OpenAI Responses API, PR 3 (native streaming) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `streamComplete` on the Responses API streams: text, tool-call arguments and reasoning summaries reach the caller as they are generated, and the final `Completion` is the one `complete` would have returned.

**Architecture:** `OpenAIClientTransport` gains `createResponseStream`. A new `private[provider]` class, `OpenAIResponsesStream`, turns each `ResponseStreamEvent` into `StreamedChunk`s and remembers the terminal event. The final `Completion` is built from the terminal event's full `Response` by the existing `OpenAIResponsesReply.completion`, so a streamed and an unstreamed reply cannot differ in usage, reasoning items, citations or finish reason. A model the transformer marks `requiresFakeStreaming` keeps PR 1's one-call route.

**Tech Stack:** Scala 3.7.1, sbt, ScalaTest, `com.openai:openai-java` 4.69.3 (`ResponseStreamEvent` and its `Response*Event` members, `StreamResponse`), `LocalProviderTestServer.sendSseResponse`, `llm4s-provider-testkit`'s `ProviderModuleChecks.assertModelCallContract`.

**Spec:** `docs/superpowers/specs/2026-11-26-openai-responses-api-design.md`, slice 3 of "Slices". Read "Streaming", "Errors, logging, metrics" and the slice-3 items of "Carried into later slices" before any task.

## Global Constraints

- Start from `main` after PR 2 has merged; until then the branch is stacked on `feat/2900-responses-reasoning`.
- `llm4s-openai`, `llm4s-openai-compatible` and `llm4s-core` are frozen: `sbt mimaReportBinaryIssues` passes against 1.0.0 with no filter; `sbt stabilityTierCheck` passes. No new public type or member: everything added is `private[provider]`.
- Every request sends `store: false`, and a streamed request the same body as the unstreamed one plus `stream: true`. No `previous_response_id`.
- The final `Completion` of a stream comes from `OpenAIResponsesReply.completion` applied to the terminal event's `Response`, then `filteredReply` and the adjustments, exactly as `complete` does. Nothing about the final completion is assembled from deltas.
- Every tool-call delta chunk carries its call's id (`StreamingAccumulator` keys calls by id). The stream's events identify a call by `item_id` / `output_index`; the stream keeps that map.
- The last chunk delivered carries the finish reason, and no chunk follows it.
- A stream that ends with no terminal event is `OpenAIClient.unfinished(...)`. Text already delivered stays delivered.
- Exactly one exchange is recorded per call, with the request body, and the raw events received so far as the response body, on success and on failure.
- The Chat Completions path is unchanged.
- No `try`/`catch`: `Result[A]`; `Using.resource` for the stream, as the Chat Completions path does. No infix method calls. Nothing may throw out of `streamComplete`.
- `OpenAIClient.scala` gains only the transport method, the branch and the delegation. Event handling goes in `OpenAIResponsesStream.scala`.
- Every commit: `git commit -s`, message ending `Co-Authored-By: Claude Fable 5.2 <noreply@anthropic.com>`.
- Filter sbt output: `2>&1 | grep -E '\[error\]|\*\*\* FAILED|Tests: |Failed tests'`.

## Review Focus

Each line has its test in the task named.

1. **A stream whose HTTP body ends after some text, with no `response.completed`.** `Left(unfinished)`, the text chunks already delivered, one failed exchange holding the events received. (Task 1)
2. **An event type this client does not map** (`response.web_search_call.searching`, or one the SDK does not know). Ignored; the stream continues. (Task 1)
3. **Two tool calls whose argument deltas interleave** (`output_index` 0 and 1 alternating). Each delta carries its own call's id and the calls rebuild correctly. (Task 1)
4. **A caller's `onChunk` that throws.** The exception does not escape `streamComplete` as a throw: it is a `Left`, the stream is closed, and the exchange is recorded. (Task 1)
5. **`response.failed` after text was delivered.** The mapped error (`rate_limit_exceeded` as a `RateLimitError`), the text chunks already delivered, no finish-reason chunk. (Task 1)

---

### Task 1: Stream events to chunks, and the final completion from the terminal event

**Files:**
- Create: `modules/openai/src/main/scala/org/llm4s/llmconnect/provider/OpenAIResponsesStream.scala`
- Modify: `modules/openai/src/main/scala/org/llm4s/llmconnect/provider/OpenAIClient.scala` (transport trait and every implementation of it, tests' fakes included; the `streamComplete` branch)
- Modify: `modules/openai/src/test/scala/org/llm4s/llmconnect/provider/OpenAIResponsesFixtures.scala`
- Test: create `modules/openai/src/test/scala/org/llm4s/llmconnect/provider/OpenAIResponsesStreamingSpec.scala`

**Interfaces:**
- Produces:

```scala
// OpenAIClientTransport (private[provider]); abstract, a real implementation everywhere
def createResponseStream(params: ResponseCreateParams): StreamResponse[ResponseStreamEvent]

private[provider] final class OpenAIResponsesStream(onChunk: StreamedChunk => Unit):
  /** Delivers the chunks `event` stands for, and remembers a terminal event. */
  def accept(event: ResponseStreamEvent): Unit
  /** The terminal event's response: `response.completed`, `response.incomplete` or `response.failed`. */
  def terminal: Option[Response]
  /** The error of an `error` event, when one arrived. */
  def streamError: Option[ujson.Value]
  /** Delivers the closing chunk carrying `reason`; call once, after the final completion is known. */
  def finish(reason: FinishReason): Unit

// fixtures
def sse(events: String*): String                      // "event: <type>\ndata: <json>\n\n" per event; no [DONE]
def created(): String                                 // response.created
def textDelta(delta: String, itemId: String = "msg_1", outputIndex: Int = 0): String
def callAdded(callId: String, name: String, itemId: String, outputIndex: Int): String   // response.output_item.added
def argumentsDelta(delta: String, itemId: String, outputIndex: Int): String
def summaryDelta(delta: String, itemId: String = "rs_1"): String
def refusalDelta(delta: String): String
def completed(response: String): String               // response.completed wrapping a full `reply(...)`
def incomplete(response: String): String
def failed(response: String): String
def errorEvent(code: String, message: String): String
```

Event mapping (spec, "Streaming"):

| Event | Chunk |
|---|---|
| `response.output_text.delta` | `contentDelta` |
| `response.refusal.delta` | `contentDelta` |
| `response.output_item.added` with a `function_call` item | the call's opening tool-call delta: id `call_id`, its name, empty arguments |
| `response.function_call_arguments.delta` | a tool-call delta with the call's id (looked up by `item_id`) and the fragment |
| `response.reasoning_summary_text.delta` | `thinkingDelta` |
| `response.completed`, `response.incomplete`, `response.failed` | none; remembered as `terminal` |
| `error` | none; remembered as `streamError` |
| anything else | none |

The client's streaming route on Responses: transform and build the params as `complete` does; open the stream in `Using.resource` inside `call(...)`; feed each event to `accept`, appending its serialised form to the raw-events buffer; then, in this order: a `streamError` is the mapped error (the mapping `OpenAIResponsesReply` uses for a failed reply); no `terminal` is `OpenAIClient.unfinished`; otherwise `OpenAIResponsesReply.completion(terminal, ...)`, `filteredReply`, adjustments, and only on a `Right` `finish(completion.finishReason)`. Record the exchange once.

- [ ] **Step 1: Write the failing tests** in `OpenAIResponsesStreamingSpec` (over `LocalProviderTestServer.sendSseResponse`, collecting chunks in a `ListBuffer`; `gpt-4o` unless stated):

```scala
"streamComplete on the Responses API" should "post a streaming request and deliver text as it arrives" in {
  // sse(created(), textDelta("The answer"), textDelta(" is 4."), completed(reply(message("The answer is 4."))))
  request.path shouldBe "/v1/responses"
  request.body("stream").bool shouldBe true
  request.body("store").bool shouldBe false
  chunks.flatMap(_.contentDelta) shouldBe Seq("The answer", " is 4.")
  chunks.last.finishReason shouldBe Some(FinishReason.Stop)
  chunks.init.flatMap(_.finishReason) shouldBe empty
  completion.content shouldBe "The answer is 4."
  completion.usage.map(_.totalTokens) shouldBe Some(15)
}

it should "deliver a tool call's name and arguments with its call id on every delta" in {
  // callAdded("call_1", "get_weather", "fc_1", 0), argumentsDelta("{\"city\":", "fc_1", 0), argumentsDelta("\"Paris\"}", "fc_1", 0),
  // completed(reply(functionCall("call_1", "get_weather", """{"city":"Paris"}""")))
  toolDeltas.map(_.id.asString).distinct shouldBe Seq("call_1")        // adapt to ToolCallId's accessor
  completion.toolCalls.map(_.name) shouldBe Seq("get_weather")
  chunks.last.finishReason shouldBe Some(FinishReason.ToolCalls)
  // and a StreamingAccumulator fed these chunks rebuilds the same call with arguments {"city":"Paris"}
}

it should "keep two interleaved tool calls apart" in {
  // callAdded call_1/fc_1/0, callAdded call_2/fc_2/1, argumentsDelta for fc_1, fc_2, fc_1, fc_2, completed(...)
  // a StreamingAccumulator fed the chunks yields call_1 {"city":"Paris"} and call_2 {"zone":"CET"}
}

it should "deliver reasoning summary text as thinking deltas and keep the reasoning on the final message" in {
  // gpt-6.1-terra; summaryDelta("Thinking"), summaryDelta(" it through."), textDelta("4"),
  // completed(reply(reasoningItem("rs_1", summary = Seq("Thinking it through.")) + "," + message("4")))
  chunks.flatMap(_.thinkingDelta) shouldBe Seq("Thinking", " it through.")
  completion.thinking shouldBe Some("Thinking it through.")
  completion.message.hasSealedThinking shouldBe true
}

it should "return the same message complete returns for the same reply" in {
  // one reply with a reasoning item (summary and encrypted content), text and a tool call, served unstreamed to complete
  // and as an event stream ending in response.completed to streamComplete:
  streamed.message shouldBe unstreamed.message          // thinking blocks, binding, text and tool calls
  streamed.usage shouldBe unstreamed.usage
  streamed.finishReason shouldBe unstreamed.finishReason
}

it should "deliver a refusal's text as content" in { /* refusalDelta("I can't"), refusalDelta(" help."), completed(reply(refusal(...))) */ }

it should "ignore events it does not map" in {
  // an event of type response.web_search_call.searching and one of type response.not_a_real_event between two textDeltas
  chunks.flatMap(_.contentDelta) shouldBe Seq("a", "b")
}

it should "end Length for a reply cut at max_output_tokens, with the text delivered" in {
  // textDelta("partial"), incomplete(reply(message("partial"), status = "incomplete", extra = incomplete_details max_output_tokens))
  chunks.last.finishReason shouldBe Some(FinishReason.Length)
  completion.finishReason shouldBe FinishReason.Length
}

it should "fail with a ContentFilteredError for a reply stopped by the content filter, with no finish chunk" in {
  // incomplete(... content_filter): result.left.value shouldBe a[ContentFilteredError]; chunks.flatMap(_.finishReason) shouldBe empty
}

it should "fail with the mapped error on response.failed, keeping the text already delivered" in {
  // textDelta("so far"), failed(reply("", status = "failed", extra = error rate_limit_exceeded "slow down"))
  result.left.value shouldBe a[RateLimitError]
  chunks.flatMap(_.contentDelta) shouldBe Seq("so far")
  chunks.flatMap(_.finishReason) shouldBe empty
}

it should "fail with the mapped error on an error event" in {
  // textDelta("so far"), errorEvent("server_error", "boom"): Left containing "boom"
}

it should "fail as unfinished when the stream ends with no terminal event" in {
  // sse(created(), textDelta("so far")) and the body ends
  result.left.value shouldBe a[NetworkError]
  chunks.flatMap(_.contentDelta) shouldBe Seq("so far")
}

it should "return a Left when the caller's onChunk throws, and record the exchange" in {
  // onChunk = _ => throw new IllegalStateException("consumer failed")
  result.isLeft shouldBe true
  exchanges should have size 1
}

it should "record one exchange holding the request and the events received, on success and on failure" in {
  // success: requestBody contains "\"stream\":true", responseBody contains "response.completed"
  // unfinished: one exchange, errorMessage defined, responseBody contains "so far"
}

it should "report the same adjustments and estimated cost as complete" in {
  // presencePenalty set: the Dropped report on the streamed completion; estimatedCost defined
}

it should "still answer a model that cannot stream natively with one unstreamed call" in {
  // a model for which the transformer sets requiresFakeStreaming (the one OpenAIClientStreamingSpec uses for the Chat
  // Completions equivalent): the request has no "stream" key and the reply is delivered as chunks
}
```

PR 1's test "answer streamComplete with one unstreamed call, delivered as chunks" asserted the request has no `stream` key for an ordinary model: replace it with the last test above.

- [ ] **Step 2: Run and see them fail.** `sbt "openai/testOnly *OpenAIResponsesStreamingSpec"`.
- [ ] **Step 3: Implement.** Read events through the SDK's `is*`/`as*` accessors inside `Try`, so an event the SDK cannot read is skipped. The `onChunk` call is inside the `call(...)` that already maps a thrown exception to a `Left`.
- [ ] **Step 4: Run** `sbt openai/test`. Expected: all pass.
- [ ] **Step 5: Commit** `feat(openai): streamComplete streams on the Responses API (#2900)`.

### Task 2: The model-call contract on the Responses API

**Files:**
- Create: `modules/openai/src/test/scala/org/llm4s/llmconnect/provider/OpenAIResponsesContractFixtures.scala`
- Modify: `modules/openai/src/test/scala/org/llm4s/llmconnect/provider/Llm4sOpenAIModuleSpec.scala`

**Interfaces:**
- Consumes: Task 1; `ProviderModuleChecks.assertModelCallContract`, `ContractCase`, `Applicability`, `RecordedReply` from `llm4s-provider-testkit`; PR 1's `OpenAIResponsesFixtures`.
- Produces: `OpenAIResponsesContractFixtures.replies: Map[ContractCase, Applicability]`, one entry per case, in the Responses shapes (a `response` object for `complete`, an event stream for `streamComplete`), mirroring `OpenAIContractFixtures`.

The nine cases and their Responses shape:

| Case | Unstreamed | Streamed |
|---|---|---|
| `CleanText` | `completed`, one message | text deltas, `response.completed` |
| `TextCutByLimit` | `incomplete`, `max_output_tokens`, a message | text deltas, `response.incomplete` |
| `ToolCall` | `completed`, one `function_call` | `output_item.added`, argument deltas, `response.completed` |
| `NoArgumentToolCall` | a `function_call` with arguments `{}` (or `""`, whichever the contract's Chat Completions fixture uses) | the same, streamed |
| `ToolCallCutByLimit` | `incomplete`, `max_output_tokens`, a `function_call` with truncated arguments | the same, streamed |
| `MalformedArguments` | `completed`, a `function_call` whose arguments are not JSON | the same, streamed |
| `ContentFiltered` | `incomplete`, `content_filter` | `response.incomplete` with `content_filter` |
| `StreamEndsEarly` | stream only | text deltas, then the body ends |
| `ErrorEvent` | stream only | an `error` event carrying `ContractCase.ProviderErrorMessage` |

- [ ] **Step 1: Write the fixtures and the failing check.** In `Llm4sOpenAIModuleSpec`, beside the existing `assertModelCallContract(OpenAIContractFixtures.replies)(openAIClientAt)`:

```scala
assertModelCallContract(OpenAIResponsesContractFixtures.replies)(openAIResponsesClientAt)
// openAIResponsesClientAt(baseUrl): the client openAIClientAt builds, its config .withApi(OpenAIApi.Responses)
```

- [ ] **Step 2: Run** `sbt "openai/testOnly *Llm4sOpenAIModuleSpec"`. Expected: the new check passes if Task 1 and PRs 1 and 2 satisfy the contract. **A case that fails is a client defect, not a fixture to bend**: fix the client (in `OpenAIResponsesReply` or `OpenAIResponsesStream`), add a wire test for it beside the others, and say which case it was in the commit message. `ToolCallCutByLimit` and `MalformedArguments` are the likeliest: read what the contract expects for them (`ProviderModuleChecks`) before deciding.
- [ ] **Step 3: Run** `sbt openai/test`. Expected: all pass.
- [ ] **Step 4: Commit** `test(openai): the model-call contract holds on the Responses API (#2900)`.

### Task 3: What PR 1 and PR 2 carried here

**Files:**
- Modify: `OpenAIResponsesReply.scala` (`statusOf`), `OpenAIClient.scala` (`completeAndRecord`)
- Test: `OpenAIResponsesWireSpec.scala`

- [ ] **Step 1: Write the failing tests:**

```scala
it should "report a failed reply the request can never satisfy as a 400-class error, not a 502" in {
  // status failed, error code invalid_prompt: an InvalidRequestError-class error (the type HttpErrorMapper gives a 400),
  // not retryable; the same for invalid_image, invalid_image_format, image_too_large and the other image_* codes of
  // ResponseError.Code; server_error and vector_store_timeout stay a 502 ServiceError
}

it should "record the request and the reply for a 200 reply that maps to an error" in {
  // complete: a failed reply, a content-filter reply and a malformed-tool-arguments reply each record one exchange
  // whose requestBody and responseBody are both defined
}
```

- [ ] **Step 2: Run and see them fail.**
- [ ] **Step 3: Implement.** The code-to-status table stays in `OpenAIResponsesReply`. `completeAndRecord` records the bodies it has when the mapping fails; a failure before the request was built still records neither.
- [ ] **Step 4: Run** `sbt scalafmtAll`, then `sbt "openai/test" "openaiCompatible/test" stabilityTierCheck mimaReportBinaryIssues`. Expected: all pass.
- [ ] **Step 5: Commit** `fix(openai): a failed Responses reply the request caused is a 400-class error; mapped failures keep their exchange bodies (#2900)`.

### Task 4: Live verification and release notes (needs `OPENAI_API_KEY`)

**Files:**
- Modify: `modules/it/src/test/scala/org/llm4s/llmconnect/smoke/OpenAISmokeSpec.scala`, `CHANGELOG.md` (edit #2900's entry), the spec

- [ ] **Step 1: Add the cases**, with `api = "responses"`, in the style of the existing Responses cases:

```scala
"stream text on the Responses API" in {                       // gpt-4o-mini
  chunks.flatMap(_.contentDelta).mkString shouldBe completion.content
  chunks.count(_.contentDelta.exists(_.nonEmpty)) should be > 1      // it streamed, it was not one chunk
  chunks.last.finishReason shouldBe Some(completion.finishReason)
}

"stream a tool call with reasoning on the Responses API" in { // gpt-6.1-terra and gpt-5.6-luna, effort low
  // cancel when the model makes no tool call
  completion.toolCalls should not be empty
  toolDeltas.map(_.id).distinct should have size completion.toolCalls.size
  completion.usage.flatMap(_.thinkingTokens).exists(_ > 0) shouldBe true
  completion.message.hasSealedThinking shouldBe true
}

"stream reasoning summary text when reasoningSummary is auto" in {  // one model; cancel on a verification error
  chunks.flatMap(_.thinkingDelta).mkString should not be empty
}

"finish a streamed tool loop that sends its reasoning back" in {    // turn 1 streamed, turn 2 streamed: accepted
}
```

- [ ] **Step 2: Run** `sbt "it/testOnly org.llm4s.llmconnect.smoke.OpenAISmokeSpec"` with the key loaded from `.env` (never printed), capturing `*** FAILED`, `CANCELED` and `Tests:` lines with six lines of context. At most three runs. **If a live stream contains an event shape the client mishandles, stop and report the exact event: do not patch around it in the test.**
- [ ] **Step 3: Update the spec**: in "Carried into later slices" remove the slice-3 items this PR closed, and add a dated line under "Streaming" saying what was verified live.
- [ ] **Step 4: Edit the CHANGELOG entry** for #2900: remove "streaming there is one call delivered in chunks until native streaming lands"; say `streamComplete` streams text, tool-call arguments and reasoning summaries. Two lines at most.
- [ ] **Step 5: Run** `sbt it/scalafmtAll it/itTierCheck` and `scripts/check-docs.sh`. Expected: all pass.
- [ ] **Step 6: Commit** `test(it): OpenAI streaming on the Responses API, live (#2900)`.
