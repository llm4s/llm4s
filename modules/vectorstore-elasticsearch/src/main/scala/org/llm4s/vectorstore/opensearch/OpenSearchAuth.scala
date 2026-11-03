package org.llm4s.vectorstore.opensearch

import org.llm4s.security.Secret

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
  final case class Basic(username: String, password: Secret) extends OpenSearchAuth {
    // Secret.basic registers the password and the base64 token, so an error body echoing either is scrubbed
    private val token: Secret                            = Secret.basic(username, password.reveal)
    private[opensearch] def headers: Map[String, String] = Map("Authorization" -> s"Basic ${token.reveal}")
    private[opensearch] def secrets: Seq[String]         = Seq(password.reveal, token.reveal)
    override def toString: String                        = s"Basic($username, ***)"
  }

  /** A bearer token, sent as `Authorization: Bearer <token>`: a JWT the security plugin's JWT backend accepts. */
  final case class BearerToken(token: Secret) extends OpenSearchAuth {
    private[opensearch] def headers: Map[String, String] = Map("Authorization" -> s"Bearer ${token.reveal}")
    private[opensearch] def secrets: Seq[String]         = Seq(token.reveal)
    override def toString: String                        = "BearerToken(***)"
  }
}
