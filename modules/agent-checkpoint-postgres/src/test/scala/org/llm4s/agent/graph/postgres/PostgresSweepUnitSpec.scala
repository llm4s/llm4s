package org.llm4s.agent.graph.postgres

import org.llm4s.agent.graph.{
  ClaimRequest,
  GraphError,
  RetentionPolicy,
  RunId,
  SweepGuard,
  SweepPage,
  SweepPolicy,
  ThreadId
}
import org.llm4s.error.ValidationError
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.PrintWriter
import java.lang.reflect.{ InvocationHandler, Method, Proxy }
import java.sql.{ Connection, PreparedStatement, ResultSet, SQLException }
import java.time.{ OffsetDateTime, ZoneOffset }
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Logger
import javax.sql.DataSource
import scala.collection.mutable
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * The retention sweep's statements and their order (design 4.53), against a scripted connection that answers each
 * query from the test's script and records every statement with its parameters: what the sweep locks before it
 * reclaims, what it reclaims and prunes, and how a store judges an id with no row against the reclamation mark. The
 * sweep against a real server is `modules/it`'s `@Docker` contract and concurrency suites.
 */
class PostgresSweepUnitSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private val t   = PostgresSchema.Tables("llm4s")
  private val now = OffsetDateTime.of(2026, 10, 22, 12, 0, 0, 0, ZoneOffset.UTC)

  /** A row of `Sql.row`'s columns: no checkpoint, no claim, no parent. */
  private def threadRow(nextSeq: Long, firstSeq: Long): Vector[Any] =
    Vector(null, nextSeq, firstSeq, firstSeq, null, null, null, null, null)

  // a: a tombstone that numbered to 6; b: a live thread with events 1 to 9
  private def rowOf(id: Any): Vector[Any] = if id == "a" then threadRow(7L, 7L) else threadRow(10L, 1L)

  "A sweep" should "reclaim the page's old tombstones under the reclamation lock, raise the mark, and prune the rest" in {
    val script = Script {
      case (sql, _) if sql.startsWith(s"SELECT thread_id FROM ${t.threads} ORDER BY") =>
        Vector(Vector("a"), Vector("b"), Vector("c"))
      case (sql, Vector(id)) if sql == Sql.lockedRow(t) || sql == Sql.lockedRowIfFree(t) => Vector(rowOf(id))
      case (sql, Vector("a", _, _)) if sql == Sql.reclaim(t)                             => Vector(Vector(7L))
      case (sql, _) if sql == Sql.reclaim(t)                                             => Vector.empty
      case (sql, _) if sql == Sql.historyAges(t) =>
        Vector(Vector(1L, "b1", now, "r"), Vector(2L, "b2", now, "s"))
    }
    val store  = script.store()
    val asked  = new CopyOnWriteArrayList[Vector[ThreadId]]()
    val policy = SweepPolicy(RetentionPolicy(maxEvents = Some(1)), tombstoneAge = Some(1.day), batchSize = 2)
    val guard: SweepGuard = threads =>
      asked.add(threads)
      Right(Map(ThreadId("b") -> Set(RunId("r"))))

    store.sweep(policy, None, guard).value shouldBe SweepPage(2, 1, Some(ThreadId("b")))
    // the guard is asked about the page alone, never the row read past it to tell whether more follow
    asked.asScala.toVector shouldBe Vector(Vector(ThreadId("a"), ThreadId("b")))

    val made            = script.made
    def at(sql: String) = made.indexWhere(_._1 == sql)
    // the protected thread is never a candidate for reclamation, and the reclamation lock comes first
    made.filter(_._1 == Sql.reclaim(t)).map(_._2.head) shouldBe Vector("a")
    at(Sql.lockReclamation) should be < at(Sql.reclaim(t))
    made.find(_._1 == Sql.lockReclamation).value._2 shouldBe Vector("llm4s.agent.checkpoint:llm4s:reclamation")
    made.filter(_._1 == Sql.raiseReclaimed(t)).map(_._2) shouldBe Vector(Vector(7L))
    // holding the reclamation lock, it never waits on a row another call holds: such a row is in use, no tombstone
    made.filter(_._1 == Sql.lockedRowIfFree(t)).map(_._2) shouldBe Vector(Vector("a"))
    at(Sql.lockReclamation) should be < at(Sql.lockedRowIfFree(t))
    Sql.lockedRowIfFree(t) should endWith("FOR UPDATE SKIP LOCKED")
    // the reclaimed thread is not pruned; the protected one is, reading each checkpoint's run
    made.filter(_._1 == Sql.setFloor(t)).map(_._2.last) shouldBe Vector("b")
    // maxEvents = 1 of events 1 to 9: the floor is 9
    made.filter(_._1 == Sql.setFloor(t)).map(_._2.head) shouldBe Vector(9L)
    made.map(_._1) should not contain Sql.deleteHistoryAt(t)
  }

  it should "skip, not wait for, a tombstone whose row another call holds" in {
    val script = Script {
      case (sql, _) if sql.startsWith(s"SELECT thread_id FROM ${t.threads} ORDER BY") => Vector(Vector("a"))
      // SKIP LOCKED reads no row: a claim of the id holds it
      case (sql, _) if sql == Sql.lockedRowIfFree(t) => Vector.empty
    }
    script.store().sweep(SweepPolicy(tombstoneAge = Some(1.day)), None, SweepGuard.none).value shouldBe
      SweepPage(1, 0, None)
    script.made.map(_._1) should not contain Sql.reclaim(t)
    script.made.map(_._1) should not contain Sql.raiseReclaimed(t)
  }

  it should "page after a cursor, and change nothing when the policy keeps everything" in {
    val script = Script {
      case (sql, _) if sql.startsWith(s"SELECT thread_id FROM ${t.threads} WHERE thread_id > ?") => Vector(Vector("z"))
    }
    val store = script.store()
    store.sweep(SweepPolicy.keepAll, Some(ThreadId("y")), SweepGuard.none).value shouldBe SweepPage(1, 0, None)
    script.made.find(_._1 == Sql.sweepPageAfter(t)).value._2 shouldBe Vector("y", SweepPolicy.DefaultBatchSize + 1)
    script.made.map(_._1).filter(_.startsWith("DELETE")) shouldBe empty
    script.made.map(_._1) should not contain Sql.lockReclamation
  }

  it should "change nothing when its guard fails" in {
    val script = Script {
      case (sql, _) if sql.startsWith(s"SELECT thread_id FROM ${t.threads} ORDER BY") => Vector(Vector("a"))
    }
    val store       = script.store()
    val unreachable = ValidationError("guard", "the queue is unreachable")
    store.sweep(SweepPolicy(tombstoneAge = Some(1.day)), None, _ => Left(unreachable)).left.value shouldBe unreachable
    script.made.map(_._1) should not contain Sql.lockReclamation
    script.made.map(_._1).filter(_.startsWith("DELETE")) shouldBe empty
  }

  "A reader of an id with no row" should "be judged against the reclamation mark" in {
    val script = Script {
      case (sql, _) if sql == Sql.reclaimedFirst(t) => Vector(Vector(5L))
    }
    script.store().eventsAfter(ThreadId("gone"), 3L, 10).left.value shouldBe
      GraphError.ThreadDeleted("gone", 3L, 5L)
  }

  "A claim of an id with no row" should "share the reclamation lock before it creates one from the mark" in {
    // the row never appears, as if a sweep kept reclaiming it: the claim gives up rather than spin
    val script = Script(PartialFunction.empty)
    val refused =
      script.store().claim(ThreadId("new"), ClaimRequest(RunId("run"), None, 30.seconds)).left.value
    refused.message should include("kept vanishing")
    val made = script.made.map(_._1)
    made.count(_ == Sql.shareReclamation) shouldBe 1
    made.indexOf(Sql.shareReclamation) should be < made.indexOf(Sql.ensureRow(t))
    made.count(_ == Sql.ensureRow(t)) shouldBe 8
    Sql.ensureRow(t) should include(s"FROM ${t.reclaimed}")
  }

  "The sweep's statements" should "read each checkpoint's run from its indexed column, and migrate schema 2 to 3" in {
    // the run is the history row's own `run_id` (schema 4), never decoded from the checkpoint's JSON
    Sql.historyAges(t) should include("run_id")
    (Sql.historyAges(t) should not).include("runId")
    val step = PostgresSchema.steps(2)(t)
    step.head should include("ADD COLUMN deleted_at timestamptz")
    step.mkString should include(s"CREATE TABLE ${t.reclaimed}")
    step.last shouldBe s"INSERT INTO ${t.reclaimed} (first_seq) VALUES (1)"
  }

  // ---- the scripted connection ----

  /**
   * Connections whose queries `answer` answers by SQL and bound parameters, with the schema reading as current and
   * the clock as `now`; every other query reads no row. `made` is every statement prepared, with its parameters.
   */
  final private case class Script(answer: PartialFunction[(String, Vector[Any]), Vector[Vector[Any]]]) {
    private val log = new CopyOnWriteArrayList[(String, Vector[Any])]()

    def made: Vector[(String, Vector[Any])] = log.asScala.toVector

    /** A store over the script's connections; what opening it ran is not recorded. */
    def store(): PostgresCheckpointer =
      val opened = PostgresCheckpointer.create(Pool(() => connection()), ownsDataSource = false).value
      log.clear()
      opened

    private def rowsFor(sql: String, params: Vector[Any]): Vector[Vector[Any]] =
      answer.applyOrElse(
        (sql, params),
        {
          case (s, _) if s.contains("to_regclass")                          => Vector(Vector(true))
          case (s, _) if s.contains("MAX(version)")                         => Vector(Vector(PostgresSchema.Current))
          case (s, _) if s.contains("pg_advisory_xact")                     => Vector(Vector(null))
          case (s, _) if s == s"SELECT ${PostgresCheckpointer.ServerClock}" => Vector(Vector(now))
          case _                                                            => Vector.empty
        }
      )

    private def connection(): Connection = {
      val handler: InvocationHandler = (_: Any, method: Method, args: Array[AnyRef]) =>
        method.getName match {
          case "prepareStatement"                                          => statement(args(0).toString)
          case "createStatement"                                           => statement("")
          case "setAutoCommit" | "rollback" | "commit" | "abort" | "close" => null
          case "isClosed"                                                  => java.lang.Boolean.FALSE
          case other => throw new UnsupportedOperationException(other)
        }
      Proxy.newProxyInstance(getClass.getClassLoader, Array(classOf[Connection]), handler).asInstanceOf[Connection]
    }

    private def statement(prepared: String): PreparedStatement = {
      val params = mutable.TreeMap.empty[Int, Any]
      def bound  = params.values.toVector
      val handler: InvocationHandler = (_: Any, method: Method, args: Array[AnyRef]) =>
        method.getName match {
          case "setString" | "setLong" | "setInt" | "setObject" =>
            params(args(0).asInstanceOf[Int]) = args(1)
            null
          case "execute" => java.lang.Boolean.FALSE
          case "executeUpdate" =>
            log.add(prepared -> bound)
            Integer.valueOf(1)
          case "executeQuery" =>
            val sql = Option(args).map(_.head.toString).getOrElse(prepared)
            log.add(sql -> bound)
            results(rowsFor(sql, bound))
          case "close" => null
          case other   => throw new SQLException(s"$other is not scripted")
        }
      Proxy
        .newProxyInstance(getClass.getClassLoader, Array(classOf[PreparedStatement]), handler)
        .asInstanceOf[PreparedStatement]
    }

    private def results(rows: Vector[Vector[Any]]): ResultSet = {
      var index    = -1
      var lastNull = false
      def value(column: AnyRef): Any =
        val v = rows(index)(column.asInstanceOf[Int] - 1)
        lastNull = v == null
        v
      val handler: InvocationHandler = (_: Any, method: Method, args: Array[AnyRef]) =>
        method.getName match {
          case "next" =>
            index += 1
            java.lang.Boolean.valueOf(index < rows.size)
          case "getString" => value(args(0)).asInstanceOf[String]
          case "getLong" =>
            value(args(0)) match {
              case null      => java.lang.Long.valueOf(0L)
              case n: Number => java.lang.Long.valueOf(n.longValue)
              case other     => throw new SQLException(s"not a number: $other")
            }
          case "getInt"     => Integer.valueOf(value(args(0)).asInstanceOf[Number].intValue)
          case "getBoolean" => java.lang.Boolean.valueOf(value(args(0)) == true)
          case "getObject"  => value(args(0)).asInstanceOf[AnyRef]
          case "wasNull"    => java.lang.Boolean.valueOf(lastNull)
          case "close"      => null
          case other        => throw new SQLException(s"$other is not scripted")
        }
      Proxy
        .newProxyInstance(getClass.getClassLoader, Array(classOf[ResultSet]), handler)
        .asInstanceOf[ResultSet]
    }
  }

  /** A `DataSource` handing out `connect()`'s connections. */
  final private case class Pool(connect: () => Connection) extends DataSource {
    def getConnection: Connection                                 = connect()
    def getConnection(user: String, password: String): Connection = connect()
    def getLogWriter: PrintWriter                                 = null
    def setLogWriter(out: PrintWriter): Unit                      = ()
    def setLoginTimeout(seconds: Int): Unit                       = ()
    def getLoginTimeout: Int                                      = 0
    def getParentLogger: Logger                                   = Logger.getGlobal
    def unwrap[T](iface: Class[T]): T                             = throw new SQLException("not a wrapper")
    def isWrapperFor(iface: Class[?]): Boolean                    = false
  }
}
