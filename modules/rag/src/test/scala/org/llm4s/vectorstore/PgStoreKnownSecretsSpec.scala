package org.llm4s.vectorstore

import org.llm4s.security.{ KnownSecrets, Secret }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.PrintWriter
import java.sql.{ Connection, SQLException, SQLFeatureNotSupportedException }
import java.util.logging.Logger
import javax.sql.DataSource

/** A registered credential in a driver's exception message never reaches a Postgres store's `LLMError` (#1932). */
class PgStoreKnownSecretsSpec extends AnyFlatSpec with Matchers {

  private val Password = "pgvector-password-0001"

  /** Every connection attempt fails with a message that quotes the password, as a misconfigured driver might. */
  private object EchoingDataSource extends DataSource {
    override def getConnection(): Connection =
      throw new SQLException(s"FATAL: password authentication failed (password=$Password)")
    override def getConnection(username: String, password: String): Connection = getConnection()
    override def getLogWriter: PrintWriter                                     = null
    override def setLogWriter(out: PrintWriter): Unit                          = ()
    override def setLoginTimeout(seconds: Int): Unit                           = ()
    override def getLoginTimeout: Int                                          = 0
    override def getParentLogger: Logger                = throw new SQLFeatureNotSupportedException()
    override def unwrap[T](iface: Class[T]): T          = throw new SQLException("not a wrapper")
    override def isWrapperFor(iface: Class[?]): Boolean = false
  }

  "PgVectorStore" should "keep a registered password out of an error built from a driver exception" in KnownSecrets
    .isolated {
      Secret(Password)
      val result = PgVectorStore.create(EchoingDataSource, "vectors").flatMap(_.count(None))
      val error  = result.left.getOrElse(fail(s"expected a failure, got $result"))
      error.message should include("password authentication failed")
      (error.message should not).include(Password)
    }

  "PgKeywordIndex" should "keep a registered password out of an error built from a driver exception" in KnownSecrets
    .isolated {
      Secret(Password)
      val result = PgKeywordIndex(EchoingDataSource, "keywords").flatMap(_.count())
      val error  = result.left.getOrElse(fail(s"expected a failure, got $result"))
      error.message should include("password authentication failed")
      (error.message should not).include(Password)
    }
}
