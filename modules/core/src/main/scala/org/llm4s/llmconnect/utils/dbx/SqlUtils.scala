package org.llm4s.llmconnect.utils.dbx

import org.llm4s.core.safety.UsingOps._
import java.sql.{ Connection, DriverManager, ResultSet }

object SqlUtils {
  def connect(host: String, port: Int, db: String, user: String, pass: String, sslmode: String): Connection = {
    val url = s"jdbc:postgresql://$host:$port/$db?sslmode=$sslmode"
    DriverManager.getConnection(url, user, pass)
  }

  def querySingleOpt[A](conn: Connection, sql: String)(read: ResultSet => A): Option[A] =
    using(conn.prepareStatement(sql))(ps => using(ps.executeQuery())(rs => if (rs.next()) Some(read(rs)) else None))

  def exec(conn: Connection, sql: String): Unit =
    using(conn.prepareStatement(sql)) { ps =>
      ps.execute()
      ()
    }
}
