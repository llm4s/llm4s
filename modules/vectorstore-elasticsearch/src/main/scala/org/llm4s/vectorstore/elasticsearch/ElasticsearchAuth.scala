package org.llm4s.vectorstore.elasticsearch

import org.llm4s.security.Secret

/**
 * How [[ElasticsearchVectorStore]] authenticates to the cluster. Credentials are never printed by `toString`.
 */
sealed trait ElasticsearchAuth {

  /** The `Authorization` header this sends, if any. */
  private[elasticsearch] def headers: Map[String, String]

  /** Every credential this carries, so an error body that echoes one can be scrubbed. */
  private[elasticsearch] def secrets: Seq[String]
}

object ElasticsearchAuth {

  /** No credentials: a local cluster with security disabled. */
  case object NoAuth extends ElasticsearchAuth {
    private[elasticsearch] def headers: Map[String, String] = Map.empty
    private[elasticsearch] def secrets: Seq[String]         = Seq.empty
  }

  /**
   * An Elasticsearch API key, sent as `Authorization: ApiKey <encoded>`.
   *
   * @param encoded the key's `encoded` value, as the create-API-key API and Kibana show it (Base64 of `id:api_key`)
   */
  final case class ApiKey(encoded: Secret) extends ElasticsearchAuth {
    private[elasticsearch] def headers: Map[String, String] = Map("Authorization" -> s"ApiKey ${encoded.reveal}")
    private[elasticsearch] def secrets: Seq[String]         = Seq(encoded.reveal)
    override def toString: String                           = "ApiKey(***)"
  }

  /** HTTP basic authentication, for a native-realm user such as `elastic`. */
  final case class Basic(username: String, password: Secret) extends ElasticsearchAuth {
    // Secret.basic registers the password and the base64 token, so an error body echoing either is scrubbed
    private val token: Secret                               = Secret.basic(username, password.reveal)
    private[elasticsearch] def headers: Map[String, String] = Map("Authorization" -> s"Basic ${token.reveal}")
    private[elasticsearch] def secrets: Seq[String]         = Seq(password.reveal, token.reveal)
    override def toString: String                           = s"Basic($username, ***)"
  }
}
