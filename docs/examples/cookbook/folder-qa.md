---
layout: page
title: Answer questions over a folder of files
parent: Cookbook
grand_parent: Examples
nav_order: 4
---

# Answer questions over a folder of files
{: .no_toc }

Ingest every file in a folder into the RAG pipeline, retrieve the passages for a question, and answer from them.
{: .fs-6 .fw-300 }

## The problem

You have a folder of documents (here a three-file handbook) and want answers drawn from them, with the files
they came from. `llm4s-rag`'s `RAG` pipeline reads every supported file in a folder (text, Markdown, PDF, Word and
more), chunks and embeds it, keeps it in an in-memory vector store and keyword index, and answers a question from the
passages that match best (hybrid search). The recipe runs offline: `BagOfWordsEmbeddings`, a stand-in embedding model
in [`Recipe.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/cookbook/Recipe.scala), turns text into word-count vectors without a network call.

## The program

The whole program, [`FolderQaRecipe.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/cookbook/FolderQaRecipe.scala). `script` is the stand-in for a model that answers without a
network or a key; `demo` runs the recipe and returns what to print.

```scala
package org.llm4s.samples.cookbook

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ AssistantMessage, MessageRole }
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.model.ModelRegistryService
import org.llm4s.rag.{ RAG, RAGAnswerResult }
import org.llm4s.types.{ Result, TryOps }

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import scala.util.Try

/** Recipe: answer questions over a folder of files with the RAG pipeline (embeddings, hybrid search, an answer). */
object FolderQaRecipe extends RecipeApp {

  val info: RecipeInfo = RecipeInfo(
    id = "folder-qa",
    title = "Answer questions over a folder of files",
    summary =
      "Ingest every file in a folder into a RAG pipeline, retrieve the passages for a question, answer from them.",
    mainClass = "org.llm4s.samples.cookbook.FolderQaRecipe"
  )

  def answer(client: LLMClient, folder: Path, question: String): Result[RAGAnswerResult] = {
    // The stand-in embedding model, registered like any provider module's; see "Use a real provider".
    given ProviderRegistry = ProviderRegistry.default.withEmbeddingProvider(BagOfWordsEmbeddings)
    for {
      models <- Llm4sConfig.modelRegistryService()
      given ModelRegistryService = models
      config = RAG
        .builder()
        .withEmbeddings(BagOfWordsEmbeddings.id.asString, BagOfWordsEmbeddings.Model, BagOfWordsEmbeddings.Dimensions)
        .withTopK(2)
        .withLLM(client)
      rag <- RAG.build(config, _ => Right(BagOfWordsEmbeddings.config))
      result = rag.ingest(folder).flatMap(_ => rag.queryWithAnswer(question))
      _      = rag.close() // closed whether the question was answered or not
      answer <- result
    } yield answer
  }

  val handbook: Map[String, String] = Map(
    "vacation.txt" -> "Employees receive 25 days of paid vacation per year. Unused days carry over until 31 March.",
    "expenses.txt" -> "Expenses above 50 euros need a receipt. Claims must be filed within 30 days.",
    "remote.txt"   -> "Remote work is allowed up to three days a week with manager approval."
  )

  /** Writes [[handbook]] to a new temporary folder, standing in for a folder of your own documents. */
  def writeHandbook(): Result[Path] = Try {
    val folder = Files.createTempDirectory("cookbook-handbook")
    folder.toFile.deleteOnExit() // registered first, so deleted last, once its files are gone
    handbook.foreach { (name, text) =>
      Files.write(folder.resolve(name), text.getBytes(StandardCharsets.UTF_8)).toFile.deleteOnExit()
    }
    folder
  }.toResult

  /** Answers with the first passage of the context the pipeline put in the prompt. */
  def script: ScriptedClient = new ScriptedClient((conversation, _) => {
    val prompt = conversation.messages.filter(_.role == MessageRole.User).map(_.content).mkString
    Right(AssistantMessage(prompt.linesIterator.find(_.startsWith("[1] ")).fold("I do not know.")(_.drop(4))))
  })

  def demo(client: LLMClient): Result[String] =
    for {
      folder <- writeHandbook()
      result <- answer(client, folder, "How many days of paid vacation do employees get?")
    } yield s"${result.answer}\n(sources: ${result.contexts.flatMap(_.metadata.get("source")).distinct.mkString(", ")})"
}
```

## Run it

```bash
sbt "samples/runMain org.llm4s.samples.cookbook.FolderQaRecipe"          # scripted client, no API key
sbt "samples/runMain org.llm4s.samples.cookbook.FolderQaRecipe --live"   # the provider your configuration names
```

The first command needs nothing but sbt. The second uses the section `llm4s.providers.provider` names; see
[running the samples](../../getting-started/configuration#running-the-samples). CI runs every recipe against its scripted client, and checks
that the program on this page is the source file, so what you read here compiles and works.

## Use a real provider

Two things change. The chat model is your provider's client (`--live` uses it). The embedding model is a real
one: drop the `given ProviderRegistry` line, name the provider in `withEmbeddings`, and resolve its config from
`llm4s.embeddings`:

```scala
val config = RAG
  .builder()
  .withEmbeddings("openai", "text-embedding-3-small") // needs llm4s-openai and OPENAI_API_KEY
  .withLLM(client)
RAG.build(config, _ => Llm4sConfig.embeddings().map(_._2))
```

See [Embeddings configuration](../../getting-started/configuration#embeddings-configuration) and the
[vector store guide](../../guide/vector-store) for a persistent store (SQLite, pgvector) instead of memory.

## Pitfalls

- The stand-in embeddings match words, not meaning: "time off" does not find "vacation". A real embedding model
  does.
- The documents and the question must be embedded with the same model. Changing the model means re-ingesting.
- An in-memory pipeline is rebuilt on every run. For a folder that changes, use a persistent store and `sync`, which
  re-ingests only the files that changed.
- `topK` passages go into the prompt: too few and the answer is missing, too many and the prompt is long and noisy.

[Back to the cookbook](../cookbook)
