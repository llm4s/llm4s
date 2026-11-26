# OpenAI Responses API, PR 2 (reasoning across turns) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On the Responses API the reasoning a turn produced is stored on its assistant message and sent back with the next request of the same thread, so a tool loop keeps the model's reasoning; `reasoningSummary` is sent when configured.

**Architecture:** A new `private[provider]` object, `OpenAIReasoningItems`, converts between OpenAI's `reasoning` output items and `ThinkingBlock`s (an `Opaque` block per item holding an llm4s envelope, plus a `Text` block for a summary). `OpenAIResponsesReply` attaches the blocks and binds them with `ThinkingReplay.bindOrigin`; `OpenAIResponsesRequest` unseals with `ThinkingReplay.replayableOrigin` and puts each item back before the item it preceded. No core change and no new stored-JSON shape.

**Tech Stack:** Scala 3.7.1, sbt, ScalaTest, `com.openai:openai-java` 4.69.3 (`ResponseReasoningItem`, `ResponseIncludable.REASONING_ENCRYPTED_CONTENT`, `com.openai.models.Reasoning`), `LocalProviderTestServer`.

**Spec:** `docs/superpowers/specs/2026-11-26-openai-responses-api-design.md`, slice 2 of "Slices". Read "Reasoning across turns" and "Carried into later slices" before any task. PR 1's plan (`2026-11-26-openai-responses-api-pr1.md`) describes what already exists.

## Global Constraints

- Start from `main` after PR 1 (#2957) has merged; until then the branch is stacked on `feat/2900-openai-responses-api`.
- `llm4s-openai`, `llm4s-openai-compatible` and `llm4s-core` are frozen: `sbt mimaReportBinaryIssues` passes against 1.0.0 with no filter; `sbt stabilityTierCheck` passes. **No new public type or member in this PR**: everything added is `private[provider]`.
- No change to `llm4s-core`. `ThinkingBlock`'s JSON (`{"type":"opaque","provider":...,"data":...}`) is not touched.
- Every request still sends `store: false`; no `previous_response_id`. Message, `function_call` and `function_call_output` input items carry no item id. A replayed reasoning item is sent exactly as OpenAI returned it, its `id` included.
- The opaque block's provider is `ProviderId("openai")`. Its `data` is the JSON object `{"item": <the reasoning item as returned>, "before": "<call_id>"}`, `before` present only when the next output item was a `function_call`.
- The Chat Completions path sends no reasoning item and is otherwise unchanged.
- No `try`/`catch`: `Result[A]`. No infix method calls. Nothing on the reply or request path may throw: a malformed envelope is skipped, not an error.
- `OpenAIClient.scala` gains only the arguments it passes. Mapping code goes in `OpenAIReasoningItems.scala`, `OpenAIResponsesRequest.scala` and `OpenAIResponsesReply.scala`.
- Every commit: `git commit -s`, message ending `Co-Authored-By: Claude Fable 5.2 <noreply@anthropic.com>`.
- Filter sbt output: `2>&1 | grep -E '\[error\]|\*\*\* FAILED|Tests: |Failed tests'`.

## Review Focus

Each line has its test in the task named.

1. **A thread stored before this PR, or on Chat Completions, then continued on Responses.** Its assistant messages have no opaque blocks: the request is exactly what PR 1 sends. (Task 3)
2. **A thread produced by another provider, then continued on OpenAI Responses.** A Gemini or OpenRouter `Opaque` block, or an Anthropic signed block, is never sent as a reasoning item. (Task 3)
3. **A message edited after it was received** (`withContent`, `withToolCalls`), or pruned so a tool call is gone. No reasoning item is sent for it, and none whose anchor call is missing. (Task 3)
4. **An opaque block with provider `openai` whose `data` is not the envelope** (truncated JSON, an `item` that is not a reasoning item). Skipped; the request is still sent. (Task 3)
5. **A reasoning item with no `encrypted_content`** (a model or gateway that ignores `include`). Not stored as an opaque block, since sending it back would be an id OpenAI cannot resolve under `store: false`; its summary text is still kept. (Task 2)

---

### Task 1: Ask for encrypted reasoning, and for a summary when configured

**Files:**
- Modify: `modules/openai/src/main/scala/org/llm4s/llmconnect/provider/OpenAIResponsesRequest.scala` (`Settings`, `build`)
- Modify: `modules/openai/src/main/scala/org/llm4s/llmconnect/provider/OpenAIClient.scala` (where `Settings` is built)
- Test: `modules/openai/src/test/scala/org/llm4s/llmconnect/provider/OpenAIResponsesWireSpec.scala`

**Interfaces:**
- Produces: two fields on `OpenAIResponsesRequest.Settings`, after `omitsSampling`:

```scala
reasons: Boolean,                               // the client's isReasoningRequest(options)
summary: Option[OpenAIReasoningSummary]         // OpenAIConfig.reasoningSummary; None for a non-OpenAIConfig
```

`include: ["reasoning.encrypted_content"]` is sent exactly when `reasons`. `reasoning.summary` (`auto`, `concise`, `detailed`) is sent exactly when `reasons` and `summary` is set; it shares the `reasoning` object with `effort` and is sent even when no effort is.

- [ ] **Step 1: Write the failing tests:**

```scala
it should "ask for encrypted reasoning from a reasoning model" in {
  // gpt-6.1-terra, no effort set
  body("include").arr.map(_.str) shouldBe Seq("reasoning.encrypted_content")
  body("store").bool shouldBe false
}

it should "not ask a non-reasoning model for encrypted reasoning" in {
  // gpt-4o
  body.obj.keySet should not contain "include"
  body.obj.keySet should not contain "reasoning"
}

it should "send the configured reasoning summary beside the effort" in {
  // gpt-6.1-terra, config.withReasoningSummary(OpenAIReasoningSummary.Auto), ReasoningEffort.Low
  body("reasoning")("summary").str shouldBe "auto"
  body("reasoning")("effort").str shouldBe "low"
  // Concise -> "concise", Detailed -> "detailed"; with no effort set, reasoning is {"summary":"auto"} alone
}

it should "send no reasoning summary unless one is configured, and none to a non-reasoning model" in {
  // gpt-6.1-terra without withReasoningSummary: body("reasoning").obj.keySet should not contain "summary"
  // gpt-4o with withReasoningSummary(Auto): no "reasoning" key at all
}
```

- [ ] **Step 2: Run and see them fail.** `sbt "openai/testOnly *OpenAIResponsesWireSpec"`.
- [ ] **Step 3: Implement.** Use `ResponseIncludable.REASONING_ENCRYPTED_CONTENT` and `Reasoning.Builder.summary`.
- [ ] **Step 4: Run** `sbt openai/test`. Expected: all pass.
- [ ] **Step 5: Commit** `feat(openai): the Responses request asks for encrypted reasoning, and for a summary when configured (#2900)`.

### Task 2: Reasoning items in a reply become bound thinking blocks

**Files:**
- Create: `modules/openai/src/main/scala/org/llm4s/llmconnect/provider/OpenAIReasoningItems.scala`
- Modify: `OpenAIResponsesReply.scala`, `OpenAIClient.scala` (passes the origin)
- Modify: `modules/openai/src/test/scala/org/llm4s/llmconnect/provider/OpenAIResponsesFixtures.scala`
- Test: `OpenAIResponsesWireSpec.scala`; create `modules/openai/src/test/scala/org/llm4s/llmconnect/provider/OpenAIReasoningItemsSpec.scala`

**Interfaces:**
- Consumes: `ThinkingReplay.bindOrigin(origin: ReplayOrigin, message: AssistantMessage): AssistantMessage` and `ReplayOrigin(provider: String, model: String)` (`private[llm4s]`, core). The origin is the client's configured provider and model, as `AnthropicClient.replayOrigin` builds it; never the model a reply reports.
- Produces:

```scala
private[provider] object OpenAIReasoningItems:
  val Provider: ProviderId = ProviderId("openai")

  /** The thinking blocks for a reply's output items, in output order. */
  def blocks(output: Seq[ResponseOutputItem]): Seq[ThinkingBlock]

  /** One stored reasoning item: where it goes back, and the item itself. */
  final case class Stored(before: Option[String], item: ResponseReasoningItem)

  /** The reasoning items `blocks` holds for OpenAI, in order; anything else, or unreadable, is left out. */
  def stored(blocks: Seq[ThinkingBlock]): Seq[Stored]

// OpenAIResponsesReply.completion gains the origin:
def completion(response: Response, provider: ProviderId, configuredModel: ModelName, origin: ReplayOrigin): Result[Completion]

// fixtures
def reasoningItem(id: String, encrypted: Option[String] = Some("enc-" + id), summary: Seq[String] = Nil): String
```

For each `reasoning` output item, in order: one `ThinkingBlock.Text(joined summary texts)` when it has summary text, then, when it has `encrypted_content`, one `ThinkingBlock.Opaque(Provider, envelope)`. The envelope's `item` is the item serialised with `ObjectMappers.jsonMapper()`; `before` is the `call_id` of the next output item when that item is a `function_call`. Summary parts are joined with a blank line.

- [ ] **Step 1: Write the failing tests.**

`OpenAIReasoningItemsSpec` (pure; build output items by reading fixture JSON with `ObjectMappers.jsonMapper()`):

```scala
"OpenAIReasoningItems.blocks" should "hold each encrypted reasoning item as an openai opaque block anchored to the call after it" in {
  // output: reasoningItem("rs_1"), functionCall("call_1", ...), reasoningItem("rs_2"), functionCall("call_2", ...)
  val opaque = blocks.collect { case ThinkingBlock.Opaque(p, d) => (p, ujson.read(d)) }
  opaque.map(_._1) shouldBe Seq(ProviderId("openai"), ProviderId("openai"))
  opaque.map(_._2("before").str) shouldBe Seq("call_1", "call_2")
  opaque.map(_._2("item")("id").str) shouldBe Seq("rs_1", "rs_2")
  opaque.map(_._2("item")("encrypted_content").str) shouldBe Seq("enc-rs_1", "enc-rs_2")
}

it should "leave out `before` when the next item is the message or nothing" in {
  // reasoningItem("rs_1"), message("hi")  and  reasoningItem("rs_1") alone
  ujson.read(data).obj.keySet should not contain "before"
}

it should "keep a summary as text, before its opaque block" in {
  // reasoningItem("rs_1", summary = Seq("First.", "Second."))
  blocks.head shouldBe ThinkingBlock.Text("First.\n\nSecond.")
  blocks(1) shouldBe a[ThinkingBlock.Opaque]
}

it should "not store a reasoning item that has no encrypted content, but keep its summary" in {
  // reasoningItem("rs_1", encrypted = None, summary = Seq("Thought."))
  blocks shouldBe Seq(ThinkingBlock.Text("Thought."))
  // and with no summary either: blocks shouldBe empty
}

"OpenAIReasoningItems.stored" should "read back what blocks wrote, in order" in {
  stored(blocks(output)).map(s => (s.before, s.item.id())) shouldBe Seq((Some("call_1"), "rs_1"), (Some("call_2"), "rs_2"))
}

it should "leave out another provider's opaque block, text blocks, and an openai block it cannot read" in {
  // Opaque(ProviderId("gemini"), "{}"), Text("x"), Opaque(openai, "not json"), Opaque(openai, """{"item":{"type":"message"}}"""), Opaque(openai, "{}")
  stored(those) shouldBe empty
}
```

`OpenAIResponsesWireSpec`:

```scala
it should "carry a reply's reasoning on the assistant message, bound to this provider and model" in {
  // gpt-6.1-terra; reply output: reasoningItem("rs_1", summary = Seq("Thinking it through.")), functionCall("call_1", "get_weather", """{"city":"Paris"}""")
  completion.thinking shouldBe Some("Thinking it through.")
  completion.message.hasSealedThinking shouldBe true
  completion.message.thinkingBinding shouldBe defined
  completion.toolCalls.map(_.name) shouldBe Seq("get_weather")
}

it should "report no thinking for a reply without reasoning items" in {
  completion.message.thinking shouldBe empty
  completion.message.thinkingBinding shouldBe None
}
```

PR 1's test "ignore reasoning items and item types it does not map" asserted `completion.message.thinking shouldBe empty` for a reply with `reasoningItem("rs_1")`: change that assertion to expect the one opaque block, and keep the rest.

- [ ] **Step 2: Run and see them fail.** `sbt "openai/testOnly *OpenAIReasoningItemsSpec *OpenAIResponsesWireSpec"`.
- [ ] **Step 3: Implement.** `blocks` and `stored` never throw: wrap JSON and SDK reads in `Try` and drop what fails. The reply attaches the blocks with `AssistantMessage.withThinking`, then `ThinkingReplay.bindOrigin`.
- [ ] **Step 4: Run** `sbt openai/test`. Expected: all pass.
- [ ] **Step 5: Commit** `feat(openai): a Responses reply's reasoning items are kept on the assistant message (#2900)`.

### Task 3: Send the reasoning back

**Files:**
- Modify: `OpenAIResponsesRequest.scala` (`Settings`, `inputItems`), `OpenAIClient.scala` (passes the origin)
- Test: `OpenAIResponsesWireSpec.scala`; create `modules/openai/src/test/scala/org/llm4s/llmconnect/provider/OpenAIResponsesReplaySpec.scala`

**Interfaces:**
- Consumes: `OpenAIReasoningItems.stored` (Task 2); `ThinkingReplay.replayableOrigin(origin: ReplayOrigin, messages: Seq[Message]): Seq[Message]`.
- Produces: `Settings` gains `origin: ReplayOrigin` (last field). `build` applies `replayableOrigin` to the conversation's messages before mapping them.

An assistant message's items, in order: its text message (when it has text); then for each tool call, the stored reasoning items whose `before` is that call's id, then the `function_call`. Stored items with no `before` go first, before the text. A stored item whose `before` names a call the message no longer has is not sent. `ResponseInputItem.ofReasoning(item)` sends the item as stored.

- [ ] **Step 1: Write the failing tests** in `OpenAIResponsesReplaySpec` (a two-request scenario over `LocalProviderTestServer`: the first reply is served, its `completion.message` is put into the next conversation, and the second request's body is captured):

```scala
"A thread on the Responses API" should "send a turn's reasoning items back, each before the call it came with" in {
  // reply 1 output: reasoningItem("rs_1"), functionCall("call_1", "get_weather", ...), reasoningItem("rs_2"), functionCall("call_2", "get_time", ...)
  // request 2: [user, <completion.message>, tool(call_1), tool(call_2)]
  types(input) shouldBe Seq("user", "reasoning", "function_call", "reasoning", "function_call", "function_call_output", "function_call_output")
  input(1)("id").str shouldBe "rs_1"
  input(1)("encrypted_content").str shouldBe "enc-rs_1"
  input(3)("id").str shouldBe "rs_2"
  input(2).obj.keySet should not contain "id"   // the function_call still carries no item id
}

it should "send a reasoning item that came before the answer's text ahead of that text" in {
  // reply 1 output: reasoningItem("rs_1"), message("It is 18C.") ; request 2: [user, <message>, user]
  types(input) shouldBe Seq("user", "reasoning", "assistant", "user")
}

it should "send the same request PR 1 sent for a thread with no reasoning" in {
  // an assistant message built by hand with text and a tool call, no thinking: no "reasoning" item
}

it should "not send reasoning for a turn whose content or tool calls were changed" in {
  // completion.message.withContent("edited")  and  .withToolCalls(Seq.empty): no "reasoning" item in request 2
}

it should "not send reasoning to another model" in {
  // message from a gpt-6.1-terra client, request 2 from a gpt-5.6-luna client: no "reasoning" item
}

it should "not send a reasoning item whose call is gone, and still send the request" in {
  // an AssistantMessage bound by hand: thinking = the two opaque blocks, toolCalls = only call_2, binding from ThinkingReplay.bindOrigin
  types(input).count(_ == "reasoning") shouldBe 1
  input(firstReasoning)("id").str shouldBe "rs_2"
}

it should "never send another provider's sealed thinking as a reasoning item" in {
  // AssistantMessage with Opaque(ProviderId("gemini"), ...) and a signed ThinkingBlock.Text, bound to this origin by hand
  types(input) should not contain "reasoning"
}

it should "skip an openai opaque block it cannot read" in {
  // Opaque(ProviderId("openai"), "not json"), bound to this origin: request 2 is sent, with no "reasoning" item
}

it should "send back reasoning from a stored message" in {
  // write(completion.message) with the message's upickle writer, read it back, continue the thread with the read message:
  // the second request's reasoning items equal those of the unstored case
}

it should "keep a stored summary out of the request" in {
  // reply 1: reasoningItem("rs_1", summary = Seq("Thought.")), message("hi"): request 2 has one reasoning item, whose own
  // "summary" is the array OpenAI returned, and no input item whose content is "Thought."
}
```

And in `OpenAIClientWireSpec` (the Chat Completions spec): a conversation whose assistant message carries an `openai` opaque block, sent by a client with `api` unset, posts to `/chat/completions` with no reasoning in the body and succeeds.

- [ ] **Step 2: Run and see them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `sbt scalafmtAll`, then `sbt "openai/test" "openaiCompatible/test" stabilityTierCheck mimaReportBinaryIssues`. Expected: all pass, no `[error]`.
- [ ] **Step 5: Commit** `feat(openai): a thread on the Responses API sends its reasoning back with each request (#2900)`.

### Task 4: Live verification and release notes (needs `OPENAI_API_KEY`)

**Files:**
- Modify: `modules/it/src/test/scala/org/llm4s/llmconnect/smoke/OpenAISmokeSpec.scala`
- Modify: `CHANGELOG.md` (edit #2900's existing entry under `### Providers`; do not add one), the spec

- [ ] **Step 1: Add the cases**, on `gpt-6.1-terra` and `gpt-5.6-luna` with `api = "responses"`, in the style of PR 1's Responses cases (cancel, not fail, when the model makes no tool call):

```scala
"finish a tool loop that sends its encrypted reasoning back" in {
  // turn 1 at effort low with the calculation prompt and the tool
  firstOk.message.hasSealedThinking shouldBe true                 // clued with the thinking blocks' types
  // turn 2: [user, firstOk.message, one tool result per call]
  second.isRight shouldBe true                                    // clued with OpenAI's error
}

"finish the same loop after the turn was edited, without its reasoning" in {
  // turn 2 with firstOk.message.withContent(firstOk.message.content + " "): accepted
}

"report reasoning summary text when reasoningSummary is auto" in {
  // config.withReasoningSummary(OpenAIReasoningSummary.Auto)
  // if OpenAI answers 400 naming organisation verification: cancel with that message
  completion.thinking.exists(_.nonEmpty) shouldBe true
}
```

- [ ] **Step 2: Run** `sbt "it/testOnly org.llm4s.llmconnect.smoke.OpenAISmokeSpec"` with the key loaded from `.env` (never printed), capturing `*** FAILED`, `CANCELED` and `Tests:` lines with six lines of context. At most three runs.
- [ ] **Step 3: Record the results** in the spec's "Reasoning across turns", replacing the "To verify live in slice 2" bullet with what OpenAI did and the date: whether an interleaved reply was accepted back in order, whether a reasoning item was accepted with its `id` under `store: false`, and what the summary request returned. **If OpenAI rejects the replayed reasoning items, stop and report the exact error: do not work around it.** In "Carried into later slices", remove the slice 2 line.
- [ ] **Step 4: Edit the CHANGELOG entry** for #2900 so it also says the reasoning of a turn is kept across the thread's requests and that `reasoningSummary` asks for summary text. Keep it to two lines.
- [ ] **Step 5: Run** `sbt it/scalafmtAll it/itTierCheck` and `scripts/check-docs.sh`. Expected: all pass.
- [ ] **Step 6: Commit** `test(it): OpenAI reasoning kept across a tool loop on the Responses API, live (#2900)`.
