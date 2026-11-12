---
layout: page
title: Build a Support Assistant
parent: Tutorials
nav_order: 1
has_children: true
description: "Build a customer-support assistant with LLM4S, one part at a time: first call, typed answers, tools and an agent, with exercises a test suite checks."
search_title: "Tutorial: build a support assistant with LLM4S"
---

# Build a support assistant with LLM4S
{: .no_toc }

Acme Shop is a small online shop, and its customers write in about orders, deliveries and refunds. Over this series
you build the assistant that answers them: first a plain answer, then a typed ticket the rest of the system can
route, then an agent that looks up the order and the refund policy before it answers.
{: .fs-6 .fw-300 }

## The parts

| Part | Adds | Shows |
|---|---|---|
| [1. First call](part-1-first-call) | An answer, then a streamed answer, from a configured provider | `Llm4sConfig`, `LLMConnect`, `Result` |
| [2. Typed answers](part-2-typed-answers) | Classify a message into a ticket | structured output, `derives` schemas |
| [3. Tools and an agent](part-3-tools) | Look up an order and the refund policy | typed tools, `ToolRegistry`, an agent |

Still to come, tracked in [#2292](https://github.com/llm4s/llm4s/issues/2292): answers from the help-centre docs
(RAG), safety and memory, testing with `llm4s-testkit` and a judge, and serving and observing the assistant.

## How the series works

The code of every part is in
[`modules/samples`](https://github.com/llm4s/llm4s/tree/main/modules/samples/src/main/scala/org/llm4s/samples/tutorial/supportassistant),
one file per part, and every part runs with **no API key**: a scripted client from
[`llm4s-testkit`](../../guide/testing-with-the-testkit) stands in for the model. Add `--live` to run the same code
against the provider your configuration names (see [running the samples](../../getting-started/configuration#running-the-samples));
a local [Ollama](../../getting-started/ollama-quickstart) model is enough.

```bash
sbt "samples/runMain org.llm4s.samples.tutorial.supportassistant.Part1FirstCall"          # scripted, no key
sbt "samples/runMain org.llm4s.samples.tutorial.supportassistant.Part1FirstCall --live"   # your provider
```

Each part has a spec, `Part1Spec` to `Part3Spec`, that runs the part's code against scripted clients and checks that
every Scala block on its page is the code in the source file, word for word. CI runs them on every pull request, so
the tutorial cannot drift from the library.

## Exercises

Each part ends with an exercise. Its stub is in
[`Exercises.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/tutorial/supportassistant/Exercises.scala):
a method whose body is `???`, marked `TODO`. Replace the `???` with your code and run the part's spec:

```bash
sbt "samples/testOnly *Part1Spec"
```

The case named "Your exercise 1" is reported as pending while the `???` is there, and fails until your code does what
the page asks. The worked solutions are in the
[`solutions`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/tutorial/supportassistant/solutions/Solutions.scala)
package, and the same spec checks them, so a solution never goes stale.

You need the [installation](../../getting-started/installation) done and a clone of the repository; Scala 3 and sbt
basics are enough.
