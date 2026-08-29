package org.llm4s.rag.permissions

import org.llm4s.error.ValidationError
import org.llm4s.types.Result

/**
 * Core types for permission-based RAG filtering.
 *
 * This module provides type-safe wrappers for:
 * - Principal IDs (users and groups mapped to integers)
 * - Collection paths with hierarchical structure
 * - Collection patterns for query filtering
 * - User authorization context
 */

/**
 * Type-safe wrapper for principal IDs.
 *
 * Uses integer representation for efficient database queries:
 * - Positive integers represent users
 * - Negative integers represent groups
 * - Zero is reserved (not used)
 *
 * @param value The integer ID (positive=user, negative=group)
 */
final case class PrincipalId(value: Int) extends AnyVal {

  /** True if this represents a user (positive ID) */
  def isUser: Boolean = value > 0

  /** True if this represents a group (negative ID) */
  def isGroup: Boolean = value < 0

  override def toString: String =
    if (isUser) s"User($value)"
    else if (isGroup) s"Group(${-value})"
    else "PrincipalId(0)"
}

object PrincipalId {

  /** Create a user principal ID (ensures positive) */
  def user(id: Int): PrincipalId = {
    require(id > 0, s"User ID must be positive, got: $id")
    PrincipalId(id)
  }

  /** Create a group principal ID (ensures negative) */
  def group(id: Int): PrincipalId = {
    require(id > 0, s"Group ID must be positive (will be negated), got: $id")
    PrincipalId(-id)
  }

  /** Create from raw value (positive=user, negative=group) */
  def fromRaw(value: Int): Result[PrincipalId] =
    if (value == 0) Left(ValidationError("principalId", "Principal ID cannot be zero"))
    else Right(PrincipalId(value))
}

/**
 * External principal identifier before resolution to internal ID.
 *
 * External IDs are human-readable identifiers like email addresses
 * or group names that get mapped to integer IDs for efficient storage.
 */
sealed trait ExternalPrincipal {

  /** The full external identifier string (e.g., "user:john@example.com") */
  def externalId: String

  /** The type-specific value (e.g., "john@example.com" or "admins") */
  def value: String
}

object ExternalPrincipal {

  /** A user identified by email or username */
  final case class User(email: String) extends ExternalPrincipal {
    def externalId: String = s"user:$email"
    def value: String      = email
  }

  /** A group identified by name */
  final case class Group(name: String) extends ExternalPrincipal {
    def externalId: String = s"group:$name"
    def value: String      = name
  }

  /** Parse an external ID string into an ExternalPrincipal */
  def parse(s: String): Result[ExternalPrincipal] = s.split(":", 2) match {
    case Array("user", email) if email.nonEmpty => Right(User(email))
    case Array("group", name) if name.nonEmpty  => Right(Group(name))
    case Array("user", _)  => Left(ValidationError("externalPrincipal", "User email cannot be empty"))
    case Array("group", _) => Left(ValidationError("externalPrincipal", "Group name cannot be empty"))
    case _ =>
      Left(ValidationError("externalPrincipal", s"Invalid principal format: $s (expected 'user:...' or 'group:...')"))
  }
}

/**
 * A validated collection path in the hierarchy.
 *
 * Collection paths use forward-slash separators (e.g., "confluence/EN/archive").
 * Each segment must contain only alphanumeric characters, underscores, and hyphens.
 *
 * @param segments The path segments (e.g., Seq("confluence", "EN", "archive"))
 */
final case class CollectionPath private (segments: Seq[String]) {

  /** The full path string (e.g., "confluence/EN/archive") */
  def value: String = segments.mkString("/")

  /** The parent path, if this is not a root collection */
  def parent: Option[CollectionPath] =
    if (segments.length > 1) Some(CollectionPath(segments.init))
    else None

  /** True if this is a root collection (no parent) */
  def isRoot: Boolean = segments.length == 1

  /** The depth of this path (1 for root, 2 for first-level child, etc.) */
  def depth: Int = segments.length

  /** The final segment of the path (the collection name) */
  def name: String = segments.last

  /** Check if this path is a direct child of the given parent */
  def isChildOf(parent: CollectionPath): Boolean =
    this.parent.contains(parent)

  /** Check if this path is a descendant of the given prefix */
  def isDescendantOf(prefix: CollectionPath): Boolean =
    segments.length > prefix.segments.length &&
      segments.take(prefix.segments.length) == prefix.segments

  /** Append a child segment to create a new path */
  def child(name: String): Result[CollectionPath] =
    CollectionPath.create(s"${this.value}/$name")

  override def toString: String = value
}

object CollectionPath {

  private val ValidSegment = """^[a-zA-Z0-9_-]+$""".r

  /**
   * Create a validated collection path from a string.
   *
   * @param path The path string (e.g., "confluence/EN")
   * @return Right(CollectionPath) if valid, Left(error) if invalid
   */
  def create(path: String): Result[CollectionPath] = {
    val segments = path.split("/").toSeq.filter(_.nonEmpty)
    if (segments.isEmpty) {
      Left(ValidationError("collectionPath", "Path cannot be empty"))
    } else if (!segments.forall(s => ValidSegment.matches(s))) {
      Left(
        ValidationError(
          "collectionPath",
          s"Invalid characters in path '$path'. Use only alphanumeric, underscore, and hyphen."
        )
      )
    } else {
      Right(CollectionPath(segments))
    }
  }

  /**
   * Create a collection path without validation (use with caution).
   * Only for internal use where the path is known to be valid.
   */
  def unsafe(path: String): CollectionPath =
    CollectionPath(path.split("/").toSeq.filter(_.nonEmpty))

  /** Create a root-level collection path */
  def root(name: String): Result[CollectionPath] = create(name)
}

/**
 * A pattern for matching collections in queries.
 *
 * Patterns support:
 * - Exact matching: `confluence/EN`
 * - Immediate children: `confluence/★` (matches `confluence/EN` but not `confluence/EN/archive`)
 * - All descendants: `confluence/★★` (matches all paths starting with `confluence/`)
 * - All collections: `★`
 *
 * Note: In code, use asterisk (*) instead of ★ shown in docs.
 */
sealed trait CollectionPattern {

  /** Check if a collection path matches this pattern */
  def matches(path: CollectionPath): Boolean

  /** Convert to a human-readable string representation */
  def patternString: String
}

object CollectionPattern {

  /** Match all collections */
  case object All extends CollectionPattern {
    def matches(path: CollectionPath): Boolean = true
    def patternString: String                  = "*"
  }

  /** Match a specific collection by exact path */
  final case class Exact(path: CollectionPath) extends CollectionPattern {
    def matches(p: CollectionPath): Boolean = p == path
    def patternString: String               = path.value
  }

  // Match immediate children of a parent (e.g., foo/STAR matches foo/bar but not foo/bar/baz)
  final case class ImmediateChildren(parent: CollectionPath) extends CollectionPattern {
    def matches(p: CollectionPath): Boolean =
      p.isChildOf(parent)
    def patternString: String = s"${parent.value}/*"
  }

  // Match all descendants of a prefix (e.g., foo/STAR-STAR matches foo/bar and foo/bar/baz)
  final case class AllDescendants(prefix: CollectionPath) extends CollectionPattern {
    def matches(p: CollectionPath): Boolean =
      p == prefix || p.isDescendantOf(prefix)
    def patternString: String = s"${prefix.value}/**"
  }

  /**
   * Parse a pattern string into a CollectionPattern.
   *
   * Pattern syntax (where STAR means asterisk):
   *  - STAR → All
   *  - foo → Exact(foo)
   *  - foo/STAR → ImmediateChildren(foo)
   *  - foo/STAR-STAR → AllDescendants(foo)
   *
   * @param pattern The pattern string to parse
   * @return Right(CollectionPattern) if valid, Left(error) if invalid
   */
  def parse(pattern: String): Result[CollectionPattern] = pattern.trim match {
    case "*"                    => Right(All)
    case p if p.endsWith("/**") => CollectionPath.create(p.dropRight(3)).map(AllDescendants.apply)
    case p if p.endsWith("/*")  => CollectionPath.create(p.dropRight(2)).map(ImmediateChildren.apply)
    case p                      => CollectionPath.create(p).map(Exact.apply)
  }
}

/**
 * User authorization context for permission-filtered queries.
 *
 * Contains the set of principal IDs (user + groups) that the current
 * user belongs to, which is used to filter collections and documents.
 *
 * @param principalIds The set of principal IDs for this user (includes user ID and all group IDs)
 * @param isAdmin True if this user has admin privileges (bypasses permission checks)
 */
final case class UserAuthorization(
  principalIds: Set[PrincipalId],
  isAdmin: Boolean = false
) {

  /** Get the principal IDs as a raw integer array (for SQL queries) */
  def asArray: Array[Int] = principalIds.map(_.value).toArray

  /** Get the principal IDs as a sequence (for SQL binding) */
  def asSeq: Seq[Int] = principalIds.map(_.value).toSeq

  /** Check if this authorization includes a specific principal */
  def includes(principalId: PrincipalId): Boolean = principalIds.contains(principalId)

  /** Add a principal to this authorization */
  def withPrincipal(principalId: PrincipalId): UserAuthorization =
    copy(principalIds = principalIds + principalId)

  /** Add multiple principals to this authorization */
  def withPrincipals(ids: Set[PrincipalId]): UserAuthorization =
    copy(principalIds = principalIds ++ ids)
}

object UserAuthorization {

  /** Anonymous user with no permissions */
  val Anonymous: UserAuthorization = UserAuthorization(Set.empty)

  /** Admin user that bypasses all permission checks */
  val Admin: UserAuthorization = UserAuthorization(Set.empty, isAdmin = true)

  /** Create authorization for a user with optional group memberships */
  def forUser(userId: PrincipalId, groups: Set[PrincipalId] = Set.empty): UserAuthorization = {
    require(userId.isUser, s"Expected user ID, got: $userId")
    require(groups.forall(_.isGroup), "All group IDs must be negative")
    UserAuthorization(groups + userId)
  }

  /** Create authorization from raw integer IDs */
  def fromRawIds(ids: Seq[Int]): Result[UserAuthorization] = {
    val results = ids.map(PrincipalId.fromRaw)
    val errors  = results.collect { case Left(e) => e }
    if (errors.nonEmpty) {
      Left(errors.head)
    } else {
      Right(UserAuthorization(results.collect { case Right(id) => id }.toSet))
    }
  }
}

/**
 * A chunk with its embedding ready for indexing.
 *
 * @param content The text content of the chunk
 * @param embedding The vector embedding
 * @param chunkIndex The index of this chunk within the document
 * @param metadata Additional metadata for this chunk
 */
final case class ChunkWithEmbedding(
  content: String,
  embedding: Array[Float],
  chunkIndex: Int,
  metadata: Map[String, String] = Map.empty
) {

  /** Get embedding dimensionality */
  def dimensions: Int = embedding.length

  override def equals(other: Any): Boolean = other match {
    case that: ChunkWithEmbedding =>
      content == that.content &&
      java.util.Arrays.equals(embedding, that.embedding) &&
      chunkIndex == that.chunkIndex &&
      metadata == that.metadata
    case _ => false
  }

  override def hashCode(): Int = {
    val state = Seq(content, java.util.Arrays.hashCode(embedding), chunkIndex, metadata)
    state.map(_.hashCode()).foldLeft(0)((a, b) => 31 * a + b)
  }
}

/**
 * Statistics for a collection.
 *
 * @param documentCount Number of unique documents in the collection
 * @param chunkCount Total number of chunks across all documents
 * @param subCollectionCount Number of direct sub-collections
 */
final case class CollectionStats(
  documentCount: Long,
  chunkCount: Long,
  subCollectionCount: Int
) {
  def isEmpty: Boolean = documentCount == 0 && chunkCount == 0
}
