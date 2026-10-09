package org.llm4s.agent.graph.sqlite

import org.llm4s.agent.graph.*
import org.llm4s.agent.testkit.ManualClock
import org.llm4s.error.{ ProcessingError, ValidationError }
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, Path }
import java.sql.{ Connection, DriverManager }
import java.time.{ Clock, Instant }
import java.util.concurrent.{ CountDownLatch, CyclicBarrier, LinkedBlockingQueue, TimeUnit }
import scala.concurrent.duration.*
import scala.util.{ Try, Using }

class SqliteCheckpointerSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private val start = Instant.parse("2026-10-10T09:00:00Z")
  private val ttl   = 30.seconds

  private def jdbc(path: Path): Connection = DriverManager.getConnection(SqliteCheckpointer.jdbcUrl(path))

  private def scalar[A](path: Path, sql: String)(read: java.sql.ResultSet => A): A =
    Using.resource(jdbc(path)) { c =>
      Using.resource(c.createStatement()) { s =>
        Using.resource(s.executeQuery(sql)) { rows =>
          rows.next() shouldBe true
          read(rows)
        }
      }
    }

  private def exec(path: Path, sql: String*): Unit =
    Using.resource(jdbc(path))(c => Using.resource(c.createStatement())(s => sql.foreach(s.executeUpdate(_): Unit)))

  private def checkpoint(thread: ThreadId, id: String, parent: Option[String], token: FencingToken): Checkpoint =
    Checkpoint(
      Checkpoint.CurrentFormat,
      id,
      parent,
      thread.value,
      "run",
      CheckpointStatus.Running,
      start,
      GraphSnapshot("g", "v1", "f", 0, Map.empty, Vector.empty, Vector.empty, Vector.empty, Vector.empty, false),
      None,
      Some(token)
    )

  private def event(name: String): EventDraft =
    EventDraft("run", None, None, None, start, RunEvent.Custom(name, 1, ujson.Obj("name" -> name)))

  // ---- opening ----

  "Opening a SqliteCheckpointer" should "create the schema at the current version, in WAL mode, on a new file" in {
    val db    = SqliteFiles.fresh()
    val store = SqliteCheckpointer.open(db).value
    store.path shouldBe db
    scalar(db, "SELECT version FROM llm4s_checkpoint_schema")(_.getInt(1)) shouldBe SqliteSchema.Current
    scalar(db, "PRAGMA journal_mode")(_.getString(1)) shouldBe "wal"
    scalar(db, "SELECT value FROM llm4s_checkpoint_meta WHERE key = 'last_token'")(_.getLong(1)) shouldBe 0L
    store.close()
    // opening again migrates nothing and keeps one version row
    SqliteCheckpointer.open(db, Clock.systemUTC()).value.close()
    scalar(db, "SELECT COUNT(*) FROM llm4s_checkpoint_schema")(_.getInt(1)) shouldBe 1
  }

  it should "let many stores open one new file at once, migrating it exactly once, round after round" in {
    // the race is on a file that does not exist yet: every opener finds it in rollback-journal mode and tries to
    // switch it to WAL at once. One round rarely shows a lost race, so it is run many times, each on a new file.
    val rounds  = 40
    val openers = 8
    val failures = (1 to rounds).flatMap { round =>
      val db      = SqliteFiles.fresh()
      val barrier = new CyclicBarrier(openers)
      val opened  = new LinkedBlockingQueue[Either[String, SqliteCheckpointer]]()
      val threads = (1 to openers).map { _ =>
        Thread.ofPlatform().start { () =>
          barrier.await(10, TimeUnit.SECONDS): Unit
          opened.put(SqliteCheckpointer.open(db).left.map(_.message))
        }
      }
      threads.foreach(_.join(30000))
      val all = Iterator.continually(opened.poll()).takeWhile(_ != null).toVector
      all.foreach(_.foreach(_.close()))
      def read[A](sql: String)(get: java.sql.ResultSet => A): Either[String, A] =
        Try(scalar(db, sql)(get)).toEither.left.map(e => s"round $round: $sql failed: ${e.getMessage}")
      val checks = Vector(
        read("SELECT COUNT(*) FROM llm4s_checkpoint_schema")(_.getInt(1)).filterOrElse(
          _ == 1,
          s"round $round: not exactly one schema version row"
        ),
        read("SELECT COUNT(*) FROM llm4s_checkpoint_meta")(_.getInt(1)).filterOrElse(
          _ == 1,
          s"round $round: not exactly one meta row"
        ),
        read("PRAGMA journal_mode")(_.getString(1)).filterOrElse(_ == "wal", s"round $round: not in WAL mode")
      )
      SqliteFiles.delete(db)
      Option.when(all.size != openers)(s"round $round: ${all.size} of $openers opens returned").toVector ++
        all.collect { case Left(message) => s"round $round: an open failed: $message" } ++
        checks.collect { case Left(problem) => problem }
    }
    withClue(failures.mkString("\n", "\n", "\n"))(failures shouldBe empty)
  }

  it should "refuse a file at a newer schema version, changing nothing" in {
    val db = SqliteFiles.fresh()
    SqliteCheckpointer.open(db).value.close()
    exec(db, "UPDATE llm4s_checkpoint_schema SET version = 99")
    val refused = SqliteCheckpointer.open(db).left.value
    refused shouldBe a[ProcessingError]
    refused.message should (include("version 99").and(include(db.toString)))
    scalar(db, "SELECT version FROM llm4s_checkpoint_schema")(_.getInt(1)) shouldBe 99
  }

  it should "refuse a file that is not a database, and close the connection it opened" in {
    val db = SqliteFiles.fresh()
    Files.writeString(db, "this is not a SQLite database, not even close" * 100)
    var connection: Option[Connection] = None
    val refused = SqliteCheckpointer
      .openWith(
        db,
        Clock.systemUTC(),
        SqliteCheckpointerConfig.default,
        url => {
          val c = DriverManager.getConnection(url)
          connection = Some(c)
          c
        }
      )
      .left
      .value
    refused shouldBe a[ProcessingError]
    connection.value.isClosed shouldBe true
  }

  it should "refuse a path whose directory does not exist" in {
    SqliteCheckpointer.open(SqliteFiles.directory.resolve("missing").resolve("x.db")).left.value shouldBe
      a[ProcessingError]
  }

  it should "open the file at exactly its path when the path holds a '?', spaces or other URI characters" in {
    // a `?` in a plain jdbc:sqlite URL would start connection settings: here they would name another file and
    // switch the journal off WAL. Windows file names cannot hold `?`, so there the rest of the name is tested.
    val windows = System.getProperty("os.name").toLowerCase.contains("win")
    val name    = if windows then "runs # 100% & more.db" else "runs ?journal_mode=delete&x=1 # 100%.db"
    val dir     = Files.createDirectories(SqliteFiles.directory.resolve(s"odd path ${java.util.UUID.randomUUID()}"))
    val db      = dir.resolve(name)
    val store   = SqliteCheckpointer.open(db).value
    val thread  = ThreadId("odd-path")
    store.claim(thread, ClaimRequest(RunId("run"), ttl)).value: Unit
    Files.exists(db) shouldBe true
    new String(Files.readAllBytes(db).take(15), "US-ASCII") shouldBe "SQLite format 3"
    // nothing else was created beside it but SQLite's own WAL and shared-memory files
    Using.resource(Files.list(dir))(_.toArray.map(_.toString).toSet) shouldBe
      Set(db.toString, s"$db-wal", s"$db-shm")
    scalar(db, "PRAGMA journal_mode")(_.getString(1)) shouldBe "wal"
    store.close()
    val again = SqliteFiles.opened(db, Clock.systemUTC())
    again.latest(thread).value shouldBe None
    again.close()
  }

  // ---- claims ----

  "A claim" should "store its expiry to the nanosecond, so a sub-second ttl lapses exactly when it should" in {
    val db      = SqliteFiles.fresh()
    val begun   = Instant.parse("2026-10-10T09:00:00.123456789Z")
    val clock   = ManualClock(begun)
    val store   = SqliteFiles.opened(db, clock)
    val thread  = ThreadId("sub-second")
    val claimed = store.claim(thread, ClaimRequest(RunId("first"), 1500.millis)).value
    claimed.expiresAt shouldBe Instant.parse("2026-10-10T09:00:01.623456789Z")
    scalar(db, "SELECT claim_expires_second, claim_expires_nano FROM llm4s_checkpoint_threads") { rows =>
      (rows.getLong(1), rows.getLong(2))
    } shouldBe ((claimed.expiresAt.getEpochSecond, 623456789L))
    store.close()
    // read back by another store, as after a restart: live one nanosecond before the expiry, lapsed at it
    val reopened = SqliteFiles.opened(db, clock)
    clock.set(claimed.expiresAt.minusNanos(1))
    reopened.claim(thread, ClaimRequest(RunId("second"), 1500.millis)).left.value shouldBe
      GraphError.ThreadBusy(thread.value, None, Some("first"))
    clock.set(claimed.expiresAt)
    val taken = reopened.claim(thread, ClaimRequest(RunId("second"), 1500.millis)).value
    taken.token.value should be > claimed.token.value
    // a renewal stores its expiry exactly too
    clock.set(claimed.expiresAt.plusNanos(250))
    reopened.renew(thread, taken.token, 1500.millis).value.expiresAt shouldBe
      Instant.parse("2026-10-10T09:00:03.123457039Z")
    reopened.close()
    val third = SqliteFiles.opened(db, clock)
    third.renew(thread, taken.token, 1.nano).value.expiresAt shouldBe claimed.expiresAt.plusNanos(251)
    third.close()
  }

  // ---- events ----

  "Compacting the event log" should "keep its replay floor in the file, so a reopened store refuses below it" in {
    val db     = SqliteFiles.fresh()
    val store  = SqliteFiles.opened(db, ManualClock(start))
    val thread = ThreadId("compacted")
    val token  = store.claim(thread, ClaimRequest(RunId("run"), ttl)).value.token
    store.commit(thread, Commit(token, events = (1 to 5).map(i => event(s"e$i")).toVector)).value: Unit
    store.compactEvents(thread, 4L).value
    store.close()

    val reopened = SqliteFiles.opened(db, ManualClock(start))
    reopened.eventsAfter(thread, 0L, 10).left.value shouldBe GraphError.ReplayUnavailable(thread.value, 4L)
    reopened.eventsAfter(thread, 2L, 10).left.value shouldBe GraphError.ReplayUnavailable(thread.value, 4L)
    reopened.eventsAfter(thread, 3L, 10).value.map(_.seq) shouldBe Vector(4L, 5L)
    scalar(db, "SELECT COUNT(*) FROM llm4s_checkpoint_events")(_.getInt(1)) shouldBe 2
    // numbering goes on from where it was, and compacting below the floor does not lower it
    reopened.commit(thread, Commit(token, events = Vector(event("e6")))).value.map(_.seq) shouldBe Vector(6L)
    reopened.compactEvents(thread, 2L).value
    reopened.eventsAfter(thread, 3L, 10).value.map(_.seq) shouldBe Vector(4L, 5L, 6L)
    reopened.close()
  }

  "The schema migrations" should "run each step in turn up to the target, and report a missing step" in {
    val db = SqliteFiles.fresh()
    val addStep =
      (c: Connection) => Using.resource(c.createStatement())(_.executeUpdate("CREATE TABLE later (x)"): Unit)
    Using.resource(jdbc(db)) { c =>
      SqliteSchema.migrate(c, target = 2, steps = SqliteSchema.steps + (1 -> addStep)) shouldBe Right(0)
      SqliteSchema.version(c) shouldBe 2
      SqliteSchema.migrate(c, target = 2, steps = Map.empty) shouldBe Right(2)
      SqliteSchema.migrate(c, target = 3, steps = Map.empty).left.value should include(
        "from checkpoint schema version 2"
      )
      SqliteSchema.migrate(c, target = 1).left.value should include("newer")
      SqliteSchema.version(c) shouldBe 2
    }
  }

  // ---- atomicity and data ----

  "A commit" should "apply nothing when the database fails part-way through writing it" in {
    val db     = SqliteFiles.fresh()
    val store  = SqliteFiles.opened(db, ManualClock(start))
    val thread = ThreadId("atomic")
    val token  = store.claim(thread, ClaimRequest(RunId("run"), ttl)).value.token
    store.commit(thread, Commit(token, Some(checkpoint(thread, "c1", None, token)), events = Vector(event("ok")))).value
    // a trigger stands in for a failure in the middle of the transaction: the second event cannot be stored
    exec(
      db,
      """CREATE TRIGGER fail_boom BEFORE INSERT ON llm4s_checkpoint_events
        |WHEN instr(NEW.record_json, 'boom') > 0 BEGIN SELECT RAISE(ABORT, 'boom refused'); END""".stripMargin
    )
    val failed = store.commit(
      thread,
      Commit(
        token,
        Some(checkpoint(thread, "c2", Some("c1"), token)),
        Vector(PendingWrite("c2", "1.0", "n", Vector.empty, Vector.empty)),
        Vector(event("fine"), event("boom"))
      )
    )
    failed.left.value shouldBe a[ProcessingError]
    failed.left.value.message should include("boom refused")
    val stored = store.latest(thread).value.value
    stored.checkpoint.id shouldBe "c1"
    stored.pendingWrites shouldBe empty
    store.eventsAfter(thread, 0L, 10).value.map(_.seq) shouldBe Vector(1L)
    exec(db, "DROP TRIGGER fail_boom")
    store.commit(thread, Commit(token, events = Vector(event("next")))).value.map(_.seq) shouldBe Vector(2L)
    store.close()
  }

  "The stored data" should "be versioned JSON, and a row that does not decode a Left, not an exception" in {
    val db     = SqliteFiles.fresh()
    val store  = SqliteFiles.opened(db, ManualClock(start))
    val thread = ThreadId("data")
    val token  = store.claim(thread, ClaimRequest(RunId("run"), ttl)).value.token
    store
      .commit(
        thread,
        Commit(
          token,
          Some(checkpoint(thread, "c1", None, token)),
          Vector(PendingWrite("c1", "0.0", "n", Vector.empty, Vector.empty)),
          Vector(event("a"))
        )
      )
      .value
    val json = ujson.read(scalar(db, "SELECT checkpoint_json FROM llm4s_checkpoint_threads")(_.getString(1)))
    json("formatVersion").num.toInt shouldBe Checkpoint.CurrentFormat
    json("fencingToken").num.toLong shouldBe token.value
    ujson
      .read(scalar(db, "SELECT write_json FROM llm4s_checkpoint_pending_writes")(_.getString(1)))("taskId")
      .str shouldBe
      "0.0"
    store.eventsAfter(thread, 0L, 0).value shouldBe empty

    def undecodable(result: Either[org.llm4s.error.LLMError, ?], operation: String, what: String): Unit =
      result.left.value match {
        case e: ProcessingError =>
          e.operation shouldBe s"sqlite-checkpointer.$operation"
          e.message should (include(s"stored $what does not decode").and(include(db.toString)))
        case other => fail(s"not a ProcessingError: $other")
      }
    exec(db, "UPDATE llm4s_checkpoint_events SET record_json = '{not json'")
    undecodable(store.eventsAfter(thread, 0L, 10), "eventsAfter", "event")
    exec(db, "UPDATE llm4s_checkpoint_pending_writes SET write_json = '[]'")
    undecodable(store.latest(thread), "latest", "pending write")
    exec(
      db,
      s"""UPDATE llm4s_checkpoint_threads SET checkpoint_json = '{"formatVersion": ${Checkpoint.CurrentFormat}, "id": 7}'"""
    )
    undecodable(store.latest(thread), "latest", "checkpoint")
    exec(db, "UPDATE llm4s_checkpoint_threads SET checkpoint_json = 'not json at all'")
    undecodable(store.latest(thread), "latest", "checkpoint")
    exec(db, "UPDATE llm4s_checkpoint_threads SET checkpoint_json = '{\"formatVersion\": 999}'")
    store.latest(thread).left.value shouldBe GraphError.UnsupportedCheckpointFormat(999, Checkpoint.CurrentFormat)
    store.close()
  }

  // ---- connections ----

  "A closed store" should "refuse every call with a ProcessingError, and close only once" in {
    val store  = SqliteFiles.opened(SqliteFiles.fresh(), ManualClock(start))
    val thread = ThreadId("closed")
    store.isClosed shouldBe false
    store.close()
    store.close()
    store.isClosed shouldBe true
    val token = FencingToken(1L)
    val calls = Vector(
      store.claim(thread, ClaimRequest(RunId("run"), ttl)),
      store.renew(thread, token, ttl),
      store.release(thread, token),
      store.commit(thread, Commit(token)),
      store.latest(thread),
      store.eventsAfter(thread, 0L, 10),
      store.compactEvents(thread, 1L),
      store.deleteThread(thread)
    )
    calls.foreach { result =>
      result.left.value shouldBe a[ProcessingError]
      result.left.value.message should include("closed")
    }
  }

  "A write" should "wait up to the busy timeout for another connection's write, then fail changing nothing" in {
    val db = SqliteFiles.fresh()
    val store =
      SqliteCheckpointer.open(db, ManualClock(start), SqliteCheckpointerConfig(busyTimeout = 200.millis)).value
    val thread = ThreadId("busy")
    val token  = store.claim(thread, ClaimRequest(RunId("run"), ttl)).value.token
    store.commit(thread, Commit(token, Some(checkpoint(thread, "c1", None, token)))).value
    Using.resource(jdbc(db)) { other =>
      Using.resource(other.createStatement())(_.execute("BEGIN IMMEDIATE"): Unit)
      val began  = System.nanoTime()
      val failed = store.commit(thread, Commit(token, events = Vector(event("blocked"))))
      (System.nanoTime() - began).nanos should be >= 150.millis // SQLite waits the whole timeout; a margin for timer granularity
      failed.left.value shouldBe a[ProcessingError]
      // WAL: reads go on while another connection writes
      store.latest(thread).value.value.checkpoint.id shouldBe "c1"
      Using.resource(other.createStatement())(_.execute("ROLLBACK"): Unit)
    }
    store.eventsAfter(thread, 0L, 10).value shouldBe empty
    store.commit(thread, Commit(token, events = Vector(event("after")))).value.map(_.seq) shouldBe Vector(1L)
    store.close()
  }

  "Two runtimes, each with a store of its own over one file" should
    "exclude each other, then let one take over the other's expired run and fence the other off" in {
      val db      = SqliteFiles.fresh()
      val clock   = ManualClock(start)
      val one     = SqliteFiles.opened(db, clock)
      val two     = SqliteFiles.opened(db, clock)
      val thread  = ThreadId("two-connections")
      val entered = new CountDownLatch(1)
      val gate    = new CountDownLatch(1)
      val workers = Workers { item =>
        if item == "b" && entered.getCount > 0 then
          entered.countDown()
          gate.await(30, TimeUnit.SECONDS): Unit
        Right(())
      }
      val claims  = ClaimPolicy(ttl = 2.hours, renewEvery = 1.hour)
      val first   = GraphRuntime(one, claims = claims)
      val second  = GraphRuntime(two, claims = claims)
      val running = first.start(thread, workers.graph, Vector("a", "b"), RunConfig().withRunId(RunId("first"))).value
      entered.await(30, TimeUnit.SECONDS) shouldBe true
      val deadline = System.nanoTime() + 30.seconds.toNanos
      while (
        !two.latest(thread).value.exists(_.pendingWrites.exists(_.nodeId == "worker")) &&
        System.nanoTime() < deadline
      ) Thread.sleep(5)

      second.recover(thread, workers.graph).left.value match {
        case GraphError.ThreadBusy(_, _, holder) => holder shouldBe Some("first")
        case other                               => fail(s"not ThreadBusy: $other")
      }
      clock.advance(claims.ttl)
      second.recover(thread, workers.graph, RunConfig().withRunId(RunId("second"))).value.await().value match {
        case RunResult.Completed(_, output, _) => output shouldBe Vector("A", "B")
        case other                             => fail(s"recovery did not complete: $other")
      }
      gate.countDown()
      running.await().value match {
        case RunResult.Failed(_, GraphError.CheckpointWriteFailed(_, _: GraphError.StaleClaim, _)) => succeed
        case other => fail(s"the stale run was not fenced off: $other")
      }
      one.latest(thread).value.value.checkpoint.runId shouldBe "second"
      workers.callsOf("a") shouldBe 1
      one.close()
      two.close()
    }

  // ---- configuration ----

  "SqliteCheckpointerConfig" should "default to a 5 second busy timeout and refuse a non-positive or oversized one" in {
    SqliteCheckpointerConfig.default.busyTimeout shouldBe 5.seconds
    SqliteCheckpointerConfig().withBusyTimeout(1.second).busyTimeout shouldBe 1.second
    SqliteCheckpointerConfig.of(250.millis).value.busyTimeout shouldBe 250.millis
    SqliteCheckpointerConfig.of(Duration.Zero).left.value shouldBe a[ValidationError]
    SqliteCheckpointerConfig.of(-1.second).left.value shouldBe a[ValidationError]
    SqliteCheckpointerConfig.of((Int.MaxValue.toLong + 1).millis).left.value shouldBe a[ValidationError]
    an[IllegalArgumentException] should be thrownBy SqliteCheckpointerConfig(Duration.Zero)
    an[IllegalArgumentException] should be thrownBy SqliteCheckpointerConfig.default.withBusyTimeout(-1.milli)
  }
}
