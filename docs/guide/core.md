---
layout: page
title: Core
description: "The core LLM4S guides: calling a model, providers, errors and reliability, typed prompts, structured output, multimodal content, caching, testing, images and speech."
parent: User Guide
nav_order: 100
has_children: true
---

# Core

What every LLM4S application uses, whatever else it builds on top: a client for a provider, a call, its `Result`, and
the options around it.
{: .fs-6 .fw-300 }

## Calling a model

- **[Basic Usage](basic-usage)** - Get started with LLM calls, client creation, and error handling
- **[Providers](providers)** - Overview of supported providers and how to configure them
- **[Error Handling](error-handling)** - Work with `Result[A]` and `LLMError`: pattern matching, for-comprehensions, every error type, recovery and testing
- **[Reliable LLM Calling](reliability)** - `ReliableClient`: retry policies, a circuit breaker, deadlines and local rate limiting around any client
- **[Cost Controls](cost-controls)** - Thinking and reasoning effort per provider, provider-side prompt caching and service tiers
- **[Caching](caching)** - Cache embeddings (exact) and model responses (semantic): configuration, keys, TTL, eviction and what the cache reports
- **[Your HTTP Client](http-transport)** - `llm4s-sttp` sends the provider clients' requests through the sttp backend your service already has

## Prompts and typed replies

- **[Typed Prompts](prompts)** - `prompt"..."` and `system"..."`: values rendered by a type class checked at compile time, and untrusted input fenced
- **[Structured Output](structured-output)** - `completeStructured`: typed replies from a schema, which providers enforce it, and what a bad reply looks like
- **[Derived Schemas](derived-schemas)** - Write the type once: `llm4s-schema-derivation` builds the `completeStructured` schema from a case class with `derives`
- **[JSON Libraries](json-libraries)** - Use circe, play-json, zio-json or jsoniter-scala with LLM4S: converting at the boundary, structured output and tools

## Images, speech and decisions

- **[Multimodal Content](multimodal)** - Send images and documents in a user message or a tool result, and check before the request whether the provider takes them
- **[Media Types](media-types)** - `llm4s-media`: the `MediaType`, `MediaCategory` and `MediaSource` vocabulary that multimodal messages, image generation and RAG extraction share
- **[Capability-Typed Clients](capabilities)** - Check once that a model can see images or call tools, and let the compiler refuse an unchecked client (experimental)
- **[Image Generation](image-generation)** - Generate images with OpenAI (DALL-E, GPT Image), Stability AI, Hugging Face or a Stable Diffusion server
- **[Speech](speech)** - Speech-to-text (STT) and text-to-speech (TTS), local and cloud
- **[STT Domain Model](stt-domain-model)** - `STTOptions`, `WordTimestamp`, `Transcription` and the `STTError` cases
- **[Jev decision model](jev)** - Ask TypeSafe's Jev typed questions (Noul, Choice, Score) and get typed answers: a decision client, not a chat model

## Testing and extending

- **[Testing with the Testkit](testing-with-the-testkit)** - Script an `LLMClient` in your tests: canned replies, tool calls, injected errors, recorded requests
- **[Writing a Provider](writing-a-provider)** - Publish your own provider module against the `llm4s-core` SPI
