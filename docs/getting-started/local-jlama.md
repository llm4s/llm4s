---
layout: page
title: First Answer, No Key, No Server
description: "A first LLM answer from Scala in five minutes with no API key and no model server: llm4s-jlama runs a small open model inside your JVM."
search_title: "Run an LLM inside the JVM from Scala with no API key: llm4s-jlama"
parent: Getting Started
nav_order: 17
---

# First answer in five minutes: no key, no server
{: .no_toc }

`llm4s-jlama` runs a small open model **inside your JVM** with [Jlama](https://github.com/tjake/Jlama), a pure-Java
inference engine. There is no API key to get, no daemon to install and no port to open: add one dependency, run, and
the first call downloads the model once (about 0.7 GB) and answers.
{: .fs-6 .fw-300 }

1. TOC
{:toc}

---

## What a 1B local model is good for

The default model, `tjake/Llama-3.2-1B-Instruct-JQ4`, is Llama 3.2 1B Instruct quantised to 4 bits. It is good for:

- **Learning llm4s**: every call, stream and conversation in the guides works against it.
- **Offline tests and CI**: no key, no network once the model is cached, no cost.
- **Demos and workshops**: a locked-down laptop, a Codespace or a classroom with bad Wi-Fi.

It is not good for real workloads. A 1B model gets facts wrong, follows long instructions loosely and does not call
tools (`llm4s-jlama` does not offer tool calling). It runs on the CPU, at a few to a few dozen tokens a second
depending on the machine. For real work use [Ollama](ollama-quickstart), which stays the recommended local server
(larger models, GPU), or a [cloud provider](configuration).

## What you need

- **JDK 21.** Tested with Eclipse Temurin 21.0.x. Jlama uses the JDK's Vector API, which is still an *incubator*
  module, so a JDK upgrade can break it: if a newer JDK fails, go back to 21.
- About **1 GB of disk** for the model and **2 GB of memory** for the JVM.
- Network access **once**, to download the model from Hugging Face to `~/.cache/llm4s/jlama`. Later runs load it from
  there and need no network.

`llm4s-jlama` is new: it is published with the first release after 0.5.1. Until that release, run the sample from a
clone of the repository with `sbt localJlamaDemo` (see [From a clone](#from-a-clone-of-the-repository)).
{: .note }

## The JVM flags

Jlama needs two JVM options:

```
--add-modules=jdk.incubator.vector --enable-preview
```

The first lets Jlama use the Vector API (SIMD); without it Jlama logs
`Java SIMD Vector API *not* available` and falls back to code many times slower. The second is what Jlama documents
for its optional native operations. Both examples below set them, so you never see the failure.

## With Scala CLI: one file

Save this as `first-answer.scala` and run `scala-cli run first-answer.scala`. Nothing else is needed: the provider
section is passed as system properties, so there is no `application.conf`.

```scala
//> using scala 3.7.1
//> using jvm 21
//> using dep org.llm4s::llm4s-core:{{ site.data.project.latest_release }}
//> using dep org.llm4s::llm4s-jlama:{{ site.data.project.latest_release }}
//> using dep org.slf4j:slf4j-simple:2.0.17
//> using javaOpt --add-modules=jdk.incubator.vector
//> using javaOpt --enable-preview
//> using javaOpt -Dllm4s.providers.provider=local
//> using javaOpt -Dllm4s.providers.local.provider=jlama
//> using javaOpt -Dllm4s.providers.local.model=tjake/Llama-3.2-1B-Instruct-JQ4

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.{ Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService

object FirstAnswer extends App {
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

`slf4j-simple` prints llm4s's and Jlama's logs at INFO. The first run logs the download, file by file with its size,
then prints the answer. The next run starts from the
cached model.

## With sbt

Add the dependency, and fork `run` with the flags:

```scala
libraryDependencies ++= Seq(
  "org.llm4s" %% "llm4s-core"  % "{{ site.data.project.latest_release }}",
  "org.llm4s" %% "llm4s-jlama" % "{{ site.data.project.latest_release }}"
)

run / fork := true
run / javaOptions ++= Seq("--add-modules=jdk.incubator.vector", "--enable-preview")
```

Name the model in `src/main/resources/application.conf`. No `apiKey`: the model runs in your process.

```hocon
llm4s.providers {
  provider = "local"

  local {
    provider = "jlama"
    model    = "tjake/Llama-3.2-1B-Instruct-JQ4"
    # modelDir = "/data/models"   # default: ~/.cache/llm4s/jlama
    # threads  = 4                # default: the physical core count
  }
}
```

The program is the same as any other provider's: the provider comes from the configuration.

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.{ Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService

object Main extends App {
  val answer = for {
    providerConfig <- Llm4sConfig.defaultProvider()
    registry       <- Llm4sConfig.modelRegistryService()
    given ModelRegistryService = registry
    client <- LLMConnect.getClient(providerConfig)
    completion <- client.streamComplete(
      Conversation(Seq(UserMessage("Write a haiku about the JVM."))),
      onChunk = chunk => chunk.content.foreach(print)
    )
    _ = client.close()
  } yield completion

  println()
  answer.left.foreach(error => println(s"Error: ${error.formatted}"))
}
```

`sbt run` streams the answer token by token. The model is loaded on the first call, not when the client is built,
and stays in memory until `close()`.

## From a clone of the repository

```bash
git clone https://github.com/llm4s/llm4s.git && cd llm4s
sbt localJlamaDemo
```

`localJlamaDemo` runs `org.llm4s.samples.basic.LocalJlamaExample` in a forked JVM with the flags above, against the
samples' `jlama-local` section.

## What works, and what does not

| | |
|---|---|
| `complete`, `streamComplete` | Yes |
| System, user and assistant messages | Yes, through the model's own chat template |
| Tool calling | No: a request with tools, or a conversation holding tool calls, is refused |
| Images, audio, files | No: text only |
| `responseFormat` / `completeStructured` | Not enforced: the model is asked, nothing constrains its output |
| Cancellation | An interrupted call stops after the current token and returns `CancelledError` |

Other Jlama models work too: any Hugging Face model Jlama can load, such as the pre-quantised `tjake/*-JQ4` and
`tjake/*-Jlama-Q4` models. Larger ones answer better and need more memory and time. All the keys are in the
[providers guide](../guide/providers#jlama-in-process).

## Next steps

- **[Ollama Quick Start](ollama-quickstart)**: larger local models on a server, with GPU support.
- **[Configuration](configuration)**: switch the same program to OpenAI, Anthropic, Gemini and others by changing
  one section.
- **[First Example](first-example)**: conversations, streaming and agents.
