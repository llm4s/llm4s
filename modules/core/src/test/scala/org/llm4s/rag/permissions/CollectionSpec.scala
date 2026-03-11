package org.llm4s.rag.permissions

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CollectionSpec extends AnyFlatSpec with Matchers {

  private val rootPath  = CollectionPath.unsafe("root")
  private val childPath = CollectionPath.unsafe("root/child")
  private val deepPath  = CollectionPath.unsafe("root/child/deep")
  private val user1     = PrincipalId.user(1)
  private val user2     = PrincipalId.user(2)
  private val group1    = PrincipalId.group(1)

  // ==========================================================================
  // Collection
  // ==========================================================================

  "Collection" should "identify public collections" in {
    val coll = Collection(1, rootPath, None, Set.empty, isLeaf = true)
    coll.isPublic shouldBe true
  }

  it should "identify restricted collections" in {
    val coll = Collection(1, rootPath, None, Set(user1), isLeaf = true)
    coll.isPublic shouldBe false
  }

  it should "identify root collections" in {
    val coll = Collection(1, rootPath, None, Set.empty, isLeaf = true)
    coll.isRoot shouldBe true
  }

  it should "identify non-root collections" in {
    val coll = Collection(2, childPath, Some(rootPath), Set.empty, isLeaf = true)
    coll.isRoot shouldBe false
  }

  it should "return the collection name" in {
    Collection(1, rootPath, None, Set.empty, isLeaf = true).name shouldBe "root"
    Collection(2, childPath, Some(rootPath), Set.empty, isLeaf = true).name shouldBe "child"
    Collection(3, deepPath, Some(childPath), Set.empty, isLeaf = true).name shouldBe "deep"
  }

  it should "return the collection depth" in {
    Collection(1, rootPath, None, Set.empty, isLeaf = true).depth shouldBe 1
    Collection(2, childPath, Some(rootPath), Set.empty, isLeaf = true).depth shouldBe 2
    Collection(3, deepPath, Some(childPath), Set.empty, isLeaf = true).depth shouldBe 3
  }

  it should "allow query for public collection by anyone" in {
    val coll = Collection(1, rootPath, None, Set.empty, isLeaf = true)
    coll.canQuery(UserAuthorization.Anonymous) shouldBe true
    coll.canQuery(UserAuthorization.forUser(user1, Set.empty)) shouldBe true
  }

  it should "allow query for restricted collection by authorized user" in {
    val coll = Collection(1, rootPath, None, Set(user1, user2), isLeaf = true)
    coll.canQuery(UserAuthorization.forUser(user1, Set.empty)) shouldBe true
    coll.canQuery(UserAuthorization.forUser(user2, Set.empty)) shouldBe true
  }

  it should "deny query for restricted collection by unauthorized user" in {
    val coll  = Collection(1, rootPath, None, Set(user1), isLeaf = true)
    val user3 = PrincipalId.user(3)
    coll.canQuery(UserAuthorization.forUser(user3, Set.empty)) shouldBe false
  }

  it should "allow query for restricted collection by admin" in {
    val coll = Collection(1, rootPath, None, Set(user1), isLeaf = true)
    coll.canQuery(UserAuthorization.Admin) shouldBe true
  }

  it should "deny query for restricted collection by anonymous" in {
    val coll = Collection(1, rootPath, None, Set(user1), isLeaf = true)
    coll.canQuery(UserAuthorization.Anonymous) shouldBe false
  }

  it should "allow query when user has matching group" in {
    val coll = Collection(1, rootPath, None, Set(group1), isLeaf = true)
    coll.canQuery(UserAuthorization.forUser(user1, Set(group1))) shouldBe true
  }

  it should "support metadata" in {
    val coll = Collection(1, rootPath, None, Set.empty, isLeaf = true, Map("type" -> "docs"))
    coll.metadata shouldBe Map("type" -> "docs")
  }

  // ==========================================================================
  // CollectionConfig
  // ==========================================================================

  "CollectionConfig" should "create public leaf by default" in {
    val config = CollectionConfig(rootPath)
    config.isPublic shouldBe true
    config.isLeaf shouldBe true
    config.metadata shouldBe Map.empty
    config.queryableBy shouldBe Set.empty
  }

  it should "compute parentPath from path" in {
    CollectionConfig(rootPath).parentPath shouldBe None
    CollectionConfig(childPath).parentPath shouldBe Some(rootPath)
  }

  it should "accumulate principals with withQueryableBy" in {
    val config = CollectionConfig(rootPath)
      .withQueryableBy(user1)
      .withQueryableBy(user2)
    config.queryableBy shouldBe Set(user1, user2)
    config.isPublic shouldBe false
  }

  it should "add multiple principals at once" in {
    val config = CollectionConfig(rootPath)
      .withQueryableBy(Set(user1, user2, group1))
    config.queryableBy shouldBe Set(user1, user2, group1)
  }

  it should "toggle leaf/parent" in {
    val leaf   = CollectionConfig(rootPath).asLeaf
    val parent = CollectionConfig(rootPath).asParent
    leaf.isLeaf shouldBe true
    parent.isLeaf shouldBe false
    parent.asLeaf.isLeaf shouldBe true
  }

  it should "accumulate metadata" in {
    val config = CollectionConfig(rootPath)
      .withMetadata("key1", "val1")
      .withMetadata("key2", "val2")
    config.metadata shouldBe Map("key1" -> "val1", "key2" -> "val2")
  }

  it should "add multiple metadata entries at once" in {
    val config = CollectionConfig(rootPath)
      .withMetadata(Map("k1" -> "v1", "k2" -> "v2"))
    config.metadata shouldBe Map("k1" -> "v1", "k2" -> "v2")
  }

  it should "override existing metadata key" in {
    val config = CollectionConfig(rootPath)
      .withMetadata("key", "old")
      .withMetadata("key", "new")
    config.metadata shouldBe Map("key" -> "new")
  }

  // ==========================================================================
  // CollectionConfig factories
  // ==========================================================================

  "CollectionConfig.publicLeaf" should "create a public leaf config" in {
    val config = CollectionConfig.publicLeaf(rootPath)
    config.isPublic shouldBe true
    config.isLeaf shouldBe true
  }

  "CollectionConfig.restrictedLeaf" should "create a restricted leaf config" in {
    val config = CollectionConfig.restrictedLeaf(rootPath, Set(user1))
    config.isPublic shouldBe false
    config.isLeaf shouldBe true
    config.queryableBy shouldBe Set(user1)
  }

  "CollectionConfig.publicParent" should "create a public parent config" in {
    val config = CollectionConfig.publicParent(rootPath)
    config.isPublic shouldBe true
    config.isLeaf shouldBe false
  }

  "CollectionConfig.restrictedParent" should "create a restricted parent config" in {
    val config = CollectionConfig.restrictedParent(rootPath, Set(user1, group1))
    config.isPublic shouldBe false
    config.isLeaf shouldBe false
    config.queryableBy shouldBe Set(user1, group1)
  }
}
