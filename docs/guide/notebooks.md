---
layout: page
title: Jupyter Notebooks
description: "Run LLM4S in Jupyter with the Almond Scala kernel: two offline notebooks, configuration inside a notebook, and known limits."
search_title: "Run LLM4S in Jupyter notebooks with the Almond Scala kernel"
parent: User Guide
nav_order: 52
---

# Jupyter Notebooks
{: .no_toc }

Try LLM4S in a Jupyter notebook with [Almond](https://almond.sh), the Scala kernel.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

Two notebooks in
[`modules/samples/notebooks`](https://github.com/llm4s/llm4s/tree/main/modules/samples/notebooks) are a starting point:

| Notebook | What it shows |
|----------|---------------|
| [`getting-started.ipynb`](https://github.com/llm4s/llm4s/blob/main/modules/samples/notebooks/getting-started.ipynb) | A completion; an answer streamed into one cell output, updated in place; structured output with `derives SchemaOf`; a typed tool the model calls |
| [`rag.ipynb`](https://github.com/llm4s/llm4s/blob/main/modules/samples/notebooks/rag.ipynb) | A handful of documents embedded into the in-memory vector store, searched, and a question answered from the closest ones |

Both run **offline** as committed: the chat model is `llm4s-testkit`'s `ScriptedLLMClient` (see
[Testing with the Testkit](testing-with-the-testkit)) and the RAG notebook's embedder is a small word-hashing
function, so they need no API key and no model server. A cell marked **Switch to a real provider** points each at a
real model. CI executes both, top to bottom, on every change to them.

## Install Almond

You need JDK 21, Python 3 with Jupyter (`pip install jupyterlab`, or `notebook`), and
[Coursier](https://get-coursier.io/docs/cli-installation). Install the kernel with Coursier:

```bash
cs launch --use-bootstrap almond:0.14.5 --scala 3.7.3 -- --install --id scala --display-name "Scala 3.7"
jupyter kernelspec list   # lists `scala`
```

Then start `jupyter lab`, open a notebook and pick the **Scala 3.7** kernel.

**Scala version.** LLM4S is built with Scala 3.7.1. Almond publishes no kernel for 3.7.1; 0.14.5's newest 3.7 kernel
is 3.7.3, which reads LLM4S's TASTy (a later 3.7 compiler reads an earlier one's). The kernel runs on the `java` first
on your `PATH` when Jupyter starts it, so make that JDK 21.

## Loading LLM4S

The first cell of each notebook loads LLM4S with Scala CLI's dependency directives, which Almond reads:

```scala
//> using dep org.llm4s::llm4s-core:{{ site.data.project.latest_release }}
//> using dep org.llm4s::llm4s-schema-derivation:{{ site.data.project.latest_release }}
//> using dep org.llm4s::llm4s-testkit:{{ site.data.project.latest_release }}
```

Add the provider module you want to call, as for any LLM4S application ([Installation](../getting-started/installation)):
`llm4s-openai`, `llm4s-anthropic`, `llm4s-ollama` and so on. The notebooks pin the latest release, and a check in CI
fails when they fall behind it.

## Configuration inside a notebook

A notebook loads configuration the way an application does, through `Llm4sConfig` (see
[Configuration](../getting-started/configuration)), from system properties set in a cell **before the first
`Llm4sConfig` call**:

- **an `application.conf` file**: the kernel has no resources directory of its own (Almond does not read
  `//> using resourceDir`), so point Typesafe Config's `config.file` property at it:

```scala
System.setProperty("config.file", "/path/to/application.conf")
```

- **individual settings**, which take precedence over `application.conf`:

```scala
System.setProperty("llm4s.providers.provider", "ollama-local")
System.setProperty("llm4s.providers.ollama-local.provider", "ollama")
System.setProperty("llm4s.providers.ollama-local.model", "llama3.2")
```

A key is read from the vendor's environment variable through the provider module's `reference.conf` (`OPENAI_API_KEY`
for `llm4s-openai`, and so on), so set it in the shell that starts Jupyter. Never read `sys.env` in the notebook.

In the notebooks, set `useRealProvider = true` in the **Switch to a real provider** cell. The getting-started notebook
then loads the section `llm4s.providers.provider` names; the RAG notebook also embeds with the model
`llm4s.embeddings` configures. The `assert`s check only the scripted answers, so a real model's answers are printed,
not checked.

## Known limits

- **Restart the kernel after changing dependencies.** A `//> using dep` line takes effect when its cell runs; a
  version changed or removed later is not unloaded. Restart the kernel and run from the top.
- **Configuration is read once.** `Llm4sConfig` reads Typesafe Config, which caches what it has loaded: a system
  property (or `config.file`) set after the first load is not seen until the kernel restarts.
- **`Math` in a cell is Almond's.** Almond imports `almond.display.*`, whose `Math` displays a formula; write
  `java.lang.Math` for the JDK's.
- **Streaming updates one output.** The getting-started notebook streams into a `Markdown` display with
  `withContent(...).update()`. A notebook saved after a headless run (`jupyter nbconvert --execute`) may show that
  output empty, because the update is a separate message.

## Running the notebooks headless

CI runs them as you can locally, from the repository root:

```bash
pip install nbconvert
jupyter nbconvert --to notebook --execute --output-dir /tmp/executed \
  modules/samples/notebooks/getting-started.ipynb modules/samples/notebooks/rag.ipynb
```

Any cell error, a compile error or a failed `assert`, fails the run. Commit notebooks with their outputs stripped
(`jupyter nbconvert --clear-output --inplace modules/samples/notebooks/*.ipynb`); `scripts/check-notebooks.sh` fails
otherwise.
