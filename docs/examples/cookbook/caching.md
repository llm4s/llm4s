---
layout: page
title: Cache repeated calls
parent: Cookbook
grand_parent: Examples
nav_order: 9
---

# Cache repeated calls
{: .no_toc }

Wrap a client in CachingLLMClient so that a question asked again is answered without a model call.
{: .fs-6 .fw-300 }

## The problem

Many applications send the same prompt again and again: a help bot asked the same question by different users.
`CachingLLMClient` wraps any client: it embeds each prompt, and when a prompt at least `similarityThreshold` similar
was answered within the TTL with the same options, returns that answer without calling the model. The demo asks
three questions, the third a repeat of the first, and the model is called twice.

## The program

The whole program, [`CachingRecipe.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/cookbook/CachingRecipe.scala). `script` is the stand-in for a model that answers without a
network or a key; `demo` runs the recipe and returns what to print.

```scala
package org.llm4s.samples.cookbook

import cats.syntax.traverse._
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.caching.{ CacheConfig, CachingLLMClient }
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.model.{ AssistantMessage, Conversation, UserMessage }
import org.llm4s.llmconnect.{ EmbeddingClient, LLMClient }
import org.llm4s.model.ModelRegistryService
import org.llm4s.trace.NoOpTracing
import org.llm4s.types.Result

import scala.concurrent.duration._

/** Recipe: answer a repeated question from a cache instead of calling the model again. */
object CachingRecipe extends RecipeApp {

  val info: RecipeInfo = RecipeInfo(
    id = "caching",
    title = "Cache repeated calls",
    summary = "Wrap a client in CachingLLMClient so that a question asked again is answered without a model call.",
    mainClass = "org.llm4s.samples.cookbook.CachingRecipe"
  )

  /** `client`, behind a cache that answers a prompt at least 0.98 similar to one seen in the last hour. */
  def cached(client: LLMClient): Result[LLMClient] =
    for {
      models <- Llm4sConfig.modelRegistryService()
      config <- CacheConfig.create(similarityThreshold = 0.98, ttl = 1.hour, maxSize = 500)
    } yield {
      given ModelRegistryService = models
      new CachingLLMClient(
        baseClient = client,
        // The stand-in embedding model; see "Use a real provider".
        embeddingClient = new EmbeddingClient(BagOfWordsEmbeddings.provider),
        embeddingModel = EmbeddingModelConfig(BagOfWordsEmbeddings.Model, BagOfWordsEmbeddings.Dimensions),
        config = config,
        tracing = new NoOpTracing
      )
    }

  /** Asks each question in turn through one cache, so a repeat is answered from it. */
  def askAll(client: LLMClient, questions: Seq[String]): Result[Seq[String]] =
    cached(client).flatMap { cache =>
      questions.toList.traverse(q => cache.complete(Conversation(Seq(UserMessage(q)))).map(_.content))
    }

  val questions: Seq[String] =
    Seq("When does the office open?", "Do you ship to Canada?", "When does the office open?")

  /** Numbers its answers, so the output shows which ones the model really gave. */
  def script: ScriptedClient =
    new ScriptedClient((conversation, call) => Right(AssistantMessage(s"(model call $call) ${answerTo(conversation)}")))

  private def answerTo(conversation: Conversation): String =
    if (conversation.messages.exists(_.content.contains("ship"))) "Yes, we ship to Canada." else "We open at nine."

  def demo(client: LLMClient): Result[String] =
    askAll(client, questions).map(answers => questions.zip(answers).map((q, a) => s"$q -> $a").mkString("\n"))
}
```

## Run it

```bash
sbt "samples/runMain org.llm4s.samples.cookbook.CachingRecipe"          # scripted client, no API key
sbt "samples/runMain org.llm4s.samples.cookbook.CachingRecipe --live"   # the provider your configuration names
```

The first command needs nothing but sbt. The second uses the section `llm4s.providers.provider` names; see
[running the samples](../../getting-started/configuration#running-the-samples). CI runs every recipe against its scripted client, and checks
that the program on this page is the source file, so what you read here compiles and works.

## Use a real provider

The chat client is your provider's (`--live` uses it). Replace the stand-in embeddings with a real embedding
client, built from `llm4s.embeddings` (see [Embeddings configuration](../../getting-started/configuration#embeddings-configuration)),
with a `ModelRegistryService` and `given ProviderRegistry = ProviderRegistry.default` in scope:

```scala
embeddingClient <- Llm4sConfig.embeddings().flatMap((provider, config) => EmbeddingClient.from(provider, config))
```

and give `embeddingModel` that model's name and dimensions. See [Caching](../../guide/caching) for the trade-offs.

## Pitfalls

- The cache is approximate: two prompts the embedding model finds close get the same answer. Keep the threshold
  high (0.95 or more) and test it on prompts that must not share an answer ("open" and "close").
- Every call, hit or miss, embeds the prompt: the cache pays off only when a model call costs more than an embedding
  call and the hit rate is high.
- Do not cache answers that depend on the time, the user or fresh data.
- The cache is in memory, in the object you create: build it once and share it. `streamComplete` bypasses it.

[Back to the cookbook](../cookbook)
