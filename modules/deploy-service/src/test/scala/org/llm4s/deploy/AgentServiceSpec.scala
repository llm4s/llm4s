package org.llm4s.deploy

import org.llm4s.agent.graph.{ GraphError, TenantId, ThreadCursor, ThreadId }
import org.llm4s.agent.service.{ EntryId, QueueError }
import org.llm4s.error.{ ConfigurationError, ValidationError }
import org.llm4s.testutil.TestTimeLimit
import org.llm4s.trace.TraceContext
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }

import java.time.Instant
import scala.concurrent.duration.*

/** [[AgentService]]'s operations over the in-memory store, with the demo agent and a real worker (design 4.60). */
class AgentServiceSpec extends AnyFlatSpec with TestTimeLimit with Matchers with Eventually with BeforeAndAfterEach {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(10, Seconds), interval = Span(20, Millis))

  private var fixture: ServiceFixture = _

  override def beforeEach(): Unit = fixture = new ServiceFixture()
  override def afterEach(): Unit  = fixture.stop()

  private def service = fixture.service

  private def thread(): String = s"t-${java.util.UUID.randomUUID().toString.take(8)}"

  private def submitted(reply: Reply): String = {
    withClue(reply)(reply.status shouldBe 202)
    reply.body("entryId").str
  }

  private def closed(entryId: String, tenant: Option[TenantId] = None): ujson.Value =
    eventually {
      val reply = service.entry(tenant, entryId)
      reply.status shouldBe 200
      reply.body("state").str should not be "open"
      reply.body
    }

  "submit" should "queue a query that the worker runs to an echoed answer" in {
    val t       = thread()
    val ticket  = service.submit(None, t, """{"query": "hello"}""", None)
    val entryId = submitted(ticket)
    ticket.body("threadId").str shouldBe t
    ticket.body("existing").bool shouldBe false

    val entry = closed(entryId)
    entry("state").str shouldBe "done"
    entry("outcome")("status").str shouldBe "completed"
    entry("kind").str shouldBe "start"
    entry.obj.contains("input") shouldBe false

    val read = service.thread(None, t)
    read.status shouldBe 200
    read.body("status").str shouldBe "completed"
    read.body("agent").str shouldBe "assistant"
    ujson.write(read.body("messages")) should include("Echo: hello")
  }

  it should "refuse a second run on a busy thread under reject, and queue it under enqueue" in {
    fixture.stop()
    fixture = new ServiceFixture(startWorker = false)
    val t     = thread()
    val first = submitted(service.submit(None, t, """{"query": "one"}""", None))

    val refused = service.submit(None, t, """{"query": "two", "policy": "reject"}""", None)
    refused.status shouldBe 409
    refused.body("error").str shouldBe "ThreadBusy"

    val queued = service.submit(None, t, """{"query": "two", "policy": "enqueue"}""", None)
    queued.status shouldBe 202
    queued.body("position").num shouldBe 1

    fixture.worker.start()
    closed(first)("state").str shouldBe "done"
    closed(queued.body("entryId").str)("state").str shouldBe "done"
    ujson.write(service.thread(None, t).body("messages")) should include("Echo: two")
  }

  it should "stop the running run at its next boundary under interrupt, and run the new input in its place" in {
    fixture.stop()
    fixture = new ServiceFixture(toolDelay = 1.second)
    val t     = thread()
    val first = submitted(service.submit(None, t, """{"query": "first"}""", None))
    eventually(service.entry(None, first).body("attempts").num shouldBe 1)

    val second  = submitted(service.submit(None, t, """{"query": "second", "policy": "interrupt"}""", None))
    val stopped = closed(first)
    stopped("state").str shouldBe "interrupted"
    stopped("outcome")("by").str shouldBe second
    closed(second)("state").str shouldBe "done"
    ujson.write(service.thread(None, t).body("messages")) should include("Echo: second")
  }

  it should "answer the same ticket to a retry under the same submission key, and refuse another submission" in {
    val t      = thread()
    val first  = service.submit(None, t, """{"query": "hi", "submissionKey": "k-1"}""", None)
    val second = service.submit(None, t, """{"query": "hi", "submissionKey": "k-1"}""", None)
    second.status shouldBe 202
    second.body("entryId") shouldBe first.body("entryId")
    second.body("existing").bool shouldBe true

    service.submit(None, t, """{"query": "other", "submissionKey": "k-1"}""", None).status shouldBe 409
  }

  it should "continue the request's traceparent in the entry, and ignore an invalid one" in {
    val parent = TraceContext.random()
    val traced = submitted(service.submit(None, thread(), """{"query": "x"}""", Some(parent.traceParent)))
    fixture.submitter.entry(None, EntryId.parse(traced).toOption.get).map(_.trace) shouldBe Right(Some(parent))

    val plain = submitted(service.submit(None, thread(), """{"query": "x"}""", Some("not-a-traceparent")))
    fixture.submitter.entry(None, EntryId.parse(plain).toOption.get).map(_.trace) shouldBe Right(None)
  }

  it should "answer 400 for a body that is not one input" in {
    val bodies = Seq(
      "not json",
      "[1]",
      "{}",
      """{"query": "a", "recover": true}""",
      """{"query": ""}""",
      """{"query": 1}""",
      """{"answers": "yes"}""",
      """{"recover": false}""",
      """{"query": "a", "policy": "later"}""",
      """{"query": "a", "submissionKey": 7}"""
    )
    bodies.foreach { body =>
      withClue(body) {
        val reply = service.submit(None, thread(), body, None)
        reply.status shouldBe 400
        reply.body("error").str shouldBe "ValidationError"
      }
    }
    service.submit(None, " ", """{"query": "a"}""", None).status shouldBe 400
  }

  it should "queue a resume and a recover, which the worker closes from the thread's state" in {
    val t = thread()
    closed(submitted(service.submit(None, t, """{"query": "a"}""", None)))("state").str shouldBe "done"
    // nothing is suspended or interrupted: the worker closes each from the thread's state
    Seq("""{"answers": {"i-1": "yes"}}""", """{"recover": true}""").foreach { body =>
      withClue(body) {
        val entry = closed(submitted(service.submit(None, t, body, None)))
        entry("state").str should not be "open"
      }
    }
  }

  "entry" should "answer 404 for an unknown entry and 400 for an id that is not a number" in {
    service.entry(None, "999999").status shouldBe 404
    service.entry(None, "abc").status shouldBe 400
  }

  it should "not show one tenant's entry to another" in {
    val a     = Some(TenantId("a"))
    val entry = submitted(service.submit(a, thread(), """{"query": "x"}""", None))
    service.entry(a, entry).status shouldBe 200
    service.entry(Some(TenantId("b")), entry).status should (be(404).or(be(410)))
    service.entry(None, entry).status should (be(404).or(be(410)))
  }

  "cancel" should "stop a running run, closing its entry cancelled" in {
    fixture.stop()
    fixture = new ServiceFixture(toolDelay = 30.seconds)
    val t     = thread()
    val entry = submitted(service.submit(None, t, """{"query": "slow"}""", None))
    eventually(service.entry(None, entry).body("attempts").num shouldBe 1)

    val reply = service.cancel(None, entry)
    reply.status shouldBe 202
    reply.body("entryId").str shouldBe entry

    closed(entry)("state").str shouldBe "cancelled"
    closed(entry)("outcome")("reason").str shouldBe "requested"
    service.thread(None, t).body("status").str shouldBe "failed"
  }

  it should "close a queued entry before it runs" in {
    fixture.stop()
    fixture = new ServiceFixture(startWorker = false)
    val entry = submitted(service.submit(None, thread(), """{"query": "never"}""", None))
    val reply = service.cancel(None, entry)
    reply.status shouldBe 202
    reply.body("state").str shouldBe "cancelled"
    reply.body("attempts").num shouldBe 0
  }

  it should "answer 404 for an unknown entry" in {
    service.cancel(None, "424242").status shouldBe 404
  }

  "thread" should "answer 404 for an unknown thread and for another tenant's" in {
    service.thread(None, thread()).status shouldBe 404
    val t = thread()
    closed(submitted(service.submit(Some(TenantId("a")), t, """{"query": "x"}""", None)), Some(TenantId("a")))
    service.thread(Some(TenantId("a")), t).status shouldBe 200
    service.thread(Some(TenantId("b")), t).status shouldBe 404
    service.thread(None, t).status shouldBe 404
  }

  "threads" should "page the tenant's threads newest first through the next cursor" in {
    val tenant = Some(TenantId(s"tenant-${java.util.UUID.randomUUID().toString.take(6)}"))
    val ids    = Vector(thread(), thread(), thread())
    ids.foreach(t => closed(submitted(service.submit(tenant, t, """{"query": "x"}""", None)), tenant))

    val first = service.threads(tenant, None, 2, None)
    first.status shouldBe 200
    first.body("threads").arr should have size 2
    val next   = first.body("next").str
    val second = service.threads(tenant, None, 2, Some(next))
    second.body("threads").arr should have size 1
    second.body("next") shouldBe ujson.Null

    val listed = (first.body("threads").arr ++ second.body("threads").arr).map(_("threadId").str)
    listed.toSet shouldBe ids.toSet
    listed.head shouldBe ids.last

    service.threads(tenant, Some("completed"), 50, Some("")).body("threads").arr should have size 3
    service.threads(tenant, Some("running"), 50, None).body("threads").arr shouldBe empty
    service.threads(Some(TenantId("nobody")), None, 50, None).body("threads").arr shouldBe empty
  }

  it should "answer 400 for an unknown status, a limit out of range and a cursor it did not issue" in {
    service.threads(None, Some("sleeping"), 10, None).status shouldBe 400
    service.threads(None, None, 0, None).status shouldBe 400
    service.threads(None, None, 501, None).status shouldBe 400
    service.threads(None, None, 10, Some("%%%")).status shouldBe 400
    service.threads(None, None, 10, Some(java.util.Base64.getUrlEncoder.encodeToString("x".getBytes))).status shouldBe
      400
  }

  "cursorText" should "round-trip a registry cursor" in {
    val cursor = ThreadCursor(Instant.parse("2026-10-23T10:15:30.123456Z"), ThreadId("a/b\nc"))
    AgentService.cursorOf(AgentService.cursorText(cursor)) shouldBe Right(cursor)
  }

  "ready" should "follow the worker's health" in {
    eventually(service.ready().status shouldBe 200)
    service.ready().body("state").str shouldBe "running"
    fixture.stop()
    val stopped = service.ready()
    stopped.status shouldBe 503
    stopped.body("problems").arr.map(_.str) should contain("the worker has stopped")
  }

  it should "be 503 for a service with no worker" in {
    val bare = new AgentService(fixture.submitter, fixture.store, fixture.config.agentId, None)
    bare.ready().status shouldBe 503
  }

  "errorReply" should "map errors to statuses, keeping a server-side error's message out of the body" in {
    AgentService.statusOf(ValidationError("x", "bad")) shouldBe 400
    AgentService.statusOf(GraphError.ThreadBusy("t", None, GraphError.BusyHolder.Unnamed)) shouldBe 409
    AgentService.statusOf(QueueError.TicketNotFound(EntryId.parse("1").toOption.get)) shouldBe 404
    AgentService.statusOf(QueueError.TicketExpired(EntryId.parse("1").toOption.get)) shouldBe 410

    val reply = service.errorReply(ConfigurationError("secret detail"))
    reply.status shouldBe 500
    reply.body shouldBe ujson.Obj("error" -> "ConfigurationError")
  }

  "a run" should "finish within the worker's drain when the service drains" in {
    fixture.stop()
    fixture = new ServiceFixture(toolDelay = 300.millis)
    val entry = submitted(service.submit(None, thread(), """{"query": "drain"}""", None))
    eventually(service.entry(None, entry).body("attempts").num shouldBe 1)
    val report = fixture.worker.shutdown()
    report.finished.map(_.value.toString) shouldBe Vector(entry)
    service.entry(None, entry).body("state").str shouldBe "done"
  }
}
