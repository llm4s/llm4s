package org.llm4s.llmconnect.config

import org.llm4s.annotation.Experimental
import org.llm4s.util.Redaction

import java.nio.file.Path

/**
 * Anthropic workload identity federation: the Anthropic SDK reads the identity token (e.g. a
 * SPIFFE JWT-SVID kept fresh by spiffe-helper) from `identityTokenFile` on every exchange and
 * presents it to `<baseUrl>/v1/oauth/token` under `federationRuleId`. The ids are redacted in
 * `toString`.
 */
@Experimental
final case class AnthropicWorkloadIdentity private (
  identityTokenFile: Path,
  federationRuleId: String,
  organizationId: String,
  serviceAccountId: Option[String],
  workspaceId: Option[String]
):
  def withIdentityTokenFile(identityTokenFile: Path): AnthropicWorkloadIdentity =
    copy(identityTokenFile = identityTokenFile)
  def withFederationRuleId(federationRuleId: String): AnthropicWorkloadIdentity =
    copy(federationRuleId = federationRuleId)
  def withOrganizationId(organizationId: String): AnthropicWorkloadIdentity = copy(organizationId = organizationId)
  def withServiceAccountId(serviceAccountId: String): AnthropicWorkloadIdentity =
    copy(serviceAccountId = Some(serviceAccountId))
  def withServiceAccountId(serviceAccountId: Option[String]): AnthropicWorkloadIdentity =
    copy(serviceAccountId = serviceAccountId)
  def withWorkspaceId(workspaceId: String): AnthropicWorkloadIdentity         = copy(workspaceId = Some(workspaceId))
  def withWorkspaceId(workspaceId: Option[String]): AnthropicWorkloadIdentity = copy(workspaceId = workspaceId)

  override def toString: String =
    s"AnthropicWorkloadIdentity(identityTokenFile=$identityTokenFile, federationRuleId=***, organizationId=***, " +
      s"serviceAccountId=${Redaction.secretOpt(serviceAccountId)}, workspaceId=${Redaction.secretOpt(workspaceId)})"

object AnthropicWorkloadIdentity:

  /** Creates an [[AnthropicWorkloadIdentity]]. Named arguments are the supported way to construct one. */
  def apply(
    identityTokenFile: Path,
    federationRuleId: String,
    organizationId: String,
    serviceAccountId: Option[String] = None,
    workspaceId: Option[String] = None
  ): AnthropicWorkloadIdentity =
    new AnthropicWorkloadIdentity(identityTokenFile, federationRuleId, organizationId, serviceAccountId, workspaceId)
