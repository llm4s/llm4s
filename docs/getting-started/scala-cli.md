---
layout: page
title: Scala CLI Quick Start
parent: Getting Started
nav_order: 7
---

# Scala CLI Quick Start
{: .no_toc }

A first LLM call from a single file, with no sbt project.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

[Scala CLI](https://scala-cli.virtuslab.org/) runs a Scala file with its dependencies declared in the
file itself, so you can try LLM4S without creating a build. This page is the shortest path from nothing
to an answer; when you want a real project, use the [starter kit](installation#quick-start-with-the-starter-kit)
or [add LLM4S to your build](installation#add-llm4s-to-your-project).

## Before you start

- **Scala CLI**, installed as described on its [install page](https://scala-cli.virtuslab.org/install).
- **A JDK**. LLM4S targets JDK 21 (see [Installation](installation#prerequisites)); check yours with `java -version`.
- **An API key** for OpenAI, or a local [Ollama](ollama-quickstart) (no key).

## The files

Make a directory with three files. The same files are in
[`modules/samples/scala-cli`](https://github.com/llm4s/llm4s/tree/main/modules/samples/scala-cli).

```
hello-llm4s/
  hello.scala
  logback.xml
  resources/application.conf
```

`hello.scala`:

```scala
//> using scala 3.7.1
//> using dep org.llm4s::llm4s-core:{{ site.data.project.latest_release }}
//> using resourceDir resources
//> using javaOpt -Dlogback.configurationFile=logback.xml

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.{ Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService

@main def hello(): Unit = {
  val answer = for {
    providerConfig <- Llm4sConfig.defaultProvider()
    registry       <- Llm4sConfig.modelRegistryService()
    given ModelRegistryService = registry
    client     <- LLMConnect.getClient(providerConfig)
    completion <- client.complete(Conversation(Seq(UserMessage("In one sentence, what is Scala?"))))
  } yield completion.content

  answer.fold(error => println(s"Error: ${error.formatted}"), text => println(text))
}
```

`resources/application.conf` names the providers the script can use. The default is the section
`provider` names; the [Configuration guide](configuration#named-provider-sections) covers the others:

```hocon
llm4s {
  providers {
    provider = "openai-main"

    openai-main {
      provider = "openai"
      model    = "gpt-4o-mini"
      apiKey   = ${?OPENAI_API_KEY}
    }

    # No API key: needs `ollama serve` and a pulled model
    ollama-local {
      provider = "ollama"
      model    = "llama3"
      baseUrl  = "http://localhost:11434"
      baseUrl  = ${?OLLAMA_BASE_URL}
    }
  }
}
```

`logback.xml` keeps the library's own logging out of your first answer (without it, logback prints
every debug message):

```xml
<configuration>
  <appender name="STDERR" class="ch.qos.logback.core.ConsoleAppender">
    <target>System.err</target>
    <encoder><pattern>%d{HH:mm:ss} %-5level %logger{20} - %msg%n</pattern></encoder>
  </appender>
  <root level="WARN"><appender-ref ref="STDERR"/></root>
</configuration>
```

## Run it

Run from inside the directory: the `logback.xml` path in the script is relative to where you run it.

```bash
cd hello-llm4s
export OPENAI_API_KEY=sk-...
scala-cli run .
```

With a local Ollama instead, no key needed (`-D` system properties override `application.conf`, so this
picks the other section):

```bash
scala-cli run . --java-opt -Dllm4s.providers.provider=ollama-local
```

The first run downloads the dependencies, which takes a while. You should see one sentence, for example:

```
Scala is a language that blends object-oriented and functional programming on the JVM.
```

The wording depends on the model.

## What the script does

1. `Llm4sConfig.defaultProvider()` reads the section that `llm4s.providers.provider` names. This is the one place
   configuration is read.
2. `LLMConnect.getClient` builds the client for that provider.
3. `client.complete` sends the conversation. Every step returns a `Result`, so the `for` stops at the first
   error and the last line prints it instead of throwing.

## If something goes wrong

**`ConfigurationError: OpenAI provider 'openai-main' is missing required fields: apiKey`**: the key is not
set in the shell that runs `scala-cli`. Check `echo $OPENAI_API_KEY`, or use the Ollama command above.

**A wall of `DEBUG` lines before the answer**: `logback.xml` was not found. Run from the directory that
contains it, and check the name and the `javaOpt` line.

**The version**: the `//> using dep` line pins the release. Change the number to move to another one.

## With 0.5.0 and later

Up to the latest release, `llm4s-core` includes the provider clients, so the single dependency above is
enough. From 0.5.0 the provider clients are separate modules: add the one you use next to core, for example

```scala
//> using dep org.llm4s::llm4s-openai:<the 0.5.0 release or later>
//> using dep org.llm4s::llm4s-ollama:<the 0.5.0 release or later>
```

(only the modules you use). They are not published yet, so those lines do not resolve today. Keep
`apiKey = ${?OPENAI_API_KEY}` in the section: the latest release needs it, and a section's own `apiKey`
takes precedence over the one the provider module binds, so it does no harm with 0.5.0.

The published modules also stop bringing a logging backend, as the
[migration guide](/reference/migration#llm4s-no-longer-brings-a-logging-backend) describes: without
one, SLF4J prints a single "no SLF4J providers were found" warning and `logback.xml` has no effect. To keep the
quiet output above, add one yourself with a Java dependency (a single colon, unlike the `::` Scala form):

```scala
//> using dep ch.qos.logback:logback-classic:1.5.34
```

## Next steps

- [First Example](first-example): the same call in a project, with conversation context, tools and streaming
- [Configuration](configuration): all providers and how keys are found
- [Ollama Quick Start](ollama-quickstart): local models
