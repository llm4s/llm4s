package org.llm4s.llmconnect.utils.dbx

import java.sql.{ Connection, DriverManager, ResultSet }

object SqlUtils {
  def connect(host: String, port: Int, db: String, user: String, pass: String, sslmode: String): Connection = {
    val url = s"jdbc:postgresql://$host:$port/$db?sslmode=$sslmode"
    DriverManager.getConnection(url, user, pass)
  }

  def querySingleOpt[A](conn: Connection, sql: String)(read: ResultSet => A): Option[A] = {
    val ps = conn.prepareStatement(sql)
    try {
      val rs = ps.executeQuery()
      try if (rs.next()) Some(read(rs)) else None
      finally rs.close()
    } finally ps.close()
  }

  def exec(conn: Connection, sql: String): Unit = {
    val ps = conn.prepareStatement(sql)
    try ps.execute()
    finally ps.close()
  }
}
