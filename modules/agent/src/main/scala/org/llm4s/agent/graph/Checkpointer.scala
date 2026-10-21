package org.llm4s.agent.graph

import org.llm4s.error.{ CancelledError, ValidationError }
import org.llm4s.types.{ Result, TryOps }

import java.time.{ Clock, Instant }
import java.util.concurrent.TimeUnit
import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

/**
 * Durable storage for graph threads: each thread's checkpoint history, the pending writes recorded
 * against its latest checkpoint, its durable event log, and the claim of the run that holds it. One
 * store owns all of them so that a commit is atomic and fenced. Several [[org.llm4s.agent.graph.GraphRuntime GraphRuntime]]s, in one
 * process or many, may share a store: the claim is what keeps two of them from running one thread at
 * once.
 *
 * Implementations must be safe to call from several threads and processes, and must:
 *
 *  - grant at most one live claim per thread ([[claim]]): a claim is live until its `expiresAt`, by
 *    the store's own clock, has passed; a request while one is live is refused with
 *    [[org.llm4s.agent.graph.GraphError.ThreadBusy GraphError.ThreadBusy]] naming its holder, and an expired claim is replaced - taken over;
 *  - issue each claim a [[org.llm4s.agent.graph.FencingToken FencingToken]] strictly greater than every token issued for that thread
 *    before - across releases, takeovers and [[deleteThread]] - so a token is never reused;
 *  - apply a [[org.llm4s.agent.graph.Commit Commit]] entirely or not at all, and only if its token is the thread's current claim
 *    ([[org.llm4s.agent.graph.GraphError.StaleClaim GraphError.StaleClaim]] otherwise, checked first) - so a writer that lost its claim, even
 *    one whose token is the highest it has seen, records nothing after another run took over - and
 *    fence [[deleteThread]] by its token in the same way, atomically with the delete; a commit's
 *    [[Commit.fence]] and a delete's `fence` are another thread's claim, checked in that same atomic step;
 *  - accept a new checkpoint only when its `parent` is the thread's latest checkpoint id - the
 *    thread version the writer expects ([[org.llm4s.agent.graph.GraphError.CheckpointConflict GraphError.CheckpointConflict]] otherwise) - and its id is
 *    not already in the thread's history, and only pending writes naming the resulting latest
 *    checkpoint ([[org.llm4s.agent.graph.GraphError.InvalidCommit GraphError.InvalidCommit]]);
 *  - keep every checkpoint a commit superseded in the thread's history, newest first in [[history]]
 *    and readable by id with [[checkpoint]], until [[prune]], a commit's `retractTurn` or
 *    [[deleteThread]] removes it - but keep pending writes for the latest checkpoint only;
 *  - number a thread's events consecutively in commit order, inside the commit, never reusing a
 *    number - including after compaction, pruning, a failed commit or [[deleteThread]]: a thread's
 *    first event is `1` unless a thread of the same id that numbered events was deleted, and then it
 *    is one past the deleted thread's next number `n` - `n` itself is skipped - so a new thread of the
 *    id numbers on past every event of the old one;
 *  - record on each event the token of the commit that stored it ([[org.llm4s.agent.graph.EventRecord.token EventRecord.token]],
 *    [[org.llm4s.agent.graph.EventRecord.committed EventRecord.committed]]), so a run's events can be told from those of an earlier run that had
 *    the same `RunId`;
 *  - refuse a read of the log after a number of a deleted thread of the id - from `1` up to the
 *    skipped `n`, exclusive ([[org.llm4s.agent.graph.Checkpointer.deleted Checkpointer.deleted]]) - with [[org.llm4s.agent.graph.GraphError.ThreadDeleted GraphError.ThreadDeleted]],
 *    from [[eventsAfter]] and [[awaitEventsAfter]] alike, so a reader that was following the deleted
 *    thread never takes the new one for its continuation; a read from `0` reads the current thread
 *    from its first event, and a watch waiting in [[awaitEventsAfter]] from inside a log that is
 *    deleted gets that refusal within about its `timeout` (from its next call, for the polling default);
 *  - keep events until [[compactEvents]] or [[prune]] removes them, and report the earliest sequence
 *    still available when asked to replay from before it ([[org.llm4s.agent.graph.GraphError.ReplayUnavailable GraphError.ReplayUnavailable]]);
 *  - keep a thread's effect records ([[org.llm4s.agent.graph.EffectRecord EffectRecord]]), upserted by key by a commit's `effects` and
 *    remembered with the task they belong to (`taskId`, `lineage`), until a commit with a checkpoint releases
 *    them ([[org.llm4s.agent.graph.Commit.releasesEffect Commit.releasesEffect]], judged before that commit's own records are applied) or
 *    [[deleteThread]] removes them - never [[prune]], never by age - and copy none of them anywhere;
 *    [[effect]] reads one;
 *  - store checkpoints, pending writes and effect records as data ([[org.llm4s.agent.graph.Checkpoint.toJson Checkpoint.toJson]], `PendingWrite`'s
 *    `ReadWriter`, [[org.llm4s.agent.graph.EffectRecord.toJson EffectRecord.toJson]]), so nothing executable survives a round trip;
 *  - keep what it stores apart from the values its callers hold: `ujson` values are mutable, so changing
 *    a checkpoint, pending write or event payload after committing it, or one a read or a commit
 *    returned, must change nothing stored or later read back;
 *  - return from [[awaitEventsAfter]] with what [[eventsAfter]] would, at once if the log already
 *    holds an event after `afterSeq`, and otherwise within about `timeout` - with the events of a
 *    commit made meanwhile through any store over the same storage, or empty;
 *  - tolerate the thread calling [[eventsAfter]] or [[awaitEventsAfter]] being interrupted at any
 *    point: cancelling a subscription interrupts its watch mid-read, so an interrupt must end that
 *    one call - with `Left(CancelledError)` or a failed result - and leave the store usable by every
 *    other thread and later call (see [[awaitEventsAfter]]);
 *  - list a thread's direct children ([[children]]) - the threads whose latest checkpoint's [[RunOrigin]] names it
 *    as `parent` - in the order they were first committed, paged, and never one [[deleteThread]] removed;
 *  - keep each thread's [[ThreadMetadata]], replaced only by a commit carrying [[Commit.metadata]] and removed only by
 *    [[deleteThread]], and list a tenant's root threads ([[threads]]) and its incomplete ones ([[incompleteThreads]]) -
 *    the thread registry (design 4.52) - by their latest checkpoint, their claim judged by the store's clock, and when
 *    the store last committed a checkpoint or metadata to them, by that clock, never one of another tenant, a child
 *    thread, one with no checkpoint, or one [[deleteThread]] removed;
 *  - answer [[latestOfRuns]] - the newest checkpoint any of a set of runs committed on a thread, the run-id lookup
 *    a run queue's entry classifies its attempts by (Stage 5 rule 4, design 4.55) - by an index on the run, and keep
 *    the checkpoint it answers with through [[prune]] while an open entry of the store's run queue names that run;
 *  - return from every call within a bound it documents (#1919) - see below.
 *
 * '''Every call returns within a bound.''' A store times out its own calls: it documents a bound, set by its
 * configuration (such as `SqliteCheckpointerConfig.busyTimeout`), within which every call returns, whatever its
 * storage is doing - waiting for a lock, for another connection's or process's write, for the store's own earlier
 * calls, or for a network - and a call that reaches the bound fails, changing nothing. [[awaitEventsAfter]] returns
 * within its `timeout` plus that bound. A store that cannot bound some call - a disk that stalls inside a write, say,
 * which no driver can time out - documents which call, and why. Keep the bound well inside `ClaimPolicy.ttl -
 * ClaimPolicy.renewEvery`: a renewal must land within that, and a store that serialises its calls queues a renewal
 * behind a slow commit.
 *
 * A [[org.llm4s.agent.graph.GraphRuntime GraphRuntime]] bounds what it can without the store: a run's result, its slot in the runtime and its claim are
 * freed within twice `ClaimPolicy.ttl` of a lost claim being found ([[org.llm4s.agent.graph.GraphError.ClaimLost GraphError.ClaimLost]]), and of a closing
 * commit - an `Async` run's drain, or an `OnExit` run's exit commit - sticking while the claim holds
 * ([[org.llm4s.agent.graph.GraphError.CommitTimedOut GraphError.CommitTimedOut]]). Every other call made while a claim holds - an admission's claim commit, a `Sync`
 * run's commits, its outcome's included, a change's write, and the release of a claim that was not lost - is waited
 * for as long as it takes, and only the store's bound limits it. Nor can the runtime free what a call stuck in the
 * store holds itself, so these last as long as the call does:
 *
 *  - '''the calling thread.''' A run thread, or the virtual thread an admission, `fork`, `updateState` or
 *    `deleteThread` makes its store calls on, stays alive inside the call, and an `Async` run's writer thread with
 *    it, after the runtime has given the call up;
 *  - '''the thread's later commits.''' Commits on one thread are written one at a time in a runtime, so a commit -
 *    or a subscription's read of events another runtime committed, which a commit's hand-over makes under the same
 *    lock - holds up every later commit on that thread in that runtime, a new run's claim commit included, until it
 *    returns; never another thread's;
 *  - '''subscriptions behind a commit.''' A `GraphRuntime.subscribe` made while a run's claim commit is in the store,
 *    on a thread with nothing of the run handed over yet - a claim commit its admission gave up on after a lost claim
 *    included - waits for that commit to return, or for the subscription to be cancelled, before it delivers its
 *    replay; a run-scoped subscription never does. And with [[org.llm4s.agent.graph.WatchPolicy.disabled WatchPolicy.disabled]], a replay made while a write a
 *    run gave up on is still in the store stops at the first event another runtime committed above what this runtime
 *    has handed over; it receives the rest when this runtime next commits on the thread, and a replay made once the
 *    write has returned has them all.
 *
 * The holder of a claim may commit and [[renew]] it after `expiresAt` for as long as no other claim
 * has replaced it: expiry only lets another run take the thread over. `CheckpointerContract` in
 * `llm4s-agent-testkit` is the executable form of this contract; a store should pass it.
 */
trait Checkpointer:

  /**
   * Claims `threadId` for `request.holder`, recording `request.tenantId` with the claim, until
   * `request.ttl` from now, by the store's clock, if no other claim on it is live, and returns the claim
   * with a new [[org.llm4s.agent.graph.FencingToken FencingToken]]. A live claim - whoever holds it, the same holder included - is
   * `Left(GraphError.ThreadBusy(threadId, latest, holder))` with the thread's latest checkpoint id and
   * nothing changed. `holder` is `GraphError.BusyHolder.Run(holder)` only when the live claim's tenant
   * is `request.tenantId` (`None` and `Some` differ), and [[org.llm4s.agent.graph.GraphError.BusyHolder.Unnamed GraphError.BusyHolder.Unnamed]] otherwise -
   * also for a claim that recorded no tenant, such as one persisted before claims carried one - so that
   * a refusal never reveals another tenant's [[org.llm4s.agent.graph.RunId RunId]]; a store never returns
   * [[org.llm4s.agent.graph.GraphError.BusyHolder.Lapsed GraphError.BusyHolder.Lapsed]]. A thread need not have a checkpoint to be claimed. A claim that
   * replaces an expired one nobody released names that claim's holder as [[org.llm4s.agent.graph.RunClaim.takenOverFrom RunClaim.takenOverFrom]],
   * under the same rule: only if it was of `request.tenantId`, and `None` otherwise.
   */
  def claim(threadId: ThreadId, request: ClaimRequest): Result[RunClaim]

  /**
   * Extends the claim whose token is `token` to `ttl` from now, by the store's clock, and returns it.
   * `Left(GraphError.StaleClaim)` if `token` is not the thread's current claim (another run took it
   * over, it was released, or the thread was deleted). An expired claim nobody replaced is renewed.
   */
  def renew(threadId: ThreadId, token: FencingToken, ttl: FiniteDuration): Result[RunClaim]

  /**
   * Ends the claim whose token is `token`, so the thread can be claimed again at once. A token that
   * is not the thread's current claim changes nothing and is `Right(())`: a run that lost its claim
   * must never release its successor's.
   */
  def release(threadId: ThreadId, token: FencingToken): Result[Unit]

  /**
   * Applies `commit` atomically, fenced by its token, and returns its events with their sequence numbers,
   * each recording that token. A `Commit.fence` of `(other, otherToken)` fences it by `other`'s claim too, checked
   * in the same transaction or under the same lock as the write, after the thread's own claim: otherwise
   * `Left(GraphError.StaleClaim(other, otherToken, otherCurrent))` with nothing written.
   */
  def commit(threadId: ThreadId, commit: Commit): Result[Vector[EventRecord]]

  /** The thread's latest checkpoint and its pending writes; `None` for a new thread. */
  def latest(threadId: ThreadId): Result[Option[StoredCheckpoint]]

  /**
   * The thread's effect record under `key` ([[org.llm4s.agent.graph.ReplayGuard ReplayGuard]], design §4.24); `None` if it has none, or
   * the thread is unknown. Needs no claim.
   */
  def effect(threadId: ThreadId, key: EffectKey): Result[Option[EffectRecord]]

  /**
   * Up to `limit` of the thread's checkpoints, newest first - the latest is the first of the first
   * page - starting after checkpoint `before` when it is given, so a caller pages through the history
   * by passing the last id of each page. A `before` that is not in the history is
   * `Left(GraphError.CheckpointNotFound)`; a thread with no checkpoint has an empty history.
   */
  def history(threadId: ThreadId, before: Option[String], limit: Int): Result[Vector[Checkpoint]]

  /** Checkpoint `checkpointId` of the thread's history, latest included; `None` if it is not there. */
  def checkpoint(threadId: ThreadId, checkpointId: String): Result[Option[Checkpoint]]

  /**
   * Removes the checkpoints and events of the thread that `policy` does not keep (see
   * [[org.llm4s.agent.graph.RetentionPolicy RetentionPolicy]]), by the store's clock: `maxAge` is judged against the time the store
   * committed each checkpoint and event, by that same clock, never against the producer's `Checkpoint.createdAt` or
   * `EventRecord.timestamp`, so a writer whose clock is skewed makes nothing too old or too young. The latest checkpoint and its pending writes are always
   * kept. Pruning a checkpoint also removes the events before the oldest checkpoint kept; `maxAge` and
   * `maxEvents` may remove more, including the latest checkpoint's. Removing events raises the replay
   * floor as [[compactEvents]] does. Needs no claim: it touches nothing a run reads or writes. An unknown thread
   * is `Right(())`.
   */
  def prune(threadId: ThreadId, policy: RetentionPolicy): Result[Unit]

  /**
   * Up to `limit` events with `seq > afterSeq`, ascending. `afterSeq` `0` reads from the thread's first
   * event, as does the number just before it; one behind the replay floor is
   * [[org.llm4s.agent.graph.GraphError.ReplayUnavailable GraphError.ReplayUnavailable]], and one inside the log of a deleted thread of the id
   * ([[org.llm4s.agent.graph.Checkpointer.deleted Checkpointer.deleted]]) is [[org.llm4s.agent.graph.GraphError.ThreadDeleted GraphError.ThreadDeleted]].
   */
  def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int): Result[Vector[EventRecord]]

  /**
   * [[eventsAfter]], waiting for a commit when there is nothing to return yet: up to `limit` events
   * with `seq > afterSeq`, ascending, at once if the log holds any; otherwise those of a commit that
   * lands within `timeout`, or an empty `Vector` once it has passed. It is how a subscription learns
   * of commits made through another [[org.llm4s.agent.graph.GraphRuntime GraphRuntime]] or process sharing the store (see
   * [[org.llm4s.agent.graph.WatchPolicy WatchPolicy]]): each subscription calls it in a loop, from the last event it has queued.
   *
   * The default polls. It reads once and, finding nothing, waits `timeout` and returns empty, so the
   * caller's next call reads again: a commit is seen up to `timeout` after it lands, at the cost of
   * one [[eventsAfter]] per `timeout` per subscription. A store that can be told of commits overrides
   * it to return as soon as one lands, without reading meanwhile - [[org.llm4s.agent.graph.InMemoryCheckpointer InMemoryCheckpointer]] wakes on
   * its own commits, and a database store can use its notification channel (PostgreSQL's
   * `LISTEN`/`NOTIFY`). Such a store must wake for commits made through every store over the same
   * storage, not only its own, and still return within about `timeout` when none lands.
   *
   * Errors are [[eventsAfter]]'s - [[org.llm4s.agent.graph.GraphError.ReplayUnavailable GraphError.ReplayUnavailable]] when `afterSeq` is behind the
   * replay floor. Interrupting the waiting thread ends the wait with `Left(CancelledError)`, the
   * thread's interrupt flag set again.
   *
   * '''The reading thread may be interrupted at any point.''' A subscription's watch calls this
   * on a thread of its own, and [[org.llm4s.agent.graph.Subscription.cancel Subscription.cancel]] - or the subscription's end - interrupts that
   * thread whether it is waiting or in the middle of a read, so the interrupt can land inside the
   * store's own I/O ([[eventsAfter]] included). A store must take that as the end of this one call,
   * never as damage to itself: release what the call held (a pooled connection, a lock), and stay
   * usable by other threads and later calls. Beware drivers whose I/O goes through a
   * `java.nio.channels.InterruptibleChannel` (a `FileChannel` or `SocketChannel`): an interrupt
   * during a read or write closes the channel, with `ClosedByInterruptException`, so a store that
   * shares one channel - one connection - between threads would lose it for all of them. Such a
   * store reads on a connection of its own for the waiting call, or discards and replaces a
   * connection an interrupt has closed.
   */
  def awaitEventsAfter(
    threadId: ThreadId,
    afterSeq: Long,
    limit: Int,
    timeout: FiniteDuration
  ): Result[Vector[EventRecord]] =
    eventsAfter(threadId, afterSeq, limit).flatMap { found =>
      if found.nonEmpty then Right(found)
      else
        CancelledError.catchInterrupt(TimeUnit.NANOSECONDS.sleep(timeout.toNanos)) match
          case Right(_)          => Right(Vector.empty)
          case Left(interrupted) => Checkpointer.waitCancelled(threadId, interrupted)
    }

  /**
   * Up to `limit` of `parent`'s direct children (design 4.41), oldest first: every thread whose latest checkpoint's
   * `Checkpoint.origin` names `parent`, in the order each first committed a checkpoint with that origin, starting
   * after child `after` when it is given, so a caller pages through them by passing the last id of each page. A
   * thread [[deleteThread]] removed is not listed, nor is a thread whose latest checkpoint names another parent or
   * none. An `after` the store does not know as a child of `parent` is `Left(ValidationError)`; a child deleted
   * since it was listed keeps its place as long as its tombstone does ([[deleteThread]]), so a page boundary survives
   * retention deleting it. A thread with no children has none; `limit` below 1 reads none. Needs no claim, and reads no tenant: the runtime
   * checks the parent's tenant (`GraphRuntime.children`).
   */
  def children(parent: ThreadId, after: Option[ThreadId], limit: Int): Result[Vector[ChildThread]]

  /**
   * The thread registry (design 4.52): a page of the root threads of `query.tenantId` that pass every filter of `query`
   * ([[org.llm4s.agent.graph.ThreadQuery!.matches ThreadQuery.matches]]), newest first by [[org.llm4s.agent.graph.ThreadSummary!.updatedAt ThreadSummary.updatedAt]] - when the store last committed a checkpoint
   * or metadata to the thread, by its clock - ties broken by thread id, descending; starting after `query.after`, at
   * most `query.limit` of them, with the cursor of the next page when more match ([[org.llm4s.agent.graph.ThreadPage.of ThreadPage.of]]). Each thread's
   * [[org.llm4s.agent.graph.ThreadStatus ThreadStatus]] is judged from its latest checkpoint and whether a live claim holds it, by the store's clock, when
   * it is read.
   *
   * Listed are only threads with a checkpoint whose latest checkpoint's tenant is `query.tenantId` (`None` and `Some`
   * differ) and whose latest checkpoint names no parent ([[org.llm4s.agent.graph.Checkpoint!.origin Checkpoint.origin]]): a child thread is read through
   * [[children]], and a thread [[deleteThread]] removed is never listed. Paging is by position: a thread changed while
   * a caller pages moves to the front, so a later page of that pass does not list it again, and a pass from the start,
   * or with `updatedSince`, finds it. Needs no claim, and reads no other tenant's thread: the tenant is the query's.
   * A store answers it within its bound however many threads it holds, by an index.
   */
  def threads(query: ThreadQuery): Result[ThreadPage]

  /**
   * The incomplete root threads of `tenantId` (design 4.52): those whose latest checkpoint is incomplete
   * (`CheckpointStatus.Running`) and that no live claim holds, by the store's clock - [[org.llm4s.agent.graph.ThreadStatus.Idle ThreadStatus.Idle]] - oldest
   * first by [[org.llm4s.agent.graph.ThreadSummary!.updatedAt ThreadSummary.updatedAt]] (ties by thread id, ascending), starting after `after`, at most `limit` (1 to
   * [[org.llm4s.agent.graph.ThreadQuery.MaxLimit ThreadQuery.MaxLimit]], `ValidationError` otherwise), with the next page's cursor when more match. A thread whose
   * claim expired without a release is listed; one whose cascade delete has begun is not ([[org.llm4s.agent.graph.ThreadStatus.Deleted ThreadStatus.Deleted]]).
   *
   * It is for operators, not a worker's recovery query: a cancelled run, a drained one and a crashed one all look alike
   * here, and only a run queue's entry says which should run again (Stage 5, rule 4). Tenant-scoped and bounded as
   * [[threads]] is.
   */
  def incompleteThreads(tenantId: Option[TenantId], after: Option[ThreadCursor], limit: Int): Result[ThreadPage]

  /**
   * The run-id lookup (Stage 5 rule 4, design 4.55): the newest checkpoint in `threadId`'s history whose
   * [[org.llm4s.agent.graph.Checkpoint!.runId Checkpoint.runId]] is one of `runIds`, or `None` if none of them committed one that is still kept -
   * none of them was admitted, or the thread was deleted since. A run queue's attempt asks it with the
   * [[org.llm4s.agent.graph.RunId RunId]]s its entry recorded, before it touches the thread, to learn whether the entry's input was
   * admitted, whatever ran on the thread since: the thread's latest checkpoint alone cannot say, because a direct
   * run after the attempt died would have replaced it. Newest is by place in the history, not by `createdAt`.
   *
   * A store answers it by an index on the thread and run, within its bound, however long the history; an empty
   * `runIds` is `Right(None)`. While an open entry of the store's run queue names a run, [[prune]] keeps the newest
   * checkpoint that run committed, so the lookup never loses the evidence an open entry depends on. Needs no claim.
   */
  def latestOfRuns(threadId: ThreadId, runIds: Set[RunId]): Result[Option[Checkpoint]]

  /** Drops events with `seq < beforeSeq`; replay can then start no earlier than `beforeSeq`. */
  def compactEvents(threadId: ThreadId, beforeSeq: Long): Result[Unit]

  /**
   * Removes everything stored for the thread - its checkpoint history, its pending writes, its
   * event log, its effect records and its claim - so that its id names a new thread again; tokens issued after it are
   * still greater than every token issued before. The store keeps only where the thread's event
   * numbering ended - a tombstone, holding nothing of the thread's data, and nothing at all for a
   * thread that numbered no event - so that a new thread of the id numbers its events on past the
   * deleted thread's, one number skipped, and a read of the deleted log after the
   * delete is [[org.llm4s.agent.graph.GraphError.ThreadDeleted GraphError.ThreadDeleted]] (see [[eventsAfter]]); a store that wakes
   * [[awaitEventsAfter]] on commits wakes it on a delete too. Deleting a thread whose tombstone has
   * numbered no event since does not move the numbering on again.
   *
   * '''The tombstone is kept for good.''' A deleted id keeps a small record - its next event number,
   * nothing of the thread's data - which nothing removes: neither [[prune]] nor a later delete. It is
   * what lets a reader of the deleted log, in any process, be told the thread was deleted, and a new
   * thread of the id be numbered past it, however long after the delete either happens. A store
   * whose threads are deleted often - every failed one-shot `Agent.run` forgets its thread - grows by
   * one such record per deleted thread that numbered an event. Fenced like a [[commit]]: only while `token` is
   * the thread's current claim, checked atomically with the delete, and otherwise
   * `Left(GraphError.StaleClaim)` with nothing deleted - so a deleter that lost its claim (it
   * expired and another run took the thread over, or it was released) deletes nothing of the run
   * that holds the thread now. A thread with no claim, an unknown one included, is therefore
   * refused too: claim it first. Use [[org.llm4s.agent.graph.GraphRuntime.deleteThread GraphRuntime.deleteThread]], which claims the thread, so it
   * refuses a thread with a live run in any runtime, and checks the tenant under its claim.
   *
   * '''The fence.''' With `fence = Some((other, otherToken))` the delete is also fenced by another thread's claim:
   * it happens only if `otherToken` is `other`'s current claim too, checked in the same atomic step as the
   * thread's own claim and the delete (one transaction, or under one lock), and otherwise is
   * `Left(GraphError.StaleClaim(other, otherToken, otherCurrent))` with nothing deleted. The thread's own claim is
   * checked first. `GraphRuntime.deleteThread` fences a cascade's delete of each descendant by the root's claim,
   * and child retention each delete by the parent's (design 4.41), so a successor run that took the thread above
   * over never finds a child it may reattach gone. `None` fences by the thread's own claim alone.
   */
  def deleteThread(threadId: ThreadId, token: FencingToken, fence: Option[(ThreadId, FencingToken)]): Result[Unit]

object Checkpointer:

  /**
   * Whether a read of a thread's log after `afterSeq` is from inside the log of a deleted thread of the
   * id, the current thread's first event being `firstSeq`. A store that deletes a thread whose next
   * event number was `n` numbers the next thread of the id from `n + 1`, so `n` is never an event's:
   * the deleted thread's events are below `firstSeq - 1`, and `firstSeq - 1` - "just before the
   * current thread's first event", as a subscriber from a run's first event asks - is never one of
   * them. `0` reads from the start of the current thread. For a store's [[org.llm4s.agent.graph.Checkpointer!.eventsAfter eventsAfter]].
   */
  def deleted(afterSeq: Long, firstSeq: Long): Boolean = afterSeq > 0 && afterSeq < firstSeq - 1

  /** An interrupted [[org.llm4s.agent.graph.Checkpointer.awaitEventsAfter Checkpointer.awaitEventsAfter]]: the flag set again, and `CancelledError`. */
  private[graph] def waitCancelled(threadId: ThreadId, interrupted: InterruptedException): Result[Nothing] =
    Thread.currentThread().interrupt()
    Left(CancelledError(s"waiting for events on thread ${threadId.value}", Some(interrupted)))

/**
 * A [[org.llm4s.agent.graph.Checkpointer Checkpointer]] in memory. Checkpoints and pending writes are stored as JSON and decoded on
 * read, exactly as a database-backed store would, so nothing executable survives a round trip.
 * Claims expire, and [[prune]] judges age, by `clock`, which also stamps each checkpoint and event with the time
 * it was committed. Fencing tokens come from one counter for the
 * whole store, so they never repeat for a thread, even after it is deleted; a deleted thread that
 * numbered any event leaves a tombstone record of its next event number. [[awaitEventsAfter]] wakes
 * on the store's own commits and deletes, so every runtime sharing the instance sees a commit as soon
 * as it lands, without polling. Its calls wait for nothing but one another's brief turn at its lock, which no
 * call holds across I/O or a wait, so each returns at once - [[awaitEventsAfter]] within its `timeout` - and it
 * needs no timeout of its own to meet the contract's bound.
 */
final class InMemoryCheckpointer(clock: Clock = Clock.systemUTC()) extends Checkpointer:

  /** For callers that cannot use default arguments (Java, Kotlin): claims expire by the system clock. */
  def this() = this(Clock.systemUTC())

  final private case class Claim(holder: String, tenantId: Option[String], token: Long, expiresAt: Instant)

  /** One stored checkpoint: its JSON, with the fields the store itself reads; `committedAt` by the store's clock. */
  final private case class Stored(
    id: String,
    status: CheckpointStatus,
    committedAt: Instant,
    json: ujson.Value,
    tenant: Option[String],
    graphId: String,
    deleting: Boolean,
    runId: String
  )

  final private case class ThreadRecord(
    /** Oldest first; the last is the latest. */
    history: Vector[Stored],
    pendingWrites: Vector[ujson.Value],
    /** By `seq`: the event as JSON, and when the store committed it, by its clock. */
    events: Vector[(Long, ujson.Value, Instant)],
    nextSeq: Long,
    earliestSeq: Long,
    claim: Option[Claim],
    /** The first event number of the thread now under the id: past every event of a deleted one. */
    firstSeq: Long,
    /** Effect records by key, with their task and the token of the commit that wrote them. */
    effects: Map[String, StoredEffect] = Map.empty,
    /** The parent the latest checkpoint's origin names, and the thread's place among its children; kept by a delete. */
    child: Option[(String, Long)] = None,
    /** The thread's metadata (design 4.52); a delete removes it. */
    metadata: ThreadMetadata = ThreadMetadata.empty,
    /** When the store last committed a checkpoint or metadata to the thread, by its clock; a delete removes it. */
    updatedAt: Instant = Instant.EPOCH
  ):
    def latestId: Option[String] = history.lastOption.map(_.id)
    def isEmpty: Boolean = history.isEmpty && claim.isEmpty && nextSeq == 1L && effects.isEmpty && metadata.isEmpty

  final private case class StoredEffect(taskId: TaskId, lineage: String, json: ujson.Value)

  private val threads   = mutable.Map.empty[String, ThreadRecord]
  private var lastToken = 0L

  /** Each parent's children, by their place among them: the index [[children]] pages. */
  private val childIndex = mutable.Map.empty[String, mutable.TreeMap[Long, String]]
  private var lastChild  = 0L
  private val lock       = new java.util.concurrent.locks.ReentrantLock()

  /** Signalled, under `lock`, whenever a commit appends events or a thread is deleted. */
  private val appended = lock.newCondition()

  /**
   * The store's run queue (design 4.55), in the same memory as its checkpoints and under the same lock: its entries
   * record each thread's deletion marker from this store, and [[prune]] keeps the newest checkpoint of every run an
   * open entry of it recorded.
   */
  val runQueue: org.llm4s.agent.service.InMemoryRunQueue =
    new org.llm4s.agent.service.InMemoryRunQueue(clock, lock, threadId => withLock(lock)(record(threadId).firstSeq))

  private def record(threadId: ThreadId): ThreadRecord =
    threads.getOrElse(threadId.value, ThreadRecord(Vector.empty, Vector.empty, Vector.empty, 1L, 1L, None, 1L))

  private def store(threadId: ThreadId, record: ThreadRecord): Unit =
    if record.isEmpty then remove(threadId) else threads.update(threadId.value, record)

  private def remove(threadId: ThreadId): Unit =
    threads.remove(threadId.value).flatMap(_.child).foreach(unindex)

  private def unindex(child: (String, Long)): Unit =
    childIndex.get(child._1).foreach { siblings =>
      siblings.remove(child._2)
      if siblings.isEmpty then childIndex.remove(child._1)
    }

  /**
   * `current`'s place among the children of the parent `checkpoint`'s origin names: kept while that is the parent
   * it is indexed under, a new last place under a new parent or for a new thread under the id (a tombstone keeps
   * its place only as a cursor until then), and none for a top-level checkpoint.
   */
  private def placed(threadId: ThreadId, current: ThreadRecord, checkpoint: Checkpoint): Option[(String, Long)] =
    val parent = checkpoint.origin.map(_.parent.value)
    if current.history.nonEmpty && current.child.map(_._1) == parent then current.child
    else
      current.child.foreach(unindex)
      parent.map { p =>
        lastChild += 1
        childIndex.getOrElseUpdate(p, mutable.TreeMap.empty).update(lastChild, threadId.value)
        p -> lastChild
      }

  // the replaced claim's holder is named only to a claim of its own tenant
  private def granted(threadId: ThreadId, claim: Claim, replaced: Option[Claim] = None): RunClaim =
    RunClaim(
      threadId,
      RunId(claim.holder),
      claim.tenantId.map(TenantId(_)),
      FencingToken(claim.token),
      claim.expiresAt,
      replaced.filter(_.tenantId == claim.tenantId).map(c => RunId(c.holder))
    )

  private def stale(threadId: ThreadId, current: ThreadRecord, token: FencingToken): GraphError =
    GraphError.StaleClaim(threadId.value, token, current.claim.map(c => FencingToken(c.token)))

  private def settled(status: CheckpointStatus): Boolean =
    status == CheckpointStatus.Completed || status == CheckpointStatus.Failed

  def claim(threadId: ThreadId, request: ClaimRequest): Result[RunClaim] = withLock(lock) {
    val current = record(threadId)
    val now     = clock.instant()
    current.claim.filter(c => now.isBefore(c.expiresAt)) match
      case Some(live) =>
        val tenant = request.tenantId.map(_.value)
        // another tenant's holder is never named
        val holder =
          if live.tenantId == tenant then GraphError.BusyHolder.Run(RunId(live.holder))
          else GraphError.BusyHolder.Unnamed
        Left(GraphError.ThreadBusy(threadId.value, current.latestId, holder))
      case None =>
        lastToken += 1
        val claim =
          Claim(request.holder.value, request.tenantId.map(_.value), lastToken, now.plusNanos(request.ttl.toNanos))
        store(threadId, current.copy(claim = Some(claim)))
        // a claim still held here has expired: this one takes it over
        Right(granted(threadId, claim, current.claim))
  }

  def renew(threadId: ThreadId, token: FencingToken, ttl: FiniteDuration): Result[RunClaim] = withLock(lock) {
    val current = record(threadId)
    current.claim.filter(_.token == token.value) match
      case None => Left(stale(threadId, current, token))
      case Some(held) =>
        val renewed = held.copy(expiresAt = clock.instant().plusNanos(ttl.toNanos))
        store(threadId, current.copy(claim = Some(renewed)))
        Right(granted(threadId, renewed))
  }

  def release(threadId: ThreadId, token: FencingToken): Result[Unit] = withLock(lock) {
    val current = record(threadId)
    if current.claim.exists(_.token == token.value) then store(threadId, current.copy(claim = None))
    Right(())
  }

  def commit(threadId: ThreadId, commit: Commit): Result[Vector[EventRecord]] = withLock(lock) {
    val current                 = record(threadId)
    val latestId                = current.latestId
    def invalid(reason: String) = GraphError.InvalidCommit(threadId.value, reason)
    // the thread's own claim first, then the fence's, both under the one lock the commit is made under
    val fenced = Option
      .when(!current.claim.exists(_.token == commit.token.value))(stale(threadId, current, commit.token))
      .orElse(commit.fence.flatMap { (other, otherToken) =>
        val above = record(other)
        Option.when(!above.claim.exists(_.token == otherToken.value))(stale(other, above, otherToken))
      })
    val conflict = commit.checkpoint.filter(_.parent != latestId).map { checkpoint =>
      GraphError.CheckpointConflict(threadId.value, checkpoint.parent, latestId)
    }
    val duplicate = commit.checkpoint.filter(c => current.history.exists(_.id == c.id)).map { checkpoint =>
      invalid(s"checkpoint ${checkpoint.id} is already in the thread's history")
    }
    val resultingId = commit.checkpoint.map(_.id).orElse(latestId)
    val misdirected = commit.pendingWrites.find(w => !resultingId.contains(w.checkpointId)).map { write =>
      invalid(
        s"pending write for task ${write.taskId} names checkpoint ${write.checkpointId}, not ${resultingId.getOrElse("<none>")}"
      )
    }
    val unanchored =
      Option.when(commit.retractTurn && commit.checkpoint.isEmpty)(invalid("retractTurn needs a new checkpoint"))
    fenced.orElse(conflict).orElse(duplicate).orElse(misdirected).orElse(unanchored).toLeft(()).map { _ =>
      // events are stored as JSON, like checkpoints, so neither the caller's payloads nor the
      // records handed back can alias what is durable
      val committedAt = clock.instant()
      val stored = commit.events.zipWithIndex.map { (draft, i) =>
        val seq = current.nextSeq + i
        (seq, upickle.default.writeJs(EventRecord.committed(threadId.value, seq, commit.token, draft)), committedAt)
      }
      val records = stored.map((_, json, _) => upickle.default.read[EventRecord](json))
      val writes  = commit.pendingWrites.map(upickle.default.writeJs(_))
      // a blocked turn is retracted back to the newest settled checkpoint, which stays
      val kept =
        if commit.retractTurn then current.history.take(current.history.lastIndexWhere(c => settled(c.status)) + 1)
        else current.history
      val added = commit.checkpoint.map { c =>
        Stored(c.id, c.status, committedAt, Checkpoint.toJson(c), c.tenantId, c.snapshot.graphId, c.deleting, c.runId)
      }
      // released before this commit's own records are applied
      val effects = current.effects.filterNot((_, e) => commit.releasesEffect(e.taskId, e.lineage)) ++
        commit.effects.map(r => r.key.value -> StoredEffect(r.taskId, r.lineage, EffectRecord.toJson(r)))
      store(
        threadId,
        current.copy(
          effects = effects,
          history = kept ++ added,
          pendingWrites = if commit.checkpoint.isDefined then writes else current.pendingWrites ++ writes,
          events = current.events ++ stored,
          nextSeq = current.nextSeq + stored.size,
          child = commit.checkpoint.fold(current.child)(placed(threadId, current, _)),
          metadata = commit.metadata.getOrElse(current.metadata),
          updatedAt =
            if commit.checkpoint.isDefined || commit.metadata.isDefined then committedAt else current.updatedAt
        )
      )
      if stored.nonEmpty then appended.signalAll()
      records
    }
  }

  def latest(threadId: ThreadId): Result[Option[StoredCheckpoint]] = withLock(lock) {
    val current = record(threadId)
    current.history.lastOption match
      case None => Right(None)
      case Some(latest) =>
        for
          checkpoint <- Checkpoint.fromJson(latest.json)
          writes     <- Try(current.pendingWrites.map(upickle.default.read[PendingWrite](_))).toResult
        yield Some(StoredCheckpoint(checkpoint, writes))
  }

  def effect(threadId: ThreadId, key: EffectKey): Result[Option[EffectRecord]] = withLock(lock) {
    record(threadId).effects.get(key.value) match
      case None         => Right(None)
      case Some(stored) => EffectRecord.fromJson(stored.json).map(Some(_))
  }

  def history(threadId: ThreadId, before: Option[String], limit: Int): Result[Vector[Checkpoint]] = withLock(lock) {
    val newestFirst = record(threadId).history.reverse
    val after = before match
      case None => Right(newestFirst)
      case Some(id) =>
        newestFirst.indexWhere(_.id == id) match
          case -1 => Left(GraphError.CheckpointNotFound(threadId.value, id))
          case at => Right(newestFirst.drop(at + 1))
    after.flatMap(page => sequence(page.take(math.max(limit, 0)).map(c => Checkpoint.fromJson(c.json))))
  }

  def checkpoint(threadId: ThreadId, checkpointId: String): Result[Option[Checkpoint]] = withLock(lock) {
    record(threadId).history.find(_.id == checkpointId) match
      case None         => Right(None)
      case Some(stored) => Checkpoint.fromJson(stored.json).map(Some(_))
  }

  def prune(threadId: ThreadId, policy: RetentionPolicy): Result[Unit] = withLock(lock) {
    val current = record(threadId)
    if current.history.isEmpty && current.events.isEmpty then Right(())
    else
      Try {
        val now    = clock.instant()
        val cutoff = policy.maxAge.map(age => now.minusNanos(age.toNanos))
        // the latest checkpoint is always kept, whatever its age
        val (older, latest) = current.history.splitAt(current.history.size - 1)
        // by when each was committed, never by its producer's own timestamp
        val young    = older.filter(c => cutoff.forall(cut => !c.committedAt.isBefore(cut)))
        val byPolicy = policy.maxCheckpoints.fold(young ++ latest)(n => (young ++ latest).takeRight(n))
        val keptIds  = byPolicy.map(_.id).toSet
        // an open queue entry's admission evidence (rule 4): the newest checkpoint each of its runs committed
        val evidence = runQueue.keptRuns(threadId).flatMap(run => current.history.findLast(_.runId == run).map(_.id))
        val kept     = current.history.filter(c => keptIds(c.id) || evidence(c.id))
        val events   = current.events.map((seq, json, at) => (seq, upickle.default.read[EventRecord](json), at))
        // events are kept while their checkpoints are: from the oldest kept checkpoint's first event on
        val byCheckpoint =
          if byPolicy.size == current.history.size then None
          else events.collectFirst { case (seq, e, _) if e.checkpointId.exists(keptIds) => seq }
        val byAge = cutoff.map { cut =>
          events.collectFirst { case (seq, _, at) if !at.isBefore(cut) => seq }.getOrElse(current.nextSeq)
        }
        val bySize = policy.maxEvents.map(n => current.nextSeq - n)
        val floor  = (Vector(current.earliestSeq) ++ byCheckpoint ++ byAge ++ bySize).max.min(current.nextSeq)
        store(
          threadId,
          current.copy(history = kept, events = current.events.filter(_._1 >= floor), earliestSeq = floor)
        )
      }.toResult
  }

  def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int): Result[Vector[EventRecord]] = withLock(lock) {
    val current = record(threadId)
    // a reader from the start reads the thread now under the id, from its first event
    if Checkpointer.deleted(afterSeq, current.firstSeq) then
      Left(GraphError.ThreadDeleted(threadId.value, afterSeq, current.firstSeq))
    else if math.max(afterSeq, current.firstSeq - 1) + 1 < current.earliestSeq then
      Left(GraphError.ReplayUnavailable(threadId.value, current.earliestSeq))
    else
      // events are held in ascending `seq`, so the first one after `afterSeq` is found by bisection
      val first: (Long, ujson.Value, Instant) = (afterSeq + 1, ujson.Null, Instant.EPOCH)
      val from =
        current.events.search(first)(using Ordering.by[(Long, ujson.Value, Instant), Long](_._1)).insertionPoint
      Try(
        current.events.slice(from, from + limit).map((_, json, _) => upickle.default.read[EventRecord](json))
      ).toResult
  }

  /** Waits on the store's own commits rather than polling; see [[org.llm4s.agent.graph.Checkpointer.awaitEventsAfter Checkpointer.awaitEventsAfter]]. */
  override def awaitEventsAfter(
    threadId: ThreadId,
    afterSeq: Long,
    limit: Int,
    timeout: FiniteDuration
  ): Result[Vector[EventRecord]] =
    CancelledError.catchInterrupt(withLock(lock) {
      @tailrec def await(remaining: Long): Result[Vector[EventRecord]] =
        eventsAfter(threadId, afterSeq, limit) match
          case Right(found) if found.isEmpty && remaining > 0 => await(appended.awaitNanos(remaining))
          case other                                          => other
      await(timeout.toNanos)
    }) match
      case Right(result)     => result
      case Left(interrupted) => Checkpointer.waitCancelled(threadId, interrupted)

  def latestOfRuns(threadId: ThreadId, runIds: Set[RunId]): Result[Option[Checkpoint]] = withLock(lock) {
    val runs = runIds.map(_.value)
    record(threadId).history.findLast(c => runs(c.runId)) match
      case None         => Right(None)
      case Some(stored) => Checkpoint.fromJson(stored.json).map(Some(_))
  }

  def compactEvents(threadId: ThreadId, beforeSeq: Long): Result[Unit] = withLock(lock) {
    val current = record(threadId)
    val floor   = math.max(current.earliestSeq, math.min(beforeSeq, current.nextSeq))
    store(threadId, current.copy(events = current.events.filter(_._1 >= floor), earliestSeq = floor))
    Right(())
  }

  def deleteThread(
    threadId: ThreadId,
    token: FencingToken,
    fence: Option[(ThreadId, FencingToken)]
  ): Result[Unit] = withLock(lock) {
    val current = record(threadId)
    // the thread's own claim first, then the fence's, both under the one lock the delete is made under
    val refused = Option
      .when(!current.claim.exists(_.token == token.value))(stale(threadId, current, token))
      .orElse(fence.flatMap { (other, otherToken) =>
        val above = record(other)
        Option.when(!above.claim.exists(_.token == otherToken.value))(stale(other, above, otherToken))
      })
    refused match
      case Some(error) => Left(error)
      case None        =>
        // a tombstone: nothing of the thread but where its numbering ended, so a new thread of the id
        // numbers on past it, one number skipped (see `deleted`), and a reader of the deleted log is told
        // it was deleted; a thread that numbered no event leaves nothing, and a tombstone that numbered no
        // event since its delete is kept as it is, so deleting it again moves nothing on
        if current.nextSeq == 1L then remove(threadId)
        else if current.nextSeq == current.firstSeq && current.firstSeq > 1L then
          store(
            threadId,
            current.copy(
              history = Vector.empty,
              pendingWrites = Vector.empty,
              claim = None,
              effects = Map.empty,
              metadata = ThreadMetadata.empty,
              updatedAt = Instant.EPOCH
            )
          )
        else
          val first = current.nextSeq + 1
          // the tombstone keeps the thread's place among its parent's children, so a page boundary survives
          store(
            threadId,
            ThreadRecord(Vector.empty, Vector.empty, Vector.empty, first, first, None, first, child = current.child)
          )
        appended.signalAll()
        Right(())
  }

  def children(parent: ThreadId, after: Option[ThreadId], limit: Int): Result[Vector[ChildThread]] = withLock(lock) {
    val siblings = childIndex.getOrElse(parent.value, mutable.TreeMap.empty[Long, String])
    val from: Result[Long] = after match
      case None => Right(0L)
      case Some(cursor) =>
        threads
          .get(cursor.value)
          .flatMap(_.child)
          .filter(_._1 == parent.value)
          .map(_._2)
          .toRight(ValidationError("after", s"'${cursor.value}' is not a known child of '${parent.value}'"))
    from.flatMap { cursor =>
      // a tombstone keeps its place but is not listed
      val listed = siblings.iteratorFrom(cursor + 1).flatMap((_, id) => threads.get(id).flatMap(_.history.lastOption))
      sequence(
        listed.take(math.max(limit, 0)).toVector.map(latest => Checkpoint.fromJson(latest.json))
      ).map(_.flatMap(ChildThread.of))
    }
  }

  def threads(query: ThreadQuery): Result[ThreadPage] = withLock(lock) {
    val listed = registered(query.tenantId).filter(query.matches)
    val after  = query.after.fold(listed)(cursor => listed.filter(t => RegistryOrder.before(t.cursor, cursor)))
    Right(ThreadPage.of(after.sorted(using RegistryOrder.newestFirst).take(query.limit + 1), query.limit))
  }

  def incompleteThreads(
    tenantId: Option[TenantId],
    after: Option[ThreadCursor],
    limit: Int
  ): Result[ThreadPage] = ThreadQuery.checkLimit(limit).map { limit =>
    withLock(lock) {
      val idle  = registered(tenantId).filter(_.status == ThreadStatus.Idle)
      val later = after.fold(idle)(cursor => idle.filter(t => RegistryOrder.before(cursor, t.cursor)))
      ThreadPage.of(later.sorted(using RegistryOrder.newestFirst.reverse).take(limit + 1), limit)
    }
  }

  /** Every root thread of `tenantId` with a checkpoint, as the registry lists it, its claim judged now; under `lock`. */
  private def registered(tenantId: Option[TenantId]): Vector[ThreadSummary] =
    val now    = clock.instant()
    val tenant = tenantId.map(_.value)
    threads.iterator.flatMap { (id, record) =>
      record.history.lastOption.filter(latest => latest.tenant == tenant && record.child.isEmpty).map { latest =>
        val claimed = record.claim.exists(c => now.isBefore(c.expiresAt))
        ThreadSummary(
          ThreadId(id),
          latest.tenant.map(TenantId(_)),
          latest.graphId,
          ThreadStatus.of(latest.status, latest.deleting, claimed),
          latest.id,
          record.metadata,
          record.updatedAt
        )
      }
    }.toVector

  private def sequence[A](results: Vector[Result[A]]): Result[Vector[A]] =
    results.foldLeft[Result[Vector[A]]](Right(Vector.empty))((acc, r) => acc.flatMap(as => r.map(as :+ _)))

private object RegistryOrder:

  /** The registry's order: newest first by `updatedAt`, then by thread id, descending. */
  val newestFirst: Ordering[ThreadSummary] =
    Ordering.by[ThreadSummary, (Instant, String)](t => (t.updatedAt, t.threadId.value)).reverse

  /** Whether position `a` comes before `b` in ascending order: by `updatedAt`, then by thread id. */
  def before(a: ThreadCursor, b: ThreadCursor): Boolean =
    a.updatedAt.isBefore(b.updatedAt) || (a.updatedAt == b.updatedAt && a.threadId.value < b.threadId.value)
