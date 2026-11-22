package org.llm4s.agent.encryption

import org.llm4s.agent.graph.*
import org.llm4s.annotation.Experimental
import org.llm4s.types.Result

import scala.concurrent.duration.FiniteDuration

/**
 * A [[org.llm4s.agent.graph.Checkpointer Checkpointer]] that encrypts what `inner` stores, with keys from `keys`
 * (#2449; spec `docs/superpowers/specs/2026-11-22-checkpoint-encryption-design.md`). It works over any store - it is
 * `CheckpointerContract` that checks it - so give it to every runtime and worker in place of the store, and wrap the
 * store's run queue with [[EncryptingRunQueue]] over the same keys:
 *
 * {{{
 * for
 *   store <- CheckpointerConfigLoader.open()            // llm4s.agent.checkpointer
 *   queue <- store.runQueue
 *   key   <- EncryptionKey.fromBase64(KeyId("2026-11"), keyFromYourSecretStore)
 *   keys         = StaticKeySource(key)
 *   checkpointer = MeteredCheckpointer(EncryptingCheckpointer(store.checkpointer, keys), metrics)
 *   agent  <- Agent.builder("assistant", client).withRuntime(new GraphRuntime(checkpointer)).build()
 *   worker <- AgentWorker(EncryptingRunQueue(queue, keys), checkpointer, Seq(QueuedAgent(agent)), metrics = metrics)
 * yield worker
 * }}}
 *
 * '''What is encrypted''' - every value the application's codecs wrote: a checkpoint's state values, its pending
 * tasks' inputs and replayed routes, and its parked questions; a pending write's updates, route payloads and
 * question; a custom event's payload; and an effect record's outcome and presumed outcome.
 *
 * '''What stays in plain''', because the store orders, fences, lists or prunes by it: thread, run, checkpoint, task,
 * node, state-key, interrupt and tenant ids; statuses, outcomes and timestamps; the graph's id, version, fingerprint
 * and shape; fencing tokens and sequence numbers; thread metadata; trace contexts; lineages, effect keys and the
 * digests recorded beside a value (an unkeyed digest lets someone who can guess a value confirm the guess); the
 * built-in events, which hold ids, counts, token usage and failure messages; and the size of every encrypted value.
 *
 * Each value is sealed on its own with AES-256-GCM under a subkey of its thread, bound to its key id, its thread and
 * its place - for a checkpoint's values, the tenant and checkpoint id too - so one moved to another thread, tenant,
 * checkpoint or field does not open. A read that cannot open a value is a `Left` ([[EncryptionError]]): it never
 * returns the stored value in its place. A thread's key is its tenant's ([[EncryptionKeySource.current]]), taken from
 * the commit's checkpoint, or for a commit with none from the thread's latest one.
 *
 * Stack [[org.llm4s.agent.metrics.MeteredCheckpointer MeteredCheckpointer]] outside it, as above: it then times
 * commits with their encryption, and reads the child-task events it counts before they are sealed.
 */
@Experimental
final class EncryptingCheckpointer private (
  val inner: Checkpointer,
  keys: EncryptionKeySource,
  settings: EncryptionSettings
) extends Checkpointer:
  import EncryptingCheckpointer.*
  import ThreadCipher.traverse

  /** Each thread's tenant, by the claim it was read under: what a commit with no checkpoint is keyed by. */
  private val tenants = new java.util.LinkedHashMap[String, (Long, Option[String])](16, 0.75f, false):
    override def removeEldestEntry(eldest: java.util.Map.Entry[String, (Long, Option[String])]): Boolean =
      size() > TenantCacheLimit

  private def remember(threadId: ThreadId, token: FencingToken, tenant: Option[String]): Unit =
    tenants.synchronized(tenants.put(threadId.value, token.value -> tenant)): Unit

  private def forget(threadId: ThreadId): Unit = tenants.synchronized(tenants.remove(threadId.value)): Unit

  /**
   * The tenant whose key `commit` is sealed with: its checkpoint's, or the thread's latest checkpoint's. A thread's
   * tenant cannot change under a claim except by that claim's own commits, so it is read once per claim.
   */
  private def tenantOf(threadId: ThreadId, commit: Commit): Result[Option[String]] =
    commit.checkpoint match
      case Some(checkpoint) => Right(checkpoint.tenantId)
      case None =>
        tenants.synchronized(Option(tenants.get(threadId.value))).filter(_._1 == commit.token.value) match
          case Some((_, tenant)) => Right(tenant)
          case None =>
            inner.owner(threadId).map { owner =>
              owner.foreach(o => remember(threadId, commit.token, o.tenantId))
              owner.flatMap(_.tenantId)
            }

  private def cipher(threadId: ThreadId) = new ThreadCipher(threadId, keys, settings)

  // ---- writes ----

  def commit(threadId: ThreadId, commit: Commit): Result[Vector[EventRecord]] =
    if !holdsPayload(commit) then inner.commit(threadId, commit)
    else
      val thread = cipher(threadId)
      for
        tenant <- tenantOf(threadId, commit)
        key    <- keys.current(tenant.map(TenantId(_)))
        seal = (place: Vector[String], value: ujson.Value) => thread.seal(key, place, value)
        checkpoint <- commit.checkpoint.fold[Result[Option[Checkpoint]]](Right(None))(c =>
          checkpointed(seal, c).map(Some(_))
        )
        writes <- traverse(commit.pendingWrites)(written(seal, _))
        events <- traverse(commit.events)(d =>
          evented(seal, d.runId, d.checkpointId, d.taskId, d.nodeId, d.event).map(e => d.copy(event = e))
        )
        effects <- traverse(commit.effects)(effected(seal, _))
        records <- inner.commit(
          threadId,
          commit.withCheckpoint(checkpoint).withPendingWrites(writes).withEvents(events).withEffects(effects)
        )
        _ = commit.checkpoint.foreach(c => remember(threadId, commit.token, c.tenantId))
        opened <- traverse(records)(record(thread.open, _))
      yield opened

  def release(threadId: ThreadId, token: FencingToken): Result[Unit] =
    forget(threadId)
    inner.release(threadId, token)

  def deleteThread(threadId: ThreadId, token: FencingToken, fence: Option[(ThreadId, FencingToken)]): Result[Unit] =
    forget(threadId)
    inner.deleteThread(threadId, token, fence)

  // ---- reads ----

  def latest(threadId: ThreadId): Result[Option[StoredCheckpoint]] =
    inner.latest(threadId).flatMap {
      case None => Right(None)
      case Some(stored) =>
        val open = cipher(threadId).open
        for
          checkpoint <- checkpointed(open, stored.checkpoint)
          writes     <- traverse(stored.pendingWrites)(written(open, _))
        yield Some(StoredCheckpoint(checkpoint, writes))
    }

  def history(threadId: ThreadId, before: Option[String], limit: Int): Result[Vector[Checkpoint]] =
    inner.history(threadId, before, limit).flatMap { found =>
      val open = cipher(threadId).open
      traverse(found)(checkpointed(open, _))
    }

  def checkpoint(threadId: ThreadId, checkpointId: String): Result[Option[Checkpoint]] =
    inner.checkpoint(threadId, checkpointId).flatMap(opened(threadId, _))

  def latestOfRuns(threadId: ThreadId, runIds: Set[RunId]): Result[Option[Checkpoint]] =
    inner.latestOfRuns(threadId, runIds).flatMap(opened(threadId, _))

  private def opened(threadId: ThreadId, found: Option[Checkpoint]): Result[Option[Checkpoint]] =
    found.fold[Result[Option[Checkpoint]]](Right(None))(c => checkpointed(cipher(threadId).open, c).map(Some(_)))

  def effect(threadId: ThreadId, key: EffectKey): Result[Option[EffectRecord]] =
    inner.effect(threadId, key).flatMap {
      case None         => Right(None)
      case Some(record) => effected(cipher(threadId).open, record).map(Some(_))
    }

  def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int): Result[Vector[EventRecord]] =
    inner.eventsAfter(threadId, afterSeq, limit).flatMap(records(threadId, _))

  override def awaitEventsAfter(
    threadId: ThreadId,
    afterSeq: Long,
    limit: Int,
    timeout: FiniteDuration
  ): Result[Vector[EventRecord]] =
    inner.awaitEventsAfter(threadId, afterSeq, limit, timeout).flatMap(records(threadId, _))

  private def records(threadId: ThreadId, found: Vector[EventRecord]): Result[Vector[EventRecord]] =
    if found.exists(r => isCustom(r.event)) then
      val open = cipher(threadId).open
      traverse(found)(record(open, _))
    else Right(found)

  // ---- forwarded as they are: nothing they carry is encrypted ----

  def claim(threadId: ThreadId, request: ClaimRequest): Result[RunClaim] = inner.claim(threadId, request)
  def renew(threadId: ThreadId, token: FencingToken, ttl: FiniteDuration): Result[RunClaim] =
    inner.renew(threadId, token, ttl)
  override def owner(threadId: ThreadId): Result[Option[ThreadOwner]]  = inner.owner(threadId)
  def prune(threadId: ThreadId, policy: RetentionPolicy): Result[Unit] = inner.prune(threadId, policy)
  def children(parent: ThreadId, after: Option[ThreadId], limit: Int): Result[Vector[ChildThread]] =
    inner.children(parent, after, limit)
  def threads(query: ThreadQuery): Result[ThreadPage] = inner.threads(query)
  def incompleteThreads(tenantId: Option[TenantId], after: Option[ThreadCursor], limit: Int): Result[ThreadPage] =
    inner.incompleteThreads(tenantId, after, limit)
  def sweep(policy: SweepPolicy, after: Option[ThreadId], guard: SweepGuard): Result[SweepPage] =
    inner.sweep(policy, after, guard)
  def compactEvents(threadId: ThreadId, beforeSeq: Long): Result[Unit] = inner.compactEvents(threadId, beforeSeq)

@Experimental
object EncryptingCheckpointer:
  import ThreadCipher.{ traverse, Codec }

  /** The most threads whose tenant a checkpointer remembers between commits; the oldest is forgotten first, and read again. */
  val TenantCacheLimit: Int = 10000

  /** `inner`, storing its payloads encrypted with `keys`. */
  def apply(
    inner: Checkpointer,
    keys: EncryptionKeySource,
    settings: EncryptionSettings = EncryptionSettings()
  ): EncryptingCheckpointer = new EncryptingCheckpointer(inner, keys, settings)

  private def isCustom(event: RunEvent): Boolean = event match
    case _: RunEvent.Custom => true
    case _                  => false

  /** Whether `commit` writes anything to encrypt: a claim's bookkeeping and the built-in events need no key. */
  private def holdsPayload(commit: Commit): Boolean =
    commit.checkpoint.isDefined || commit.pendingWrites.nonEmpty || commit.effects.nonEmpty ||
      commit.events.exists(d => isCustom(d.event))

  private def tenantPlace(tenant: Option[String]): Vector[String] = tenant.fold(Vector("-", ""))(Vector("t", _))

  private def versioned(codec: Codec, place: Vector[String], json: VersionedJson): Result[VersionedJson] =
    codec(place, json.value).map(value => json.copy(value = value))

  private def routed(codec: Codec, place: Vector[String], routes: Vector[EncodedRoute]): Result[Vector[EncodedRoute]] =
    traverse[(EncodedRoute, Int), EncodedRoute](routes.zipWithIndex) {
      case (goto: EncodedRoute.Goto, _) => Right(goto)
      case (EncodedRoute.Send(node, payload), i) =>
        versioned(codec, place ++ Vector("route", i.toString), payload).map(EncodedRoute.Send(node, _))
      case (EncodedRoute.FanOut(join, node, payloads), i) =>
        traverse(payloads.zipWithIndex)((p, j) => versioned(codec, place ++ Vector("route", i.toString, j.toString), p))
          .map(EncodedRoute.FanOut(join, node, _))
    }

  /** `checkpoint` with every value of its snapshot through `codec`, each bound to the tenant, the checkpoint and its place. */
  private def checkpointed(codec: Codec, checkpoint: Checkpoint): Result[Checkpoint] =
    val place    = Vector("checkpoint") ++ tenantPlace(checkpoint.tenantId) :+ checkpoint.id
    val snapshot = checkpoint.snapshot
    for
      state <- traverse(snapshot.state.toVector)((key, v) =>
        versioned(codec, place ++ Vector("state", key), v).map(key -> _)
      )
      frontier <- traverse(snapshot.frontier) { task =>
        val at = place ++ Vector("task", task.taskId)
        for
          input <- versioned(codec, at :+ "input", task.input)
          replay <- task.replay
            .fold[Result[Option[Vector[EncodedRoute]]]](Right(None))(routed(codec, at, _).map(Some(_)))
        yield task.copy(input = input, replay = replay)
      }
      parked <- traverse(snapshot.parked) { continuation =>
        val at = place ++ Vector("parked", continuation.interruptId)
        for
          question <- versioned(codec, at :+ "question", continuation.question)
          held     <- routed(codec, at, continuation.heldRoutes)
        yield continuation.copy(question = question, heldRoutes = held)
      }
    yield checkpoint.withSnapshot(snapshot.copy(state = state.toMap, frontier = frontier, parked = parked))

  private def written(codec: Codec, write: PendingWrite): Result[PendingWrite] =
    val place = Vector("write", write.checkpointId, write.taskId)
    for
      operations <- traverse[(EncodedOperation, Int), EncodedOperation](write.operations.zipWithIndex) {
        case (EncodedOperation.Update(key, update), i) =>
          versioned(codec, place ++ Vector("update", i.toString, key), update).map(EncodedOperation.Update(key, _))
        case (remove, _) => Right(remove)
      }
      routes <- routed(codec, place, write.routes)
      suspension <- write.suspension.fold[Result[Option[EncodedSuspension]]](Right(None)) { s =>
        versioned(codec, place :+ "question", s.question).map(q => Some(s.copy(question = q)))
      }
    yield write.copy(operations = operations, routes = routes, suspension = suspension)

  /**
   * `event` with a custom event's payload through `codec`, bound to what a draft and its record share - the store
   * numbers an event inside the commit, so its sequence number is not known when it is sealed.
   */
  private def evented(
    codec: Codec,
    runId: String,
    checkpointId: Option[String],
    taskId: Option[String],
    nodeId: Option[String],
    event: RunEvent
  ): Result[RunEvent] = event match
    case RunEvent.Custom(name, version, payload) =>
      val place =
        Vector(
          "event",
          runId,
          checkpointId.getOrElse(""),
          taskId.getOrElse(""),
          nodeId.getOrElse(""),
          name,
          version.toString
        )
      codec(place, payload).map(RunEvent.Custom(name, version, _))
    case other => Right(other)

  private def record(codec: Codec, r: EventRecord): Result[EventRecord] =
    evented(codec, r.runId, r.checkpointId, r.taskId, r.nodeId, r.event).map(event => r.copy(event = event))

  /** `record` with its outcome and its presumed outcome through `codec`, in its JSON form: its body is the guard's. */
  private def effected(codec: Codec, record: EffectRecord): Result[EffectRecord] =
    val json  = EffectRecord.toJson(record)
    val place = Vector("effect", record.key.operation, record.key.value)
    def through(holder: ujson.Value, field: String): Result[Unit] =
      holder.objOpt.flatMap(_.get(field)) match
        case Some(value) => codec(place :+ field, value).map(sealedValue => holder(field) = sealedValue)
        case None        => Right(())
    for
      _       <- through(json("intent"), "presumed")
      _       <- json.obj.get("outcome").fold[Result[Unit]](Right(()))(through(_, "value"))
      rebuilt <- EffectRecord.fromJson(json)
    yield rebuilt
