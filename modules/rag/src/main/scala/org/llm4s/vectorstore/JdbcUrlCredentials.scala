package org.llm4s.vectorstore

import org.llm4s.security.Secret

/**
 * Registers a password a JDBC URL carries (`?password=`, `sslpassword=`, `user:password@host`), so a driver error that
 * echoes the URL is scrubbed. The stores take the password as a separate argument; this covers a URL that also holds one.
 */
private[llm4s] object JdbcUrlCredentials {

  private val QueryPassword = "(?i)[?&;](?:ssl)?password=([^&;]*)".r
  private val UserInfo      = "^jdbc:[a-z0-9]+://[^/?@:]*:([^/?@]+)@".r

  def register(jdbcUrl: String): Unit = {
    QueryPassword.findAllMatchIn(jdbcUrl).foreach(m => Secret(m.group(1)))
    UserInfo.findFirstMatchIn(jdbcUrl).foreach(m => Secret(m.group(1)))
  }
}
