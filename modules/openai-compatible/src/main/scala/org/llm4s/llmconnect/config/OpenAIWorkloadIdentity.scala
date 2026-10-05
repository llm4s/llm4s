package org.llm4s.llmconnect.config

import org.llm4s.annotation.Experimental
import org.llm4s.llmconnect.auth.IdentitySource

/**
 * OpenAI workload identity federation: the identity token (e.g. a SPIFFE JWT-SVID) the OpenAI SDK
 * exchanges at OpenAI's token endpoint for the service account's access token.
 *
 * @param identityToken      where the identity token comes from; read afresh on each exchange
 * @param identityProviderId the OpenAI workload identity provider (`OPENAI_IDENTITY_PROVIDER_ID`)
 * @param serviceAccountId   the OpenAI service account to act as (`OPENAI_SERVICE_ACCOUNT_ID`)
 * @param clientId           sent with the exchange when the identity provider requires one
 */
@Experimental
final case class OpenAIWorkloadIdentity(
  identityToken: IdentitySource,
  identityProviderId: String,
  serviceAccountId: String,
  clientId: Option[String] = None
)
