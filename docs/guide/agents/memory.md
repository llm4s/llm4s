---
layout: page
title: Memory System
nav_order: 3
parent: Agents
grand_parent: User Guide
---

# Memory System
{: .no_toc }

Persistent context and knowledge for agents across conversations.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Overview

The LLM4S Memory System provides:

- **Short-term memory** - Conversation context within a session
- **Long-term memory** - Persistent facts and knowledge
- **Semantic search** - Find relevant context using embeddings
- **Entity tracking** - Remember information about people, places, things
- **Multiple backends** - In-memory, SQLite, vector stores

The snippets in this guide are kept in `MemoryGuideSpec`
(`modules/memory/src/test/scala/org/llm4s/agent/memory/MemoryGuideSpec.scala`), which compiles
them and checks what the text around them says. Snippets that need a live provider (an embedding
client, an agent) take it as a parameter, so they are compiled but not run against one. The
Postgres store lives in another module and is not covered there.

---

## Dependency

{: .note }
> Not yet published. `llm4s-memory` exists in the build as of
> [#1129](https://github.com/llm4s/llm4s/issues/1129) but ships in the next release;
> in `0.4.1` and earlier this code is inside `llm4s-core`.

```scala
libraryDependencies += "org.llm4s" %% "llm4s-memory" % llm4sVersion
```

The Postgres backend is a separate artifact - see
[Postgres Memory Store](../../reference/postgres-memory-store.md). Package names are unchanged
either way, so `org.llm4s.agent.memory.*` imports keep working.

---

## Quick Start

```scala
import org.llm4s.agent.memory._

val result = for {
  // Record a user fact
  m1 <- SimpleMemoryManager.empty.recordUserFact(
    fact = "Prefers Scala over Java",
    userId = Some("user-123"),
    importance = Some(0.9)
  )

  // Record entity knowledge
  m2 <- m1.recordEntityFact(
    entityId = EntityId("anthropic"),
    entityName = "Anthropic",
    fact = "AI company that created Claude",
    importance = Some(0.8)
  )

  // Get relevant context for a query
  context <- m2.getRelevantContext("Tell me about Scala programming")
} yield context

result match {
  case Right(ctx) => println(s"Context: $ctx")
  case Left(err)  => println(s"Error: $err")
}
```

Every write returns a `Result` holding the *updated* manager, so chain the calls and keep using
the manager you get back. This prints:

```
# Retrieved Context
## User Preferences
- Prefers Scala over Java
```

The in-memory store searches by **keyword**: it splits the query into phrases - its whitespace-separated
pieces, each of whose words must appear next to each other and in order - and returns the memories that
contain at least one of them as whole words, best first. A memory's score is the share of the query's
distinct phrases it contains, not of its words. "Prefers Scala over Java" contains the phrase "scala" from
the query, so it is returned; the Anthropic fact contains none, so it is not. A word never matches inside
a longer one, so `i` matches the word "I" but not "Berlin", and `prefer` does not match "Prefers" - there
is no stemming. Words are split and compared exactly as the SQLite stores' FTS5 index (`unicode61`) does,
so both kinds of store find the same memories for a query
([#1594](https://github.com/llm4s/llm4s/issues/1594)):

- A word is a run of letters, digits and private-use characters in any script. Anything else separates
  words: punctuation (`java?` is the word `java`), and also combining marks such as Devanagari vowel
  signs, so "मुझे" is the two words "म" and "झ".
- Case is ignored, and so is the accent of a Latin letter that has exactly one (`ECOLE` matches
  `école`). Other accents are kept: `αθηνα` does not match "Αθήνα", `живет` does not match "Живёт",
  `мои` does not match "Мой", and `viet` does not match Vietnamese "Việt", whose `ệ` has two.
- A query word with punctuation inside is a phrase: `berlin-based` matches "Berlin-based" but not
  "based in Berlin".

The two kinds of store rank the memories they find differently (the SQLite store uses BM25). To retrieve
by meaning, use a vector store (see [Vector Store](#vector-store)).

---

## Memory Types

| Type | Purpose | Example |
|------|---------|---------|
| `Conversation` | Chat history | "User asked about weather" |
| `UserFact` | User preferences/info | "Prefers dark mode" |
| `Entity` | Knowledge about entities | "Paris is capital of France" |
| `Knowledge` | External knowledge | "Scala 3 released in 2021" |
| `Task` | Task outcomes | "Generated report successfully" |
| `Custom(name)` | Application-specific | Any custom memory type |

---

## Memory Manager

The `MemoryManager` is the main interface for working with memory. `SimpleMemoryManager` is the
standard implementation; `LLMMemoryManager` adds LLM-powered consolidation and entity extraction
(see [Memory Consolidation](#memory-consolidation) and [Entity Extraction](#entity-extraction)).

```scala
import org.llm4s.agent.memory._

// In-memory store, default configuration
val manager = SimpleMemoryManager.empty

// A store and a configuration of your own
val configured = SimpleMemoryManager.withStore(
  store = InMemoryStore.empty,
  config = MemoryManagerConfig(defaultImportance = 0.7)
)
```

### Configuration Options

`MemoryManagerConfig` is built in code (this module reads no configuration file or environment
variable):

| Option | Default | Description |
|--------|---------|-------------|
| `defaultImportance` | `0.5` | Importance given to a memory when you do not pass one |
| `autoRecordMessages` | `true` | Not read by the managers yet (see below) |
| `autoExtractEntities` | `false` | Not read by the managers yet (see below) |
| `contextTokenBudget` | `2000` | Not read by the managers yet: pass `maxTokens` to `getRelevantContext` |
| `consolidationEnabled` | `false` | Not read by the managers yet: call `consolidateMemories` yourself |

`defaultImportance` is the only option a manager consults today. The other four are accepted and
stored, but neither `SimpleMemoryManager` nor `LLMMemoryManager` reads them, so setting them
changes nothing: record messages with `recordMessage`, bound the context with `maxTokens`, and
call `consolidateMemories` and `extractEntities` yourself.

---

## Recording Memory

Every `record*` method returns `Result[MemoryManager]`: the manager with the memory added.

### User Facts

```scala
// Record a user preference
val result = manager.recordUserFact(
  fact = "Prefers functional programming",
  userId = Some("user-123"),
  importance = Some(0.9)
)
```

### Entity Knowledge

```scala
// Record knowledge about an entity
val result = manager.recordEntityFact(
  entityId = EntityId("scala-lang"),
  entityName = "Scala",
  fact = "Scala is a JVM language combining OOP and FP",
  entityType = "language",
  importance = Some(0.8)
)
```

### External Knowledge

```scala
// Record external knowledge: `source` says where it came from
val result = manager.recordKnowledge(
  content = "The latest LLM4S version is 0.5.0",
  source = "release-notes",
  metadata = Map("channel" -> "stable")
)
```

`recordKnowledge` takes no `importance`, and the memory it stores has none (the metadata you pass
is kept on it). Use `recordEntityFact` or `recordUserFact` when importance matters.

### Task Outcomes

```scala
// Record a task result
val result = manager.recordTask(
  description = "Generate the quarterly report",
  outcome = "Report generated",
  success = true,
  importance = Some(0.6)
)
```

### Conversation Messages

```scala
import org.llm4s.llmconnect.model._

// Record a conversation turn
val result = manager.recordMessage(
  message = UserMessage("What's the weather in Paris?"),
  conversationId = "conv-789"
)

// Record several messages at once
val result = manager.recordConversation(
  messages = Seq(UserMessage("And in Rome?"), AssistantMessage("Sunny, 24 degrees.")),
  conversationId = "conv-789"
)
```

The conversation id is a plain `String`. Each message is stored with its role (`user`,
`assistant`, `system` or `tool`).

---

## Retrieving Memory

Each retrieval method returns a `Result[String]`, ready to put in a prompt. When there is nothing
to report the string is empty.

### Relevant Context

Get the memories relevant to a query. With the in-memory store this is keyword matching; with a
vector store it is similarity of meaning:

```scala
val context = manager.getRelevantContext(
  query = "Tell me about Scala programming",
  maxTokens = 1000,
  filter = MemoryFilter.ByTypes(Set(MemoryType.UserFact, MemoryType.Knowledge))
)
```

The context starts with `# Retrieved Context` and groups the memories under `## Relevant
Knowledge`, `## Entity Information`, `## User Preferences`, `## Previous Context` and `## Past
Tasks`. `maxTokens` limits the length at about four characters per token (see
[Manage Context Token Budget](#3-manage-context-token-budget)).

### Conversation History

```scala
val history = manager.getConversationContext(
  conversationId = "conv-789",
  maxMessages = 10
)
```

The result starts with `Previous conversation:` and has one `[role]: text` line per message.

### Entity Context

```scala
val entityInfo = manager.getEntityContext(EntityId("anthropic"))
```

The result is `Known facts about <entity name>:` followed by one `- fact` line per memory.

### User Context

```scala
val userInfo = manager.getUserContext(Some("user-123"))
```

The result is `Known facts about the user:` followed by one `- fact` line per fact recorded for
that user. Pass `None` to include the facts of every user.

---

## Memory Stores

### In-Memory Store

Fast but volatile - loses data on restart. Each store is an immutable value: a write returns a
new store, and `SimpleMemoryManager` keeps track of it for you.

```scala
import org.llm4s.agent.memory.InMemoryStore

val store = InMemoryStore.empty
val manager = SimpleMemoryManager.withStore(store)
```

### SQLite Store

Persistent local storage. Opening a store returns a `Result`, and a SQLite store holds one JDBC
connection, so close it when you are done:

```scala
import org.llm4s.agent.memory.SQLiteMemoryStore

for {
  // File-based (persistent)
  file <- SQLiteMemoryStore("memory.db")

  // In-memory SQLite (fast, volatile)
  volatile <- SQLiteMemoryStore.inMemory()
} yield (file, volatile)
```

A SQLite store is not safe for concurrent use: give each thread its own instance, or synchronise
access yourself.

### Vector Store

Semantic search with embeddings:

```scala
import org.llm4s.agent.memory._
import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.types.Result

// Wrap an embedding client
def embeddingServiceFor(embeddingClient: EmbeddingClient, modelConfig: EmbeddingModelConfig): EmbeddingService =
  LLMEmbeddingService(embeddingClient, modelConfig)

// A vector store in a SQLite file (VectorMemoryStore.inMemory(embeddingService) keeps it in memory)
def vectorManager(embeddingService: EmbeddingService, path: String): Result[SimpleMemoryManager] =
  VectorMemoryStore(path, embeddingService).map(store => SimpleMemoryManager.withStore(store))
```

For tests, `MockEmbeddingService(dimensions = 8)` produces deterministic vectors from a hash of
the text; its scores say nothing about meaning.

The store embeds what it keeps as a *document* and the text you search with as a *query*
(`EmbeddingService.embedQuery`). Models that embed the two differently (Voyage, Cohere, Jina) are
then searched correctly. `embedQuery` delegates to `embed` unless a service overrides it, so a
custom `EmbeddingService` written before it existed keeps working unchanged.

---

## Memory with Agents

### Injecting Context

Put the relevant memory in the system prompt, run the agent, then record the turn. The memory half
of this is runnable as is; `runAgent` stands for however you build and call your agent (see the
[Agents guide](index)), taking the system prompt and returning the messages of the turn:

```scala
import org.llm4s.agent.memory._
import org.llm4s.llmconnect.model.Message
import org.llm4s.types.Result

def answerWithMemory(
  manager: MemoryManager,
  userQuery: String,
  conversationId: String
)(runAgent: String => Result[Seq[Message]]): Result[MemoryManager] =
  for {
    // Get relevant context for the query
    context <- manager.getRelevantContext(userQuery)

    // Build a system prompt that carries the context
    systemPrompt = s"""You are a helpful assistant.
      |
      |Relevant context from memory:
      |$context""".stripMargin

    // Run your agent with that prompt and keep what was said
    messages <- runAgent(systemPrompt)
    updated  <- manager.recordConversation(messages, conversationId)
  } yield updated
```

### Recording the Conversation

Managers record only what you pass to the `record*` methods. `MemoryManagerConfig.autoRecordMessages`
is accepted but no manager reads it yet, so record each turn yourself, as above, with
`recordMessage` or `recordConversation`.

---

## Semantic Search

### With Embeddings

```scala
import org.llm4s.agent.memory._

import scala.util.Using

def semanticSearch(embeddingService: EmbeddingService): Result[Seq[ScoredMemory]] =
  VectorMemoryStore.inMemory(embeddingService).flatMap { store =>
    Using.resource(new AutoCloseable { override def close(): Unit = store.close() }) { _ =>
      for {
        m1 <- SimpleMemoryManager.withStore(store).recordKnowledge("Paris is the capital of France", "geography")
        m2 <- m1.recordKnowledge("Berlin is the capital of Germany", "geography")
        m3 <- m2.recordKnowledge("Rome is the capital of Italy", "geography")
        results <- m3.store.search("European capitals", topK = 2)
      } yield results
    }
  }
```

The results are `ScoredMemory` values, best first. With a real embedding model they are the
memories closest in meaning to the query.

### Search with Filters

Filters are built from the `MemoryFilter` cases and combined with `&&`, `||` and `!`:

```scala
val filter = MemoryFilter.ByType(MemoryType.Knowledge) &&
  MemoryFilter.MinImportance(0.7) &&
  MemoryFilter.ByMetadata("user_id", "user-123")

val results = store.search(
  query = "programming languages",
  topK = 5,
  filter = filter
)
```

The other cases are `ByTypes`, `HasMetadata`, `MetadataContains`, `ByEntity`, `ByConversation`,
`ByTimeRange`, `ContentContains`, `Custom` (any `Memory => Boolean`), `All` and `None`.

---

## Memory Consolidation

Consolidation summarises old memories into fewer, shorter ones. Only `LLMMemoryManager` does it:
`SimpleMemoryManager.consolidateMemories` returns the manager unchanged.

```scala
import org.llm4s.agent.memory._
import org.llm4s.llmconnect.LLMClient
import org.llm4s.types.Result

import java.time.Instant
import java.time.temporal.ChronoUnit

// An LLM client is needed to write the summaries
def llmManager(client: LLMClient): LLMMemoryManager =
  LLMMemoryManager(
    config = MemoryManagerConfig.default,
    store = InMemoryStore.empty,
    client = client
  )

// Consolidate memories older than a week, in groups of at least ten
def consolidate(manager: MemoryManager): Result[MemoryManager] =
  manager.consolidateMemories(
    olderThan = Instant.now().minus(7, ChronoUnit.DAYS),
    minCount = 10
  )
```

`minCount` applies to each group of old memories (same type and context) separately, not to their
total. Nothing consolidates on its own: `consolidationEnabled` is not read, so call
`consolidateMemories` when you want it (for example from a scheduled job).

---

## Entity Extraction

Extract entities from text using an LLM. Only `LLMMemoryManager` does it: on
`SimpleMemoryManager`, `extractEntities` returns the manager unchanged.

```scala
import org.llm4s.agent.memory._
import org.llm4s.types.Result

def extractEntities(manager: MemoryManager): Result[String] =
  for {
    m1 <- manager.extractEntities(
      text = "Anthropic is an AI company that created Claude",
      conversationId = Some("conv-789")
    )
    // The extracted entities are ordinary entity memories
    info <- m1.getEntityContext(EntityId.fromName("Anthropic"))
  } yield info
```

`extractEntities` returns the manager holding the new entity memories, not a list of ids. Read
them back with `getEntityContext`; `EntityId.fromName` turns a name into the id used for it
(lower case, spaces replaced by `_`). Nothing extracts on its own: `autoExtractEntities` is not
read, so call `extractEntities` for the text you want mined.

---

## Memory Statistics

```scala
val result = manager.stats.map { stats =>
  s"""Total memories: ${stats.totalMemories}
     |By type: ${stats.byType}
     |Oldest: ${stats.oldestMemory}
     |Newest: ${stats.newestMemory}""".stripMargin
}
```

`stats` is a `Result[MemoryStats]`. It also has `entityCount`, `conversationCount` (the number of
distinct conversations) and `embeddedCount`. `byType` leaves out types with no memories, and
`oldestMemory` and `newestMemory` are `None` when there are none.

---

## Persistence Patterns

### Save and Load

```scala
import scala.util.Using

// Both opened stores close even when a Result is Left.
val result = SQLiteMemoryStore("memory.db").flatMap { store =>
  Using.resource(new AutoCloseable { override def close(): Unit = store.close() }) { _ =>
    SimpleMemoryManager.withStore(store).recordUserFact("Likes Scala", Some("user-1")).map(_ => ())
  }
}.flatMap { _ =>
  SQLiteMemoryStore("memory.db").flatMap { reopened =>
    Using.resource(new AutoCloseable { override def close(): Unit = reopened.close() }) { _ =>
      SimpleMemoryManager.withStore(reopened).getUserContext(Some("user-1"))
    }
  }
}
// Right("Known facts about the user:\n- Likes Scala")
```

### Cross-Session Memory

```scala
import scala.util.Using

class PersistentMemory(dbPath: String) {

  // Open the store, run something against a manager on it, and close the store again
  def withManager[A](use: MemoryManager => Result[A]): Result[A] =
    SQLiteMemoryStore(dbPath).flatMap { store =>
      Using.resource(new AutoCloseable { override def close(): Unit = store.close() }) { _ =>
        use(SimpleMemoryManager.withStore(store))
      }
    }
}

val memory = new PersistentMemory("memory.db")

// Session 1
val result1 = memory.withManager(_.recordUserFact("Likes Scala", Some("user-1")))

// Session 2 (later)
val result2 = memory.withManager(_.getUserContext(Some("user-1")))
// context includes "Likes Scala" from session 1
```

---

## Best Practices

### 1. Set Appropriate Importance

```scala
for {
  // High importance - core user preferences
  m1 <- manager.recordUserFact("Primary programming language is Scala", importance = Some(0.9))

  // Medium importance - useful but not critical
  m2 <- m1.recordEntityFact(EntityId("scaladays"), "ScalaDays", "Attended in 2024", importance = Some(0.6))

  // Low importance - ephemeral information
  m3 <- m2.recordTask("Run the tests", "Passed", success = true, importance = Some(0.3))
} yield m3
```

### 2. Use Specific Memory Types

Don't use generic `Knowledge` for everything: specific types are grouped under their own
heading in the retrieved context and can be filtered with `MemoryFilter.ByType`.

- `recordUserFact(...)` for user preferences
- `recordEntityFact(...)` for entity-specific information
- `recordKnowledge(...)` for external knowledge
- `recordTask(...)` for task outcomes

### 3. Manage Context Token Budget

```scala
// About four characters per token: the context is cut to fit
val context = manager.getRelevantContext(query, maxTokens = 500)
```

`getRelevantContext` stops adding memories once the next one would take the text past
`maxTokens * 4` characters; the section headings and the `# Retrieved Context` line count towards
that. A heading is written only with at least one memory under it, so a `maxTokens` too small for
any memory gives an empty string. The default is 2000 tokens, and `MemoryManagerConfig.contextTokenBudget` is not consulted.

### 4. Clean Up Old Memories

```scala
// Consolidate old memories periodically (LLMMemoryManager only)
consolidate(manager)

// Delete old, low-importance memories (there is no "maximum importance" filter: use `Custom`)
store.deleteMatching(
  MemoryFilter.Custom(_.importance.exists(_ <= 0.3)) &&
    MemoryFilter.ByTimeRange(before = Some(thirtyDaysAgo))
)
```

`deleteMatching` returns the store without the matching memories.

---

## Examples

| Example | Description |
|---------|-------------|
| [BasicMemoryExample](/examples/#memory-examples) | Getting started with memory |
| [ConversationMemoryExample](/examples/#memory-examples) | Conversation history management |
| [MemoryWithAgentExample](/examples/#memory-examples) | Integrating memory with agents |
| [SQLiteMemoryExample](/examples/#memory-examples) | Persistent SQLite storage |
| [VectorMemoryExample](/examples/#memory-examples) | Semantic search with embeddings |

[Browse all examples →](/examples/)

---

## Next Steps

- [Handoffs Guide](handoffs) - Agent-to-agent delegation
- [Streaming Guide](streaming) - Real-time execution events
- [Guardrails Guide](guardrails) - Input/output validation
