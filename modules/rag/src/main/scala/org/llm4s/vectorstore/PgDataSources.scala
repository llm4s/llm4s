package org.llm4s.vectorstore

import javax.sql.DataSource

/** How the Postgres stores and indexes release a `javax.sql.DataSource` they own. */
private[llm4s] object PgDataSources {

  /**
   * Closes `dataSource`, a pool the caller of this method owns, if it can be closed: HikariCP's pool is
   * `AutoCloseable`, while a plain `DataSource` such as pgjdbc's `PGSimpleDataSource` holds no connections to release.
   * Never call it on a caller-owned pool.
   */
  def closeOwned(dataSource: DataSource): Unit = dataSource match {
    case closeable: AutoCloseable => closeable.close()
    case _                        => ()
  }
}
