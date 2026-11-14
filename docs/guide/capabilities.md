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

It depends on `llm4s-core`. `completeStructuredOf` also needs `llm4s-schema-derivation`, an optional dependency:
add it to use that method.

## Checked once at the edge, typed afterwards

A capability set is an intersection of markers: `Vision`, `Tools`, `ParallelTools`, `StructuredOutput`,
`Reasoning`, `PromptCaching` and `SystemMessages`. `client.requireCapabilities[C](provider, model)` looks the
model up in the `ModelRegistryService` in scope and returns a `Capable[C]`, or a `ValidationError` that names every
capability the model lacks. Code that needs a capability takes a `Capable` of it:

```scala
import org.llm4s.capability.*

def caption(c: Capable[Vision], image: ContentPart.Image): Result[String] =
  c.describe(image, "One-line alt text").map(_.content)

def altText(client: LLMClient, image: ContentPart.Image)(using ModelRegistryService): Result[String] =
  for {
    capable <- client.requireCapabilities[Vision & Tools]("openai", "gpt-4o") // checked once, here
    alt     <- caption(capable, image)                                         // compiles: Vision & Tools is a Vision
  } yield alt
```

`Capable` is covariant, so a `Capable[Vision & Tools]` is a `Capable[Vision]` and a `Capable[Tools]`. A client
checked only for tools is not one with vision, and passing it is a compile error
(`Found: Capable[Tools], Required: Capable[Vision]`). A set that holds anything other than the markers, or no
set at all (`client.requireCapabilities("openai", "gpt-4o")`), does not compile either.

The operations a capability allows are methods of `Capable`, thin layers over `LLMClient.complete`:

| Needs | Method | Sends |
|---|---|---|
| `Vision` | `describe(image, question, options)` | one user message: the image, then the question |
| `Tools` | `completeWithTools(conversation, tools, options)` | the conversation, with `tools` added to the options' tools |
| `StructuredOutput` | `completeStructuredOf[A](conversation, options)` | what `llm4s-schema-derivation`'s `client.completeStructuredOf[A]` sends |

`capable.client` is the client itself, for everything else, and `capable.capabilities` lists what it was checked
(or assumed) for as `Capability` values (`Capability.Vision`, ...).

## A model named at compile time

`model"provider/model"` names a model of the model data bundled with `llm4s-core`, checked by the compiler. Its
type is a `ModelRef` of the capabilities that data marks the model as having, and `requireCapabilities` takes it:

```scala
val gpt4o = model"openai/gpt-4o" // ModelRef[Vision & Tools & ParallelTools & StructuredOutput & PromptCaching & SystemMessages]

def structured(client: LLMClient)(using ModelRegistryService): Result[Capable[Vision & StructuredOutput]] =
  client.requireCapabilities[Vision & StructuredOutput](gpt4o) // checked again, at run time
```

A model the data does not know does not compile, and the error suggests the closest one it does:
`model"openai/gpt-4oo"` fails with `unknown model openai/gpt-4oo in the bundled model data; did you mean
openai/gpt-4o?`. Assigning `model"openai/o3-mini"` to a `ModelRef[Vision]` does not compile either, since the data
does not mark o3-mini as seeing images. `requireCapabilities(ref)` checks the literal's whole set;
`requireCapabilities[C](ref)` checks only `C`, which must be part of it.

## The run-time check is the authoritative one

The type of `model"..."` is the compile-time view: the snapshot of the model data in the `llm4s-core` the code was
compiled against. `requireCapabilities` reads the registry at run time: the bundled model data of `llm4s-core`
(`modeldata/`) with llm4s's overrides, or the registry your application configures
(`Llm4sConfig.modelRegistryService()`). The two can differ, so a `ModelRef` is checked again when it is passed to
`requireCapabilities`, and only the run-time result counts. A model the registry does not mark as supporting a
capability is refused, even when the provider might accept the request; a model it does not know is refused with
the registry's error.

## Models the registry does not know

For a local or fine-tuned model, state the capabilities yourself:

```scala
def local(client: LLMClient): Capable[Vision] =
  Capable.assume[Vision](client) // no check: visible in review
```

`assume` checks nothing; its `metadata` is `None`. The provider still refuses a request the model cannot serve.

## A runnable sample

`org.llm4s.samples.capability.CapabilityTypedClientExample` checks gpt-4o against the bundled data, captions an
image and extracts a structured caption from a scripted client, so it runs offline:

```bash
sbt "samples/runMain org.llm4s.samples.capability.CapabilityTypedClientExample"
```
