package org.llm4s.llmconnect.config

import org.llm4s.annotation.Experimental
import org.llm4s.llmconnect.auth.IdentitySource
import org.llm4s.util.Redaction

/**
 * OpenAI workload identity federation: the identity token (e.g. a SPIFFE JWT-SVID) the OpenAI SDK
 * exchanges at OpenAI's token endpoint for the service account's access token. The ids are redacted
 * in `toString`.
 *
 * @param identityToken      where the identity token comes from; read afresh on each exchange
 * @param identityProviderId the OpenAI workload identity provider (`OPENAI_IDENTITY_PROVIDER_ID`)
 * @param serviceAccountId   the OpenAI service account to act as (`OPENAI_SERVICE_ACCOUNT_ID`)
 * @param clientId           sent with the exchange when the identity provider requires one
 */
@Experimental
final case class OpenAIWorkloadIdentity private (
  identityToken: IdentitySource,
  identityProviderId: String,
  serviceAccountId: String,
  clientId: Option[String]
):
  def withIdentityToken(identityToken: IdentitySource): OpenAIWorkloadIdentity = copy(identityToken = identityToken)
  def withIdentityProviderId(identityProviderId: String): OpenAIWorkloadIdentity =
    copy(identityProviderId = identityProviderId)
  def withServiceAccountId(serviceAccountId: String): OpenAIWorkloadIdentity = copy(serviceAccountId = serviceAccountId)
  def withClientId(clientId: String): OpenAIWorkloadIdentity                 = copy(clientId = Some(clientId))
  def withClientId(clientId: Option[String]): OpenAIWorkloadIdentity         = copy(clientId = clientId)

  override def toString: String =
    s"OpenAIWorkloadIdentity($identityToken, identityProviderId=***, serviceAccountId=***, " +
      s"clientId=${Redaction.secretOpt(clientId)})"

object OpenAIWorkloadIdentity:

  /** Creates an [[OpenAIWorkloadIdentity]]. Named arguments are the supported way to construct one. */
  def apply(
    identityToken: IdentitySource,
    identityProviderId: String,
    serviceAccountId: String,
    clientId: Option[String] = None
  ): OpenAIWorkloadIdentity =
    new OpenAIWorkloadIdentity(identityToken, identityProviderId, serviceAccountId, clientId)
