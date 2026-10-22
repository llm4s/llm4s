package org.llm4s.vectorstore.elasticsearch

import java.nio.charset.StandardCharsets
import java.util.Base64

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
  final case class ApiKey(encoded: String) extends ElasticsearchAuth {
    private[elasticsearch] def headers: Map[String, String] = Map("Authorization" -> s"ApiKey $encoded")
    private[elasticsearch] def secrets: Seq[String]         = Seq(encoded)
    override def toString: String                           = "ApiKey(***)"
  }

  /** HTTP basic authentication, for a native-realm user such as `elastic`. */
  final case class Basic(username: String, password: String) extends ElasticsearchAuth {
    private def token: String =
      Base64.getEncoder.encodeToString(s"$username:$password".getBytes(StandardCharsets.UTF_8))
    private[elasticsearch] def headers: Map[String, String] = Map("Authorization" -> s"Basic $token")
    private[elasticsearch] def secrets: Seq[String]         = Seq(password, token)
    override def toString: String                           = s"Basic($username, ***)"
  }
}
