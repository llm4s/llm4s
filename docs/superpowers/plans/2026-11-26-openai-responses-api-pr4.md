# OpenAI Responses API, PR 4 (the default for api.openai.com) Implementation Plan

> **Overtaken during execution** in several places (routing rule 6, the Scalafix umbrellas, the registry data patches): the design doc, `docs/superpowers/specs/2026-11-26-openai-responses-api-design.md`, records what was built.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An `openai` client that points at api.openai.com uses the Responses API without being told to, the model registry decides the exceptions, every entry point can pin either API, and a thread can be told not to keep reasoning.

**Architecture:** `llm4s-core` learns two facts from the LiteLLM model file it already bundles (`ModelMode.Responses`, `ModelMetadata.supportedEndpoints`). A `private[provider]` function, `OpenAIApiRouting.resolve`, replaces the three-line match in `OpenAIClient` and is the only place the choice is made. `OpenAIConfig` gains `keepReasoning`. The Java builder, the Kotlin DSL and the Spring flat keys each gain the two settings, since none of them goes through a named section.

**Tech Stack:** Scala 3.7.1, sbt, ScalaTest, upickle, `com.openai:openai-java` 4.69.3, Java 21 (facade), Kotlin/Gradle (`modules/kotlin-api`, built outside sbt), Scalafix (`modules/scalafix-rules`).

**Spec:** `docs/superpowers/specs/2026-11-26-openai-responses-api-design.md`, slice 4 of "Slices". Read "Routing", "API surface", "Docs and release notes" and the slice-4 items of "Carried into later slices" before any task.

## Decisions this plan makes (recorded in the spec by Task 6)

1. **Reasoning opt-out**: `OpenAIConfig.keepReasoning: Boolean`, default `true`; section key `keepReasoning`. When `false` the request sends no `include`, a reply's reasoning items are not stored as opaque blocks (summary text is still kept), and stored `openai` opaque blocks are not sent back. It is the size knob and the recovery hatch.
2. **A rejected encrypted reasoning item** is an error that names the cause and the remedy; the client does not retry on its own. A silent retry would hide that a thread's reasoning has gone and would double the request.
3. **Positional patterns**: a growth-safe (private-constructor) class gaining a field is not a source break under the 1.x promise; the policy says so, the migration guide notes each one, and `Llm4sMigrateFrom_1_0` rewrites them, as the 0.x rules did.
4. **Not in this PR** (each gets an issue reference in the spec's follow-up list, to be filed by the maintainer): the Java facade and Spring AI message views dropping thinking blocks on a round trip; `DeterministicCompressor` unsealing tool-call-only messages; Azure on Responses; hosted tools.

## Global Constraints

- Start from `main` after PR 3 has merged.
- `llm4s-core`, `llm4s-openai` and `llm4s-openai-compatible` are frozen: `sbt mimaReportBinaryIssues` passes against 1.0.0 with no filter; `sbt stabilityTierCheck` passes.
- New public surface is exactly: `ModelMode.Responses`; `ModelMetadata.supportedEndpoints` with `withSupportedEndpoints` and the previous 8-parameter `apply` kept as a forwarding overload **without defaults**; `OpenAIConfig.keepReasoning` with `withKeepReasoning(Boolean)`, `OpenAIConfig.KeepReasoningKey` / `KeepReasoningConfigKey`; the Java, Kotlin and Spring settings named in Task 4. Everything else is `private[provider]` or `private[llm4s]`.
- `ModelMetadata` has a upickle `macroRW`: the new constructor field keeps a default (`Seq.empty`), because the reader fills missing keys from it.
- Routing is decided once, when the client is built, by `OpenAIApiRouting.resolve`, and logged at INFO with the reason. It is never decided per request.
- An explicit `api` always wins. Azure and Requesty stay on Chat Completions.
- With `api = "chat-completions"` the wire is byte-for-byte what 1.0.0 sent.
- No vendor name or model list for routing in `llm4s-openai`: the two per-model facts come from the registry.
- Durations are `FiniteDuration`. No `try`/`catch`: `Result[A]`. No infix method calls. No `sys.env` / `System.getenv` outside `modules/it`.
- Docs: every page keeps a 50 to 170 character `description`; a page that links an llm4s issue declares it in front matter; `scripts/check-docs.sh` passes. CHANGELOG: edit #2900's existing entry under `### Providers` into a **Changed** entry; never read the file whole.
- Every commit: `git commit -s`, message ending `Co-Authored-By: Claude Fable 5.2 <noreply@anthropic.com>`.
- Filter sbt output: `2>&1 | grep -E '\[error\]|\*\*\* FAILED|Tests: |Failed tests'`.

## Review Focus

Each line has its test in the task named.

1. **A user's registry override file** that marks a model `mode: responses` or lists `supported_endpoints`. Routing follows the override, not the bundled file. (Task 2)
2. **A base URL that is api.openai.com written differently**: a trailing slash, an upper-case host, an explicit `:443`. Still the default. `https://api.openai.com.evil.example/v1` and `http://api.openai.com/v1` are not. (Task 2)
3. **A model the registry does not know** (a fine-tune id, tomorrow's model) on the default base URL. Responses, with the reason logged. (Task 2)
4. **A thread stored with reasoning, continued with `keepReasoning = false`.** No reasoning item is sent; the request succeeds. (Task 3)
5. **A registry entry written by 1.0.0** (no `supportedEndpoints` key) read by this version, and one written by this version read by 1.0.0's reader shape. Both read. (Task 1)

---

### Task 1: The registry knows which API serves a model

**Files:**
- Modify: `modules/core/src/main/scala/org/llm4s/model/ModelMetadata.scala`
- Test: `modules/core/src/test/scala/org/llm4s/model/ModelMetadataSpec.scala`

**Interfaces:**
- Produces:

```scala
// object ModelMode
case object Responses extends ModelMode { val name = "responses" }   // fromString("responses") => Responses

// ModelMetadata: a ninth constructor field, last
supportedEndpoints: Seq[String] = Seq.empty
def withSupportedEndpoints(supportedEndpoints: Seq[String]): ModelMetadata

// companion: the 9-parameter apply with defaults, and the previous 8-parameter apply kept WITHOUT defaults, forwarding
def apply(modelId: ModelName, provider: ProviderId, mode: ModelMode, maxInputTokens: Option[Int], maxOutputTokens: Option[Int],
          capabilities: ModelCapabilities, pricing: ModelPricing, deprecationDate: Option[LocalDate]): ModelMetadata
```

`fromJson` reads `supported_endpoints` (an array of strings; absent, null or not an array gives `Seq.empty`).

- [ ] **Step 1: Write the failing tests:**

```scala
"ModelMode.fromString" should "read responses" in {
  ModelMode.fromString("responses") shouldBe ModelMode.Responses
  ModelMode.fromString("RESPONSES") shouldBe ModelMode.Responses
  ModelMode.Responses.name shouldBe "responses"
  upickle.default.read[ModelMode](upickle.default.write[ModelMode](ModelMode.Responses)) shouldBe ModelMode.Responses
}

"ModelMetadata.fromJson" should "read supported_endpoints, and none when the entry lists none" in {
  // {"litellm_provider":"openai","mode":"chat","supported_endpoints":["/v1/chat/completions","/v1/responses"]}
  meta.supportedEndpoints shouldBe Seq("/v1/chat/completions", "/v1/responses")
  // without the key, with null, and with a string instead of an array: Seq.empty
}

it should "read a responses-only entry" in {
  // {"litellm_provider":"openai","mode":"responses","supported_endpoints":["/v1/responses"]}
  meta.mode shouldBe ModelMode.Responses
}

"The bundled registry" should "mark the responses-only OpenAI models and know which endpoints serve the current ones" in {
  // ModelRegistryTestSupport.defaultService()
  lookup("o1-pro").mode shouldBe ModelMode.Responses
  lookup("gpt-6.1-terra").mode shouldBe ModelMode.Chat
  lookup("gpt-6.1-terra").supportedEndpoints should contain("/v1/responses")
  lookup("gpt-audio-1.5").supportedEndpoints should not contain "/v1/responses"
}

"ModelMetadata's stored form" should "read an entry written before supportedEndpoints existed" in {
  // a literal JSON string as 1.0.0's writer produced it (write one from the current writer, delete the key, paste it)
  read[ModelMetadata](literal).supportedEndpoints shouldBe Seq.empty
}

it should "still be built by the 8-argument apply" in {
  ModelMetadata(id, provider, ModelMode.Chat, None, None, ModelCapabilities(), ModelPricing(), None).supportedEndpoints shouldBe Seq.empty
}
```

- [ ] **Step 2: Run and see them fail.** `sbt "core/testOnly *ModelMetadataSpec"`.
- [ ] **Step 3: Implement.** Then `grep -rn 'ModelMode.Unknown\|case ModelMode\.' modules --include='*.scala'` and check each site: an entry that was `Unknown` because its mode was `responses` (OpenAI's, and other providers') is now `Responses`. Any code that selects "chat models" by `mode == ModelMode.Chat` now has to decide about `Responses`; list each site and what you did in your report.
- [ ] **Step 4: Run** `sbt core/test stabilityTierCheck mimaReportBinaryIssues`. Expected: all pass, no `[error]`.
- [ ] **Step 5: Commit** `feat(core): the model registry reads mode responses and supported_endpoints (#2900)`.

### Task 2: Routing

**Files:**
- Create: `modules/openai/src/main/scala/org/llm4s/llmconnect/provider/OpenAIApiRouting.scala`
- Modify: `modules/openai/src/main/scala/org/llm4s/llmconnect/provider/OpenAIClient.scala` (the `api` resolution, lines 163-172, becomes one call)
- Test: create `modules/openai/src/test/scala/org/llm4s/llmconnect/provider/OpenAIApiRoutingSpec.scala`; `OpenAIResponsesWireSpec.scala`

**Interfaces:**
- Consumes: Task 1.
- Produces:

```scala
private[provider] object OpenAIApiRouting:
  final case class Choice(api: OpenAIApi, reason: String)
  def resolve(config: ProviderConfig, provider: ProviderId, model: String, registry: ModelRegistryService): Choice
  /** Whether `baseUrl` is OpenAI's own API: https, host api.openai.com (any case), port absent or 443. */
  def isOpenAI(baseUrl: BaseUrl): Boolean
```

Rules, first match wins:

| # | When | API |
|---|---|---|
| 1 | the config is not an `OpenAIConfig` (Azure) | Chat Completions |
| 2 | `config.api` is set | as set |
| 3 | `provider` is not `openai` (Requesty, anything else built on `OpenAIConfig`) | Chat Completions |
| 4 | the registry's entry for the model has `mode == ModelMode.Responses` | Responses |
| 5 | `isOpenAI(config.baseUrl)` is false | Chat Completions |
| 6 | the registry's entry has non-empty `supportedEndpoints` without `/v1/responses` | Chat Completions |
| 7 | otherwise | Responses |

The registry lookup is the one `OpenAIReasoning` uses (`registry.lookup(ModelName(model))`, kept only when the entry's provider is an OpenAI one). Each `reason` says which rule applied, in words a user reading the log would understand.

- [ ] **Step 1: Write the failing tests** in `OpenAIApiRoutingSpec`, one per rule and per Review Focus line (a `ModelRegistryService` built over an override file for the override case; find how existing specs build one):

```scala
"OpenAIApiRouting" should "use Responses for an openai client on OpenAI's own base URL" in { /* gpt-4o, default base URL: Responses */ }
it should "use Chat Completions for an openai client on any other base URL" in { /* https://gateway.example/v1 */ }
it should "do what the config says, either way" in { /* withApi(ChatCompletions) on the default URL; withApi(Responses) on a gateway; withApi(Responses) for provider requesty */ }
it should "use Chat Completions for Azure and for Requesty" in { }
it should "use Responses for a responses-only model wherever the client points" in { /* o1-pro on https://gateway.example/v1 */ }
it should "use Chat Completions for a model whose endpoints do not include /v1/responses" in { /* gpt-audio-1.5 on the default URL */ }
it should "use Responses for a model the registry does not know" in { /* ft:gpt-4o:acme::abc on the default URL */ }
it should "follow a registry override" in { /* an override marking gpt-4o mode responses: Responses on a gateway */ }
it should "recognise OpenAI's base URL however it is written" in {
  Seq("https://api.openai.com/v1", "https://api.openai.com/v1/", "https://API.OpenAI.com/v1", "https://api.openai.com:443/v1")
    .foreach(u => OpenAIApiRouting.isOpenAI(BaseUrl(u)) shouldBe true)
  Seq("https://api.openai.com.evil.example/v1", "http://api.openai.com/v1", "https://api.openai.com:8443/v1",
      "https://user@api.openai.com/v1", "not a url", "")
    .foreach(u => OpenAIApiRouting.isOpenAI(BaseUrl(u)) shouldBe false)
}
it should "say which rule applied" in { /* each Choice.reason is non-empty and names the base URL, the model's mode, or the config */ }
```

- [ ] **Step 2: Run and see them fail.**
- [ ] **Step 3: Implement.** `isOpenAI` parses with `java.net.URI` inside `Try`; a URL with user info is not OpenAI's.
- [ ] **Step 4: Fix what the flip breaks.** `sbt openai/test`. Specs that build a client on the default base URL (`OpenAIRoutingTest`, `OpenAIInCodeConfigSpec`, `OpenAIWorkloadIdentitySpec`, `OpenAIApiKeyFileSpec`, `DocumentedProviderConfigSpec`) and then assert a Chat Completions request pin `withApi(OpenAIApi.ChatCompletions)` / `api = "chat-completions"`; where a spec asserts something that should hold on both APIs (a credential sent, a key file re-read), run it on both. Do not rewrite a Chat Completions spec into a Responses one. List each changed spec in your report.
- [ ] **Step 5: Run** `sbt openai/test openaiCompatible/test`. Expected: all pass.
- [ ] **Step 6: Commit** `feat(openai)!: an openai client on api.openai.com uses the Responses API (#2900)`.

### Task 3: `keepReasoning`, and a rejected reasoning item that says what happened

**Files:**
- Modify: `modules/openai-compatible/src/main/scala/org/llm4s/llmconnect/config/OpenAIConfig.scala`, `modules/openai/src/main/scala/org/llm4s/llmconnect/provider/OpenAIProvider.scala`, `OpenAIResponsesRequest.scala` (`Settings`), `OpenAIResponsesReply.scala`, `OpenAIClient.scala`
- Test: `OpenAIConfigSpec.scala`, `OpenAINamedProviderSpec.scala`, `OpenAIResponsesReplaySpec.scala`, `OpenAIResponsesWireSpec.scala`

**Interfaces:**
- Produces: `OpenAIConfig.keepReasoning: Boolean` (constructor field after `reasoningSummary`, `true` from both factories), `withKeepReasoning(keepReasoning: Boolean): OpenAIConfig`, `OpenAIConfig.KeepReasoningKey = "keepReasoning"`, `KeepReasoningConfigKey` (optional; `true` / `false`, anything else fails the section load naming the key). `Settings` gains `keepReasoning: Boolean`.

- [ ] **Step 1: Write the failing tests:**

```scala
// OpenAIConfigSpec
it should "keep reasoning unless told not to" in {
  OpenAIConfig(Secret("sk-test"), ModelName("gpt-6.1-terra")).keepReasoning shouldBe true
  config.withKeepReasoning(false).keepReasoning shouldBe false
  config.withKeepReasoning(false).toString should include("keepReasoning=false")   // shown only when false
}
// OpenAINamedProviderSpec: keepReasoning = false loads; keepReasoning = "sometimes" fails naming the section and the key

// OpenAIResponsesWireSpec / OpenAIResponsesReplaySpec, client config .withKeepReasoning(false), gpt-6.1-terra
it should "not ask for encrypted reasoning when keepReasoning is false" in { body.obj.keySet should not contain "include" }
it should "not keep a reply's reasoning items when keepReasoning is false, but keep its summary text" in {
  // reply: reasoningItem("rs_1", summary = Seq("Thought.")), functionCall(...)
  completion.message.hasSealedThinking shouldBe false
  completion.thinking shouldBe Some("Thought.")
}
it should "not send stored reasoning back when keepReasoning is false" in {
  // a message stored by a keepReasoning = true client (sealed, bound), continued on a keepReasoning = false client of the same model
  types(input) should not contain "reasoning"
}
it should "still send the effort and the summary setting when keepReasoning is false" in { }

// a rejected reasoning item
it should "say what to do when OpenAI rejects a replayed reasoning item" in {
  // the server answers 400 {"error":{"message":"The encrypted content ... could not be verified.","type":"invalid_request_error","code":"invalid_encrypted_content"}}
  // to a request that carried a reasoning item
  error.message should include("keepReasoning")
  error.message should include("could not be verified")          // OpenAI's own message is kept
  // the error type is the one a 400 has today; and a 400 with another code, or this code on a request that carried
  // no reasoning item, is reported exactly as today
}
```

- [ ] **Step 2: Run and see them fail.**
- [ ] **Step 3: Implement.** The hint is added where the Responses call's error is mapped, only when the request carried at least one reasoning item and the error's code is `invalid_encrypted_content` or its message mentions encrypted content. Wording: what happened (OpenAI rejected reasoning stored on this thread), the likely causes (a different key or organisation, a model alias that moved, an old thread), and the two remedies (`keepReasoning = false`, or continuing the thread from an edited turn).
- [ ] **Step 4: Run** `sbt openai/test openaiCompatible/test stabilityTierCheck mimaReportBinaryIssues`. Expected: all pass.
- [ ] **Step 5: Commit** `feat(openai): keepReasoning = false keeps reasoning out of a thread; a rejected reasoning item says why (#2900)`.

### Task 4: Every entry point can choose

**Files:**
- Modify: `modules/java-api/src/main/java/org/llm4s/javaapi/OpenAiClientBuilder.java`, `ClientSettings.java`, `modules/java-api/src/main/scala/org/llm4s/javaapi/ClientBuilds.scala` (`openAiConfig`)
- Modify: `modules/kotlin-api/src/main/kotlin/org/llm4s/kotlin/ClientDsl.kt` (`OpenAiSpec`)
- Modify: `modules/spring-boot-starter/src/main/scala/org/llm4s/spring/ProviderConfigParser.scala` (`parseOpenAI`) and its properties metadata if the module ships one (`grep -rn 'reserve-completion' modules/spring-boot-starter`)
- Test: the existing spec for each (`grep -rln 'OpenAiClientBuilder\|parseOpenAI' modules/*/src/test`)

**Interfaces:**
- Produces:

```java
// OpenAiClientBuilder
public OpenAiClientBuilder api(String api);                    // "responses" | "chat-completions"; anything else fails build() naming both
public OpenAiClientBuilder keepReasoning(boolean keepReasoning);
public OpenAiClientBuilder reasoningSummary(String summary);   // "auto" | "concise" | "detailed"
```

```kotlin
// OpenAiSpec
var api: String? = null
var keepReasoning: Boolean? = null
var reasoningSummary: String? = null
```

Spring flat keys: `llm4s.api`, `llm4s.keep-reasoning`, `llm4s.reasoning-summary`, read by `parseOpenAI` only, parsed with `OpenAIApi.parse` / `OpenAIReasoningSummary.parse`; a bad value fails as a bad `llm4s.api-key` does today. Named sections (`llm4s.providers.<name>.api`, Spring and Quarkus) already reach `OpenAIProvider.buildConfig`: add one Spring test that proves `llm4s.providers.main.api=chat-completions` and `...keep-reasoning=false` arrive.

- [ ] **Step 1: Write the failing tests**: for each entry point, unset gives a config with `api == None` and `keepReasoning == true`; each value arrives on the built `OpenAIConfig`; a bad value is a failure that names the two valid values.
- [ ] **Step 2: Run and see them fail.** `sbt "javaApi/test" "springBootStarter/test"` (project ids from `build.sbt`).
- [ ] **Step 3: Implement.** `ProviderConfigParser` uses `OpenAIConfig.DEFAULT_BASE_URL` instead of its string literal while you are there.
- [ ] **Step 4: Kotlin.** `modules/kotlin-api` is a Gradle build sbt never compiles. Follow its README to build and test it against the local artifacts (`sbt publishLocal` or the documented equivalent, then `./gradlew test` in the module). If it cannot be built in this environment, say so plainly in your report with the command and its error: do not claim the Kotlin change is verified.
- [ ] **Step 5: Run** the sbt tests of both modules. Expected: all pass.
- [ ] **Step 6: Commit** `feat(java-api,kotlin-api,spring): the OpenAI builders choose the API and whether reasoning is kept (#2900)`.

### Task 5: The migration rule

**Files:**
- Modify: `modules/scalafix-rules/rules/src/main/scala/org/llm4s/scalafix/Llm4sMigrateFrom_1_0.scala`, `PositionalPatternsGainFields.scala`; the rule's `input-*` / `output` fixtures and its test

- [ ] **Step 1: Write the failing fixture**: under the 1.0 input directory (create it as the 0.6 one is laid out), a file matching `OpenAIConfig` positionally with 11 patterns and `ModelMetadata` with 8; the expected output has 14 (`api`, `reasoningSummary`, `keepReasoning`) and 9.
- [ ] **Step 2: Run** the module's tests (`sbt "scalafixTests/test"`; project id from `build.sbt`) and see the new fixture fail.
- [ ] **Step 3: Implement**: an arity map for 1.0 (`OpenAIConfig` 11 to 14, `ModelMetadata` 8 to 9) and `PositionalPatternsGainFields` wired into `Llm4sMigrateFrom_1_0` with it. The 0.5 and 0.6 umbrellas and their maps are not touched.
- [ ] **Step 4: Run** the module's tests. Expected: all pass.
- [ ] **Step 5: Commit** `feat(scalafix): Llm4sMigrateFrom_1_0 widens positional patterns on OpenAIConfig and ModelMetadata (#2900)`.

### Task 6: Docs, policy and release notes

**Files:**
- Modify: `docs/guide/providers.md` ("## OpenAI", lines 114-224; "## Provider-specific keys", the table near 484), `docs/getting-started/configuration.md` ("### Section keys", near 394), `docs/reference/compatibility-policy.md` ("## Sealed types that may gain a case", "## What the promise covers"), `docs/reference/migration.md` (a new `##` section before the first existing one, near line 135), `docs/reference/scalafix.md` (the umbrella rule's section), `modules/openai/src/main/resources/reference.conf` (the commented examples, lines 22-50), `CHANGELOG.md`, the spec
- Modify: `modules/openai/src/test/scala/org/llm4s/config/DocumentedProviderConfigSpec.scala`

- [ ] **Step 1: `DocumentedProviderConfigSpec` first**: add the documented section with `api`, `reasoningSummary` and `keepReasoning` to its inline HOCON and assert each arrives on the config. Run it and see it pass (the keys exist) — it pins that the docs' example loads.
- [ ] **Step 2: `providers.md`, OpenAI section**: a subsection "Which API a section uses" with the seven routing rules as a table in the user's terms; "Reasoning with tools" (the configured effort is sent with tools on Responses; on Chat Completions a 5.6 / 6 / 6.1 model is sent `reasoning_effort: none`, reported as an adjustment); "Reasoning kept across a thread" (what is stored on the message, that stored threads and checkpoints grow by the encrypted item on each reasoning turn, `keepReasoning = false`, and what a rejected item's error says); what differs on Responses (`presencePenalty` / `frequencyPenalty` are reported `Dropped`; a `maxTokens` below 16 is sent as 16 and reported; `streamComplete` streams). The keys table gains `api`, `reasoningSummary`, `keepReasoning`.
- [ ] **Step 3: `configuration.md`**: the three keys in "Section keys", each one line, linking to the providers page.
- [ ] **Step 4: `compatibility-policy.md`**: `OpenAIApi` and `OpenAIReasoningSummary` join the "Models and configuration" row of the may-gain-a-case table. Under "What the promise covers" (the does-not-cover list): a positional pattern on a growth-safe class (one built with `apply` and `with*` setters, its constructor private) is not covered, because such a class gains fields in minor releases; each gain is listed in the migration guide and rewritten by `Llm4sMigrateFrom_1_0`.
- [ ] **Step 5: `migration.md`**: a section "An `openai` section on api.openai.com uses the Responses API (#2900)": who is affected (provider `openai`, default base URL, in a section, in code, through the Java, Kotlin and Spring entry points); what changes on the wire and in the exchange log; the two penalties; that reasoning is now kept on the thread; `api = "chat-completions"` and its three other spellings to keep 1.0.x behaviour; that a streamed chunk's id is now the response id (`resp_...`); that a failed reply's code is reported as a `ValidationError`, a `ContentFilteredError` or a rate-limit error (the table from the providers page); the positional patterns that gained fields and the Scalafix command.
- [ ] **Step 6: `scalafix.md`**: the 1.0 umbrella now runs one rule; say which.
- [ ] **Step 7: `reference.conf`**: one commented example section pinning `api = "chat-completions"`, and one line each for the two other keys.
- [ ] **Step 8: CHANGELOG**: turn #2900's entry under `### Providers` into one **Changed** entry of two lines (the default, the opt-out, links to the issue and the migration section). Remove the sentence in the `### Build & tooling` entry that says `Llm4sMigrateFrom_1_0` "runs no rule yet, since nothing has broken since 1.0.0", replacing it with what it now runs (`grep -n 'Llm4sMigrateFrom_1_0' CHANGELOG.md`).
- [ ] **Step 9: The spec**: record the four decisions at the top of this plan under "Decided" (marking them as the plan's calls, for the maintainer to confirm), and empty the slice-4 group of "Carried into later slices" except the items this plan names as not in this PR.
- [ ] **Step 10: Run** `scripts/check-docs.sh` and `sbt "openai/testOnly *DocumentedProviderConfigSpec" docsSourcesCheck`. Expected: all pass.
- [ ] **Step 11: Commit** `docs: the Responses API is the default for an openai section on api.openai.com (#2900)`.

### Task 7: Live verification (needs `OPENAI_API_KEY`)

**Files:**
- Modify: `modules/it/src/test/scala/org/llm4s/llmconnect/smoke/OpenAISmokeSpec.scala`, the spec

- [ ] **Step 1: Make the suite's default client the default**: the existing Responses cases drop their explicit `api` (they now prove the default); the existing Chat Completions tool cases pin `api = "chat-completions"`. Add:

```scala
"use the Responses API by default and Chat Completions when told to" in {
  // an exchange sink on each client: the default client's request body has "input" and "store"; the pinned one has "messages"
}

"finish a tool loop with keepReasoning false" in {             // gpt-6.1-terra
  firstOk.message.hasSealedThinking shouldBe false
  second.isRight shouldBe true
}

"answer after a tool result, with and without the reasoning replayed" in {   // gpt-5.6-luna, 5 loops each way
  // counts turn-2 replies that are empty (no text, no tool call) with keepReasoning true and with false, and reports both
  // counts with info(...). Asserts only that every turn 2 was accepted.
}
```

And two observations that assert nothing about the model, each logging what it saw with `info(...)`:

```scala
"report what an in-stream error looks like on the wire" in {
  // a streamed request OpenAI must reject after the stream opens, if one can be provoked cheaply (for example an image
  // part whose URL cannot be fetched, on a vision model); logs the mapped error's type and the exchange's raw events.
  // If OpenAI rejects it with an HTTP error before any event, log that and cancel: the question stays open.
}

"say whether a model marked for one-call streaming can stream on the Responses API" in {
  // the model OpenAIClientStreamingSpec uses for requiresFakeStreaming, if OpenAI still serves it: one streamed request
  // through a client built with the SDK transport directly is out of scope; instead log whether the registry still
  // lists the model and cancel with that note. Record the answer in the spec's follow-up list.
}
```

- [ ] **Step 2: Run** `sbt "it/testOnly org.llm4s.llmconnect.smoke.OpenAISmokeSpec"` with the key loaded from `.env` (never printed), capturing `*** FAILED`, `CANCELED`, `Tests:` and the `info` lines of the comparison and observation cases. At most three runs.
- [ ] **Step 3: Record in the spec**, dated: that the default client used Responses live, and the two empty-turn-2 counts out of five. **If the count with reasoning replayed is three or more out of five and without it is zero or one, stop and report: that is evidence the replay harms `gpt-5.6-luna`, and whether the default should keep reasoning is the maintainer's decision.**
- [ ] **Step 4: Run** `sbt it/scalafmtAll it/itTierCheck` and `scripts/check-docs.sh`. Expected: all pass.
- [ ] **Step 5: Commit** `test(it): the OpenAI smoke suite runs on the default API; keepReasoning live (#2900)`.
