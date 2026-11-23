package org.llm4s.agent.encryption

import org.llm4s.agent.AgentId
import org.llm4s.agent.graph.*
import org.llm4s.agent.service.*
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

/**
 * [[EncryptingRunQueue]] stores no input in plain, binds each to its thread, tenant and agent, and keeps a retried
 * submission idempotent although the store can no longer compare two inputs (#2449).
 */
class EncryptingRunQueueSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private val Secret = "the-patient-has-condition-x"
  private val agent  = AgentId.of("assistant").value

  private def key(id: String): EncryptionKey = EncryptionKey.generate(KeyId(id))
  private def thread(): ThreadId             = ThreadId(s"encq-${java.util.UUID.randomUUID().toString.take(8)}")
  private def start(text: String = Secret)   = QueuedInput.Start(ujson.Obj("query" -> text))

  private def submission(on: ThreadId, input: QueuedInput = start(), tenant: Option[String] = None): Submission =
    Submission(on, agent, input, tenant.map(TenantId(_)), BusyThreadPolicy.Enqueue)

  private def text(entry: QueueEntry): String = ujson.write(QueuedInput.toJson(entry.input))

  "EncryptingRunQueue" should "store no input in plain, and hand a worker the input that was submitted" in {
    val raw    = InMemoryCheckpointer().runQueue
    val queue  = EncryptingRunQueue(raw, StaticKeySource(key("k1")))
    val on     = thread()
    val ticket = queue.submit(submission(on)).value

    text(raw.entry(None, ticket.entryId).value) should (not.include(Secret).and(include("1.k1.")))
    queue.entry(None, ticket.entryId).value.input shouldBe start()
    queue.openEntries(None, on).value.map(_.input) shouldBe Vector(start())

    val leased = queue.take(TakeRequest("w1", Set(agent), 30.seconds)).value.value
    leased.entry.input shouldBe start()
    queue.recordAttempt(leased.lease, RunId("a1")).value match {
      case AttemptStart.Recorded(entry) => entry.input shouldBe start()
      case other                        => fail(s"not recorded: $other")
    }
    queue.close(leased.lease, EntryOutcome.Done(None, None)).value.input shouldBe start()
    queue.awaitClosed(None, ticket.entryId, 1.second).value.input shouldBe start()
  }

  it should "seal each answer of a resume, and leave a recover as it is" in {
    val raw   = InMemoryCheckpointer().runQueue
    val queue = EncryptingRunQueue(raw, StaticKeySource.tenantsOnly)
    val answers =
      QueuedInput.Resume(Map(InterruptId("i1") -> ujson.Str(Secret), InterruptId("i2") -> ujson.Str(Secret)))
    val keyed  = EncryptingRunQueue(raw, StaticKeySource(key("k1")))
    val ticket = keyed.submit(submission(thread(), answers)).value

    val kept = text(raw.entry(None, ticket.entryId).value)
    kept should (not.include(Secret).and(include("i1")).and(include("i2")))
    keyed.entry(None, ticket.entryId).value.input shouldBe answers

    // a recover holds nothing to seal, so it needs no key
    val recover = queue.submit(submission(thread(), QueuedInput.Recover)).value
    queue.entry(None, recover.entryId).value.input shouldBe QueuedInput.Recover
  }

  it should "answer a retried submission under its key with the first one's ticket" in {
    val queue = EncryptingRunQueue(InMemoryCheckpointer().runQueue, StaticKeySource(key("k1")))
    val on    = thread()
    val keyed = submission(on).withSubmissionKey(SubmissionKey.of("request-1").value)
    val first = queue.submit(keyed).value
    first.existing shouldBe false

    val again = queue.submit(keyed.withMaxAttempts(5)).value
    again.existing shouldBe true
    again.entryId shouldBe first.entryId
    again.position shouldBe Some(0)
    queue.openEntries(None, on).value should have size 1

    val different = queue.submit(keyed.withInput(start("another question"))).left.value
    different shouldBe QueueError.SubmissionKeyReused(SubmissionKey.of("request-1").value, first.entryId)
  }

  it should "refuse an input moved to another thread, tenant or agent, and one that was changed" in {
    val keys   = StaticKeySource(key("k1"))
    val raw    = InMemoryCheckpointer().runQueue
    val queue  = EncryptingRunQueue(raw, keys)
    val on     = thread()
    val ticket = queue.submit(submission(on, tenant = Some("acme"))).value
    val stolen = raw.entry(Some(TenantId("acme")), ticket.entryId).value.input

    def planted(to: Submission): org.llm4s.types.Result[QueueEntry] = {
      val other = InMemoryCheckpointer().runQueue
      val id    = other.submit(to.withInput(stolen)).value.entryId
      EncryptingRunQueue(other, keys).entry(to.tenantId, id)
    }

    planted(submission(on, tenant = Some("acme"))).value.input shouldBe start()
    planted(submission(thread(), tenant = Some("acme"))).left.value shouldBe a[EncryptionError.DecryptionFailed]
    planted(submission(on, tenant = Some("globex"))).left.value shouldBe a[EncryptionError.DecryptionFailed]
    planted(submission(on, tenant = None)).left.value shouldBe a[EncryptionError.DecryptionFailed]
    planted(submission(on, tenant = Some("acme")).withAgentId(AgentId.of("other").value)).left.value shouldBe
      a[EncryptionError.DecryptionFailed]
  }

  it should "queue nothing when the tenant has no key, and refuse a plaintext entry unless migrating" in {
    val raw   = InMemoryCheckpointer().runQueue
    val keys  = StaticKeySource.tenantsOnly.withTenantKey(TenantId("acme"), key("acme-1"))
    val queue = EncryptingRunQueue(raw, keys)
    val on    = thread()
    queue.submit(submission(on, tenant = Some("globex"))).left.value shouldBe
      EncryptionError.NoCurrentKey(Some(TenantId("globex")))
    raw.openEntries(Some(TenantId("globex")), on).value shouldBe empty

    val plain = raw.submit(submission(on, tenant = Some("acme"))).value
    queue.entry(Some(TenantId("acme")), plain.entryId).left.value shouldBe a[EncryptionError.NotEncrypted]
    val migrating = EncryptingRunQueue(raw, keys, EncryptionSettings(plaintextReads = true))
    migrating.entry(Some(TenantId("acme")), plain.entryId).value.input shouldBe start()
  }

  it should "open the entries an interrupting submission closed, and the ones a cancel reports" in {
    val queue        = EncryptingRunQueue(InMemoryCheckpointer().runQueue, StaticKeySource(key("k1")))
    val on           = thread()
    val interrupting = submission(on).withPolicy(BusyThreadPolicy.Interrupt)
    queue.submit(interrupting).value
    val second = queue.submit(interrupting.withInput(start("second"))).value
    second.interrupted.map(_.input) shouldBe Vector(start())

    val cancelled = queue.cancel(None, CancelTarget.Thread(on), CancelReason.Requested(None)).value
    cancelled.entries.map(_.input) shouldBe Vector(start("second"))
  }
}
