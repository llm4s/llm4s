package org.llm4s.rag.permissions.pg

import org.llm4s.error.ValidationError
import org.llm4s.it.Tier
import org.llm4s.it.tags.Docker
import org.llm4s.rag.permissions._
import org.llm4s.security.Secret
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.DriverManager
import scala.util.Using

/**
 * The collection hierarchy against Postgres: `canQuery` and `effectiveAccess` agree with `findAccessible` and with
 * search (#2990), and giving a leaf a child never hides its documents (#2991). The SQL alone is checked in
 * `PgCollectionHierarchySpec` (`modules/rag`).
 *
 * To run:
 *   export PGVECTOR_TEST_URL=jdbc:postgresql://localhost:5432/postgres
 *   sbt "it/testOnly org.llm4s.rag.permissions.pg.PgPermissionHierarchySpec"
 */
@Docker
class PgPermissionHierarchySpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val pgUrl      = sys.env.get("PGVECTOR_TEST_URL")
  private val pgUser     = sys.env.getOrElse("PGVECTOR_USER", "postgres")
  private val pgPassword = Secret(sys.env.getOrElse("PGVECTOR_PASSWORD", "postgres"))

  private val tableName                          = "test_permission_hierarchy_vectors"
  private var searchIndex: Option[PgSearchIndex] = None

  private val root                             = PgSuiteCleanup.runRoot("permission-hierarchy")
  private def under(p: String): CollectionPath = CollectionPath.unsafe(s"${root.value}/$p")

  private val vector = Array(0.1f, 0.2f, 0.3f)

  override def beforeAll(): Unit = {
    super.beforeAll()
    pgUrl.foreach { url =>
      PgSearchIndex.fromJdbcUrl(url, pgUser, pgPassword, tableName).foreach { index =>
        PgSuiteCleanup.dropOwnTables(url, pgUser, pgPassword, Seq(tableName))
        index.initializeSchema().flatMap(_ => index.collections.create(CollectionConfig.publicParent(root))) match {
          case Right(_) => searchIndex = Some(index)
          case Left(e)  => println(s"Failed to initialize schema: ${e.message}")
        }
      }
    }
  }

  override def afterAll(): Unit = {
    for {
      index <- searchIndex
      url   <- pgUrl
    } PgSuiteCleanup.removeOwn(index, root, url, pgUser, pgPassword, Seq(tableName))
    searchIndex.foreach(_.close())
    super.afterAll()
  }

  private def index(): PgSearchIndex = {
    Tier.require(searchIndex.isDefined, "PostgreSQL with pgvector not available")
    searchIndex.get
  }

  private def group(name: String): PrincipalId =
    index().principals.getOrCreate(ExternalPrincipal.Group(s"${root.value}-$name")).toOption.get

  private def ingestOne(path: CollectionPath, docId: String): Unit =
    index().ingest(path, docId, Seq(ChunkWithEmbedding(s"text of $docId", vector, 0)), Map.empty, Set.empty) shouldBe
      Right(Seq(PgSearchIndex.chunkId(index().collections.get(path).toOption.flatten.get.id, docId, 0)))

  private def hits(auth: UserAuthorization, path: CollectionPath): Seq[String] =
    index().query(auth, CollectionPattern.Exact(path), vector, 10).toOption.get.map(_.record.id)

  /** Runs `sql` with `path` as its one parameter, outside the store: to build states older versions left behind. */
  private def execute(sql: String, path: CollectionPath): Unit =
    Using.resource(DriverManager.getConnection(pgUrl.get, pgUser, pgPassword.reveal)) { conn =>
      Using.resource(conn.prepareStatement(sql)) { stmt =>
        stmt.setString(1, path.value)
        stmt.executeUpdate()
      }
    }

  "canQuery" should "deny a public child once its parent is restricted, as findAccessible does (#2990)" in {
    val store    = index().collections
    val hr       = group("hr")
    val salaries = under("hr/salaries")

    store.ensureExists(CollectionConfig.publicLeaf(salaries)).isRight shouldBe true
    store.updatePermissions(under("hr"), Set(hr)).isRight shouldBe true

    store.canQuery(salaries, UserAuthorization.Anonymous) shouldBe Right(false)
    store.canQuery(salaries, UserAuthorization(Set(hr))) shouldBe Right(true)
    store.findAccessible(UserAuthorization.Anonymous, CollectionPattern.Exact(salaries)) shouldBe Right(Seq.empty)
    store.effectiveAccess(salaries).map(_.isPublic) shouldBe Right(false)
  }

  it should "deny a user who holds only one of a parent's and a child's disjoint principals (#2990)" in {
    val store = index().collections
    val a     = group("a")
    val b     = group("b")
    val child = under("disjoint/child")

    store.create(CollectionConfig.restrictedParent(under("disjoint"), Set(a))).isRight shouldBe true
    store.create(CollectionConfig.restrictedLeaf(child, Set(b))).isRight shouldBe true

    store.canQuery(child, UserAuthorization.Anonymous) shouldBe Right(false)
    store.canQuery(child, UserAuthorization(Set(a))) shouldBe Right(false)
    store.canQuery(child, UserAuthorization(Set(b))) shouldBe Right(false)
    store.canQuery(child, UserAuthorization(Set(a, b))) shouldBe Right(true)
    store.effectiveAccess(child).map(_.permits(UserAuthorization.Anonymous)) shouldBe Right(false)
    (store.getEffectivePermissions(child): @scala.annotation.nowarn("cat=deprecation")).isLeft shouldBe true
  }

  "The guide's hierarchy" should "be accepted, and searched only by users at both levels (#2990)" in {
    val store     = index().collections
    val employees = group("employees")
    val managers  = group("managers")
    val executive = under("company/executive")

    store.create(CollectionConfig.restrictedParent(under("company"), Set(employees))).isRight shouldBe true
    store.create(CollectionConfig.restrictedLeaf(executive, Set(managers))).map(_.path) shouldBe Right(executive)
    ingestOne(executive, "board-minutes")

    hits(UserAuthorization(Set(employees, managers)), executive) should have size 1
    hits(UserAuthorization(Set(employees)), executive) shouldBe empty
    hits(UserAuthorization(Set(managers)), executive) shouldBe empty
    store.canQuery(executive, UserAuthorization(Set(employees, managers))) shouldBe Right(true)
    store.canQuery(executive, UserAuthorization(Set(managers))) shouldBe Right(false)
  }

  it should "still refuse a public child of a restricted parent" in {
    val store = index().collections
    store.create(CollectionConfig.restrictedParent(under("closed"), Set(group("closed")))).isRight shouldBe true
    store.create(CollectionConfig.publicLeaf(under("closed/open"))).left.toOption.get shouldBe a[ValidationError]
  }

  "Creating a child" should "be refused under a leaf that holds documents, which stay searchable (#2991)" in {
    val store = index().collections
    val docs  = under("docs")
    store.create(CollectionConfig.publicLeaf(docs)).isRight shouldBe true
    ingestOne(docs, "handbook")

    store.ensureExists(CollectionConfig.publicLeaf(under("docs/archive"))).left.toOption.get shouldBe
      a[ValidationError]
    store.get(docs).map(_.map(_.isLeaf)) shouldBe Right(Some(true))
    store.get(under("docs/archive")) shouldBe Right(None)
    hits(UserAuthorization.Admin, docs) should have size 1
  }

  it should "make an empty leaf a parent" in {
    val store = index().collections
    store.create(CollectionConfig.publicLeaf(under("empty"))).isRight shouldBe true
    store.create(CollectionConfig.publicLeaf(under("empty/child"))).isRight shouldBe true
    store.get(under("empty")).map(_.map(_.isLeaf)) shouldBe Right(Some(false))
  }

  it should "leave the parent a leaf when the child's insert fails (#2991)" in {
    val store = index().collections
    val leaf  = under("rollback")
    store.create(CollectionConfig.publicLeaf(leaf)).isRight shouldBe true
    // A child row already present makes the insert fail on the unique path, after the parent's demotion.
    execute(
      "INSERT INTO llm4s_collections (path, parent_path, queryable_by, is_leaf) VALUES (? || '/child', NULL, '{}', TRUE)",
      leaf
    )

    store.create(CollectionConfig.publicLeaf(under("rollback/child"))).isLeft shouldBe true
    store.get(leaf).map(_.map(_.isLeaf)) shouldBe Right(Some(true))
  }

  "Search" should "find the documents of a collection an earlier version made a non-leaf (#2991)" in {
    val store = index().collections
    val stuck = under("stuck")
    store.create(CollectionConfig.publicLeaf(stuck)).isRight shouldBe true
    ingestOne(stuck, "old-doc")
    execute("UPDATE llm4s_collections SET is_leaf = FALSE WHERE path = ?", stuck)

    hits(UserAuthorization.Admin, stuck) should have size 1
    hits(UserAuthorization.Anonymous, stuck) should have size 1
    index().deleteDocumentFromAllCollections("old-doc") shouldBe Right(1L)
  }
}
