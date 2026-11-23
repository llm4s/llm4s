# Webhooks on run completion: a durable outbox beside the run queue, sliced

Issue #2447. Deferred by Stage 5's Decision 11 (`2026-10-20-stage-5-production-design.md`); the queue it builds on is
design records 4.55 to 4.57 (`docs/design/typed-agent-runtime/`). Scheduled runs (#2446,
`2026-11-22-scheduled-runs-design.md`) reuse this design. Nothing here is built yet: this document settles the issue's
open questions and slices the work.

## Open questions for the maintainer

Each has a recommendation, and the rest of the document assumes it. None could be settled from the code.

1. **Named endpoints or a URL on each submission?** LangGraph Platform takes a `webhook` URL per run. Recommended: a
   submission names an endpoint (`WebhookName`); the URL and its signing secrets come from a resolver the application
   supplies (`WebhookEndpoints`), so no URL and no secret is ever written to the store, and a run's submitter cannot
   aim the service at an address of its choosing. A server that lets tenants register URLs implements the resolver
   over its own table. If per-submission URLs are wanted instead, three things in this design change: the URL becomes
   a stored application payload that #2760's `EncryptingRunQueue` must seal (a URL can be a capability, as a Slack
   hook URL is), DNS rebinding must be closed before any delivery (`NetworkSecurity` documents that it is not,
   `core/safety/NetworkSecurity.scala`, "Name resolution"), and signing secrets still need a resolver.
2. **Does the delivery worker live in `llm4s-agent`?** Stage 5 rule 6 says "no JDBC or HTTP dependency in
   `llm4s-agent` or core". Recommended: yes, in `org.llm4s.agent.service`, over core's `Llm4sHttpClient`, which
   `llm4s-agent` already has through core (`build.sbt`, `lazy val agent`): no dependency is added, and no HTTP
   *server* enters the module. It would be the first main-source use of `org.llm4s.http` in `llm4s-agent`. The
   alternative is a new published module `llm4s-agent-webhooks` holding only the dispatcher, at the cost of the six
   "Adding a module" steps for about one class.
3. **The answer in the payload.** The issue asks for "answer or error", content opt-in. Recommended: version 1 carries
   no message content at all, only ids and the outcome; a receiver reads the answer from the service with the ids.
   Reason: a queue entry does not hold the answer (`EntryOutcome.Done(checkpointId, status)`, `QueueEntry.scala`), and
   `Agent` has no call that reads a thread's last answer without running it (`Agent.scala`), so an opt-in needs a new
   read API first. When that exists, the opt-in is per endpoint (the operator decides which receiver may get content),
   read from the checkpoint at delivery time and never copied into the outbox row (slice H5).
4. **Standard Webhooks signatures, mandatory.** Recommended: sign every delivery, in the
   [Standard Webhooks](https://www.standardwebhooks.com/) header format, so receivers verify with an existing library;
   an endpoint without a secret is refused when it is configured. The alternative is an llm4s-specific header, or
   optional signing as LangGraph has (it suggests a token in the query string).
5. **A webhook for an entry that closed before it ran.** LangGraph sends none for a run cancelled while pending.
   Recommended: send it. The caller asked to be told instead of polling, and an entry cancelled or superseded before
   it ran is otherwise silent for ever.

## Intent

A caller that submits a run through `RunSubmitter` learns how it ended by `await` or `follow`, each of which holds a
connection or polls (`RunSubmitter.scala`). A webhook tells it instead: when the queue entry closes, the service
POSTs a small signed JSON document to an endpoint the submission named, **at least once**, from whichever instance is
running a dispatcher, surviving a crash of any of them.

### Goals

1. A delivery is recorded in the transaction that closes the entry, so an entry never closes without its delivery
   and no delivery exists for an entry that did not close.
2. At-least-once delivery with backoff and a bounded give-up, from any instance, with one stable id per delivery so a
   receiver can discard repeats.
3. No secret and no message content in a store row, a log line, a metric or a trace attribute.
4. In-memory, SQLite and PostgreSQL all implement it and pass one contract suite (Stage 5 rules 7 and 8).

### Non-goals

- **The receiving side, endpoint registration over HTTP, per-request URLs**: the HTTP server project (Stage 5
  Decision 2; `docs/reference/agent-platform-parity.md`, "Scope and sources").
- **Webhooks for direct runs.** `agent.run` and `agent.start` answer their caller in process and make no queue entry,
  so there is no close to hang a delivery on and nobody waiting elsewhere. Child runs have no entry either.
- **Intermediate events.** One delivery per entry, at its close. A caller that wants progress follows the run.
- **Exactly-once delivery, ordered delivery, or a guarantee about the receiver's processing.**

### Anti-goals

- A close that succeeds while its delivery is lost, or a delivery whose close rolled back.
- A new dependency in `llm4s-agent`, or any HTTP server.
- A URL fetched because a run's submitter, or a model, wrote it.

## What exists today (checked against the code, 2026-11-22)

- **One place closes an entry in each store.** `PostgresRunQueue.closeRow` and `SqliteRunQueue.closeRow` are called by
  every path that closes: `close` under a lease, `cancel` of an entry with no attempt, `submit` of an `Interrupt`
  entry closing earlier unstarted ones, and `recordAttempt` finding the thread deleted. Each runs inside the store's
  write transaction (`store.writing(...)`), with the database's clock in Postgres (`store.clockNow(c)`).
  `InMemoryRunQueue.closeRow` is the same under its lock. So "the transaction that closes the entry" is one function
  per store.
- **Terminal states**: `EntryOutcome` is `Done(checkpointId, status)`, `Failed(reason)`, `Cancelled(reason)` or
  `Interrupted(by)` (`QueueEntry.scala`). `Done` covers a run that completed **or suspended**, and a thread that
  moved on past the entry's input (a direct `recover`; #2752's `Agent.abandon` will be another way), so `status` is
  not always `Completed`. The store keeps the outcome as JSON (`EntryOutcome.toJson`).
- **An entry carries its submitter's trace context** (`QueueEntry.trace`, design 4.58) and is reclaimed
  `ticketLifetime` after it closes, 24 hours by default (`RunQueueSettings.DefaultTicketLifetime`,
  `RunQueue.reclaim`).
- **`Submission.sameAs`** decides whether a retry under a held submission key is the same submission; it compares
  everything but `maxAttempts` and `traceContext` (`Submission.scala`).
- **HTTP client**: core's `Llm4sHttpClient` (`http/Llm4sHttpClient.scala`), JDK-backed, returning `Result`, a non-2xx
  status as a `Right`. It never sets a redirect policy, so the JDK default applies and it follows none.
  `HttpHeaders.retryAfter` (`private[llm4s]`) reads `Retry-After`. `Llm4sConfig.httpNetwork()` supplies proxy and
  TLS trust settings.
- **URL safety**: `NetworkSecurity.validateUrl(url, additionalBlockedHostnames, allowedProtocols)`
  (`core/safety/NetworkSecurity.scala`, `@Stable`) resolves the host and refuses private, loopback, link-local,
  metadata and other non-global addresses. The HTTP tool (`modules/agent-tools`, `HttpConfig`) uses it and
  re-validates each redirect hop. It does not cover DNS rebinding.
- **Secrets**: `org.llm4s.security.Secret` (core) prints as `***`.
- **Durable rows hold no message content** (Stage 5 rule 10; CLAUDE.md, Streaming Events). #2760 leaves queue failure
  messages and cancel notes in plain and seals inputs.
- **Schemas**: PostgreSQL is at version 5 (`PostgresSchema.Current`), SQLite at 11 (`SqliteSchema.Current`),
  hand-rolled steps, a newer schema refused. #2757 takes PostgreSQL version 6.
- **The example service** (`modules/deploy-service`, `AgentRoutes`) offers `await` and an SSE `follow`; no webhook.

## Placement

| What | Where |
|---|---|
| `WebhookName`, `Submission.webhook`, `QueueEntry.webhook`, the `WebhookOutbox` SPI, `WebhookDelivery`, in-memory outbox | `llm4s-agent`, `org.llm4s.agent.service` |
| `WebhookEndpoints`, `WebhookEndpoint`, `WebhookDispatcher`, `WebhookSettings`, signing, the config loader | `llm4s-agent`, `org.llm4s.agent.service` (open question 2) |
| `WebhookOutboxContract`, and the close-path cases added to `RunQueueContract` | `llm4s-agent-testkit` |
| SQLite and PostgreSQL outbox, one schema step each | `llm4s-agent-checkpoint-sqlite`, `llm4s-agent-checkpoint-postgres` |
| Example wiring and a receiver that verifies signatures | `modules/deploy-service` (unpublished) |
| Multi-instance delivery test | `modules/it`, `@Docker` tier |
| Endpoint registration API, per-tenant endpoint storage, the receiver | the HTTP server project, not this repository |

Core changes: none.

## Decisions

### 1. The library ships the outbox and the dispatcher; the server project ships endpoints over HTTP

**Decision.** The library ships three things: the outbox as part of the store contract, a delivery worker
(`WebhookDispatcher`), and a resolver SPI for endpoints (`WebhookEndpoints`) with one implementation read from
`application.conf`.

**Reasoning.** The guarantee is a property of the store: only the function that closes an entry can write the
delivery atomically, and Stage 5 placed everything that touches the store's guarantees in this repository ("the queue
and worker must move with the claim and fencing rules", Stage 5 spec, Intent). A completion hook alone cannot give
it: a hook runs in the worker after the close commits, so a crash between the two loses the webhook, and three of the
four close paths (cancel before run, interrupt before run, thread deleted) do not run in a worker at all. The
dispatcher is in the library because claiming, backoff and dead letters must agree with the outbox contract, and
because signing and address checks are easy to get wrong once per application.

**Rejected.** *Only a completion hook*, with the server project building the outbox: not atomic, as above.
*Only the outbox SPI*, each application writing its own sender: every user re-implements signing, retry and SSRF
checks. *The dispatcher inside `AgentWorker`*: a deployment may allow egress from some instances only, and a
dispatcher must keep delivering while no worker is running, so it is a class of its own, started beside a worker or
alone.

### 2. At least once, from an outbox row written by the close

**Decision.**

- When an entry whose submission named a webhook closes, `closeRow` inserts one delivery row in the same transaction
  (the same lock, in memory). One delivery per entry.
- A dispatcher **takes** due deliveries: pending, due by the store's clock, with no lease or an expired one; leased to
  a holder with a new token for a ttl. PostgreSQL takes skip-locked, as `take` and `reclaim` do for entries, so
  instances do not contend. A take records the attempt (`attempts + 1`) before any request is sent.
- The dispatcher POSTs and **settles** under its lease: `Delivered` on any 2xx; `Retry` on a transport error, a
  timeout, 408, 429 or 5xx, and when the resolver does not know the endpoint (configuration may be rolling out);
  `Dead` on any other status, 3xx included. A settle under a lease that is no longer current is refused, as
  `QueueError.LeaseLost` refuses one for an entry.
- **Backoff**: 10 seconds, doubling, capped at 1 hour, with up to 20% jitter; `Retry-After` is honoured up to the
  cap. The store computes the next attempt time from its own clock.
- **Give-up**: a delivery not delivered within `retryFor` of the entry's close (24 hours) is settled `Dead` with the
  last failure. Dead and delivered rows are kept `deliveryLifetime` (7 days) and then reclaimed; a dead one can be
  made pending again (`redeliver`).
- **Ordering**: none is promised, between entries or between a retry and a later entry's first attempt. The payload
  carries the entry id (issued in submission order), the close time and `closedAtSeq`, which order the events of a
  thread.
- **Idempotency**: the delivery's id is the same on every attempt and after `redeliver`, in the `webhook-id` header
  and the payload's `id`.

**Reasoning.** A crash after the POST and before the settle sends the delivery again when the lease expires: that is
the at-least-once, and the id is what makes it harmless. Age, not an attempt count, bounds the retries because what an
operator reasons about is "how long may a receiver be down". An unexpected 4xx is not retried because the request
will not change.

**Rejected.** *Deliver from the worker, record after*: lost on a crash. *Build the payload at delivery time from the
entry*: the entry is reclaimed after the ticket lifetime, so a delivery would have to pin it or shorten its own
retries to fit. The row copies what the payload needs instead. *Exactly once*: impossible across an HTTP call.

### 3. Every close fires; only queued runs have webhooks

**Decision.** A delivery is written for every `EntryOutcome`: `done` (completed, **suspended**, or passed over),
`failed` (run failed, attempts exhausted, unrunnable), `cancelled` (requested, thread deleted) and `interrupted`,
including entries that closed before they ran (open question 5). A suspended run is reported as what it is:
`outcome.status` is `Suspended`, and the resume that answers it is its own submission, with its own webhook if it
names one. Direct runs and child runs have none (Non-goals).

**Reasoning.** The trigger is "the entry closed", which is the one event every path shares and the one the store
makes atomic. Filtering by state at the source would add an option for something a receiver does with one `if`.

**Differs from LangGraph**, which reports a run paused at an interrupt as `success` and leaves the receiver to read
the thread's state to find out, and sends nothing for a run cancelled while pending.

**Rejected.** *A per-submission list of states to fire on*: speculative. *A webhook when a thread suspends without
its entry closing*: there is no such state; a suspended run's entry is closed `Done`.

### 4. Payload: version 1, ids and outcome, no content

```json
{
  "type": "llm4s.run.closed",
  "version": 1,
  "id": "8841",
  "timestamp": "2026-11-22T10:15:30.123456Z",
  "data": {
    "entryId": 912,
    "threadId": "support-4411",
    "agentId": "assistant",
    "tenantId": "acme",
    "submissionKey": "req-77",
    "submittedAt": "2026-11-22T10:15:02.000100Z",
    "runIds": ["0d0c..."],
    "outcome": { "state": "done", "checkpointId": "c-19", "status": "Completed" },
    "closedAtSeq": 42
  }
}
```

**Decision.** `timestamp` is the close time by the store's clock. `outcome` is the JSON the store already keeps
(`EntryOutcome.toJson`): `state`, and `checkpointId` and `status`, or `reason` with `attempts`, `message` or `note`,
or `by`. `runIds` are the entry's attempts, oldest first. `tenantId` and `submissionKey` are absent when the entry
has none. Adding a field does not change `version`; receivers ignore fields they do not know. A change that removes or
re-types a field is version 2, chosen per endpoint when it exists.

No message content (open question 3). Failure messages and cancel notes are included, as `RunSubmitter.entry`
already returns them and #2760 leaves them in plain; each is cut to 2,000 characters in the payload, so a body stays
under 16 KiB. The payload is serialised once, when the row is written, so every attempt sends the same bytes.

**Reasoning.** A thin event needs no opt-in, cannot leak a conversation through a misconfigured receiver, and keeps
the outbox row free of anything #2760 would have to seal. Making `EntryOutcome.toJson` a wire format is deliberate:
one encoding of an outcome, pinned by a golden test in slice H1.

**Rejected.** *LangGraph's shape* (the run object with `values`, the thread's state): it puts content in every
webhook. *Storing the answer in the row behind a flag*: content at rest in a second place, and the row would need
#2760's treatment.

### 5. Signing, addresses, redirects, timeouts, TLS

**Decision.**

- **Signature**: HMAC-SHA-256 (`javax.crypto.Mac`) over `<id>.<timestamp>.<body>`, base64, sent as
  `webhook-signature: v1,<signature>` with `webhook-id` and `webhook-timestamp` (seconds), as Standard Webhooks
  defines. The timestamp is the store's time at the take that leased the attempt, so each attempt is signed afresh.
  Receivers should refuse a timestamp more than 5 minutes from their clock and remember ids for that long.
- **Rotation**: an endpoint holds one or more secrets; every delivery carries one signature per secret, space
  separated. Rotate by adding the new secret, updating the receiver, then removing the old one.
- **Where secrets come from**: the resolver. The shipped one reads `llm4s.agent.webhooks.endpoints.<name>` from the
  application's `Config` (`url`, `secrets`, through `${?VAR}` or a file, as provider keys are), into
  `org.llm4s.security.Secret`. A secret is at least 24 bytes. No row, log line, error or metric holds one.
- **Addresses**: `https` only, and `NetworkSecurity.validateUrl(url, allowedProtocols = Set("https"))` before every
  attempt. An endpoint marked `allowPrivateAddresses` skips the address check and may use `http`: the receiver of a
  library user is often its own service inside the cluster. URLs with user-info are refused when configured. The
  rebinding gap `NetworkSecurity` documents is accepted because the operator, not a submitter, chose the host (open
  question 1).
- **Redirects** are not followed: a 3xx settles `Dead` with the status. `Llm4sHttpClient` follows none today; the
  dispatcher's tests pin it.
- **Timeouts**: 10 seconds per attempt (connect, send, response headers). The response body is not read. The lease
  ttl is the attempt timeout plus 20 seconds, so no renewal exists.
- **TLS**: the client is built from `Llm4sConfig.httpNetwork()`, so the application's trust store and proxy apply.
  There is no per-endpoint trust setting.
- A `traceparent` header continues the entry's trace (`QueueEntry.trace`), when it has one.

**Rejected.** *A token in the URL's query*, LangGraph's advice: a secret in a URL reaches access logs. *An
`Llm4s-Signature` header of our own*: receivers would need our verifier. *Following redirects with re-validation*, as
the HTTP tool can: a receiver that moves should be reconfigured, and each hop is another chance to be sent somewhere
private.

### 6. Declared per submission, by name; resolved per tenant; validated at submit

**Decision.** `Submission.webhook: Option[WebhookName]`. `RunSubmitter`, when built with a resolver, refuses a
submission whose name the resolver does not know for the submission's tenant (`ValidationError("webhook", ...)`), and
any submission that names a webhook when it has no resolver. The resolver is asked with the tenant, so a tenant cannot
name another's endpoint. `webhook` is part of `Submission.sameAs`: a retry under a held submission key with a
different webhook is `SubmissionKeyReused`.

**Reasoning.** Per submission is the primitive: a per-agent or per-tenant default is one line in the caller that
builds submissions, and the resolver already scopes by tenant. Validation at submit catches a typo while the caller
is still there to be told.

**Rejected.** *Per agent, on `AgentBuilder`*: an `Agent` also runs directly, where there is no webhook. *Per tenant
in the store*: an endpoints table and its CRUD belong to the server project.

### 7. Schema, retention, tenancy, metrics, tracing

- **Schema**: one step per store. Queue entries gain `webhook text`. A new table
  `checkpoint_webhook_deliveries`: `delivery_id` (a sequence), `entry_id`, `thread_id`, `agent_id`, `tenant_id`,
  `webhook`, `payload_json`, `trace_parent`, `closed_at`, `state` (`pending`, `delivered`, `dead`), `attempt_count`,
  `next_attempt_at`, `last_failure`, `lease_holder`, `lease_token`, `lease_expires_at`, `settled_at`; indexed on
  `(next_attempt_at, delivery_id) WHERE state = 'pending'`, on `entry_id`, and on `(settled_at, delivery_id) WHERE
  state <> 'pending'`. SQLite goes 11 to 12. PostgreSQL takes the next free version when the slice merges: 6 today,
  7 once #2757 has merged. Processes sharing a database upgrade together (Stage 5 Decision 9).
- **Retention**: a pending delivery is never reclaimed. `WebhookOutbox.reclaim(lifetime, limit)` removes settled rows
  older than `deliveryLifetime`, oldest first; the dispatcher calls it on its own interval. A delivery outlives its
  entry: the row is self-contained. `deleteThread` does not remove deliveries; the `cancelled` (`threadDeleted`)
  delivery is how the caller learns of it.
- **Tenancy** (Stage 5 rule 9): `delivery` and `redeliver` answer for the caller's tenant only. `take`, `depth` and
  `reclaim` are the operator's, store-wide, as `RunQueue.openRuns` and `reclaim` are.
- **Encryption (#2760)**: a delivery row holds ids, a webhook name, times and the outcome JSON, all of which #2760
  already keeps in plain on the entry, so `EncryptingRunQueue` passes `webhook` through and no outbox wrapper is
  needed. That is a consequence of open questions 1 and 3; either answered the other way adds a sealed column.
- **Metrics** (names follow design 4.59's `llm4s.agent.queue.*`): `llm4s.agent.webhook.attempts` (counter; result
  `delivered`, `retry`, `dead`; a status class), `llm4s.agent.webhook.attempt.duration` (histogram),
  `llm4s.agent.webhook.lag` (histogram, close to delivered), `llm4s.agent.webhook.pending` and
  `llm4s.agent.webhook.oldest.seconds` (gauges, sampled from `depth`). Labelled by agent id, never by endpoint name,
  URL or tenant.
- **Tracing**: no new `TraceEvent`. Attempts are logged at DEBUG with the delivery id, endpoint name and status,
  never the URL.

## Public API

All in `org.llm4s.agent.service`, all `@Experimental`. Existing types named here are the real ones.

```scala
opaque type WebhookName = String            // 1 to 64 characters of A-Z a-z 0-9 _ . : -
object WebhookName:
  def of(value: String): Result[WebhookName]

opaque type DeliveryId = Long
object DeliveryId:
  def of(value: Long): Result[DeliveryId]

// Submission, Submission.apply/of and RunSubmitter.submit gain `webhook: Option[WebhookName] = None`,
// Submission gains withWebhook(n: WebhookName) and withWebhook(n: Option[WebhookName]);
// QueueEntry gains `webhook: Option[WebhookName]`. One `apply` each: no forwarding overload before the baseline.

enum DeliveryState:
  case Pending
  case Delivered
  case Dead(reason: String)

enum DeliveryResult:
  case Delivered
  case Retry(after: FiniteDuration, failure: String)
  case Dead(reason: String)

final case class WebhookDelivery private (   // growth-prone: apply + with*
  id: DeliveryId, entryId: EntryId, threadId: ThreadId, agentId: AgentId, tenantId: Option[TenantId],
  webhook: WebhookName, payload: ujson.Value, trace: Option[TraceContext], closedAt: Instant,
  state: DeliveryState, attempts: Int, nextAttemptAt: Option[Instant], lastFailure: Option[String],
  settledAt: Option[Instant])

final case class DeliveryLease(deliveryId: DeliveryId, token: LeaseToken, expiresAt: Instant)
final case class LeasedDelivery(delivery: WebhookDelivery, lease: DeliveryLease, takenAt: Instant)

trait WebhookOutbox:
  def take(holder: String, ttl: FiniteDuration, limit: Int): Result[Vector[LeasedDelivery]]
  def settle(lease: DeliveryLease, result: DeliveryResult): Result[WebhookDelivery]
  def delivery(tenantId: Option[TenantId], entryId: EntryId): Result[Option[WebhookDelivery]]
  def redeliver(tenantId: Option[TenantId], deliveryId: DeliveryId): Result[WebhookDelivery]
  def depth: Result[QueueDepth]
  def reclaim(lifetime: FiniteDuration, limit: Int): Result[Int]

final case class WebhookEndpoint private (   // growth-prone
  url: java.net.URI, secrets: Vector[Secret], allowPrivateAddresses: Boolean)
object WebhookEndpoint:
  def of(url: String, secret: Secret, allowPrivateAddresses: Boolean = false): Result[WebhookEndpoint]

trait WebhookEndpoints:
  def endpoint(tenantId: Option[TenantId], name: WebhookName): Result[Option[WebhookEndpoint]]
object WebhookEndpoints:
  def of(endpoints: Map[WebhookName, WebhookEndpoint]): WebhookEndpoints   // the same for every tenant
  def from(config: com.typesafe.config.Config): Result[WebhookEndpoints]   // llm4s.agent.webhooks.endpoints

final case class WebhookSettings private (   // growth-prone
  concurrency: Int, pollInterval: FiniteDuration, attemptTimeout: FiniteDuration, initialBackoff: FiniteDuration,
  maxBackoff: FiniteDuration, retryFor: FiniteDuration, deliveryLifetime: FiniteDuration)

final class WebhookDispatcher(
  outbox: WebhookOutbox, endpoints: WebhookEndpoints, http: Llm4sHttpClient,
  settings: WebhookSettings = WebhookSettings.default, metrics: MetricsCollector = MetricsCollector.noop):
  def start(): Unit
  def shutdown(): Unit
```

`RunSubmitter` gains a constructor parameter `webhooks: Option[WebhookEndpoints] = None`. The stores expose the
outbox as they expose the queue: `InMemoryCheckpointer.webhookOutbox`, `SqliteCheckpointer.webhookOutbox`,
`PostgresCheckpointer.webhookOutbox`, and `CheckpointStore.webhookOutbox: Result[WebhookOutbox]`. `WebhookEndpoints.from`
is the one signature that names a third-party type, as the existing config loaders do. `QueueError` gains two cases:
`DeliveryLeaseLost(deliveryId: DeliveryId, token: LeaseToken)` and `DeliveryNotFound(deliveryId: DeliveryId)`, the
second also the answer for another tenant's delivery.

## Slice plan

Each slice is one PR. Breaking slices add their CHANGELOG line and a migration note.

| Slice | Delivers | Tested by |
|---|---|---|
| **H1** outbox contract | `WebhookName`, `Submission.webhook` (in `sameAs`), `QueueEntry.webhook`, `WebhookOutbox`, `WebhookDelivery`, the payload encoding, the in-memory outbox. Breaking: `Submission.apply`. | `WebhookOutboxContract` (new, `llm4s-agent-testkit`): take, lease expiry and takeover, settle under a lost lease, retry timing by the store's clock, give-up, `redeliver`, tenant scoping, reclaim. `RunQueueContract` gains: each of the four close paths writes exactly one delivery, none without a webhook, a keyed retry with another webhook is refused. A golden test pins the payload. |
| **H2** durable stores | SQLite and PostgreSQL outboxes and schema steps; `CheckpointStore.webhookOutbox`. | Both contracts over SQLite in its module and over PostgreSQL in `modules/it` (`@Docker`); a migration test from the previous version; a test that a failed delivery insert rolls the close back; two contending takers get disjoint deliveries. |
| **H3** dispatcher | `WebhookEndpoint`, `WebhookEndpoints` and its config loader, signing, `WebhookSettings`, `WebhookDispatcher`, metrics. | A scripted `Llm4sHttpClient` and `ManualClock`: every status class, `Retry-After`, backoff and cap, give-up, a secret never in an error or log, signatures against Standard Webhooks' published test vectors, two secrets, private address refused and allowed, redirect not followed. |
| **H4** submit-time validation and the example | `RunSubmitter(webhooks = ...)`; `modules/deploy-service` wires a dispatcher and one configured endpoint; guide section; the parity row. | Unit tests for the refusals; **acceptance** (`modules/it`, `@Docker`): three instances over one PostgreSQL, a local receiver that verifies signatures; kill the instance that holds a delivery lease mid-request; the receiver sees every closed entry at least once and no id that is not an entry's. |
| **H5** answer opt-in (only if open question 3 is answered yes) | A read of a thread's last answer on `Agent`; `WebhookEndpoint.withAnswer`; payload field `data.answer`, read at delivery. | Contract that the outbox row never holds it; size limit omits it with `answerOmitted`. |

If #2760 has merged before H1, H1 also passes `webhook` through `EncryptingRunQueue` and adds it to that wrapper's
plain comparison on `SubmissionKeyReused`.

## Prior art: LangGraph Platform

[Webhooks](https://docs.langchain.com/langsmith/use-webhooks): a `webhook` URL on run and cron creation; a POST of
the run object including `values`; statuses `success` (also for a run paused at an interrupt), `error`, `timeout`,
`interrupted`, `rollback`; no webhook for a run cancelled while pending; no delivery guarantee or retry stated; no
signature (a query-string token is suggested; static headers from `langgraph.json`); URL policy by
`webhooks.url.allowed_domains`, `require_https`, `allowed_ports`, `disable_loopback`.

llm4s differs by: a stated at-least-once guarantee from a transactional outbox; a signature on every delivery; no
content in the payload; a suspended run reported as suspended; a delivery for entries closed before they ran; named
endpoints rather than a URL per run.

## Breaking changes

`Submission.apply`/`of` and `RunSubmitter.submit` gain a parameter (source-compatible for named arguments);
`QueueEntry` gains a field; a `RunQueue` implemented outside this repository must write deliveries at close. One line
under `### Breaking changes` and a section in `docs/reference/migration.md`, with H1.
