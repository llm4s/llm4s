# llm4s-knowledgegraph

A knowledge-graph model for llm4s, with storage, traversal, LLM-driven extraction and graph-guided
question answering. Experimental (see
[`docs/reference/v1-scope.md`](../../docs/reference/v1-scope.md)): outside the 1.0 compatibility
promise, and its API may still change.

## Install

```scala
libraryDependencies += "org.llm4s" %% "llm4s-knowledgegraph" % "<version>"
```

Depends on `llm4s-core` only. Which release carries which module is in the
[installation guide](../../docs/getting-started/installation.md); this README describes `main`.

The module has no `reference.conf` and reads no configuration of its own. The extraction and
question-answering classes take an `LLMClient` you build the usual way (see the
[providers guide](../../docs/guide/providers.md)).

## What it provides

| Area | Types | Notes |
|---|---|---|
| Model | `Node(id, label, properties)`, `Edge(source, target, relationship, properties)`, `Graph` | properties are `ujson.Value`s; `Graph` is immutable and has `merge`, `validate`, `getNeighbors`, `findNodesByLabel` and similar |
| Storage | `GraphStore`, `InMemoryGraphStore`, `JsonGraphStore` | every `GraphStore` method returns a `Result`; `traverse(startId, TraversalConfig)` does a breadth-first walk with `maxDepth` and `Direction` |
| Extraction | `KnowledgeGraphGenerator`, `SchemaGuidedExtractor`, `MultiDocumentGraphBuilder`, `EntityLinker`, `CoreferenceResolver` | LLM-driven; `KnowledgeGraphGenerator` writes into its `GraphStore`; the others return a `Graph`, a `SourceTrackedGraph` or text to the caller |
| Query | `GraphQuery`, `GraphQueryExecutor`, `GraphQueryTranslator`, `GraphRanking` | |
| Question answering | `GraphQAPipeline` | identifies entities, traverses for context, ranks them and asks the LLM; the answer carries the nodes and edges that supported it |
| Tool | `GraphQueryTool` | exposes the pipeline to an agent as a tool |
| In-memory engine | `GraphEngine` | traversal and querying over a `Graph` value |

`InMemoryGraphStore` is thread-safe (atomic compare-and-swap updates) and not persistent;
`JsonGraphStore` writes the whole graph to one JSON file. For a database, use the Neo4j adapter in
[`llm4s-knowledgegraph-neo4j`](../knowledgegraph-neo4j/README.md).

`org.llm4s.knowledgegraph.graphrag` (graph-augmented retrieval) shares this package root but ships
in [`llm4s-rag`](../rag/README.md), not here.

## Minimal example

```scala
import org.llm4s.knowledgegraph.{ Edge, Node }
import org.llm4s.knowledgegraph.storage.{ Direction, InMemoryGraphStore, TraversalConfig }

val store = new InMemoryGraphStore()

val outcome = for {
  _          <- store.upsertNode(Node("alice", "Person", Map("role" -> ujson.Str("engineer"))))
  _          <- store.upsertNode(Node("llm4s", "Project"))
  _          <- store.upsertEdge(Edge("alice", "llm4s", "WORKS_ON"))
  neighbours <- store.getNeighbors("alice", Direction.Outgoing)
  reachable  <- store.traverse("alice", TraversalConfig(maxDepth = 2))
} yield (neighbours.map(_.node.id), reachable.map(_.id))
// Right((List(llm4s), Vector(alice, llm4s)))   -- traverse includes the start node
```

## Limits

- **Experimental tier.** Types here are not annotated `@Stable`; expect changes before they are
  frozen.
- **Extraction needs an LLM.** `KnowledgeGraphGenerator` and `SchemaGuidedExtractor` call the
  client you give them and parse its JSON reply; a failure, including a reply that does not parse,
  is a `Left(ProcessingError)`, so check the `Result`.
- **`InMemoryGraphStore` is not persistent**, and `JsonGraphStore` rewrites its file on each
  change: neither is meant for large graphs.

## Tests

```bash
sbt knowledgegraph/test          # unit tests, fake LLM clients, in-memory stores
sbt testIntegration              # the @Docker tier in modules/it, which includes the Neo4j adapter's suites
```

## See also

- [`llm4s-rag`](../rag/README.md) - GraphRAG, which combines this graph with retrieval
- [`llm4s-knowledgegraph-neo4j`](../knowledgegraph-neo4j/README.md) - the Neo4j `GraphStore`
- [Stability and scope](../../docs/reference/v1-scope.md)
