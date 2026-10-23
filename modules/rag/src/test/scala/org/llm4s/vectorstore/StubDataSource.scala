package org.llm4s.vectorstore

import java.io.PrintWriter
import java.lang.reflect.{ InvocationHandler, Method, Proxy }
import java.sql.{ Connection, ResultSet, SQLException, SQLFeatureNotSupportedException, Statement }
import java.util.logging.Logger
import javax.sql.DataSource
import scala.collection.mutable

/**
 * A `javax.sql.DataSource` that is not HikariCP's, as a caller's own pool would be: its connections accept the DDL the
 * Postgres stores run on creation and record it, and it counts the connections it hands out. `failing` makes every
 * `getConnection` throw, as an unreachable database does.
 */
class StubDataSource(failing: Boolean = false) extends DataSource {
  val statements: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
  @volatile var connectionsOpened: Int        = 0

  private def proxy[A](cls: Class[A])(f: (String, Array[AnyRef]) => AnyRef): A =
    Proxy
      .newProxyInstance(
        getClass.getClassLoader,
        Array(cls),
        new InvocationHandler {
          def invoke(p: Any, m: Method, args: Array[AnyRef]): AnyRef = f(m.getName, args)
        }
      )
      .asInstanceOf[A]

  private def emptyResultSet: ResultSet = proxy(classOf[ResultSet]) {
    case ("next", _) => java.lang.Boolean.FALSE
    case _           => null
  }

  private def statement: Statement = proxy(classOf[Statement]) {
    case ("execute", a)       => statements += a(0).toString; java.lang.Boolean.FALSE
    case ("executeUpdate", a) => statements += a(0).toString; Integer.valueOf(0)
    case ("executeQuery", a)  => statements += a(0).toString; emptyResultSet
    case _                    => null
  }

  private def connection: Connection = proxy(classOf[Connection]) {
    case ("createStatement", _) => statement
    case ("isClosed", _)        => java.lang.Boolean.FALSE
    case ("getAutoCommit", _)   => java.lang.Boolean.TRUE
    case _                      => null
  }

  override def getConnection(): Connection =
    if (failing) throw new SQLException("connection refused")
    else {
      connectionsOpened += 1
      connection
    }
  override def getConnection(username: String, password: String): Connection = getConnection()
  override def getLogWriter: PrintWriter                                     = null
  override def setLogWriter(out: PrintWriter): Unit                          = ()
  override def setLoginTimeout(seconds: Int): Unit                           = ()
  override def getLoginTimeout: Int                                          = 0
  override def getParentLogger: Logger                = throw new SQLFeatureNotSupportedException()
  override def unwrap[T](iface: Class[T]): T          = throw new SQLException("not a wrapper")
  override def isWrapperFor(iface: Class[?]): Boolean = false
}

/** A [[StubDataSource]] that is `AutoCloseable`, as a pool is, and counts how often it was closed. */
final class ClosableStubDataSource(failing: Boolean = false) extends StubDataSource(failing) with AutoCloseable {
  @volatile var closeCount: Int = 0
  override def close(): Unit    = closeCount += 1
}
