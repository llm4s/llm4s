---
layout: page
title: Knowledge Graph
description: "Build, store and query a knowledge graph in Scala, extract relationships from documents, and answer questions with graph context."
search_title: "Knowledge graphs in Scala with LLM4S"
parent: RAG and Data
grand_parent: User Guide
nav_order: 311
---

# Knowledge Graph

Use `llm4s-knowledgegraph` when your application needs to follow relationships between entities. For example, a graph can answer who Bob reports to and where his manager works by following two directed edges. You can build the graph yourself or extract entities and relationships from text with a model.

## Installation

Add the graph module using the same `llm4sVersion` as your other LLM4S dependencies. The `llm4s-rag` module already brings in `llm4s-knowledgegraph`. See [Installation](../getting-started/installation#optional-dependencies) for version setup.

```sbt
libraryDependencies += "org.llm4s" %% "llm4s-knowledgegraph" % llm4sVersion
```

Add this dependency if you use `Neo4jGraphStore`.

```sbt
libraryDependencies += "org.llm4s" %% "llm4s-knowledgegraph-neo4j" % llm4sVersion
```

## Build and query a graph

A `Node` has an ID, a label and optional JSON properties. A directed `Edge` has source and target node IDs, a relationship type and optional JSON properties. A `Graph` is an immutable collection of nodes and edges. `GraphStore` provides operations to update and query stored graphs.

This example uses a subset of the company graph from `InMemoryKnowledgeGraphExample`. Insert the nodes before inserting edges. The example needs no model, API key or database.

```scala
import org.llm4s.knowledgegraph.{Edge, Graph, Node}
import org.llm4s.knowledgegraph.storage.{
  Direction, GraphFilter, GraphStore, InMemoryGraphStore, JsonGraphStore, TraversalConfig
}
import org.llm4s.types.Result
import java.nio.file.Path

def companyStore(): Result[GraphStore] = {
  val store = new InMemoryGraphStore()
  for {
    _ <- store.upsertNode(Node("alice", "Person", Map("role" -> ujson.Str("Manager"))))
    _ <- store.upsertNode(Node("bob", "Person", Map("role" -> ujson.Str("Engineer"))))
    _ <- store.upsertNode(Node("acme", "Organization"))
    _ <- store.upsertEdge(Edge("bob", "alice", "REPORTS_TO"))
    _ <- store.upsertEdge(Edge("alice", "acme", "WORKS_AT"))
  } yield store
}

def inspectCompany(store: GraphStore): Result[(Seq[String], Seq[String], Graph)] =
  for {
    reports <- store.getNeighbors("alice", Direction.Incoming)
    reachable <- store.traverse("bob", TraversalConfig(maxDepth = 2, direction = Direction.Outgoing))
    engineers <- store.query(
      GraphFilter(nodeLabel = Some("Person"), propertyKey = Some("role"), propertyValue = Some("Engineer"))
    )
  } yield (
    reports.filter(_.edge.relationship == "REPORTS_TO").map(_.node.id),
    reachable.map(_.id),
    engineers
  )

def saveAndReload(store: GraphStore, file: Path): Result[Graph] =
  for {
    graph <- store.loadAll()
    _ <- new JsonGraphStore(file).save(graph)
    loaded <- new JsonGraphStore(file).load()
  } yield loaded
```

- `getNeighbors` returns pairs of connecting edges and neighbouring nodes. `Direction.Incoming` follows edges towards the current node, `Outgoing` follows edges away from it, and `Both` includes either direction. Alice's direct report is Bob.
- `traverse` visits nodes breadth-first. With `maxDepth = 2` and outgoing edges, the example returns Bob, Alice and Acme in that order. The start node counts as depth zero. Set `visitedNodeIds` to exclude nodes already visited.
- `query` returns a subgraph. The label and property filters select nodes, and the returned edges connect those selected nodes. Set `relationshipType` to narrow the edges further. The engineer subgraph contains Bob and no edges.

Run the full sample from the repository root.

```bash
sbt "samples/runMain org.llm4s.samples.knowledge.InMemoryKnowledgeGraphExample"
```

Use `InMemoryGraphStore` for a graph that lives in the current process. Use `JsonGraphStore` to keep a graph in a local file. For the round trip above, pass a path in an existing directory. Loading a missing file creates an empty graph file, while loading an existing empty file fails because it contains no JSON.

Use `Neo4jGraphStore` when the graph should live in a Neo4j database that multiple application instances can access. Open it with `Neo4jGraphStore(config)` and close the store when finished. `Neo4jKnowledgeGraphExample` shows connection setup and querying with a running Neo4j instance.

## Extract a graph from text

The extraction and question-answering examples take a configured `LLMClient`. Follow the [provider guide](providers) to create one.

`KnowledgeGraphGenerator.extract` asks the model for nodes and edges, writes the parsed graph into the supplied store and returns the extracted graph. Optional `entityTypes` and `relationTypes` focus the prompt.

`SchemaGuidedExtractor.extract` includes the `ExtractionSchema` in the prompt and returns a parsed graph. Use `SchemaValidator` to check the extracted graph against the schema. Set `allowOutOfSchema = false` to have validation drop unknown types.

```scala
import org.llm4s.knowledgegraph.Graph
import org.llm4s.knowledgegraph.extraction.{
  DocumentSource, ExtractionConfig, ExtractionSchema, KnowledgeGraphGenerator,
  MultiDocumentGraphBuilder, SchemaGuidedExtractor, SchemaValidator, SourceTrackedGraph
}
import org.llm4s.knowledgegraph.storage.GraphStore
import org.llm4s.llmconnect.LLMClient
import org.llm4s.types.Result

val companySchema = ExtractionSchema.simple(
  entityTypes = Seq("Person", "Organization"),
  relationshipTypes = Seq("WORKS_AT"),
  allowOutOfSchema = false
)

def extractFreeForm(client: LLMClient, store: GraphStore, text: String): Result[Graph] =
  new KnowledgeGraphGenerator(client, store).extract(text)

def extractWithSchema(client: LLMClient, text: String): Result[Graph] =
  new SchemaGuidedExtractor(client)
    .extract(text, companySchema)
    .map(graph => new SchemaValidator(companySchema).validate(graph).validGraph)

def extractCompanyDocuments(client: LLMClient): Result[SourceTrackedGraph] = {
  val builder = new MultiDocumentGraphBuilder(client, ExtractionConfig(schema = Some(companySchema)))
  builder.extractDocuments(Seq(
    "Alice works at Acme." -> DocumentSource("staff", "Staff directory"),
    "Bob works at Acme." -> DocumentSource("directory", "Engineering directory")
  ))
}
```

### Combine documents and retain their sources

`MultiDocumentGraphBuilder` extracts each document in order, validates it when a schema is configured, then combines the graphs. Give each document a stable, distinct `DocumentSource` ID. Pass an `existingGraph` to `extractDocuments` to add documents without extracting the earlier ones again.

| `ExtractionConfig` field | Default | Effect |
| --- | --- | --- |
| `schema` | `None` | Uses free-form extraction. Supply `Some(schema)` for schema-guided extraction and validation. |
| `enableCoreference` | `true` | Runs `CoreferenceResolver` on each document before extraction to resolve references such as pronouns. |
| `enableEntityLinking` | `true` | Runs `EntityLinker` after merging the document graphs to consolidate matching entities. |
| `llmDisambiguation` | `false` | Enables model-assisted disambiguation during entity linking. Requires entity linking to be enabled. |

Read the merged graph from `SourceTrackedGraph.graph`. Call `getNodeSources(nodeId)` or `getEdgeSources(sourceId, targetId, relationship)` with IDs from the merged graph to retrieve contributing documents. Deterministic entity linking remaps provenance to the merged IDs. Store the provenance separately if you save only the plain `Graph` to JSON.

## Answer questions from the graph

`GraphQAPipeline.ask` identifies entities, translates the question to a `GraphQuery`, runs it against the store, and expands the results by traversing relationships. It can rank graph context via `GraphRanking` before asking the model to answer. Use `GraphQAConfig` to set the enrichment traversal depth and context limits, control ranking and configure citations. The result includes the answer, graph citations, identified entities and query result.

`GraphQueryTool.toolSafe` exposes the pipeline as the `graph_query` tool for an agent. The tool accepts a question and an optional `max_hops` override. It returns the answer, formatted graph citations and entity count. The agent example also uses the `llm4s-agent` dependency. See [Build Your Own Tool](custom-tools) for tool registration.

```scala
import org.llm4s.agent.{Agent, AgentResult}
import org.llm4s.knowledgegraph.query.{GraphQAConfig, GraphQAPipeline, GraphQAResult}
import org.llm4s.knowledgegraph.storage.GraphStore
import org.llm4s.knowledgegraph.tool.GraphQueryTool
import org.llm4s.llmconnect.LLMClient
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.types.Result

def askCompany(client: LLMClient, store: GraphStore): Result[GraphQAResult] =
  new GraphQAPipeline(client, store, GraphQAConfig(maxHops = 2))
    .ask("Where does Bob's manager work?")

def askCompanyAgent(client: LLMClient, store: GraphStore): Result[AgentResult] =
  for {
    tool <- GraphQueryTool.toolSafe(client, store)
    tools <- ToolRegistry.of(Seq(tool))
    agent <- Agent.builder("company-assistant", client).withTools(tools).build()
    result <- agent.run("Use the company graph to find where Bob's manager works.")
  } yield result
```

Graph QA citations describe the graph context used for the answer. For document provenance, look up the cited node or edge in your `SourceTrackedGraph`.

## Handle failures

Graph store operations, extraction and question answering return `Result[A]`. Compose them with a for-comprehension, or match on `Right(value)` and `Left(error)`. See [Error Handling](error-handling) for working with `LLMError`.

- An in-memory edge upsert with a missing endpoint fails. An in-memory traversal from an unknown start ID returns an empty sequence.
- Malformed model JSON, missing required fields and dangling edges fail graph extraction. A dangling edge produces a `ProcessingError` with operation `graph_integrity_violation`. Loading a JSON file also validates edge endpoints.
- Free-form extraction propagates store write errors. Earlier successful writes can remain in the store after a failure.
- Multi-document extraction stops on the first document error. Schema validation can instead return a filtered graph and report violations through logging.
- Graph QA treats malformed entity-identification replies as an empty list of mentions. A malformed query-translation reply, such as invalid JSON, an unknown query type or a missing required `node_id`, fails `ask` with a `ProcessingError` whose operation is `query_parse`. Model call failures during identification, query translation or answer generation also return `Left`. The agent tool maps a pipeline failure to a tool handler error.
