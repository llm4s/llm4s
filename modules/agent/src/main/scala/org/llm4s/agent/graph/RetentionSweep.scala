package org.llm4s.agent.graph

import org.llm4s.annotation.Experimental
import org.llm4s.error.{ CancelledError, LLMError, ValidationError }
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import scala.concurrent.duration.FiniteDuration

/**
 * What a store-wide retention sweep ([[Checkpointer.sweep]], design 4.53) does to each thread it visits: it prunes the
 * thread's history by `retention`, exactly as [[Checkpointer.prune]] does, and - when `tombstoneAge` is set - reclaims
 * the tombstone of a deleted thread once the store deleted it more than `tombstoneAge` ago, by the store's clock; and -
 * when `threadAge` is set - deletes a settled top-level thread (its latest checkpoint `Completed` or `Failed`, no live
 * claim, no child threads, nothing the guard protects) that the store has not updated for `threadAge`: it claims the
 * thread, checks it is still the one it judged, and deletes it under that claim, so a run that starts meanwhile is
 * never cut (#3260). Without `threadAge` no thread is ever removed: only its history is pruned. At
 * most `batchSize` threads are visited by one call, so every call stays within the store's bound however many
 * threads it holds; a caller pages through the store with the cursor each call returns.
 *
 * `SweepPolicy()` changes nothing: it keeps every checkpoint and event and reclaims no tombstone.
 *
 * `apply` and the `with*` setters throw `IllegalArgumentException` for a non-positive `tombstoneAge` or `threadAge`, or a
 * `batchSize`
 * outside 1 to [[SweepPolicy.MaxBatchSize]]; [[SweepPolicy.of]] returns a `ValidationError`.
 */
@Experimental
final case class SweepPolicy private (
  retention: RetentionPolicy,
  tombstoneAge: Option[FiniteDuration],
  batchSize: Int,
  threadAge: Option[FiniteDuration]
):
  def withRetention(r: RetentionPolicy): SweepPolicy     = SweepPolicy(r, tombstoneAge, batchSize, threadAge)
  def withTombstoneAge(age: FiniteDuration): SweepPolicy = SweepPolicy(retention, Some(age), batchSize, threadAge)
  def withTombstoneAge(age: Option[FiniteDuration]): SweepPolicy = SweepPolicy(retention, age, batchSize, threadAge)
  def withBatchSize(n: Int): SweepPolicy                         = SweepPolicy(retention, tombstoneAge, n, threadAge)
  def withThreadAge(age: FiniteDuration): SweepPolicy = SweepPolicy(retention, tombstoneAge, batchSize, Some(age))
  def withThreadAge(age: Option[FiniteDuration]): SweepPolicy = SweepPolicy(retention, tombstoneAge, batchSize, age)

@Experimental
object SweepPolicy:

  /** The threads one call visits unless a policy says otherwise. */
  val DefaultBatchSize: Int = 100

  /** The most threads one call may visit: a larger page would not stay within a store's bound. */
  val MaxBatchSize: Int = 1000

  private def problems(
    tombstoneAge: Option[FiniteDuration],
    batchSize: Int,
    threadAge: Option[FiniteDuration]
  ): List[String] =
    List(
      tombstoneAge.filter(_.length <= 0).map(a => s"tombstoneAge must be positive, was $a"),
      threadAge.filter(_.length <= 0).map(a => s"threadAge must be positive, was $a"),
      Option.when(batchSize < 1 || batchSize > MaxBatchSize)(
        s"batchSize must be between 1 and $MaxBatchSize, was $batchSize"
      )
    ).flatten

  /** Throws `IllegalArgumentException` for an invalid limit; use [[of]] for untrusted input. */
  def apply(
    retention: RetentionPolicy = RetentionPolicy.keepAll,
    tombstoneAge: Option[FiniteDuration] = None,
    batchSize: Int = DefaultBatchSize,
    threadAge: Option[FiniteDuration] = None
  ): SweepPolicy =
    val found = problems(tombstoneAge, batchSize, threadAge)
    require(found.isEmpty, found.mkString("; "))
    new SweepPolicy(retention, tombstoneAge, batchSize, threadAge)

  def of(
    retention: RetentionPolicy = RetentionPolicy.keepAll,
    tombstoneAge: Option[FiniteDuration] = None,
    batchSize: Int = DefaultBatchSize,
    threadAge: Option[FiniteDuration] = None
  ): Result[SweepPolicy] =
    problems(tombstoneAge, batchSize, threadAge) match
      case Nil   => Right(new SweepPolicy(retention, tombstoneAge, batchSize, threadAge))
      case found => Left(ValidationError("sweep", found))

  /** Changes nothing: [[Checkpointer.sweep]] only visits. */
  val keepAll: SweepPolicy = apply()

/**
 * What a [[Checkpointer.sweep]] must not take from a thread, asked once per page before the page changes: for each
 * thread of `threads` that something outside the store still depends on, the [[RunId]]s whose newest checkpoint on
 * that thread must be kept. A thread in the answer - with an empty set or not - keeps its tombstone; for each `RunId`
 * in its set, the newest checkpoint that run committed is kept, whatever the retention policy, while everything else
 * of the thread is pruned as usual (Stage 5, rule 4: a run queue's open entry names the thread and each attempt's
 * `RunId`, and the newest checkpoint of each is the evidence that the entry's input was admitted). A thread missing
 * from the answer has nothing protected.
 *
 * A `Left` ends the sweep's call with that error, changing nothing of the page. The guard is called outside the
 * store's transactions and locks, so it may read another store, such as a run queue in the same database; it is
 * called with at most [[SweepPolicy.batchSize]] threads.
 */
@Experimental
trait SweepGuard:
  def retained(threads: Vector[ThreadId]): Result[Map[ThreadId, Set[RunId]]]

@Experimental
object SweepGuard:

  /** Nothing is protected. */
  val none: SweepGuard = _ => Right(Map.empty)

  /** The fixed `runs`: each thread named keeps its tombstone and the newest checkpoint of each of its runs. */
  def of(runs: Map[ThreadId, Set[RunId]]): SweepGuard = threads => Right(runs.view.filterKeys(threads.toSet).toMap)

/**
 * One call of [[Checkpointer.sweep]]: how many threads it visited (`examined`), how many tombstones it reclaimed
 * (`reclaimed`), and the cursor of the next page - the last thread visited - when more threads follow, `None` once
 * the sweep has visited the whole store.
 */
@Experimental
final case class SweepPage private (examined: Int, reclaimed: Int, next: Option[ThreadId]):
  def withExamined(n: Int): SweepPage          = SweepPage(n, reclaimed, next)
  def withReclaimed(n: Int): SweepPage         = SweepPage(examined, n, next)
  def withNext(t: ThreadId): SweepPage         = SweepPage(examined, reclaimed, Some(t))
  def withNext(t: Option[ThreadId]): SweepPage = SweepPage(examined, reclaimed, t)

@Experimental
object SweepPage:
  def apply(examined: Int, reclaimed: Int, next: Option[ThreadId]): SweepPage =
    new SweepPage(examined, reclaimed, next)

/**
 * The parts of a sweep every store shares: which checkpoints a protected thread keeps, and which of a page's threads
 * come next. For stores, not callers.
 */
private[graph] object RetentionSweep:

  private val logger = LoggerFactory.getLogger("org.llm4s.agent.graph.RetentionSweep")

  /**
   * The most rows of one thread - checkpoints, or events - one step of a sweep's prune removes, so every step stays
   * well within a store's bound however large the thread's backlog is (#3086).
   */
  val ChunkRows: Int = 1000

  /**
   * What a SQL store's sweep works out once per thread and call (#3086): for the thread whose first event is
   * `firstSeq` - so a plan never outlives a delete and re-creation of its id - the events' floor its prune goes to and
   * the positions of the checkpoints it drops, oldest first, still to remove.
   */
  final case class PrunePlan(firstSeq: Long, floor: Long, dropped: Vector[Long])

  /** What one step of a sweep did to a thread: reclaimed its tombstone, finished it, or left more to do. */
  enum Step[+P]:
    case Reclaimed extends Step[Nothing]
    case Done      extends Step[Nothing]

    /**
     * More to remove; `plan`, what the store worked out for the thread in an earlier step of the same call, saves the
     * next step working it out again. A store never carries it to another call: one can always rebuild it from what it
     * holds.
     */
    case More[P](plan: Option[P]) extends Step[P]

  /**
   * The threads of a page a sweep finished (`examined`), the tombstones it reclaimed, and whether it was cut short by
   * its budget before the rest of the page.
   */
  final case class Visited(examined: Int, reclaimed: Int, cutShort: Boolean):

    /**
     * The page's cursor: the last thread finished when the call was cut short - so the next call, paging after it,
     * starts with the thread the budget stopped, or stopped before - else as [[next]] has it.
     */
    def cursor(page: Vector[ThreadId], batchSize: Int): Option[ThreadId] =
      if cutShort then Some(page(examined - 1)) else next(page, batchSize)

  /**
   * Visits `threads` in order, each by `step` - one bounded transaction of the store's, called again with the plan
   * it returned while it returns [[Step.More]] - so a page always makes progress (#3086): a thread whose step fails
   * is logged and skipped, and the next pass tries it again. The first thread is always finished; once `budget` has
   * passed, the page stops - between threads, or after the step in flight inside a later thread - and its cursor is
   * the last thread it finished, so the next call starts with the one it stopped in. A store's steps leave a thread
   * in a state any later step, sweep or prune finishes from, so nothing carried between calls is lost. Only a
   * `CancelledError`, or any failure once `closed` says the store is closed, ends the call with a `Left`.
   */
  def visit[P](threads: Vector[ThreadId], budget: FiniteDuration, store: String, closed: => Boolean)(
    step: (ThreadId, Option[P]) => Result[Step[P]]
  ): Result[Visited] =
    val deadline           = System.nanoTime() + budget.toNanos
    def late: Boolean      = System.nanoTime() - deadline > 0
    def fatal(e: LLMError) = e.isInstanceOf[CancelledError] || closed
    // Some(reclaimed) once the thread is done or skipped; None if the budget ran out inside it
    @tailrec def steps(threadId: ThreadId, plan: Option[P], first: Boolean): Result[Option[Boolean]] =
      step(threadId, plan) match
        case Left(e) if fatal(e) => Left(e)
        case Left(e) =>
          logger.warn(s"$store: the retention sweep skipped thread ${threadId.value}, whose prune failed: ${e.message}")
          Right(Some(false))
        case Right(Step.Reclaimed)                 => Right(Some(true))
        case Right(Step.Done)                      => Right(Some(false))
        case Right(Step.More(_)) if !first && late => Right(None)
        case Right(Step.More(f))                   => steps(threadId, f, first)
    @tailrec def from(i: Int, reclaimed: Int): Result[Visited] =
      if i == threads.size then Right(Visited(i, reclaimed, cutShort = false))
      else if i > 0 && late then Right(Visited(i, reclaimed, cutShort = true))
      else
        steps(threads(i), None, first = i == 0) match
          case Left(e) => Left(e)
          // the thread it stopped in is not finished: the cursor is the one before it, and the next call resumes it
          case Right(None)        => Right(Visited(i, reclaimed, cutShort = true))
          case Right(Some(true))  => from(i + 1, reclaimed + 1)
          case Right(Some(false)) => from(i + 1, reclaimed)
    from(0, 0)

  /**
   * Where one step of a sweep's prune raises a thread's events' floor: towards `target`, by at most [[ChunkRows]]
   * events - `nth` is the seq of the event [[ChunkRows]] places past the current floor, if there is one.
   */
  def chunkFloor(current: Long, target: Long, nth: Option[Long]): Long =
    math.max(current, nth.fold(target)(math.min(target, _)))

  /**
   * The checkpoints `retention` keeps of a history (oldest first, each with its commit time and the `RunId` that
   * committed it), as [[Checkpointer.prune]] keeps them, then those it does not of which each is the newest checkpoint
   * one of `protectedRuns` committed. The first is what the events' floor follows; the second only adds checkpoints.
   */
  def kept[C](
    history: Vector[C],
    retention: RetentionPolicy,
    cutoff: Option[java.time.Instant],
    committedAt: C => java.time.Instant,
    runId: C => String,
    protectedRuns: Set[RunId]
  ): (Vector[C], Vector[C]) =
    val indexed = history.zipWithIndex
    // the latest checkpoint is always kept, whatever its age
    val (older, latest) = indexed.splitAt(indexed.size - 1)
    val young           = older.filter((c, _) => cutoff.forall(cut => !committedAt(c).isBefore(cut)))
    val byPolicy        = retention.maxCheckpoints.fold(young ++ latest)(n => (young ++ latest).takeRight(n))
    val keptIndexes     = byPolicy.map(_._2).toSet
    val runs            = protectedRuns.map(_.value)
    // the newest checkpoint each protected run committed: the evidence that its input was admitted
    val evidence =
      indexed.filter((c, _) => runs(runId(c))).groupMapReduce((c, _) => runId(c))(_._2)(math.max).values.toSet
    (byPolicy.map(_._1), indexed.collect { case (c, i) if evidence(i) && !keptIndexes(i) => c })

  /**
   * Deletes `threadId` if `policy.threadAge` makes it idle (#3260): a top-level thread whose latest checkpoint is
   * `Completed` or `Failed`, with no child threads, that the store last updated (`updatedAt`, by its clock) before
   * `cutoff`, and that `protectedRuns` does not name. It is claimed under its own tenant - a live run makes the claim
   * `ThreadBusy` and the thread is kept - then read again under the claim and deleted only if its latest checkpoint is
   * still the one judged, fenced by the claim. Whether it was deleted; a failure is logged and the thread kept.
   */
  def removeIdle(
    store: Checkpointer,
    threadId: ThreadId,
    cutoff: java.time.Instant,
    updatedAt: Option[java.time.Instant],
    protectedRuns: Map[ThreadId, Set[RunId]]
  ): Boolean =
    def settled(c: Checkpoint) = c.status == CheckpointStatus.Completed || c.status == CheckpointStatus.Failed
    val judged =
      if protectedRuns.contains(threadId) || !updatedAt.exists(_.isBefore(cutoff)) then Right(None)
      else
        for
          latest <- store.latest(threadId).map(_.map(_.checkpoint).filter(c => settled(c) && c.origin.isEmpty))
          children <- latest.fold[Result[Vector[ChildThread]]](Right(Vector.empty))(_ =>
            store.children(threadId, None, 1)
          )
        yield latest.filter(_ => children.isEmpty)
    val removed = judged.flatMap {
      case None => Right(false)
      case Some(idle) =>
        store.claim(
          threadId,
          ClaimRequest(
            RunId(s"llm4s-sweep-${java.util.UUID.randomUUID()}"),
            idle.tenantId.map(TenantId(_)),
            IdleClaimTtl
          )
        ) match
          case Left(_: GraphError.ThreadBusy) => Right(false)
          case Left(error)                    => Left(error)
          case Right(claim) =>
            val deleted = store.latest(threadId).flatMap {
              case Some(now) if now.checkpoint.id == idle.id =>
                store.deleteThread(threadId, claim.token, None).map(_ => true)
              case _ => Right(false)
            }
            // a deleted thread's claim went with it; one kept is given back
            if !deleted.contains(true) then store.release(threadId, claim.token): Unit
            deleted
    }
    removed.left.foreach(e =>
      logger.warn(s"The retention sweep kept idle thread ${threadId.value}, whose removal failed: ${e.message}")
    )
    removed.getOrElse(false)

  /** How long the claim a sweep deletes an idle thread under is held: the delete is one store call. */
  private val IdleClaimTtl: FiniteDuration = scala.concurrent.duration.FiniteDuration(30, "seconds")

  /** The cursor a page of `visited` threads, read one past `batchSize` to tell whether more follow, hands back. */
  def next(visited: Vector[ThreadId], batchSize: Int): Option[ThreadId] =
    Option.when(visited.size > batchSize)(visited(batchSize - 1))
