---
layout: page
title: "1. First Call"
parent: Build a Support Assistant
grand_parent: Tutorials
nav_order: 1
description: "Support assistant tutorial, part 1: ask a model a question, stream the answer, and build the client from configuration with Llm4sConfig."
search_title: "Support assistant tutorial 1: first LLM call in Scala"
---

# Part 1: a first call
{: .no_toc }

The assistant's first job is to answer a customer's question. This part sends the question with a short system
prompt, streams the answer as it is generated, and builds the client from configuration.
{: .fs-6 .fw-300 }

The code is
[`Part1FirstCall.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/tutorial/supportassistant/Part1FirstCall.scala).

## Ask a question

An `LLMClient` is the one type your code talks to, whichever provider is behind it. A conversation is a sequence of
messages: the system message says who the assistant is, the user message is the customer's question.

```scala
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, SystemMessage, UserMessage }
import org.llm4s.types.Result

val instructions: String =
  "You are the support assistant of Acme Shop. Answer in at most three sentences."

def conversationFor(question: String): Conversation =
  Conversation(Seq(SystemMessage(instructions), UserMessage(question)))

def ask(client: LLMClient, question: String): Result[String] =
  client.complete(conversationFor(question), CompletionOptions()).map(_.content)
```

`complete` returns a `Result[Completion]`, which is `Either[LLMError, Completion]`: a provider error, a timeout or a
refused key is a `Left` you handle, never an exception. `.map(_.content)` keeps the answer's text and passes an
error through unchanged.

`ask` takes the client as a parameter instead of building one. That keeps it testable: `Part1Spec` hands it a
`ScriptedLLMClient` that answers from a script, and checks both the answer and the messages that were sent.

## Stream the answer

A customer waiting for an answer would rather see it appear. `streamComplete` calls `onChunk` with each piece as the
provider sends it, and still returns the whole completion at the end.

```scala
def askStreaming(client: LLMClient, question: String)(onText: String => Unit): Result[String] =
  client
    .streamComplete(conversationFor(question), CompletionOptions(), chunk => chunk.content.foreach(onText))
    .map(_.content)
```

## Build the client from configuration

Only the application's entry point knows which provider it uses. `Llm4sConfig.defaultProvider()` reads the section
that `llm4s.providers.provider` names in `application.conf`, and `LLMConnect.getClient` builds its client:

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.model.ModelRegistryService

def configuredClient(): Result[LLMClient] =
  for {
    providerConfig <- Llm4sConfig.defaultProvider()
    registry       <- Llm4sConfig.modelRegistryService()
    given ModelRegistryService = registry
    client <- LLMConnect.getClient(providerConfig)
  } yield client
```

With `OPENAI_API_KEY` set, this section is all the configuration the assistant needs (see
[Configuration](../../getting-started/configuration) for Anthropic, Gemini and Ollama):

```hocon
llm4s {
  providers {
    provider = "openai-main"
    openai-main {
      provider = "openai"
      model    = "gpt-4o-mini"
    }
  }
}
```

Run the part with its scripted client, then against your provider:

```bash
sbt "samples/runMain org.llm4s.samples.tutorial.supportassistant.Part1FirstCall"
sbt "samples/runMain org.llm4s.samples.tutorial.supportassistant.Part1FirstCall --live"
```

## Exercise 1: answer in the customer's language

Acme Shop has customers in France. Write `Part1Exercise.askIn(client, question, language)` in
[`Exercises.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/tutorial/supportassistant/Exercises.scala):
like `ask`, but the system message is `instructions` followed by `" Answer in <language>."`. Then run

```bash
sbt "samples/testOnly *Part1Spec"
```

The case "Your exercise 1" checks the messages your code sends and the answer it returns. Stuck? The solution is
`Part1Solution` in the
[`solutions`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/tutorial/supportassistant/solutions/Solutions.scala)
package.

Next: [Part 2, typed answers](part-2-typed-answers).
