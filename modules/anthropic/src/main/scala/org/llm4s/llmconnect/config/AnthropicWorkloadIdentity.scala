package org.llm4s.llmconnect.config

import org.llm4s.annotation.Experimental

import java.nio.file.Path

/**
 * Anthropic workload identity federation: the Anthropic SDK reads the identity token (e.g. a
 * SPIFFE JWT-SVID kept fresh by spiffe-helper) from `identityTokenFile` on every exchange and
 * presents it to `<baseUrl>/v1/oauth/token` under `federationRuleId`.
 */
@Experimental
final case class AnthropicWorkloadIdentity(
  identityTokenFile: Path,
  federationRuleId: String,
  organizationId: String,
  serviceAccountId: Option[String] = None,
  workspaceId: Option[String] = None
)
