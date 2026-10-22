package org.llm4s.vectorstore.opensearch

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * How [[OpenSearchVectorStore]] authenticates to the cluster. Credentials are never printed by `toString`.
 *
 * The security plugin's HTTP basic authentication (its internal user database, as on a self-managed cluster or an
 * Amazon OpenSearch Service domain with fine-grained access control) and bearer tokens (its JWT backend) are
 * supported. AWS Signature Version 4 request signing (IAM) is not.
 */
sealed trait OpenSearchAuth {

  /** The `Authorization` header this sends, if any. */
  private[opensearch] def headers: Map[String, String]

  /** Every credential this carries, so an error body that echoes one can be scrubbed. */
  private[opensearch] def secrets: Seq[String]
}

object OpenSearchAuth {

  /** No credentials: a local cluster with the security plugin disabled. */
  case object NoAuth extends OpenSearchAuth {
    private[opensearch] def headers: Map[String, String] = Map.empty
    private[opensearch] def secrets: Seq[String]         = Seq.empty
  }

  /** HTTP basic authentication, for a user of the security plugin such as `admin`. */
  final case class Basic(username: String, password: String) extends OpenSearchAuth {
    private def token: String =
      Base64.getEncoder.encodeToString(s"$username:$password".getBytes(StandardCharsets.UTF_8))
    private[opensearch] def headers: Map[String, String] = Map("Authorization" -> s"Basic $token")
    private[opensearch] def secrets: Seq[String]         = Seq(password, token)
    override def toString: String                        = s"Basic($username, ***)"
  }

  /** A bearer token, sent as `Authorization: Bearer <token>`: a JWT the security plugin's JWT backend accepts. */
  final case class BearerToken(token: String) extends OpenSearchAuth {
    private[opensearch] def headers: Map[String, String] = Map("Authorization" -> s"Bearer $token")
    private[opensearch] def secrets: Seq[String]         = Seq(token)
    override def toString: String                        = "BearerToken(***)"
  }
}
