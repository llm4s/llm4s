# llm4s-knowledgegraph-neo4j

Neo4j adapter for the `GraphStore` trait. Lets you use a Neo4j graph database as the backing store for llm4s Knowledge Graph operations.

**Requires Neo4j 5.9 or later** (parameterized variable-length path bounds used in traversal queries).

## Quick Start

Add to your `build.sbt`:

```scala
libraryDependencies += "org.llm4s" %% "llm4s-knowledgegraph-neo4j" % "<version>"
```

### Connect via Config

```scala
import org.llm4s.knowledgegraph.neo4j.Neo4jGraphStore

val store = Neo4jGraphStore(Neo4jGraphStore.Config(
  uri      = "bolt://localhost:7687",
  user     = "neo4j",
  password = "my-password",
  database = "neo4j",        // optional, default "neo4j"
  maxPoolSize = 10           // optional, default 10
)).getOrElse(sys.error("Could not connect"))
```

### Connect from environment (convenience)

```scala
// bolt://localhost:7687, neo4j/neo4j
val store = Neo4jGraphStore.local().getOrElse(sys.error("No local Neo4j"))
```

### Bring your own driver

```scala
import org.neo4j.driver.GraphDatabase
val driver = GraphDatabase.driver("bolt://myhost:7687", AuthTokens.basic("neo4j", "pass"))
val store  = Neo4jGraphStore(driver, "neo4j").getOrElse(sys.error("failed"))
// store.close() does NOT close the driver — lifecycle is caller-managed
```

## Operations

All operations return `Result[A]` (`Either[LLMError, A]`).

| Operation | Description |
|---|---|
| `upsertNode(node)` | Insert or update a node (idempotent `MERGE`) |
| `upsertEdge(edge)` | Insert or update an edge (both endpoints must exist) |
| `getNode(id)` | Retrieve a node by ID |
| `getNeighbors(id, direction)` | Adjacent nodes + their edges |
| `query(filter)` | Subgraph matching label / property / relationship filters |
| `traverse(startId, config)` | BFS traversal with depth and direction control |
| `deleteNode(id)` | Remove node and all connected edges |
| `deleteEdge(src, tgt, rel)` | Remove a specific edge |
| `loadAll()` | Full graph snapshot |
| `stats()` | Node count, edge count, average degree, densest node |
| `executeRead(cypher)` | Native read Cypher pass-through |
| `executeWrite(cypher)` | Native write Cypher pass-through |

## Namespace Isolation

All nodes are stored with the `:LLM4S` label and two reserved properties (`llm4s_id`, `llm4s_label`). This lets llm4s coexist with your own data in the same Neo4j database.

## Testing

The published `knowledgegraph-neo4j` module keeps only the pure unit tests, so:

```bash
sbt "knowledgegraphNeo4j/test"
```

does not require a running Neo4j instance.

Live Neo4j integration coverage now lives in the separate `it` module. Those tests require a running Neo4j 5.x instance (Community Edition is fine). Without one, the tests are automatically **skipped**.

```bash
# Start Neo4j (Docker, easiest):
docker run --rm -p 7687:7687 -e NEO4J_AUTH=neo4j/neo4j neo4j:5

# Run the live integration suite:
sbt "it/test"
```

Tests connect to `bolt://localhost:7687` with credentials `neo4j` / `neo4j`.

## Notes

- **Thread-safety**: the Neo4j Java driver is fully thread-safe; `Neo4jGraphStore` inherits that guarantee.
- **Connection pooling**: managed by the driver pool (`maxPoolSize` config).
- **Embedded testing**: `neo4j-harness` was evaluated but found to be incompatible with Netty 4.1.115 on Java 17+ due to a bug in `LocalNettyConnector`. Use Docker or a real Neo4j instance for integration testing.
