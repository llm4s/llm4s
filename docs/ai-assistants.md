---
layout: page
title: AI Coding Assistants
parent: Getting Started
nav_order: 13
description: "Point Copilot, Claude Code, Cursor and Codex at llm4s: llms.txt, llms-full.txt and a rules file that keeps generated code compiling."
---

# Using llm4s with AI coding assistants
{: .no_toc }

Give your coding assistant the current llm4s documentation and the ten rules it most often gets wrong.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

Assistants were trained mostly on older llm4s, or on none. They invent `Llm4sConfig` overloads that no longer exist,
read `LLM_MODEL`, call a provider from `llm4s-core` (which ships none), throw instead of returning `Result`, or reach
for `ConfigFactory.load()`. Two things fix most of it: the documentation in a form assistants read, and a short rules
file in your project.

## The documentation, as files

Both files are generated from this site's pages whenever the site is built, so they are never hand-edited copies.

| File | Contents |
|------|----------|
| [`/llms.txt`](/llms.txt) | A curated index ([llmstxt.org](https://llmstxt.org/)): the release version, then every page of the getting-started pages, the user guide, the cookbook and the Java and Kotlin guides, in navigation order, each with its one-line description. |
| [`/llms-full.txt`](/llms-full.txt) | The full text of those same pages in one file. Every Scala block comes from a page whose snippets are compiled against the release (`DocSnippets`); a block on any other page is preceded by `(not compile-checked)`. |
| [`/llm4s-rules.md`](/llm4s-rules.md) | The rules block below, as a file to drop into your project. |

`/llms.txt` follows the latest release. A project pinned to an older minor release can use the copy kept for it,
`/llms/<major.minor>/llms.txt` (with `llms-full.txt` and `llm4s-rules.md` beside it), for the latest minor and the one
before it.

## Point your assistant at llm4s

Put the rules file in the place your tool reads, and, for a tool that fetches URLs, give it `llms.txt`.

| Tool | Where the rules go | The documentation |
|------|--------------------|-------------------|
| Claude Code | `CLAUDE.md` (or `AGENTS.md`, imported from it) | Ask it to read `https://llm4s.org/llms-full.txt`, or add the URL to your `CLAUDE.md` |
| Codex | `AGENTS.md` | Name `https://llm4s.org/llms.txt` in `AGENTS.md` |
| GitHub Copilot | `.github/copilot-instructions.md` | Reference `https://llm4s.org/llms.txt` in a chat with `#fetch` |
| Cursor | a rule in `.cursor/rules/` | Add `https://llm4s.org/llms-full.txt` under Settings, Indexing and Docs |
| Metals with an MCP client | `AGENTS.md` or your client's instructions file | Whatever your client offers for URLs |

For example, to start a project with the rules:

```bash
curl -fsSL https://llm4s.org/llm4s-rules.md >> AGENTS.md
```

## The rules

This block is the content of `/llm4s-rules.md`. Its Scala examples are compiled with the rest of the documentation,
so they match the current release.

<!-- rules:start -->

## Rules for writing llm4s code

llm4s is Scala 3 on JDK 21. Follow these when you write or change code that uses it.

### 1. Return `Result[A]`; never throw

`Result[A]` is `Either[LLMError, A]`. A failing call is a `Left`, so compose with `for`, `map` and `fold`, and convert a
`Try` with `.toResult`.

```scala
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Conversation, UserMessage }
import org.llm4s.types.Result

def answer(client: LLMClient, question: String): Result[String] =
  client.complete(Conversation(Seq(UserMessage(question)))).map(_.content)
```

### 2. Load configuration with `Llm4sConfig`, once, at the edge of the application

Core code receives the client it needs as a parameter.

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result

def startup(): Result[LLMClient] = for {
  providerConfig <- Llm4sConfig.defaultProvider()
  registry       <- Llm4sConfig.modelRegistryService()
  given ModelRegistryService = registry
  client <- LLMConnect.getClient(providerConfig)
} yield client
```

### 3. Name the provider in `application.conf`, not in code

A chat provider is a named section under `llm4s.providers`, and `llm4s.providers.provider` names the default. Load the
default with `Llm4sConfig.defaultProvider()`.

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

### 4. A provider is its own dependency

`llm4s-core` ships no provider. Add the module of each provider you call (`llm4s-openai`, `llm4s-anthropic`,
`llm4s-gemini`, `llm4s-ollama`, `llm4s-openai-compatible`, ...) next to it, and other features likewise (`llm4s-agent`,
`llm4s-rag`, `llm4s-agent-tools`).

### 5. Bring a `ModelRegistryService` into scope as a `given`

Do it before `LLMConnect.getClient`, as in rule 2.

### 6. Never read `LLM_MODEL`

Nothing in the library reads it. The model is the `model` key of the provider section.

### 7. Never use `sys.env`, `System.getenv`, `ConfigFactory.load()` or `ConfigSource.default`

An API key comes from `llm4s.credentials.<provider>.apiKey`, which each provider module binds to the vendor's usual
variable (`OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, ...), or from the section's own `apiKey`. Environment variables reach
the configuration only through `${?VAR}` in a `.conf` file.

### 8. Build `CompletionOptions` with its `with` methods

Start from `CompletionOptions()` and chain `.withTemperature(...)`, `.withMaxTokens(...)`. Do not call `new` or `.copy`.

```scala
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation }

def ask(client: LLMClient, conversation: Conversation) = {
  val options = CompletionOptions().withTemperature(0.2).withMaxTokens(512)
  client.complete(conversation, options)
}
```

### 9. A duration is a `FiniteDuration`, not a number of milliseconds

Write `30.seconds`, never `timeoutMs = 30000`.

```scala
import scala.concurrent.duration._
import org.llm4s.toolapi.ToolExecutionConfig

val toolConfig = ToolExecutionConfig.default.withTimeout(30.seconds)
```

### 10. Use the current artifact names and the latest release version

The group is `org.llm4s`, every artifact is `llm4s-<module>` and Scala 3 only (`%%` in sbt, the `_3` suffix elsewhere);
`llm4s-core` is not `core`, and the Java API is `llm4s-java-api`. A project using several modules can import the
`llm4s-bom`. Take the version from the [installation page](/getting-started/installation), not from memory.

<!-- rules:end -->

## Does it help?

The rules and `llms.txt` target the failures above, which are the ones that make a first attempt not compile. The
five prompts to test them with are: "make a first call to OpenAI", "add a tool", "stream a reply", "RAG over a
folder" and "call llm4s from Java". Give each to your assistant with and without the rules and `llms.txt`, and count
the outputs that compile.
