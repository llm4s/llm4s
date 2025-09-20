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

## Security Considerations

### SQL Injection Prevention

DBx Core implements comprehensive SQL injection prevention measures:

1. **Identifier Validation**: All schema names, table names, and identifiers are validated using `SqlSafetyUtils`:
   - Only alphanumeric characters, underscores, and dollar signs allowed
   - Maximum 63 characters (PostgreSQL limit)
   - No special characters that could break out of identifiers
   - Proper quoting using PostgreSQL's quote_ident() equivalent

2. **Parameterized Queries**: All user-provided values are passed as parameters, never concatenated:
   ```scala
   // Safe: Version is parameterized
   val sql = s"insert into $qualifiedName(pgvector_version) values (?)"
   ps.setString(1, version)

   // Never do this:
   // val sql = s"insert into table values ('$userInput')" // VULNERABLE!
   ```

3. **Schema/Table Name Safety**: Configuration validation ensures safe identifiers:
   ```scala
   // Validated on startup
   SqlSafetyUtils.validateIdentifier(schema)  // Rejects dangerous input
   SqlSafetyUtils.validateIdentifier(table)   // Prevents injection
   ```

### Required PostgreSQL Permissions

The database user configured for DBx Core requires the following permissions:

```sql
-- Minimum required permissions
GRANT CONNECT ON DATABASE your_database TO dbx_user;
GRANT CREATE ON DATABASE your_database TO dbx_user;  -- For schema creation
GRANT USAGE ON SCHEMA dbx TO dbx_user;               -- After schema exists
GRANT CREATE ON SCHEMA dbx TO dbx_user;              -- For table creation
GRANT ALL ON ALL TABLES IN SCHEMA dbx TO dbx_user;  -- For data operations

-- For pgvector operations (when implemented)
GRANT USAGE ON SCHEMA vector TO dbx_user;            -- If pgvector is in separate schema
```

### Connection Security

1. **SSL/TLS Support**: Configure SSL mode via `PG_SSLMODE`:
   - `require`: Always use SSL (recommended for production)
   - `verify-ca`: Verify server certificate
   - `verify-full`: Full certificate verification including hostname
   - `disable`: No SSL (development only)

2. **Connection Pool Security**:
   - Connections are tested before use with `SELECT 1`
   - Maximum lifetime of 30 minutes prevents stale connections
   - Automatic cleanup of leaked connections
   - Connection timeout prevents resource exhaustion

3. **Credential Management**:
   - Never log passwords or sensitive connection details
   - Use environment variables for configuration
   - Consider using credential rotation in production
   - Use tools like HashiCorp Vault for secret management

### Error Handling Security

1. **Information Disclosure Prevention**:
   - Database errors are categorized and sanitized
   - Internal details are logged but not exposed to clients
   - Generic error messages for authentication failures

2. **Resource Exhaustion Protection**:
   - Connection pool limits prevent connection exhaustion
   - Transaction timeouts prevent long-running operations
   - Automatic rollback on errors prevents data corruption

### Configuration Security

1. **Validation on Startup**: All configuration is validated before use:
   - Port ranges checked (1-65535)
   - SSL modes validated against allowed values
   - Schema/table names checked for SQL injection risks

2. **Fail-Safe Defaults**:
   - Schema defaults to 'dbx' if not specified
   - Connections auto-commit by default unless in transaction
   - Pool automatically closes connections on shutdown

### Best Practices

1. **Principle of Least Privilege**: Grant only necessary database permissions
2. **Network Isolation**: Use private networks or VPCs when possible
3. **Audit Logging**: Enable PostgreSQL audit logging for sensitive operations
4. **Regular Updates**: Keep PostgreSQL and pgvector extension updated
5. **Monitor Connections**: Track active connections and unusual patterns
6. **Backup Strategy**: Regular backups before schema changes

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