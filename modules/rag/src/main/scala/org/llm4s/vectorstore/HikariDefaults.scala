package org.llm4s.vectorstore

import scala.concurrent.duration.*

/** Default HikariCP connection pool timeout settings shared across Postgres-backed stores. */
private[llm4s] object HikariDefaults {
  val ConnectionTimeout: FiniteDuration = 30.seconds
  val IdleTimeout: FiniteDuration       = 10.minutes
  val MaxLifetime: FiniteDuration       = 30.minutes
}
