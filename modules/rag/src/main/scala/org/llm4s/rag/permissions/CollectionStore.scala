package org.llm4s.rag.permissions

import org.llm4s.types.Result

/**
 * Storage for collections with permission management.
 *
 * Manages the hierarchical collection structure and enforces
 * permission inheritance rules:
 * - SubCollections inherit parent permissions
 * - SubCollections can only add restrictions (never loosen)
 * - Documents can only be added to leaf collections
 */
trait CollectionStore {

  /**
   * Create a new collection.
   *
   * Validates that:
   * - The parent exists (if not a root collection)
   * - Permissions don't loosen parent restrictions
   * - The path is valid and not already taken
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
   * Validates that new permissions don't loosen parent restrictions.
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
   * Get effective permissions for a collection considering inheritance.
   *
   * Effective permissions are the intersection of:
   * - The collection's own queryableBy, AND
   * - All ancestor collections' queryableBy sets
   *
   * If any ancestor is public (empty queryableBy), that level is skipped.
   * If the collection itself is public, returns empty (meaning public).
   *
   * @param path The collection path
   * @return The effective permissions set
   */
  def getEffectivePermissions(path: CollectionPath): Result[Set[PrincipalId]]

  /**
   * Check if a user can query a collection.
   *
   * This considers the effective permissions (with inheritance).
   *
   * @param path The collection path
   * @param auth The user's authorization context
   * @return True if the user can query this collection
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
