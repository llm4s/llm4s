package org.llm4s.agent.encryption

import org.llm4s.agent.AgentId
import org.llm4s.agent.delegation.ChildRef
import org.llm4s.agent.events.{ SubagentEvents, TaskEnded }
import org.llm4s.agent.graph.*
import org.llm4s.agent.metrics.{ AgentMetrics, MeteredCheckpointer, RecordingMetrics }
import org.llm4s.llmconnect.model.UsageSummary
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.concurrent.duration.*

/**
 * [[EncryptingCheckpointer]] stores no payload in plain, binds each ciphertext to its thread, tenant and place, reads
 * old keys' rows after a rotation, and fails typed - never with a stored value - when it cannot open one (#2449).
 */
class EncryptingCheckpointerSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private val at     = Instant.parse("2026-11-22T12:00:00Z")
  private val Secret = "the-patient-has-condition-x"

  private def key(id: String): EncryptionKey = EncryptionKey.generate(KeyId(id))

  private def thread(): ThreadId = ThreadId(s"enc-${java.util.UUID.randomUUID().toString.take(8)}")

  private def secret(text: String = Secret): VersionedJson = VersionedJson(1, ujson.Obj("text" -> text))

  /** A checkpoint holding the secret everywhere a snapshot holds a value. */
  private def checkpoint(
    on: ThreadId,
    id: String = "c1",
    parent: Option[String] = None,
    tenant: Option[String] = None,
    text: String = Secret
  ): Checkpoint = {
    val task = GraphSnapshot.PendingTask(
      "1.0",
      "n",
      secret(text),
      None,
      None,
      None,
      None,
      replay = Some(Vector(EncodedRoute.Goto("m"), EncodedRoute.Send("m", secret(text))))
    )
    val parked = GraphSnapshot.ParkedContinuation(
      "i1",
      "n",
      secret(text),
      "0.0",
      "n",
      None,
      None,
      heldRoutes = Vector(EncodedRoute.FanOut("j", "m", Vector(secret(text), secret(text))))
    )
    val snapshot = GraphSnapshot(
      "g",
      "v1",
      "f",
      1,
      Map("messages" -> secret(text), "notes" -> secret(text)),
      Vector(task),
      Vector.empty,
      Vector.empty,
      Vector(parked),
      paused = false
    )
    Checkpoint(Checkpoint.CurrentFormat, id, parent, on.value, "r1", CheckpointStatus.Running, at, snapshot, tenant)
  }

  private def write(checkpointId: String = "c1"): PendingWrite =
    PendingWrite(
      checkpointId,
      "1.0",
      "n",
      Vector(EncodedOperation.Update("messages", secret()), EncodedOperation.Remove("notes")),
      Vector(EncodedRoute.Send("m", secret())),
      Some(EncodedSuspension("n", secret()))
    )

  private def custom(text: String = Secret): EventDraft =
    EventDraft("r1", Some("c1"), Some("1.0"), Some("n"), at, RunEvent.Custom("app.note", 1, ujson.Obj("text" -> text)))

  private def effect(on: ThreadId): EffectRecord =
    EffectRecord
      .fromJson(
        ujson.Obj(
          "format"    -> EffectRecord.CurrentFormat,
          "key"       -> s"key-of-${on.value}",
          "operation" -> "op",
          "scope"     -> "scope",
          "taskId"    -> "1.0",
          "lineage"   -> "l",
          "intent" -> ujson
            .Obj("support" -> "verifiable", "before" -> "absent", "after" -> "absent", "presumed" -> Secret),
          "outcome" -> ujson.Obj("value" -> ujson.Obj("text" -> Secret))
        )
      )
      .value

  private def claimed(store: Checkpointer, on: ThreadId, tenant: Option[String] = None): FencingToken =
    store.claim(on, ClaimRequest(RunId("r1"), tenant.map(TenantId(_)), 30.seconds)).value.token

  /** Commits everything a thread holds, through `store`, and releases the claim it was committed under. */
  private def fill(store: Checkpointer, on: ThreadId, tenant: Option[String] = None): Unit = {
    val token = claimed(store, on, tenant)
    store
      .commit(
        on,
        Commit(
          token,
          Some(checkpoint(on, tenant = tenant)),
          Vector(write()),
          Vector(custom(), EventDraft("r1", None, None, None, at, RunEvent.RunStarted(tenant, None))),
          effects = Vector(effect(on))
        )
      )
      .value
    store.release(on, token).value
  }

  /** Everything `store` holds for `on`, as JSON text: what someone reading the database sees. */
  private def dump(store: Checkpointer, on: ThreadId): String = {
    val latest = store.latest(on).value.value
    val parts = Vector(Checkpoint.toJson(latest.checkpoint)) ++
      store.history(on, None, 100).value.map(Checkpoint.toJson) ++
      latest.pendingWrites.map(upickle.default.writeJs(_)) ++
      store.eventsAfter(on, 0L, 100).value.map(upickle.default.writeJs(_)) ++
      store.effect(on, effect(on).key).value.map(EffectRecord.toJson)
    parts.map(ujson.write(_)).mkString("\n")
  }

  /** A raw store holding `checkpoint` as its thread's only one, written around the encryption. */
  private def planted(checkpoint: Checkpoint): InMemoryCheckpointer = {
    val raw   = InMemoryCheckpointer()
    val on    = ThreadId(checkpoint.threadId)
    val token = claimed(raw, on)
    raw.commit(on, Commit(token, Some(checkpoint))).value
    raw
  }

  private def stored(raw: Checkpointer, on: ThreadId): Checkpoint = raw.latest(on).value.value.checkpoint

  "EncryptingCheckpointer" should "store no payload in plain, and read back exactly what was committed" in {
    val raw   = InMemoryCheckpointer()
    val store = EncryptingCheckpointer(raw, StaticKeySource(key("k1")))
    val on    = thread()
    fill(store, on)

    (dump(raw, on) should not).include(Secret)
    dump(raw, on) should include(ThreadCipher.Marker)

    val latest = store.latest(on).value.value
    latest.checkpoint shouldBe checkpoint(on)
    latest.pendingWrites shouldBe Vector(write())
    store.history(on, None, 10).value shouldBe Vector(checkpoint(on))
    store.checkpoint(on, "c1").value.value shouldBe checkpoint(on)
    store.latestOfRuns(on, Set(RunId("r1"))).value.value shouldBe checkpoint(on)
    store.effect(on, effect(on).key).value.value shouldBe effect(on)
    val events = store.eventsAfter(on, 0L, 10).value
    events.map(_.event) shouldBe Vector(custom().event, RunEvent.RunStarted(None, None))
    store.awaitEventsAfter(on, 0L, 10, 1.second).value shouldBe events
  }

  it should "answer a commit with the events as they were committed, not as they are stored" in {
    val store = EncryptingCheckpointer(InMemoryCheckpointer(), StaticKeySource(key("k1")))
    val on    = thread()
    val token = claimed(store, on)
    val records =
      store.commit(on, Commit(token, Some(checkpoint(on)), events = Vector(custom()))).value
    records.map(_.event) shouldBe Vector(custom().event)
  }

  it should "leave the plain structure a store works by as it was" in {
    val raw   = InMemoryCheckpointer()
    val store = EncryptingCheckpointer(raw, StaticKeySource(key("k1")))
    val on    = thread()
    fill(store, on, tenant = Some("acme"))

    val kept     = stored(raw, on)
    val expected = checkpoint(on, tenant = Some("acme"))
    kept.id shouldBe expected.id
    kept.tenantId shouldBe Some("acme")
    kept.status shouldBe expected.status
    kept.snapshot.state.keySet shouldBe expected.snapshot.state.keySet
    kept.snapshot.liveTasks shouldBe expected.snapshot.liveTasks
    kept.snapshot.superstep shouldBe expected.snapshot.superstep
    raw.owner(on).value.value.tenantId shouldBe Some("acme")
  }

  it should "use a fresh nonce for every value: one value sealed twice is stored as two ciphertexts" in {
    val raw   = InMemoryCheckpointer()
    val store = EncryptingCheckpointer(raw, StaticKeySource(key("k1")))
    val on    = thread()
    fill(store, on)
    val state = stored(raw, on).snapshot.state
    // "messages" and "notes" hold the same plaintext
    state("messages").value should not be state("notes").value
  }

  it should "refuse a ciphertext moved to another thread" in {
    val raw   = InMemoryCheckpointer()
    val keys  = StaticKeySource(key("k1"))
    val store = EncryptingCheckpointer(raw, keys)
    val from  = thread()
    val to    = thread()
    fill(store, from)

    val moved = planted(stored(raw, from).withThreadId(to.value))
    EncryptingCheckpointer(moved, keys).latest(to).left.value shouldBe a[EncryptionError.DecryptionFailed]
  }

  it should "refuse a ciphertext moved to another tenant's thread of the same id" in {
    val raw   = InMemoryCheckpointer()
    val keys  = StaticKeySource(key("k1"))
    val store = EncryptingCheckpointer(raw, keys)
    val on    = thread()
    fill(store, on, tenant = Some("acme"))

    val moved = planted(stored(raw, on).withTenantId("globex"))
    EncryptingCheckpointer(moved, keys).latest(on).left.value shouldBe a[EncryptionError.DecryptionFailed]
  }

  it should "refuse a ciphertext moved to another checkpoint or another state key of its thread" in {
    val raw   = InMemoryCheckpointer()
    val keys  = StaticKeySource(key("k1"))
    val store = EncryptingCheckpointer(raw, keys)
    val on    = thread()
    fill(store, on)
    val kept = stored(raw, on)

    val renamed = planted(kept.withId("c2"))
    EncryptingCheckpointer(renamed, keys).latest(on).left.value shouldBe a[EncryptionError.DecryptionFailed]

    val state = kept.snapshot.state
    val swapped =
      kept.withSnapshot(kept.snapshot.copy(state = Map("messages" -> state("notes"), "notes" -> state("messages"))))
    EncryptingCheckpointer(planted(swapped), keys).latest(on).left.value shouldBe a[EncryptionError.DecryptionFailed]
  }

  it should "refuse a ciphertext that was changed, and one that is not a ciphertext" in {
    val raw   = InMemoryCheckpointer()
    val keys  = StaticKeySource(key("k1"))
    val store = EncryptingCheckpointer(raw, keys)
    val on    = thread()
    fill(store, on)
    val kept = stored(raw, on)
    val text = ThreadCipher.envelope(kept.snapshot.state("messages").value).value

    def withMessages(envelope: String): Checkpointer = {
      val state = kept.snapshot.state.updated("messages", VersionedJson(1, ujson.Obj(ThreadCipher.Marker -> envelope)))
      EncryptingCheckpointer(planted(kept.withSnapshot(kept.snapshot.copy(state = state))), keys)
    }

    // the last character of the tag, changed
    val flipped = text.init + (if (text.last == 'A') 'B' else 'A')
    withMessages(flipped).latest(on).left.value shouldBe a[EncryptionError.DecryptionFailed]
    withMessages("1.k1.AAAA").latest(on).left.value shouldBe a[EncryptionError.MalformedCiphertext]
    withMessages("2.k1." + text.split('.').last).latest(on).left.value shouldBe a[EncryptionError.MalformedCiphertext]
  }

  it should "read rows an old key wrote after a rotation, write with the new key, and fail typed once the old key is gone" in {
    val raw    = InMemoryCheckpointer()
    val first  = key("2026-10")
    val second = key("2026-11")
    val on     = thread()
    fill(EncryptingCheckpointer(raw, StaticKeySource(first)), on)
    dump(raw, on) should include("1.2026-10.")

    val rotated = EncryptingCheckpointer(raw, StaticKeySource(second).withRetiredKey(first))
    rotated.latest(on).value.value.checkpoint shouldBe checkpoint(on)
    val token = claimed(rotated, on)
    rotated.commit(on, Commit(token, Some(checkpoint(on, id = "c2", parent = Some("c1"))))).value
    ujson.write(Checkpoint.toJson(stored(raw, on))) should include("1.2026-11.")
    (ujson.write(Checkpoint.toJson(stored(raw, on))) should not).include("1.2026-10.")
    // both generations are read
    rotated.history(on, None, 10).value.map(_.id) shouldBe Vector("c2", "c1")

    val forgotten = EncryptingCheckpointer(raw, StaticKeySource(second))
    forgotten.latest(on).value.value.checkpoint.id shouldBe "c2"
    forgotten.checkpoint(on, "c1").left.value shouldBe EncryptionError.UnknownKey(KeyId("2026-10"))
  }

  it should "fail typed with the wrong key under the right id" in {
    val raw = InMemoryCheckpointer()
    val on  = thread()
    fill(EncryptingCheckpointer(raw, StaticKeySource(key("k1"))), on)
    val impostor = EncryptingCheckpointer(raw, StaticKeySource(key("k1")))
    impostor.latest(on).left.value shouldBe a[EncryptionError.DecryptionFailed]
    impostor.eventsAfter(on, 0L, 10).left.value shouldBe a[EncryptionError.DecryptionFailed]
    impostor.effect(on, effect(on).key).left.value shouldBe a[EncryptionError.DecryptionFailed]
  }

  it should "write nothing when there is no key for the thread's tenant" in {
    val raw   = InMemoryCheckpointer()
    val store = EncryptingCheckpointer(raw, StaticKeySource.tenantsOnly.withTenantKey(TenantId("acme"), key("acme-1")))
    val on    = thread()
    val token = claimed(store, on, Some("globex"))
    val refused =
      store.commit(on, Commit(token, Some(checkpoint(on, tenant = Some("globex"))), events = Vector(custom())))
    refused.left.value shouldBe EncryptionError.NoCurrentKey(Some(TenantId("globex")))
    raw.latest(on).value shouldBe None
    raw.eventsAfter(on, 0L, 10).value shouldBe empty
  }

  it should "seal each tenant's threads with that tenant's key, so one tenant's key opens nothing of another's" in {
    val raw   = InMemoryCheckpointer()
    val acme  = key("acme-1")
    val globe = key("globex-1")
    val both =
      StaticKeySource.tenantsOnly.withTenantKey(TenantId("acme"), acme).withTenantKey(TenantId("globex"), globe)
    val store = EncryptingCheckpointer(raw, both)
    val a     = thread()
    val g     = thread()
    fill(store, a, Some("acme"))
    fill(store, g, Some("globex"))

    dump(raw, a) should (include("1.acme-1.").and(not).include("1.globex-1."))
    dump(raw, g) should (include("1.globex-1.").and(not).include("1.acme-1."))

    val acmeOnly = EncryptingCheckpointer(raw, StaticKeySource.tenantsOnly.withTenantKey(TenantId("acme"), acme))
    acmeOnly.latest(a).value.value.checkpoint shouldBe checkpoint(a, tenant = Some("acme"))
    acmeOnly.latest(g).left.value shouldBe EncryptionError.UnknownKey(KeyId("globex-1"))
    acmeOnly.eventsAfter(g, 0L, 10).left.value shouldBe EncryptionError.UnknownKey(KeyId("globex-1"))
  }

  it should "seal a commit with no checkpoint with the key of the thread's tenant" in {
    val raw   = InMemoryCheckpointer()
    val keys  = StaticKeySource(key("default")).withTenantKey(TenantId("acme"), key("acme-1"))
    val on    = thread()
    val first = EncryptingCheckpointer(raw, keys)
    val token = claimed(first, on, Some("acme"))
    first.commit(on, Commit(token, Some(checkpoint(on, tenant = Some("acme"))))).value
    first.commit(on, Commit(token, pendingWrites = Vector(write()))).value
    // another process holding the claim: it has not seen the checkpoint commit
    EncryptingCheckpointer(raw, keys).commit(on, Commit(token, events = Vector(custom()))).value

    val text = dump(raw, on)
    text should include("1.acme-1.")
    (text should not).include("1.default.")
    first.latest(on).value.value.pendingWrites shouldBe Vector(write())
  }

  it should "refuse a plaintext row by default, and read it during a migration while still writing ciphertext" in {
    val raw = InMemoryCheckpointer()
    val on  = thread()
    fill(raw, on)
    dump(raw, on) should include(Secret)

    val keys   = StaticKeySource(key("k1"))
    val strict = EncryptingCheckpointer(raw, keys)
    strict.latest(on).left.value shouldBe a[EncryptionError.NotEncrypted]
    strict.eventsAfter(on, 0L, 10).left.value shouldBe a[EncryptionError.NotEncrypted]
    strict.effect(on, effect(on).key).left.value shouldBe a[EncryptionError.NotEncrypted]

    val migrating = EncryptingCheckpointer(raw, keys, EncryptionSettings().withPlaintextReads(true))
    migrating.latest(on).value.value shouldBe StoredCheckpoint(checkpoint(on), Vector(write()))
    migrating.eventsAfter(on, 0L, 10).value.map(_.event).head shouldBe custom().event

    val token = claimed(migrating, on)
    migrating.commit(on, Commit(token, Some(checkpoint(on, id = "c2", parent = Some("c1"))))).value
    (ujson.write(Checkpoint.toJson(stored(raw, on))) should not).include(Secret)
    // the old plaintext checkpoint and the new encrypted one, both read
    migrating.history(on, None, 10).value shouldBe
      Vector(checkpoint(on, id = "c2", parent = Some("c1")), checkpoint(on))
  }

  it should "never return a stored value in place of one it cannot open, even with plaintext reads on" in {
    val raw = InMemoryCheckpointer()
    val on  = thread()
    fill(EncryptingCheckpointer(raw, StaticKeySource(key("k1"))), on)
    val lenient = EncryptingCheckpointer(raw, StaticKeySource(key("k2")), EncryptionSettings(plaintextReads = true))
    lenient.latest(on).left.value shouldBe EncryptionError.UnknownKey(KeyId("k1"))
  }

  it should "keep a payload that looks like a ciphertext as the payload it is" in {
    val store     = EncryptingCheckpointer(InMemoryCheckpointer(), StaticKeySource(key("k1")))
    val on        = thread()
    val token     = claimed(store, on)
    val lookalike = ujson.Obj(ThreadCipher.Marker -> "1.k1.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
    val draft     = custom().copy(event = RunEvent.Custom("app.note", 1, lookalike))
    store.commit(on, Commit(token, Some(checkpoint(on)), events = Vector(draft))).value
    store.eventsAfter(on, 0L, 10).value.map(_.event) shouldBe Vector(draft.event)
  }

  it should "name no payload, ciphertext or key in an error" in {
    val raw = InMemoryCheckpointer()
    val on  = thread()
    fill(EncryptingCheckpointer(raw, StaticKeySource(key("k1"))), on)
    val error    = EncryptingCheckpointer(raw, StaticKeySource(key("k1"))).latest(on).left.value
    val rendered = s"${error.message} ${error.formatted} $error"
    (rendered should not).include(Secret)
    (rendered should not).include(ThreadCipher.envelope(stored(raw, on).snapshot.state("messages").value).value)
    rendered should include(on.value)
  }

  it should "let MeteredCheckpointer, stacked outside it, count the child tasks whose events it seals" in {
    val metrics = new RecordingMetrics
    val raw     = InMemoryCheckpointer()
    val store   = MeteredCheckpointer(EncryptingCheckpointer(raw, StaticKeySource(key("k1"))), metrics)
    val on      = thread()
    val agent   = AgentId.of("researcher").value
    val ended   = TaskEnded(ChildRef(thread(), RunId("c1"), agent, None, 1), "completed", UsageSummary(), 3.seconds)
    val draft =
      EventDraft(
        "r1",
        None,
        None,
        None,
        at,
        RunEvent.Custom(SubagentEvents.TaskEnded.name, 1, upickle.default.writeJs(ended))
      )
    val token = claimed(store, on)
    store.commit(on, Commit(token, Some(checkpoint(on)), events = Vector(draft))).value

    metrics.series(AgentMetrics.TasksEnded) shouldBe Map(Vector("researcher", "completed") -> 1.0)
    metrics.durations(AgentMetrics.CommitDuration, "ok") should have size 1
    (ujson.write(upickle.default.writeJs(raw.eventsAfter(on, 0L, 10).value)) should not).include("researcher")
  }
}
