package org.llm4s.llmconnect.config

import org.llm4s.annotation.Stable
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.auth.IdentityTokenSource
import org.llm4s.types.Result
import org.llm4s.util.Redaction

import java.nio.file.{ Files, Path }
import scala.util.Try

/**
 * Anthropic workload identity federation: the Anthropic SDK reads the identity token (e.g. a
 * SPIFFE JWT-SVID kept fresh by spiffe-helper) from `identityTokenFile` on every exchange and
 * presents it to `<baseUrl>/v1/oauth/token` under `federationRuleId`. The ids are redacted in
 * `toString`.
 *
 * The SDK reads the file itself, so llm4s's own checks on an identity token file (a regular file of at most
 * 64 KiB, read with a bounded read) do not guard its reads. `AnthropicClient` applies them when the client is
 * built ([[AnthropicWorkloadIdentity.checkTokenFile]]): a path that is a directory, a FIFO, a device or a file larger
 * than a token is refused then. A path that does not exist yet is accepted, since an identity agent such as
 * `spiffe-helper` may write it after the application starts; and the SDK's later reads are unchecked, so point it at
 * a file the agent maintains.
 */
@Stable
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

  /**
   * The checks llm4s applies to an identity token file it reads itself, applied to the file the Anthropic SDK will
   * read (#1818): when `identityTokenFile` exists, it must be a regular file (after following links) of at most
   * 64 KiB. A path that does not exist yet passes. A refusal is a `ConfigurationError` naming
   * `workloadIdentity.identityTokenFile`; neither the file's content nor any part of it is read.
   */
  private[llm4s] def checkTokenFile(identity: AnthropicWorkloadIdentity): Result[AnthropicWorkloadIdentity] =
    val path  = identity.identityTokenFile
    val field = s"${FieldPrefix}identityTokenFile"
    def refuse(reason: String) =
      Left(ConfigurationError(s"Anthropic $field $path $reason", List(field)))
    if !Try(Files.exists(path)).getOrElse(false) then Right(identity)
    else if !Try(Files.isRegularFile(path)).getOrElse(false) then refuse("is not a regular file")
    else
      Try(Files.size(path)).toOption match
        case Some(size) if size > IdentityTokenSource.MaxTokenFileBytes =>
          refuse(s"is larger than ${IdentityTokenSource.MaxTokenFileBytes} bytes, so not an identity token")
        case Some(_) => Right(identity)
        case None    => refuse("cannot be read")

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
