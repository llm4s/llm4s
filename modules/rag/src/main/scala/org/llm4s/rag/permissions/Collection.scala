package org.llm4s.rag.permissions

/**
 * A collection in the search index hierarchy.
 *
 * Collections organize documents and define access permissions.
 * They form a tree structure where:
 * - Parent collections can have their own queryable_by permissions
 * - Sub-collections inherit parent permissions and can add further restrictions
 * - Documents can only be added to leaf collections
 *
 * @param id Database primary key
 * @param path The unique collection path (e.g., "confluence/EN")
 * @param parentPath The parent collection path, if not a root collection
 * @param queryableBy Set of principal IDs that can query this collection (empty = public)
 * @param isLeaf True if this is a leaf collection (can contain documents)
 * @param metadata Optional key-value metadata for the collection
 */
final case class Collection(
  id: Int,
  path: CollectionPath,
  parentPath: Option[CollectionPath],
  queryableBy: Set[PrincipalId],
  isLeaf: Boolean,
  metadata: Map[String, String] = Map.empty
) {

  /** True if this collection is public (queryable by anyone) */
  def isPublic: Boolean = queryableBy.isEmpty

  /** True if this is a root collection (no parent) */
  def isRoot: Boolean = parentPath.isEmpty

  /** The collection name (final path segment) */
  def name: String = path.name

  /** The depth of this collection in the hierarchy (1 for root) */
  def depth: Int = path.depth

  /**
   * Check if a user with the given authorization can query this collection.
   *
   * A user can query if:
   * - The collection is public (empty queryableBy), OR
   * - The user is an admin, OR
   * - The user's principal IDs overlap with the collection's queryableBy set
   */
  def canQuery(auth: UserAuthorization): Boolean =
    isPublic || auth.isAdmin || auth.principalIds.exists(queryableBy.contains)
}

/**
 * Configuration for creating a new collection.
 *
 * @param path The collection path to create
 * @param queryableBy Set of principal IDs that can query this collection (empty = public)
 * @param isLeaf True if this is a leaf collection (can contain documents)
 * @param metadata Optional key-value metadata for the collection
 */
final case class CollectionConfig(
  path: CollectionPath,
  queryableBy: Set[PrincipalId] = Set.empty,
  isLeaf: Boolean = true,
  metadata: Map[String, String] = Map.empty
) {

  /** True if this collection is public (queryable by anyone) */
  def isPublic: Boolean = queryableBy.isEmpty

  /** The parent path, derived from the collection path */
  def parentPath: Option[CollectionPath] = path.parent

  /** Add a principal to the queryableBy set */
  def withQueryableBy(principalId: PrincipalId): CollectionConfig =
    copy(queryableBy = queryableBy + principalId)

  /** Add multiple principals to the queryableBy set */
  def withQueryableBy(principalIds: Set[PrincipalId]): CollectionConfig =
    copy(queryableBy = queryableBy ++ principalIds)

  /** Mark this collection as a leaf (can contain documents) */
  def asLeaf: CollectionConfig = copy(isLeaf = true)

  /** Mark this collection as non-leaf (can only contain sub-collections) */
  def asParent: CollectionConfig = copy(isLeaf = false)

  /** Add metadata to this collection */
  def withMetadata(key: String, value: String): CollectionConfig =
    copy(metadata = metadata + (key -> value))

  /** Add multiple metadata entries */
  def withMetadata(entries: Map[String, String]): CollectionConfig =
    copy(metadata = metadata ++ entries)
}

object CollectionConfig {

  /**
   * Create a public leaf collection configuration.
   *
   * @param path The collection path
   * @return A configuration for a public, leaf collection
   */
  def publicLeaf(path: CollectionPath): CollectionConfig =
    CollectionConfig(path, isLeaf = true)

  /**
   * Create a restricted leaf collection configuration.
   *
   * @param path The collection path
   * @param queryableBy The set of principals that can query this collection
   * @return A configuration for a restricted, leaf collection
   */
  def restrictedLeaf(path: CollectionPath, queryableBy: Set[PrincipalId]): CollectionConfig =
    CollectionConfig(path, queryableBy = queryableBy, isLeaf = true)

  /**
   * Create a public parent collection configuration (cannot contain documents).
   *
   * @param path The collection path
   * @return A configuration for a public, parent collection
   */
  def publicParent(path: CollectionPath): CollectionConfig =
    CollectionConfig(path, isLeaf = false)

  /**
   * Create a restricted parent collection configuration.
   *
   * @param path The collection path
   * @param queryableBy The set of principals that can query this collection
   * @return A configuration for a restricted, parent collection
   */
  def restrictedParent(path: CollectionPath, queryableBy: Set[PrincipalId]): CollectionConfig =
    CollectionConfig(path, queryableBy = queryableBy, isLeaf = false)
}
