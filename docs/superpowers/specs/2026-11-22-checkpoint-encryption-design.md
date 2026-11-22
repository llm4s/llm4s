# Checkpoint encryption at rest

Issue #2449, deferred by Decision 11 of the Stage 5 spec (`2026-10-20-stage-5-production-design.md`). This document
settles the issue's open questions; the code is `org.llm4s.agent.encryption` in `llm4s-agent`.

## Intent

An application that keeps agent threads in SQLite or PostgreSQL can have what its users said, and what its tools
returned, stored encrypted under keys the application holds, optionally one per tenant. Someone who reads the database,
a backup of it or its write-ahead log learns the shape of the work but none of its content; someone who can also write
to it cannot move content between threads or tenants, or replace it, without the next read failing.

### Non-goals

- Hiding metadata. Ids, statuses, timestamps and sizes stay in plain (listed below): the store works by them.
- Rollback protection. A writer to the database can put a thread back to an earlier state of itself, ciphertexts and
  all. No per-row AEAD prevents that.
- Replacing disk or database encryption (TDE), which still covers what stays plain here.
- A KMS client. The key source is an SPI; this repository ships only the in-memory one.

## What exists today (checked against the code, 2026-11-22)

- `Checkpointer` takes and returns typed values (`Commit`, `Checkpoint`, `PendingWrite`, `EventRecord`,
  `EffectRecord`), not bytes. Every application value inside them is a `ujson.Value` in a typed slot
  (`VersionedJson.value`, `RunEvent.Custom.payload`, an effect's outcome).
- The stores read the structure around those values and never the values: `Commit.releasesEffect` reads
  `snapshot.liveTasks`, `superstep` and `status`; the registry reads `tenantId`, `origin`, `status` and
  `snapshot.graphId`; `prune` reads each event's `checkpointId`. Neither store compares or indexes a payload.
- `MeteredCheckpointer` is already a wrapping `Checkpointer`, and reads one payload: the `agent.task_ended` custom
  event, for the child-task metrics.
- The run queue is a second SPI (`RunQueue`), reached as `store.runQueue`, not through `Checkpointer`. It stores
  `QueuedInput` (the user's query, or the answers to interrupts) and compares it: `submit` decides whether a retry
  under a `SubmissionKey` is the same submission by `Submission.sameAs`, which compares the input's JSON.
- An event's sequence number and a queue entry's id are issued by the store inside the write.

## Decisions

### 1. Encryption sits above the store, as two wrappers

`EncryptingCheckpointer(inner, keys)` and `EncryptingRunQueue(inner, keys)`. One implementation serves the in-memory,
SQLite and PostgreSQL stores and any store written later, with no schema change, and `CheckpointerContract` and
`RunQueueContract` run over them unchanged.

The issue asked for one wrapper. One is not enough: queue inputs never pass through `Checkpointer`, so a wrapping
checkpointer alone would leave every queued query in plain while claiming encryption at rest. The second wrapper is the
smallest design that closes that.

*Rejected: encryption inside each store* (a column cipher in `SqliteCheckpointer` and `PostgresCheckpointer`). It could
bind a ciphertext to its sequence number or entry id, which a wrapper cannot (below), but it is two implementations and
a schema change each, and a third-party store gets nothing.

*Rejected: a byte-level codec SPI the stores call.* The SPI is typed; adding a codec hook to every store is the
in-store design with more surface.

### 2. Values are encrypted in place; structure stays plain

Because the SPI is typed, the wrapper cannot hand the store an opaque blob: the store must still receive a `Checkpoint`
whose `liveTasks` and `superstep` are true. So each application value is replaced, where it sits, by an envelope - a
JSON object of one key - and everything around it is left alone.

Encrypted:

| Row | Fields |
|---|---|
| Checkpoint | every `snapshot.state` value; each pending task's `input` and replayed route payloads; each parked continuation's `question` and held route payloads |
| Pending write | each `Update`'s value; `Send` and `FanOut` payloads; the suspension's `question` (the interrupt as asked) |
| Event | a `RunEvent.Custom` payload |
| Effect record | the outcome value, and a verifiable intent's presumed outcome |
| Queue entry | a `Start` input; each answer of a `Resume` (the interrupt as answered) |

Plain - **what someone with read access to the database still learns**:

- Identity and order: thread, run, checkpoint, task, node, interrupt, entry and tenant ids; submission keys
  (idempotency keys); fencing and lease tokens; sequence numbers; every timestamp; parent and origin links, so the
  whole tree of child threads.
- Shape: the graph's id, version, fingerprint and declared shape; state key ids (which fields a thread has, not their
  values); which nodes ran and are pending; superstep counts; join and fan-out bookkeeping.
- Outcomes: checkpoint status and run outcome; a queue entry's policy, attempts, holders and outcome.
- Thread metadata (`ThreadMetadata`): the registry filters by it, so it is a queryable label, not a payload. Do not
  put content in it.
- Trace contexts.
- The built-in events in full: they carry ids, counts and token usage, and `TaskFailed`/`RunFailed` carry the
  failure's message. A queue entry's `FailureReason` message and a cancel's note are plain too.
- Digests: task lineages, effect keys, and the digests recorded beside a value (`resultDigest`, `resumeDigest`,
  `questionDigest`, a verifiable effect's state checks). They are unkeyed, so someone who can guess a value exactly
  can confirm the guess; they reveal nothing about a value that cannot be guessed.
- The length of every encrypted value, and the id of the key that encrypted it.

*Rejected: encrypting failure messages.* An event's could be reached; a queue entry's cannot (`close` and `cancel` name
an entry by lease or id, without the tenant a key is chosen by), and the two hold the same text. Encrypting one would
claim more than it delivers. A follow-up can carry the tenant on `Lease`.

*Rejected: encrypting the digests.* Lineages and effect keys are what the store releases and looks up effect records
by; hiding the others while those stay would hide nothing.

### 3. The cipher: AES-256-GCM from the JDK, a subkey per thread, no envelope encryption

- `AES/GCM/NoPadding` (`javax.crypto`), 256-bit key, 128-bit tag, and a fresh 96-bit nonce from `SecureRandom` for
  every value. No new dependency.
- Each value is sealed under a **subkey of its thread**: HKDF-Expand (RFC 5869, HMAC-SHA-256, one block) of the key
  with the thread id as context. The key from the key source never encrypts a payload directly.
- **Nonce bound.** With random 96-bit nonces, NIST SP 800-38D limits one key to 2^32 encryptions, which keeps the
  chance of a repeated nonce (which would reveal the XOR of two plaintexts and allow forgery under that key) below
  2^-32. A checkpoint with 20 state keys is 20 encryptions, so a store-wide key would reach 2^32 after some 10^8
  commits - within reach of a busy service. The per-thread subkey makes the bound per thread and per key id: 2^32
  values on one thread, which no thread approaches. This is why the subkey is worth its fifteen lines.
- **No envelope encryption** (a random data key per row, wrapped by a master key). It would cost a KMS call, or a
  cache with its own invalidation, per commit, and a second stored format, to buy what the subkey already gives (a
  bounded use of each AES key) and what the key-source SPI already allows: a KMS-backed source unwraps its data keys
  once and answers them from memory.

  *Rejected alternative, recorded:* the direct key with a documented 2^32 limit and "rotate before then". Simpler by
  those fifteen lines, but it turns a cryptographic limit into an operational duty nobody can meter.

Stored form of a value: `{"llm4s:enc": "1.<key id>.<base64url(nonce ‖ ciphertext ‖ tag)>"}`. `1` is the format.

### 4. Associated data: the row identity

Every seal authenticates the key id, the thread id and the value's place, length-prefixed:

| Row | Place |
|---|---|
| Checkpoint value | tenant, checkpoint id, then `state/<key>`, `task/<task id>/input`, `parked/<interrupt id>/question`, or a route's index |
| Pending write value | checkpoint id, task id, then the update's index and key, a route's index, or `question` |
| Custom event | run id, checkpoint id, task id, node id, event name and version |
| Effect record | operation, effect key (which is derived from the thread), `value` or `presumed` |
| Queue input | tenant, agent id, then `start` or `resume/<interrupt id>` |

So a ciphertext copied to another thread, another tenant's thread of the same id, another checkpoint, another state
key or another interrupt does not open, and changing a checkpoint's plain `tenantId` breaks its values.

Two things are **not** bound, because the store issues them inside the write, after the wrapper has sealed:

- an event's sequence number: two custom events of one run, task, name and version can be exchanged;
- a queue entry's id: the inputs of two entries of one thread, tenant and agent can be exchanged.

The tenant is bound where the row carries one (checkpoints, queue entries). Pending writes, events and effect records
carry none; they are bound to the thread, whose id is the store's key across tenants.

### 5. The key source

```scala
trait EncryptionKeySource:
  def current(tenantId: Option[TenantId]): Result[EncryptionKey]   // to write
  def key(keyId: KeyId): Result[EncryptionKey]                     // to read what it wrote
```

- `StaticKeySource(key)` is the shipped one: a default key, `withTenantKey` for a tenant's own, `withRetiredKey` for
  reading; `StaticKeySource.tenantsOnly` has no default, so a tenant with no key cannot write.
- **Rotation** is `current` answering a key with a new id. Every envelope names its key, so old rows stay readable
  while `key` still answers the old id, and new writes use the new key. Nothing is re-encrypted in place: a thread's
  old checkpoints age out by retention, and the old key can be dropped when they have.
- **Per tenant**: a commit is sealed with the key of its checkpoint's tenant; a commit with no checkpoint (a pending
  write, an effect, an event) with the key of the thread's latest checkpoint's tenant, read once per claim
  (`Checkpointer.owner`) and remembered. Reads choose the key by the id in the envelope, so `key` is not
  tenant-scoped: an event row names no tenant to scope it by. Isolation rests on each tenant's rows being sealed
  under that tenant's key and on the thread binding above.
- `EncryptionKey` holds its 32 bytes privately. Nothing reads them back; `toString` is `EncryptionKey(<id>, ***)`;
  no error carries key material, plaintext or ciphertext.

*Rejected: `key(tenantId, keyId)`.* Events, pending writes and effects are read by thread, with no tenant in hand; a
lookup that needs one would cost a second store read per event page.

### 6. Failure is a typed `Left`, and plaintext is refused

`EncryptionError`: `NoCurrentKey` (nothing is written), `UnknownKey`, `DecryptionFailed` (wrong key, changed, or
moved), `MalformedCiphertext`, `NotEncrypted`, `InvalidKey`, `EncryptionFailed`. A read never returns a stored value
in place of one it could not open, and a write never falls back to plaintext.

**Migration.** A store that already holds threads has plaintext rows. By default a plaintext value where a ciphertext
belongs is `NotEncrypted`: otherwise a writer to the database could swap any ciphertext for a plaintext of their
choosing. `EncryptionSettings().withPlaintextReads(true)` reads such values as they are, while still encrypting every
write; an application turns it on to switch encryption on, and off once retention has pruned the old rows. With it on,
a value that *is* an envelope must still open. There is no bulk re-encryption tool: history cannot be rewritten through
the SPI.

A value is taken for an envelope when it is an object of exactly the key `llm4s:enc` and a string. An application
value of that shape is safe under encryption (it is sealed like any other, and comes back as itself); in a legacy
plaintext row read during a migration it fails to open, typed.

**The queue.** An entry whose input cannot be opened fails the call that read it, after the store has acted: a `take`
has leased it. It is leased again as each lease expires, holding its thread's queue, until its key is supplied or it is
cancelled. Closing it automatically was rejected: a worker missing a key by misconfiguration would destroy good work.

### 7. Idempotent submission survives

Two encryptions of one input differ, so the store's `sameAs` reports every retry under a `SubmissionKey` as
`SubmissionKeyReused`. That error names the entry; `EncryptingRunQueue` reads it, opens it, compares the submissions
in plain, and answers the first ticket if they match. One extra read, on retries only.

*Rejected: deterministic encryption for queue inputs* (a nonce derived from the plaintext). It keeps `sameAs` working
in the store but reveals when two inputs are equal, breaks on rotation, and is a second construction to get right.

### 8. Cost, and `MeteredCheckpointer`

- Per value: one AES-GCM pass and one JSON serialisation; per commit or read, one HMAC for the subkey. Measured with
  the JDK's cipher alone (JDK 21, an Apple-silicon laptop, 2026-11-22): a subkey derivation, a seal and an open
  together take about 7 µs for a 100-byte value, 10 µs for 1 KB, 29 µs for 16 KB and 176 µs for 256 KB - a few
  microseconds fixed, then under a microsecond per kilobyte. A checkpoint of twenty 1 KB values costs about 0.1 ms to
  seal: small beside a database round trip. The JSON pass is not in those figures, and nothing was measured
  end to end through a store.
- Size: 28 bytes of nonce and tag, then Base64 (4/3), per value; a checkpoint grows by about a third.
- Extra store calls: one `owner` read when the first commit a wrapper sees under a claim holds a payload but no
  checkpoint (the tenant is then remembered for that claim; at most 10,000 threads are remembered); one `entry` read
  per retried keyed submission. None on reads.
- A commit with nothing to seal (built-in events only) asks for no key.
- **Stack `MeteredCheckpointer` outside**: `MeteredCheckpointer(EncryptingCheckpointer(store, keys), metrics)`. Its
  commit timer then includes encryption, and it reads `agent.task_ended` before it is sealed. Inside, it would time the
  store alone and silently count no child task as ended, because the payload it parses is a ciphertext. No new metric:
  rule 10 (no content, no ids in metrics) leaves nothing to add.

## Placement and tier

`org.llm4s.agent.encryption` in `llm4s-agent`: it needs only the JDK. Every type is `@Experimental`. No configuration
keys: an application builds its key source in code from wherever it keeps secrets. Binding a key to
`llm4s.agent.checkpointer` in `reference.conf` is left for when the config loader has a use for it.

## Tests

- `CheckpointerContract` and `RunQueueContract` over the wrappers on the in-memory store (`llm4s-agent-testkit`) and
  on SQLite (`llm4s-agent-checkpoint-sqlite`, with the file's and its write-ahead log's bytes checked for the
  plaintext); `CheckpointerContract` over `EncryptingCheckpointer` on PostgreSQL (`modules/it`, `@Docker`). The queue
  wrapper is not run against PostgreSQL: it adds no SQL, and its contract runs over the other two.
- Direct: no plaintext in what the store holds; a ciphertext moved to another thread, tenant, checkpoint or state key
  fails; a changed one fails; rotation reads old-key rows and writes new-key rows; a missing key fails typed and writes
  nothing; per-tenant keys isolate; plaintext is refused unless migrating; `MeteredCheckpointer` outside still counts
  child tasks; HKDF against RFC 5869's vector; no key material in `toString` or errors.

## Left for later

- Failure messages and cancel notes (decision 2), which needs the tenant on `Lease`.
- A `reference.conf` binding and a KMS-backed `EncryptionKeySource`.
- Binding an event to its sequence number and a queue input to its entry id, which needs the store's help.
