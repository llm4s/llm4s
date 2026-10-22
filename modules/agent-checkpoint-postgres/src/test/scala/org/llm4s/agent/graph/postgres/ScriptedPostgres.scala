package org.llm4s.agent.graph.postgres

import org.scalatest.EitherValues.*

import java.io.PrintWriter
import java.lang.reflect.{ InvocationHandler, Method, Proxy }
import java.sql.{ Connection, PreparedStatement, ResultSet, SQLException }
import java.time.OffsetDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Logger
import javax.sql.DataSource
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/**
 * A PostgreSQL store over scripted connections, for unit specs that need no server: each query is answered by
 * `answer` from its SQL and bound parameters, the schema reads as current and the clock as `now`, every other query
 * reads no row, and every statement is recorded with its parameters (`made`). A bound `text[]` is recorded as a
 * `Vector` of its elements. The store against a real server is `modules/it`'s `@Docker` suites.
 */
final private[postgres] case class ScriptedPostgres(
  now: OffsetDateTime,
  answer: PartialFunction[(String, Vector[Any]), Vector[Vector[Any]]]
) {
  private val log = new CopyOnWriteArrayList[(String, Vector[Any])]()

  /** Every statement made since the store opened, with its bound parameters. */
  def made: Vector[(String, Vector[Any])] = log.asScala.toVector

  /** The parameters of every statement made whose SQL is `sql`, in order. */
  def madeWith(sql: String): Vector[Vector[Any]] = made.collect { case (`sql`, params) => params }

  /** A store over the script's connections; what opening it ran is not recorded. */
  def store(config: PostgresCheckpointerConfig = PostgresCheckpointerConfig.default): PostgresCheckpointer =
    val opened = PostgresCheckpointer.create(Pool(() => connection()), ownsDataSource = false, config).value
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
    lazy val self: Connection = Proxy
      .newProxyInstance(getClass.getClassLoader, Array(classOf[Connection]), handler)
      .asInstanceOf[Connection]
    lazy val handler: InvocationHandler = (_: Any, method: Method, args: Array[AnyRef]) =>
      method.getName match {
        case "prepareStatement" => statement(args(0).toString, self)
        case "createStatement"  => statement("", self)
        case "createArrayOf"    => textArray(args(1).asInstanceOf[Array[AnyRef]].toVector)
        case "setAutoCommit" | "rollback" | "commit" | "abort" | "close" => null
        case "isClosed"                                                  => java.lang.Boolean.FALSE
        case other => throw new UnsupportedOperationException(other)
      }
    self
  }

  /** A `java.sql.Array` holding `elements`, recorded as them. */
  private def textArray(elements: Vector[AnyRef]): java.sql.Array =
    Proxy
      .newProxyInstance(
        getClass.getClassLoader,
        Array(classOf[java.sql.Array]),
        (_: Any, method: Method, _: Array[AnyRef]) =>
          method.getName match {
            case "getArray" => elements.toArray
            case "toString" => elements.mkString("{", ",", "}")
            case other      => throw new SQLException(s"$other is not scripted")
          }
      )
      .asInstanceOf[java.sql.Array]

  private def statement(prepared: String, connection: Connection): PreparedStatement = {
    val params = mutable.TreeMap.empty[Int, Any]
    def bound  = params.values.toVector
    val handler: InvocationHandler = (_: Any, method: Method, args: Array[AnyRef]) =>
      method.getName match {
        case "setString" | "setLong" | "setInt" | "setObject" =>
          params(args(0).asInstanceOf[Int]) = args(1)
          null
        case "setArray" =>
          params(args(0).asInstanceOf[Int]) =
            args(1).asInstanceOf[java.sql.Array].getArray.asInstanceOf[Array[?]].toVector
          null
        case "getConnection" => connection
        case "execute"       => java.lang.Boolean.FALSE
        // each row of a batch is recorded as a statement of its own
        case "addBatch" =>
          log.add(prepared -> bound)
          null
        case "executeBatch" => Array.empty[Int]
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
        case "getInt" =>
          value(args(0)) match {
            case null      => Integer.valueOf(0)
            case n: Number => Integer.valueOf(n.intValue)
            case other     => throw new SQLException(s"not a number: $other")
          }
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
