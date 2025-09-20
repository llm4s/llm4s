package org.llm4s.llmconnect.utils.dbx

import com.zaxxer.hikari.{ HikariConfig, HikariDataSource }
import org.llm4s.llmconnect.config.dbx.PgConfig
import java.sql.Connection

/**
 * Manages database connection pooling using HikariCP
 */
class ConnectionPool(pgConfig: PgConfig) extends AutoCloseable {

  private val dataSource: HikariDataSource = createDataSource()

  private def createDataSource(): HikariDataSource = {
    val config = new HikariConfig()

    // JDBC URL with SSL mode
    val jdbcUrl =
      s"jdbc:postgresql://${pgConfig.host}:${pgConfig.port}/${pgConfig.database}?sslmode=${pgConfig.sslmode}"
    config.setJdbcUrl(jdbcUrl)
    config.setUsername(pgConfig.user)
    config.setPassword(pgConfig.password)

    // Pool configuration
    config.setMaximumPoolSize(10) // Adjust based on expected load
    config.setMinimumIdle(2)
    config.setConnectionTimeout(30000) // 30 seconds
    config.setIdleTimeout(600000)      // 10 minutes
    config.setMaxLifetime(1800000)     // 30 minutes

    // Performance optimizations
    config.setAutoCommit(true)
    config.addDataSourceProperty("cachePrepStmts", "true")
    config.addDataSourceProperty("prepStmtCacheSize", "250")
    config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048")

    // Pool name for monitoring
    config.setPoolName("dbx-pool")

    // Connection test query
    config.setConnectionTestQuery("SELECT 1")

    new HikariDataSource(config)
  }

  /**
   * Gets a connection from the pool.
   * Caller is responsible for closing the connection to return it to the pool.
   */
  def getConnection(): Connection = dataSource.getConnection()

  /**
   * Executes a function with a connection from the pool, automatically returning it after use.
   */
  def withConnection[T](f: Connection => T): T = {
    val conn = getConnection()
    try
      f(conn)
    finally
      conn.close() // Returns connection to pool
  }

  /**
   * Executes a function within a transaction.
   */
  def withTransaction[E, T](f: Connection => Either[E, T]): Either[E, T] = {
    val conn = getConnection()
    conn.setAutoCommit(false)
    try
      f(conn) match {
        case Right(result) =>
          conn.commit()
          Right(result)
        case Left(error) =>
          conn.rollback()
          Left(error)
      }
    catch {
      case e: Exception =>
        conn.rollback()
        throw e
    } finally {
      conn.setAutoCommit(true)
      conn.close()
    }
  }

  /**
   * Closes the connection pool
   */
  override def close(): Unit =
    if (!dataSource.isClosed) {
      dataSource.close()
    }

  /**
   * Gets pool statistics
   */
  def getPoolStats(): PoolStats = PoolStats(
    activeConnections = dataSource.getHikariPoolMXBean.getActiveConnections,
    idleConnections = dataSource.getHikariPoolMXBean.getIdleConnections,
    totalConnections = dataSource.getHikariPoolMXBean.getTotalConnections,
    threadsAwaitingConnection = dataSource.getHikariPoolMXBean.getThreadsAwaitingConnection
  )
}

case class PoolStats(
  activeConnections: Int,
  idleConnections: Int,
  totalConnections: Int,
  threadsAwaitingConnection: Int
)

object ConnectionPool {

  /**
   * Creates a new connection pool for the given configuration
   */
  def create(pgConfig: PgConfig): ConnectionPool = new ConnectionPool(pgConfig)
}
