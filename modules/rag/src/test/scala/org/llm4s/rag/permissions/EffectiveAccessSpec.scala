package org.llm4s.rag.permissions

import org.llm4s.error.ValidationError
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * [[EffectiveAccess]] and the default [[CollectionStore.effectiveAccess]] (#2990): a collection is queryable by a
 * non-admin only with a principal at every restricted level, so a public child of a restricted parent is not public,
 * and disjoint parent and child sets are not public either.
 */
class EffectiveAccessSpec extends AnyFlatSpec with Matchers {

  private val employees = PrincipalId.group(1)
  private val managers  = PrincipalId.group(2)
  private val hrTeam    = PrincipalId.group(3)
  private val alice     = PrincipalId.user(1)

  private def coll(id: Int, path: String, queryableBy: PrincipalId*): Collection = {
    val p = CollectionPath.unsafe(path)
    Collection(id, p, p.parent, queryableBy.toSet, isLeaf = true)
  }

  private def user(groups: PrincipalId*) = UserAuthorization.forUser(alice, groups.toSet)

  "EffectiveAccess.of" should "be Public when no level is restricted" in {
    EffectiveAccess.of(Seq(coll(1, "docs"), coll(2, "docs/en"))) shouldBe EffectiveAccess.Public
    EffectiveAccess.Public.permits(UserAuthorization.Anonymous) shouldBe true
  }

  it should "not make a public child of a restricted parent public" in {
    val access = EffectiveAccess.of(Seq(coll(1, "hr", hrTeam), coll(2, "hr/salaries")))
    access.isPublic shouldBe false
    access.permits(UserAuthorization.Anonymous) shouldBe false
    access.permits(user(hrTeam)) shouldBe true
  }

  it should "require a principal at every level, which may differ between levels (the guide's hierarchy)" in {
    val access = EffectiveAccess.of(Seq(coll(1, "company", employees), coll(2, "company/executive", managers)))
    access.permits(user(employees, managers)) shouldBe true
    access.permits(user(employees)) shouldBe false
    access.permits(user(managers)) shouldBe false
    access.permits(UserAuthorization.Anonymous) shouldBe false
    access.permits(UserAuthorization.Admin) shouldBe true
    access match {
      case EffectiveAccess.Restricted(levels) =>
        levels.map(l => (l.path.value, l.anyOf)) shouldBe Seq(
          "company"           -> Set(employees),
          "company/executive" -> Set(managers)
        )
      case other => fail(s"expected Restricted, got $other")
    }
  }

  "EffectiveAccess.Nobody" should "admit only an admin" in {
    EffectiveAccess.Nobody.permits(UserAuthorization.Admin) shouldBe true
    EffectiveAccess.Nobody.permits(user(employees)) shouldBe false
  }

  "EffectiveAccess.legacyPrincipals" should "never answer an empty set for a restricted collection" in {
    val path = CollectionPath.unsafe("company/executive")
    EffectiveAccess.legacyPrincipals(path, EffectiveAccess.Public) shouldBe Right(Set.empty)
    EffectiveAccess.legacyPrincipals(
      path,
      EffectiveAccess.of(Seq(coll(1, "company", employees, managers), coll(2, "company/executive", managers)))
    ) shouldBe Right(Set(managers))
    EffectiveAccess
      .legacyPrincipals(
        path,
        EffectiveAccess.of(Seq(coll(1, "company", employees), coll(2, "company/executive", managers)))
      )
      .left
      .toOption
      .get shouldBe a[ValidationError]
    EffectiveAccess.legacyPrincipals(path, EffectiveAccess.Nobody).isLeft shouldBe true
  }

  "CollectionStore.effectiveAccess" should "by default read the collection and each ancestor" in {
    val store = new GetOnlyStore(Seq(coll(1, "hr", hrTeam), coll(2, "hr/salaries")))
    store.effectiveAccess(CollectionPath.unsafe("hr/salaries")).map(_.permits(UserAuthorization.Anonymous)) shouldBe
      Right(false)
    store.effectiveAccess(CollectionPath.unsafe("hr")).map(_.isPublic) shouldBe Right(false)
    store.effectiveAccess(CollectionPath.unsafe("missing/child")).isLeft shouldBe true
  }

  /** A store that implements only `get`, to exercise the trait's defaults. */
  final private class GetOnlyStore(collections: Seq[Collection]) extends CollectionStore {
    private val byPath                                             = collections.map(c => c.path -> c).toMap
    def get(path: CollectionPath): Result[Option[Collection]]      = Right(byPath.get(path))
    def create(config: CollectionConfig): Result[Collection]       = ???
    def getById(id: Int): Result[Option[Collection]]               = ???
    def list(pattern: CollectionPattern): Result[Seq[Collection]]  = ???
    def findAccessible(a: UserAuthorization, p: CollectionPattern) = ???
    def updatePermissions(p: CollectionPath, q: Set[PrincipalId])  = ???
    def updateMetadata(p: CollectionPath, m: Map[String, String])  = ???
    def delete(path: CollectionPath): Result[Unit]                 = ???
    def getEffectivePermissions(path: CollectionPath): Result[Set[PrincipalId]]  = ???
    def canQuery(path: CollectionPath, auth: UserAuthorization): Result[Boolean] = ???
    def listChildren(parentPath: CollectionPath): Result[Seq[Collection]]        = ???
    def countDocuments(path: CollectionPath): Result[Long]                       = ???
    def countChunks(path: CollectionPath): Result[Long]                          = ???
    def stats(path: CollectionPath): Result[CollectionStats]                     = ???
    def ensureExists(config: CollectionConfig): Result[Collection]               = ???
  }
}
