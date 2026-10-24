package org.llm4s.deploy

import org.llm4s.agent.graph.{ EventRecord, GraphRuntime, RunEvent, ThreadId }
import org.llm4s.agent.graph.postgres.{ PgCheckpointerDb, PostgresCheckpointer, PostgresCheckpointerConfig }
import org.llm4s.agent.service.{ EntryId, EntryOutcome, QueueEntry, RunSubmitter }
import org.llm4s.it.tags.Docker
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.{ Signaler, ThreadSignaler, TimeLimitedTests }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Minutes, Span }

import java.lang.ProcessBuilder.Redirect
import java.net.{ ServerSocket, URI }
import java.net.http.{ HttpClient, HttpRequest, HttpResponse }
import java.nio.file.{ Files, Path }
import java.security.SecureRandom
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.{ CopyOnWriteArrayList, CountDownLatch, TimeUnit }
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.{ Try, Using }

/**
 * Stage 5's exit test (spec "Exit criteria", design 4.61): three processes of the example agent service (design 4.60),
 * each a plain JVM running `DeployServiceMain` with its own HTTP port, worker and connection pool, share one Postgres
 * schema - nothing else connects them. Each criterion is one test:
 *
 *  1. a run submitted through A is executed by whichever worker takes it, streamed live through another instance and
 *     replayed from any sequence number through the third; the instance executing it is killed with `kill -9`
 *     (`destroyForcibly`) mid-run, a worker on another instance recovers it once its lease and claim expire, and the
 *     stream carries on with no gap and no duplicate;
 *  2. a second message to a busy thread under `Enqueue` runs after the first;
 *  3. under `Interrupt` it stops the first at its next superstep boundary - the started tool call finishes - and runs
 *     in its place, leaving a provider-valid history;
 *  4. a cancel sent through one instance stops a run executing on another;
 *  5. the run of (1) is one trace across the two machines that executed it;
 *  6. a retention sweep reclaims a tombstone past its age, and a reader of the reclaimed id is answered `ThreadDeleted`
 *     (design 4.53), also once a new thread reuses the id.
 *
 * Which worker takes a queued run is the queue's choice (`FOR UPDATE SKIP LOCKED` among three polling workers), so the
 * test does not pick it: it reads the entry's first attempt, whose lease holder names the instance (each instance's
 * worker label is its name), and then kills, streams, cancels and replays through instances chosen relative to that
 * one. "Instance A" of the criteria is the instance executing the run.
 *
 * Determinism: the demo model's tool call takes [[ToolDelay]] (`LLM4S_SERVICE_TOOL_DELAY`), and the test acts only once
 * the executing instance's tool reports the call in progress (`llm4s_demo_tool_calls_active` on its `/metrics`) and the
 * store holds no result for it yet, so the kill, interrupt and cancel land inside the call. Claims and queue leases last [[Ttl]] (`-D`), so recovery after the
 * kill takes seconds. Every wait polls a condition with a deadline; nothing sleeps a fixed time.
 *
 * The test JVM also opens the schema itself, as an operator's tool would: to read entries' attempts (the lease holder
 * of each), the thread's event log and checkpoints, and to delete a thread with `RunSubmitter.deleteThread` - the
 * example service has no delete endpoint (design 4.60).
 *
 * Tier 2 (`@Docker`): the database is [[PgCheckpointerDb]]'s.
 */
@Docker
class MultiMachineExitSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll with TimeLimitedTests {

  override def timeLimit: Span               = Span(3, Minutes)
  override val defaultTestSignaler: Signaler = ThreadSignaler

  /** How long the demo model's one tool call per turn takes: the window to kill, interrupt or cancel a run in. */
  private val ToolDelay = 2.seconds

  /** Claim and lease lifetime: how long after a `kill -9` the run's entry and thread can be taken over. */
  private val Ttl = 4.seconds

  private val schema = PgCheckpointerDb.freshSchema()
  private val http   = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

  private def freePort(): Int = Using.resource(new ServerSocket(0))(_.getLocalPort)

  /** One example-service process, labelled `label` in its claims and leases. */
  final class Instance(val label: String) {
    @volatile var port: Int                     = 0
    val log: Path                               = Files.createTempFile(s"llm4s-$label-", ".log")
    @volatile private var proc: Option[Process] = None

    def start(): Unit = {
      port = freePort()
      val java = Path.of(System.getProperty("java.home"), "bin", "java").toString
      val settings = Vector(
        s"llm4s.agent.checkpointer.postgres.schema=$schema",
        // the store's bound (connection + call timeout) stays inside the claim's ttl - renewEvery (Stage 5 rule 2)
        "llm4s.agent.checkpointer.postgres.callTimeout=1s",
        "llm4s.agent.checkpointer.postgres.connectionTimeout=1s",
        "llm4s.agent.checkpointer.postgres.maxPoolSize=6",
        s"llm4s.agent.runtime.claims.ttl=${Ttl.toMillis}ms",
        "llm4s.agent.runtime.claims.renewEvery=1s",
        "llm4s.agent.runtime.watch.pollInterval=100ms",
        s"llm4s.agent.queue.lease.ttl=${Ttl.toMillis}ms",
        "llm4s.agent.queue.lease.renewEvery=1s",
        "llm4s.agent.worker.pollInterval=200ms",
        "llm4s.agent.worker.drain.grace=5s",
        "llm4s.agent.worker.drain.exitTimeout=2s",
        // the retention sweep every instance's worker runs (one at a time): tombstones are reclaimed 3 s after a delete
        "llm4s.agent.worker.sweep.every=1s",
        "llm4s.agent.worker.sweep.tombstoneAge=3s"
      ).map(s => s"-D$s")
      val command = Vector(java, "-Xmx256m") ++ settings ++
        Vector("-cp", System.getProperty("java.class.path"), "org.llm4s.deploy.DeployServiceMain")
      val builder =
        new ProcessBuilder(command.asJava).redirectErrorStream(true).redirectOutput(Redirect.appendTo(log.toFile))
      val env = builder.environment()
      env.put("PORT", port.toString)
      env.put("LLM4S_CHECKPOINTER", "postgres")
      env.put("LLM4S_CHECKPOINTER_POSTGRES_URL", PgCheckpointerDb.jdbcUrl)
      env.put("LLM4S_CHECKPOINTER_POSTGRES_USER", PgCheckpointerDb.user)
      env.put("LLM4S_CHECKPOINTER_POSTGRES_PASSWORD", PgCheckpointerDb.password)
      env.put("LLM4S_RUNTIME_LABEL", label)
      env.put("LLM4S_WORKER_LABEL", label)
      env.put("LLM4S_SERVICE_TOOL_DELAY", s"${ToolDelay.toMillis} milliseconds")
      proc = Some(builder.start())
    }

    def process: Process = proc.getOrElse(fail(s"$label was never started"))
    def output: String   = Try(Files.readString(log)).getOrElse("")

    /** Whether `holder` - a lease holder, the worker's label and a random suffix - is this instance's worker. */
    def owns(holder: String): Boolean = holder.startsWith(s"$label-")

    def awaitReady(): Unit = {
      val deadline = System.nanoTime() + 90.seconds.toNanos
      while (!get("/ready").exists(_.statusCode() == 200) && System.nanoTime() < deadline) {
        if (!process.isAlive) fail(s"$label exited before it was ready:\n$output")
        TimeUnit.MILLISECONDS.sleep(100)
      }
      withClue(output)(get("/ready").map(_.statusCode()) shouldBe Some(200))
    }

    /** `kill -9`: no shutdown hook, no drain; its claims and leases stay until they expire. */
    def kill(): Unit = {
      process.destroyForcibly()
      process.waitFor(30, TimeUnit.SECONDS) shouldBe true
    }

    /** SIGTERM: the service drains its worker and exits. */
    def stop(): Unit = proc.filter(_.isAlive).foreach { p =>
      p.destroy()
      if (!p.waitFor(30, TimeUnit.SECONDS)) p.destroyForcibly(): Unit
    }

    def request(path: String): HttpRequest.Builder =
      HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(30))

    def get(path: String, headers: (String, String)*): Option[HttpResponse[String]] =
      Try(
        http.send(
          headers.foldLeft(request(path))((b, h) => b.header(h._1, h._2)).GET().build(),
          HttpResponse.BodyHandlers.ofString()
        )
      ).toOption

    def post(path: String, body: String, headers: (String, String)*): HttpResponse[String] =
      http.send(
        headers
          .foldLeft(request(path))((b, h) => b.header(h._1, h._2))
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .build(),
        HttpResponse.BodyHandlers.ofString()
      )

    override def toString: String = label
  }

  private val machineA  = new Instance("exit-a")
  private val machineB  = new Instance("exit-b")
  private val machineC  = new Instance("exit-c")
  private val instances = Vector(machineA, machineB, machineC)

  /** The test's own connection to the schema, as an operator's: entries, logs, checkpoints and the delete. */
  @volatile private var opened: Option[PostgresCheckpointer] = None
  private def store: PostgresCheckpointer                    = opened.getOrElse(fail("the store is not open"))

  override def beforeAll(): Unit = if (PgCheckpointerDb.enabled) {
    instances.foreach(_.start()) // all three migrate the fresh schema at once (design 4.50)
    instances.foreach(_.awaitReady())
    opened = Some(
      PostgresCheckpointer
        .open(
          PgCheckpointerDb.jdbcUrl,
          PgCheckpointerDb.user,
          PgCheckpointerDb.password,
          PostgresCheckpointerConfig(schema)
        )
        .fold(e => throw new IllegalStateException(e.message), identity)
    )
  }

  override def afterAll(): Unit = if (PgCheckpointerDb.enabled) {
    instances.foreach(_.stop())
    opened.foreach(_.close())
    PgCheckpointerDb.dropSchema(schema)
  }

  // ---- helpers ----

  /** Restarts any instance an earlier test left dead, so one failure does not fail every later test. */
  private def ensureRunning(): Unit = {
    val dead = instances.filterNot(i => Try(i.process.isAlive).getOrElse(false))
    dead.foreach(_.start())
    dead.foreach(_.awaitReady())
  }

  /** Polls `probe` until it answers, failing with `what` and the instances' logs after `within`. */
  private def await[A](what: String, within: FiniteDuration = 60.seconds)(probe: => Option[A]): A = {
    val deadline = System.nanoTime() + within.toNanos
    var found    = probe
    while (found.isEmpty && System.nanoTime() < deadline) {
      TimeUnit.MILLISECONDS.sleep(25)
      found = probe
    }
    found.getOrElse(fail(s"timed out after $within waiting for $what\n$logs"))
  }

  private def logs: String =
    instances
      .map(i => s"---- ${i.label} ----\n${i.output.linesIterator.toVector.takeRight(40).mkString("\n")}")
      .mkString("\n")

  /** Submits a run through `via`; its entry id. */
  private def submit(
    via: Instance,
    thread: String,
    query: String,
    policy: String = "reject",
    headers: Seq[(String, String)] = Nil
  ): String = {
    val reply =
      via.post(s"/threads/$thread/runs", ujson.write(ujson.Obj("query" -> query, "policy" -> policy)), headers*)
    withClue(reply.body())(reply.statusCode() shouldBe 202)
    ujson.read(reply.body())("entryId").str
  }

  private def entry(id: String): QueueEntry =
    EntryId
      .parse(id)
      .flatMap(store.runQueue.entry(None, _))
      .fold(e => fail(s"entry $id: ${e.message}"), identity)

  private def closed(id: String): QueueEntry = await(s"entry $id to close")(Some(entry(id)).filterNot(_.isOpen))

  /** The instance whose worker took entry `id`'s first attempt. */
  private def executorOf(id: String): Instance = {
    val holder = await(s"a worker to take entry $id")(entry(id).attempts.headOption.map(_.holder))
    instances.find(_.owns(holder)).getOrElse(fail(s"no instance owns lease holder $holder"))
  }

  private def log(thread: String): Vector[EventRecord] =
    store.eventsAfter(ThreadId(thread), 0L, 10000).fold(e => fail(e.message), identity)

  private def custom(r: EventRecord, name: String): Option[ujson.Value] = r.event match {
    case RunEvent.Custom(`name`, _, payload) => Some(payload)
    case _                                   => None
  }

  /** The demo tool calls `via` is executing now (`llm4s_demo_tool_calls_active`, set inside the tool itself). */
  private def activeToolCalls(via: Instance): Double =
    via
      .get("/metrics")
      .flatMap(_.body().linesIterator.find(_.startsWith("llm4s_demo_tool_calls_active ")))
      .flatMap(_.split(' ').lastOption.flatMap(_.toDoubleOption))
      .getOrElse(0.0)

  /**
   * Waits until entry `id`'s run is inside its tool call on the instance executing it, and answers that instance. The
   * tool itself reports that it has started (on its instance's `/metrics`): the store records a tool call only once it
   * has finished, and a committed model call asking for one says only that the tool is next - the run may still stop
   * at the boundary before it. Each test has one run executing at a time, so a call in progress on the executing
   * instance is this run's; the store must show no result yet for the call the model asked for.
   */
  private def awaitToolCall(thread: String, id: String): Instance =
    await(s"entry $id's tool call to start") {
      val runs     = entry(id).runIds.map(_.value)
      val events   = log(thread).filter(r => runs(r.runId))
      val asked    = events.lastIndexWhere(r => custom(r, "agent.model_call_completed").exists(_("toolCalls").num > 0))
      val finished = events.drop(math.max(asked, 0)).exists(r => custom(r, "agent.tool_executed").isDefined)
      entry(id).attempts.lastOption
        .flatMap(a => instances.find(_.owns(a.holder)))
        .filter(i => asked >= 0 && !finished && activeToolCalls(i) >= 1)
    }

  /** The thread as `via` shows it. */
  private def threadView(via: Instance, id: String): ujson.Value = {
    val reply = via.get(s"/threads/$id").getOrElse(fail(s"no answer from $via"))
    withClue(reply.body())(reply.statusCode() shouldBe 200)
    ujson.read(reply.body())
  }

  private def messages(t: ujson.Value): Vector[ujson.Value] = t("messages").arr.toVector.map(_("message"))

  private def answers(t: ujson.Value): Vector[String] =
    messages(t).filter(_("type").str == "assistant").flatMap(_("data")("contentOpt").strOpt)

  /**
   * A history a provider accepts: it starts with the user, every assistant tool call is answered by a tool message
   * directly after it (before anything else), no tool message answers a call not just made, and it ends with an answer.
   */
  private def assertProviderValid(t: ujson.Value): Unit = {
    val ms = messages(t)
    withClue(ujson.write(t("messages"))) {
      ms.headOption.map(_("type").str) shouldBe Some("user")
      var open = Set.empty[String]
      ms.foreach { m =>
        m("type").str match {
          case "tool" =>
            open should contain(m("toolCallId").str)
            open -= m("toolCallId").str
          case other =>
            open shouldBe empty
            if (other == "assistant") open = m("data")("toolCalls").arr.map(_("id").str).toSet
        }
      }
      open shouldBe empty
      ms.last("type").str shouldBe "assistant"
      ms.last("data")("toolCalls").arr shouldBe empty
    }
  }

  /** One Server-Sent Event. */
  final case class Frame(id: Option[Long], event: String, data: String)

  private def frames(lines: Iterator[String]): Iterator[Frame] = new Iterator[Frame] {
    private var pending: Option[Frame] = None
    private def read(): Option[Frame] = {
      var id: Option[Long] = None
      var event            = "message"
      val data             = new StringBuilder
      var any              = false
      var done             = false
      while (!done && lines.hasNext) {
        val line = lines.next()
        if (line.isEmpty) { if (any) done = true }
        else if (line.startsWith(":")) ()
        else {
          any = true
          if (line.startsWith("id: ")) id = Some(line.drop(4).toLong)
          else if (line.startsWith("event: ")) event = line.drop(7)
          else if (line.startsWith("data: ")) data.append(line.drop(6))
        }
      }
      Option.when(any)(Frame(id, event, data.toString))
    }
    def hasNext: Boolean = { if (pending.isEmpty) pending = read(); pending.isDefined }
    def next(): Frame    = { val f = pending.getOrElse(read().get); pending = None; f }
  }

  /** A client following entry `id`'s events live through `via`, on a thread of its own. */
  final class Follower(via: Instance, id: String) {
    val received: CopyOnWriteArrayList[Frame] = new CopyOnWriteArrayList[Frame]()
    private val finished                      = new CountDownLatch(1)
    @volatile var failure: Option[Throwable]  = None

    Thread.ofVirtual().start { () =>
      Try {
        val response = http.send(
          via.request(s"/runs/$id/events").timeout(Duration.ofMinutes(3)).GET().build(),
          HttpResponse.BodyHandlers.ofLines()
        )
        val it    = frames(response.body().iterator().asScala)
        var ended = false
        while (!ended && it.hasNext) {
          val f = it.next()
          received.add(f)
          ended = f.event == "end" || f.event == "error"
        }
      }.failed.foreach(e => failure = Some(e))
      finished.countDown()
    }: Unit

    def events: Vector[Frame] = received.asScala.toVector.filter(_.event == "run-event")

    def awaitEnd(): Vector[Frame] = {
      withClue(logs)(finished.await(90, TimeUnit.SECONDS) shouldBe true)
      failure.foreach(e => fail(s"following $id through $via failed", e))
      received.asScala.toVector
    }
  }

  /** Entry `id`'s events through `via` after `after` (as `?after=`, or the `Last-Event-ID` header), to the stream's end. */
  private def replay(via: Instance, id: String, after: Long, header: Boolean): Vector[Frame] = {
    val response =
      if (header) via.get(s"/runs/$id/events", "Last-Event-ID" -> after.toString)
      else via.get(s"/runs/$id/events?after=$after")
    val r = response.getOrElse(fail(s"no answer from $via"))
    r.statusCode() shouldBe 200
    frames(r.body().linesIterator).toVector
  }

  private val random = new SecureRandom()
  private def hex(bytes: Int): String = {
    val b = new Array[Byte](bytes)
    random.nextBytes(b)
    HexFormat.of().formatHex(b)
  }

  /** What the kill test saw, for the trace test: the trace id sent, the thread, and each attempt's run and instance. */
  final case class Survived(traceId: String, thread: String, attempts: Vector[(String, Instance)])
  @volatile private var survived: Option[Survived] = None

  // ---- the exit criteria ----

  "Three example-service instances on one Postgres" should
    "run a run submitted through A on any worker, stream it live through B, replay it through C, and survive a kill -9 of the machine running it" in {
      PgCheckpointerDb.require()
      ensureRunning()
      val traceId = hex(16)
      val thread  = "exit-kill"
      val id = submit(machineA, thread, "survive a kill", headers = Seq("traceparent" -> s"00-$traceId-${hex(8)}-01"))

      val executing            = executorOf(id) // whichever worker took it
      val others               = instances.filterNot(_ == executing)
      val (streamer, replayer) = (others.head, others.last)
      val follower             = new Follower(streamer, id)
      awaitToolCall(thread, id) shouldBe executing
      await(s"$streamer to stream entry $id live")(Option.when(follower.events.nonEmpty)(()))
      val beforeKill = follower.events.size

      executing.kill() // kill -9 mid-tool-call: its claim and lease are left to expire

      val streamed = follower.awaitEnd()
      streamed.last.event shouldBe "end"
      val done = closed(id)
      done.outcome.get shouldBe a[EntryOutcome.Done]
      ujson.read(streamed.last.data)("state").str shouldBe "done"

      // recovered by another instance's worker, as a second attempt with a run id of its own
      withClue(done.attempts)(done.attempts should have size 2)
      executing.owns(done.attempts(0).holder) shouldBe true
      val recoverer = others.find(_.owns(done.attempts(1).holder))
      withClue(done.attempts(1).holder)(recoverer should not be empty)
      info(
        s"submitted through $machineA, executed by $executing (killed), streamed through $streamer, " +
          s"recovered by ${recoverer.get}, replayed through $replayer"
      )

      // the stream went on through the kill, with no gap and no duplicate: exactly the thread's log, in order
      val events = follower.events
      val ids    = events.map(_.id.getOrElse(fail("a run event without an id")))
      events.size should be > beforeKill
      ids shouldBe log(thread).map(_.seq)
      ids shouldBe (ids.head to ids.last).toVector
      events.map(f => ujson.read(f.data)("runId").str).toSet shouldBe done.runIds.map(_.value)

      // replayed from any sequence number through the third instance: the same events, byte for byte
      val byId = events.map(f => f.id.get -> f.data).toMap
      for (after <- Vector(0L, ids(1), ids(ids.size / 2), ids.last - 1, ids.last)) {
        val replayed = replay(replayer, id, after, header = after == ids(ids.size / 2))
        replayed.last.event shouldBe "end"
        val got = replayed.filter(_.event == "run-event")
        withClue(s"after $after: ")(got.map(_.id.get) shouldBe ids.filter(_ > after))
        got.foreach(f => f.data shouldBe byId(f.id.get))
      }

      val t = threadView(replayer, thread)
      t("status").str shouldBe "completed"
      answers(t) shouldBe Vector("Echo: survive a kill")
      assertProviderValid(t)

      survived = Some(
        Survived(traceId, thread, done.attempts.map(a => a.runId.value -> instances.find(_.owns(a.holder)).get))
      )

      // a replacement instance under the same name joins, as a restarted pod would
      executing.start()
      executing.awaitReady()
    }

  it should "keep the run that survived the kill in one trace across the two machines that executed it" in {
    PgCheckpointerDb.require()
    ensureRunning()
    val s = survived.getOrElse(cancel("the kill test did not get far enough to record its run"))
    s.attempts.map(_._2).distinct should have size 2
    val checkpoints = store.history(ThreadId(s.thread), None, 1000).fold(e => fail(e.message), identity)
    s.attempts.foreach { case (runId, machine) =>
      val own = checkpoints.filter(_.runId == runId)
      withClue(s"run $runId on $machine: ")(own should not be empty)
      own.foreach(c => c.trace.map(_.traceId) shouldBe Some(s.traceId))
    }
  }

  it should "queue a second message to a busy thread under Enqueue and run it after the first" in {
    PgCheckpointerDb.require()
    ensureRunning()
    val thread = "exit-enqueue"
    val first  = submit(machineA, thread, "first")
    awaitToolCall(thread, first): Unit

    val busy = machineB.post(s"/threads/$thread/runs", """{"query": "rejected"}""")
    withClue(busy.body())(busy.statusCode() shouldBe 409) // Reject, the default, refuses a busy thread

    val second = submit(machineB, thread, "second", "enqueue")
    entry(second).position should not be empty

    closed(first).outcome.get shouldBe a[EntryOutcome.Done]
    closed(second).outcome.get shouldBe a[EntryOutcome.Done]
    val events     = log(thread)
    val firstRuns  = entry(first).runIds.map(_.value)
    val secondRuns = entry(second).runIds.map(_.value)
    val firstSeqs  = events.filter(e => firstRuns(e.runId)).map(_.seq)
    val secondSeqs = events.filter(e => secondRuns(e.runId)).map(_.seq)
    firstSeqs should not be empty
    secondSeqs should not be empty
    firstSeqs.max should be < secondSeqs.min

    val t = threadView(machineC, thread)
    answers(t) shouldBe Vector("Echo: first", "Echo: second")
    assertProviderValid(t)
  }

  it should "under Interrupt, stop the first run at its next superstep boundary and run the second in its place" in {
    PgCheckpointerDb.require()
    ensureRunning()
    val thread = "exit-interrupt"
    val first  = submit(machineA, thread, "first")
    awaitToolCall(thread, first): Unit

    val second = submit(machineC, thread, "second", "interrupt")

    closed(first).outcome shouldBe Some(EntryOutcome.Interrupted(EntryId.parse(second).toOption))
    closed(second).outcome.get shouldBe a[EntryOutcome.Done]

    val events     = log(thread)
    val firstRuns  = entry(first).runIds.map(_.value)
    val secondRuns = entry(second).runIds.map(_.value)
    val firstOwn   = events.filter(e => firstRuns(e.runId))
    // the started tool call finished: its result is a success, not a cancellation
    firstOwn.flatMap(custom(_, "agent.tool_executed")).map(_("outcome").str) shouldBe Vector("Succeeded")
    // and the first run made no further model call: it stopped at the boundary after the tool's superstep
    firstOwn.flatMap(custom(_, "agent.model_call_completed")) should have size 1
    firstOwn.map(_.seq).max should be < events.filter(e => secondRuns(e.runId)).map(_.seq).min

    val t = threadView(machineB, thread)
    answers(t) shouldBe Vector("Echo: second")
    val toolResults = messages(t).filter(_("type").str == "tool").map(_("content").str)
    toolResults should have size 2
    toolResults.foreach(_ should include("paused"))
    assertProviderValid(t)
  }

  it should "stop a run executing on one instance when it is cancelled through another" in {
    PgCheckpointerDb.require()
    ensureRunning()
    val thread    = "exit-cancel"
    val id        = submit(machineA, thread, "never answered")
    val executing = executorOf(id)
    awaitToolCall(thread, id) shouldBe executing
    val via = instances.filterNot(_ == executing).last
    info(s"executed by $executing, cancelled through $via")

    val reply = via.post(s"/runs/$id/cancel", "")
    withClue(reply.body())(reply.statusCode() shouldBe 202)

    val done = closed(id)
    done.outcome.get shouldBe a[EntryOutcome.Cancelled]
    done.attempts should have size 1
    executing.owns(done.attempts.head.holder) shouldBe true
    await(s"$thread to stop running")(Option.when(threadView(via, thread)("status").str != "running")(()))

    // the run was stopped inside its tool call, not let finish
    val runs = done.runIds.map(_.value)
    log(thread)
      .filter(e => runs(e.runId))
      .flatMap(custom(_, "agent.tool_executed"))
      .map(_("outcome").str) should not contain "Succeeded"
    answers(threadView(via, thread)) shouldBe empty
    executing.process.isAlive shouldBe true
  }

  it should "reclaim a deleted thread's tombstone in a retention sweep and answer a reader of the reclaimed id ThreadDeleted" in {
    PgCheckpointerDb.require()
    ensureRunning()
    val thread = "exit-reclaim"
    val id     = submit(machineA, thread, "soon deleted")
    closed(id).outcome.get shouldBe a[EntryOutcome.Done]
    val lastSeq = log(thread).map(_.seq).max

    // the service kernel's delete, as a service's DELETE would call it
    val submitter = new RunSubmitter(store.runQueue, store)
    submitter.deleteThread(new GraphRuntime(store), None, ThreadId(thread), 30.seconds) shouldBe Right(())
    tombstone(thread) shouldBe defined

    def assertReaderToldDeleted(): Unit = {
      // a reader of the old log resuming after event 1: refused, never shown another thread's events
      val frames = replay(machineC, id, 1L, header = true)
      frames.map(_.event) shouldBe Vector("error")
      ujson.read(frames.head.data)("error").str shouldBe "ThreadDeleted"
    }
    assertReaderToldDeleted() // before reclamation

    // the workers' sweep (every second, tombstones older than 3 s) reclaims it
    await(s"$thread's tombstone to be reclaimed", 60.seconds)(Option.when(tombstone(thread).isEmpty)(()))
    reclaimedMark should be > lastSeq

    assertReaderToldDeleted() // after reclamation
    machineB.get(s"/threads/$thread").map(_.statusCode()) shouldBe Some(404)

    // a new thread under the reclaimed id numbers past the old log, and the old reader is still refused
    val reused = submit(machineB, thread, "a new thread, same id")
    closed(reused).outcome.get shouldBe a[EntryOutcome.Done]
    log(thread).map(_.seq).min should be > lastSeq + 1
    assertReaderToldDeleted()
    answers(threadView(machineA, thread)) shouldBe Vector("Echo: a new thread, same id")
  }

  /** The thread's row while it is a tombstone: when it was deleted, by the database's clock. */
  private def tombstone(thread: String): Option[java.time.Instant] =
    PgCheckpointerDb.withConnection { c =>
      Using.resource(
        c.prepareStatement(s"""SELECT deleted_at FROM "$schema".checkpoint_threads WHERE thread_id = ?""")
      ) { st =>
        st.setString(1, thread)
        Using.resource(st.executeQuery()) { rows =>
          if (rows.next()) Option(rows.getTimestamp(1)).map(_.toInstant) else None
        }
      }
    }

  /** The store's reclamation mark (design 4.53): the first event number of any id without a row. */
  private def reclaimedMark: Long =
    PgCheckpointerDb.scalar(s"""SELECT first_seq FROM "$schema".checkpoint_reclaimed""")(_.getLong(1))
}
