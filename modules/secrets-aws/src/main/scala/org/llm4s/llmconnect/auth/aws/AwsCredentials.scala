package org.llm4s.llmconnect.auth.aws

import org.llm4s.annotation.Experimental
import org.llm4s.security.Secret

/**
 * The AWS credentials [[AwsSecretsManagerApiKeyProvider]] signs its requests with (Signature Version 4): an access
 * key id, its secret access key and, for temporary credentials from STS, a session token.
 *
 * {{{
 * val longLived = AwsCredentials("AKIA...", Secret(secretAccessKey))
 * val temporary = AwsCredentials("ASIA...", Secret(secretAccessKey)).withSessionToken(Secret(sessionToken))
 * }}}
 *
 * @param accessKeyId     the access key id, `AKIA...` or, for temporary credentials, `ASIA...`; not secret
 * @param secretAccessKey the secret access key; never printed by `toString` nor put in an error
 * @param sessionToken    the session token of temporary credentials, sent as `X-Amz-Security-Token`
 */
@Experimental
final case class AwsCredentials private (
  accessKeyId: String,
  secretAccessKey: Secret,
  sessionToken: Option[Secret]
):

  /** The session token of temporary credentials. */
  def withSessionToken(sessionToken: Secret): AwsCredentials = copy(sessionToken = Some(sessionToken))

  /** The session token of temporary credentials, or none for long-lived ones. */
  def withSessionToken(sessionToken: Option[Secret]): AwsCredentials = copy(sessionToken = sessionToken)

  override def toString: String =
    s"AwsCredentials($accessKeyId, secretAccessKey=***, sessionToken=${sessionToken.fold("-")(_ => "***")})"

object AwsCredentials:

  /** Long-lived credentials: an access key id and its secret access key, with no session token. */
  def apply(accessKeyId: String, secretAccessKey: Secret): AwsCredentials =
    new AwsCredentials(accessKeyId, secretAccessKey, None)
