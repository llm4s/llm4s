---
layout: page
title: "4. Help-Centre Answers"
parent: Build a Support Assistant
grand_parent: Tutorials
nav_order: 4
description: "Support assistant tutorial, part 4: answer from the shop's help-centre articles with an LLM4S RAG pipeline that ingests, embeds, retrieves and cites its sources."
search_title: "Support assistant tutorial 4: RAG over help-centre docs in Scala"
---

# Part 4: answers from the help centre
{: .no_toc }

Most questions are already answered somewhere in the help centre. This part puts the help-centre articles into a
retrieval-augmented generation (RAG) pipeline, so the assistant answers from them and says which articles it used.
{: .fs-6 .fw-300 }

The code is
[`Part4HelpCentre.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/tutorial/supportassistant/Part4HelpCentre.scala).
It uses `llm4s-rag`, which the samples already depend on; the
[folder Q&A recipe](../../examples/cookbook/folder-qa) is the same pipeline over a folder of files.

## The articles

Acme Shop's help centre has four short articles, keyed by an id the answers cite:

```scala
val articles: Map[String, String] = Map(
  "delivery" -> "Standard delivery takes 2 to 4 working days. Express delivery arrives the next working day.",
  "returns"  -> "You can return an unused item within 30 days of delivery. Print a free label from your account.",
  "refunds"  -> "A refund goes back to the original payment method within 5 working days of the return arriving.",
  "account"  -> "To change the email address of your account, open Settings and choose Email."
)
```

A real shop would load its articles from files, a website or a database; `rag.ingest(path)` and the
[document loaders](../../guide/document-loaders) do that. A map keeps the tutorial offline.

## A pipeline over them

A pipeline cuts each article into chunks, turns each chunk into a vector with an embedding model, and stores both the
vectors and the words, so a question can find its passages by meaning and by keyword. `withHelpCentre` builds one,
ingests the articles, hands it to your code and closes it afterwards:

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.model.ModelRegistryService
import org.llm4s.rag.RAG
import org.llm4s.samples.cookbook.BagOfWordsEmbeddings
import org.llm4s.types.Result

val helpCentrePrompt: String =
  "You are the support assistant of Acme Shop. Answer only from the context, and cite each passage you use as [n]."

def ingestArticles(rag: RAG): Result[Int] =
  articles.toSeq.foldLeft[Result[Int]](Right(0)) { case (chunks, (id, text)) =>
    chunks.flatMap(count => rag.ingestText(text, documentId = id).map(count + _))
  }

def withHelpCentre[A](client: LLMClient)(use: RAG => Result[A]): Result[A] = {
  given ProviderRegistry = ProviderRegistry.default.withEmbeddingProvider(BagOfWordsEmbeddings)
  for {
    models <- Llm4sConfig.modelRegistryService()
    given ModelRegistryService = models
    config = RAG
      .builder()
      .withEmbeddings(BagOfWordsEmbeddings.id.asString, BagOfWordsEmbeddings.Model, BagOfWordsEmbeddings.Dimensions)
      .withTopK(2)
      .withSystemPrompt(helpCentrePrompt)
      .withLLM(client)
    rag <- RAG.build(config, _ => Right(BagOfWordsEmbeddings.config))
    result = ingestArticles(rag).flatMap(_ => use(rag))
    _      = rag.close() // closed whether `use` succeeded or not
    answer <- result
  } yield answer
}
```

`BagOfWordsEmbeddings` is the cookbook's stand-in embedding model: it hashes the words of a text into a vector, so
texts that share words are close, and it never touches the network. It is a real `EmbeddingProviderDescriptor`,
registered the way a provider module's is, so swapping in a real model is a change of configuration: drop the
`given ProviderRegistry`, set `.withEmbeddings("openai", "text-embedding-3-small")` (or an Ollama model), and resolve
its config with `Llm4sConfig.embeddings()`; see [embeddings](../../guide/embeddings) and the recipe's
["Use a real provider"](../../examples/cookbook/folder-qa#use-a-real-provider).

`withTopK(2)` sends the two best passages to the model. `withSystemPrompt` replaces the pipeline's default
instructions with the shop's own, which ask the model to cite the passages it uses.

## A cited answer

`queryWithAnswer` retrieves the passages for the question, puts them into the prompt numbered `[1]`, `[2]`, ..., and
asks the model. The result carries the passages too, each with the `docId` of the article it came from, so the answer
can list its sources:

```scala
case class CitedAnswer(text: String, sources: Seq[String])

def answerFromHelpCentre(rag: RAG, question: String): Result[CitedAnswer] =
  rag.queryWithAnswer(question).map { result =>
    CitedAnswer(result.answer, result.contexts.flatMap(_.metadata.get("docId")).distinct)
  }
```

Run the part. The scripted model answers from the first passage it is given:

```bash
sbt "samples/runMain org.llm4s.samples.tutorial.supportassistant.Part4HelpCentre"
sbt "samples/runMain org.llm4s.samples.tutorial.supportassistant.Part4HelpCentre --live"
```

```text
done: Express delivery arrives the next working day [1]. (sources: delivery, returns)
```

The second source is there because the returns article mentions delivery too: retrieval finds passages, and the
model decides which of them answer the question.

## The help centre as a tool

The Part 3 agent answers questions about orders. Give it the help centre as a third tool, and it can answer "how long
does express delivery take?" in the same conversation as "where is my order?". The tool returns the passages, not an
answer: the agent's model writes the answer, with the order lookup and the policy to hand.

```scala
import org.llm4s.agent.Agent
import org.llm4s.schema.*
import org.llm4s.toolapi.{ ToolFunction, ToolRegistry }
import upickle.default.ReadWriter

@description("What to look up in the help centre")
case class HelpQuery(@description("The customer's question, in a few words") question: String)
  derives SchemaOf, ReadWriter

case class HelpPassage(article: String, text: String) derives ReadWriter

def helpTool(rag: RAG): Result[ToolFunction[HelpQuery, Seq[HelpPassage]]] =
  Tool.of[HelpQuery, Seq[HelpPassage]]("search_help_centre", "Passages of Acme Shop's help-centre articles") {
    query =>
      rag
        .query(query.question)
        .map(_.map(passage => HelpPassage(passage.metadata.getOrElse("docId", "unknown"), passage.content)))
        .left
        .map(_.message)
  }

def helpAgent(client: LLMClient, rag: RAG): Result[Agent] =
  for {
    order  <- Part3Tools.orderTool
    policy <- Part3Tools.policyTool
    help   <- helpTool(rag)
    registry <- ToolRegistry.of(Seq(order, policy, help))
    agent <- Agent
      .builder("support", client)
      .withSystemPrompt("You are the support assistant of Acme Shop. Use the tools; never guess an order's status.")
      .withTools(registry)
      .build()
  } yield agent
```

`Part4Spec` runs the pipeline, the cited answer, the tool and the agent against scripted clients, and checks that the
delivery article is the first passage for a delivery question, and that the prompt the model received contains it.

## Exercise 4: the sources alone

Write `Part4Exercise.sources` in
[`Exercises.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/tutorial/supportassistant/Exercises.scala):
the ids of the articles that answer a question, best first and each once, without calling the model. `rag.query`
retrieves passages without generating an answer. Then run

```bash
sbt "samples/testOnly *Part4Spec"
```

For a bonus, add a fifth article to `articles` and ask about it with `--live`. The solution is `Part4Solution` in the
[`solutions`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/tutorial/supportassistant/solutions/Solutions.scala)
package.

Previous: [Part 3, tools and an agent](part-3-tools). Next: [Part 5, safety and memory](part-5-safety-memory).
