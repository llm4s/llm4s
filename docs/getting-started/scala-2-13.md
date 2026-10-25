---
layout: page
title: Using LLM4S from Scala 2.13
parent: Getting Started
nav_order: 12
# Every Scala 2.13 on this page is a Scala 2.13 caller reading the Scala 3 artifacts (scripts/check-doc-support.sh)
scala_2_13: tasty-reader
---

# Using LLM4S from Scala 2.13
{: .no_toc }

Scala 2.13: call-only, via the TASTy reader. A Spark job, a Play application or an Akka or Pekko service can call LLM4S's Scala 3 artifacts today.
{: .fs-6 .fw-300 }

1. TOC
{:toc}

---

LLM4S is published for Scala 3 only: there is no `_2.13` artifact, and a cross-build is not planned before 1.0
([#874](https://github.com/llm4s/llm4s/issues/874)). But the Scala 2.13 compiler can read Scala 3 libraries through
its TASTy reader, and a project on Scala 2.13.16 or later that does so can *call* LLM4S: load a provider, complete
and stream, build options, run an agent and run tools. What it cannot do is *extend* LLM4S with Scala types (define a
tool with `ToolBuilder`, ask for structured output into a case class, implement `LLMClient`); for the first two,
`llm4s-java-api` is the route, and it works from Scala 2.13 through the TASTy reader as well.

These are not promises written from reading the code: each statement below is a probe, a small Scala 2.13 program
that CI compiles and runs against the current build, on Scala 2.13.16 and the latest 2.13, and that fails the build
when its outcome changes ([what is checked](#how-this-is-checked)).

## Setup

```scala
// build.sbt of a Scala 2.13 project, which reads LLM4S through the TASTy reader
scalaVersion := "2.13.16"   // or later: the TASTy reader of 2.13.16 reads the Scala 3.7 artifacts
scalacOptions += "-Ytasty-reader"

libraryDependencies ++= Seq(
  ("org.llm4s" %% "llm4s-core"   % "{{ site.data.project.latest_release }}").cross(CrossVersion.for2_13Use3),
  ("org.llm4s" %% "llm4s-ollama" % "{{ site.data.project.latest_release }}").cross(CrossVersion.for2_13Use3), // one provider module per provider
  ("org.llm4s" %% "llm4s-agent"  % "{{ site.data.project.latest_release }}").cross(CrossVersion.for2_13Use3)  // if you run agents
)
```

`CrossVersion.for2_13Use3` makes `%%` pick the `_3` artifacts. Two rules keep the classpath consistent:

- **Do not add the `_2.13` build of a library LLM4S uses as `_3`** (cats, uPickle, pureconfig) next to it: the two
  builds share class names, and whichever loads first wins. Let LLM4S bring the `_3` ones.
- **`scala-library` must be 2.13.16 or later at run time**, the version LLM4S's Scala 3 standard library is built
  on. The [Spark guide](../guide/spark) covers runtimes that pin an older one.

## Calling LLM4S

From Scala 2.13, pass LLM4S's `using` parameters as an explicit second argument list:

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.{Conversation, UserMessage}
import org.llm4s.llmconnect.spi.ProviderRegistry

val registry = ProviderRegistry.default
val reply = for {
  models     <- Llm4sConfig.modelRegistryService()
  config     <- Llm4sConfig.defaultProvider()(registry)
  client     <- LLMConnect.getClient(config)(models, registry)
  completion <- client.complete(Conversation(Seq(UserMessage("Hi"))))
} yield completion.content
```

Configuration is the same `application.conf` as from Scala 3 ([Configuration](configuration)).

## What works

| From Scala 2.13 | Works | Probe |
|---|---|---|
| `Conversation`, messages, `Result` | yes | `A_conversation` |
| `Llm4sConfig` + `LLMConnect.getClient` + `complete` | yes | `B_config_client` |
| `streamComplete` with a Scala 2.13 callback | yes | `C_streaming` |
| `CompletionOptions` builders, `ReasoningEffort` and the other enumerations | yes | `D_options_enums` |
| Running tools through a `ToolRegistry` you were given | yes | `E2_tool_registry` |
| `Agent.builder(...).build()`, `run`, `continueConversation` | yes | `G_agent` |
| Defining a tool with `llm4s-java-api`'s `JTool.builder`, and an agent calling it | yes | `J_java_api_tool` |
| Structured output into a Java record with `llm4s-java-api`'s `JLlmClient.complete(prompt, Record.class)` | yes | `K_java_api_structured` |
| Defining a tool with `ToolBuilder` | **no** | `E_tools` |
| `completeStructured[A]` | **no** | `F_structured` |
| Implementing `LLMClient` | **no** | `H_subclass_client` |
| Naming an agent id: `ThreadId("...")` | **no** | `I_opaque_ids` |

## The four caveats and their workarounds

### 1. Defining a tool with `ToolBuilder`

`ToolBuilder` takes a uPickle `ReadWriter` for the tool's result, and the TASTy reader cannot read uPickle's Scala 3
type classes, whether summoned or written by hand. Use `llm4s-java-api`'s tool builder, which needs no codec, and the
agent it builds:

```scala
// add ("org.llm4s" %% "llm4s-java-api" % ...).cross(CrossVersion.for2_13Use3)
import org.llm4s.javaapi.{JTool, JToolArgs, Llm4s}

val add = JTool
  .builder("add", "Adds two integers")
  .param("a", classOf[Integer], "the first")
  .param("b", classOf[Integer], "the second")
  .handler((args: JToolArgs) => Integer.valueOf(args.getInt("a") + args.getInt("b")))
  .build()
  .get()

val client = Llm4s.createDefaultClient().get()
val answer = Llm4s.createAgentWithTools(client, java.util.List.of(add)).get().run("What is 2 + 3?")
```

A Scala 2.13 lambda converts to the Java `JToolHandler` interface. Tools that LLM4S already defines (the built-in
tools of `llm4s-agent-tools`) are ordinary values, and an agent or a `ToolRegistry` runs them from Scala 2.13 as it
runs any other.

### 2. Structured output with `completeStructured`

`completeStructured[A]` takes a uPickle `Reader[A]`, for the same reason out of reach. `llm4s-java-api`'s
`JLlmClient.complete(prompt, Record.class)` derives the schema from a **Java record** and reads the answer into it.
A Scala case class is not a record, so declare the record in a Java source file of the same project, which sbt
compiles alongside the Scala:

```java
// src/main/java/City.java
public record City(String name, String country, int population) {}
```

```scala
import org.llm4s.javaapi.Llm4s

val city: City = Llm4s.createDefaultClient().get().complete("The largest city in Japan", classOf[City]).get()
```

`JTool` and `JLlmClient.complete(prompt, Record.class)` are on `main` and not yet in a release: until the next one,
[build LLM4S locally](installation#unreleased-changes-main) to use them.

### 3. Implementing `LLMClient`

Implementing the trait means the compiler checks every member, including `completeStructured`'s `Reader`, so a
Scala 2.13 class cannot extend it. Calling the members of a client LLM4S gives you works. For a test double, use
`llm4s-java-api`'s `FakeLlmClient` (`FakeLlmClient.replying("first", "second").client()`), which the structured-output
probe uses from Scala 2.13. A custom provider is written in Scala 3, in its own module
([writing a provider](../guide/writing-a-provider)), and called from Scala 2.13 like the built-in ones.

### 4. Naming an agent id

The agent runtime's ids (`ThreadId`, `RunId`, ...) are top-level opaque types whose companions the TASTy reader of
Scala 2.13 does not see, so `ThreadId("saved-1")` does not compile. Pass back the ids LLM4S returns
(`result.threadId`, or `agent.continueConversation(result, ...)`); through `llm4s-java-api` the ids are plain
`String`s (`JAgentResult.threadId()`).

## How this is checked

The probes live in
[`docs/design/scala-2.13-interop/probes`](https://github.com/llm4s/llm4s/tree/main/docs/design/scala-2.13-interop/probes),
and [`expected.md`](https://github.com/llm4s/llm4s/blob/main/docs/design/scala-2.13-interop/expected.md) beside them
holds each one's expected outcome. The *Scala 2.13 probes* workflow publishes the current build to the runner's local
repository, runs every probe on Scala 2.13.16 and the latest Scala 2.13 release, and fails when an outcome differs from the table in
either direction: a change that breaks one of the calls above fails, and so does one that fixes a caveat until this
page and the table say so. It runs nightly and on every change to the frozen modules, `llm4s-java-api` or the probes.

To run them yourself against a release, with [Scala CLI](https://scala-cli.virtuslab.org/):

```bash
cd docs/design/scala-2.13-interop
./run-probes.sh                 # the latest release, on 2.13.16 and the latest 2.13
```

The measurements and the reasons behind each caveat are in the design record,
[Calling LLM4S from Scala 2.13](https://github.com/llm4s/llm4s/blob/main/docs/design/scala-2.13-interop.md).
