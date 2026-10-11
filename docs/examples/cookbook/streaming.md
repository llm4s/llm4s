---
layout: page
title: Stream tokens to the terminal
parent: Cookbook
grand_parent: Examples
nav_order: 7
---

# Stream tokens to the terminal
{: .no_toc }

Print each piece of the reply as the model produces it, and get the whole completion at the end.
{: .fs-6 .fw-300 }

## The problem

A long answer can take seconds to generate. Streaming shows the user the first words at once.
`streamComplete` calls your function with each `StreamedChunk` as it arrives, and returns the whole `Completion`
when the reply ends, so you print as you go and still get the full text and usage afterwards.

## The program

The whole program, [`StreamingRecipe.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/cookbook/StreamingRecipe.scala). `script` is the stand-in for a model that answers without a
network or a key; `demo` runs the recipe and returns what to print.

```scala
package org.llm4s.samples.cookbook

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ AssistantMessage, CompletionOptions, Conversation, SystemMessage, UserMessage }
import org.llm4s.types.Result

/** The streamed reply, as the caller saw it arrive and as the client returned it at the end. */
final case class Streamed(pieces: Vector[String], text: String)

/** Recipe: print a reply to the terminal as it is generated, instead of waiting for all of it. */
object StreamingRecipe extends RecipeApp {

  val info: RecipeInfo = RecipeInfo(
    id = "streaming",
    title = "Stream tokens to the terminal",
    summary = "Print each piece of the reply as the model produces it, and get the whole completion at the end.",
    mainClass = "org.llm4s.samples.cookbook.StreamingRecipe"
  )

  def stream(client: LLMClient, question: String, onText: String => Unit): Result[Streamed] = {
    val conversation = Conversation(Seq(SystemMessage("Answer in two or three sentences."), UserMessage(question)))
    val pieces       = Vector.newBuilder[String]
    client
      .streamComplete(
        conversation,
        CompletionOptions().withMaxTokens(200),
        chunk =>
          // A chunk can carry a tool call, a finish reason or reasoning instead of text: print only the text.
          chunk.content.filter(_.nonEmpty).foreach { text =>
            pieces += text
            onText(text)
          }
      )
      .map(completion => Streamed(pieces.result(), completion.content))
  }

  /** Writes each piece as it arrives; `flush`, because `print` alone may hold the text back until a newline. */
  def toTerminal(text: String): Unit = {
    print(text)
    Console.flush()
  }

  /** Streams a fixed reply a word at a time, as [[ScriptedClient]] streams every reply. */
  def script: ScriptedClient = new ScriptedClient((_, _) =>
    Right(
      AssistantMessage(
        "Streaming sends the reply in pieces while the model is still writing it. " +
          "The user sees the first words at once instead of waiting for the whole answer."
      )
    )
  )

  def demo(client: LLMClient): Result[String] =
    stream(client, "Why stream a model's reply?", toTerminal).map { streamed =>
      s"\n(${streamed.pieces.size} pieces, ${streamed.text.length} characters)"
    }
}
```

## Run it

```bash
sbt "samples/runMain org.llm4s.samples.cookbook.StreamingRecipe"          # scripted client, no API key
sbt "samples/runMain org.llm4s.samples.cookbook.StreamingRecipe --live"   # the provider your configuration names
```

The first command needs nothing but sbt. The second uses the section `llm4s.providers.provider` names; see
[running the samples](../../getting-started/configuration#running-the-samples). CI runs every recipe against its scripted client, and checks
that the program on this page is the source file, so what you read here compiles and works.

## Use a real provider

Run with `--live`: every provider module streams. In an agent, build it with `Agent.builder(...).withStreaming()`
and listen with `agent.stream(...)`: the text arrives as `AgentEvents.TextDelta` events, along with tool calls and
steps; see [Streaming](../../guide/agents/streaming).

## Pitfalls

- A chunk is not always text: it can carry a piece of a tool call, a finish reason or reasoning, with no content.
  Print only `chunk.content`, and skip empty pieces.
- A chunk is a piece of a word as often as a whole one. Do not add spaces between chunks.
- The callback runs on the client's thread, while the request is open. Keep it quick: hand slow work (a database
  write, a UI update across threads) to something else.
- `print` may hold text back until a newline; flush after each piece.

[Back to the cookbook](../cookbook)
