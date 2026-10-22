package org.llm4s.agent.graph

import org.llm4s.annotation.Experimental
import org.llm4s.error.ValidationError
import org.llm4s.types.Result

import java.time.Instant

/**
 * Where a thread is, as the thread registry reports it ([[Checkpointer.threads]], design 4.52). It is derived, when the
 * store is read, from the thread's latest checkpoint and its claim, judged by the store's clock:
 *
 *  - `Deleted`: a cascade delete of the thread has begun ([[Checkpoint.deleting]]) and not finished: the thread refuses
 *    every admission until a `deleteThread` finishes it. A thread `deleteThread` removed is not listed at all: its
 *    tombstone holds nothing of it, not even its tenant.
 *  - `Running`: a live claim holds the thread - a run, or a change such as `updateState`, `fork` or a delete, is under
 *    way on some machine - whatever its latest checkpoint says.
 *  - `Idle`: no live claim holds the thread and its latest checkpoint is incomplete (`CheckpointStatus.Running`): a run
 *    stopped before it finished - it crashed, was cancelled or was drained, which look alike here - and `recover`
 *    would continue it. [[Checkpointer.incompleteThreads]] lists these, oldest first.
 *  - `Suspended`, `Completed`, `Failed`: no live claim holds the thread, and its latest checkpoint has that status.
 */
@Experimental
enum ThreadStatus:
  case Idle, Running, Suspended, Failed, Completed, Deleted

object ThreadStatus:

  /**
   * The status of a thread whose latest checkpoint has `status` and `deleting`, held by a live claim or not. For a
   * store's [[Checkpointer.threads]].
   */
  def of(status: CheckpointStatus, deleting: Boolean, claimed: Boolean): ThreadStatus =
    if deleting then Deleted
    else if claimed then Running
    else
      status match
        case CheckpointStatus.Running   => Idle
        case CheckpointStatus.Suspended => Suspended
        case CheckpointStatus.Completed => Completed
        case CheckpointStatus.Failed    => Failed

/**
 * A thread's metadata: a small map of strings its caller sets, for finding the thread again ([[ThreadQuery.metadata]]),
 * not for content - it is stored as it is, read by operators, and copied into no event or trace. It is set by a
 * `start` whose `RunConfig.metadata` is not empty, and replaced with `GraphRuntime.updateMetadata`; in the store, by a
 * fenced [[Commit.metadata]]. A deleted thread's metadata goes with it.
 *
 * At most [[ThreadMetadata.MaxEntries]] entries; each key is 1 to [[ThreadMetadata.MaxKeyLength]] characters of
 * `A-Z a-z 0-9 _ . : / -`, and each value at most [[ThreadMetadata.MaxValueLength]] characters, none of them a control
 * character.
 */
@Experimental
final case class ThreadMetadata private (entries: Map[String, String]):

  /** Whether this holds every entry of `filter`: the metadata equality [[ThreadQuery]] filters by. */
  def matches(filter: ThreadMetadata): Boolean = filter.entries.forall((k, v) => entries.get(k).contains(v))

  def isEmpty: Boolean = entries.isEmpty

  /** As a JSON object, key by key: how a store keeps it. */
  def toJson: ujson.Obj = ujson.Obj.from(entries.toSeq.sortBy(_._1).map((k, v) => k -> ujson.Str(v)))

object ThreadMetadata:
  val MaxEntries: Int     = 32
  val MaxKeyLength: Int   = 64
  val MaxValueLength: Int = 256

  val empty: ThreadMetadata = new ThreadMetadata(Map.empty)

  private val KeyPattern = "[A-Za-z0-9_.:/-]+".r

  /** The metadata `entries`; an `IllegalArgumentException` naming every problem if they break a limit. */
  def apply(entries: Map[String, String]): ThreadMetadata =
    val found = problems(entries)
    require(found.isEmpty, found.mkString("; "))
    new ThreadMetadata(entries)

  def apply(entries: (String, String)*): ThreadMetadata = apply(entries.toMap)

  /** The metadata `entries`, or a `ValidationError` naming every limit they break. */
  def of(entries: Map[String, String]): Result[ThreadMetadata] =
    problems(entries) match
      case Nil   => Right(new ThreadMetadata(entries))
      case found => Left(ValidationError("metadata", found))

  /** Metadata a store kept as a JSON object ([[ThreadMetadata.toJson]]), checked again as it is read. */
  def fromJson(json: ujson.Value): Result[ThreadMetadata] =
    scala.util
      .Try(json.obj.iterator.map((k, v) => k -> v.str).toMap)
      .toOption
      .toRight(ValidationError("metadata", "is not a JSON object of strings"))
      .flatMap(of)

  private def problems(entries: Map[String, String]): List[String] =
    val size = Option.when(entries.size > MaxEntries)(s"has ${entries.size} entries, more than $MaxEntries").toList
    size ++ entries.toList.sortBy(_._1).flatMap { (key, value) =>
      val shownKey = key.take(MaxKeyLength)
      Option
        .when(key.isEmpty || key.length > MaxKeyLength || !KeyPattern.matches(key))(
          s"key '$shownKey' is not 1 to $MaxKeyLength characters of A-Z a-z 0-9 _ . : / -"
        )
        .toList ++
        Option
          .when(value.length > MaxValueLength || value.exists(_.isControl))(
            s"the value of '$shownKey' is longer than $MaxValueLength characters or holds a control character"
          )
          .toList
    }

/**
 * Where a page of the thread registry ended: the last thread's [[ThreadSummary.updatedAt]] and id, which the next page
 * starts after ([[ThreadQuery.after]], [[Checkpointer.incompleteThreads]]). It names a position, not a thread, so it
 * stays valid when that thread changes or is deleted.
 */
@Experimental
final case class ThreadCursor(updatedAt: Instant, threadId: ThreadId)

/**
 * A search of the thread registry ([[Checkpointer.threads]]): the root threads of one tenant - `None` and `Some` differ,
 * as for claims: `None` finds only threads of no tenant - narrowed by every filter given, newest first by
 * [[ThreadSummary.updatedAt]], at most `limit` at a time (1 to [[ThreadQuery.MaxLimit]]), starting after `after`.
 *
 *  - `status`: only threads of that [[ThreadStatus]];
 *  - `agent`: only threads whose latest checkpoint is of that graph (`GraphSnapshot.graphId`; an `Agent`'s is its id);
 *  - `metadata`: only threads whose metadata holds every one of these entries ([[ThreadMetadata.matches]]);
 *  - `updatedSince`: only threads the store changed at or after this instant, by its clock.
 */
@Experimental
final case class ThreadQuery private (
  tenantId: Option[TenantId],
  status: Option[ThreadStatus],
  agent: Option[String],
  metadata: ThreadMetadata,
  updatedSince: Option[Instant],
  after: Option[ThreadCursor],
  limit: Int
):
  def withTenantId(t: TenantId): ThreadQuery               = copy(tenantId = Some(t))
  def withTenantId(t: Option[TenantId]): ThreadQuery       = copy(tenantId = t)
  def withStatus(s: ThreadStatus): ThreadQuery             = copy(status = Some(s))
  def withStatus(s: Option[ThreadStatus]): ThreadQuery     = copy(status = s)
  def withAgent(a: String): ThreadQuery                    = copy(agent = Some(a))
  def withAgent(a: Option[String]): ThreadQuery            = copy(agent = a)
  def withMetadata(m: ThreadMetadata): ThreadQuery         = copy(metadata = m)
  def withUpdatedSince(at: Instant): ThreadQuery           = copy(updatedSince = Some(at))
  def withUpdatedSince(at: Option[Instant]): ThreadQuery   = copy(updatedSince = at)
  def withAfter(cursor: ThreadCursor): ThreadQuery         = copy(after = Some(cursor))
  def withAfter(cursor: Option[ThreadCursor]): ThreadQuery = copy(after = cursor)
  def withLimit(n: Int): ThreadQuery                       = ThreadQuery.checked(copy(limit = n))

  /** Whether `thread` passes every filter but the page's position and size. */
  def matches(thread: ThreadSummary): Boolean =
    thread.tenantId == tenantId && status.forall(_ == thread.status) && agent.forall(_ == thread.agent) &&
      thread.metadata.matches(metadata) && updatedSince.forall(at => !thread.updatedAt.isBefore(at))

object ThreadQuery:

  /** The most threads one page holds, so every call stays within the store's bound. */
  val MaxLimit: Int = 500

  val DefaultLimit: Int = 50

  /** A search of `tenantId`'s root threads; an `IllegalArgumentException` if `limit` is not 1 to [[MaxLimit]]. */
  def apply(
    tenantId: Option[TenantId],
    status: Option[ThreadStatus] = None,
    agent: Option[String] = None,
    metadata: ThreadMetadata = ThreadMetadata.empty,
    updatedSince: Option[Instant] = None,
    after: Option[ThreadCursor] = None,
    limit: Int = DefaultLimit
  ): ThreadQuery = checked(new ThreadQuery(tenantId, status, agent, metadata, updatedSince, after, limit))

  /** A page size a registry call accepts - 1 to [[MaxLimit]] - or a `ValidationError`. */
  def checkLimit(limit: Int): Result[Int] =
    Either.cond(limit >= 1 && limit <= MaxLimit, limit, ValidationError("limit", s"must be 1 to $MaxLimit, was $limit"))

  private def checked(query: ThreadQuery): ThreadQuery =
    checkLimit(query.limit).left.foreach(e => throw new IllegalArgumentException(e.message))
    query

/**
 * One thread as the registry lists it: its id and tenant, its agent (the graph id of its latest checkpoint), its
 * [[ThreadStatus]], its latest checkpoint's id, its metadata, and `updatedAt` - when the store last committed a
 * checkpoint or metadata to it, by the store's clock (never a producer's timestamp).
 */
@Experimental
final case class ThreadSummary private (
  threadId: ThreadId,
  tenantId: Option[TenantId],
  agent: String,
  status: ThreadStatus,
  checkpointId: String,
  metadata: ThreadMetadata,
  updatedAt: Instant
):
  def withStatus(s: ThreadStatus): ThreadSummary     = copy(status = s)
  def withMetadata(m: ThreadMetadata): ThreadSummary = copy(metadata = m)
  def withUpdatedAt(at: Instant): ThreadSummary      = copy(updatedAt = at)

  /** Where a page ending at this thread ended. */
  def cursor: ThreadCursor = ThreadCursor(updatedAt, threadId)

object ThreadSummary:
  def apply(
    threadId: ThreadId,
    tenantId: Option[TenantId],
    agent: String,
    status: ThreadStatus,
    checkpointId: String,
    metadata: ThreadMetadata,
    updatedAt: Instant
  ): ThreadSummary = new ThreadSummary(threadId, tenantId, agent, status, checkpointId, metadata, updatedAt)

/**
 * A page of the thread registry and where the next one starts: `next` is the last thread's cursor when more threads
 * match, and `None` on the last page.
 */
@Experimental
final case class ThreadPage(threads: Vector[ThreadSummary], next: Option[ThreadCursor])

object ThreadPage:

  /**
   * The page of `limit` from `found`, which a store read with one row more than the page holds: the extra row only
   * says that another page follows.
   */
  def of(found: Vector[ThreadSummary], limit: Int): ThreadPage =
    val page = found.take(limit)
    ThreadPage(page, Option.when(found.size > limit)(page.last.cursor))
