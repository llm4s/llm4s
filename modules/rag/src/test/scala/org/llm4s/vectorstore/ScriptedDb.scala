package org.llm4s.vectorstore

import java.lang.reflect.{ InvocationHandler, Method, Proxy }
import java.sql.{ Connection, PreparedStatement, ResultSet, Statement }
import javax.sql.DataSource
import scala.collection.mutable

/**
 * A connection whose queries return `rows(sql)` and which records the parameters of every statement it runs
 * (one entry per `executeUpdate`, `executeQuery`, `execute` or batch row). A statement whose SQL `fails` throws when run, after
 * it is recorded. Commits and rollbacks are counted.
 */
final class ScriptedDb(rows: String => Seq[Map[String, Any]], fails: String => Boolean = _ => false) {
  val writes: mutable.ListBuffer[(String, Map[Int, Any])] = mutable.ListBuffer.empty
  var commits: Int                                        = 0
  var rollbacks: Int                                      = 0

  /** The SQL of every statement run, in order. */
  def statements: Seq[String] = writes.toSeq.map(_._1)

  private def proxy[T](cls: Class[T])(handler: (String, Array[AnyRef]) => Any): T =
    Proxy
      .newProxyInstance(
        getClass.getClassLoader,
        Array[Class[?]](cls),
        new InvocationHandler {
          def invoke(p: Any, m: Method, args: Array[AnyRef]): AnyRef = {
            val result = handler(m.getName, Option(args).getOrElse(Array.empty))
            (result, m.getReturnType) match {
              case (null, t) if t == java.lang.Boolean.TYPE => java.lang.Boolean.FALSE
              case (null, t) if t == java.lang.Integer.TYPE => Int.box(0)
              case (null, t) if t == java.lang.Long.TYPE    => Long.box(0L)
              case (null, t) if t == java.lang.Double.TYPE  => Double.box(0.0)
              case (r, _)                                   => r.asInstanceOf[AnyRef]
            }
          }
        }
      )
      .asInstanceOf[T]

  private def resultSet(data: Seq[Map[String, Any]]): ResultSet = {
    var at = -1
    proxy(classOf[ResultSet]) {
      case ("next", _)                       => at += 1; at < data.size
      case (_, Array(column: String))        => data(at).getOrElse(column, null)
      case (_, Array(_: Integer)) if at >= 0 => data(at).values.headOption.orNull
      case _                                 => null
    }
  }

  private def statement(sql: String): PreparedStatement = {
    val params = mutable.Map.empty[Int, Any]
    def record(): Unit = {
      writes += ((sql, params.toMap)); params.clear()
      if (fails(sql)) throw new java.sql.SQLException(s"scripted failure: $sql")
    }
    proxy(classOf[PreparedStatement]) {
      case (name, Array(i: Integer, v)) if name.startsWith("set") => params(i.intValue) = v
      case ("addBatch", _)                                        => record()
      case ("executeBatch", _)                                    => Array(1)
      case ("executeUpdate", _)                                   => record(); 1
      case ("execute", _)                                         => record(); false
      case ("executeQuery", _)                                    => record(); resultSet(rows(sql))
      case _                                                      => null
    }
  }

  val dataSource: DataSource = {
    val connection = proxy(classOf[Connection]) {
      case ("getAutoCommit", _)                     => true
      case ("prepareStatement", Array(sql: String)) => statement(sql)
      case ("createStatement", _)                   => proxy(classOf[Statement])((_, _) => null)
      case ("commit", _)                            => commits += 1
      case ("rollback", _)                          => rollbacks += 1
      case _                                        => null
    }
    proxy(classOf[DataSource]) {
      case ("getConnection", _) => connection
      case _                    => null
    }
  }

  /** The metadata parameter each recorded statement wrote, as a map. */
  def writtenMetadata(index: Int): Seq[Map[String, String]] =
    writes.toSeq.collect { case (_, p) if p.contains(index) => PgMetadataJson.read(p(index).toString) }
}
