# Migration Guide

## Slice 4 (PR 1): `ProviderKind` becomes `ProviderId`, `ProviderConfig` opens up

The first of the slice 4 changes ([#1131](https://github.com/llm4s/llm4s/issues/1131)). No SPI
yet - this only removes the two things that make a provider impossible to supply from outside
`llm4s-core`: a closed `enum` and a `sealed` trait. It is in the build but not yet in a release,
so nothing here affects `0.4.1` or earlier.

**This is a clean break: there is no deprecated `ProviderKind` shim.** A shim would have kept a
closed list of twelve providers inside the very module whose purpose is to remove it, and it
would only have half-worked - `case ProviderKind.OpenAI =>` and `def f(k: ProviderKind)` would
still compile, while `.values`, `.ordinal`, `.fromOrdinal` and exhaustivity would not. A clear
compile error beats a partly-working deprecated type.

### `ProviderKind` → `ProviderId`

```scala
// Before
enum ProviderKind:
  case OpenAI; case Anthropic; /* ... ten more */

// After
opaque type ProviderId = String
object ProviderId:
  def apply(raw: String): ProviderId = raw.trim.toLowerCase   // canonicalises
  extension (id: ProviderId) def asString: String = id
```

`ProviderId` is an **open vocabulary**, not an enumeration. Any string names a provider; whether
that provider can be resolved is answered at resolution time, by whatever is on the classpath -
which is the whole point. It stays `opaque` over `String`, so `Option[ProviderId]` and
`Map[ProviderName, ProviderId]` still do not box.

| Before | After |
|---|---|
| `ProviderKind.OpenAI` | `ProviderId("openai")` |
| `ProviderKind.fromString(s)` / `fromName(s)`, returning `Option` | `ProviderId(s)`, total |
| `kind.name` | `id.asString` |
| `kind.toString` → `"OpenAI"` | `id.asString` → `"openai"` |
| `ProviderKind.all` | no replacement - ask the thing that resolves providers, not the type |
| `ProviderKind.values` / `.ordinal` / `.fromOrdinal` / `.productPrefix` | no replacement |
| exhaustive `match` on `ProviderKind` | match on `id.asString`, with a default branch |

`asString` lives in `object ProviderId` rather than beside `ModelName.asString` and friends,
because every newtype in `ProviderModelTypes` erases to `String` and a second `asString` at that
level would be a double definition after erasure. Companion-scoped extensions resolve through the
opaque type's implicit scope, so `id.asString` still needs no extra import.

**`toString` changed value.** `ProviderKind.OpenAI.toString` was `"OpenAI"`; a `ProviderId` is
its canonical lowercase spelling, so it prints `"openai"`. If you interpolated a provider into
log or error text, expect the case to change. Uppercase derivations still work:
`providerId.asString.toUpperCase` is `"OPENAI"`, as `providerKind.toString.toUpperCase` was.

### `ProviderConfig` is no longer `sealed`, and describes itself

In Scala 3 `sealed` restricts extension to the **same file**, so all ten provider configs were
stuck in one 756-line file - not merely in the same jar. `ProviderConfig` is now a plain `trait`
with three new members:

```scala
trait ProviderConfig:
  def providerId: ProviderId              // replaces `val provider: ProviderKind`
  def endpointUrl: Option[String]         // the endpoint this config will contact
  def withModel(model: String): ProviderConfig
  // model, contextWindow, reserveCompletion unchanged
```

```scala
// Before
config.provider == ProviderKind.OpenAI
// After
config.providerId == ProviderId("openai")
```

The three additions exist so that code describing a config does not have to know the set of
subtypes. Four exhaustive matches were **deleted rather than moved** by using them:
`ConfigPolicyEngine.providerName`, `ConfigPolicyEngine.baseUrlOrEndpoint`,
`PrometheusMetricsExample`'s provider-name match, and `ProviderSetupRuntime.overrideModel`. If
you have a `match` on `ProviderConfig`, that is the migration: reach for `providerId`,
`endpointUrl` or `withModel` first, and only keep the match if you genuinely need
provider-specific fields.

Losing `sealed` also means an exhaustive `match` on `ProviderConfig` now compiles with a
warning - and **fails for anyone building with `-Werror`**, as this repo does. Add a default
branch, or annotate the scrutinee `(config: @unchecked)` if you have deliberately accepted the
risk.

One behaviour change falls out of this: `ConfigPolicyEngine.baseUrlOrEndpoint` used to return
`None` for `VertexAIConfig`, because the old match had no case for it. It now returns
`Some(computedBaseUrl)`. A `requiredBaseUrlPattern` policy that silently reported "no
endpoint/baseUrl found" for Vertex AI will now actually check the URL.

### Unknown provider ids are no longer rejected while parsing

`NamedProviderConfigNormalizer` used to fail on an unrecognised `provider` string with
`"Configured provider 'x' has unknown provider 'moonbeam'"`. It now produces a `ProviderId`
unconditionally; only *resolution* fails, with an error naming what is registered:

```
No provider capabilities registered for provider 'moonbeam'.
Registered providers: anthropic, azure, cohere, deepseek, gemini, mistral, ollama,
openai, openrouter, requesty, vertexai, zai
```

This is what lets a provider live in a module `llm4s-core` has never heard of. The accepted
aliases are unchanged - `provider = "google"` still resolves to `gemini`, and
`provider = "vertex"` to `vertexai` - though that table moves into each provider's descriptor
when the SPI lands.

### Validation error text is now provider-agnostic

```
// Before
Azure OpenAI provider 'my-azure' is missing required fields:
// After
Provider 'my-azure' (provider = azure) is missing required fields:
```

The per-field guidance underneath is unchanged, including the `${?AZURE_API_KEY}` substitution
hint. Only the leading sentence differs, because it used to be generated from a hard-coded
display name per provider.

### Bug fix: `provider = "vertexai"` now works at all

`ProviderKind.VertexAI` existed, `NamedProviderLoader` built a `VertexAIConfig` from it, and
`LLMConnect` built a `VertexAIClient` from that - but Vertex AI was missing from
`ProviderCapabilitiesRegistry` and had no validator object. Since validation routes through that
registry, **every `provider = "vertexai"` config failed validation outright**, so none of the
supporting code was reachable from configuration. Both are now present, and the config path is
covered by tests.

### What did *not* change

`ProviderConfig.fromValues`'s `require(...)` calls still throw rather than returning `Result`;
converting them is a throw-to-`Left` behaviour change and is deferred to PR 2.
`ReliableProviders`' seven per-provider factories are also unchanged here - they collapse to a
single registry-routed `wrap` in PR 2. `NamedProviderLoader`, `NamedProviderValidator`,
`ProviderCapabilities`, `ProviderCapabilitiesRegistry` and `ProviderModelLister` are all
`private[llm4s]` or `private[config]`, so their reshaping costs users nothing.

## Slice 3: `llm4s-speech`

The last artifact of slice 3 ([#1130](https://github.com/llm4s/llm4s/issues/1130)) and the last
package out of `llm4s-core` in this slice. It is in the build but not yet in a release, so
nothing here affects `0.4.1` or earlier.

### What moved

| Packages | New module |
|---|---|
| `org.llm4s.speech` (and `speech/io`, `processing`, `stt`, `tts`, `util`) | `llm4s-speech` |

**Package names did not change, and there are no source breaks.** 16 main and 21 test files
moved whole; the only code outside the package that referenced it was a sample.

```scala
// Before
libraryDependencies += "org.llm4s" %% "llm4s-core" % version

// After - only if you use speech-to-text or text-to-speech
libraryDependencies ++= Seq(
  "org.llm4s" %% "llm4s-core"   % version,
  "org.llm4s" %% "llm4s-speech" % version
)
```

### What `llm4s-core` sheds

**Vosk (25 MB) and JNA.** `com.alphacephei:vosk` is imported by exactly one file,
`speech/stt/VoskSpeechToText.scala`, and until now sat on the classpath of every `llm4s-core`
user, whether or not they had any use for offline speech recognition. It is the single largest
dependency the carve programme has moved.

`Deps.jna` moves with it, but it is worth being precise about why, because it is not a second
dependency: Vosk's own POM already depends on `net.java.dev.jna:jna:5.7.0`. The explicit
declaration exists to win that version conflict and pull 5.19.1 instead. Dropping it would not
remove JNA - it would silently downgrade it to a release that predates Apple Silicon support.

`llm4s-workspace-client` also declared both, and used neither; those declarations are removed
here too, so Vosk genuinely leaves the build for everyone who is not doing speech.

### The Vosk resolver is deleted, not moved

`build.sbt` carried the project's only third-party resolver:

```scala
resolvers += "Vosk Repository" at "https://alphacephei.com/maven/"
```

It resolved nothing. Vosk 0.3.45 publishes to Maven Central, which is where every build has
actually been getting it - the local Coursier cache holds `vosk-0.3.45.jar` under
`repo1.maven.org` and not a single artifact under `alphacephei.com`. What it *did* do was add a
third-party host to the lookup path for every artifact in the build, including llm4s's own
inter-module jars, which produced a steady trickle of failed requests to alphacephei.com on
every resolve.

So it is removed rather than carried into `llm4s-speech`, and **the build now has no
third-party resolvers at all**.

### Slice 3 is complete

With this, `llm4s-core` no longer contains `rag`, `knowledgegraph`, `agent/memory`, `mcp`,
`imagegeneration`, `imageprocessing` or `speech`. What remains is the agent runtime,
`llmconnect`, `toolapi`, `config`, `trace` and the provider clients, which
[slices 4 to 6](https://github.com/llm4s/llm4s/issues/1126) address.

## Slice 3: `llm4s-image`

Part of slice 3 of the module carves tracked in
[#1126](https://github.com/llm4s/llm4s/issues/1126); the slice is
[#1130](https://github.com/llm4s/llm4s/issues/1130). It is in the build but not yet in a
release, so nothing here affects `0.4.1` or earlier.

### What moved

| Packages | New module |
|---|---|
| `org.llm4s.imagegeneration` | `llm4s-image` |
| `org.llm4s.imageprocessing` | `llm4s-image` |

**Package names did not change, and this carve adds no source breaks of its own.** The image
API's one source break - image formats becoming `org.llm4s.media.MediaType` - landed earlier,
in [`llm4s-media`](#slice-3-llm4s-media), precisely so that this step is a pure file move.

The two packages move together because they are two halves of one subsystem: generate an
image, then analyse or convert it. Both are built on the same media vocabulary, and splitting
them would leave two artifacts nobody uses apart.

```scala
// Before
libraryDependencies += "org.llm4s" %% "llm4s-core" % version

// After - only if you generate images, or analyse them with a vision model
libraryDependencies ++= Seq(
  "org.llm4s" %% "llm4s-core"  % version,
  "org.llm4s" %% "llm4s-image" % version
)
```

`llm4s-image` brings `llm4s-media` with it, so `MediaType` is on your classpath either way.

### What `llm4s-core` sheds

No third-party dependency: the image clients are built on `Llm4sHttpClient`, `ujson`/`upickle`
and `javax.imageio` from the JDK, all of which core keeps for other reasons. What core sheds is
**19 source files and about 3,600 lines** of a subsystem most users never touch, along with its
edge to `llm4s-media` - that edge existed only because the image packages were still inside
core, and it leaves with them.

Core's measured statement coverage rises from 74.05% to 74.89% as a result, since the image
code was below core's average.

### One test moved with the code

`org.llm4s.async.AsyncErrorHandlingSpec` lived in core's test tree under a package name that
suggests it is about asynchrony in general. Every one of its assertions exercises an image
client - it checks that `ImageProcessingClient.analyzeImageAsync` and the image generation
clients' `Future { blocking { ... } }.recover { ... }` pattern surface thrown exceptions as
`Left` rather than as a failed `Future`. It moves to `llm4s-image` with the code it tests,
keeping its package name.

Had it been left behind it would simply have stopped compiling - but the more useful point is
that leaving it would have removed the only coverage of that behaviour from the module that
owns it.

## Slice 3: `llm4s-media`

A new module rather than a carve, landed as part of slice 3
([#1130](https://github.com/llm4s/llm4s/issues/1130)) and ahead of `llm4s-image` and
`llm4s-speech`, so those two carves can be pure file moves. It is in the build but not yet in
a release, so nothing here affects `0.4.1` or earlier.

### Why

A media type - a MIME string, a canonical file extension, and whether the thing is an image,
audio, video or text - is the one piece of vocabulary every multimodal subsystem needs to
name. Because there was nowhere shared to put it, each grew its own. `llm4s-core` shipped
three overlapping enumerations of the same handful of image formats:

| Type | Cases | Members |
|---|---|---|
| `org.llm4s.imagegeneration.ImageFormat` | PNG, JPEG, WEBP | `extension`, `mimeType` |
| `org.llm4s.imageprocessing.ImageFormat` | PNG, JPEG, WEBP, GIF | `extension`, `mimeType` |
| `org.llm4s.imageprocessing.MediaType` | Jpeg, Png, Gif, WebP, Bmp, Tiff | `value` |

The first two are structurally identical and differ only in package, so a format produced by
image generation could not be handed to image processing without a hand-written conversion.
The third models the same six formats a third way, in the same package as the second. Meanwhile
`MediaExtractor` in `llm4s-rag` discriminated on raw MIME prefixes (`mimeType.startsWith
("image/")`), with no type to name the answer at all.

Carving `image` and `speech` out of core without fixing this would have frozen three copies
into three artifacts, where consolidating them later costs a cross-module source break rather
than an in-module one.

### What `llm4s-media` is

Vocabulary only - no I/O, no content sniffing, no third-party dependencies at all:

- `org.llm4s.media.MediaType` - `mimeType`, `extension`, `category`, plus `fromExtension`,
  `fromPath` and `fromMimeType` lookups. Sealed; refines into `ImageMediaType` and
  `AudioMediaType` so an image API can require an image without re-enumerating the cases.
- `org.llm4s.media.MediaCategory` - `Image`, `Audio`, `Video`, `Text`, `Application`, with
  `fromMimeType`.

Deciding what a file actually *is* from its bytes needs Tika and stays in `llm4s-rag`; that
code produces a MIME string and resolves it here. That separation is what lets every consumer
depend on `llm4s-media` without inheriting anything.

### Source breaks

**This is a source break, taken deliberately ahead of the 1.0 API freeze.** All three types
above are replaced by `org.llm4s.media.MediaType`.

| Before | After |
|---|---|
| `org.llm4s.imagegeneration.ImageFormat` | `org.llm4s.media.ImageMediaType` |
| `org.llm4s.imageprocessing.ImageFormat` | `org.llm4s.media.ImageMediaType` |
| `org.llm4s.imageprocessing.MediaType` | `org.llm4s.media.MediaType` |
| `ImageFormat.PNG` | `MediaType.Png` |
| `ImageFormat.JPEG` | `MediaType.Jpeg` |
| `ImageFormat.WEBP` | `MediaType.WebP` |
| `ImageFormat.GIF` | `MediaType.Gif` |
| `MediaType.Jpeg.value` | `MediaType.Jpeg.mimeType` |

```scala
// Before
import org.llm4s.imagegeneration.ImageFormat
val opts = ImageGenerationOptions(format = ImageFormat.PNG)

// After
import org.llm4s.media.MediaType
val opts = ImageGenerationOptions(format = MediaType.Png)
```

Two lookups changed shape as well. `org.llm4s.imageprocessing.MediaType.fromExtension` and
`.fromPath` were total, silently returning JPEG for anything they did not recognise - so a
`.txt` file reported as an image and the caller could not tell. The replacements return
`Option`, and callers that genuinely want the old fallback ask for it:

```scala
// Before
val mt = MediaType.fromPath(path)                                   // JPEG if unrecognised

// After
val mt = MediaType.imageFromPath(path).getOrElse(MediaType.Jpeg)    // fallback is now visible
```

`AnthropicVisionClient.detectMediaType` keeps the old behaviour and its old signature shape -
it still answers JPEG for an unrecognised extension, because that is what the Anthropic API
assumes for an unlabelled image - but now returns an `ImageMediaType`.

### Adding the dependency

Nothing to add today: `llm4s-media` arrives as a transitive dependency of `llm4s-core` (via
the image packages, which are still in core) and of `llm4s-rag`. Declare it directly only if
you name `MediaType` or `MediaCategory` in your own signatures.

```scala
libraryDependencies += "org.llm4s" %% "llm4s-media" % version
```

## Slice 3: `llm4s-mcp`

Third of the module carves tracked in
[#1126](https://github.com/llm4s/llm4s/issues/1126); slice 3 is
[#1130](https://github.com/llm4s/llm4s/issues/1130), which carves three independent
subsystems - `mcp`, `image` and `speech` - one artifact at a time. This note covers `mcp`;
the other two follow. It is in the build but not yet in a release, so nothing here affects
`0.4.1` or earlier.

### What moved

| Packages | New module |
|---|---|
| `org.llm4s.mcp` | `llm4s-mcp` |

**Package names did not change, and there are no source breaks.** Nothing outside
`org.llm4s.mcp` referenced it, so the whole package moved with no facade left behind.

```scala
// Before
libraryDependencies += "org.llm4s" %% "llm4s-core" % version

// After - only if you use the Model Context Protocol client, server or tool registry
libraryDependencies ++= Seq(
  "org.llm4s" %% "llm4s-core" % version,
  "org.llm4s" %% "llm4s-mcp"  % version
)
```

### What `llm4s-core` sheds

**Java-WebSocket.** Worth being precise about why, because it is not what
[#1130](https://github.com/llm4s/llm4s/issues/1130) predicted: MCP does not use WebSockets at
all. Its transports are stdio, HTTP and SSE, built on `Llm4sHttpClient` and
`com.sun.net.httpserver`. `Deps.websocket` was declared on `llm4s-core` and imported by
nothing in it - the only WebSocket code in the repo is `ContainerisedWorkspace` in
`llm4s-workspace-client`, which declares the dependency itself. So core sheds it by dropping a
declaration that was never used, and the dependency does not follow `mcp` anywhere.

If you depend on `llm4s-core` and were picking up `org.java-websocket` transitively, declare
it yourself.

### Configuration keys

None. `org.llm4s.mcp` reads no `reference.conf` keys and no `Llm4sConfig` method names a type
that moved.

---

## Slice 2: `llm4s-memory` and `llm4s-memory-postgres`

Second of the module carves tracked in
[#1126](https://github.com/llm4s/llm4s/issues/1126); slice 2 is
[#1129](https://github.com/llm4s/llm4s/issues/1129). It is in the build but not yet in a
release, so nothing here affects `0.4.1` or earlier.

### What moved

| Packages | New module |
|---|---|
| `org.llm4s.agent.memory`, except `PostgresMemoryStore` | `llm4s-memory` |
| `org.llm4s.agent.memory.PostgresMemoryStore` | `llm4s-memory-postgres` |

**Package names did not change, and there are no source breaks in this slice.** Nothing
outside `org.llm4s.agent.memory` referenced it, so the whole package moved with no facade left
behind. Add the dependency; your imports stay as they are.

```scala
// Before
libraryDependencies += "org.llm4s" %% "llm4s-core" % version

// After — only if you use agent memory
libraryDependencies ++= Seq(
  "org.llm4s" %% "llm4s-core"   % version,
  "org.llm4s" %% "llm4s-memory" % version
)

// ...and only if you store memories in Postgres/pgvector
libraryDependencies += "org.llm4s" %% "llm4s-memory-postgres" % version
```

### Why two artifacts

`PostgresMemoryStore` was the only file in the package that needed a connection pool and a
server-side driver. Shipping it alongside `InMemoryStore` would mean every user of agent
memory inherits HikariCP and the Postgres JDBC driver whether or not they ever open a
connection. `llm4s-memory` carries sqlite-jdbc — for the file-backed `SQLiteMemoryStore` and
`VectorMemoryStore` — and nothing else; `llm4s-memory-postgres` depends on `llm4s-memory` and
adds the two heavy dependencies.

### What `llm4s-core` sheds

**HikariCP and the Postgres JDBC driver** leave the core classpath. sqlite-jdbc leaves too:
all three used to be declared in the build's shared settings, which put them on *every*
module's classpath, so core could not shed them by itself. They are now declared only by the
modules that open a connection. If you depend on `llm4s-core` and use any of the three
directly, declare them yourself rather than relying on the transitive edge.

### `org.llm4s.vectorstore.PostgresVectorHelpers`

Unchanged for callers — same package, same object, same methods — but worth knowing where it
ships. It is the pgvector text codec (`[0.1,0.2,0.3]` ⇄ `Array[Float]`), it names no JDBC
type, and it now has consumers in two modules that do not and should not depend on each
other: `PgVectorStore` in `llm4s-rag` and `PostgresMemoryStore` in `llm4s-memory-postgres`.
Rather than have either reach for the other, the single copy lives in `llm4s-core`, which both
already depend on. Slice 1 had briefly moved it into `llm4s-rag` and left a private duplicate
in core for `PostgresMemoryStore`; that duplicate is now gone.

So `org.llm4s.vectorstore` is split across two jars: this one object in `llm4s-core`, the rest
in `llm4s-rag`. It resolves the same way on any ordinary classpath.

### Configuration keys

None. `agent/memory` reads no `reference.conf` keys and no `Llm4sConfig` method returns a type
that moved, so there is nothing to migrate.

---

## Slice 1: `llm4s-rag` and `llm4s-knowledgegraph`

First of the module carves tracked in
[#1126](https://github.com/llm4s/llm4s/issues/1126); slice 1 is
[#1128](https://github.com/llm4s/llm4s/issues/1128). It is in the build but not yet in a
release, so nothing here affects `0.4.1` or earlier.

### What moved

| Packages | New module |
|---|---|
| `org.llm4s.rag`, `org.llm4s.vectorstore`, `org.llm4s.chunking`, `org.llm4s.reranker`, `org.llm4s.eval`, `org.llm4s.extract`, `org.llm4s.knowledgegraph.graphrag` | `llm4s-rag` |
| `org.llm4s.knowledgegraph` (everything except `graphrag`) | `llm4s-knowledgegraph` |

**Package names did not change**, with the one deliberate exception described under [Source
breaks](#source-breaks) below. Add the dependency; your imports stay as they are.

```scala
// Before
libraryDependencies += "org.llm4s" %% "llm4s-core" % version

// After — only if you use RAG, vector stores, chunking, reranking or the knowledge graph
libraryDependencies ++= Seq(
  "org.llm4s" %% "llm4s-core" % version,
  "org.llm4s" %% "llm4s-rag"  % version   // depends on llm4s-knowledgegraph transitively
)
```

`llm4s-rag` depends on `llm4s-knowledgegraph`, so depending on the graph alone is only
worth doing if you want the graph without RAG.

`llm4s-knowledgegraph-neo4j` — a separate, already-published artifact — now depends on
`llm4s-knowledgegraph` instead of `llm4s-core`, which resolves for you.

### What `llm4s-core` sheds

Six dependencies leave the core classpath: **Tika, POI, PDFBox, jsoup, AWS S3 and AWS STS**.
If you depend on `llm4s-core` and use any of those directly, declare them yourself rather
than relying on the transitive edge.

### Source breaks

Three, all of them in this slice on purpose — pre-1.0 is when a duplicate is cheapest to
remove.

**1. The two document extractors are now one.** `org.llm4s.rag.extract.DocumentExtractor`
and `org.llm4s.llmconnect.extractors.UniversalExtractor` were independent implementations of
one job: two Tika instances, two sets of MIME constants, two PDFBox paths, two POI paths.
They are now `org.llm4s.extract`.

| Before | After |
|---|---|
| `org.llm4s.rag.extract.DocumentExtractor` | `org.llm4s.extract.DocumentExtractor` |
| `org.llm4s.rag.extract.DefaultDocumentExtractor` | `org.llm4s.extract.TikaDocumentExtractor` |
| `UniversalExtractor.extract(path)` → `Either[ExtractorError, String]` | `TikaDocumentExtractor.extractFromPath(path)` → `Result[ExtractedDocument]` (text in `.text`) |
| `UniversalExtractor.extractFromBytes(bytes, name, mime)` | `TikaDocumentExtractor.extract(bytes, name, mime)` |
| `UniversalExtractor.extractFromStream(in, name, mime)` | `TikaDocumentExtractor.extractFromStream(in, name, mime)` (returns `ExtractedDocument`) |
| `UniversalExtractor.isTextLike(mime)` | `TikaDocumentExtractor.canExtract(mime)` — also true for legacy `.doc` |
| `UniversalExtractor.detectMimeType(bytes, name)` | unchanged |
| `UniversalExtractor.extractAny(path)` and its `Extracted` / `TextContent` / `ImageContent` / `AudioContent` / `VideoContent` ADT | `org.llm4s.extract.MediaExtractor` |
| `org.llm4s.llmconnect.model.ExtractorError` | `org.llm4s.error.ProcessingError` |

The package is `org.llm4s.extract`, not `org.llm4s.rag.extract`: extraction has two real
consumers — RAG document loading and multimodal embedding — and it quarantines the three
heaviest dependencies in the build. Naming it outside the `rag` namespace makes any later
decision to give it its own artifact a build-file change rather than a code change.

**2. `EmbeddingClient.encodePath` is now `FileEmbedder.encodeFromPath`.** `EmbeddingClient`
keeps the pure vector API; file reading, MIME sniffing and chunking live in
`org.llm4s.rag.embed`. The six-parameter signature — which included an
`experimentalStubsEnabled: Boolean`, a deployment decision arriving at a call site — became
a config object.

```scala
// Before
client.encodePath(path, textModel, chunkingCfg, stubsEnabled, localModels)

// After
import org.llm4s.rag.embed.{ FileEmbedder, FileEmbeddingConfig, TextChunkingConfig }

FileEmbedder.encodeFromPath(
  path,
  client,
  FileEmbeddingConfig(
    textModel = textModel,
    localModels = localModels,
    chunking = TextChunkingConfig(enabled = true, size = 1000, overlap = 100),
    experimentalStubs = stubsEnabled
  )
)
```

`UniversalEncoder.TextChunkingConfig` is now the top-level `org.llm4s.rag.embed.TextChunkingConfig`.

**3. `Llm4sConfig.pgSearchIndex()` is now `PgSearchIndexConfigLoader.default()`.** It
returned a `SearchIndex.PgConfig`, which is RAG's type; `Llm4sConfig` stays in `llm4s-core`
and cannot name it. The loader itself keeps its package (`org.llm4s.config`) and its
`load(source)` method, and moves to `llm4s-rag`.

```scala
// Before
val pg = Llm4sConfig.pgSearchIndex()

// After
import org.llm4s.config.PgSearchIndexConfigLoader
val pg = PgSearchIndexConfigLoader.default()
```

### Configuration keys

`llm4s.rag.permissions.pg.*` and `llm4s.rerank.*` now ship in `llm4s-rag`'s `reference.conf`
rather than core's. HOCON merges `reference.conf` across jars, so the key paths are
unchanged and nothing in your `application.conf` needs editing — but a build that reads
those keys without depending on `llm4s-rag` no longer gets the defaults.

`llm4s.embeddings.*` — including `chunking` and `experimentalStubs` — stays in core, because
`Llm4sConfig` still reads it there.

---

## Artifact coordinate rename (v0.4.0)

### Breaking change

Every **published** artifact under the `org.llm4s` group was renamed to carry an `llm4s-`
prefix and a consistent kebab-case suffix. This is a **coordinate-only** change: there are
no API changes, no package moves and no source changes in this release. Update your
`build.sbt`, recompile, and you are done.

| Old coordinate | New coordinate |
|---|---|
| `"org.llm4s" %% "core"` | `"org.llm4s" %% "llm4s-core"` |
| `"org.llm4s" %% "workspaceShared"` (published as `workspaceshared`) | `"org.llm4s" %% "llm4s-workspace-shared"` |
| `"org.llm4s" %% "workspaceClient"` (published as `workspaceclient`) | `"org.llm4s" %% "llm4s-workspace-client"` |
| `"org.llm4s" %% "trace-opentelemetry"` | `"org.llm4s" %% "llm4s-observability-otel"` |
| `"org.llm4s" %% "knowledgegraph-neo4j"` | `"org.llm4s" %% "llm4s-knowledgegraph-neo4j"` |

Maven users: the `artifactId` gains the same prefix, so `core_3` becomes `llm4s-core_3`
(and `core_2.13` becomes `llm4s-core_2.13`).

### Why

Two reasons:

1. **Consistency.** `org.llm4s:core` is a poor coordinate to read in somebody else's build
   file, and the module names were an inconsistent mix of camelCase (silently lowercased by
   the publish into `workspaceclient`) and kebab-case.
2. **Escaping a bad publish.** The `core_3` / `core_2.13` artifacts carry an accidental
   mis-published version `2.1.593` (a typo). Maven Central publishes are immutable, so that
   version cannot be retracted, and some resolvers sort it as the "latest" release. A fresh
   artifact name is the only way out; documentation is not.

### Nobody is stranded

Releases up to and including **0.3.4** remain published, unchanged and resolvable under the
old coordinates. Pinning `"org.llm4s" %% "core" % "0.3.4"` keeps working indefinitely — you
only need to change coordinates when you move to 0.4.0 or later.

If you are pinning `core` with a floating or range version, pin an explicit `0.3.4` before
upgrading, so the phantom `2.1.593` is never selected.

### Migration steps

1. Replace the old coordinate with the new one in your `build.sbt` (see the table above).
2. Set the version to `0.4.0` or later.
3. Recompile. No imports, types or method signatures changed.

```scala
// Before
libraryDependencies += "org.llm4s" %% "core" % "0.3.4"

// After
libraryDependencies += "org.llm4s" %% "llm4s-core" % "0.4.0"
```

### Note on `"org.llm4s" %% "llm4s"`

The aggregate `llm4s` artifact (`llm4s_3` / `llm4s_2.13`) is **not** published and has not
been since 0.2.9 — the root project sets `publish / skip := true`. Any build file or
documentation that depends on `"org.llm4s" %% "llm4s"` is wrong independently of this
rename and should be changed to `"org.llm4s" %% "llm4s-core"`.

### Note on `llm4s-observability-otel`

The OpenTelemetry integration is published as `llm4s-observability-otel` rather than
`llm4s-trace-opentelemetry`. The name anticipates the `llm4s-observability` module that the
modularisation work will carve out of `trace` + `metrics`, so the integration is named once
rather than twice.

### Unpublished modules

`samples`, `workspaceRunner`, `workspaceSamples`, `config-policy`, `it` and `benchmarks` set
`publish / skip := true` and never reached Maven Central. Their `name` values were made
consistent in the same change, but this has no effect on any downstream build.

---

## MessageRole Enum Changes (v0.2.0)

### Breaking Change
The `MessageRole` has been converted from string-based constants to a proper enum type for better type safety.

### Before (v0.1.x)
```scala
import org.llm4s.llmconnect.model.Message

val message = Message(role = "assistant", content = "Hello")
message.role match {
  case "assistant" => // handle assistant
  case "user" => // handle user
  case _ => // handle other
}
```

### After (v0.2.0)
```scala
import org.llm4s.llmconnect.model.{Message, MessageRole}

val message = AssistantMessage(content = "Hello")
// or
val message = Message(role = MessageRole.Assistant, content = "Hello")

message.role match {
  case MessageRole.Assistant => // handle assistant
  case MessageRole.User => // handle user
  case MessageRole.System => // handle system
  case MessageRole.Tool => // handle tool
}
```

### Migration Steps

1. **Update imports**: Add `MessageRole` to your imports
   ```scala
   import org.llm4s.llmconnect.model.MessageRole
   ```

2. **Replace string comparisons**: Update pattern matches and comparisons
   ```scala
   // Before
   if (message.role == "assistant") { ... }
   
   // After
   if (message.role == MessageRole.Assistant) { ... }
   ```

3. **Update message creation**: Use the typed constructors
   ```scala
   // Before
   Message(role = "user", content = "Hello")
   
   // After
   UserMessage(content = "Hello")
   // or
   Message(role = MessageRole.User, content = "Hello")
   ```

## Error Hierarchy Changes (v0.2.0)

### New Error Categorization
Errors are now categorized using traits for better type safety and recovery strategies.

### Before (v0.1.x)
```scala
error match {
  case e: LLMError if e.isRecoverable => // retry logic
  case e: LLMError => // handle non-recoverable
}
```

### After (v0.2.0)
```scala
error match {
  case e: RecoverableError => // retry logic
  case e: NonRecoverableError => // handle non-recoverable
}
```

### Error Recovery Pattern
```scala
import org.llm4s.error._

def handleError(error: LLMError): Unit = error match {
  case _: RateLimitError => // wait and retry
  case _: TimeoutError => // retry with backoff
  case _: ServiceError with RecoverableError => // retry
  case _: AuthenticationError => // refresh token or fail
  case _: ValidationError => // fix input and retry
  case _ => // non-recoverable, fail
}
```

### Migration Steps

1. **Replace `isRecoverable` checks**: Use pattern matching on traits
   ```scala
   // Before
   if (error.isRecoverable) { ... }
   
   // After
   error match {
     case _: RecoverableError => { ... }
     case _ => { ... }
   }
   ```

2. **Update error handling**: Use the new trait-based categorization
   ```scala
   // Before
   case e: ServiceError if e.isRecoverable =>
   
   // After
   case e: ServiceError with RecoverableError =>
   ```

3. **Use smart constructors**: Create errors using the companion object methods
   ```scala
   // Before
   new RateLimitError(429, "Rate limit exceeded", Some(60.seconds))
   
   // After
   RateLimitError(429, "Rate limit exceeded", Some(60.seconds))
   ```

## Configuration Changes (v0.2.0+)

### EnvLoader and legacy ConfigReader → Llm4sConfig

Older versions used `EnvLoader` and a custom `ConfigReader` abstraction. These have been superseded by `Llm4sConfig` (PureConfig‑based) and typed helpers.

### Before (v0.1.x)
```scala
import org.llm4s.config.EnvLoader

val apiKey = EnvLoader.get("OPENAI_API_KEY")
val model  = EnvLoader.getOrElse("LLM_MODEL", "gpt-4")
```

or:

```scala
import org.llm4s.config.ConfigReader
import org.llm4s.llmconnect.LLMConnect

val client: org.llm4s.types.Result[org.llm4s.llmconnect.LLMClient] =
  ConfigReader.Provider().flatMap(LLMConnect.getClient)
```

### After (post‑0.2.0)

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect

val client: org.llm4s.types.Result[org.llm4s.llmconnect.LLMClient] =
  for {
    cfg    <- Llm4sConfig.provider()
    client <- LLMConnect.getClient(cfg)
  } yield client
```

### Typed Config: recommended patterns

- Tracing (typed):
  ```scala
  import org.llm4s.config.Llm4sConfig
  import org.llm4s.trace.{ Tracing, EnhancedTracing, TracingMode }

  val tracerResult: org.llm4s.types.Result[Tracing] =
    Llm4sConfig.tracing().map(Tracing.create)
  ```

- Provider model for display (typed):
  ```scala
  val modelNameResult = Llm4sConfig.provider().map(_.model)
  // Prefer completion.model after the API call when available
  ```

- Workspace (samples):
  ```scala
  import org.llm4s.codegen.WorkspaceConfigSupport

  val ws = WorkspaceConfigSupport.load().getOrElse(
    throw new IllegalArgumentException("Failed to load workspace settings")
  )
  ```

- Embeddings (samples):
  ```scala
  val ui      = org.llm4s.samples.embeddingsupport.EmbeddingUiSettings.loadFromEnv()
    .getOrElse(throw new IllegalArgumentException("Failed to load UI settings"))
  val targets = org.llm4s.samples.embeddingsupport.EmbeddingTargets.loadFromEnv()
    .fold(err => throw new IllegalArgumentException(err.toString), _.targets)
  val query   = org.llm4s.samples.embeddingsupport.EmbeddingQuery.loadFromEnv()
    .fold(_ => None, _.value)
  ```

## Configuration: legacy reader → `Llm4sConfig` / typed helpers (post‑0.2.0)

Earlier versions used a custom `ConfigReader`-style abstraction as a catch‑all for configuration. With PureConfig in place and typed helpers available, the preferred path is now:

- Use `org.llm4s.config.Llm4sConfig` in core code.
- Use explicit typed loaders plus `LLMConnect.getClient` in application/sample code.

### Provider configuration and client creation

**Before (legacy reader-based API)**
```scala
import org.llm4s.config.ConfigReader
import org.llm4s.llmconnect.LLMConnect

val client: org.llm4s.types.Result[org.llm4s.llmconnect.LLMClient] =
  ConfigReader.Provider().flatMap(LLMConnect.getClient)
```

**After**
```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect

// Typed path using Llm4sConfig
val client: org.llm4s.types.Result[org.llm4s.llmconnect.LLMClient] =
  for {
    cfg    <- Llm4sConfig.provider()
    client <- LLMConnect.getClient(cfg)
  } yield client
```

### Tracing configuration

**Before (legacy reader-based API)**
```scala
import org.llm4s.config.ConfigReader
import org.llm4s.trace.Tracing

val tracer: Tracing =
  ConfigReader.TracingConf().map(Tracing.create).getOrElse(Tracing.noop)
```

**After**
```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.trace.Tracing

val tracer: org.llm4s.types.Result[Tracing] =
  Llm4sConfig.tracing().map(Tracing.create)
```

### Embeddings: provider and client

**Before (legacy reader-based API)**
```scala
import org.llm4s.config.ConfigReader
import org.llm4s.llmconnect.EmbeddingClient

val client: org.llm4s.types.Result[EmbeddingClient] =
  ConfigReader.Embeddings().flatMap { case (provider, cfg) =>
    EmbeddingClient.from(provider, cfg)
  }
```

**After**
```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.EmbeddingClient

val client: org.llm4s.types.Result[EmbeddingClient] =
  Llm4sConfig.embeddings().flatMap { case (provider, cfg) =>
    EmbeddingClient.from(provider, cfg)
  }
```

### Workspace settings

**Before**
```scala
import org.llm4s.codegen.WorkspaceSettings

val ws = WorkspaceSettings.load().getOrElse(
  throw new IllegalArgumentException("Failed to load workspace settings")
)
```

**After**
```scala
import org.llm4s.codegen.WorkspaceConfigSupport

val ws = WorkspaceConfigSupport.load().getOrElse(
  throw new IllegalArgumentException("Failed to load workspace settings")
)
```

### API keys and types

**Before (legacy reader-based API)**
```scala
// Legacy pattern: API key resolved from a generic config reader
def loadApiKey(reader: /* legacy ConfigReader */ Any): Result[ApiKey] =
  ApiKey.unsafe("sk-legacy-key") // placeholder for old behavior
```

**After**
```scala
import org.llm4s.config.Llm4sConfig

val cfgResult = Llm4sConfig.provider() // Result[ProviderConfig]
```

- For **new code**, do not introduce new parameters of reader/ConfigReader types. Prefer:
  - `Llm4sConfig` in core libraries.
  - Typed helpers plus `LLMConnect.getClient` (and `Llm4sConfig.tracing().map(Tracing.create)` / `.map(EnhancedTracing.create)` for tracing) in applications and samples.
- For **existing code** that currently depends on a `ConfigReader`-style abstraction:
  - Start by swapping call sites to use typed helpers (e.g., `Llm4sConfig.provider()`).
  - Where you need fine-grained control, switch to `Llm4sConfig` functions instead of calling the legacy reader directly.
