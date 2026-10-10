---
layout: page
title: Summarise a long document
parent: Cookbook
grand_parent: Examples
nav_order: 3
---

# Summarise a long document
{: .no_toc }

Cut a document into parts, summarise each part, then summarise the summaries (map-reduce).
{: .fs-6 .fw-300 }

## The problem

A document longer than the context window, or long enough to make one call slow and expensive, cannot be
summarised in one request. Map-reduce splits it: the sentence chunker cuts it into parts at sentence boundaries, each
part is summarised on its own (map), and the part summaries, in document order, are summarised once more (reduce).

## The program

The whole program, [`SummariseRecipe.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/cookbook/SummariseRecipe.scala). `script` is the stand-in for a model that answers without a
network or a key; `demo` runs the recipe and returns what to print.

```scala
package org.llm4s.samples.cookbook

import cats.syntax.traverse._
import org.llm4s.chunking.{ ChunkerFactory, ChunkingConfig }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ AssistantMessage, Conversation, MessageRole, SystemMessage, UserMessage }
import org.llm4s.types.Result

/** A summary and how many parts the document was cut into. */
final case class Summary(text: String, parts: Int)

/** Recipe: summarise a document longer than you want to send at once, by map-reduce. */
object SummariseRecipe extends RecipeApp {

  val info: RecipeInfo = RecipeInfo(
    id = "summarise",
    title = "Summarise a long document",
    summary = "Cut a document into parts, summarise each part, then summarise the summaries (map-reduce).",
    mainClass = "org.llm4s.samples.cookbook.SummariseRecipe"
  )

  val MapPrompt: String = "Summarise this part of a longer document in one sentence. Keep names and numbers."
  val ReducePrompt: String =
    "These are summaries of the parts of one document, in order. Write a three-sentence summary."

  def summarise(client: LLMClient, document: String, partSize: Int = 250): Result[Summary] = {
    val chunking = ChunkingConfig(targetSize = partSize, maxSize = partSize * 3 / 2, overlap = 0, minChunkSize = 0)
    val parts    = ChunkerFactory.sentence().chunk(document, chunking).map(_.content).toList
    def ask(instruction: String, text: String): Result[String] =
      client.complete(Conversation(Seq(SystemMessage(instruction), UserMessage(text)))).map(_.content)

    for {
      partial <- parts.traverse(part => ask(MapPrompt, part)) // map: one call per part
      summary <- partial match { // reduce: one more call, unless there was only one part
        case Seq(only) => Right(only)
        case several   => ask(ReducePrompt, several.zipWithIndex.map((s, i) => s"${i + 1}. $s").mkString("\n"))
      }
    } yield Summary(summary, parts.size)
  }

  val report: String = Seq(
    "The northern warehouse shipped 18,400 orders in the third quarter, 12 percent more than in the second. " +
      "Most of the growth came from the new coffee subscription, which now makes up a quarter of all orders.",
    "Late deliveries rose from 2 to 5 percent in August, when one of the two carriers lost a sorting hub to a fire. " +
      "Moving the affected routes to the second carrier brought the rate back to 2 percent by mid September.",
    "Returns stayed flat, under 2 percent of orders. The team plans to add a second packing line before the holiday peak, " +
      "at a cost of 140,000 euros, and to hire six seasonal staff in November."
  ).mkString("\n\n")

  /** Answers a part with its first sentence, and the summaries with a fixed three-sentence summary. */
  def script: ScriptedClient = new ScriptedClient((conversation, _) => {
    val instruction = conversation.messages.find(_.role == MessageRole.System).map(_.content).getOrElse("")
    val text        = conversation.messages.filter(_.role == MessageRole.User).map(_.content).mkString
    Right(
      AssistantMessage(
        if (instruction == MapPrompt) text.takeWhile(_ != '.') + "."
        else
          "Orders grew 12 percent, " +
            "led by the coffee subscription. A carrier outage pushed late deliveries to 5 percent in August, since " +
            "fixed. A second packing line and six seasonal staff are planned before the holiday peak."
      )
    )
  })

  def demo(client: LLMClient): Result[String] =
    summarise(client, report).map(summary => s"${summary.text}\n(from ${summary.parts} parts)")
}
```

## Run it

```bash
sbt "samples/runMain org.llm4s.samples.cookbook.SummariseRecipe"          # scripted client, no API key
sbt "samples/runMain org.llm4s.samples.cookbook.SummariseRecipe --live"   # the provider your configuration names
```

The first command needs nothing but sbt. The second uses the section `llm4s.providers.provider` names; see
[running the samples](../../getting-started/configuration#running-the-samples). CI runs every recipe against its scripted client, and checks
that the program on this page is the source file, so what you read here compiles and works.

## Use a real provider

Pass your provider's client to `summarise`, or run with `--live`. Set `partSize` (in characters) to what suits
your model: a few thousand characters per part is usual. The map calls are independent, so for a long document run
them concurrently (with a `Future` per part, or the effect library you use) instead of in sequence as here.

## Pitfalls

- Every part costs a call, and the reduce one more: a 100-part document is 101 calls.
- The reduce step sees only the summaries. A detail one part's summary dropped is gone; tell the map prompt what
  must be kept (here: names and numbers).
- When the part summaries together are still too long for one call, reduce in rounds: summarise them in groups,
  then summarise those.
- Parts are cut at sentence boundaries with no overlap; a point made across two parts can be split. Add overlap in
  `ChunkingConfig` if that matters for your documents.

[Back to the cookbook](../cookbook)
