package org.llm4s.agent.graph.postgres

import org.llm4s.agent.graph.*
import org.llm4s.error.ProcessingError
import org.llm4s.it.tags.Docker
import org.scalatest.{ BeforeAndAfterAll, EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.{ Instant, OffsetDateTime }
import java.util.concurrent.{ CyclicBarrier, LinkedBlockingQueue, TimeUnit }
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * What the contract cannot see about `PostgresCheckpointer` (design 4.50): claims and retention on the server's real
 * clock; the schema - created and migrated once by instances that start together, refused when a newer build migrated
 * it, opened read-only by a role that may not create anything, one schema per store in a shared database; the store's
 * own pool; and interrupts landing inside calls.
 *
 * Tier 2 (`@Docker`): see [[PgCheckpointerDb]].
 */
@Docker
class PostgresCheckpointerSpec
    extends AnyFlatSpec
    with Matchers
    with EitherValues
    with OptionValues
    with BeforeAndAfterAll {

  private val schemas = new java.util.concurrent.ConcurrentLinkedQueue[String]()

  private def fresh(): PostgresCheckpointerConfig = {
    PgCheckpointerDb.require()
    val schema = PgCheckpointerDb.freshSchema()
    schemas.add(schema)
    PostgresCheckpointerConfig(schema = schema)
  }

  private def thread(): ThreadId = ThreadId(s"spec-${java.util.UUID.randomUUID()}")

  private def checkpoint(id: String, parent: Option[String], on: ThreadId) =
    Checkpoint(
      Checkpoint.CurrentFormat,
      id,
      parent,
      on.value,
      "run",
      CheckpointStatus.Running,
      // the producer's clock: a year out, which retention must not read
      Instant.now().minusSeconds(365L * 24 * 3600),
      GraphSnapshot("g", "v1", "f", 0, Map.empty, Vector.empty, Vector.empty, Vector.empty, Vector.empty, false)
    )

  private def event(name: String, checkpointId: Option[String]): EventDraft =
    EventDraft(
      "run",
      checkpointId,
      None,
      None,
      Instant.now().plusSeconds(365L * 24 * 3600),
      RunEvent.Custom(name, 1, ujson.Obj("name" -> name))
    )

  private def dbNow(): Instant =
    PgCheckpointerDb.scalar("SELECT clock_timestamp()")(_.getObject(1, classOf[OffsetDateTime])).toInstant

  // ---- the server's clock ----

  "A claim" should "expire by the database's clock, and be taken over once it has" in {
    val store  = PgCheckpointerDb.store(fresh())
    val t      = thread()
    val before = dbNow()
    val first  = store.claim(t, ClaimRequest(RunId("first"), None, 1.second)).value
    first.expiresAt.isAfter(before.plusMillis(900)) shouldBe true
    first.expiresAt.isBefore(dbNow().plusMillis(1100)) shouldBe true
    store.claim(t, ClaimRequest(RunId("second"), None, 1.second)).left.value shouldBe
      GraphError.ThreadBusy(t.value, None, GraphError.BusyHolder.Run(RunId("first")))
    Thread.sleep(1200)
    val second = store.claim(t, ClaimRequest(RunId("second"), None, 30.seconds)).value
    second.takenOverFrom shouldBe Some(RunId("first"))
    second.token.value should be > first.token.value
    store.close()
  }

  "Retention" should "judge age by when the database committed each row, not by the producer's timestamps" in {
    val store = PgCheckpointerDb.store(fresh())
    val t     = thread()
    val token = store.claim(t, ClaimRequest(RunId("run"), None, 30.seconds)).value.token
    store.commit(t, Commit(token, Some(checkpoint("old", None, t)), events = Vector(event("old", Some("old"))))).value
    Thread.sleep(1500)
    store
      .commit(
        t,
        Commit(token, Some(checkpoint("young", Some("old"), t)), events = Vector(event("young", Some("young"))))
      )
      .value
    store.prune(t, RetentionPolicy(maxAge = Some(1.second))).value
    store.history(t, None, 10).value.map(_.id) shouldBe Vector("young")
    store.eventsAfter(t, 1L, 10).value.map(_.seq) shouldBe Vector(2L)
    store.close()
  }

  // ---- the schema ----

  "A new schema" should "be created and migrated once by sixteen stores opening it at once" in {
    val config  = fresh()
    val barrier = new CyclicBarrier(16)
    val opened  = new LinkedBlockingQueue[Either[String, PostgresCheckpointer]]()
    val threads = (1 to 16).map { _ =>
      Thread.ofVirtual().start { () =>
        barrier.await(30, TimeUnit.SECONDS): Unit
        opened.put(
          PostgresCheckpointer
            .create(PgCheckpointerDb.pool(maxSize = 1), ownsDataSource = true, config)
            .left
            .map(_.message)
        )
      }
    }
    threads.foreach(_.join(60000))
    val all = opened.asScala.toVector
    all.collect { case Left(problem) => problem } shouldBe empty
    PgCheckpointerDb.scalar(s"""SELECT COUNT(*), MAX(version) FROM "${config.schema}".checkpoint_schema""")(rows =>
      (rows.getInt(1), rows.getInt(2))
    ) shouldBe (1 -> PostgresSchema.Current)
    all.collect { case Right(store) => store }.foreach(_.close())
  }

  it should "be refused, with nothing changed, when a newer build has migrated it" in {
    val config = fresh()
    PgCheckpointerDb.store(config).close()
    PgCheckpointerDb.execute(s"""UPDATE "${config.schema}".checkpoint_schema SET version = 99""")
    val pool = PgCheckpointerDb.pool()
    PostgresCheckpointer.create(pool, ownsDataSource = true, config).left.value shouldBe
      GraphError.UnsupportedStoreSchema(s"""PostgreSQL schema "${config.schema}"""", 99, PostgresSchema.Current)
    PgCheckpointerDb.scalar(s"""SELECT version FROM "${config.schema}".checkpoint_schema""")(_.getInt(1)) shouldBe 99
    // an owned pool is closed with a refused store
    pool.isClosed shouldBe true
  }

  it should "open at the current version for a role that may only read and write its tables" in {
    val config = fresh()
    PgCheckpointerDb.store(config).close()
    val role = s"llm4s_it_role_${java.util.UUID.randomUUID().toString.replace("-", "").take(12)}"
    val s    = s""""${config.schema}""""
    PgCheckpointerDb.execute(s"CREATE ROLE $role LOGIN PASSWORD 'dml-only'")
    Using.resource(PgCheckpointerDb.connect()) { c =>
      Using.resource(c.createStatement()) { st =>
        st.execute(s"GRANT USAGE ON SCHEMA $s TO $role")
        st.execute(s"GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA $s TO $role")
        st.execute(s"GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA $s TO $role")
      }
    }
    val store = PostgresCheckpointer.open(PgCheckpointerDb.jdbcUrl, role, "dml-only", config.withMaxPoolSize(2)).value
    val t     = thread()
    val token = store.claim(t, ClaimRequest(RunId("run"), None, 30.seconds)).value.token
    store.commit(t, Commit(token, Some(checkpoint("c1", None, t)))).value
    store.latest(t).value.value.checkpoint.id shouldBe "c1"
    store.close()
    // the same role cannot create a schema of its own: the open fails, as a ProcessingError
    PostgresCheckpointer
      .open(PgCheckpointerDb.jdbcUrl, role, "dml-only", fresh().withMaxPoolSize(1))
      .left
      .value shouldBe a[ProcessingError]
    Using.resource(PgCheckpointerDb.connect()) { c =>
      Using.resource(c.createStatement()) { st =>
        st.execute(s"REVOKE ALL ON ALL TABLES IN SCHEMA $s FROM $role")
        st.execute(s"REVOKE ALL ON ALL SEQUENCES IN SCHEMA $s FROM $role")
        st.execute(s"REVOKE ALL ON SCHEMA $s FROM $role")
        st.execute(s"DROP ROLE $role")
      }
    }
  }

  "Two schemas in one database" should "keep two stores apart" in {
    val a     = PgCheckpointerDb.store(fresh())
    val b     = PgCheckpointerDb.store(fresh())
    val t     = thread()
    val token = a.claim(t, ClaimRequest(RunId("a"), None, 30.seconds)).value.token
    a.commit(t, Commit(token, Some(checkpoint("c1", None, t)))).value
    b.latest(t).value shouldBe None
    b.claim(t, ClaimRequest(RunId("b"), None, 30.seconds)).value.holder shouldBe RunId("b")
    a.close()
    b.close()
  }

  // ---- the pool ----

  "A store with a pool of its own" should "work, close its pool with it, and report an unreachable database" in {
    val config = fresh().withMaxPoolSize(2)
    val store = PostgresCheckpointer
      .open(PgCheckpointerDb.jdbcUrl, PgCheckpointerDb.user, PgCheckpointerDb.password, config)
      .value
    val t = thread()
    store.claim(t, ClaimRequest(RunId("run"), None, 30.seconds)).value
    store.close()
    store.isClosed shouldBe true
    store.latest(t).left.value shouldBe a[ProcessingError]
    val unreachable = PostgresCheckpointer
      .open("jdbc:postgresql://127.0.0.1:1/none", "nobody", "nothing", config.withConnectionTimeout(500.millis))
      .left
      .value
    unreachable shouldBe a[ProcessingError]
  }

  // ---- interrupts ----

  "A store whose calls are interrupted mid-flight" should "stay usable by every other call" in {
    val store = PgCheckpointerDb.store(fresh())
    val t     = thread()
    val token = store.claim(t, ClaimRequest(RunId("run"), None, 30.seconds)).value.token
    store.commit(t, Commit(token, Some(checkpoint("c1", None, t)), events = Vector(event("e", Some("c1"))))).value
    (1 to 50).foreach { i =>
      val reader = Thread.ofVirtual().start { () =>
        while (!Thread.currentThread().isInterrupted) store.eventsAfter(t, 0L, 10): Unit
      }
      Thread.sleep(i % 5L)
      reader.interrupt()
      reader.join(10000)
      reader.isAlive shouldBe false
    }
    // a flag already set when a call begins decides nothing, and stays set
    val flagged = new LinkedBlockingQueue[(Boolean, Boolean)]()
    Thread
      .ofVirtual()
      .start { () =>
        Thread.currentThread().interrupt()
        val read = store.latest(t).isRight
        // `offer`, not `put`, which an interrupted thread could not complete
        flagged.offer(read -> Thread.currentThread().isInterrupted): Unit
      }
      .join(10000)
    flagged.poll() shouldBe (true -> true)
    store.commit(t, Commit(token, events = Vector(event("after", None)))).value.map(_.seq) shouldBe Vector(2L)
    store.close()
  }

  /** The schema as a version-4 build left it: no stop requests on queue entries (design 4.57). */
  private def asVersionFour(config: PostgresCheckpointerConfig): Unit = {
    val q = s""""${config.schema}""""
    PgCheckpointerDb.execute(
      s"""ALTER TABLE $q.checkpoint_queue_entries DROP COLUMN stop_json;
         |UPDATE $q.checkpoint_schema SET version = 4""".stripMargin
    )
  }

  "A version-4 schema" should "open at the current version, its open entries with no stop request" in {
    val config = fresh()
    val store  = PgCheckpointerDb.store(config)
    val t      = ThreadId("v4-thread")
    val agent  = org.llm4s.agent.AgentId.unsafe("upgraded")
    val queued = store.runQueue
      .submit(org.llm4s.agent.service.Submission(t, agent, org.llm4s.agent.service.QueuedInput.query("q")))
      .value
    store.close()
    asVersionFour(config)
    val q        = s""""${config.schema}""""
    val reopened = PgCheckpointerDb.store(config)
    PgCheckpointerDb.scalar(s"SELECT version FROM $q.checkpoint_schema")(_.getInt(1)) shouldBe PostgresSchema.Current
    reopened.runQueue.entry(None, queued.entryId).value.stopRequest shouldBe None
    val leased =
      reopened.runQueue.take(org.llm4s.agent.service.TakeRequest("w", Set(agent), 30.seconds)).value.value
    reopened.runQueue.recordAttempt(leased.lease, RunId.random()).value
    val reason = org.llm4s.agent.service.CancelReason.Requested(Some("upgraded"))
    reopened.runQueue
      .cancel(None, org.llm4s.agent.service.CancelTarget.Entry(queued.entryId), reason)
      .value
      .entries
      .map(_.stopRequest) shouldBe Vector(Some(org.llm4s.agent.service.StopRequest.Cancel(reason)))
    reopened.close()
  }

  /** The schema as a version-3 build left it: no run queue and no run ids on history rows (design 4.55). */
  private def asVersionThree(config: PostgresCheckpointerConfig): Unit = {
    val q = s""""${config.schema}""""
    PgCheckpointerDb.execute(
      s"""DROP TABLE $q.checkpoint_queue_attempts, $q.checkpoint_queue_entries;
         |DROP SEQUENCE $q.checkpoint_queue_entry_ids, $q.checkpoint_queue_lease_tokens;
         |DROP INDEX $q.checkpoint_history_run;
         |ALTER TABLE $q.checkpoint_history DROP COLUMN run_id;
         |UPDATE $q.checkpoint_schema SET version = 3""".stripMargin
    )
  }

  "A version-3 schema" should "open at the current version, its history rows' run ids backfilled for the run-id lookup" in {
    val config = fresh()
    val store  = PgCheckpointerDb.store(config)
    val t      = ThreadId("v3-thread")
    val token  = store.claim(t, ClaimRequest(RunId("run"), None, 30.seconds)).value.token
    store.commit(t, Commit(token, Some(checkpoint("c1", None, t).withRunId("run-a")))).value
    store.commit(t, Commit(token, Some(checkpoint("c2", Some("c1"), t).withRunId("run-b")))).value
    store.commit(t, Commit(token, Some(checkpoint("c3", Some("c2"), t).withRunId("run-c")))).value
    // released: a live claim would refuse the Reject submission below (W3)
    store.release(t, token).value
    store.close()
    asVersionThree(config)
    val q = s""""${config.schema}""""
    // one damaged row must not stop the schema opening: it keeps no run id
    PgCheckpointerDb.execute(
      s"UPDATE $q.checkpoint_history SET checkpoint_json = 'not json' WHERE checkpoint_id = 'c3'"
    )

    val reopened = PgCheckpointerDb.store(config)
    PgCheckpointerDb.scalar(s"SELECT COUNT(*) FROM $q.checkpoint_history WHERE run_id IS NULL")(_.getInt(1)) shouldBe 1
    PgCheckpointerDb.scalar(s"SELECT version FROM $q.checkpoint_schema")(_.getInt(1)) shouldBe PostgresSchema.Current
    reopened.latestOfRuns(t, Set(RunId("run-a"))).value.map(_.id) shouldBe Some("c1")
    reopened.latestOfRuns(t, Set(RunId("run-a"), RunId("run-b"))).value.map(_.id) shouldBe Some("c2")
    reopened.runQueue.openEntries(None, t).value shouldBe empty
    // the queue's tables are there, with every column W1 reads
    val queued = reopened.runQueue
      .submit(
        org.llm4s.agent.service.Submission(
          t,
          org.llm4s.agent.AgentId.unsafe("upgraded"),
          org.llm4s.agent.service.QueuedInput.query("q")
        )
      )
      .value
    reopened.runQueue.entry(None, queued.entryId).value.admitted shouldBe false
    reopened.close()
  }

  // ---- the thread registry (design 4.52) ----

  /** The schema `q` (quoted) as a version-2 build left it, once [[asVersionThree]] has: no tombstone reclamation (design 4.53). */
  private def asVersionTwo(q: String): Unit = {
    PgCheckpointerDb.execute(s"ALTER TABLE $q.checkpoint_threads DROP COLUMN deleted_at")
    PgCheckpointerDb.execute(s"DROP TABLE $q.checkpoint_reclaimed")
    PgCheckpointerDb.execute(s"UPDATE $q.checkpoint_schema SET version = 2")
  }

  "A version-2 schema" should "date its tombstones from the upgrade, and number new threads from 1 until one is reclaimed" in {
    val config = fresh()
    val store  = PgCheckpointerDb.store(config)
    val gone   = ThreadId("gone")
    val token  = store.claim(gone, ClaimRequest(RunId("run"), None, 30.seconds)).value.token
    store.commit(gone, Commit(token, Some(checkpoint("c1", None, gone)), events = Vector(event("a", None)))).value
    store.deleteThread(gone, token, None).value
    store.close()
    val q = s""""${config.schema}""""
    asVersionThree(config)
    asVersionTwo(q)

    val reopened = PgCheckpointerDb.store(config)
    PgCheckpointerDb.scalar(s"SELECT version FROM $q.checkpoint_schema")(_.getInt(1)) shouldBe PostgresSchema.Current
    PgCheckpointerDb.scalar(s"SELECT first_seq FROM $q.checkpoint_reclaimed")(_.getLong(1)) shouldBe 1L
    PgCheckpointerDb.scalar(
      s"SELECT deleted_at > clock_timestamp() - INTERVAL '1 minute' FROM $q.checkpoint_threads WHERE thread_id = 'gone'"
    )(_.getBoolean(1)) shouldBe true
    // a new id numbers from 1, as before the upgrade
    val fresh1 = ThreadId("fresh")
    val held   = reopened.claim(fresh1, ClaimRequest(RunId("run"), None, 30.seconds)).value.token
    reopened.commit(fresh1, Commit(held, events = Vector(event("f", None)))).value.map(_.seq) shouldBe Vector(1L)
    // the tombstone's age counts from the upgrade
    val policy = SweepPolicy(tombstoneAge = Some(1.hour))
    reopened.sweep(policy, None, SweepGuard.none).value.reclaimed shouldBe 0
    PgCheckpointerDb.execute(s"UPDATE $q.checkpoint_threads SET deleted_at = deleted_at - INTERVAL '2 hours'")
    reopened.sweep(policy, None, SweepGuard.none).value.reclaimed shouldBe 1
    reopened.eventsAfter(gone, 1L, 10).left.value shouldBe GraphError.ThreadDeleted("gone", 1L, 3L)
    reopened.close()
  }

  "A version-1 schema" should "fill the thread registry from each thread's latest checkpoint and its commit time" in {
    val config = fresh()
    val store  = PgCheckpointerDb.store(config)
    val tenant = Some(TenantId("acme"))
    def committed(t: ThreadId, status: CheckpointStatus, deleting: Boolean = false): Unit = {
      val token = store.claim(t, ClaimRequest(RunId("run"), tenant, 30.seconds)).value.token
      val written =
        checkpoint("c1", None, t).withStatus(status).withTenantId(tenant.map(_.value)).withDeleting(deleting)
      store.commit(t, Commit(token, Some(written))).value
      store.release(t, token).value
    }
    val (done, stopped, deleting) = (ThreadId("done"), ThreadId("stopped"), ThreadId("deleting"))
    committed(done, CheckpointStatus.Completed)
    committed(stopped, CheckpointStatus.Running)
    committed(deleting, CheckpointStatus.Running, deleting = true)
    val before = store.threads(ThreadQuery(tenant)).value
    store.close()
    asVersionThree(config)
    val q = s""""${config.schema}""""
    asVersionTwo(q)
    Vector("", "_status", "_agent", "_claimed", "_metadata").foreach { suffix =>
      PgCheckpointerDb.execute(s"DROP INDEX $q.checkpoint_threads_registry$suffix")
    }
    PgCheckpointerDb.execute(
      s"""ALTER TABLE $q.checkpoint_threads DROP COLUMN tenant_key, DROP COLUMN tenant_id, DROP COLUMN graph_id,
         |  DROP COLUMN thread_status,
         |  DROP COLUMN updated_at, DROP COLUMN metadata""".stripMargin
    )
    PgCheckpointerDb.execute(s"UPDATE $q.checkpoint_schema SET version = 1")

    val reopened = PgCheckpointerDb.store(config)
    PgCheckpointerDb.scalar(s"SELECT version FROM $q.checkpoint_schema")(_.getInt(1)) shouldBe PostgresSchema.Current
    val after = reopened.threads(ThreadQuery(tenant)).value
    after shouldBe before
    after.threads.map(t => t.threadId -> t.status).toMap shouldBe Map(
      done     -> ThreadStatus.Completed,
      stopped  -> ThreadStatus.Idle,
      deleting -> ThreadStatus.Deleted
    )
    reopened.incompleteThreads(tenant, None, 10).value.threads.map(_.threadId) shouldBe Vector(stopped)
    reopened.close()
  }

  "The thread registry" should "answer every query through a registry index, in its order, never scanning the threads" in {
    val config = fresh()
    PgCheckpointerDb.store(config).close()
    val t   = PostgresSchema.Tables(config.schema)
    val now = Instant.parse("2026-10-21T12:00:00Z")
    // many threads, so the planner's choice is the one a large store gets: most of them one tenant's, a few claimed,
    // a few of no tenant
    PgCheckpointerDb.execute(
      s"""INSERT INTO ${t.threads} (thread_id, next_seq, earliest_seq, first_seq, checkpoint_id, tenant_id, graph_id,
         |  thread_status, updated_at, metadata, claim_holder, claim_token, claim_expires_at)
         |SELECT 'thread-' || g, 1, 1, 1, 'c', CASE WHEN g % 100 = 0 THEN NULL ELSE 'acme' END, 'graph-' || (g % 7),
         |  (ARRAY['Running', 'Completed', 'Suspended', 'Failed', 'Deleted'])[1 + g % 5],
         |  TIMESTAMPTZ '2026-10-21 12:00:00Z' - g * INTERVAL '1 second', jsonb_build_object('team', 'team-' || (g % 13)),
         |  CASE WHEN g % 500 = 0 THEN 'run' END, CASE WHEN g % 500 = 0 THEN g END,
         |  CASE WHEN g % 500 = 0 THEN TIMESTAMPTZ '2026-10-21 12:00:30Z' END
         |FROM generate_series(1, 20000) g""".stripMargin
    )
    PgCheckpointerDb.execute(s"ANALYZE ${t.threads}")
    val tenant = Some(TenantId("acme"))
    val cursor = Some(ThreadCursor(now, ThreadId("t")))
    def plan(statement: (String, Vector[Any])): Vector[String] =
      PgCheckpointerDb.withConnection { c =>
        Using.resource(c.createStatement())(_.execute("SET enable_seqscan = off"): Unit)
        Using.resource(c.prepareStatement(s"EXPLAIN ${statement._1}")) { query =>
          statement._2.zipWithIndex.foreach {
            case (at: Instant, i) => query.setObject(i + 1, OffsetDateTime.ofInstant(at, java.time.ZoneOffset.UTC))
            case (value, i)       => query.setObject(i + 1, value)
          }
          Using.resource(query.executeQuery())(rows =>
            Iterator.continually(rows).takeWhile(_.next()).map(_.getString(1)).toVector
          )
        }
      }
    val ordered = Vector(
      PostgresCheckpointer.threadsStatement(t, ThreadQuery(tenant), now),
      PostgresCheckpointer.threadsStatement(t, ThreadQuery(None).withAfter(cursor), now),
      PostgresCheckpointer.threadsStatement(t, ThreadQuery(tenant).withUpdatedSince(now), now),
      PostgresCheckpointer.threadsStatement(t, ThreadQuery(tenant).withAgent("assistant").withAfter(cursor), now)
    ) ++ ThreadStatus.values.toVector.map(status =>
      PostgresCheckpointer.threadsStatement(t, ThreadQuery(tenant, status = Some(status)).withAfter(cursor), now)
    ) :+ PostgresCheckpointer.incompleteStatement(t, tenant, cursor, 10, now)
    ordered.foreach { statement =>
      val steps = plan(statement)
      withClue(steps.mkString("\n")) {
        steps.exists(_.matches(".*Index (Only )?Scan.* using checkpoint_threads_registry.*")) shouldBe true
        steps.exists(_.contains("Sort")) shouldBe false
        steps.exists(_.contains("Seq Scan")) shouldBe false
      }
    }
    // metadata is found through its GIN index
    val byMetadata = plan(
      PostgresCheckpointer.threadsStatement(t, ThreadQuery(tenant).withMetadata(ThreadMetadata("team" -> "red")), now)
    )
    withClue(byMetadata.mkString("\n"))(byMetadata.mkString should include("checkpoint_threads_registry"))
  }

  override def afterAll(): Unit = {
    if (PgCheckpointerDb.enabled) schemas.asScala.foreach(PgCheckpointerDb.dropSchema)
    super.afterAll()
  }
}
