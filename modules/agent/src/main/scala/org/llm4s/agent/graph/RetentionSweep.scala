package org.llm4s.agent.graph

import org.llm4s.annotation.Experimental
import org.llm4s.error.ValidationError
import org.llm4s.types.Result

import scala.concurrent.duration.FiniteDuration

/**
 * What a store-wide retention sweep ([[Checkpointer.sweep]], design 4.53) does to each thread it visits: it prunes the
 * thread's history by `retention`, exactly as [[Checkpointer.prune]] does, and - when `tombstoneAge` is set - reclaims
 * the tombstone of a deleted thread once the store deleted it more than `tombstoneAge` ago, by the store's clock. At
 * most `batchSize` threads are visited by one call, so every call stays within the store's bound however many
 * threads it holds; a caller pages through the store with the cursor each call returns.
 *
 * `SweepPolicy()` changes nothing: it keeps every checkpoint and event and reclaims no tombstone.
 *
 * `apply` and the `with*` setters throw `IllegalArgumentException` for a non-positive `tombstoneAge` or a `batchSize`
 * outside 1 to [[SweepPolicy.MaxBatchSize]]; [[SweepPolicy.of]] returns a `ValidationError`.
 */
@Experimental
final case class SweepPolicy private (
  retention: RetentionPolicy,
  tombstoneAge: Option[FiniteDuration],
  batchSize: Int
):
  def withRetention(r: RetentionPolicy): SweepPolicy             = SweepPolicy(r, tombstoneAge, batchSize)
  def withTombstoneAge(age: FiniteDuration): SweepPolicy         = SweepPolicy(retention, Some(age), batchSize)
  def withTombstoneAge(age: Option[FiniteDuration]): SweepPolicy = SweepPolicy(retention, age, batchSize)
  def withBatchSize(n: Int): SweepPolicy                         = SweepPolicy(retention, tombstoneAge, n)

object SweepPolicy:

  /** The threads one call visits unless a policy says otherwise. */
  val DefaultBatchSize: Int = 100

  /** The most threads one call may visit: a larger page would not stay within a store's bound. */
  val MaxBatchSize: Int = 1000

  private def problems(tombstoneAge: Option[FiniteDuration], batchSize: Int): List[String] =
    List(
      tombstoneAge.filter(_.length <= 0).map(a => s"tombstoneAge must be positive, was $a"),
      Option.when(batchSize < 1 || batchSize > MaxBatchSize)(
        s"batchSize must be between 1 and $MaxBatchSize, was $batchSize"
      )
    ).flatten

  /** Throws `IllegalArgumentException` for an invalid limit; use [[of]] for untrusted input. */
  def apply(
    retention: RetentionPolicy = RetentionPolicy.keepAll,
    tombstoneAge: Option[FiniteDuration] = None,
    batchSize: Int = DefaultBatchSize
  ): SweepPolicy =
    val found = problems(tombstoneAge, batchSize)
    require(found.isEmpty, found.mkString("; "))
    new SweepPolicy(retention, tombstoneAge, batchSize)

  def of(
    retention: RetentionPolicy = RetentionPolicy.keepAll,
    tombstoneAge: Option[FiniteDuration] = None,
    batchSize: Int = DefaultBatchSize
  ): Result[SweepPolicy] =
    problems(tombstoneAge, batchSize) match
      case Nil   => Right(new SweepPolicy(retention, tombstoneAge, batchSize))
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

object SweepPage:
  def apply(examined: Int, reclaimed: Int, next: Option[ThreadId]): SweepPage =
    new SweepPage(examined, reclaimed, next)

/**
 * The parts of a sweep every store shares: which checkpoints a protected thread keeps, and which of a page's threads
 * come next. For stores, not callers.
 */
private[graph] object RetentionSweep:

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

  /** The cursor a page of `visited` threads, read one past `batchSize` to tell whether more follow, hands back. */
  def next(visited: Vector[ThreadId], batchSize: Int): Option[ThreadId] =
    Option.when(visited.size > batchSize)(visited(batchSize - 1))
