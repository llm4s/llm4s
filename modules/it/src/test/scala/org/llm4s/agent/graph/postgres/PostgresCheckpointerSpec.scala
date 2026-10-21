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

  override def afterAll(): Unit = {
    if (PgCheckpointerDb.enabled) schemas.asScala.foreach(PgCheckpointerDb.dropSchema)
    super.afterAll()
  }
}
