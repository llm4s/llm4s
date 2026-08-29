package org.llm4s.rag.permissions

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PermissionTypesSpec extends AnyFlatSpec with Matchers {

  // PrincipalId tests
  "PrincipalId" should "identify users with positive IDs" in {
    val userId = PrincipalId(123)
    userId.isUser shouldBe true
    userId.isGroup shouldBe false
  }

  it should "identify groups with negative IDs" in {
    val groupId = PrincipalId(-5)
    groupId.isUser shouldBe false
    groupId.isGroup shouldBe true
  }

  it should "create users via factory method" in {
    val userId = PrincipalId.user(42)
    userId.value shouldBe 42
    userId.isUser shouldBe true
  }

  it should "create groups with negative IDs via factory method" in {
    val groupId = PrincipalId.group(10)
    groupId.value shouldBe -10
    groupId.isGroup shouldBe true
  }

  it should "reject zero in fromRaw" in {
    PrincipalId.fromRaw(0).isLeft shouldBe true
  }

  // ExternalPrincipal tests
  "ExternalPrincipal" should "parse user identifiers" in {
    val result = ExternalPrincipal.parse("user:john@example.com")
    result shouldBe Right(ExternalPrincipal.User("john@example.com"))
  }

  it should "parse group identifiers" in {
    val result = ExternalPrincipal.parse("group:admins")
    result shouldBe Right(ExternalPrincipal.Group("admins"))
  }

  it should "generate correct external IDs" in {
    ExternalPrincipal.User("john@example.com").externalId shouldBe "user:john@example.com"
    ExternalPrincipal.Group("admins").externalId shouldBe "group:admins"
  }

  it should "reject invalid format" in {
    ExternalPrincipal.parse("invalid").isLeft shouldBe true
    ExternalPrincipal.parse("user:").isLeft shouldBe true
    ExternalPrincipal.parse("group:").isLeft shouldBe true
  }

  // CollectionPath tests
  "CollectionPath" should "create valid paths" in {
    val result = CollectionPath.create("confluence/EN/archive")
    result.isRight shouldBe true
    result.map(_.value) shouldBe Right("confluence/EN/archive")
  }

  it should "create root paths" in {
    val result = CollectionPath.create("confluence")
    result.isRight shouldBe true
    result.map(_.isRoot) shouldBe Right(true)
  }

  it should "compute parent paths" in {
    val path   = CollectionPath.unsafe("confluence/EN/archive")
    val parent = path.parent
    parent shouldBe defined
    parent.get.value shouldBe "confluence/EN"
    parent.get.parent.map(_.value) shouldBe Some("confluence")
    parent.get.parent.flatMap(_.parent) shouldBe None
  }

  it should "compute depth correctly" in {
    CollectionPath.unsafe("confluence").depth shouldBe 1
    CollectionPath.unsafe("confluence/EN").depth shouldBe 2
    CollectionPath.unsafe("confluence/EN/archive").depth shouldBe 3
  }

  it should "reject empty paths" in {
    CollectionPath.create("").isLeft shouldBe true
    CollectionPath.create("/").isLeft shouldBe true
  }

  it should "reject invalid characters" in {
    CollectionPath.create("my collection").isLeft shouldBe true
    CollectionPath.create("path/with spaces").isLeft shouldBe true
    CollectionPath.create("path.with.dots").isLeft shouldBe true
  }

  it should "detect child relationships" in {
    val parent     = CollectionPath.unsafe("confluence")
    val child      = CollectionPath.unsafe("confluence/EN")
    val grandchild = CollectionPath.unsafe("confluence/EN/archive")

    child.isChildOf(parent) shouldBe true
    grandchild.isChildOf(parent) shouldBe false
    grandchild.isChildOf(child) shouldBe true
  }

  it should "detect descendant relationships" in {
    val ancestor   = CollectionPath.unsafe("confluence")
    val child      = CollectionPath.unsafe("confluence/EN")
    val grandchild = CollectionPath.unsafe("confluence/EN/archive")

    child.isDescendantOf(ancestor) shouldBe true
    grandchild.isDescendantOf(ancestor) shouldBe true
    grandchild.isDescendantOf(child) shouldBe true
    ancestor.isDescendantOf(child) shouldBe false
  }

  // CollectionPattern tests
  "CollectionPattern.All" should "match all collections" in {
    CollectionPattern.All.matches(CollectionPath.unsafe("anything")) shouldBe true
    CollectionPattern.All.matches(CollectionPath.unsafe("a/b/c")) shouldBe true
  }

  "CollectionPattern.Exact" should "match exact paths only" in {
    val pattern = CollectionPattern.Exact(CollectionPath.unsafe("confluence/EN"))
    pattern.matches(CollectionPath.unsafe("confluence/EN")) shouldBe true
    pattern.matches(CollectionPath.unsafe("confluence")) shouldBe false
    pattern.matches(CollectionPath.unsafe("confluence/EN/archive")) shouldBe false
  }

  "CollectionPattern.ImmediateChildren" should "match only direct children" in {
    val pattern = CollectionPattern.ImmediateChildren(CollectionPath.unsafe("confluence"))
    pattern.matches(CollectionPath.unsafe("confluence/EN")) shouldBe true
    pattern.matches(CollectionPath.unsafe("confluence/DE")) shouldBe true
    pattern.matches(CollectionPath.unsafe("confluence")) shouldBe false
    pattern.matches(CollectionPath.unsafe("confluence/EN/archive")) shouldBe false
    pattern.matches(CollectionPath.unsafe("other")) shouldBe false
  }

  "CollectionPattern.AllDescendants" should "match the prefix and all descendants" in {
    val pattern = CollectionPattern.AllDescendants(CollectionPath.unsafe("confluence"))
    pattern.matches(CollectionPath.unsafe("confluence")) shouldBe true
    pattern.matches(CollectionPath.unsafe("confluence/EN")) shouldBe true
    pattern.matches(CollectionPath.unsafe("confluence/EN/archive")) shouldBe true
    pattern.matches(CollectionPath.unsafe("other")) shouldBe false
    pattern.matches(CollectionPath.unsafe("confluence2")) shouldBe false
  }

  "CollectionPattern.parse" should "parse * as All" in {
    CollectionPattern.parse("*") shouldBe Right(CollectionPattern.All)
  }

  it should "parse path/* as ImmediateChildren" in {
    val result = CollectionPattern.parse("confluence/*")
    result.isRight shouldBe true
    result.map(_.patternString) shouldBe Right("confluence/*")
  }

  it should "parse path/** as AllDescendants" in {
    val result = CollectionPattern.parse("confluence/**")
    result.isRight shouldBe true
    result.map(_.patternString) shouldBe Right("confluence/**")
  }

  it should "parse plain path as Exact" in {
    val result = CollectionPattern.parse("confluence/EN")
    result.isRight shouldBe true
    result.map(_.patternString) shouldBe Right("confluence/EN")
  }

  // UserAuthorization tests
  "UserAuthorization" should "check principal inclusion" in {
    val auth = UserAuthorization(Set(PrincipalId(1), PrincipalId(-5)))
    auth.includes(PrincipalId(1)) shouldBe true
    auth.includes(PrincipalId(-5)) shouldBe true
    auth.includes(PrincipalId(2)) shouldBe false
  }

  it should "convert to array for SQL" in {
    val auth = UserAuthorization(Set(PrincipalId(1), PrincipalId(-5)))
    auth.asArray.sorted shouldBe Array(-5, 1)
  }

  "UserAuthorization.forUser" should "create authorization with user and groups" in {
    val auth = UserAuthorization.forUser(
      PrincipalId.user(42),
      Set(PrincipalId.group(1), PrincipalId.group(2))
    )
    auth.principalIds shouldBe Set(PrincipalId(42), PrincipalId(-1), PrincipalId(-2))
  }

  "UserAuthorization.Anonymous" should "have no principals" in {
    UserAuthorization.Anonymous.principalIds shouldBe empty
    UserAuthorization.Anonymous.isAdmin shouldBe false
  }

  "UserAuthorization.Admin" should "be an admin" in {
    UserAuthorization.Admin.isAdmin shouldBe true
  }

  // ChunkWithEmbedding tests
  "ChunkWithEmbedding" should "compute dimensions" in {
    val chunk = ChunkWithEmbedding("content", Array(0.1f, 0.2f, 0.3f), 0)
    chunk.dimensions shouldBe 3
  }

  it should "implement equals correctly" in {
    val chunk1 = ChunkWithEmbedding("content", Array(0.1f, 0.2f), 0)
    val chunk2 = ChunkWithEmbedding("content", Array(0.1f, 0.2f), 0)
    val chunk3 = ChunkWithEmbedding("different", Array(0.1f, 0.2f), 0)

    chunk1 shouldBe chunk2
    chunk1 should not be chunk3
  }

  it should "implement hashCode correctly" in {
    val chunk1 = ChunkWithEmbedding("content", Array(0.1f, 0.2f), 0)
    val chunk2 = ChunkWithEmbedding("content", Array(0.1f, 0.2f), 0)

    chunk1.hashCode() shouldBe chunk2.hashCode()
  }

  it should "not equal non-ChunkWithEmbedding objects" in {
    val chunk = ChunkWithEmbedding("content", Array(0.1f, 0.2f), 0)
    chunk.equals("not a chunk") shouldBe false
    chunk.equals(null) shouldBe false
  }

  it should "support metadata" in {
    val chunk = ChunkWithEmbedding("content", Array(0.1f), 0, Map("key" -> "value"))
    chunk.metadata shouldBe Map("key" -> "value")
  }

  // Additional PrincipalId tests
  "PrincipalId.toString" should "format user IDs correctly" in {
    PrincipalId(123).toString shouldBe "User(123)"
  }

  it should "format group IDs correctly" in {
    PrincipalId(-5).toString shouldBe "Group(5)"
  }

  it should "format zero ID correctly" in {
    PrincipalId(0).toString shouldBe "PrincipalId(0)"
  }

  "PrincipalId.user" should "reject non-positive IDs" in {
    an[IllegalArgumentException] should be thrownBy PrincipalId.user(0)
    an[IllegalArgumentException] should be thrownBy PrincipalId.user(-1)
  }

  "PrincipalId.group" should "reject non-positive IDs" in {
    an[IllegalArgumentException] should be thrownBy PrincipalId.group(0)
    an[IllegalArgumentException] should be thrownBy PrincipalId.group(-1)
  }

  "PrincipalId.fromRaw" should "accept positive values as users" in {
    val result = PrincipalId.fromRaw(42)
    result shouldBe Right(PrincipalId(42))
    result.map(_.isUser) shouldBe Right(true)
  }

  it should "accept negative values as groups" in {
    val result = PrincipalId.fromRaw(-10)
    result shouldBe Right(PrincipalId(-10))
    result.map(_.isGroup) shouldBe Right(true)
  }

  // Additional ExternalPrincipal tests
  "ExternalPrincipal.User" should "expose value correctly" in {
    val user = ExternalPrincipal.User("john@example.com")
    user.value shouldBe "john@example.com"
  }

  "ExternalPrincipal.Group" should "expose value correctly" in {
    val group = ExternalPrincipal.Group("admins")
    group.value shouldBe "admins"
  }

  // Additional CollectionPath tests
  "CollectionPath.name" should "return the last segment" in {
    CollectionPath.unsafe("confluence/EN/archive").name shouldBe "archive"
    CollectionPath.unsafe("confluence").name shouldBe "confluence"
  }

  "CollectionPath.child" should "create a valid child path" in {
    val parent = CollectionPath.unsafe("confluence")
    val result = parent.child("EN")

    result.isRight shouldBe true
    result.map(_.value) shouldBe Right("confluence/EN")
  }

  it should "reject invalid child names" in {
    val parent = CollectionPath.unsafe("confluence")
    val result = parent.child("invalid name")

    result.isLeft shouldBe true
  }

  "CollectionPath.root" should "create a root collection" in {
    val result = CollectionPath.root("myroot")
    result.isRight shouldBe true
    result.map(_.isRoot) shouldBe Right(true)
    result.map(_.value) shouldBe Right("myroot")
  }

  it should "reject invalid root names" in {
    CollectionPath.root("invalid name").isLeft shouldBe true
  }

  "CollectionPath.value" should "return the full path string" in {
    CollectionPath.unsafe("a/b/c").value shouldBe "a/b/c"
  }

  // Additional UserAuthorization tests
  "UserAuthorization.asSeq" should "return principal IDs as sequence" in {
    val auth = UserAuthorization(Set(PrincipalId(1), PrincipalId(-5), PrincipalId(3)))
    auth.asSeq.sorted shouldBe Seq(-5, 1, 3)
  }

  "UserAuthorization.withPrincipal" should "add a principal" in {
    val auth    = UserAuthorization(Set(PrincipalId(1)))
    val updated = auth.withPrincipal(PrincipalId(-5))

    updated.principalIds shouldBe Set(PrincipalId(1), PrincipalId(-5))
  }

  "UserAuthorization.withPrincipals" should "add multiple principals" in {
    val auth    = UserAuthorization(Set(PrincipalId(1)))
    val updated = auth.withPrincipals(Set(PrincipalId(-5), PrincipalId(-10)))

    updated.principalIds shouldBe Set(PrincipalId(1), PrincipalId(-5), PrincipalId(-10))
  }

  "UserAuthorization.fromRawIds" should "create authorization from valid IDs" in {
    val result = UserAuthorization.fromRawIds(Seq(1, -5, 3))
    result.isRight shouldBe true
    result.map(_.principalIds) shouldBe Right(Set(PrincipalId(1), PrincipalId(-5), PrincipalId(3)))
  }

  it should "fail if any ID is zero" in {
    val result = UserAuthorization.fromRawIds(Seq(1, 0, 3))
    result.isLeft shouldBe true
  }

  it should "handle empty sequence" in {
    val result = UserAuthorization.fromRawIds(Seq.empty)
    result.isRight shouldBe true
    result.map(_.principalIds) shouldBe Right(Set.empty)
  }

  "UserAuthorization.forUser" should "reject group IDs as user ID" in {
    an[IllegalArgumentException] should be thrownBy {
      UserAuthorization.forUser(PrincipalId(-1))
    }
  }

  it should "reject user IDs in groups set" in {
    an[IllegalArgumentException] should be thrownBy {
      UserAuthorization.forUser(PrincipalId(1), Set(PrincipalId(2)))
    }
  }

  // CollectionStats tests
  "CollectionStats" should "report isEmpty correctly" in {
    // isEmpty checks documentCount and chunkCount only, not subCollectionCount
    CollectionStats(0, 0, 0).isEmpty shouldBe true
    CollectionStats(1, 0, 0).isEmpty shouldBe false
    CollectionStats(0, 1, 0).isEmpty shouldBe false
    // subCollectionCount doesn't affect isEmpty
    CollectionStats(0, 0, 5).isEmpty shouldBe true
    CollectionStats(1, 1, 5).isEmpty shouldBe false
  }

  // CollectionPattern additional tests
  "CollectionPattern.All.patternString" should "return *" in {
    CollectionPattern.All.patternString shouldBe "*"
  }

  "CollectionPattern.Exact.patternString" should "return the path" in {
    CollectionPattern.Exact(CollectionPath.unsafe("a/b")).patternString shouldBe "a/b"
  }

  "CollectionPattern.ImmediateChildren.patternString" should "return path/*" in {
    CollectionPattern.ImmediateChildren(CollectionPath.unsafe("parent")).patternString shouldBe "parent/*"
  }

  "CollectionPattern.AllDescendants.patternString" should "return path/**" in {
    CollectionPattern.AllDescendants(CollectionPath.unsafe("prefix")).patternString shouldBe "prefix/**"
  }

  // ==========================================================================
  // Collection tests
  // ==========================================================================

  "Collection" should "report isPublic correctly" in {
    val publicCollection = Collection(
      id = 1,
      path = CollectionPath.unsafe("test"),
      parentPath = None,
      queryableBy = Set.empty,
      isLeaf = true
    )
    publicCollection.isPublic shouldBe true

    val restrictedCollection = Collection(
      id = 2,
      path = CollectionPath.unsafe("restricted"),
      parentPath = None,
      queryableBy = Set(PrincipalId(1)),
      isLeaf = true
    )
    restrictedCollection.isPublic shouldBe false
  }

  it should "report isRoot correctly" in {
    val rootCollection = Collection(
      id = 1,
      path = CollectionPath.unsafe("root"),
      parentPath = None,
      queryableBy = Set.empty,
      isLeaf = true
    )
    rootCollection.isRoot shouldBe true

    val childCollection = Collection(
      id = 2,
      path = CollectionPath.unsafe("root/child"),
      parentPath = Some(CollectionPath.unsafe("root")),
      queryableBy = Set.empty,
      isLeaf = true
    )
    childCollection.isRoot shouldBe false
  }

  it should "return correct name" in {
    val collection = Collection(
      id = 1,
      path = CollectionPath.unsafe("parent/child/grandchild"),
      parentPath = Some(CollectionPath.unsafe("parent/child")),
      queryableBy = Set.empty,
      isLeaf = true
    )
    collection.name shouldBe "grandchild"
  }

  it should "return correct depth" in {
    val rootCollection = Collection(
      id = 1,
      path = CollectionPath.unsafe("root"),
      parentPath = None,
      queryableBy = Set.empty,
      isLeaf = true
    )
    rootCollection.depth shouldBe 1

    val deepCollection = Collection(
      id = 2,
      path = CollectionPath.unsafe("a/b/c"),
      parentPath = Some(CollectionPath.unsafe("a/b")),
      queryableBy = Set.empty,
      isLeaf = true
    )
    deepCollection.depth shouldBe 3
  }

  it should "allow query for public collections" in {
    val publicCollection = Collection(
      id = 1,
      path = CollectionPath.unsafe("public"),
      parentPath = None,
      queryableBy = Set.empty,
      isLeaf = true
    )
    publicCollection.canQuery(UserAuthorization.Anonymous) shouldBe true
    publicCollection.canQuery(UserAuthorization(Set(PrincipalId(1)))) shouldBe true
    publicCollection.canQuery(UserAuthorization.Admin) shouldBe true
  }

  it should "allow query for admin on restricted collections" in {
    val restrictedCollection = Collection(
      id = 1,
      path = CollectionPath.unsafe("restricted"),
      parentPath = None,
      queryableBy = Set(PrincipalId(1), PrincipalId(2)),
      isLeaf = true
    )
    restrictedCollection.canQuery(UserAuthorization.Admin) shouldBe true
  }

  it should "allow query for authorized users on restricted collections" in {
    val restrictedCollection = Collection(
      id = 1,
      path = CollectionPath.unsafe("restricted"),
      parentPath = None,
      queryableBy = Set(PrincipalId(1), PrincipalId(2)),
      isLeaf = true
    )
    restrictedCollection.canQuery(UserAuthorization(Set(PrincipalId(1)))) shouldBe true
    restrictedCollection.canQuery(UserAuthorization(Set(PrincipalId(2)))) shouldBe true
    restrictedCollection.canQuery(UserAuthorization(Set(PrincipalId(1), PrincipalId(3)))) shouldBe true
  }

  it should "deny query for unauthorized users on restricted collections" in {
    val restrictedCollection = Collection(
      id = 1,
      path = CollectionPath.unsafe("restricted"),
      parentPath = None,
      queryableBy = Set(PrincipalId(1), PrincipalId(2)),
      isLeaf = true
    )
    restrictedCollection.canQuery(UserAuthorization.Anonymous) shouldBe false
    restrictedCollection.canQuery(UserAuthorization(Set(PrincipalId(3)))) shouldBe false
  }

  it should "support metadata" in {
    val collection = Collection(
      id = 1,
      path = CollectionPath.unsafe("test"),
      parentPath = None,
      queryableBy = Set.empty,
      isLeaf = true,
      metadata = Map("key1" -> "value1", "key2" -> "value2")
    )
    collection.metadata shouldBe Map("key1" -> "value1", "key2" -> "value2")
  }

  // ==========================================================================
  // CollectionConfig tests
  // ==========================================================================

  "CollectionConfig" should "report isPublic correctly" in {
    val publicConfig = CollectionConfig(CollectionPath.unsafe("test"))
    publicConfig.isPublic shouldBe true

    val restrictedConfig = CollectionConfig(
      CollectionPath.unsafe("test"),
      queryableBy = Set(PrincipalId(1))
    )
    restrictedConfig.isPublic shouldBe false
  }

  it should "compute parentPath correctly" in {
    val rootConfig = CollectionConfig(CollectionPath.unsafe("root"))
    rootConfig.parentPath shouldBe None

    val childConfig = CollectionConfig(CollectionPath.unsafe("parent/child"))
    childConfig.parentPath shouldBe Some(CollectionPath.unsafe("parent"))

    val deepConfig = CollectionConfig(CollectionPath.unsafe("a/b/c"))
    deepConfig.parentPath shouldBe Some(CollectionPath.unsafe("a/b"))
  }

  it should "add single principal with withQueryableBy" in {
    val config  = CollectionConfig(CollectionPath.unsafe("test"))
    val updated = config.withQueryableBy(PrincipalId(1))

    updated.queryableBy shouldBe Set(PrincipalId(1))
    updated.isPublic shouldBe false
  }

  it should "add multiple principals with withQueryableBy" in {
    val config  = CollectionConfig(CollectionPath.unsafe("test"))
    val updated = config.withQueryableBy(Set(PrincipalId(1), PrincipalId(2)))

    updated.queryableBy shouldBe Set(PrincipalId(1), PrincipalId(2))
  }

  it should "accumulate principals across multiple withQueryableBy calls" in {
    val config = CollectionConfig(CollectionPath.unsafe("test"))
      .withQueryableBy(PrincipalId(1))
      .withQueryableBy(Set(PrincipalId(2), PrincipalId(3)))

    config.queryableBy shouldBe Set(PrincipalId(1), PrincipalId(2), PrincipalId(3))
  }

  it should "convert to leaf with asLeaf" in {
    val config = CollectionConfig(CollectionPath.unsafe("test"), isLeaf = false)
    config.isLeaf shouldBe false

    val leafConfig = config.asLeaf
    leafConfig.isLeaf shouldBe true
  }

  it should "convert to parent with asParent" in {
    val config = CollectionConfig(CollectionPath.unsafe("test"), isLeaf = true)
    config.isLeaf shouldBe true

    val parentConfig = config.asParent
    parentConfig.isLeaf shouldBe false
  }

  it should "add single metadata entry with withMetadata" in {
    val config  = CollectionConfig(CollectionPath.unsafe("test"))
    val updated = config.withMetadata("key", "value")

    updated.metadata shouldBe Map("key" -> "value")
  }

  it should "add multiple metadata entries with withMetadata" in {
    val config  = CollectionConfig(CollectionPath.unsafe("test"))
    val updated = config.withMetadata(Map("key1" -> "value1", "key2" -> "value2"))

    updated.metadata shouldBe Map("key1" -> "value1", "key2" -> "value2")
  }

  it should "accumulate metadata across multiple withMetadata calls" in {
    val config = CollectionConfig(CollectionPath.unsafe("test"))
      .withMetadata("key1", "value1")
      .withMetadata(Map("key2" -> "value2", "key3" -> "value3"))

    config.metadata shouldBe Map("key1" -> "value1", "key2" -> "value2", "key3" -> "value3")
  }

  // ==========================================================================
  // CollectionConfig companion object tests
  // ==========================================================================

  "CollectionConfig.publicLeaf" should "create a public leaf collection" in {
    val config = CollectionConfig.publicLeaf(CollectionPath.unsafe("test"))

    config.isPublic shouldBe true
    config.isLeaf shouldBe true
    config.queryableBy shouldBe Set.empty
    config.metadata shouldBe Map.empty
  }

  "CollectionConfig.restrictedLeaf" should "create a restricted leaf collection" in {
    val principals = Set(PrincipalId(1), PrincipalId(2))
    val config     = CollectionConfig.restrictedLeaf(CollectionPath.unsafe("test"), principals)

    config.isPublic shouldBe false
    config.isLeaf shouldBe true
    config.queryableBy shouldBe principals
  }

  "CollectionConfig.publicParent" should "create a public parent collection" in {
    val config = CollectionConfig.publicParent(CollectionPath.unsafe("test"))

    config.isPublic shouldBe true
    config.isLeaf shouldBe false
    config.queryableBy shouldBe Set.empty
  }

  "CollectionConfig.restrictedParent" should "create a restricted parent collection" in {
    val principals = Set(PrincipalId(1), PrincipalId(-5))
    val config     = CollectionConfig.restrictedParent(CollectionPath.unsafe("test"), principals)

    config.isPublic shouldBe false
    config.isLeaf shouldBe false
    config.queryableBy shouldBe principals
  }
}
