---
layout: page
title: Permission-Based RAG
parent: User Guide
nav_order: 4
---

# Permission-Based RAG
{: .no_toc }

Enterprise-grade access control for RAG applications.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Overview

Permission-based RAG extends LLM4S with enterprise-grade access control for document retrieval. It enables you to:

- **Organize documents in collections** with hierarchical structure (e.g., `confluence/EN/archive`)
- **Control who can search** with collection-level `queryableBy` permissions
- **Fine-grained document access** with document-level `readableBy` permissions
- **Map users and groups** to efficient integer IDs for fast database queries
- **Query with patterns** like `*` (all), `confluence/*` (immediate children), `confluence/**` (all descendants)

**Key Features:**
- Two-level permission model (collection + document)
- Permission inheritance (children can only restrict, never loosen)
- Pattern-based collection queries
- PostgreSQL backend with pgvector for efficient vector search
- Seamless integration with the existing RAG API

---

## Quick Start

### Prerequisites

You need PostgreSQL with pgvector extension:

```bash
# Using Docker
docker run -d --name pgvector -p 5432:5432 \
  -e POSTGRES_PASSWORD=postgres \
  pgvector/pgvector:pg16

# Or enable on existing PostgreSQL
psql -c "CREATE EXTENSION IF NOT EXISTS vector;"
```

### Minimal Example

```scala
import org.llm4s.rag.RAG
import org.llm4s.rag.RAG.RAGConfigOps
import org.llm4s.rag.permissions._
import org.llm4s.rag.permissions.pg.PgSearchIndex

// 1. Create and initialize the SearchIndex
val searchIndex = PgSearchIndex.fromJdbcUrl(
  jdbcUrl = "jdbc:postgresql://localhost:5432/postgres",
  user = "postgres",
  password = "postgres",
  vectorTableName = "rag_vectors"
).getOrElse(throw new RuntimeException("Failed to create SearchIndex"))

searchIndex.initializeSchema().getOrElse(throw new RuntimeException("Failed to init schema"))

// 2. Create a collection
val collectionPath = CollectionPath.unsafe("my-docs")
searchIndex.collections.create(CollectionConfig.publicLeaf(collectionPath))

// 3. Build RAG with permission support
val rag = RAG.builder()
  .withEmbeddings("openai")
  .withSearchIndex(searchIndex)
  .build()
  .getOrElse(throw new RuntimeException("Failed to build RAG"))

// 4. Ingest a document
rag.ingestWithPermissions(
  collectionPath = collectionPath,
  documentId = "doc-1",
  content = "LLM4S is a framework for building LLM-powered applications in Scala.",
  metadata = Map("source" -> "readme")
)

// 5. Query with permissions
val results = rag.queryWithPermissions(
  auth = UserAuthorization.Admin,  // Admin bypasses all checks
  collectionPattern = CollectionPattern.All,
  queryText = "What is LLM4S?"
)

results.foreach { r =>
  r.foreach(result => println(s"${result.id}: ${result.content.take(50)}..."))
}

// 6. Cleanup
rag.close()
searchIndex.close()
```

---

## Core Concepts

### Principal IDs

Principals represent users and groups. They're mapped to integers for efficient database queries:

```scala
import org.llm4s.rag.permissions._

// Users have positive IDs
val userId = PrincipalId.user(42)     // Creates PrincipalId(42)
userId.isUser   // true
userId.isGroup  // false

// Groups have negative IDs
val groupId = PrincipalId.group(5)    // Creates PrincipalId(-5)
groupId.isUser   // false
groupId.isGroup  // true

// Create from raw value
PrincipalId.fromRaw(42)   // Right(PrincipalId(42))
PrincipalId.fromRaw(-5)   // Right(PrincipalId(-5))
PrincipalId.fromRaw(0)    // Left(error) - zero not allowed
```

### External Principals

External principals are human-readable identifiers that get mapped to integer IDs:

```scala
import org.llm4s.rag.permissions._

// User by email
val user = ExternalPrincipal.User("john@example.com")
user.externalId  // "user:john@example.com"

// Group by name
val group = ExternalPrincipal.Group("engineering")
group.externalId  // "group:engineering"

// Parse from string
ExternalPrincipal.parse("user:john@example.com")  // Right(User("john@example.com"))
ExternalPrincipal.parse("group:admins")           // Right(Group("admins"))
ExternalPrincipal.parse("invalid")                // Left(error)
```

### Collection Paths

Collections use hierarchical paths with forward-slash separators:

```scala
import org.llm4s.rag.permissions._

// Create with validation
val path = CollectionPath.create("confluence/EN/archive")
// Right(CollectionPath(Seq("confluence", "EN", "archive")))

// Path properties
path.foreach { p =>
  p.value      // "confluence/EN/archive"
  p.depth      // 3
  p.name       // "archive" (final segment)
  p.isRoot     // false
  p.parent     // Some(CollectionPath("confluence/EN"))
}

// Hierarchy relationships
val parent = CollectionPath.unsafe("confluence")
val child = CollectionPath.unsafe("confluence/EN")
val grandchild = CollectionPath.unsafe("confluence/EN/archive")

child.isChildOf(parent)           // true
grandchild.isChildOf(parent)      // false (not direct child)
grandchild.isDescendantOf(parent) // true

// Invalid paths are rejected
CollectionPath.create("path with spaces")  // Left(error)
CollectionPath.create("")                  // Left(error)
```

### Collection Patterns

Patterns filter which collections to search:

```scala
import org.llm4s.rag.permissions._

// Match all collections
CollectionPattern.All

// Match exact path
CollectionPattern.Exact(CollectionPath.unsafe("confluence/EN"))

// Match immediate children only
CollectionPattern.ImmediateChildren(CollectionPath.unsafe("confluence"))
// Matches: confluence/EN, confluence/DE
// Does NOT match: confluence/EN/archive

// Match all descendants
CollectionPattern.AllDescendants(CollectionPath.unsafe("confluence"))
// Matches: confluence, confluence/EN, confluence/EN/archive, confluence/DE

// Parse from string
CollectionPattern.parse("*")              // All
CollectionPattern.parse("confluence")     // Exact
CollectionPattern.parse("confluence/*")   // ImmediateChildren
CollectionPattern.parse("confluence/**")  // AllDescendants
```

### User Authorization

Authorization context contains the user's principal IDs:

```scala
import org.llm4s.rag.permissions._

// Create for a user with group memberships
val auth = UserAuthorization.forUser(
  userId = PrincipalId.user(42),
  groups = Set(PrincipalId.group(1), PrincipalId.group(2))
)
auth.principalIds  // Set(PrincipalId(42), PrincipalId(-1), PrincipalId(-2))
auth.isAdmin       // false

// Admin bypasses all permission checks
val adminAuth = UserAuthorization.Admin
adminAuth.isAdmin  // true

// Anonymous user (no permissions)
val anonAuth = UserAuthorization.Anonymous
anonAuth.principalIds.isEmpty  // true

// Check if user includes a specific principal
auth.includes(PrincipalId.user(42))  // true
auth.includes(PrincipalId.group(1))  // true
auth.includes(PrincipalId.user(99))  // false
```

---

## Collections

### Creating Collections

Collections are created through the `CollectionStore`:

```scala
import org.llm4s.rag.permissions._

val collections = searchIndex.collections

// Public leaf collection (anyone can query, can contain documents)
val publicConfig = CollectionConfig.publicLeaf(
  CollectionPath.unsafe("public-docs")
)
collections.create(publicConfig)

// Restricted collection (only specific groups can query)
val restrictedConfig = CollectionConfig.restrictedLeaf(
  path = CollectionPath.unsafe("internal"),
  queryableBy = Set(PrincipalId.group(1))  // Only engineering group
)
collections.create(restrictedConfig)

// Parent collection (can have sub-collections, not documents)
val parentConfig = CollectionConfig.publicParent(
  CollectionPath.unsafe("confluence")
)
collections.create(parentConfig)

// Fluent configuration
val config = CollectionConfig(CollectionPath.unsafe("my-collection"))
  .withQueryableBy(PrincipalId.group(5))
  .withQueryableBy(Set(PrincipalId.user(1), PrincipalId.user(2)))
  .withMetadata("description", "My documents")
  .asLeaf
```

### Hierarchical Structure

Collections form a tree structure:

```
SearchIndex
├── confluence (parent, queryableBy: [employees])
│   ├── confluence/EN (leaf, inherits + can restrict)
│   ├── confluence/DE (leaf, inherits)
│   └── confluence/archive (leaf, inherits)
├── internal (parent, queryableBy: [admins])
│   └── internal/hr (leaf, queryableBy: [hr-team])
└── public (leaf, queryableBy: [])  ← empty = public
```

**Rules:**
- Parent collections cannot contain documents (only sub-collections)
- Leaf collections can contain documents
- When you create a child, the parent automatically becomes a non-leaf
- Permissions are inherited: children can only restrict, never loosen

```scala
// Create parent first
collections.create(CollectionConfig.publicParent(CollectionPath.unsafe("confluence")))

// Create child - parent automatically becomes non-leaf
collections.create(CollectionConfig.publicLeaf(CollectionPath.unsafe("confluence/EN")))

// Or use ensureExists to create parent hierarchy automatically
collections.ensureExists(CollectionConfig.publicLeaf(
  CollectionPath.unsafe("deep/nested/collection")
))
// Creates: deep (parent), deep/nested (parent), deep/nested/collection (leaf)
```

### Permission Inheritance

Permissions flow down the hierarchy:

```scala
val principals = searchIndex.principals

// Create groups
val employees = principals.getOrCreate(ExternalPrincipal.Group("employees")).toOption.get
val managers = principals.getOrCreate(ExternalPrincipal.Group("managers")).toOption.get

// Parent restricted to employees
collections.create(CollectionConfig(
  path = CollectionPath.unsafe("company"),
  queryableBy = Set(employees),
  isLeaf = false
))

// Child further restricted to managers only
collections.create(CollectionConfig(
  path = CollectionPath.unsafe("company/executive"),
  queryableBy = Set(managers),
  isLeaf = true
))

// Get effective permissions (considers inheritance)
collections.getEffectivePermissions(CollectionPath.unsafe("company/executive"))
// Returns intersection: only managers who are also employees

// Cannot loosen permissions
collections.create(CollectionConfig(
  path = CollectionPath.unsafe("company/public"),
  queryableBy = Set.empty,  // Trying to make public
  isLeaf = true
))
// ERROR: Cannot make collection public when parent is restricted
```

### Querying Collections

```scala
val collections = searchIndex.collections

// Get a specific collection
collections.get(CollectionPath.unsafe("confluence/EN"))
// Right(Some(Collection(...)))

// List all collections matching a pattern
collections.list(CollectionPattern.AllDescendants(CollectionPath.unsafe("confluence")))
// Seq(confluence, confluence/EN, confluence/DE, ...)

// Find accessible collections for a user
val userAuth = UserAuthorization.forUser(userId, groups = Set(employeesGroup))
collections.findAccessible(userAuth, CollectionPattern.All)
// Only returns collections the user can access

// List children of a collection
collections.listChildren(CollectionPath.unsafe("confluence"))
// Seq(confluence/EN, confluence/DE, ...)

// Check if user can query a collection
collections.canQuery(CollectionPath.unsafe("internal"), userAuth)
// Right(true) or Right(false)

// Get collection statistics
collections.stats(CollectionPath.unsafe("confluence/EN"))
// Right(CollectionStats(documentCount = 42, chunkCount = 156, subCollectionCount = 0))
```

---

## Documents

### Ingesting with Permissions

Use `ingestWithPermissions` to add documents with access control:

```scala
// Public document (inherits collection permissions)
rag.ingestWithPermissions(
  collectionPath = CollectionPath.unsafe("public-docs"),
  documentId = "doc-1",
  content = "This document is visible to anyone who can query the collection.",
  metadata = Map("source" -> "wiki", "author" -> "john")
)

// Restricted document (only specific users can read)
rag.ingestWithPermissions(
  collectionPath = CollectionPath.unsafe("public-docs"),
  documentId = "confidential-doc",
  content = "This is confidential information.",
  metadata = Map("classification" -> "confidential"),
  readableBy = Set(PrincipalId.user(42), PrincipalId.group(1))
)
```

### Two-Level Permission Model

Permissions are checked at two levels:

1. **Collection-level (`queryableBy`)**: Controls who can search the collection
2. **Document-level (`readableBy`)**: Fine-grained access within a collection

```
Collection: "hr-docs" (queryableBy: [hr-group])
├── doc-1 (readableBy: [])           ← Anyone in hr-group can read
├── doc-2 (readableBy: [hr-managers]) ← Only HR managers
└── doc-3 (readableBy: [ceo])         ← Only CEO can read
```

**Query Flow:**
1. Filter collections by user's principals (`queryableBy` check)
2. Vector search within accessible collections
3. Filter results by `readableBy` check
4. Return permission-filtered results

### Deleting Documents

```scala
// Delete a specific document
rag.deleteFromCollection(
  collectionPath = CollectionPath.unsafe("public-docs"),
  documentId = "doc-1"
)

// Clear all documents in a collection (via SearchIndex)
searchIndex.clearCollection(CollectionPath.unsafe("public-docs"))
```

---

## Querying

### Permission-Filtered Search

```scala
// Query with user permissions
val userAuth = UserAuthorization.forUser(
  userId = PrincipalId.user(42),
  groups = Set(PrincipalId.group(1))
)

val results = rag.queryWithPermissions(
  auth = userAuth,
  collectionPattern = CollectionPattern.All,
  queryText = "What is the vacation policy?",
  topK = Some(10)
)

results.foreach { searchResults =>
  searchResults.foreach { result =>
    println(s"[${result.score}] ${result.id}")
    println(s"  Content: ${result.content.take(100)}...")
    println(s"  Metadata: ${result.metadata}")
  }
}
```

### Query with Answer Generation

```scala
// Requires LLM client to be configured
val rag = RAG.builder()
  .withEmbeddings("openai")
  .withSearchIndex(searchIndex)
  .withLLM(llmClient)  // Add LLM for answer generation
  .build()
  .getOrElse(???)

val answer = rag.queryWithPermissionsAndAnswer(
  auth = userAuth,
  collectionPattern = CollectionPattern.AllDescendants(CollectionPath.unsafe("hr")),
  question = "How many vacation days do employees get?",
  topK = Some(5)
)

answer.foreach { result =>
  println(s"Answer: ${result.answer}")
  println(s"Based on ${result.contexts.size} sources")
}
```

### Collection Patterns in Queries

```scala
// Search all collections
rag.queryWithPermissions(auth, CollectionPattern.All, "query")

// Search exact collection
rag.queryWithPermissions(
  auth,
  CollectionPattern.Exact(CollectionPath.unsafe("hr/policies")),
  "query"
)

// Search immediate children of a collection
rag.queryWithPermissions(
  auth,
  CollectionPattern.ImmediateChildren(CollectionPath.unsafe("confluence")),
  "query"
)  // Searches: confluence/EN, confluence/DE, but NOT confluence/EN/archive

// Search all descendants
rag.queryWithPermissions(
  auth,
  CollectionPattern.AllDescendants(CollectionPath.unsafe("confluence")),
  "query"
)  // Searches: confluence, confluence/EN, confluence/EN/archive, etc.
```

---

## Principal Management

### Creating and Looking Up Principals

```scala
val principals = searchIndex.principals

// Create or get existing principal
val john = principals.getOrCreate(ExternalPrincipal.User("john@example.com"))
val engineering = principals.getOrCreate(ExternalPrincipal.Group("engineering"))

// Batch create
val mapping = principals.getOrCreateBatch(Seq(
  ExternalPrincipal.User("alice@example.com"),
  ExternalPrincipal.User("bob@example.com"),
  ExternalPrincipal.Group("hr")
))

// Lookup without creating
principals.lookup(ExternalPrincipal.User("john@example.com"))
// Right(Some(PrincipalId(42)))

principals.lookup(ExternalPrincipal.User("unknown@example.com"))
// Right(None)

// Reverse lookup
principals.getExternalId(PrincipalId(42))
// Right(Some(ExternalPrincipal.User("john@example.com")))

// List all users
principals.list("user", limit = 100, offset = 0)

// Count groups
principals.count("group")
```

---

## Schema Management

### Initializing the Schema

The schema must be initialized before use:

```scala
val searchIndex = PgSearchIndex.fromJdbcUrl(
  jdbcUrl = "jdbc:postgresql://localhost:5432/postgres",
  user = "postgres",
  password = "postgres",
  vectorTableName = "rag_vectors"
).getOrElse(???)

// Initialize permission tables and indexes
searchIndex.initializeSchema()
```

This creates:
- `llm4s_principals` table for user/group ID mapping
- `llm4s_collections` table for collection hierarchy
- Extends your vectors table with `collection_id` and `readable_by` columns
- GIN indexes for efficient array containment queries

### Migration from Non-Permission RAG

If you have existing vectors without permissions:

```scala
import org.llm4s.rag.permissions.pg.PgSchemaManager
import java.sql.DriverManager

val conn = DriverManager.getConnection(jdbcUrl, user, password)

// Run full migration
PgSchemaManager.runFullMigration(conn, "vectors").foreach { stats =>
  println(s"Created ${stats.tablesCreated} tables")
  println(s"Created ${stats.indexesCreated} indexes")
  println(s"Migrated ${stats.vectorsMigrated} vectors to default collection")
}

conn.close()
```

This:
1. Creates permission tables
2. Adds `collection_id` and `readable_by` columns to your vectors table
3. Creates a "default" public collection
4. Migrates existing vectors to the default collection

---

## Complete Example

Here's a complete example showing a multi-tenant document system:

```scala
import org.llm4s.rag.RAG
import org.llm4s.rag.RAG.RAGConfigOps
import org.llm4s.rag.permissions._
import org.llm4s.rag.permissions.pg.PgSearchIndex

object MultiTenantRAGExample extends App {

  // 1. Setup
  val searchIndex = PgSearchIndex.fromJdbcUrl(
    "jdbc:postgresql://localhost:5432/postgres",
    "postgres", "postgres", "vectors"
  ).getOrElse(throw new RuntimeException("Failed to create SearchIndex"))

  searchIndex.initializeSchema()

  val principals = searchIndex.principals
  val collections = searchIndex.collections

  // 2. Create users and groups
  val john = principals.getOrCreate(ExternalPrincipal.User("john@acme.com")).toOption.get
  val alice = principals.getOrCreate(ExternalPrincipal.User("alice@acme.com")).toOption.get
  val engineering = principals.getOrCreate(ExternalPrincipal.Group("engineering")).toOption.get
  val hr = principals.getOrCreate(ExternalPrincipal.Group("hr")).toOption.get

  // 3. Create collection hierarchy
  //    acme (parent, all employees)
  //    ├── acme/engineering (engineering group)
  //    │   └── acme/engineering/secrets (restricted)
  //    └── acme/hr (hr group)

  collections.create(CollectionConfig(
    path = CollectionPath.unsafe("acme"),
    queryableBy = Set(engineering, hr),  // All employees
    isLeaf = false
  ))

  collections.create(CollectionConfig.restrictedLeaf(
    CollectionPath.unsafe("acme/engineering"),
    Set(engineering)
  ))

  collections.create(CollectionConfig.restrictedLeaf(
    CollectionPath.unsafe("acme/hr"),
    Set(hr)
  ))

  // 4. Build RAG
  val rag = RAG.builder()
    .withEmbeddings("openai")
    .withSearchIndex(searchIndex)
    .build()
    .getOrElse(throw new RuntimeException("Failed to build RAG"))

  // 5. Ingest documents
  rag.ingestWithPermissions(
    CollectionPath.unsafe("acme/engineering"),
    "api-docs",
    "Our REST API uses OAuth 2.0 for authentication...",
    metadata = Map("type" -> "documentation")
  )

  rag.ingestWithPermissions(
    CollectionPath.unsafe("acme/hr"),
    "vacation-policy",
    "Employees receive 20 days of paid vacation per year...",
    metadata = Map("type" -> "policy")
  )

  // Secret document only Alice can read
  rag.ingestWithPermissions(
    CollectionPath.unsafe("acme/hr"),
    "salary-data",
    "Confidential salary information...",
    readableBy = Set(alice)  // Only Alice can read
  )

  // 6. Query as different users
  val johnAuth = UserAuthorization.forUser(john, Set(engineering))
  val aliceAuth = UserAuthorization.forUser(alice, Set(hr))

  println("=== John (Engineering) searching 'vacation' ===")
  rag.queryWithPermissions(johnAuth, CollectionPattern.All, "vacation policy").foreach { results =>
    if (results.isEmpty) println("No results (no access to HR docs)")
    else results.foreach(r => println(s"Found: ${r.id}"))
  }

  println("\n=== Alice (HR) searching 'vacation' ===")
  rag.queryWithPermissions(aliceAuth, CollectionPattern.All, "vacation policy").foreach { results =>
    results.foreach(r => println(s"Found: ${r.id}"))
  }

  println("\n=== Alice searching 'salary' ===")
  rag.queryWithPermissions(aliceAuth, CollectionPattern.All, "salary").foreach { results =>
    results.foreach(r => println(s"Found: ${r.id} (secret doc visible to Alice)"))
  }

  // 7. Cleanup
  rag.close()
  searchIndex.close()
}
```

---

## Best Practices

### Collection Design

1. **Use meaningful hierarchy**: `{org}/{department}/{project}` or `{source}/{category}`
2. **Keep paths short**: Deep nesting increases permission check overhead
3. **Plan for growth**: Use parent collections for organizational units
4. **Document your structure**: Create a diagram of your collection hierarchy

### Permission Design

1. **Prefer groups over users**: Easier to manage at scale
2. **Use empty `queryableBy` for public**: More efficient than listing all users
3. **Reserve `readableBy` for exceptions**: Most docs should inherit from collection
4. **Validate early**: Check permissions before expensive operations

### Performance

1. **Use batch operations**: `getOrCreateBatch` for multiple principals
2. **Create indexes**: The schema manager creates GIN indexes automatically
3. **Limit pattern scope**: Use specific patterns instead of `All` when possible
4. **Monitor collection sizes**: Large collections may need partitioning

---

## API Reference

### SearchIndex Trait

```scala
trait SearchIndex {
  def principals: PrincipalStore
  def collections: CollectionStore

  def query(
    auth: UserAuthorization,
    collectionPattern: CollectionPattern,
    queryVector: Array[Float],
    topK: Int = 10,
    additionalFilter: Option[MetadataFilter] = None
  ): Result[Seq[ScoredRecord]]

  def ingest(
    collectionPath: CollectionPath,
    documentId: String,
    chunks: Seq[ChunkWithEmbedding],
    metadata: Map[String, String] = Map.empty,
    readableBy: Set[PrincipalId] = Set.empty
  ): Result[Int]

  def deleteDocument(collectionPath: CollectionPath, documentId: String): Result[Long]
  def clearCollection(collectionPath: CollectionPath): Result[Long]
  def stats(collectionPath: CollectionPath): Result[CollectionStats]
  def initializeSchema(): Result[Unit]
  def close(): Unit
}
```

### RAG Permission Methods

```scala
class RAG {
  def searchIndex: Option[SearchIndex]
  def hasPermissions: Boolean

  def queryWithPermissions(
    auth: UserAuthorization,
    collectionPattern: CollectionPattern,
    queryText: String,
    topK: Option[Int] = None
  ): Result[Seq[RAGSearchResult]]

  def queryWithPermissionsAndAnswer(
    auth: UserAuthorization,
    collectionPattern: CollectionPattern,
    question: String,
    topK: Option[Int] = None
  ): Result[RAGAnswerResult]

  def ingestWithPermissions(
    collectionPath: CollectionPath,
    documentId: String,
    content: String,
    metadata: Map[String, String] = Map.empty,
    readableBy: Set[PrincipalId] = Set.empty
  ): Result[Int]

  def deleteFromCollection(
    collectionPath: CollectionPath,
    documentId: String
  ): Result[Long]
}
```

---

## Troubleshooting

### Common Issues

**"Collection not found" error**
- Ensure the collection exists before ingesting
- Use `ensureExists` to create parent hierarchy automatically

**"Cannot make collection public when parent is restricted"**
- Child collections cannot have looser permissions than parents
- Either make the parent public or restrict the child to a subset of parent's principals

**"SearchIndex required" error**
- Call `.withSearchIndex(index)` when building RAG
- Ensure the SearchIndex is created and initialized

**Empty search results**
- Check that the user has access to the collection (`canQuery`)
- Verify documents have matching `readableBy` permissions
- Confirm the collection pattern includes the target collection

### Debugging Tips

```scala
// Check user's accessible collections
collections.findAccessible(userAuth, CollectionPattern.All).foreach { accessible =>
  println(s"User can access: ${accessible.map(_.path.value)}")
}

// Check effective permissions
collections.getEffectivePermissions(path).foreach { perms =>
  if (perms.isEmpty) println("Collection is public")
  else println(s"Required principals: ${perms.map(_.value)}")
}

// Verify collection exists and is a leaf
collections.get(path).foreach {
  case Some(c) => println(s"Collection ${c.path.value}: isLeaf=${c.isLeaf}")
  case None => println("Collection not found")
}
```

---

## Next Steps

- **[Vector Store Guide](vector-store)** - Learn about the underlying vector storage
- **[RAG Evaluation Guide](rag-evaluation)** - Measure and improve RAG quality
- **[Examples Gallery](/examples/#embeddings-examples)** - See more RAG examples
