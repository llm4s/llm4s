package org.llm4s.llmconnect.config

import org.llm4s.annotation.Experimental
import org.llm4s.types.Result
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

  /** How a [[validate]] refusal names a field: `workloadIdentity.<field>`, as `AnthropicConfig` holds it. */
  private[llm4s] val FieldPrefix: String = "workloadIdentity."

  /**
   * The rules every [[AnthropicWorkloadIdentity]] must meet, whichever way it was built:
   * `identityTokenFile`, `federationRuleId` and `organizationId` must not be blank, nor
   * `serviceAccountId` or `workspaceId` when set. `AnthropicConfig.validate` applies them, so
   * `AnthropicConfig.fromValues`, the named section and `AnthropicClient` all do. A refusal is a
   * `ConfigurationError` naming the field as `workloadIdentity.<field>`.
   */
  private[llm4s] def validate(identity: AnthropicWorkloadIdentity): Result[AnthropicWorkloadIdentity] =
    for
      _ <- ProviderConfig.nonEmpty("Anthropic", s"${FieldPrefix}identityTokenFile", identity.identityTokenFile.toString)
      _ <- ProviderConfig.nonEmpty("Anthropic", s"${FieldPrefix}federationRuleId", identity.federationRuleId)
      _ <- ProviderConfig.nonEmpty("Anthropic", s"${FieldPrefix}organizationId", identity.organizationId)
      _ <- ProviderConfig.nonEmptyIfSet("Anthropic", s"${FieldPrefix}serviceAccountId", identity.serviceAccountId)
      _ <- ProviderConfig.nonEmptyIfSet("Anthropic", s"${FieldPrefix}workspaceId", identity.workspaceId)
    yield identity
