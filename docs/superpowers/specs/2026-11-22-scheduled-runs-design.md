# Scheduled (cron) runs: schedules beside the run queue, sliced

Issue #2446. Deferred by Stage 5's Decision 11 (`2026-10-20-stage-5-production-design.md`); the queue it builds on is
design records 4.55 to 4.57 (`docs/design/typed-agent-runtime/`). Webhooks for scheduled runs reuse
`2026-11-22-run-completion-webhooks-design.md` (#2447), which is built first. Nothing here is built yet: this document
settles the issue's open questions and slices the work.

## Open questions for the maintainer

Each has a recommendation, and the rest of the document assumes it. None could be settled from the code.

1. **The default for firings missed during downtime, and whether "catch up" exists.** Recommended: two policies,
   `FireOnce` (the default: one run, late, for however many fire times passed) and `Skip(lateAfter)`. No catch-up: a
   schedule's input is fixed, so N catch-up runs are the same prompt N times, at N times the cost, and tell the agent
   nothing a single late run does not. `Skip` as the default would be the cheaper failure but loses a daily run to a
   ten-minute outage silently.
2. **How long a fresh-thread schedule's threads live.** Retention never removes a whole thread: "the thread's latest
   checkpoint and its pending writes are never removed" (`RetentionPolicy.scala`). So a schedule that makes a thread
   per firing grows the store without bound unless something deletes them. Recommended: `FreshThread(keepFor)`, 7 days
   by default, deleted by the scheduler; `None` keeps them. LangGraph deletes the thread as soon as the run completes
   by default, which here would delete the answer before anyone could read it (the webhook carries none). The
   alternative that removes this from the scheduler altogether is a store-wide thread expiry in `SweepPolicy`, which
   is a retention feature of its own and would need its own issue.
3. **DST for fixed-time schedules** (decision 5): each matching local time fires exactly once, a skipped one at the
   transition and a repeated one at its first occurrence, while schedules whose hour field is a wildcard follow the
   timeline. The alternative is the timeline for everything, which is simpler to state and skips a `30 2 * * *` run
   on the night clocks go forward.
4. **A thread-bound schedule whose thread is busy.** Recommended default: `BusyThreadPolicy.Reject`, which here means
   the firing is skipped and recorded. `Enqueue` is available per schedule, but as a default it lets a schedule that
   fires faster than its runs finish build a queue without bound.

## Intent

Run an agent on a schedule: a cron expression in a time zone submits a run through the Stage 5 queue, either onto one
thread, which each run continues, or onto a fresh thread each time. Schedules are data in the store, so every
instance sees them, any instance can create or change one, and whichever instance is running a scheduler fires each
one **once**, however many there are and whichever of them die.

### Goals

1. A fire time is decided once across the fleet: submitted, skipped or abandoned, and recorded as such.
2. A firing claimed by an instance that then dies is finished by another, without a second run.
3. Time is the database's (Stage 5 rule 1): skew between hosts changes nothing but how soon a tick sleeps.
4. `java.time` only; no new dependency in `llm4s-agent`.
5. In-memory, SQLite and PostgreSQL implement the store and pass one contract suite (Stage 5 rules 7 and 8).

### Non-goals

- **A scheduler service, an HTTP API for schedules**: the server project (Stage 5 Decision 2).
- **One-shot "run at" schedules, end times, a maximum number of runs.** A caller that wants one run later submits it
  later; none of these is asked for by the issue.
- **Second-level granularity.** The smallest interval is one minute.
- **Passing the fire time to the agent.** The input is the schedule's, unchanged.

### Anti-goals

- A fire time that produces two runs, or one that silently produces none.
- A lock one instance holds for the fleet: its death would delay every schedule by a lease.
- A schedule stored in `application.conf`, where two instances can disagree about it and no call can change it.

## What exists today (checked against the code, 2026-11-22)

- **Submission**: `RunSubmitter.submit(threadId, agentId, input, tenantId, policy, submissionKey, traceContext)`
  queues a `QueuedInput` and answers a `Ticket` (`RunSubmitter.scala`). Nothing in the repository submits on a
  schedule.
- **Submission keys** (`SubmissionKey`, at most 200 characters, `QueueIds.scala`): `RunQueue.submit` looks the key up
  among the tenant's entries and inserts only if there is none, under a lock on the key
  (`PostgresRunQueue.submit`: `lock(c, s"key:$scope:${key.value}")`, then `Sql.queueEntryByKey`; a unique index on
  `(key_scope, submission_key)`, `PostgresSchema.addRunQueue`). A held key with the same submission answers the
  existing ticket (`Ticket.existing`); with a different one, `QueueError.SubmissionKeyReused(key, entryId)`. The key
  lookup comes before the busy check. `Submission.sameAs` compares thread, agent, tenant, input, policy and key.
- **A key lives as long as its entry.** A closed entry is reclaimed `ticketLifetime` after its close, 24 hours by
  default, and its key with it (`RunQueue.reclaim`; `RunQueueSettings.DefaultTicketLifetime`; the comment in
  `PostgresRunQueue.submit`: "a reclaim running now either deletes it first - and the key is free - or skips it").
- **Busy threads**: `BusyThreadPolicy` is `Reject`, `Enqueue` or `Interrupt`. A `Reject` submission to a thread with
  an open entry, or a live claim of the submitter's tenant, is refused with `GraphError.ThreadBusy` and makes no
  entry (`RunQueue.scala`, class doc). A thread with pending interrupts is not busy: `submit` has no refusal for
  it, and "`start` stays refused while interrupts are pending, whatever the policy" (Stage 5 spec, W3).
- **Clock**: PostgreSQL judges time by its own clock (`PostgresCheckpointer.clockNow`); in-memory and SQLite use
  their injected `Clock` (Stage 5 rule 1). The SPI has no call that answers the store's time.
- **Deleting a thread** through the service: `RunSubmitter.deleteThread(runtime, tenantId, threadId, timeout)` closes
  the thread's entries, waits, then deletes. An entry submitted after a delete runs on a new thread of the same id.
- **Loops beside the queue**: `AgentWorker` runs its take loop, the retention sweep (one instance at a time, by a
  store claim on a reserved thread id, `AgentWorker.SweepLock`) and a depth sampler on virtual threads; `start()` and
  `shutdown()`.
- **What `llm4s-agent` may depend on**: core, ujson, fansi and ox (`build.sbt`, `lazy val agent`); it is a frozen
  module (`mimaFrozen("llm4s-agent")`). There is no cron parser in the repository.
- **Encryption (#2760, open)** seals queue inputs above the store (`EncryptingRunQueue`), leaving ids, keys and times
  in plain, and compares in plain on `SubmissionKeyReused`.
- **Schemas**: PostgreSQL 5, SQLite 11; #2757 takes PostgreSQL 6; the webhook outbox takes the next.

## Placement

| What | Where |
|---|---|
| `CronExpression`, `Schedule`, `ScheduleStore`, `Firing`, `RunScheduler`, `SchedulerSettings`, the in-memory store | `llm4s-agent`, `org.llm4s.agent.service` |
| `ScheduleStoreContract` | `llm4s-agent-testkit` |
| SQLite and PostgreSQL schedule stores, one schema step each | `llm4s-agent-checkpoint-sqlite`, `llm4s-agent-checkpoint-postgres` |
| Example wiring | `modules/deploy-service` (unpublished) |
| Multi-instance firing test | `modules/it`, `@Docker` tier |

Core changes: none.

## Decisions

### 1. Schedules live in the store, beside the queue

**Decision.** A `ScheduleStore` SPI, implemented by the three stores in the database that holds the queue, reached as
the queue is (`CheckpointStore.scheduleStore`). `RunScheduler` is the API over it: `create`, `update`, `pause`,
`resume`, `delete`, `schedule`, `schedules`, `firings`, all tenant-scoped. A schedule has a caller-chosen
`ScheduleId`, unique per tenant, so creating one from deployment scripts is repeatable: a second `create` of the same
id is refused (`ScheduleError.Exists`). `update` is a compare-and-set on the schedule's `revision`.

Changing a schedule's expression or zone, and `resume`, set its next fire time to the first one after the store's
now: no firing is owed for the time a schedule was paused or different. `delete` removes the schedule; firings it
already submitted run on (cancel them through `RunSubmitter.cancel`), and their records stay until reclaimed.

**Reasoning.** "Every instance sees them" is the requirement, and the claim that makes a firing happen once needs a
row to be made against. Configuration cannot be changed by a call, and instances rolled at different times would
disagree about it.

**Rejected.** *Schedules in `application.conf`*: as above. *Both, with config seeding the store*: a caller can do that
with `create` at startup, treating `Exists` as success. *A store-issued numeric id*, as entries have: a retried
`create` would make a second schedule, which is the failure this feature must not have.

### 2. Each fire time is claimed in the store; a submission key makes the submit after it repeatable

**Do Stage 5 submission keys already give exactly-once firing? No**, for three reasons, each from the code above:

1. A key is held only while its entry is. Once the entry has closed and been reclaimed, the same key inserts a new
   entry. Keys alone bound duplicates by time, not absolutely.
2. A key deduplicates an insert, not a decision. A firing that is skipped (the thread was busy and the policy is
   `Reject`, or the firing was too late) makes no entry, so there is no key to find, and another instance looking at
   the same fire time a moment later may decide differently and run it.
3. Without a durable cursor no instance knows which fire times are already dealt with after a restart.

**Decision.** Two rows and one key.

- A schedule row holds `nextFireAt`. A **firing** row, keyed `(tenant, schedule, fireAt)`, records one fire time's
  fate.
- `ScheduleStore.claim` is one transaction: if the schedule is not paused and its `nextFireAt` is still the value the
  caller read, insert the firing row (claimed at the store's now, with the thread it will run on) and set
  `nextFireAt` to the next fire time. Otherwise it answers `None`: another instance has it. This is the
  exactly-once: a compare-and-set on one row, with the firing's primary key as a second guard.
- The claimant then submits through the queue with the key `llm4s:schedule:<scheduleId>:<fireAt epoch second>`, and
  **settles** the firing with what happened: `Submitted(entryId)`, `SkippedBusy`, `SkippedMissed`, or
  `Refused(message)` for any other refusal by the queue.
- A firing left unsettled for `redriveAfter` (30 seconds) is taken up by any scheduler, which submits with the same
  key and settles. If the first claimant did submit, the key finds its entry (`Ticket.existing`); if the schedule was
  edited in between, the queue answers `SubmissionKeyReused(key, entryId)`, which names the entry, and the firing is
  settled `Submitted(entryId)`. A fresh-thread firing's thread id is generated at the claim and stored in the firing
  row, so a re-drive submits to the same thread.
- **The key's lifetime is respected, not assumed.** An entry cannot be reclaimed sooner than `ticketLifetime` after
  the claim, since it closes after it was submitted and is reclaimed `ticketLifetime` after that. A scheduler
  therefore re-drives a firing only while it is younger than half the ticket lifetime, and settles an older one
  `Abandoned` without submitting. So a firing yields exactly one entry unless no scheduler ran for half a ticket
  lifetime (12 hours by default) between a claim and its submit, in which case it yields at most one.
  `ticketLifetime` must therefore be the same on every instance: the scheduler reads it from its `RunSubmitter`'s
  settings, the sweeping worker from its own (`AgentWorker.queueSettings`).
- `RunSubmitter.submit` refuses a caller's key that begins `llm4s:`, so a caller cannot occupy a firing's key.

**Reasoning.** There is no transaction across the schedule store and the queue, deliberately: Stage 5 kept the queue
a separate SPI and correct by idempotency rather than by a shared transaction (Decision 5), and #2760's wrappers sit
above each SPI, so a store-internal "claim and insert the entry" would write a scheduled input into the queue
unsealed. Claim, then an idempotent submit, then settle has the same shape as an entry's attempts: recorded before
acted on.

**Rejected.** *Keys only*, no firing row: the three failures above. *One transaction in the store that claims and
inserts the queue entry*: bypasses `RunQueue`, so it bypasses encryption and any other wrapper, and puts submission
rules in two places. *A fleet-wide scheduler lock*, as the retention sweep has: the sweep can wait a lease for a dead
holder, a firing cannot.

### 3. Missed firings: fire once, or skip; per schedule

**Decision.** `MissedFirings` is `FireOnce` (the default) or `Skip(lateAfter: FiniteDuration)`.

When a scheduler finds a schedule due, its `nextFireAt` is `T` and the store's now is `now`:

- `FireOnce`: claim `T` and submit it, however late. The next fire time is the first after `now`, so every fire time
  between `T` and `now` is passed over; their number is recorded on the firing (`missed`, counted up to 1,000).
- `Skip(lateAfter)`: if `now - T` is at most `lateAfter`, as `FireOnce`. Otherwise claim `T`, settle it
  `SkippedMissed` and move to the first fire time after `now`.

So after an outage of any length a schedule produces at most one run, and none of the fire times it missed is
unrecorded.

**Reasoning and rejected.** Open question 1. *Catch up* (one run per missed fire time) is rejected for cost and
because the runs would be identical. A cap on catch-up was considered and is the same objection with a number on it.

### 4. One thread or a fresh one; a busy thread; fresh threads' lifetime

**Decision.** `ScheduleTarget` is `Thread(threadId)` or `FreshThread(keepFor: Option[FiniteDuration])`. Every firing
is a `QueuedInput.Start` with the schedule's input; a schedule whose input is a `Resume` or `Recover` is refused when
it is created.

- **Thread-bound**: each firing continues the thread, creating it on the first. The schedule carries a
  `BusyThreadPolicy`, the queue's own, default `Reject`. `Reject` and a busy thread settle the firing `SkippedBusy`;
  `Enqueue` queues it behind the thread's entries; `Interrupt` stops the running turn for it, as any `Interrupt`
  submission does. A thread suspended for an approval is not busy: the queue accepts the firing, and the
  runtime's refusal of a start on such a thread is its entry's outcome, as for any submission (slice C4 pins it).
- **Fresh thread**: a new thread id per firing, generated at the claim. The policy is irrelevant. `keepFor` after
  the claim, once the firing's entry has closed, the scheduler deletes the thread
  (`RunSubmitter.deleteThread`); `None` keeps it. The deadline is stored on the firing row when it is claimed, so
  deleting or editing the schedule orphans nothing.
- Deleting a thread a schedule is bound to does not touch the schedule: the next firing starts a new thread of that
  id, as any submission after a delete does. `RunScheduler.schedules` can filter by thread so a service's delete can
  remove them first.

**Reasoning.** The queue already has a busy-thread policy with exactly the three behaviours wanted, and a second one
would have to be explained against it. Skipping is what `Reject` means when nobody is there to retry.

**Rejected.** *A scheduler-specific overlap policy* (`Forbid`, `Replace`, `Allow`): the same three under other
names. *Deleting a fresh thread when its run completes*, LangGraph's default: open question 2.

### 5. Five-field cron, a zone per schedule, DST defined, parser written here

**Decision.**

- **Dialect**: five fields, `minute hour day-of-month month day-of-week`. Each is `*`, a number, a range `a-b`, a
  list `a,b`, or a step `*/n` or `a-b/n`. Months and days may be three-letter English names, any case; day-of-week
  is 0 to 7, 0 and 7 both Sunday. When both day fields are restricted, a day matches if either does, as Vixie cron.
  No seconds or year field, no `?`, `L`, `W`, `#`, and no `@daily` macros: anything else is a `ValidationError`
  naming the field. An expression that can never fire (`0 0 31 2 *`) is refused when parsed.
- **Zone**: `Schedule.zone: java.time.ZoneId`, default `ZoneOffset.UTC`. It is stored by id. An instance whose JVM
  does not know the id leaves that schedule to the others and counts an error.
- **DST**, with `java.time.zone.ZoneRules` (`getValidOffsets`, `getTransition`):
  - *Hour field `*` or `*/n`* (the schedule is an interval within the day): fire times follow the timeline. An hour
    that does not exist does not fire; an hour that happens twice fires both times. `0 * * * *` fires every real
    hour, all year.
  - *Any other hour field* (the schedule names times of day): each matching local time fires exactly once. One that
    is skipped fires at the instant of the transition, and several skipped ones fire once between them. One that is
    repeated fires at its first occurrence only. `30 2 * * *` runs once on every day of the year.
- **A parser written here**, in `llm4s-agent`: `CronExpression`, with `next(after, zone)`.

**Reasoning.** LangGraph runs every schedule in UTC, which makes "9 a.m. on weekdays" wrong for half the year
anywhere with DST. The two-rule DST behaviour is the distinction cronie's `cron(8)` draws between wildcard and
fixed-time jobs, the one that matches what each kind of schedule is for. The parser is about 200 lines for this dialect; the DST rule is ours
whatever parses the text; and `llm4s-agent` is a frozen module whose dependencies are core, ujson, fansi and ox, so
a dependency added for it is one every user of the agent runtime carries and that the freeze would freeze.

**Rejected.** *Quartz syntax* (seconds, `?`, `L`): a larger dialect to support for ever, for cases nobody has asked
for. *UTC only*: above. *A dependency*: cron-utils and Quartz bring far more than a parser, and neither decides DST
the way this needs.

### 6. Every scheduler ticks; the store's clock decides what is due

**Decision.** `RunScheduler.start()` runs a loop on a virtual thread, as `AgentWorker` does; a deployment starts one
in each instance that runs a worker. Every `tickInterval` (15 seconds, with jitter so instances spread out):

1. `ScheduleStore.due(redriveAfter, limit)` answers the store's now, the schedules whose `nextFireAt` is at or
   before it, oldest first, and the unsettled firings claimed more than `redriveAfter` ago.
2. For each due schedule: decide (decision 3), claim, submit, settle. For each stale firing: re-drive (decision 2).
3. Delete the threads of fresh-thread firings past their deadline (decision 4), and reclaim settled firings older
   than `firingLifetime` (7 days) that have no thread left to delete.

There is no leader and no lock: instances contend only in `claim`, which one of them wins.

**Drift.** Fire times come from the expression and the store's clock, never from when a tick ran, so lateness does
not accumulate. A firing is late by at most a tick plus the store calls, and the lateness is measured
(`llm4s.agent.schedule.lateness`). The host's clock is used only to sleep between ticks. `ScheduleStore.now` exists
because `create`, `update` and `resume` need the store's time to compute a first fire time.

**Rejected.** *The loop inside `AgentWorker`*: an instance that only serves the API should be able to manage
schedules without a worker, and a worker without schedules should not tick. *A timer per schedule in memory*: lost on
restart, and wrong on every instance but one. *PostgreSQL `pg_cron` or `LISTEN`*: no SQLite or in-memory equivalent.

### 7. Webhooks, tenancy, limits, encryption, metrics, tracing

- **Webhooks**: `Schedule.webhook: Option[WebhookName]` is copied onto each firing's `Submission`, validated when the
  schedule is created or updated, as a submission's is. A firing that makes no entry (skipped, abandoned, refused)
  sends none: it is visible in `firings` and the metrics. The webhook payload's `submissionKey` names the schedule
  and fire time.
- **Tenancy** (Stage 5 rule 9): a schedule belongs to its `tenantId`, its id is unique within it, it submits as that
  tenant, and every management call answers for the caller's tenant only, another tenant's schedule being
  `ScheduleError.NotFound`. `due`, `claim`, `settle` and `reclaim` are the operator's, store-wide.
- **Limits** (`SchedulerSettings`): at most `maxSchedulesPerTenant` (100), counted in `create`'s transaction; a
  schedule whose next 64 fire times include two closer than `minInterval` (1 minute, so no limit beyond the
  dialect's until an operator raises it) is refused.
- **Encryption (#2760)**: a schedule row holds one application payload, `input`, which is what a queue entry's input
  is. An `EncryptingScheduleStore` seals it (associated data: key id, tenant, schedule id), and the scheduler is built
  over it and over `EncryptingRunQueue`. Ids, the expression, the zone, times, policies and the webhook name stay
  plain: the store orders or filters by them. A firing row holds ids and times only.
- **Metrics**, labelled by `agent` as the queue's are (`llm4s.agent.queue.closed`): `llm4s.agent.schedule.firings`
  (counter; `outcome`: `submitted`, `skipped_busy`, `skipped_missed`, `abandoned`, `refused`),
  `llm4s.agent.schedule.missed` (counter of fire times passed over), `llm4s.agent.schedule.lateness` (histogram),
  `llm4s.agent.schedule.redrives` (counter), `llm4s.agent.schedule.ticks` (counter; `result`). Never labelled by
  schedule id or tenant.
- **Tracing**: a firing has no submitter, so its entry starts a trace of its own (`Submission.traceContext` is
  `None`). No new `TraceEvent`.
- **Schema**: one step per store, the next version after the webhook outbox's. `checkpoint_schedules`
  (`key_scope`, `schedule_id`, `tenant_id`, `agent_id`, `cron`, `zone`, `thread_id`, `keep_for_micros`, `input_json`,
  `busy_policy`, `missed_policy`, `late_after_micros`, `webhook`, `paused`, `revision`, `created_at`, `updated_at`,
  `next_fire_at`; primary key `(key_scope, schedule_id)`; index on `next_fire_at WHERE NOT paused`) and
  `checkpoint_schedule_firings` (`key_scope`, `schedule_id`, `fire_at`, `agent_id`, `thread_id`, `claimed_at`,
  `missed`, `outcome_json`, `entry_id`, `settled_at`, `delete_thread_at`; primary key
  `(key_scope, schedule_id, fire_at)`; indexes on `claimed_at WHERE outcome_json IS NULL` and on `delete_thread_at`).
  Durations are stored in their columns' units and are `FiniteDuration` in the API.

## Public API

All in `org.llm4s.agent.service`, all `@Experimental`. Existing types named here are the real ones.

```scala
final class CronExpression private (...):    // equality and toString by the normalised text
  def next(after: Instant, zone: ZoneId): Instant
object CronExpression:
  def parse(text: String): Result[CronExpression]

opaque type ScheduleId = String              // 1 to 64 characters of A-Z a-z 0-9 _ . -
object ScheduleId:
  def of(value: String): Result[ScheduleId]

enum ScheduleTarget:
  case Thread(threadId: ThreadId)
  case FreshThread(keepFor: Option[FiniteDuration])

enum MissedFirings:
  case FireOnce
  case Skip(lateAfter: FiniteDuration)

final case class Schedule private (          // growth-prone: apply + with*
  id: ScheduleId, tenantId: Option[TenantId], agentId: AgentId, cron: CronExpression, zone: ZoneId,
  target: ScheduleTarget, input: QueuedInput, busy: BusyThreadPolicy, missed: MissedFirings,
  webhook: Option[WebhookName], paused: Boolean,
  revision: Long, createdAt: Option[Instant], updatedAt: Option[Instant], nextFireAt: Option[Instant])
object Schedule:
  def apply(id: ScheduleId, agentId: AgentId, cron: CronExpression, input: QueuedInput,
            target: ScheduleTarget = ScheduleTarget.FreshThread(Some(7.days)),
            tenantId: Option[TenantId] = None, zone: ZoneId = ZoneOffset.UTC,
            busy: BusyThreadPolicy = BusyThreadPolicy.Reject, missed: MissedFirings = MissedFirings.FireOnce,
            webhook: Option[WebhookName] = None): Schedule

enum FiringOutcome:
  case Submitted(entryId: EntryId)
  case SkippedBusy
  case SkippedMissed
  case Abandoned
  case Refused(message: String)

final case class Firing private (            // growth-prone
  scheduleId: ScheduleId, tenantId: Option[TenantId], agentId: AgentId, fireAt: Instant, threadId: ThreadId,
  claimedAt: Instant, missed: Int, outcome: Option[FiringOutcome], settledAt: Option[Instant],
  deleteThreadAt: Option[Instant])

final case class DueSchedules(now: Instant, schedules: Vector[Schedule], unsettled: Vector[Firing])

trait ScheduleStore:
  def now: Result[Instant]
  def create(schedule: Schedule, nextFireAt: Instant, maxPerTenant: Int): Result[Schedule]
  def update(schedule: Schedule, nextFireAt: Instant): Result[Schedule]        // compare-and-set on revision
  def delete(tenantId: Option[TenantId], id: ScheduleId): Result[Unit]
  def schedule(tenantId: Option[TenantId], id: ScheduleId): Result[Schedule]
  def schedules(tenantId: Option[TenantId], thread: Option[ThreadId], after: Option[ScheduleId], limit: Int)
      : Result[Vector[Schedule]]
  def firings(tenantId: Option[TenantId], id: ScheduleId, limit: Int): Result[Vector[Firing]]   // newest first
  def due(redriveAfter: FiniteDuration, limit: Int): Result[DueSchedules]
  def claim(schedule: Schedule, fireAt: Instant, nextFireAt: Instant, threadId: ThreadId, missed: Int)
      : Result[Option[Firing]]
  def settle(firing: Firing, outcome: FiringOutcome): Result[Firing]            // the first settle wins
  def threadsToDelete(limit: Int): Result[Vector[Firing]]
  def threadDeleted(firing: Firing): Result[Unit]
  def reclaim(lifetime: FiniteDuration, limit: Int): Result[Int]

sealed trait ScheduleError extends LLMError
object ScheduleError:
  final case class NotFound(id: ScheduleId) extends ScheduleError
  final case class Exists(id: ScheduleId) extends ScheduleError
  final case class Conflict(id: ScheduleId, revision: Long) extends ScheduleError
  final case class TooMany(limit: Int) extends ScheduleError

final case class SchedulerSettings private ( // growth-prone
  tickInterval: FiniteDuration, batchSize: Int, redriveAfter: FiniteDuration, firingLifetime: FiniteDuration,
  maxSchedulesPerTenant: Int, minInterval: FiniteDuration, deleteTimeout: FiniteDuration)

final class RunScheduler(
  store: ScheduleStore, submitter: RunSubmitter, runtime: GraphRuntime,
  settings: SchedulerSettings = SchedulerSettings.default, metrics: MetricsCollector = MetricsCollector.noop):
  def create(schedule: Schedule): Result[Schedule]
  def update(schedule: Schedule): Result[Schedule]
  def pause(tenantId: Option[TenantId], id: ScheduleId): Result[Schedule]
  def resume(tenantId: Option[TenantId], id: ScheduleId): Result[Schedule]
  def delete(tenantId: Option[TenantId], id: ScheduleId): Result[Unit]
  def schedule(tenantId: Option[TenantId], id: ScheduleId): Result[Schedule]
  def schedules(tenantId: Option[TenantId], thread: Option[ThreadId] = None,
                after: Option[ScheduleId] = None, limit: Int = 100): Result[Vector[Schedule]]
  def firings(tenantId: Option[TenantId], id: ScheduleId, limit: Int = 100): Result[Vector[Firing]]
  def start(): Unit
  def shutdown(): Unit
```

`runtime` is there for `RunSubmitter.deleteThread`, which takes one. The stores expose `scheduleStore` as they expose
`runQueue`, and `CheckpointStore.scheduleStore: Result[ScheduleStore]`. `WebhookName` is #2447's; until its slice H1
has merged, `Schedule` has no `webhook`.

## Slice plan

Each slice is one PR. C1 to C4 do not need webhooks; C5 does.

| Slice | Delivers | Tested by |
|---|---|---|
| **C1** `CronExpression` | Parser, `next`, the DST rules. Pure. | Table tests of every field form and every refusal; known sequences; DST tables for `Europe/London`, `America/New_York` and `Australia/Lord_Howe` (a 30-minute shift), both rules, both transitions; a property test that `next` is strictly increasing and matches the expression. |
| **C2** store contract | `ScheduleId`, `Schedule`, `ScheduleTarget`, `MissedFirings`, `Firing`, `FiringOutcome`, `ScheduleStore`, `ScheduleError`, the in-memory store. | `ScheduleStoreContract` (new, `llm4s-agent-testkit`, hooks as `RunQueueContract`'s: `newStores(clock)`, `reopen`, `sibling`, `advanceStoreClock`): create, `Exists`, `TooMany` counted atomically, update by revision, tenant scoping, `due` by the store's clock under a skewed caller, `claim` won by exactly one of N contenders, a paused or edited schedule not claimed, first settle wins, unsettled firings listed after `redriveAfter`, reclaim never removing an unsettled firing or one with a thread to delete. |
| **C3** durable stores | SQLite and PostgreSQL stores, schema steps, `CheckpointStore.scheduleStore`. | The contract over SQLite in its module and PostgreSQL in `modules/it` (`@Docker`); migration tests; claim contention across connections. |
| **C4** scheduler | `RunScheduler`, `SchedulerSettings`, limits, the `llm4s:` key prefix refused by `RunSubmitter.submit`, metrics. | `ManualClock` and the in-memory stores: on-time firing, `FireOnce` and `Skip` after a simulated outage, each busy policy, a suspended thread, re-drive after a claimant dies before the submit and after it (one entry either way), an edited schedule re-driven (`SubmissionKeyReused` settles `Submitted`), `Abandoned` past half the ticket lifetime, pause and resume owing nothing, `minInterval`. |
| **C5** lifetime, webhooks, encryption, example | Fresh-thread deletion; `Schedule.webhook`; `EncryptingScheduleStore` if #2760 has merged; `modules/deploy-service` wiring; guide; the parity row. | Deletion waits for an open entry and survives the schedule's deletion; `ScheduleStoreContract` over the encrypting wrapper; **acceptance** (`modules/it`, `@Docker`): three instances over one PostgreSQL with skewed host clocks, an every-minute schedule for five minutes while one instance is killed between claim and submit; exactly one entry per fire time, each with one webhook delivery. |

## Prior art: LangGraph Platform

[Cron jobs](https://docs.langchain.com/langsmith/cron-jobs): `crons.create_for_thread` (each run on one thread) and
`crons.create` (a new thread per run); a cron expression in UTC, with no zone parameter; `on_run_completed` `"delete"`
(the default) or `"keep"` for stateless crons' threads; a warning to delete crons no longer needed, because they
keep spending. The page says nothing of missed runs, of firing once across servers, or of a busy thread.

llm4s differs by: a time zone per schedule with DST defined; a stated policy for missed firings; a stated guarantee
across instances; the queue's busy-thread policy on thread-bound schedules; a record of every fire time including
the skipped ones; fresh threads kept for a period rather than deleted at once; pause and resume.

## Breaking changes

None to existing callers but one: `RunSubmitter.submit` refuses a submission key beginning `llm4s:` (C4), with a
CHANGELOG line under `### Breaking changes` and a migration note. A store implemented outside this repository gains
nothing it must implement: `ScheduleStore` is a new SPI.
