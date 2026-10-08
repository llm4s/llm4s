---
layout: page
title: Cookbook
parent: Examples
nav_order: 1
---

# Cookbook
{: .no_toc }

Five complete recipes for things you would actually build. Each is a small Scala program in
[`modules/samples`](https://github.com/llm4s/llm4s/tree/main/modules/samples/src/main/scala/org/llm4s/samples/cookbook)
with a `run` function you can copy, and each runs with **no API key**: by default a scripted client stands in for the
model, so you can see the whole flow work in seconds. Add `--live` to run the same code against the provider chosen
by your configuration (`llm4s.providers.provider` in `application.conf`, see [Configuration](../getting-started/configuration)).

CI runs every recipe against its scripted client on every pull request, and checks that the code on this page is the
code in the source files, so what you read here compiles and works.

1. TOC
{:toc}

<!-- recipe: tool-calling -->
## 1. An agent that calls a tool

Give an agent the built-in calculator and let the model decide when to use it. The library runs the tool and feeds its output back, and the model answers from it.

Run it:

```bash
sbt "samples/runMain org.llm4s.samples.cookbook.ToolCallingRecipe"
sbt "samples/runMain org.llm4s.samples.cookbook.ToolCallingRecipe --live"
```

The core of the recipe:

```scala
def run(client: LLMClient, question: String): Result[AgentResult] =
  for {
    tools  <- BuiltinTools.coreSafe
    agent  <- Agent.builder("calculator-agent", client).withTools(new ToolRegistry(tools)).build()
    result <- agent.run(question)
  } yield result
```

The whole file, with the scripted client: [`ToolCallingRecipe.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/cookbook/ToolCallingRecipe.scala).

What the spec checks:

- the calculator really ran: its output is a tool message in the thread
- the model was offered the calculator on the first call and shown its output on the second

<!-- recipe: structured-output -->
## 2. Classify text into a typed value

`completeStructured` sends a JSON schema with the request and reads the reply into a case class, so the rest of the program deals in a `Ticket`, not a string.

Run it:

```bash
sbt "samples/runMain org.llm4s.samples.cookbook.StructuredOutputRecipe"
sbt "samples/runMain org.llm4s.samples.cookbook.StructuredOutputRecipe --live"
```

The core of the recipe:

```scala
val ticketSchema = Schema
  .`object`[Ticket]("A customer support ticket")
  .withRequiredField("category", Schema.string("The kind of ticket").withEnum(categories))
  .withRequiredField("urgency", Schema.integer("From 1 (can wait) to 5 (urgent)").withRange(Some(1), Some(5)))
  .withRequiredField("summary", Schema.string("One sentence describing the problem"))

def classify(client: LLMClient, text: String): Result[Ticket] =
  for {
    ticket <- client.completeStructured[Ticket](Conversation(Seq(UserMessage(text))), ticketSchema)
    _ <- Either.cond(
      categories.contains(ticket.category),
      (),
      ValidationError.invalid("category", s"'${ticket.category}' is not one of ${categories.mkString(", ")}")
    )
    // The schema states the range, but a provider that ignores it can still answer outside it.
    _ <- Either.cond(
      1 <= ticket.urgency && ticket.urgency <= 5,
      (),
      ValidationError.invalid("urgency", s"${ticket.urgency} is outside 1 to 5")
    )
  } yield ticket
```

The whole file, with the scripted client: [`StructuredOutputRecipe.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/cookbook/StructuredOutputRecipe.scala).

What the spec checks:

- a good reply, and one wrapped in a code fence, are read into a `Ticket`
- a reply that is not JSON, has a field of the wrong type or misses a field is a `Left`
- the schema is sent with the request, with the categories as an enum

**Watch out:** Only some providers enforce a schema while generating (OpenAI and Gemini do; Anthropic gets a best-effort instruction). The recipe checks the category again on the way out for that reason.

<!-- recipe: guardrails -->
## 3. Guardrails around an agent

Input guardrails run before the model sees the text, output guardrails on the final answer. A request that fails an input guardrail never reaches the model, so it costs nothing.

Run it:

```bash
sbt "samples/runMain org.llm4s.samples.cookbook.GuardrailsRecipe"
sbt "samples/runMain org.llm4s.samples.cookbook.GuardrailsRecipe --live"
```

The core of the recipe:

```scala
def guardedAgent(client: LLMClient): Result[Agent] =
  Agent
    .builder("guarded-agent", client)
    .withMiddleware(
      new GuardrailMiddleware(
        input = Seq(LengthCheck(1, 200), ProfanityFilter.withCustomWords(Set("heck"))),
        output = Seq(LengthCheck(1, 300))
      )
    )
    .build()

def ask(client: LLMClient, query: String): Result[AgentResult] =
  guardedAgent(client).flatMap(_.run(query))
```

The whole file, with the scripted client: [`GuardrailsRecipe.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/cookbook/GuardrailsRecipe.scala).

What the spec checks:

- a normal request reaches the model and is answered
- an over-long and a listed-word request are refused with no call to the model; a blank one is rejected by the agent before the guardrails run
- an over-long answer is refused after the model was called, and the user gets no answer

**Watch out:** An output guardrail cannot save the cost of the call: it has already been made. The checks here are plain code; the LLM-as-judge guardrails make a second model call per check.

<!-- recipe: document-qa -->
## 4. Answer questions from your documents

Chunk some text, put it in an in-memory keyword index (BM25), retrieve the best passage for the question and answer from it. No embedding model is involved, so it runs anywhere.

Run it:

```bash
sbt "samples/runMain org.llm4s.samples.cookbook.DocumentQaRecipe"
sbt "samples/runMain org.llm4s.samples.cookbook.DocumentQaRecipe --live"
```

The core of the recipe:

```scala
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
```

The whole file, with the scripted client: [`DocumentQaRecipe.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/cookbook/DocumentQaRecipe.scala).

What the spec checks:

- the answer comes from the matching passage and names its source
- only the best passage goes into the prompt, not the whole handbook
- when nothing matches, the recipe says so without calling the model

**Watch out:** A keyword index matches words, not meaning. A question as written must contain every one of its words to match, so the recipe turns it into an OR of its content words. For meaning-based search use the vector stores in `llm4s-rag`.

<!-- recipe: memory -->
## 5. Remember facts between turns

Record what a user says in a memory manager, and put the relevant facts in the system prompt when the next question arrives.

Run it:

```bash
sbt "samples/runMain org.llm4s.samples.cookbook.MemoryRecipe"
sbt "samples/runMain org.llm4s.samples.cookbook.MemoryRecipe --live"
```

The core of the recipe:

```scala
/** The store matches words as substrings, so give it the content words, without punctuation or short words. */
def contentWords(question: String): String =
  question.toLowerCase.split("[^a-z0-9]+").filter(_.length > 3).distinct.mkString(" ")

def recall(client: LLMClient, facts: Seq[String], question: String): Result[Remembered] =
  for {
    manager <- facts.foldLeft[Result[MemoryManager]](Right(SimpleMemoryManager.empty)) { (manager, fact) =>
      manager.flatMap(_.recordUserFact(fact, Some("user-1"), Some(0.9)))
    }
    context <- manager.getRelevantContext(contentWords(question))
    reply <- client.complete(
      Conversation(Seq(SystemMessage(s"What you know about the user:\n$context"), UserMessage(question)))
    )
  } yield Remembered(reply.content, context)
```

The whole file, with the scripted client: [`MemoryRecipe.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/cookbook/MemoryRecipe.scala).

What the spec checks:

- a recorded fact is shown to the model and changes its answer; with nothing recorded it does not know
- a fact that has nothing to do with the question is left out of the prompt

**Watch out:** The in-memory store counts a query word as a match when the memory text contains it as a substring, so a short word such as "I" or "or" matches almost anything, and "Java?" with its question mark matches nothing ([#1594](https://github.com/llm4s/llm4s/issues/1594)). The recipe searches with the question's content words only. The question still has to share a word with the fact: "Which language do I prefer, Scala or Java?" finds "Prefers Scala over Java", but "What do I like?" finds nothing.

## More recipes

These five are a start, not the whole list. The [examples index](index) lists the rest of the samples, and the
[issue that tracks the cookbook](https://github.com/llm4s/llm4s/issues/1476) lists the recipes still wanted: summarise a
long document, stream tokens, fall back between providers, and more. A recipe is a good first contribution: copy one of
the five files, add its registry entry in `Recipe.scala`, and the spec tells you what else the page needs.
