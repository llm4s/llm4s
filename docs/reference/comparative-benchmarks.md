---
layout: page
title: Comparative Benchmarks
description: "A reproducible harness comparing llm4s, LangChain4j and Spring AI against one local mock server: call overhead, streaming, tool loops, concurrency, cold start."
parent: Reference
nav_order: 28
---

# Comparative Benchmarks
{: .no_toc }

[Comparing LLM4S](../guide/comparison) answers "what features". This page answers "what does it cost at runtime": how
much time and memory the library adds on top of the model call, how long a fresh JVM takes to its first reply, and
how many jars a chat app pulls in. It measures llm4s, LangChain4j and Spring AI on the same five scenarios, against
the same local mock server, so that anyone can re-run it and check the numbers.

1. TOC
{:toc}

## What the mock removes

Every library talks to one **local mock OpenAI-compatible server**
([`MockOpenAiServer`](https://github.com/llm4s/llm4s/blob/main/modules/benchmarks-comparative/harness/src/main/java/org/llm4s/benchcmp/harness/MockOpenAiServer.java)):
canned replies, **no artificial delay**, in a JVM of its own on the loopback interface. There is no model, no API
key and no network. A measured time is therefore the library's own work (building the request, serialising it, the
HTTP client, parsing the reply, the agent loop) plus a loopback round trip and the mock's handling. The loopback and
mock part is the same for every library; it is not subtracted, so the numbers are an upper bound on library
overhead. In a real application a model call takes hundreds of milliseconds to seconds, which these numbers leave
out on purpose: they show only what the library adds.

## Scenarios

| Scenario | JMH benchmark | What it measures |
|---|---|---|
| Chat completion | `ChatBenchmark.chat` | One non-streamed completion: p50 and p99 latency, bytes allocated per call (`-prof gc`) |
| Streaming | `StreamBenchmark.stream`, `chunks` = 1 and 500 | A streamed completion read to the end. With 1 chunk the time is an upper bound on time to first token; `(time(500) - time(1)) / 499` is the cost per chunk |
| Tool-calling agent | `ToolAgentBenchmark.toolAgent` | The library's agent with one `lookup(key)` tool: the server asks for 3 tool calls, then answers, so 4 HTTP calls per run. Reported per run and per round trip (run / 4) |
| Concurrency | `ConcurrentBenchmark.concurrent200` | 200 chat completions at once on one shared client, one virtual thread each where the library supports it: time per batch and throughput (200 / batch time) |
| Cold start and footprint | the `comparativeColdStart` task | A fresh JVM per run that builds a client and makes one chat call, timed from process launch until it prints the reply; and the jar count and total jar size of the library's classpath |

Setup checks each library's reply (the canned text, all 500 chunks, the tool loop's final answer), so a misconfigured
adapter fails the run instead of measuring an error path.

## The adapters

Each library is used the way its documentation shows, with its default HTTP client and retries switched off:

| Library | Version | Adapter | What it uses |
|---|---|---|---|
| llm4s | the checked-out build | [`Llm4sLibrary.scala`](https://github.com/llm4s/llm4s/blob/main/modules/benchmarks-comparative/llm4s/src/main/scala/org/llm4s/benchcmp/llm4s/Llm4sLibrary.scala), [`Llm4sColdStart.scala`](https://github.com/llm4s/llm4s/blob/main/modules/benchmarks-comparative/llm4s/src/main/scala/org/llm4s/benchcmp/llm4s/Llm4sColdStart.scala) | The generic `openai-compatible` provider through `LLMConnect` (`llm4s-openai-compatible`), and an `Agent` with a `ToolBuilder` tool (`llm4s-agent`) |
| LangChain4j | 1.23.0 | [`LangChain4jLibrary.java`](https://github.com/llm4s/llm4s/blob/main/modules/benchmarks-comparative/langchain4j/src/main/java/org/llm4s/benchcmp/langchain4j/LangChain4jLibrary.java), [`LangChain4jColdStart.java`](https://github.com/llm4s/llm4s/blob/main/modules/benchmarks-comparative/langchain4j/src/main/java/org/llm4s/benchcmp/langchain4j/LangChain4jColdStart.java) | `OpenAiChatModel` and `OpenAiStreamingChatModel` (`langchain4j-open-ai`), and an `AiServices` assistant with a `@Tool` (`langchain4j`) |
| Spring AI | 2.0.2 | [`SpringAiLibrary.java`](https://github.com/llm4s/llm4s/blob/main/modules/benchmarks-comparative/spring-ai/src/main/java/org/llm4s/benchcmp/springai/SpringAiLibrary.java), [`SpringAiColdStart.java`](https://github.com/llm4s/llm4s/blob/main/modules/benchmarks-comparative/spring-ai/src/main/java/org/llm4s/benchcmp/springai/SpringAiColdStart.java) | `OpenAiChatModel` built with its builder (`spring-ai-openai`) and a `ChatClient` with a `@Tool` (`spring-ai-client-chat`), without Spring Boot |

Each library builds its tool definitions once, outside the measured call: Spring AI's `@Tool` object is resolved to
`ToolCallback`s at construction, as LangChain4j's `AiServices` and the llm4s `Agent` do. The llm4s tool run also
calls `agent.forget(threadId)` inside the measured call, since a completed thread stays in the agent's in-memory
runtime until it is forgotten and a server would do the same.

The versions are pinned in `project/Dependencies.scala` (`Versions.langchain4j`, `Versions.springAi`) and printed with
every result. The benchmarks themselves are in
[`ComparativeBenchmarks.scala`](https://github.com/llm4s/llm4s/blob/main/modules/benchmarks-comparative/jmh/src/main/scala/org/llm4s/benchcmp/ComparativeBenchmarks.scala).
A correction to an adapter, from anyone who knows the library better, is welcome as a pull request.

## Running it

You need a JDK 21 and sbt, nothing else:

```bash
sbt "benchmarksComparative/comparativeBenchmarks"
```

It runs the cold-start task, then the JMH benchmarks with `-prof gc`, then writes the tables. Everything goes to
`target/comparative/`:

| File | Contents |
|---|---|
| `jmh.json` | JMH's raw results (`-rf json`), with the JDK, JVM flags and JMH settings |
| `coldstart.json` | Cold-start samples, jar names, counts and sizes, library versions, OS, CPU and `java -version` |
| `results.md` | The tables, generated from the two JSON files by [`Report.scala`](https://github.com/llm4s/llm4s/blob/main/modules/benchmarks-comparative/jmh/src/main/scala/org/llm4s/benchcmp/Report.scala); nothing in them is typed by hand |

Arguments after the task name go to JMH. A quick check that everything works (seconds per scenario, numbers not worth
reading):

```bash
sbt "set benchmarksComparative/comparativeColdStartRuns := 2" \
    "benchmarksComparative/comparativeBenchmarks -f 1 -wi 1 -w 1s -i 1 -r 1s"
```

`sbt "benchmarksComparative/Jmh/run"` runs only the JMH scenarios, and a regular expression after it selects some
(`... Jmh/run Stream`). The full run uses 2 forks, 5 warm-up and 5 measurement iterations of 2 seconds per
benchmark, with a 1 GB fixed heap, and 10 cold starts per library after one untimed launch that warms the file
cache; the JMH part alone is about 10 minutes of measurement, plus fork start-up.

The [Comparative Benchmarks workflow](https://github.com/llm4s/llm4s/actions/workflows/comparative-benchmarks.yml)
runs the same command on GitHub's `ubuntu-latest` runner for every release tag and on manual dispatch (with optional
JMH arguments), uploads the three files as an artifact, and shows `results.md` in the run summary.

The comparative projects are not part of the llm4s build's aggregate: `sbt test`, `sbt compile` and publishing never
build them, and LangChain4j and Spring AI are never dependencies of a published llm4s module.

## Results

No results are published here yet. When they are, they will be a workflow run's `results.md`, unedited, with a link
to the run and its `jmh.json` and `coldstart.json`, and the run's hardware, JDK and versions, which `results.md`
states in its first table. A result that shows llm4s slower than another library is published like any other, with
the issue opened to fix it.

## Limitations

- **No model latency, no delay.** The mock answers at once, so the numbers show only what the library adds; in an
  application, model latency dominates them. They do not say which library gives better answers.
- **The loopback round trip is included.** Nothing is subtracted, so the numbers are an upper bound on library
  overhead, the same upper bound for each library.
- **One classpath for JMH.** The JMH benchmarks run all three libraries in one project, so a dependency they share
  (Jackson, for example) is resolved to one version for all three. The cold-start runs use each library's own
  classpath.
- **Footprint is the adapter's classpath.** It counts the jars of a chat-and-tools app: llm4s's
  `llm4s-openai-compatible` and `llm4s-agent`, LangChain4j's `langchain4j-open-ai` and `langchain4j`, Spring AI's
  `spring-ai-openai` and `spring-ai-client-chat`, with everything they pull in, the Scala library included for
  llm4s. A smaller app (chat only) pulls in fewer.
- **One tuning outside the defaults.** Spring AI's OkHttp client keeps only 5 idle connections by default, so
  200 concurrent calls per batch run the machine out of ephemeral ports within seconds. Its adapter sets
  `maxIdleConnections(256)`, the pool the JDK `HttpClient` that llm4s and LangChain4j use has by default.
- **Spring AI runs the concurrency scenario on platform threads.** On JDK 21, 200 concurrent Spring AI calls on
  virtual threads deadlocked in a test run: okio (under OkHttp, under the OpenAI Java SDK that Spring AI 2.0 uses)
  pinned every carrier thread. Its adapter therefore declares `virtualThreads() = false`, and the scenario gives it a
  pool of 200 platform threads created at setup. JEP 491 removes that pinning in later JDKs; this harness runs on JDK 21.
- **Peak heap is not measured.** The concurrency scenario reports time and allocation per batch; a heap peak depends
  mostly on when the collector runs, so it is left out rather than reported unreliably.
- **Time to first token is an upper bound.** It is the time of a whole one-chunk stream, not the time of the first
  callback in a longer one.
- **One machine per run.** Compare libraries within one run, not numbers across machines. A GitHub runner is a
  shared virtual machine, so its numbers vary more than a dedicated machine's.
- **The adapters are not yet reviewed by the other projects.** The LangChain4j and Spring AI adapters were written
  from those projects' documentation; they have not yet been reviewed by maintainers of those projects.

See also [Performance Benchmarks](benchmarks) for llm4s's internal hot paths.
