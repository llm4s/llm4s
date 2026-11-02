package org.llm4s.vectorstore

import org.llm4s.security.{ KnownSecrets, Secret }
import org.llm4s.util.Redaction
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class JdbcUrlCredentialsSpec extends AnyFlatSpec with Matchers {

  "JdbcUrlCredentials.register" should "register a password carried in the query so a driver error echoing the URL is scrubbed" in
    KnownSecrets.isolated {
      val url = "jdbc:postgresql://db:5432/app?user=u&password=urlpw-secret-1&ssl=true"
      JdbcUrlCredentials.register(url)
      (Redaction.scrubKnown(s"connection to $url refused") should not).include("urlpw-secret-1")
    }

  it should "register the password of user info before the host" in KnownSecrets.isolated {
    val url = "jdbc:postgresql://app:userinfo-pw-2@db:5432/app"
    JdbcUrlCredentials.register(url)
    (Redaction.scrubKnown(s"cannot reach $url") should not).include("userinfo-pw-2")
  }

  it should "leave a URL with no password alone" in KnownSecrets.isolated {
    JdbcUrlCredentials.register("jdbc:postgresql://db:5432/app")
    Redaction.scrubKnown("jdbc:postgresql://db:5432/app") shouldBe "jdbc:postgresql://db:5432/app"
  }

  "VectorStoreFactory.Config" should "print its credentials masked" in KnownSecrets.isolated {
    val shown = VectorStoreFactory.Config
      .pgvector("jdbc:postgresql://db:5432/app", user = Some("u"), password = Some(Secret("factory-pw-3")))
      .toString
    (shown should not).include("factory-pw-3")
    VectorStoreFactory.Config.qdrantCloud("https://q.example", Secret("factory-key-4")).toString should include("***")
  }
}
