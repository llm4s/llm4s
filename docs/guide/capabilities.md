---
layout: page
title: Capability-Typed Clients
description: "Check once that a model can see images or call tools, then let the Scala compiler refuse a client that was not checked (llm4s-capabilities, experimental)."
search_title: "Capability-typed LLM clients in Scala 3"
parent: User Guide
nav_order: 43
---

# Capability-Typed Clients
{: .no_toc }

Sending an image to a text-only model, or tools to a model without function calling, otherwise fails only when the
request reaches the provider. `llm4s-capabilities` checks the model once, at the application's edge, against the
model metadata llm4s already has, and from then on the compiler tracks what was checked.

> **Experimental.** The module is new and not part of the 1.0 frozen surface.

1. TOC
{:toc}

## Installation

```sbt
libraryDependencies += "org.llm4s" %% "llm4s-capabilities" % "{{ site.data.project.latest_release }}"
```

It depends on `llm4s-core` only.

## Checked once at the edge, typed afterwards

A capability set is an intersection of markers: `Vision`, `Tools`, `ParallelTools`, `StructuredOutput`,
`Reasoning`, `PromptCaching` and `SystemMessages`. `client.require[C](provider, model)` looks the model up in the
`ModelRegistryService` in scope and returns a `Capable[C]`, or a `ValidationError` that names every capability the
model lacks. Code that needs a capability takes a `Capable` of it:

```scala
import org.llm4s.capability.*

def caption(c: Capable[Vision], image: ContentPart.Image): Result[String] =
  c.describe(image, "One-line alt text").map(_.content)

def altText(client: LLMClient, image: ContentPart.Image)(using ModelRegistryService): Result[String] =
  for {
    capable <- client.require[Vision & Tools]("openai", "gpt-4o") // checked once, here
    alt     <- caption(capable, image)                             // compiles: Vision & Tools is a Vision
  } yield alt
```

`Capable` is covariant, so a `Capable[Vision & Tools]` is a `Capable[Vision]` and a `Capable[Tools]`. A client
checked only for tools is not one with vision, and passing it is a compile error
(`Found: Capable[Tools], Required: Capable[Vision]`). A set that holds anything other than the markers, or no
set at all (`client.require("openai", "gpt-4o")`), does not compile either.

The operations a capability allows are methods of `Capable`, thin layers over `LLMClient.complete`:

| Needs | Method | Sends |
|---|---|---|
| `Vision` | `describe(image, question, options)` | one user message: the image, then the question |
| `Tools` | `completeWithTools(conversation, tools, options)` | the conversation, with `tools` added to the options' tools |

`capable.client` is the client itself, for everything else.

## The run-time check is the authoritative one

`require` reads the registry at run time: the bundled model data of `llm4s-core` (`modeldata/`) with llm4s's
overrides, or the registry your application configures (`Llm4sConfig.modelRegistryService()`). A model the registry
does not mark as supporting a capability is refused, even when the provider might accept the request; a model it
does not know is refused with the registry's error.

## Models the registry does not know

For a local or fine-tuned model, state the capabilities yourself:

```scala
def local(client: LLMClient): Capable[Vision] =
  Capable.assume[Vision](client) // no check: visible in review
```

`assume` checks nothing; its `metadata` is `None`. The provider still refuses a request the model cannot serve.

## Not yet in this module

A compile-time model literal (`model"openai/gpt-4o"`, typed from the bundled data), a structured-output operation
on `Capable[StructuredOutput]`, and a runnable sample are tracked in
[#2331](https://github.com/llm4s/llm4s/issues/2331).
