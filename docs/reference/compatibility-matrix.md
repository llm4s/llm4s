---
layout: page
title: Compatibility Matrix
parent: Reference
nav_order: 24
---

# Compatibility Matrix
{: .no_toc }

Which provider supports what with LLM4S, and which versions of the stack around it LLM4S is built and tested with.
Both tables are generated from the code, not written by hand: `sbt generateCompatibilityMatrix` writes
`docs/_data/capabilities.yml` and `docs/_data/tested-versions.yml`, and CI runs it again on every pull request and
fails when either committed file differs. A new provider module or a dependency bump cannot ship with a stale table.

1. TOC
{:toc}

## Provider capabilities

One row per provider id that the provider modules of this repository register. The facts come from each module's
`ProviderDescriptor` and `EmbeddingProviderDescriptor`, found through its `META-INF/services` entry the way an
application finds them ([writing a provider](../guide/writing-a-provider)):

- **Chat** and **Embeddings**: the artifact whose descriptor registers the id, if any. One id can be served by two
  artifacts, as Cohere is.
- **Streaming** and **Tool calling**: the descriptor's `ProviderFeatures`.
- **API key**: whether a section must have one (`ProviderConfigSpec.requiresApiKey`). *not required* covers both a
  provider with no key (Ollama) and one that uses its platform's own credentials (Amazon Bedrock, Vertex AI), and a
  provider whose key is optional (the generic OpenAI-compatible one).
- **Workload identity**: whether a section can authenticate with an identity token instead of a key (the descriptor
  declares `authExtras`).
- **`@Cloud` smoke suites**: the live-API suites in `modules/it` for that provider, run by `sbt testSmoke`.

| Provider id | Aliases | Chat | Streaming | Tool calling | API key (chat) | Workload identity | Embeddings | API key (embeddings) | `@Cloud` smoke suites |
|---|---|---|---|---|---|---|---|---|---|
{% for p in site.data.capabilities.providers %}| `{{ p.id }}` | {{ p.aliases | join: ", " }} | {% if p.chat %}`{{ p.chat_artifact }}`{% else %}-{% endif %} | {% if p.chat %}{% if p.streaming %}yes{% else %}no{% endif %}{% else %}-{% endif %} | {% if p.chat %}{% if p.tool_calling %}yes{% else %}no{% endif %}{% else %}-{% endif %} | {% if p.chat %}{{ p.chat_api_key }}{% else %}-{% endif %} | {% if p.chat %}{% if p.chat_workload_identity %}yes{% else %}no{% endif %}{% else %}-{% endif %} | {% if p.embeddings %}`{{ p.embedding_artifact }}`{% else %}-{% endif %} | {% if p.embeddings %}{{ p.embedding_api_key }}{% else %}-{% endif %} | {{ p.cloud_smoke | join: ", " }} |
{% endfor %}

### Not declared yet

`ProviderFeatures` holds two flags today, streaming and tool calling. Structured output, vision input, reasoning
effort and token usage on streams are not declared by any descriptor yet, so the matrix says *not declared* rather
than repeating claims from prose; a provider's own section of the [providers guide](../guide/providers) describes what
it does. A column changes here when a module declares the fact in code.

| Provider id | Structured output | Vision input | Reasoning effort | Usage on streams |
|---|---|---|---|---|
{% for p in site.data.capabilities.providers %}| `{{ p.id }}` | {{ p.structured_output }} | {{ p.vision_input }} | {{ p.reasoning_effort }} | {{ p.stream_usage }} |
{% endfor %}

Any endpoint that speaks the OpenAI chat-completions API also works through the generic `openai-compatible` id with
configuration alone ([recipes](../guide/providers#openai-compatible-endpoints)).

## Tested versions

Read from the build: the Scala version and the JDK floor from `project/Dependencies.scala`, the JDKs and the latest
Spring Boot releases from the CI workflow, Kotlin from the Kotlin API's Gradle build and Quarkus from the Quarkus
sample.

- **Scala**: {{ site.data["tested-versions"].scala }}, Scala 3 only.
- **Scala 2.13**: call-only, via the TASTy reader ([Using LLM4S from Scala 2.13](../getting-started/scala-2-13)); CI
  runs its probes on 2.13.16 and the latest 2.13 on every change to the frozen modules.
- **Minimum JDK**: {{ site.data["tested-versions"].jdk_minimum }}. CI runs {{ site.data["tested-versions"].jdk_ci | join: ", " }}.

| Stack | Versions | Where | Note |
|---|---|---|---|
{% for s in site.data["tested-versions"].stacks %}| {{ s.name }} | {{ s.versions | join: ", " }} | `{{ s.artifact }}` | {{ s.note }} |
{% endfor %}

A version here is the one the build compiles and tests against, not a range LLM4S promises. What you can rely on
across upgrades is in the [compatibility and deprecation policy](compatibility-policy); for resolving a version
conflict in your own build, see [dependency conflicts](dependency-conflicts).
