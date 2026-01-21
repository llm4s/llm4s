package org.llm4s.samples.rag

import org.llm4s.rag.{ EmbeddingProvider, RAG }
import org.llm4s.rag.RAG.RAGConfigOps
import org.llm4s.rag.permissions._
import org.llm4s.rag.permissions.pg.PgSearchIndex
import org.llm4s.config.Llm4sConfig
import org.slf4j.LoggerFactory
import scala.util.chaining._

/**
 * Permission-Based RAG Example
 *
 * This example demonstrates the permission-aware RAG API that enables
 * enterprise-grade access control for document retrieval.
 *
 * Key features:
 * - Hierarchical collection structure (e.g., confluence/EN, confluence/DE)
 * - User and group-based access control
 * - Collection-level queryable_by permissions
 * - Document-level readable_by permissions
 * - Pattern-based collection queries (star, path/star, path/star-star)
 *
 * Prerequisites:
 * - PostgreSQL with pgvector extension
 * - OPENAI_API_KEY environment variable
 *
 * Usage:
 *   # Start PostgreSQL with pgvector
 *   docker run -d --name pgvector -p 5432:5432 \
 *     -e POSTGRES_PASSWORD=postgres \
 *     pgvector/pgvector:pg16
 *
 *   # Run the example
 *   export OPENAI_API_KEY=sk-...
 *   export PGVECTOR_HOST=localhost
 *   export PGVECTOR_PORT=5432
 *   export PGVECTOR_DATABASE=postgres
 *   export PGVECTOR_USER=postgres
 *   export PGVECTOR_PASSWORD=postgres
 *   sbt "samples/runMain org.llm4s.samples.rag.PermissionBasedRAGExample"
 */
object PermissionBasedRAGExample extends App {
  private val logger = LoggerFactory.getLogger(getClass)

  logger.info("=" * 60)
  logger.info("Permission-Based RAG Example")
  logger.info("=" * 60)

  // ========== Part 1: Core Types Overview ==========
  logger.info("--- Part 1: Core Types Overview ---")

  // Principal IDs: Positive = User, Negative = Group
  val userId  = PrincipalId.user(42).tap(u => logger.info("User ID: {} (raw: {})", u, u.value))
  val groupId = PrincipalId.group(5).tap(g => logger.info("Group ID: {} (raw: {})", g, g.value)) // Stored as -5

  // Collection paths with hierarchy
  val rootPath       = CollectionPath.create("confluence")
  val childPath      = CollectionPath.create("confluence/EN")
  val grandchildPath = CollectionPath.create("confluence/EN/archive")

  logger.info("Collection paths:")
  rootPath.foreach(p => logger.info("  Root: {} (depth: {})", p, p.depth))
  childPath.foreach(p => logger.info("  Child: {} (depth: {})", p, p.depth))
  grandchildPath.foreach { p =>
    logger.info("  Grandchild: {} (depth: {})", p, p.depth)
    logger.info("    Parent: {}", p.parent.map(_.value).getOrElse("none"))
  }

  // Collection patterns for querying
  logger.info("Collection patterns:")
  logger.info("  * -> matches all collections")
  logger.info("  confluence -> exact match only")
  logger.info("  confluence/★ -> immediate children (EN, DE)")
  logger.info("  confluence/★★ -> all descendants (EN, EN/archive, DE)")

  val patterns = Seq(
    "*"             -> CollectionPath.unsafe("any/path"),
    "confluence"    -> CollectionPath.unsafe("confluence"),
    "confluence/*"  -> CollectionPath.unsafe("confluence/EN"),
    "confluence/**" -> CollectionPath.unsafe("confluence/EN/archive")
  )

  patterns.foreach { case (patternStr, testPath) =>
    CollectionPattern.parse(patternStr).foreach { pattern =>
      val matches = pattern.matches(testPath)
      logger.info("  Pattern '{}' matches '{}': {}", patternStr, testPath, matches)
    }
  }

  // User authorization context
  logger.info("User authorization:")
  val auth = UserAuthorization
    .forUser(
      userId = PrincipalId.user(1),
      groups = Set(PrincipalId.group(10), PrincipalId.group(20))
    )
    .tap(a => logger.info("  Principal IDs: {}", a.principalIds.map(_.value).toSeq.sorted))
    .tap(a => logger.info("  Is admin: {}", a.isAdmin))

  logger.info("Admin authorization:")
  logger.info("  Bypasses all permission checks: {}", UserAuthorization.Admin.isAdmin)

  // ========== Part 2: API Overview (Without Database) ==========
  logger.info("--- Part 2: Permission-Aware RAG API ---")

  logger.info("""
    |// Configure RAG with a SearchIndex for permissions
    |val config = RAG.builder()
    |  .withEmbeddings(EmbeddingProvider.OpenAI)
    |  .withSearchIndex(searchIndex)
    |  .build()
    |
    |// Create collections with access control
    |searchIndex.collections.create(CollectionConfig(
    |  path = CollectionPath.unsafe("internal"),
    |  queryableBy = Set(PrincipalId.group(1)), // Only admins group
    |  isLeaf = true
    |))
    |
    |// Ingest with document-level permissions
    |rag.ingestWithPermissions(
    |  collectionPath = CollectionPath.unsafe("internal"),
    |  documentId = "confidential-doc-1",
    |  content = "Secret company information...",
    |  readableBy = Set(PrincipalId.user(42)) // Only user 42 can read
    |)
    |
    |// Query with permission filtering
    |val results = rag.queryWithPermissions(
    |  auth = UserAuthorization.forUser(PrincipalId.user(42), Set()),
    |  collectionPattern = CollectionPattern.All,
    |  queryText = "company secrets"
    |)
  """.stripMargin)

  // ========== Part 3: Live Demo with PostgreSQL ==========
  logger.info("--- Part 3: Live Demo with PostgreSQL ---")

  val pgConfigResult = Llm4sConfig.pgSearchIndex()

  pgConfigResult match {
    case Left(error) =>
      logger.info("Could not load PostgreSQL config: {}", error.message)
      logger.info("To run the live demo, set env vars like:")
      logger.info("  export PGVECTOR_HOST=localhost")
      logger.info("  export PGVECTOR_PORT=5432")
      logger.info("  export PGVECTOR_DATABASE=postgres")
      logger.info("  export PGVECTOR_USER=postgres")
      logger.info("  export PGVECTOR_PASSWORD=postgres")
      logger.info("Skipping live demo...")

    case Right(pgConfig) =>
      val config = pgConfig.copy(vectorTableName = "permission_demo_vectors")
      logger.info("PostgreSQL URL: {}", config.jdbcUrl)

      // Try to create a SearchIndex
      PgSearchIndex(config) match {
        case Left(error) =>
          logger.info("Could not connect to PostgreSQL: {}", error.message)
          logger.info("To run the live demo, start PostgreSQL with pgvector:")
          logger.info("  docker run -d --name pgvector -p 5432:5432 \\")
          logger.info("    -e POSTGRES_PASSWORD=postgres \\")
          logger.info("    pgvector/pgvector:pg16")
          logger.info("Skipping live demo...")

        case Right(searchIndex) =>
          logger.info("Connected to PostgreSQL!")

          // Initialize schema
          searchIndex.initializeSchema() match {
            case Left(error) =>
              logger.info("Schema initialization failed: {}", error.message)

            case Right(_) =>
              logger.info("Schema initialized.")

              // Create collections
              val collections = searchIndex.collections
              val principals  = searchIndex.principals

              logger.info("Creating users and groups...")

              // Create principals
              val johnResult        = principals.getOrCreate(ExternalPrincipal.User("john@example.com"))
              val janeResult        = principals.getOrCreate(ExternalPrincipal.User("jane@example.com"))
              val engineeringResult = principals.getOrCreate(ExternalPrincipal.Group("engineering"))
              val hrResult          = principals.getOrCreate(ExternalPrincipal.Group("hr"))

              (for {
                john        <- johnResult
                jane        <- janeResult
                engineering <- engineeringResult
                hr          <- hrResult
              } yield {
                logger.info("  john@example.com -> {}", john.value)
                logger.info("  jane@example.com -> {}", jane.value)
                logger.info("  engineering group -> {}", engineering.value)
                logger.info("  hr group -> {}", hr.value)

                logger.info("Creating collections...")

                // Create collection hierarchy
                val publicConfig = CollectionConfig(
                  path = CollectionPath.unsafe("public"),
                  queryableBy = Set.empty, // Empty = public
                  isLeaf = true
                )
                val engineeringConfig = CollectionConfig(
                  path = CollectionPath.unsafe("engineering"),
                  queryableBy = Set(engineering),
                  isLeaf = true
                )
                val hrConfig = CollectionConfig(
                  path = CollectionPath.unsafe("hr"),
                  queryableBy = Set(hr),
                  isLeaf = true
                )

                collections.create(publicConfig).foreach(_ => logger.info("  Created 'public' (accessible to all)"))
                collections
                  .create(engineeringConfig)
                  .foreach(_ => logger.info("  Created 'engineering' (engineering group only)"))
                collections.create(hrConfig).foreach(_ => logger.info("  Created 'hr' (HR group only)"))

                // Demo permission filtering
                logger.info("Permission filtering demo:")

                val johnAuth = UserAuthorization.forUser(john, Set(engineering))
                val janeAuth = UserAuthorization.forUser(jane, Set(hr))

                logger.info("  John (engineering): principals = {}", johnAuth.asSeq.sorted)
                collections.findAccessible(johnAuth, CollectionPattern.All).foreach { accessible =>
                  logger.info("    Can access: {}", accessible.map(_.path.value).mkString(", "))
                }

                logger.info("  Jane (HR): principals = {}", janeAuth.asSeq.sorted)
                collections.findAccessible(janeAuth, CollectionPattern.All).foreach { accessible =>
                  logger.info("    Can access: {}", accessible.map(_.path.value).mkString(", "))
                }

                logger.info("  Admin: bypasses all checks")
                collections.findAccessible(UserAuthorization.Admin, CollectionPattern.All).foreach { accessible =>
                  logger.info("    Can access: {}", accessible.map(_.path.value).mkString(", "))
                }

              }) match {
                case Left(error) =>
                  logger.info("Error during demo: {}", error.message)
                case Right(_) =>
                  logger.info("Demo completed successfully!")
              }

              // Build RAG with SearchIndex
              logger.info("--- Building RAG with Permission Support ---")

              val ragResult = for {
                rag <- RAG
                  .builder()
                  .withEmbeddings(EmbeddingProvider.OpenAI)
                  .withSearchIndex(searchIndex)
                  .withTopK(5)
                  .build()
              } yield rag

              ragResult match {
                case Left(error) =>
                  logger.info("Could not build RAG: {}", error.message)
                  logger.info("Make sure OPENAI_API_KEY is set for embeddings.")

                case Right(rag) =>
                  logger.info("RAG built with permission support!")
                  logger.info("  Has permissions: {}", rag.hasPermissions)
                  rag.close()
              }
          }

          searchIndex.close()
      }
  }

  // ========== Part 4: Two-Level Permission Model ==========
  logger.info("--- Part 4: Two-Level Permission Model ---")
  logger.info("""
    |Permission filtering happens at two levels:
    |
    |1. COLLECTION-LEVEL (queryable_by):
    |   - Controls who can search within a collection
    |   - Set when creating collection
    |   - Empty array = public (anyone can search)
    |
    |2. DOCUMENT-LEVEL (readable_by):
    |   - Fine-grained control within a collection
    |   - Set when ingesting documents
    |   - Empty array = inherit from collection
    |   - Non-empty = only specified principals can read
    |
    |Example:
    |  Collection: "hr-docs" queryable_by: [hr_group]
    |    - Doc 1: readable_by: [] -> anyone in hr_group can read
    |    - Doc 2: readable_by: [ceo_user] -> only CEO can read
    |
    |Query flow:
    |  1. Filter collections by user's principals (queryable_by check)
    |  2. Vector search within accessible collections
    |  3. Filter results by readable_by check
    |  4. Return permission-filtered results
  """.stripMargin)

  logger.info("=" * 60)
  logger.info("Permission-Based RAG Example Complete")
  logger.info("=" * 60)
}
