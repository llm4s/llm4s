---
layout: page
title: Playground (Codespaces)
description: "Try LLM4S in the browser with no install and no API key: a GitHub Codespace with JDK 21, sbt, Scala CLI, Metals and a small local Ollama model."
search_title: "Try LLM4S in the browser with GitHub Codespaces"
parent: Getting Started
nav_order: 15
---

# Playground: try LLM4S in a Codespace
{: .no_toc }

[![Open in GitHub Codespaces](https://github.com/codespaces/badge.svg)](https://codespaces.new/llm4s/llm4s?quickstart=1)

Open the repository in a GitHub Codespace and run the samples in your browser: no JDK, sbt or Ollama to install, and
no API key. The codespace is the repository's [dev container](https://github.com/llm4s/llm4s/tree/main/.devcontainer):

- JDK 21 with Coursier, sbt and Scala CLI;
- VS Code with the Metals extension, for completion, navigation and errors in the editor;
- [Ollama](https://ollama.com) in a sidecar container, answering on `http://localhost:11434`, with a small chat model
  (`qwen2.5:0.5b`) and an embedding model (`nomic-embed-text`) pulled when the codespace is created.

The samples' `ollama-local` provider section reads `OLLAMA_MODEL` and `OLLAMA_BASE_URL`, which the container sets, so
the samples run against the local model unchanged.

1. TOC
{:toc}

## Start a codespace

Click the badge above, or on [github.com/llm4s/llm4s](https://github.com/llm4s/llm4s) choose **Code**, then
**Codespaces**, then **Create codespace on main**. The container asks for a 4-core machine (16 GB of memory); the
chat model needs about 1 GB of it. GitHub's free monthly allowance for personal accounts covers several hours of
use on that machine.

When the repository has Codespaces prebuilds turned on, the image is already built and sbt's dependencies for the
samples are already downloaded and compiled, so the first command below starts at once; the aim is a first answer
within three minutes of creating the codespace. Without a prebuild, the first `sbt` command fetches and compiles
everything first, which takes several minutes more.

The same container runs locally: open the repository in VS Code with the
[Dev Containers](https://code.visualstudio.com/docs/devcontainers/containers) extension and choose **Reopen in
Container**. It needs Docker.

## Five commands to run

Run these in the codespace's terminal, from the repository root.

**1. A first call.** One question to the model, and its answer:

```bash
sbt "samples/runMain org.llm4s.samples.basic.BasicLLMCallingExample"
```

**2. Streaming.** The answer printed token by token as it arrives, with the time to the first chunk:

```bash
sbt "samples/runMain org.llm4s.samples.streaming.BasicStreamingExample"
```

**3. A tool-calling agent.** An agent with a weather tool, which the model calls to answer:

```bash
sbt "samples/runMain org.llm4s.samples.agent.SingleStepAgentExample"
```

**4. RAG over the docs.** Embeds the pages of `docs/getting-started` with `nomic-embed-text`, then answers questions
from the passages it retrieves:

```bash
sbt "samples/runMain org.llm4s.samples.rag.DocumentQAExample docs/getting-started"
```

**5. The doctor.** Checks the JDK, the samples' configuration, the provider module, the local Ollama and its model,
and makes one small request to it:

```bash
sbt "configPolicy/runMain org.llm4s.configpolicy.DoctorCli --config modules/samples/src/main/resources/application.conf --local --live"
```

See [Doctor](../reference/doctor) for what each step checks. A small model is quick but not clever: expect short,
sometimes wrong answers, and an agent that does not always call its tool. The point is to see the code work end to
end; [Configuration](configuration#running-the-samples) shows how to point the same samples at a hosted provider.

## Changing the model

Set `OLLAMA_MODEL` to another model from the [Ollama library](https://ollama.com/library), pull it, and run the
samples again:

```bash
export OLLAMA_MODEL=llama3.2:1b
bash .devcontainer/pull-models.sh
sbt "samples/runMain org.llm4s.samples.basic.BasicLLMCallingExample"
```

A larger model needs a machine with more memory: a 7B model needs about 8 GB on its own.

## How it is tested

The workflow
[`devcontainer.yml`](https://github.com/llm4s/llm4s/blob/main/.github/workflows/devcontainer.yml) builds this
container nightly, and on every change to it, and runs the five commands above inside it against the in-container
model with
[`.devcontainer/playground-check.sh`](https://github.com/llm4s/llm4s/blob/main/.devcontainer/playground-check.sh). Each
command must exit 0 and show that it worked; the first call must print a non-empty answer.

## Next steps

- [First Example](first-example) - write your own first program
- [Ollama Quick Start](ollama-quickstart) - the same local setup on your own machine
- [Cookbook](../examples/cookbook) - complete programs for common tasks, each runnable without a key
