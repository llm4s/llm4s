package org.llm4s.deploy

import io.undertow.Undertow
import org.llm4s.testutil.TestTimeLimit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }

import java.net.{ InetSocketAddress, URI }
import java.net.http.{ HttpClient, HttpRequest, HttpResponse }
import java.time.Duration
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * The example agent service's real routes behind a real server on an ephemeral port, over the in-memory store with
 * the demo agent and a real worker (design 4.60): submit, follow over SSE (and resume with `Last-Event-ID`), cancel,
 * read and list threads, readiness and metrics.
 */
class AgentRoutesHttpSpec extends AnyFlatSpec with TestTimeLimit with Matchers with Eventually with BeforeAndAfterAll {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(10, Seconds), interval = Span(20, Millis))

  private val fixture = new ServiceFixture(toolDelay = 200.millis)
  private val routes  = new AgentRoutes(fixture.service, fixture.config, Some(() => "llm4s_test_metric 1.0\n"))
  private val app    = new DeployApp(DeployServiceConfig("127.0.0.1", 0), () => LlmCheckOutcome.Ready("x"), Seq(routes))
  private val server = Undertow.builder().addHttpListener(0, "127.0.0.1").setHandler(app.defaultHandler).build()
  private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

  private var port = 0

  override def beforeAll(): Unit = {
    server.start()
    port = server.getListenerInfo.get(0).getAddress.asInstanceOf[InetSocketAddress].getPort
  }

  override def afterAll(): Unit = {
    fixture.stop()
    server.stop()
  }

  private def request(path: String, headers: (String, String)*): HttpRequest.Builder = {
    val builder = HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(20))
    headers.foreach((k, v) => builder.header(k, v))
    builder
  }

  private def get(path: String, headers: (String, String)*): HttpResponse[String] =
    client.send(request(path, headers*).GET().build(), HttpResponse.BodyHandlers.ofString())

  private def post(path: String, body: String, headers: (String, String)*): HttpResponse[String] =
    client.send(
      request(path, headers*)
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .header("Content-Type", "application/json")
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )

  private def thread(): String = s"h-${java.util.UUID.randomUUID().toString.take(8)}"

  /** The SSE frames of `body`: each frame's fields, comment frames dropped. */
  private def frames(body: String): Vector[Map[String, String]] =
    body
      .split("\n\n")
      .toVector
      .map(_.linesIterator.filterNot(_.startsWith(":")).toVector)
      .filter(_.nonEmpty)
      .map(_.map { line =>
        val (k, v) = line.span(_ != ':')
        k -> v.drop(1).trim
      }.toMap)

  "POST /threads/{id}/runs then GET /runs/{entry}/events" should
    "stream the run's events live, each with its sequence number, then an end event" in {
      val t      = thread()
      val ticket = post(s"/threads/$t/runs", """{"query": "stream me"}""")
      ticket.statusCode() shouldBe 202
      val entry = ujson.read(ticket.body())("entryId").str

      val stream = get(s"/runs/$entry/events")
      stream.statusCode() shouldBe 200
      stream.headers().firstValue("Content-Type").orElse("") should startWith("text/event-stream")
      val all    = frames(stream.body())
      val events = all.filter(_.get("event").contains("run-event"))
      events should not be empty
      val seqs = events.map(_("id").toLong)
      seqs shouldBe seqs.sorted
      seqs.distinct shouldBe seqs
      events.foreach(e => ujson.read(e("data"))("seq").num.toLong shouldBe e("id").toLong)
      all.last("event") shouldBe "end"
      ujson.read(all.last("data"))("state").str shouldBe "done"

      // resuming after an event replays only the later ones
      val resumeFrom = seqs(seqs.size / 2)
      val resumed = frames(get(s"/runs/$entry/events", "Last-Event-ID" -> resumeFrom.toString).body())
        .filter(_.get("event").contains("run-event"))
        .map(_("id").toLong)
      resumed shouldBe seqs.filter(_ > resumeFrom)

      frames(get(s"/runs/$entry/events?after=${seqs.last}").body()).map(_("event")) shouldBe Vector("end")
    }

  "GET /runs/{entry}/events" should "answer 404 for an unknown entry and 400 for a bad Last-Event-ID" in {
    get("/runs/987654/events").statusCode() shouldBe 404
    val entry = ujson.read(post(s"/threads/${thread()}/runs", """{"query": "x"}""").body())("entryId").str
    get(s"/runs/$entry/events", "Last-Event-ID" -> "minus-one").statusCode() shouldBe 400
  }

  "a traceparent header" should "be kept on the run's entry" in {
    val traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
    val response    = post(s"/threads/${thread()}/runs", """{"query": "traced"}""", "traceparent" -> traceparent)
    val entry       = ujson.read(response.body())("entryId").str
    fixture.submitter
      .entry(None, AgentService.entryIdOf(entry).toOption.get)
      .map(_.trace.map(_.traceParent)) shouldBe Right(Some(traceparent))
  }

  "GET /runs/{entry}, POST /runs/{entry}/cancel, GET /threads/{id} and GET /threads" should
    "answer for the tenant the X-Tenant-Id header names" in {
      val tenant = "X-Tenant-Id" -> s"acme-${java.util.UUID.randomUUID().toString.take(6)}"
      val t      = thread()
      val entry  = ujson.read(post(s"/threads/$t/runs", """{"query": "tenanted"}""", tenant).body())("entryId").str
      eventually(ujson.read(get(s"/runs/$entry", tenant).body())("state").str shouldBe "done")
      get(s"/runs/$entry").statusCode() should not be 200

      val read = get(s"/threads/$t", tenant)
      read.statusCode() shouldBe 200
      read.body() should include("Echo: tenanted")
      get(s"/threads/$t").statusCode() shouldBe 404

      val listed = ujson.read(get("/threads?limit=10", tenant).body())
      listed("threads").arr.map(_("threadId").str) shouldBe Seq(t)
      get("/threads?status=completed", tenant).statusCode() shouldBe 200
      get("/threads?limit=0", tenant).statusCode() shouldBe 400

      val cancelled = post(s"/runs/$entry/cancel", "", tenant)
      cancelled.statusCode() shouldBe 202
      ujson.read(cancelled.body())("state").str shouldBe "done"
    }

  "GET /ready" should "be 200 while the worker runs" in {
    eventually(get("/ready").statusCode() shouldBe 200)
  }

  "GET /metrics" should "serve the rendered metrics as Prometheus text" in {
    val response = get("/metrics")
    response.statusCode() shouldBe 200
    response.headers().firstValue("Content-Type").orElse("") should startWith("text/plain")
    response.body() shouldBe "llm4s_test_metric 1.0\n"
  }

  it should "be 404 when metrics are off" in {
    val bare  = new AgentRoutes(fixture.service, fixture.config, None)
    val off   = new DeployApp(DeployServiceConfig("127.0.0.1", 0), () => LlmCheckOutcome.Ready("x"), Seq(bare))
    val other = Undertow.builder().addHttpListener(0, "127.0.0.1").setHandler(off.defaultHandler).build()
    other.start()
    val otherPort = other.getListenerInfo.get(0).getAddress.asInstanceOf[InetSocketAddress].getPort
    val response = client.send(
      HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$otherPort/metrics")).GET().build(),
      HttpResponse.BodyHandlers.ofString()
    )
    other.stop()
    response.statusCode() shouldBe 404
  }

  "GET /health" should "still be served beside the agent routes" in {
    get("/health").statusCode() shouldBe 200
    get("/llm-check").statusCode() shouldBe 200
    get("/health").headers().map().asScala.keySet.map(_.toLowerCase) should contain("content-type")
  }
}
