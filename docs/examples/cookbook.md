---
layout: page
title: Cookbook
parent: Examples
nav_order: 1
has_children: true
---

# Cookbook
{: .no_toc }

Complete programs for the things you would actually build, each on one page: the problem, the program, how to run it,
what to change for a real provider, and the pitfalls.
{: .fs-6 .fw-300 }

Every recipe is a small Scala program in
[`modules/samples`](https://github.com/llm4s/llm4s/tree/main/modules/samples/src/main/scala/org/llm4s/samples/cookbook),
and every one runs with **no API key**: by default a scripted client stands in for the model, so you can see the whole
flow work in seconds. Add `--live` to run the same code against the provider your configuration names
(`llm4s.providers.provider`, see [running the samples](../getting-started/configuration#running-the-samples)).

CI runs every recipe against its scripted client on every pull request, and checks that the program on each page is
the source file, so what you read compiles and works.

## The recipes

| Recipe | What it shows |
|---|---|
| [Classify text into an enum](cookbook/structured-output) | `completeStructured` reads a reply into a case class whose category is one of a fixed set |
| [Extract structured data from an email](cookbook/email-extraction) | a nested schema with a list of line items, checked against the email |
| [Summarise a long document](cookbook/summarise) | map-reduce: summarise each part, then the summaries |
| [Answer questions over a folder of files](cookbook/folder-qa) | the RAG pipeline: ingest a folder, embed, retrieve, answer with sources |
| [An agent that calls two tools](cookbook/tool-calling) | a tool of your own and the built-in calculator, chained by the model |
| [A guardrailed chatbot](cookbook/guardrails) | input and output guardrails around an agent |
| [Stream tokens to the terminal](cookbook/streaming) | `streamComplete`: print the reply as it is generated |
| [Fall back between providers](cookbook/fallback) | `ReliableClient` retries, then a second provider |
| [Cache repeated calls](cookbook/caching) | `CachingLLMClient`: answer a repeated question without a model call |
| [Evaluate an answer with a judge](cookbook/judge) | LLM-as-judge: grade an answer against a reference, 1 to 5 |
| [Answer questions with keyword search](cookbook/document-qa) | a BM25 keyword index, no embedding model |
| [Remember facts between turns](cookbook/memory) | a memory manager feeding the system prompt |
| [Several agents in one graph](cookbook/multi-agent-graph) | parallel specialist agents and an editor, joined in a graph |

## Running a recipe

```bash
sbt "samples/runMain org.llm4s.samples.cookbook.StructuredOutputRecipe"          # scripted client, no API key
sbt "samples/runMain org.llm4s.samples.cookbook.StructuredOutputRecipe --live"   # your configured provider
```

For `--live`, the samples default to a local Ollama model (`ollama pull llama3` first). To use another provider, add
a section to the git-ignored `modules/samples/src/main/resources/application.local.conf` and select it:

```hocon
llm4s.providers {
  openai-main {
    provider = "openai"
    model    = "gpt-4o-mini"
  }
}
```

```bash
export OPENAI_API_KEY=sk-...
export LLM4S_PROVIDER=openai-main
sbt "samples/runMain org.llm4s.samples.cookbook.StructuredOutputRecipe --live"
```

## Adding a recipe

A recipe is a good first contribution. Copy one of the files, give it its own `RecipeInfo`, add it to
`Cookbook.apps` in `Recipe.scala`, and write its page in `docs/examples/cookbook/` with the program pasted in.
`CookbookDocsSpec` then tells you what is missing: the page, its sections, its place in the navigation, the links from
this page, the README and the examples index, or a program that differs from the source. For the samples that are
not recipes, see the [examples index](index).
