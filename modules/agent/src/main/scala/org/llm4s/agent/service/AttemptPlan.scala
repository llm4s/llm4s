package org.llm4s.agent.service

import org.llm4s.agent.graph.{ Checkpoint, CheckpointStatus }
import org.llm4s.annotation.Experimental

/**
 * What an attempt at a queue entry does to its thread (Stage 5 rule 4, design 4.55), decided after
 * [[RunQueue.recordAttempt]] recorded the attempt's `RunId` and before the attempt touches the thread:
 *
 *  - `Start`: none of the entry's runs committed - its input was never admitted, an earlier attempt having died before
 *    its first commit - so the attempt admits it: `run` (or the typed agent's run) for a start, `resume` for a resume.
 *  - `Recover`: the attempt recovers the thread - its entry's admitted run is the thread's latest and incomplete
 *    (`Running`), so a worker died mid-run; or the entry is a queued recover, whose attempts recover the thread until
 *    one of them commits (the owner's explicit transfer of the thread's recovery to the queue).
 *  - `Close`: the entry's input was admitted and the thread has moved on - its run reached rest, or the thread moved
 *    on through a direct `recover`, which applied the input once - so the attempt closes the entry with `outcome`,
 *    running nothing.
 */
@Experimental
enum AttemptPlan:
  case Start
  case Recover
  case Close(outcome: EntryOutcome)

@Experimental
object AttemptPlan:

  /**
   * The plan for an attempt at `entry`, from the run-id lookup - `admitted`, the newest checkpoint any of the entry's
   * recorded runs committed (`Checkpointer.latestOfRuns(entry.threadId, entry.runIds)`) - and the thread's `latest`
   * checkpoint, both read after the attempt was recorded. Never from an attempt count, and never from `latest` alone:
   * a direct run after an attempt died would have replaced it.
   */
  def of(entry: QueueEntry, admitted: Option[Checkpoint], latest: Option[Checkpoint]): AttemptPlan =
    admitted match
      case None =>
        entry.input match
          case QueuedInput.Recover => Recover
          case _                   => Start
      case Some(own) =>
        latest match
          case Some(now) if now.id == own.id && now.status == CheckpointStatus.Running => Recover
          case Some(now)                                                               => Close(closedFrom(now))
          // admitted, and the thread is gone: deleted after the attempt was recorded
          case None => Close(EntryOutcome.Cancelled(CancelReason.ThreadDeleted))

  /** The outcome of an entry closed from the thread's state `latest`, without running anything. */
  def closedFrom(latest: Checkpoint): EntryOutcome =
    latest.status match
      case CheckpointStatus.Failed =>
        EntryOutcome.Failed(FailureReason.RunFailed(s"the thread's run failed at checkpoint ${latest.id}"))
      case status => EntryOutcome.Done(Some(latest.id), Some(status))
