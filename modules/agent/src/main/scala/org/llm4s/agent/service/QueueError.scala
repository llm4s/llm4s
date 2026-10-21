package org.llm4s.agent.service

import org.llm4s.annotation.Experimental
import org.llm4s.error.{ LLMError, NonRecoverableError }

/** Errors a [[RunQueue]] answers with (design 4.55). Every case is non-recoverable: retrying the same call changes nothing. */
@Experimental
sealed trait QueueError extends LLMError

@Experimental
object QueueError:

  /**
   * No entry `entryId` of the caller's tenant: the store never issued the id, or the entry is another tenant's. A store
   * that cannot tell a reclaimed entry from an unknown one answers this for both.
   */
  final case class TicketNotFound(entryId: EntryId) extends QueueError with NonRecoverableError:
    override val message: String = s"No queue entry ${entryId.value}"

  /**
   * Entry `entryId` was closed and then reclaimed once its ticket lifetime passed: its outcome is no longer known.
   * Answered, instead of [[TicketNotFound]], for every id the store issued that it no longer holds for the caller's
   * tenant - so it never tells another tenant's live entry from a reclaimed one.
   */
  final case class TicketExpired(entryId: EntryId) extends QueueError with NonRecoverableError:
    override val message: String = s"Queue entry ${entryId.value} has expired: it closed and was reclaimed"

  /**
   * A call made under the lease `token` on entry `entryId` was refused: that lease is not the entry's current one -
   * another worker took the entry over after the lease expired, or it was released. `current` is the current lease's
   * token, if the entry is leased.
   */
  final case class LeaseLost(entryId: EntryId, token: LeaseToken, current: Option[LeaseToken])
      extends QueueError
      with NonRecoverableError:
    override val message: String =
      s"Lease ${token.value} on queue entry ${entryId.value} is not its current lease (${current.fold("none")(_.value.toString)})"

  /** Entry `entryId` is closed (`outcome`), so nothing more can be done under a lease on it. */
  final case class EntryClosed(entryId: EntryId, outcome: EntryOutcome) extends QueueError with NonRecoverableError:
    override val message: String = s"Queue entry ${entryId.value} is closed (${outcome.state})"

  /** The submission key names an entry the store holds for a different submission. */
  final case class SubmissionKeyReused(key: SubmissionKey, entryId: EntryId)
      extends QueueError
      with NonRecoverableError:
    override val message: String =
      s"Submission key '${key.value}' already names queue entry ${entryId.value}, a different submission"
