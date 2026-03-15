package org.llm4s.vectorstore

/** Default HikariCP connection pool timeout settings shared across Postgres-backed stores. */
object HikariDefaults {
  val CONNECTION_TIMEOUT_MS: Long = 30000L   // 30 seconds
  val IDLE_TIMEOUT_MS: Long       = 600000L  // 10 minutes
  val MAX_LIFETIME_MS: Long       = 1800000L // 30 minutes
}
