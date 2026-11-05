---
layout: page
title: An LLM framework for Scala
parent: Scala AI
nav_order: 1
description: "LLM4S is an open-source LLM framework for Scala 3: model calls, tools, agents and RAG with typed errors. How it relates to LangChain and LangChain4j."
search_title: "An LLM framework for Scala, and the LangChain question"
---

# An LLM framework for Scala (and the LangChain question)
{: .no_toc }

Yes, Scala has an LLM framework: LLM4S is an open-source library, written for Scala 3, for calling language models and
building tools, agents and retrieval (RAG) on top of them. It is not a port of LangChain; it covers much of the same
ground with a Scala-first design in which every failure is a typed `Result` value instead of an exception.
{: .fs-6 .fw-300 }

1. TOC
{:toc}

## What LLM4S is

LLM4S runs inside your JVM application, in the same build, tests and deployment as the rest of your code. It gives you:

- **One client type for many providers.** OpenAI, Anthropic, Google Gemini, Ollama and more, each in its own module, so
  you add only the providers you call ([providers](providers)).
- **Typed errors.** Calls return `Result[A]`, an `Either[LLMError, A]`, so a rate limit or a missing API key is a
  value your code handles, not an exception ([error handling](../guide/error-handling)).
- **Tools, agents and guardrails** in one library ([agents](agents)), with streaming events, handoffs between agents
  and human review.
- **Retrieval**: chunking, embeddings, vector stores, hybrid search, reranking and evaluation ([RAG](rag)).
- **Java and Kotlin entry points** for mixed codebases ([Java and Kotlin](java-kotlin)).

It requires Scala 3 and JDK 21, and it is pre-1.0: the API can still change between releases, and the
[v1 scope](../reference/v1-scope) says what will be stable at 1.0.

## Is it "LangChain for Scala"?

Not in the sense of a port or a drop-in replacement. LangChain is a Python project, with a separate library for
JavaScript and TypeScript, LangChain.js ([LangChain repository](https://github.com/langchain-ai/langchain), checked
2026-11-05). LangChain4j, the best-known JVM library with the name, says of itself that it "is not a Java port of
LangChain (Python)" ([LangChain4j introduction](https://docs.langchain4j.dev/intro), checked 2026-11-05). LLM4S is
not a port of either, and its API does not mirror theirs.

What the three have in common is the set of problems: talking to many model providers through one interface, letting
a model call your functions as tools, running agents, and answering questions over your own documents. Where LLM4S
deliberately differs:

- It is written for Scala 3, and its tools, agents, RAG and memory are Scala APIs. LangChain4j, Spring AI, Koog and Embabel
  are Java or Kotlin libraries ([comparison](../guide/comparison#at-a-glance-by-audience), sources checked 2026-10-11).
- Errors are values (`Result[A]`) with typed error classes, not exceptions.
- A provider is a small published interface, `ProviderDescriptor`, so adding a provider is adding a dependency
  ([writing a provider](../guide/writing-a-provider)).
- A guardrail block is an outcome of the run (`AgentStatus.Blocked`), not an exception, and handoffs are routes inside one
  agent graph.

It also lacks things others have: LangChain4j and Spring AI each list far more vector stores, and LLM4S has no Scala
2.13 artifact. The [comparison with LangChain4j, Spring AI, Koog and Embabel](../guide/comparison) is a sourced
table with a "when not to choose LLM4S" section; read it before deciding.

## A first call

Add the core and the module of the provider you call (here OpenAI) to `build.sbt`:

```scala
libraryDependencies ++= Seq(
  "org.llm4s" %% "llm4s-core"   % "{{ site.data.project.latest_release }}",
  "org.llm4s" %% "llm4s-openai" % "{{ site.data.project.latest_release }}"
)
```

Name the model in `src/main/resources/application.conf`; with `OPENAI_API_KEY` set, that is the whole configuration:

```hocon
llm4s {
  providers {
    provider = "openai-main"

    openai-main {
      provider = "openai"
      model    = "gpt-4o-mini"
    }
  }
}
```

Then build a client from that configuration, and ask it a question:

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result

def connect(): Result[LLMClient] = for {
  providerConfig  <- Llm4sConfig.defaultProvider()
  registryService <- Llm4sConfig.modelRegistryService()
  given ModelRegistryService = registryService
  client <- LLMConnect.getClient(providerConfig)
} yield client
```

```scala
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.Conversation
import org.llm4s.types.Result

def ask(client: LLMClient, question: String): Result[String] = for {
  conversation <- Conversation.fromPrompts("You answer in one sentence.", question)
  completion   <- client.complete(conversation)
} yield completion.content
```

`connect().flatMap(client => ask(client, "What is Scala?"))` is a `Right` with the answer, or a `Left` saying what
went wrong. No API key? [Ollama](../getting-started/ollama-quickstart) runs a model on your machine for free.

## Where to go next

- [Getting started](../getting-started/): installation, configuration and a first example.
- [Coming from LangChain4j or Spring AI](../guide/migrating-from-langchain4j): the same tasks side by side.
- [Coming from sttp-ai or openai-scala-client](../guide/migrating-from-scala-llm-clients), if you already call a model
  from Scala.
- [Why LLM4S](/why-llm4s/): why build LLM features on the JVM at all, and what adopting LLM4S involves.
