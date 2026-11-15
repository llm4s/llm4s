# Portable llm4s: message types and a chat client for Scala.js and Scala Native (spike)

**Measured:** 2026-11-11, against `main` @ d928be4f4 (Scala 3.7.1), Scala.js 1.22.0, Scala Native 0.5.12,
sbt-crossproject 1.4.0, sttp client4 4.0.27, Laminar 17.2.1, on macOS arm64.
**Question ([#2338](https://github.com/llm4s/llm4s/issues/2338)):** how much of llm4s can be portable to Scala.js
and Scala Native, and at what cost? Should llm4s ship it before 1.0, after, or not at all?
**Change to the published JVM API:** none. The prototype is a self-contained sbt build in
[`portable-spike/build`](portable-spike/build); the llm4s build never loads it.

## Short answer

**About 40% of core is portable now: the data types, tool schemas and errors, 67 of core's files, compile unchanged
for Scala.js and Scala Native.** Their upickle codecs write the same bytes on the JVM, Scala.js and Native. They
need two small stand-ins: core's Java annotations (`@Stable`, `@Experimental`) and the two slf4j calls in that set.
On that base, an OpenAI-compatible chat client over sttp4 (about 330 lines, streaming included) compiles for all
three platforms and passes the same offline tests on each. The JVM-only parts are the transport (`java.net.http`),
configuration (Typesafe Config, pureconfig), the tokenizer (jtokkit), and anything that blocks a thread.

**Recommendation: ship it after 1.0, as Experimental modules, and only when users ask for it. Do not ship it at
all if they do not.** Before 1.0, do nothing beyond keeping the record below up to date. The reasons are under
[Recommendation](#recommendation).

## What was built

| Piece | Where | What it shows |
|---|---|---|
| `portable` (JVM, JS, Native) | `build/build.sbt`, `build/portable/` | Compiles core's own files, listed in `portableCoreFiles`, and `llm4s-media` unchanged on all three platforms. It adds the client below. |
| `OpenAIChatProtocol`, `SseParser`, `PortableChatClient` | `build/portable/shared/src/main` | The chat-completions wire format as pure functions over core's `Conversation`, `CompletionOptions`, `Completion` and `StreamedChunk`. It has an incremental SSE parser, and a client generic in sttp's effect: `FetchBackend` (Future) on JS, `CurlBackend` (synchronous) on Native, any backend on the JVM. |
| Cross-platform specs | `build/portable/shared/src/test` | The same expected JSON strings on every platform: `CrossPlatformJsonSpec`, `OpenAIChatProtocolSpec`, `SseParserSpec`, and `PortableChatClientSpec` over sttp's in-memory stub backend. |
| Inventory probes | `InventoryProbeSpec` (shared), `NotNativeProbeSpec` (`js-jvm`), `NotJsProbeSpec` (`jvm-native`), `JvmOnlyProbeSpec` (`jvm`) | Each JDK-heavy entry point sits in the narrowest test directory whose platforms link and pass it, so the directory a probe is in records the result. |
| Laminar demo | `build/demos/laminar` | A page that streams a reply into the DOM as it arrives. It reads the fetch body with the same `StreamDecoder` and holds no API key. `FetchStreamingSpec` runs it on Node's own fetch, with a stubbed body split mid-event and mid-character. |
| Native CLI | `build/demos/cli/chat.scala` | `llm4s-chat [--base-url URL] [--model NAME] PROMPT...`, built with Scala CLI or `sbt cliDemo/nativeLink`. |

CI: [`.github/workflows/portable-spike.yml`](../../.github/workflows/portable-spike.yml) runs `test` on all three
platforms and links both demos. It runs only when the spike or the workflow changes, or when started by hand.
Until this recommendation is accepted, a change to core must not fail on a platform llm4s does not ship. The
workflow also writes the artifact sizes to the run summary.

Results: JVM 32 tests, Scala.js 26 (and 2 in the Laminar demo), Scala Native 28, all passing.

## 1. Inventory

### What compiles

The issue named four packages: `types`, `error`, `llmconnect.model` and `toolapi`. Compiling their 56 files pulls in
11 more through imports:

- `core/safety/ErrorMapper`, `core/safety/Safety`
- `reliability/RetryPolicy`
- `security/Secret`, `security/KnownSecrets`
- `util/Redaction`, `util/SecretPatterns`, `util/ExactMatcher`, `util/BoundedJson`, `util/DurationText`
- `llmconnect/config/EmbeddingModelConfig`

The 67 files hold 12.7k of core's 31.7k main lines. They depend on `llm4s-media`, upickle and cats-core, which all
have JS and Native builds. Compiling them took no change to any of them. They need:

| Need | Why | In the spike |
|---|---|---|
| `org.llm4s.annotation.Stable` / `Experimental` | They are Java `@interface`s with RUNTIME retention, which `StabilityAnnotationsSpec` checks by reflection. Scala.js and Native compile no Java. | Scala `StaticAnnotation` stand-ins in `portable/js-native`. `sbt stabilityTierCheck` reads source, so it would accept either. |
| `org.slf4j` | `LLMError.correlationId` reads `MDC.get`, and `KnownSecrets` logs one warning. slf4j-api has no JS or Native build. | A stand-in covering exactly those calls: no MDC, and the warning goes to stderr. |
| `java.time.Instant` | `Completion.created` and `ErrorRecovery` | scala-java-time 2.7.0 on JS and Native. sttp already brings it to JS. |

### What links and runs

The Scala.js and Native linkers check only the code a program reaches, so compiling is not enough. The probes reach
each JDK-heavy entry point:

| Entry point | JVM | Scala.js | Native | Blocker |
|---|---|---|---|---|
| Codecs: `Conversation`, every `Message`, `ContentPart` (with a media URL), `ToolCall`, `ReasoningEffort` | yes | yes | yes | |
| `ToolFunction`, `Schema`, `toOpenAITool`, `execute`; `CompletionOptions`, `Completion`, `StreamedChunk`, `TokenUsage`, `UsageSummary`, `ToolExecutionConfig` | yes | yes | yes | |
| `LLMError` and its cases; the `Message` factories; `ApiKey` / `Secret` | yes | yes | yes | |
| `ToolRegistry.execute` | yes | **no** | yes | It reaches `CancelledError` (`ClosedByInterruptException`) and `Await`, which Scala.js cannot block on. |
| `CancelledError.isCancellation`, `ErrorRecovery.recoverWithBackoff` | yes | **no** | yes | `ClosedByInterruptException`, `Thread.sleep` |
| `ArtifactKey.generate`, `SemanticBlockId.generate` | yes | yes | **no** | `UUID.randomUUID` needs `SecureRandom`, which Native 0.5.12 lacks. |
| `ArtifactKey.fromContent` | yes | **no** | **no** | `MessageDigest` |
| `Redaction.redact` | yes | **fails at run time** | **no** | On JS, its patterns need look-behind (ES2018, which this build sets) and use an embedded flag mid-pattern, which JS RegExp rejects. On Native, `URLEncoder.encode(String, Charset)` is missing. |
| `DefaultErrorMapper` | yes | **no** | **no** | It reaches `Redaction`. On JS, also `InterruptedIOException`, `ConnectException` and `Matcher.useTransparentBounds`. |

So the vocabulary a frontend or CLI needs is portable: messages, options, completions, chunks, tool calls, tool
schemas and errors. What is not portable is code that either blocks (`ToolRegistry`, `ErrorRecovery`) or scans
text with JDK regex features (`Redaction`, `DefaultErrorMapper`). A portable module would keep those on the JVM,
or give them a platform-specific implementation.

Two gaps belong to the API rather than the platforms:

- **`Completion`, `StreamedChunk`, `TokenUsage` and `CompletionOptions` have no upickle codec.** Only the
  conversation side has one. A shared protocol for chat UIs, where a JVM backend sends completions to a Scala.js
  frontend, needs codecs for these types.
- **`ToolRegistry` mixes the registry with blocking execution.** A portable registry would expose only
  `executeAsync`, with the blocking variants on the JVM side.

### The rest of core

The rest of core is JVM-bound by design:

- `http` (`Llm4sHttpClient` on `java.net.http`)
- `config` (Typesafe Config and pureconfig: about a dozen loaders)
- `context/tokens` (jtokkit)
- the provider SPI (`ServiceLoader` with config)
- `trace` and `metrics` (slf4j)
- `reliability` (threads, `TokenBucket`)

None of it is needed to call a model from a browser or a CLI. The portable client replaces the transport and takes
its settings as arguments.

## 2. The portable client

- **sttp4 4.1.x cannot be used with Scala 3.7.1.** It is built with Scala 3.9, and 3.7.1 cannot read its TASTy, so
  the spike pins 4.0.27. Shipping on sttp4 ties llm4s's Scala upgrades to sttp's.
- **Streaming is not portable through sttp4 alone.** Its incremental bodies need a streaming capability (fs2, ZIO
  or Pekko), and none covers all three platforms. `FetchBackend` and `CurlBackend` read the body whole. So
  `PortableChatClient.streamComplete` delivers core's `StreamedChunk`s once the reply ends. The browser demo reads
  the fetch `ReadableStream` itself and feeds the same `StreamDecoder`, about 40 lines of Scala.js. A Native
  equivalent would need curl's write callback.
- **Native needs system libraries.** sttp links libcurl and libidn2. In CI that is
  `apt-get install libcurl4-openssl-dev libidn2-dev`. On macOS it is `brew install libidn2`, because macOS ships
  libcurl.
- The protocol code (`OpenAIChatProtocol`) is pure and platform-free. A tool call's continuation deltas carry only
  their `index`, and the decoder resolves them to the call's id, the rule `OpenAICompatibleClient.StreamToolCalls`
  follows on the JVM.

## 3. Sizes and start-up

| Artifact | Bytes | gzip |
|---|---|---|
| Laminar demo, `fullLinkJS` (Closure) | 2,292,079 | 344,795 |
| A Scala.js program that only writes a `Conversation` as JSON | 1,488,138 | 227,328 |
| Laminar "hello" alone, for scale | 300,269 | 46,124 |
| Native CLI, `sbt cliDemo/nativeLink` (release-fast, immix, no LTO) | 8,157,120 | 1,886,990 |
| The same, stripped | 5,166,608 | |
| Native CLI, Scala CLI `--native-mode release-fast` | 7,974,736 | |

The Scala.js weight is mostly the libraries, not llm4s. In the unoptimised output of the types-only program,
llm4s's own classes are about 8%. The rest is Scala collections, upickle, and the `java.math` and `java.nio` parts
of the Scala.js javalib. The Native CLI starts in about 30 ms: 20 runs took 0.65 s wall time.

## 4. Running the demos against a local Ollama

The demos have been run here against a local OpenAI-compatible server that streams canned SSE, and against the
offline stubs in the specs. They have not been run against a live Ollama, because none was installed on the machine
that measured this.

Both demos default to `http://localhost:11434/v1` and model `llama3`. Run `ollama pull llama3` first, then start
Ollama if it is not already running. From `docs/design/portable-spike/build`:

```
# Laminar page: link it, serve the demo directory, open http://localhost:8000
sbt laminarDemo/fullLinkJS
cd demos/laminar && python3 -m http.server 8000
```

Ollama accepts browser requests from `localhost` origins by default. If the browser reports a CORS error, restart
Ollama with `OLLAMA_ORIGINS=http://localhost:8000`.

The page sends no API key and has no field for one. In production, point it at your own backend, which holds the
key and forwards to the vendor. Never ship a vendor key to a browser.

```
# Native CLI through sbt
sbt cliDemo/nativeLink
demos/cli/target/scala-3.7.1/clidemo "Why is the sky blue?"

# or through Scala CLI, from the locally published prototype
sbt portableNative/publishLocal
scala-cli --power package --native demos/cli/chat.scala -o llm4s-chat
./llm4s-chat --model llama3 "Why is the sky blue?"
```

On Apple silicon, add `--native-linking -L/opt/homebrew/lib` for Homebrew's libidn2. An x86_64 Scala CLI running
under Rosetta also needs an arm64 clang: pass `--native-clang` a script that runs `arch -arm64 /usr/bin/clang "$@"`.

## Recommendation

**After 1.0, as Experimental modules, if there is demand. Not before 1.0. Not at all if demand never comes.**

What shipping it means:

- **A `llm4s-model` module**, cross-built for JVM, JS and Native. It would hold the 67 files minus the JVM-only
  entry points above, and `llm4s-core` would depend on it, keeping the same packages. The source changes are small:
  - the annotations, as JVM Java plus JS/Native Scala in platform directories;
  - `LLMError.correlationId` and `KnownSecrets`'s warning through a small platform hook, not slf4j;
  - the blocking parts of `ToolRegistry`, `ErrorRecovery` and `CancelledError` kept on the JVM side;
  - `UUID.randomUUID` and `MessageDigest` replaced, or kept JVM-only.

  None of these changes a public signature. Separately, the four response types need the codecs listed above.
- **A `llm4s-portable-client` module** (sttp4) on top of it, with incremental streaming written per platform.

Its cost:

- **The build.** Three sbt plugins in the root build. Two more platforms for every test of the moved types. A
  Native job that installs clang, libcurl and libidn2. Here a clean `test` of all three platforms took 48 s on a laptop, and linking both demos 18 s more.
- **Binary compatibility.** Each module publishes three artifacts. MiMa checks only the JVM artifact. Scala.js IR
  and Scala Native NIR compatibility follow from Scala.js 1.x and Native 0.5.x, so a Native 0.6 means a new artifact
  line.
- **Maintenance.**
  - Every change to the moved files must link on all three platforms, which forbids a JDK API the two javalibs lack
    (see the blockers table).
  - JDK regex code must behave the same under JavaScript's and Native's regex engines.
  - The sttp4 pin couples Scala upgrades.
  - scala-java-time is the largest single cost in a browser bundle that uses `Completion`.

Why after 1.0, not before:

- The 1.0 work (#1281) is about the JVM API, and none of this changes it.
- The portable set's blockers are few and local, so the split can be made whenever it is wanted, from this record.
- Whether to make it is a question of demand, which the 1.0 release is the way to measure. Until then, the spike
  costs one workflow that runs only when its own files change.

If the recommendation is accepted, the follow-up is:

1. Add the four response codecs: a JVM-visible API change, worth its own issue.
2. Split `llm4s-model` out of core, with the platform-specific parts listed above.
3. Write the portable client with per-platform streaming.
4. Run the two demos against a live Ollama, and record the result here.
