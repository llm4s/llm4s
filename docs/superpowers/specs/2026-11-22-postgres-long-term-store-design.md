# A shared Postgres `LongTermStore`

[#2453](https://github.com/llm4s/llm4s/issues/2453). Deferred by Decision 11 of the Stage 5 spec
(`2026-10-20-stage-5-production-design.md`). The SPI is Stage 3's (`docs/design/typed-agent-runtime/4.25-stage-3-slice-c3-backend-spis.md`);
its replay rules are C4's (`4.24-stage-3-slice-c4-replay-of-external-effects.md`). This document settles the issue's
open questions; the code is `PostgresCheckpointer.longTermStore` in `llm4s-agent-checkpoint-postgres` and
`LongTermStoreContract` in `llm4s-agent-testkit`.

## Intent

An agent service on several machines (Stage 5) keeps its threads in one PostgreSQL database, but the harness's
long-term store - `/memories/` files (`StoreFileBackend`) and anything else kept by `(Namespace, key)` - had only
`InMemoryLongTermStore`, which is one process's. A file an agent wrote on one machine was not there on the next.
This adds the Postgres store, in the database the service already has, and the contract suite every store passes.

### What the SPI is, and is not

`LongTermStore` is a bounded key-value store of JSON items with one mutation primitive, `modify`: a read-modify-write
in the store's own transaction, serialisable within its namespace, fenced by the run's claim token and recorded under
the effect's key (`AppliedKeys`). `get`, `list` (by key prefix) and `search(ns, query: String, limit)` read it.

`search` takes text. The SPI has no embedding, no vector and no score, and says only that "each store documents how
it matches". Semantic memory does not use this SPI at all: design 4.31 built it on `llm4s-memory`'s
`TransactionalMemoryStore`, whose Postgres store (`llm4s-memory-postgres`, pgvector) already exists.

## Decisions

1. **Placement: inside `llm4s-agent-checkpoint-postgres`, as `PostgresCheckpointer.longTermStore(limits)`.** The store
   needs what that module already has and keeps `private[postgres]`: the bounded call (`statement_timeout`, the
   watchdog, discarded connections), the migrations under an advisory lock, the database's clock, the pool, the JDBC
   driver and HikariCP. `PostgresRunQueue` set the pattern: a second store over the same schema, reached from the
   checkpointer, closed with it. It also lets `deleteThread` and the sweep treat the store's per-thread rows in their
   own transactions (Decision 4), which a store elsewhere could only do eventually.
   - *Rejected: a new module beside it* (`llm4s-agent-store-postgres`). It would either copy the call machinery or
     make it public API, to save a dependency nobody is spared: a service with this store has the checkpointer. The
     "Adding a module" checklist (coverage floor, codecov flag, CI leg, docs project, pre-commit, bom) buys nothing here.
   - *Rejected: `llm4s-memory-postgres`.* That module implements `MemoryStore` and depends on core only;
     `LongTermStore` is `llm4s-agent`'s, so the store would drag the agent runtime into a memory module. It is also
     where `PostgresVectorHelpers` matters, and this store has no vectors (Decision 5).

2. **Schema: the checkpoint store's schema and its one version number.** Version 6 adds three tables and changes no
   existing one: `checkpoint_store_items`, `checkpoint_store_applied`, `checkpoint_store_fences`. One migration story:
   opening the checkpointer migrates everything, once, under the schema's advisory lock; an older build refuses the
   newer schema as it always has (Stage 5 Decision 9: upgrade every process together).
   - *Rejected: a version table of its own.* Two version numbers in one schema make "which build can open this" a
     matrix, for tables that are created together and dropped together.
   - Items are keyed by `(namespace_id, item_key)`. `namespace_id` is a SHA-256 of the namespace (tenant and path,
     each length-prefixed): a namespace may be 1024 characters, more than a B-tree row holds beside a 1024-byte key.
     The row also carries `namespace` (the JSON array `Namespace.render` gives) and `tenant_key` (as
     `checkpoint_threads` writes it) so that the table can be read, and a tenant's rows found, without the digest.
   - Keys are `text COLLATE "C"`: compared and ordered by their UTF-8 bytes whatever the database's collation.
     Values and outcomes are `text` holding ASCII JSON (every character past ASCII escaped), so a string's NUL or
     unpaired surrogate reads back exactly; `jsonb` would refuse both and reorder object fields.

3. **Tenancy and namespaces: exactly the SPI's.** The tenant is part of the namespace, the namespace part of the
   primary key, and no call reaches across namespaces (rule 8). `StoreLimits` bound one namespace, checked in the
   `modify` transaction against what the database holds now, counted by the code the in-memory store counts with
   (`item_bytes` is stored, so the count is the same on every machine). Each instance enforces the limits it was
   given; a service gives every machine the same.
   - Serialisable within a namespace by a transaction-scoped advisory lock on the namespace, taken before it is read:
     the transaction stays read-committed, so there is no serialisation failure to retry.
     *Rejected: `SERIALIZABLE`*, which turns contention into errors the SPI has no way to report as retryable.
   - Offboarding a tenant is SQL on `tenant_key`; the SPI has no cross-namespace delete and this adds none.
   - PostgreSQL `text` holds neither NUL nor an unpaired surrogate. A key or a tenant with either is refused by
     `modify` (`BackendRefused`) and reads as absent. The in-memory store keeps a NUL in a key; a `VPath` never has one.

4. **Retention: items belong to no thread and never expire; per-thread rows go with the thread.**
   - *Items.* `deleteThread`, `prune` and the Stage 5 sweep never remove an item, and nothing expires by age:
     long-term memory outlives threads by definition, and the SPI has no TTL. *Rejected: a `maxAge` on the store* -
     new SPI surface with no consumer; an application that wants one deletes through `modify`, or by SQL on
     `updated_at`.
   - *Applied keys* live until C4's guard forgets them, never by age (the SPI's rule). A thread deleted with an
     effect half-recorded would leak its key for ever, so `deleteThread` removes the thread's applied keys in its own
     transaction: the thread's effect records go in that same transaction, and without one nothing can replay the key.
   - *Fences* (the newest claim token seen per thread) are "kept as long as the store keeps that thread's data"
     (C4). Here that is the thread's row: the fence goes when the row does - at once when `deleteThread` leaves no
     tombstone, otherwise when a sweep reclaims the tombstone (design 4.53) - so a stale run of a deleted thread is
     still refused for as long as the checkpoint store itself still remembers the thread.
     *Rejected: dropping the fence at `deleteThread`*, which would let a run taken over before the delete write once more.

5. **Semantic search: not in this change.** `search` is the in-memory store's keyword match - the query,
   case-insensitively, as a substring of the key or of any string in the value, in key order - done in the JVM over
   the namespace read a page at a time, so the two stores match identically (Java's case folding, not a collation's)
   and a namespace's bound bounds the read. An embedding column would need the SPI to carry an embedder, a vector or a
   score, and none of those is in `LongTermStore`; inventing them here would be speculative API. Semantic memory
   across machines is `SemanticMemoryMiddleware` over `PostgresMemoryStore` today. The parity row says so.
   - *Rejected: `ILIKE` or full-text search in SQL.* Either matches differently from the in-memory store (collation,
     stemming), so the contract could not hold both to one rule.

6. **A contract suite, `LongTermStoreContract`, in `llm4s-agent-testkit`.** What the `LongTermStore` Scaladoc states,
   as cases: items read back exactly; literal prefixes, key order, limits; namespaces apart; every bound, exactly at
   its limit and in UTF-8; `modify` against a `StoreView`; refused changes recording nothing; applied keys answered
   without running the change; fencing for `modify` and `forget`, the token kept even when the change refused; a
   change that calls back refused; and concurrent writers, through two instances when the store has them (`sibling`).
   `InMemoryLongTermStore` passes it in the testkit's own tests; the Postgres store passes it in `modules/it`
   (`@Docker`), with `ReplayContract` (a `CounterSubject` the testkit now ships) and `FileBackendContract` over
   `StoreFileBackend` beside it. The checks both stores make (`check`, `within`, the matcher, the refusals) are one
   `private[llm4s]` implementation in `LongTermStore`'s companion.

## Out of scope

- A HOCON block for the store: it is reached from a `PostgresCheckpointer` in code, with `StoreLimits`.
- A SQLite `LongTermStore`. The contract is there for it.
- Vector search over `LongTermStore` items: it starts with an SPI change, as its own issue.

## Known differences from the in-memory store

- Key order is by UTF-8 bytes (code points); the in-memory store's is by UTF-16 units. They differ only between a
  supplementary character and one in U+E000..U+FFFF.
- NUL in a key or tenant (Decision 3).
- `StoreItem.updated` is the database's clock, to the microsecond.
- A `change` runs inside a database transaction: it must not block, and must return within the call timeout.
