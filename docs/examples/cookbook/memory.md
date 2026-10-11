---
layout: page
title: Remember facts between turns
parent: Cookbook
grand_parent: Examples
nav_order: 12
---

# Remember facts between turns
{: .no_toc }

Record what a user says, and put the relevant facts in the system prompt when the next question arrives.
{: .fs-6 .fw-300 }

## The problem

A model forgets everything between calls. To remember what a user told you, record it in a memory manager and,
when the next question arrives, retrieve the facts that relate to it and put them in the system prompt.

## The program

The whole program, [`MemoryRecipe.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/cookbook/MemoryRecipe.scala). `script` is the stand-in for a model that answers without a
network or a key; `demo` runs the recipe and returns what to print.

```scala
package org.llm4s.samples.cookbook

import org.llm4s.agent.memory.{ MemoryManager, SimpleMemoryManager }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ AssistantMessage, Conversation, MessageRole, SystemMessage, UserMessage }
import org.llm4s.types.Result

/** What the model said, and the remembered context it was shown. */
final case class Remembered(reply: String, context: String)

/**
 * Recipe: remember what the user told you, and use it on a later turn.
 *
 * Facts are recorded in a memory manager, and when the next question arrives the relevant ones are retrieved and put
 * in the system prompt. The in-memory store matches whole words, ignoring case and punctuation, so the question has to
 * share a word with the fact: "Which language do I prefer, Scala or Java?" finds "Prefers Scala over Java", but
 * "What do I like?" does not, and neither would "What do I prefer?" ("prefer" is not "Prefers").
 * {{{
 *   sbt "samples/runMain org.llm4s.samples.cookbook.MemoryRecipe"          # scripted client, no API key
 *   sbt "samples/runMain org.llm4s.samples.cookbook.MemoryRecipe --live"   # your configured provider
 * }}}
 */
object MemoryRecipe extends RecipeApp {

  val info: RecipeInfo = RecipeInfo(
    id = "memory",
    title = "Remember facts between turns",
    summary = "Record what a user says, then retrieve it into the prompt when it matters.",
    mainClass = "org.llm4s.samples.cookbook.MemoryRecipe"
  )

  val facts: Seq[String] = Seq("Prefers Scala over Java", "Works in the Berlin office")

  val question: String = "Which language do I prefer, Scala or Java?"

  def recall(client: LLMClient, facts: Seq[String], question: String): Result[Remembered] =
    for {
      manager <- facts.foldLeft[Result[MemoryManager]](Right(SimpleMemoryManager.empty)) { (manager, fact) =>
        manager.flatMap(_.recordUserFact(fact, Some("user-1"), Some(0.9)))
      }
      context <- manager.getRelevantContext(question)
      reply <- client.complete(
        Conversation(Seq(SystemMessage(s"What you know about the user:\n$context"), UserMessage(question)))
      )
    } yield Remembered(reply.content, context)

  /** Answers from the system prompt when it contains the fact, and admits it does not know otherwise. */
  def script: ScriptedClient = new ScriptedClient((conversation, _) => {
    val system = conversation.messages.find(_.role == MessageRole.System).map(_.content).getOrElse("")
    Right(AssistantMessage(if (system.contains("Prefers Scala")) "You prefer Scala." else "I do not know yet."))
  })

  def demo(client: LLMClient): Result[String] =
    recall(client, facts, question).map(r =>
      s"${r.reply}\n(context shown to the model: ${r.context.replace('\n', ' ')})"
    )
}
```

## Run it

```bash
sbt "samples/runMain org.llm4s.samples.cookbook.MemoryRecipe"          # scripted client, no API key
sbt "samples/runMain org.llm4s.samples.cookbook.MemoryRecipe --live"   # the provider your configuration names
```

The first command needs nothing but sbt. The second uses the section `llm4s.providers.provider` names; see
[running the samples](../../getting-started/configuration#running-the-samples). CI runs every recipe against its scripted client, and checks
that the program on this page is the source file, so what you read here compiles and works.

## Use a real provider

Pass your provider's client to `recall`, or run with `--live`. For memory that survives a restart, use the
SQLite or Postgres stores of `llm4s-memory` and `llm4s-memory-postgres`; see [Memory](../../guide/agents/memory).

## Pitfalls

- The in-memory store matches whole words, ignoring case and punctuation, so the question has to share a word
  with the fact: "Which language do I prefer, Scala or Java?" finds "Prefers Scala over Java", but "What do I like?"
  finds nothing, and neither does "What do I prefer?", because there is no stemming and "prefer" is not "Prefers"
  ([#1594](https://github.com/llm4s/llm4s/issues/1594)). For retrieval by meaning, use a store with embeddings.
- Record facts with a user id and retrieve by it, or one user's facts reach another's prompt.

[Back to the cookbook](../cookbook)
