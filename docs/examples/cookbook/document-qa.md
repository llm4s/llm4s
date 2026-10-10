---
layout: page
title: Answer questions with keyword search
parent: Cookbook
grand_parent: Examples
nav_order: 11
---

# Answer questions with keyword search
{: .no_toc }

Chunk some text, put it in an in-memory keyword index (BM25), retrieve the best passage and answer from it.
{: .fs-6 .fw-300 }

## The problem

Sometimes you want retrieval with no embedding model at all: a small set of documents, exact terms (product
names, error codes), or nowhere to send text to be embedded. A keyword index (SQLite FTS5, BM25 ranking) finds the
passage that shares the most words with the question; only that passage goes into the prompt.

## The program

The whole program, [`DocumentQaRecipe.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/cookbook/DocumentQaRecipe.scala). `script` is the stand-in for a model that answers without a
network or a key; `demo` runs the recipe and returns what to print.

```scala
package org.llm4s.samples.cookbook

import org.llm4s.chunking.{ ChunkerFactory, ChunkingConfig }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ AssistantMessage, Conversation, MessageRole, SystemMessage, UserMessage }
import org.llm4s.types.Result
import org.llm4s.vectorstore.{ KeywordDocument, SQLiteKeywordIndex }

/** An answer and the documents it was drawn from. */
final case class DocumentAnswer(text: String, sources: Seq[String])

/**
 * Recipe: answer a question from your own documents.
 *
 * The documents are split into chunks and put in an in-memory keyword index (BM25), the best chunk for the question
 * is retrieved, and only that chunk goes into the prompt. No embedding model is involved, so it runs anywhere.
 *
 * A keyword index matches words, not meaning: a question is turned into an OR of its content words, because a
 * question as written must contain every one of its words to match. For meaning-based search, see the vector stores
 * in `llm4s-rag`.
 * {{{
 *   sbt "samples/runMain org.llm4s.samples.cookbook.DocumentQaRecipe"          # scripted client, no API key
 *   sbt "samples/runMain org.llm4s.samples.cookbook.DocumentQaRecipe --live"   # your configured provider
 * }}}
 */
object DocumentQaRecipe extends RecipeApp {

  val info: RecipeInfo = RecipeInfo(
    id = "document-qa",
    title = "Answer questions with keyword search",
    summary = "Chunk some text, index it, retrieve the best passage for a question and answer from it.",
    mainClass = "org.llm4s.samples.cookbook.DocumentQaRecipe"
  )

  val handbook: Map[String, String] = Map(
    "vacation" -> "Employees receive 25 days of paid vacation per year. Unused days carry over until 31 March.",
    "expenses" -> "Expenses above 50 euros need a receipt. Claims must be filed within 30 days.",
    "remote"   -> "Remote work is allowed up to three days a week with manager approval."
  )

  val question: String = "How many vacation days do employees get?"

  private val stopWords = Set("what", "when", "where", "which", "does", "many", "much", "have", "from", "with")

  /** The question as an OR of its content words, quoted so that FTS5 reads them as plain words. */
  private[cookbook] def keywordQuery(question: String): String =
    question.toLowerCase
      .split("[^a-z0-9]+")
      .filter(word => word.length > 3 && !stopWords.contains(word))
      .distinct
      .map(word => "\"" + word + "\"")
      .mkString(" OR ")

  def answer(client: LLMClient, documents: Map[String, String], question: String): Result[DocumentAnswer] =
    SQLiteKeywordIndex.inMemory().flatMap { index =>
      val chunking = ChunkingConfig(targetSize = 200, maxSize = 300, overlap = 0, minChunkSize = 0)
      val chunks = for {
        (source, text) <- documents.toSeq
        chunk          <- ChunkerFactory.simple().chunk(text, chunking)
      } yield KeywordDocument(s"$source-${chunk.index}", chunk.content, Map("source" -> source))

      // A question of only stop words and short words has no content words. FTS5 rejects an empty
      // MATCH as a syntax error, so skip the search and let the no-document answer stand.
      val query = keywordQuery(question)
      val outcome = for {
        _    <- index.indexBatch(chunks)
        hits <- if (query.isEmpty) Right(Seq.empty) else index.search(query, topK = 1)
        reply <-
          if (hits.isEmpty) Right(None)
          else {
            val context = hits.map(_.content).mkString("\n")
            val prompt = Conversation(
              Seq(SystemMessage(s"Answer only from this context.\n\n$context"), UserMessage(question))
            )
            client.complete(prompt).map(completion => Some(completion.content))
          }
      } yield DocumentAnswer(
        reply.getOrElse("I could not find anything about that in the documents."),
        hits.flatMap(_.metadata.get("source")).distinct
      )

      index.close()
      outcome
    }

  /** Answers with the first sentence of the context it was given. */
  def script: ScriptedClient = new ScriptedClient((conversation, _) => {
    val system  = conversation.messages.find(_.role == MessageRole.System).map(_.content).getOrElse("")
    val passage = system.split("\n\n").lastOption.getOrElse("")
    Right(AssistantMessage(passage.takeWhile(_ != '.') + "."))
  })

  def demo(client: LLMClient): Result[String] =
    answer(client, handbook, question).map(a => s"${a.text}\n(source: ${a.sources.mkString(", ")})")
}
```

## Run it

```bash
sbt "samples/runMain org.llm4s.samples.cookbook.DocumentQaRecipe"          # scripted client, no API key
sbt "samples/runMain org.llm4s.samples.cookbook.DocumentQaRecipe --live"   # the provider your configuration names
```

The first command needs nothing but sbt. The second uses the section `llm4s.providers.provider` names; see
[running the samples](../../getting-started/configuration#running-the-samples). CI runs every recipe against its scripted client, and checks
that the program on this page is the source file, so what you read here compiles and works.

## Use a real provider

Pass your provider's client to `answer`, or run with `--live`. Nothing else changes: the index needs no
provider. For documents on disk, see the [folder recipe](folder-qa), which reads files and adds vector search.

## Pitfalls

- A keyword index matches words, not meaning. A question as written must contain every one of its words to
  match, so the recipe turns it into an OR of its content words.
- A question of only stop words has no content words; FTS5 rejects an empty query, so the recipe answers "not
  found" without searching.
- When nothing matches, say so instead of asking the model: it would answer from what it knows.

[Back to the cookbook](../cookbook)
