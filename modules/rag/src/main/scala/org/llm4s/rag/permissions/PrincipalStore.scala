package org.llm4s.rag.permissions

import org.llm4s.types.Result

/**
 * Storage for principal ID mappings.
 *
 * Maps external identifiers (email addresses, group names) to internal
 * integer IDs that are efficient for database queries.
 *
 * Design:
 * - User IDs are positive integers (auto-incremented from SERIAL)
 * - Group IDs are negative integers (from a separate sequence)
 * - External IDs are prefixed with type: "user:john@example.com", "group:admins"
 */
trait PrincipalStore {

  /**
   * Get or create a principal ID for an external identifier.
   *
   * If the external ID already exists, returns the existing ID.
   * Otherwise, creates a new ID (positive for users, negative for groups).
   *
   * @param external The external principal identifier
   * @return The internal principal ID
   */
  def getOrCreate(external: ExternalPrincipal): Result[PrincipalId]

  /**
   * Batch get or create for multiple principals.
   *
   * More efficient than individual calls for bulk operations.
   *
   * @param externals The external principal identifiers
   * @return Map from external principal to internal ID
   */
  def getOrCreateBatch(externals: Seq[ExternalPrincipal]): Result[Map[ExternalPrincipal, PrincipalId]]

  /**
   * Lookup principal by external ID without creating.
   *
   * @param external The external principal identifier
   * @return The internal ID if found, None otherwise
   */
  def lookup(external: ExternalPrincipal): Result[Option[PrincipalId]]

  /**
   * Lookup multiple principals by external IDs.
   *
   * Only returns mappings for principals that exist.
   *
   * @param externals The external principal identifiers
   * @return Map from external principal to internal ID (only for existing principals)
   */
  def lookupBatch(externals: Seq[ExternalPrincipal]): Result[Map[ExternalPrincipal, PrincipalId]]

  /**
   * Reverse lookup: get external ID for a principal ID.
   *
   * @param id The internal principal ID
   * @return The external principal if found, None otherwise
   */
  def getExternalId(id: PrincipalId): Result[Option[ExternalPrincipal]]

  /**
   * Delete a principal mapping.
   *
   * Note: This does not remove the principal from any collection permissions
   * or document readable_by lists. Those should be cleaned up separately.
   *
   * @param external The external principal identifier
   * @return Success or error
   */
  def delete(external: ExternalPrincipal): Result[Unit]

  /**
   * List all principals of a given type.
   *
   * @param principalType Either "user" or "group"
   * @param limit Maximum number to return
   * @param offset Number to skip
   * @return List of external principals
   */
  def list(principalType: String, limit: Int = 100, offset: Int = 0): Result[Seq[ExternalPrincipal]]

  /**
   * Count principals of a given type.
   *
   * @param principalType Either "user" or "group"
   * @return Count of principals
   */
  def count(principalType: String): Result[Long]

  /**
   * Check if a principal exists.
   *
   * @param external The external principal identifier
   * @return True if exists, false otherwise
   */
  def exists(external: ExternalPrincipal): Result[Boolean] =
    lookup(external).map(_.isDefined)
}
