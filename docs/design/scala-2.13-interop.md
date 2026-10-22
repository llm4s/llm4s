# Calling LLM4S from Scala 2.13 through the Scala 3 artifacts

**Measured:** 2026-10-21, against the published **llm4s 0.5.1** artifacts (built with Scala 3.7.1).
**Question ([#1460](https://github.com/llm4s/llm4s/issues/1460)):** can a Scala 2.13 project use LLM4S today,
without a cross-build, by reading the Scala 3 artifacts with the TASTy reader? This feeds the post-1.0 decision on
Scala 2.13 support in [#874](https://github.com/llm4s/llm4s/issues/874). No source or artifact changes.

## Short answer

**Yes, for calling LLM4S; no, for extending it.** From Scala 2.13 you can load a provider from configuration, get a
client, complete and stream, build options with the enumerations, run an agent turn and continue it, and run tools
through a `ToolRegistry`. You cannot use any API that needs a uPickle codec: `ToolBuilder`,
`completeStructured`, or implementing `LLMClient` yourself. You also cannot name the agent runtime's opaque ids
(`ThreadId(...)`), though you can pass around the ones LLM4S returns.

## Setup

```scala
// build.sbt of a Scala 2.13 project
scalaVersion := "2.13.16"            // or later; 2.13.18 was measured too
scalacOptions += "-Ytasty-reader"
libraryDependencies ++= Seq(
  ("org.llm4s" %% "llm4s-core"   % "0.5.1").cross(CrossVersion.for2_13Use3),
  ("org.llm4s" %% "llm4s-ollama" % "0.5.1").cross(CrossVersion.for2_13Use3), // one provider module per provider
)
```

LLM4S's `using` parameters become an explicit second argument list:

```scala
val registry = ProviderRegistry.default
for {
  models <- Llm4sConfig.modelRegistryService()
  config <- Llm4sConfig.defaultProvider()(registry)
  client <- LLMConnect.getClient(config)(models, registry)
} yield client
```

## Results

Each entry point is a separate probe, so one failure does not hide another. Every probe that calls a model talks to
a local fake Ollama server (`common/FakeOllama.scala`), so no key and no network beyond Maven Central are needed.
The same results on Scala **2.13.16** and **2.13.18**, Scala CLI 1.4.1, JDK 21.

| Entry point | Probe | From Scala 2.13 | Evidence (verbatim) |
|---|---|---|---|
| `Conversation`, messages, `Result` | `A_conversation` | compiles and runs | `4 messages, last: And llm4s?` |
| `Llm4sConfig` + `LLMConnect.getClient` + `complete` | `B_config_client` | compiles and runs | `Hello from the fake Ollama. (usage: Some(23))` |
| `streamComplete` with a 2.13 callback | `C_streaming` | compiles and runs | `2 chunks [streame\|d reply], final: streamed reply` |
| `CompletionOptions` builders, `ReasoningEffort` (Scala 3 `enum`-like ADT) | `D_options_enums` | compiles and runs | `temperature=0.2 maxTokens=Some(256) reasoning=high parsed=Some(Medium) budget=Some(32768)` |
| `ToolBuilder` (defining a tool) | `E_tools` | **does not compile** | `can't find term required by object upickle.core.Types.ReadWriter: upickle.core.Types.this.ReadWriter; perhaps it is missing from the classpath.` |
| `ToolRegistry.execute` (running tools) | `E2_tool_registry` | compiles and runs | `an unknown tool is an error value: true` |
| `completeStructured[A]` (implicit uPickle `Reader[A]`) | `F_structured` | **does not compile** | `can't find term required by object upickle.core.Types.Reader: upickle.core.Types.this.Reader; perhaps it is missing from the classpath.` |
| `Agent.builder(...).build()`, `run`, `continueConversation` | `G_agent` | compiles and runs | `List(Some(Agent answer.), Some(Agent answer.)), same thread: true` |
| Implementing `LLMClient` (a fake client, a custom provider) | `H_subclass_client` | **does not compile** | `can't find term required by object upickle.core.Types.ReadWriter: upickle.core.Types.this.ReadWriter; perhaps it is missing from the classpath.` |
| Naming an opaque id: `ThreadId("...")` | `I_opaque_ids` | **does not compile** | `object ThreadId is not a member of package org.llm4s.agent.graph` |

`scala-2.13-interop/results.md` is the raw output of `scala-2.13-interop/run-probes.sh`.

## The blockers

**1. uPickle's type classes (`ToolBuilder`, `completeStructured`, implementing `LLMClient`; by the same signature,
though not probed, `AgentResult.decodeOutput[O: ReadWriter]`).** The Scala 2.13 compiler cannot read `upickle.core.Types` from the `upickle_3` artifact, so any
signature that mentions `ReadWriter`, `Reader` or `Writer` fails, whether the codec is summoned implicitly or
written by hand (`upickle.default.reader[ujson.Value].map(...)` fails the same way). Subclassing `LLMClient` fails
because the compiler must check every member, including `completeStructured`'s implicit `Reader`; calling the
other members of an `LLMClient` you were given works. The cause is uPickle's Scala 3 artifact, not an LLM4S
construct, so it is **inherent** to the TASTy route: no LLM4S-side change short of removing uPickle from the public
API (a breaking change to frozen signatures) avoids it.

**2. Top-level opaque types' companions (`ThreadId`, `RunId`, `InterruptId`, ... in
`org.llm4s.agent.graph.GraphIds`).** Scala 2.13 reads the opaque alias but not a companion `object` defined at the
top level of a Scala 3 file, which the TASTy reader supports only partially. Values LLM4S returns
(`AgentResult.threadId`) can still be passed back, which is how `continueConversation` works in `G_agent`.
Classified **fixable on our side**, but not API-neutrally: the companions would have to move into an enclosing
object, which changes their paths in a module headed for the freeze.

Not blockers, contrary to the issue's grep-based worries: Scala 3 `enum`-style ADTs, `opaque` aliases used through
returned values, private constructors with companion `apply` and `with*` setters, extension methods on results, and
`using` parameters passed explicitly all work.

## Recommendation

**Document Scala 2.13 as supported with caveats**, for applications that *call* LLM4S: a Spark job, a Play
application or an sbt build that wants completions, streaming, agents and existing tools. State the setup above,
the explicit `using` argument lists, and the four things that do not work.

For the gaps, the likely Scala 2.13 route is the **Java facade**, not a 2.13 cross-build: `llm4s-java-api` is written for
callers that are not Scala 3, and its open work on defining tools from Java (#1484) and structured output into a
Java record (#1486) covers exactly blockers 1's `ToolBuilder` and `completeStructured`. Whether a 2.13 caller can use
those as plain Java APIs is not measured here: it is the second follow-up below.

For [#874](https://github.com/llm4s/llm4s/issues/874): a 2.13 cross-build is not needed to *use* the frozen spine
from 2.13; its cost should be weighed only against the extension points (custom providers, typed tools, structured
output into Scala types), and the Java facade may cover enough of those that #874 can stay post-1.0 or be closed.

## Follow-ups

- A "Using LLM4S from Scala 2.13" section in the getting-started docs, with the setup and the four caveats above
  (and a pointer to the Spark guide, [#1461](https://github.com/llm4s/llm4s/issues/1461)).
- Re-run the probes against `llm4s-java-api` once #1484 and #1486 land, adding a tool and a structured-output
  probe through the Java facade.
- Decide, before the freeze, whether the agent runtime's id companions should move into an enclosing object
  (blocker 2). Only worth it if 2.13 callers are a target.

## Reproducing

```bash
cd docs/design/scala-2.13-interop
./run-probes.sh               # 2.13.16 and 2.13.18; or ./run-probes.sh 2.13.18
```

`common/project.scala` pins the artifacts, `-Ytasty-reader` and the Scala version; each file in `probes/` is one
entry point and prints `PASS: ...` when it compiles and runs.
