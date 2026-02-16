package org.llm4s.rag.permissions.pg

import org.llm4s.rag.permissions._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Integration tests for PgSearchIndex.
 *
 * These tests require a running PostgreSQL database with pgvector extension.
 * Set PGVECTOR_TEST_URL environment variable to enable tests.
 *
 * To run:
 *   export PGVECTOR_TEST_URL=jdbc:postgresql://localhost:5432/postgres
 *   export PGVECTOR_USER=postgres
 *   export PGVECTOR_PASSWORD=postgres
 *   sbt "core/testOnly org.llm4s.rag.permissions.pg.PgSearchIndexSpec"
 */
class PgSearchIndexSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  // Skip all tests if PostgreSQL is not available
  private val pgUrl      = sys.env.get("PGVECTOR_TEST_URL")
  private val pgUser     = sys.env.getOrElse("PGVECTOR_USER", "postgres")
  private val pgPassword = sys.env.getOrElse("PGVECTOR_PASSWORD", "postgres")

  private val testTableName = "test_permission_vectors"

  private var searchIndex: Option[PgSearchIndex] = None

  override def beforeAll(): Unit = {
    super.beforeAll()
    pgUrl.foreach { url =>
      PgSearchIndex.fromJdbcUrl(url, pgUser, pgPassword, testTableName) match {
        case Right(index) =>
          // Clean up from previous test runs
          index.dropSchema()

          index.initializeSchema() match {
            case Right(_) => searchIndex = Some(index)
            case Left(e)  => println(s"Failed to initialize schema: ${e.message}")
          }
        case Left(e) =>
          println(s"Failed to create PgSearchIndex: ${e.message}")
      }
    }
  }

  override def afterAll(): Unit = {
    searchIndex.foreach(_.close())
    super.afterAll()
  }

  private def requirePg(): Unit =
    assume(searchIndex.isDefined, "PostgreSQL with pgvector not available")

  // ========== PrincipalStore Tests ==========

  "PgPrincipalStore" should "create and lookup users" in {
    requirePg()
    val store = searchIndex.get.principals

    val result = for {
      id     <- store.getOrCreate(ExternalPrincipal.User("test-user@example.com"))
      lookup <- store.lookup(ExternalPrincipal.User("test-user@example.com"))
    } yield (id, lookup)

    result.isRight shouldBe true
    val (id, lookup) = result.toOption.get
    id.isUser shouldBe true
    id.value should be > 0
    lookup shouldBe Some(id)
  }

  it should "create groups with negative IDs" in {
    requirePg()
    val store = searchIndex.get.principals

    val result = store.getOrCreate(ExternalPrincipal.Group("test-group"))

    result.isRight shouldBe true
    val id = result.toOption.get
    id.isGroup shouldBe true
    id.value should be < 0
  }

  it should "return existing ID on duplicate getOrCreate" in {
    requirePg()
    val store = searchIndex.get.principals

    val result = for {
      id1 <- store.getOrCreate(ExternalPrincipal.User("duplicate@example.com"))
      id2 <- store.getOrCreate(ExternalPrincipal.User("duplicate@example.com"))
    } yield (id1, id2)

    result.isRight shouldBe true
    val (id1, id2) = result.toOption.get
    id1 shouldBe id2
  }

  it should "support batch operations" in {
    requirePg()
    val store = searchIndex.get.principals

    val externals = Seq(
      ExternalPrincipal.User("batch1@example.com"),
      ExternalPrincipal.User("batch2@example.com"),
      ExternalPrincipal.Group("batch-group")
    )

    val result = store.getOrCreateBatch(externals)

    result.isRight shouldBe true
    val mapping = result.toOption.get
    mapping.size shouldBe 3
    mapping.values.filter(_.isUser).size shouldBe 2
    mapping.values.filter(_.isGroup).size shouldBe 1
  }

  // ========== CollectionStore Tests ==========

  "PgCollectionStore" should "create and retrieve collections" in {
    requirePg()
    val store = searchIndex.get.collections

    val config = CollectionConfig(
      path = CollectionPath.unsafe("test-collection"),
      queryableBy = Set.empty,
      isLeaf = true
    )

    val result = for {
      created   <- store.create(config)
      retrieved <- store.get(config.path)
    } yield (created, retrieved)

    result.isRight shouldBe true
    val (created, retrieved) = result.toOption.get
    created.path.value shouldBe "test-collection"
    created.isLeaf shouldBe true
    created.isPublic shouldBe true
    retrieved shouldBe Some(created)
  }

  it should "create hierarchical collections" in {
    requirePg()
    val store = searchIndex.get.collections

    val parentConfig = CollectionConfig(
      path = CollectionPath.unsafe("parent-coll"),
      queryableBy = Set.empty,
      isLeaf = true
    )

    val childConfig = CollectionConfig(
      path = CollectionPath.unsafe("parent-coll/child"),
      queryableBy = Set.empty,
      isLeaf = true
    )

    val result = for {
      parent        <- store.create(parentConfig)
      child         <- store.create(childConfig)
      updatedParent <- store.get(parentConfig.path)
    } yield (parent, child, updatedParent)

    result.isRight shouldBe true
    val (parent, child, updatedParent) = result.toOption.get
    child.parentPath shouldBe Some(parent.path)
    // Parent should be marked as non-leaf after child is created
    updatedParent.get.isLeaf shouldBe false
  }

  it should "reject child permissions that are not subset of parent" in {
    requirePg()
    val store      = searchIndex.get.collections
    val principals = searchIndex.get.principals

    val result = for {
      group1 <- principals.getOrCreate(ExternalPrincipal.Group("perm-test-group1"))
      group2 <- principals.getOrCreate(ExternalPrincipal.Group("perm-test-group2"))

      // Create restricted parent with group1
      _ <- store.create(
        CollectionConfig(
          path = CollectionPath.unsafe("perm-test-parent"),
          queryableBy = Set(group1),
          isLeaf = true
        )
      )

      // Try to create child with group2 (not in parent) - should fail
      childResult = store.create(
        CollectionConfig(
          path = CollectionPath.unsafe("perm-test-parent/child"),
          queryableBy = Set(group2),
          isLeaf = true
        )
      )
    } yield childResult

    result.isRight shouldBe true
    val childResult = result.toOption.get
    childResult.isLeft shouldBe true
    childResult.left.toOption.get.message should include("not in parent")
  }

  it should "allow child permissions that are subset of parent" in {
    requirePg()
    val store      = searchIndex.get.collections
    val principals = searchIndex.get.principals

    val result = for {
      group1 <- principals.getOrCreate(ExternalPrincipal.Group("subset-test-group1"))
      group2 <- principals.getOrCreate(ExternalPrincipal.Group("subset-test-group2"))

      // Create restricted parent with both groups
      _ <- store.create(
        CollectionConfig(
          path = CollectionPath.unsafe("subset-test-parent"),
          queryableBy = Set(group1, group2),
          isLeaf = true
        )
      )

      // Create child with only group1 (subset of parent) - should succeed
      child <- store.create(
        CollectionConfig(
          path = CollectionPath.unsafe("subset-test-parent/child"),
          queryableBy = Set(group1),
          isLeaf = true
        )
      )
    } yield child

    result.isRight shouldBe true
    result.toOption.get.queryableBy.size shouldBe 1
  }

  it should "list collections by pattern" in {
    requirePg()
    val store = searchIndex.get.collections

    // Create some collections
    store.ensureExists(CollectionConfig.publicLeaf(CollectionPath.unsafe("pattern-test")))
    store.ensureExists(CollectionConfig.publicLeaf(CollectionPath.unsafe("pattern-test/a")))
    store.ensureExists(CollectionConfig.publicLeaf(CollectionPath.unsafe("pattern-test/b")))
    store.ensureExists(CollectionConfig.publicLeaf(CollectionPath.unsafe("pattern-test/a/nested")))

    // Test ImmediateChildren pattern
    val immediateResult = store.list(
      CollectionPattern.ImmediateChildren(CollectionPath.unsafe("pattern-test"))
    )
    immediateResult.isRight shouldBe true
    val immediate = immediateResult.toOption.get
    (immediate.map(_.path.value) should contain).allOf("pattern-test/a", "pattern-test/b")
    immediate.map(_.path.value) should not contain "pattern-test/a/nested"

    // Test AllDescendants pattern
    val descendantsResult = store.list(
      CollectionPattern.AllDescendants(CollectionPath.unsafe("pattern-test"))
    )
    descendantsResult.isRight shouldBe true
    val descendants = descendantsResult.toOption.get
    (descendants.map(_.path.value) should contain)
      .allOf("pattern-test", "pattern-test/a", "pattern-test/b", "pattern-test/a/nested")
  }

  it should "filter accessible collections by user permissions" in {
    requirePg()
    val store      = searchIndex.get.collections
    val principals = searchIndex.get.principals

    // Create a restricted collection
    val result = for {
      adminGroup <- principals.getOrCreate(ExternalPrincipal.Group("admin-access-test"))
      _ <- store.ensureExists(
        CollectionConfig(
          path = CollectionPath.unsafe("restricted-test"),
          queryableBy = Set(adminGroup),
          isLeaf = true
        )
      )
      _ <- store.ensureExists(CollectionConfig.publicLeaf(CollectionPath.unsafe("public-test")))

      // Query as admin
      adminResults <- store.findAccessible(UserAuthorization.Admin, CollectionPattern.All)

      // Query as user with admin group
      userWithGroup <- store.findAccessible(
        UserAuthorization(Set(adminGroup)),
        CollectionPattern.All
      )

      // Query as user without admin group
      userWithoutGroup <- store.findAccessible(
        UserAuthorization(Set(PrincipalId.user(999))),
        CollectionPattern.All
      )
    } yield (adminResults, userWithGroup, userWithoutGroup)

    result.isRight shouldBe true
    val (adminResults, userWithGroup, userWithoutGroup) = result.toOption.get

    // Admin sees everything
    adminResults.map(_.path.value) should contain("restricted-test")
    adminResults.map(_.path.value) should contain("public-test")

    // User with admin group sees restricted
    userWithGroup.map(_.path.value) should contain("restricted-test")

    // User without admin group only sees public
    userWithoutGroup.map(_.path.value) should contain("public-test")
    userWithoutGroup.map(_.path.value) should not contain "restricted-test"
  }

  it should "update collection permissions" in {
    requirePg()
    val store      = searchIndex.get.collections
    val principals = searchIndex.get.principals

    val result = for {
      group1 <- principals.getOrCreate(ExternalPrincipal.Group("update-perm-group1"))
      group2 <- principals.getOrCreate(ExternalPrincipal.Group("update-perm-group2"))

      // Create collection with group1
      created <- store.create(
        CollectionConfig(
          path = CollectionPath.unsafe("update-perm-test"),
          queryableBy = Set(group1),
          isLeaf = true
        )
      )

      // Update to also include group2
      updated <- store.updatePermissions(
        CollectionPath.unsafe("update-perm-test"),
        Set(group1, group2)
      )
    } yield (created, updated)

    result.isRight shouldBe true
    val (created, updated) = result.toOption.get
    created.queryableBy.size shouldBe 1
    updated.queryableBy.size shouldBe 2
  }

  it should "delete collections" in {
    requirePg()
    val store = searchIndex.get.collections

    val result = for {
      _ <- store.create(
        CollectionConfig.publicLeaf(CollectionPath.unsafe("delete-coll-test"))
      )
      beforeDelete <- store.get(CollectionPath.unsafe("delete-coll-test"))
      _            <- store.delete(CollectionPath.unsafe("delete-coll-test"))
      afterDelete  <- store.get(CollectionPath.unsafe("delete-coll-test"))
    } yield (beforeDelete, afterDelete)

    result match {
      case Left(err) => fail(s"Delete test failed: ${err.message}")
      case Right((beforeDelete, afterDelete)) =>
        beforeDelete.isDefined shouldBe true
        afterDelete.isDefined shouldBe false
    }
  }

  it should "get collection stats" in {
    requirePg()
    val index = searchIndex.get

    val result = for {
      _ <- index.collections.ensureExists(
        CollectionConfig.publicLeaf(CollectionPath.unsafe("stats-test"))
      )

      // Ingest some documents
      _ <- index.ingest(
        collectionPath = CollectionPath.unsafe("stats-test"),
        documentId = "stats-doc-1",
        chunks = Seq(
          ChunkWithEmbedding("Content 1", Array(0.1f, 0.2f, 0.3f), 0),
          ChunkWithEmbedding("Content 2", Array(0.2f, 0.3f, 0.4f), 1)
        )
      )

      stats <- index.stats(CollectionPath.unsafe("stats-test"))
    } yield stats

    result match {
      case Left(err) => fail(s"Stats test failed: ${err.message}")
      case Right(stats) =>
        stats.documentCount should be >= 1L
    }
  }

  // ========== SearchIndex Integration Tests ==========

  "PgSearchIndex" should "ingest and query with permissions" in {
    requirePg()
    val index = searchIndex.get

    // Setup: Create a collection and ingest a document
    val result = for {
      _ <- index.collections.ensureExists(
        CollectionConfig.publicLeaf(CollectionPath.unsafe("search-test"))
      )

      // Ingest with a test embedding (3 dimensions for simplicity)
      count <- index.ingest(
        collectionPath = CollectionPath.unsafe("search-test"),
        documentId = "doc-1",
        chunks = Seq(
          ChunkWithEmbedding("Test content about Scala programming", Array(0.1f, 0.2f, 0.3f), 0),
          ChunkWithEmbedding("More content about functional programming", Array(0.2f, 0.3f, 0.4f), 1)
        ),
        metadata = Map("source" -> "test"),
        readableBy = Set.empty
      )

      // Query
      results <- index.query(
        auth = UserAuthorization.Admin,
        collectionPattern = CollectionPattern.Exact(CollectionPath.unsafe("search-test")),
        queryVector = Array(0.15f, 0.25f, 0.35f),
        topK = 10
      )
    } yield (count, results)

    result.isRight shouldBe true
    val (count, results) = result.toOption.get
    count shouldBe 2
    results should not be empty
  }

  it should "respect document-level readable_by permissions" in {
    requirePg()
    val index = searchIndex.get

    val result = for {
      // Create user
      secretUser <- index.principals.getOrCreate(ExternalPrincipal.User("secret@example.com"))

      // Create collection
      _ <- index.collections.ensureExists(
        CollectionConfig.publicLeaf(CollectionPath.unsafe("readable-by-test"))
      )

      // Ingest public document
      _ <- index.ingest(
        collectionPath = CollectionPath.unsafe("readable-by-test"),
        documentId = "public-doc",
        chunks = Seq(ChunkWithEmbedding("Public content", Array(0.1f, 0.2f, 0.3f), 0)),
        readableBy = Set.empty // Public
      )

      // Ingest secret document
      _ <- index.ingest(
        collectionPath = CollectionPath.unsafe("readable-by-test"),
        documentId = "secret-doc",
        chunks = Seq(ChunkWithEmbedding("Secret content", Array(0.15f, 0.25f, 0.35f), 0)),
        readableBy = Set(secretUser) // Only secretUser can read
      )

      // Query as secretUser - should see both
      secretResults <- index.query(
        auth = UserAuthorization(Set(secretUser)),
        collectionPattern = CollectionPattern.Exact(CollectionPath.unsafe("readable-by-test")),
        queryVector = Array(0.12f, 0.22f, 0.32f),
        topK = 10
      )

      // Query as different user - should only see public
      otherResults <- index.query(
        auth = UserAuthorization(Set(PrincipalId.user(9999))),
        collectionPattern = CollectionPattern.Exact(CollectionPath.unsafe("readable-by-test")),
        queryVector = Array(0.12f, 0.22f, 0.32f),
        topK = 10
      )
    } yield (secretResults, otherResults)

    result.isRight shouldBe true
    val (secretResults, otherResults) = result.toOption.get

    // Check by docId in metadata (chunk IDs now include collection prefix)
    secretResults.flatMap(_.record.metadata.get("docId")) should contain("secret-doc")
    secretResults.flatMap(_.record.metadata.get("docId")) should contain("public-doc")

    otherResults.flatMap(_.record.metadata.get("docId")) should contain("public-doc")
    otherResults.flatMap(_.record.metadata.get("docId")) should not contain "secret-doc"
  }

  it should "delete documents correctly" in {
    requirePg()
    val index = searchIndex.get

    val result = for {
      _ <- index.collections.ensureExists(
        CollectionConfig.publicLeaf(CollectionPath.unsafe("delete-test"))
      )

      _ <- index.ingest(
        collectionPath = CollectionPath.unsafe("delete-test"),
        documentId = "to-delete",
        chunks = Seq(
          ChunkWithEmbedding("Content to delete", Array(0.1f, 0.2f, 0.3f), 0),
          ChunkWithEmbedding("More content to delete", Array(0.2f, 0.3f, 0.4f), 1)
        )
      )

      // Verify document exists
      beforeDelete <- index.query(
        auth = UserAuthorization.Admin,
        collectionPattern = CollectionPattern.Exact(CollectionPath.unsafe("delete-test")),
        queryVector = Array(0.15f, 0.25f, 0.35f),
        topK = 10
      )

      // Delete
      deleteCount <- index.deleteDocument(CollectionPath.unsafe("delete-test"), "to-delete")

      // Verify document is gone
      afterDelete <- index.query(
        auth = UserAuthorization.Admin,
        collectionPattern = CollectionPattern.Exact(CollectionPath.unsafe("delete-test")),
        queryVector = Array(0.15f, 0.25f, 0.35f),
        topK = 10
      )
    } yield (beforeDelete, deleteCount, afterDelete)

    result.isRight shouldBe true
    val (beforeDelete, deleteCount, afterDelete) = result.toOption.get

    // Check by docId in metadata (chunk IDs now include collection prefix)
    beforeDelete.flatMap(_.record.metadata.get("docId")) should contain("to-delete")
    beforeDelete.size shouldBe 2
    deleteCount shouldBe 2
    afterDelete.flatMap(_.record.metadata.get("docId")) should not contain "to-delete"
  }

  it should "allow same documentId in different collections without overwriting" in {
    requirePg()
    val index = searchIndex.get

    val result = for {
      // Create two separate collections
      _ <- index.collections.ensureExists(
        CollectionConfig.publicLeaf(CollectionPath.unsafe("cross-coll-a"))
      )
      _ <- index.collections.ensureExists(
        CollectionConfig.publicLeaf(CollectionPath.unsafe("cross-coll-b"))
      )

      // Ingest same documentId into both collections with different content
      _ <- index.ingest(
        collectionPath = CollectionPath.unsafe("cross-coll-a"),
        documentId = "shared-doc-id",
        chunks = Seq(ChunkWithEmbedding("Content from collection A", Array(0.1f, 0.2f, 0.3f), 0))
      )
      _ <- index.ingest(
        collectionPath = CollectionPath.unsafe("cross-coll-b"),
        documentId = "shared-doc-id",
        chunks = Seq(ChunkWithEmbedding("Content from collection B", Array(0.4f, 0.5f, 0.6f), 0))
      )

      // Query collection A - should get A's content
      resultsA <- index.query(
        auth = UserAuthorization.Admin,
        collectionPattern = CollectionPattern.Exact(CollectionPath.unsafe("cross-coll-a")),
        queryVector = Array(0.1f, 0.2f, 0.3f),
        topK = 10
      )

      // Query collection B - should get B's content
      resultsB <- index.query(
        auth = UserAuthorization.Admin,
        collectionPattern = CollectionPattern.Exact(CollectionPath.unsafe("cross-coll-b")),
        queryVector = Array(0.4f, 0.5f, 0.6f),
        topK = 10
      )
    } yield (resultsA, resultsB)

    result match {
      case Left(err)                   => fail(s"Test failed: ${err.message}")
      case Right((resultsA, resultsB)) =>
        // Both collections should have the document
        resultsA.size shouldBe 1
        resultsB.size shouldBe 1

        // Content should be different (not overwritten)
        resultsA.head.record.content shouldBe Some("Content from collection A")
        resultsB.head.record.content shouldBe Some("Content from collection B")
    }
  }

  // ========== Schema Management Tests ==========

  "PgSchemaManager" should "be idempotent" in {
    requirePg()
    val index = searchIndex.get

    // Initialize schema multiple times should not fail
    val result = for {
      _ <- index.initializeSchema()
      _ <- index.initializeSchema()
      _ <- index.initializeSchema()
    } yield ()

    result.isRight shouldBe true
  }
}
