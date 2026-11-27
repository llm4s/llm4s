package org.llm4s.rag.permissions

import org.llm4s.error.ValidationError
import org.llm4s.types.Result

/**
 * Storage for collections with permission management.
 *
 * Manages the hierarchical collection structure and enforces
 * permission inheritance rules:
 * - A sub-collection inherits every restriction above it: a non-admin user needs a principal at each restricted
 *   level ([[EffectiveAccess]])
 * - A sub-collection of a restricted collection may not be public, but may name any principals; it need not repeat
 *   its parent's
 * - Documents can only be added to leaf collections, and a leaf holding documents cannot be given children
 */
trait CollectionStore {

  /**
   * Create a new collection.
   *
   * Validates that:
   * - The parent exists (if not a root collection)
   * - The collection is not public under a restricted parent
   * - The parent, if it is a leaf, holds no documents: it becomes a non-leaf, and a non-leaf holds none
   * - The path is valid and not already taken
   *
   * Making the parent a non-leaf and creating the collection succeed or fail together.
   *
   * @param config The collection configuration
   * @return The created collection, or error
   */
  def create(config: CollectionConfig): Result[Collection]

  /**
   * Get a collection by path.
   *
   * @param path The collection path
   * @return The collection if found, None otherwise
   */
  def get(path: CollectionPath): Result[Option[Collection]]

  /**
   * Get a collection by its database ID.
   *
   * @param id The collection ID
   * @return The collection if found, None otherwise
   */
  def getById(id: Int): Result[Option[Collection]]

  /**
   * List all collections matching a pattern.
   *
   * Does NOT apply permission filtering - returns all matching collections.
   * Use findAccessible for permission-filtered queries.
   *
   * @param pattern The pattern to match (default: all)
   * @return All matching collections
   */
  def list(pattern: CollectionPattern = CollectionPattern.All): Result[Seq[Collection]]

  /**
   * Find collections accessible by user matching a pattern.
   *
   * This is the key permission-filtered query used during search.
   * Returns only collections where:
   * - Pattern matches, AND
   * - User has access (public OR user's principals overlap with queryableBy)
   *
   * Also respects inheritance - if a parent is not accessible, its
   * children are also not accessible.
   *
   * @param auth The user's authorization context
   * @param pattern The pattern to match
   * @return Accessible collections matching the pattern
   */
  def findAccessible(
    auth: UserAuthorization,
    pattern: CollectionPattern
  ): Result[Seq[Collection]]

  /**
   * Update collection permissions.
   *
   * Validates that the collection is not made public under a restricted parent.
   *
   * @param path The collection path
   * @param queryableBy The new set of queryable principals
   * @return The updated collection, or error
   */
  def updatePermissions(
    path: CollectionPath,
    queryableBy: Set[PrincipalId]
  ): Result[Collection]

  /**
   * Update collection metadata.
   *
   * @param path The collection path
   * @param metadata The new metadata (replaces existing)
   * @return The updated collection, or error
   */
  def updateMetadata(
    path: CollectionPath,
    metadata: Map[String, String]
  ): Result[Collection]

  /**
   * Delete a collection.
   *
   * The collection must be empty (no documents or sub-collections).
   *
   * @param path The collection path
   * @return Success or error
   */
  def delete(path: CollectionPath): Result[Unit]

  /**
   * Who may query a collection, considering every collection above it.
   *
   * The default reads the collection and each ancestor with [[get]]; a store that can read them in one query should
   * override it.
   *
   * @param path The collection path
   * @return The collection's effective access, or an error if it or an ancestor does not exist
   */
  def effectiveAccess(path: CollectionPath): Result[EffectiveAccess] = {
    val chain = Iterator.iterate(Option(path))(_.flatMap(_.parent)).takeWhile(_.isDefined).flatten.toList.reverse
    chain
      .foldLeft[Result[List[Collection]]](Right(Nil)) { (acc, p) =>
        acc.flatMap(found =>
          get(p).flatMap(
            _.toRight(ValidationError("collection", s"Collection not found: ${p.value}"))
              .map(found :+ _)
          )
        )
      }
      .map(EffectiveAccess.of)
  }

  /**
   * Get effective permissions for a collection considering inheritance.
   *
   * An empty set means public. A collection whose levels no single principal satisfies (`company` queryable by
   * `employees`, `company/executive` by `managers`) has no answer of this shape, and returns an error rather than
   * an empty set.
   *
   * @param path The collection path
   * @return The principals any one of which may query the collection, or empty for public
   */
  @deprecated("an empty set reads as public; use effectiveAccess", since = "1.1.0")
  def getEffectivePermissions(path: CollectionPath): Result[Set[PrincipalId]]

  /**
   * Check if a user can query a collection.
   *
   * Applies the rule [[findAccessible]] does, the collection and every ancestor ([[EffectiveAccess]]): a public
   * child of a restricted parent is not public.
   *
   * @param path The collection path
   * @param auth The user's authorization context
   * @return True if the user can query this collection, or an error if it does not exist
   */
  def canQuery(path: CollectionPath, auth: UserAuthorization): Result[Boolean]

  /**
   * List direct children of a collection.
   *
   * @param parentPath The parent collection path
   * @return Direct child collections
   */
  def listChildren(parentPath: CollectionPath): Result[Seq[Collection]]

  /**
   * Count documents in a collection.
   *
   * @param path The collection path
   * @return Number of documents (not chunks)
   */
  def countDocuments(path: CollectionPath): Result[Long]

  /**
   * Count chunks in a collection.
   *
   * @param path The collection path
   * @return Number of chunks
   */
  def countChunks(path: CollectionPath): Result[Long]

  /**
   * Get collection statistics.
   *
   * @param path The collection path
   * @return Collection statistics
   */
  def stats(path: CollectionPath): Result[CollectionStats]

  /**
   * Check if a collection exists.
   *
   * @param path The collection path
   * @return True if exists
   */
  def exists(path: CollectionPath): Result[Boolean] =
    get(path).map(_.isDefined)

  /**
   * Ensure a collection exists, creating it if necessary.
   *
   * Creates parent collections as needed (as public, non-leaf).
   *
   * @param config The collection configuration
   * @return The existing or newly created collection
   */
  def ensureExists(config: CollectionConfig): Result[Collection]
}
