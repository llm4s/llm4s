# DBx Core - Vector Store Abstraction Layer

DBx Core provides a provider-agnostic abstraction layer for vector databases, starting with PostgreSQL/pgvector support.

## Features

- **Provider Abstraction**: Clean trait-based design for supporting multiple vector stores
- **PostgreSQL/pgvector**: First-class support with automatic setup and health checks
- **Connection Pooling**: Efficient resource management with HikariCP
- **SQL Injection Prevention**: Robust identifier validation and parameterized queries
- **Transaction Support**: Atomic operations with automatic rollback on errors
- **Type Safety**: Comprehensive error modeling with Either-based error handling
- **LLM4S Integration**: Seamless integration with the Result type system
- **Logging**: Structured logging with SLF4J

## Configuration

DBx Core uses environment variables for configuration:

```bash
# Required
export PG_HOST=localhost
export PG_PORT=5432
export PG_DATABASE=mydb
export PG_USER=myuser
export PG_PASSWORD=mypassword
export PG_SSLMODE=disable  # or require, prefer

# Optional
export PG_SCHEMA=dbx  # defaults to 'dbx'
```

## Usage

### Basic Initialization

```scala
import org.llm4s.llmconnect.DbxClient
import org.llm4s.llmconnect.config.dbx.DbxConfig

// Load configuration
val config = DbxConfig.load()

// Create client
val client = new DbxClient(config)

// Initialize database
client.initCore() match {
  case Right(report) =>
    println(s"Initialized! PGVector version: ${report.pgvectorVersion}")
  case Left(error) =>
    println(s"Failed: ${error.message}")
}

// Always close when done
client.close()
```

### Using with LLM4S Result Type

```scala
import org.llm4s.llmconnect.dbx.DbxErrorBridge._

// Convert to Result type
val result: Result[CoreHealthReport] = client.initCoreAsResult()

// Or use the extension method
val result2 = client.initCore().asResult
```

### Connection Pool Statistics

```scala
val stats = client.getPoolStats()
println(s"Active connections: ${stats.activeConnections}")
println(s"Idle connections: ${stats.idleConnections}")
println(s"Total connections: ${stats.totalConnections}")
```

## Vector Operations (Coming Soon)

The following interfaces are defined and ready for implementation:

```scala
// Create a collection
val config = CollectionConfig(
  name = "embeddings",
  dimension = 1536,
  distanceMetric = DistanceMetric.Cosine
)

// Store a vector
val request = StoreVectorRequest(
  embedding = Vector(0.1f, 0.2f, ...),
  metadata = Map("source" -> "document.pdf"),
  content = Some("Original text")
)

// Search for similar vectors
val searchRequest = SearchVectorRequest(
  queryEmbedding = Vector(0.1f, 0.2f, ...),
  limit = 10,
  threshold = Some(0.8f)
)
```

## Architecture

### Core Components

- **DbxClient**: Main client interface with connection pooling
- **DbxProvider**: Provider abstraction trait
- **PGVectorProvider**: PostgreSQL/pgvector implementation
- **ConnectionPool**: HikariCP-based connection management
- **SqlSafetyUtils**: SQL injection prevention utilities

### Error Hierarchy

```
DbxError (sealed trait)
├── ConfigError - Configuration issues
├── ConnectionError - Network/connection failures (recoverable)
├── PgvectorMissing - Extension not installed
├── SchemaError - Schema/identifier validation failures
├── PermissionError - Database permission issues
└── WriteError - Write operation failures (recoverable)
```

### Safety Features

1. **SQL Identifier Validation**
   - Only allows alphanumeric, underscore, and dollar signs
   - Maximum 63 characters (PostgreSQL limit)
   - Proper quoting and escaping

2. **Connection Pooling**
   - Maximum 10 connections
   - Minimum 2 idle connections
   - 30-second connection timeout
   - Automatic connection testing

3. **Transaction Management**
   - Automatic rollback on errors
   - Proper resource cleanup
   - Connection returning to pool

## Requirements

- PostgreSQL 12+
- pgvector extension (for vector operations)
- Java 11+
- Scala 2.13 or 3.x

## Testing

Run the test suite:

```bash
sbt test

# Specific tests
sbt "testOnly org.llm4s.llmconnect.utils.dbx.SqlSafetyUtilsTest"
sbt "testOnly org.llm4s.llmconnect.config.dbx.DbxConfigTest"
```

## Future Enhancements

- [ ] Implement vector storage operations
- [ ] Add support for Pinecone
- [ ] Add support for Weaviate
- [ ] Add support for Qdrant
- [ ] Implement batch operations
- [ ] Add index management
- [ ] Add backup/restore functionality
- [ ] Add metrics and monitoring