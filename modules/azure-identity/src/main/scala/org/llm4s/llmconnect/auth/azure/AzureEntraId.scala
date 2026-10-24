package org.llm4s.llmconnect.auth.azure

import com.azure.core.credential.{ TokenCredential, TokenRequestContext }
import com.azure.identity.DefaultAzureCredentialBuilder
import org.llm4s.annotation.Experimental
import org.llm4s.error.AuthenticationError
import org.llm4s.llmconnect.auth.{ AccessToken, AccessTokenProvider, CachingAccessTokenProvider }
import org.llm4s.llmconnect.provider.AzureEntraIdSupport
import org.llm4s.types.Result

import java.time.Clock
import scala.concurrent.duration.*
import scala.util.Try

/**
 * Microsoft Entra ID tokens for Azure OpenAI, keyless (#2274): an
 * [[org.llm4s.llmconnect.auth.AccessTokenProvider AccessTokenProvider]] over any Azure `TokenCredential`, for
 * `AzureConfig.withTokenProvider` and `OpenAIEmbeddingProvider.fromConfig(config, tokenProvider)`.
 *
 * A token is fetched for [[CognitiveServicesScope]], cached, and fetched again [[RefreshMargin]] before it expires
 * (or half-way through its life, if it is shorter), so a request never carries an expired one. Concurrent callers
 * share one fetch, and a token the service rejects with a 401 is dropped, so the next request fetches a new one.
 *
 * {{{
 * // DefaultAzureCredential: environment, workload identity, managed identity, the Azure CLI's login, ...
 * val config = AzureConfig.keyless(endpoint, "my-gpt-4o-deployment", AzureEntraId.defaultCredential())
 *
 * // Any other TokenCredential, such as a user-assigned managed identity's
 * val tokens = AzureEntraId.tokenProvider(new ManagedIdentityCredentialBuilder().clientId(id).build())
 * }}}
 *
 * A section of `application.conf` does the same with `authentication = "entra-id"` (and, for a user-assigned
 * managed identity, `clientId`) once this module is on the classpath.
 */
@Experimental
object AzureEntraId:

  /** The scope of an Azure OpenAI (Azure AI services) token. */
  val CognitiveServicesScope: String = "https://cognitiveservices.azure.com/.default"

  /**
   * How long before a token expires it is fetched again. Azure's own clients refresh five minutes ahead; a token
   * that lives shorter than twice this is refreshed half-way through its life.
   */
  val RefreshMargin: FiniteDuration = 5.minutes

  /** Tokens for Azure OpenAI from `credential`, cached and refreshed before they expire. */
  def tokenProvider(credential: TokenCredential): AccessTokenProvider =
    tokenProvider(credential, CognitiveServicesScope)

  /** Tokens for `scope` from `credential`, cached and refreshed before they expire. */
  def tokenProvider(credential: TokenCredential, scope: String): AccessTokenProvider =
    tokenProvider(credential, scope, Clock.systemUTC())

  private[azure] def tokenProvider(credential: TokenCredential, scope: String, clock: Clock): AccessTokenProvider =
    new CachingAccessTokenProvider(() => fetch(credential, scope), RefreshMargin, clock)

  /**
   * Tokens from Azure's `DefaultAzureCredential`, which tries, in turn, the environment (`AZURE_CLIENT_ID` with a
   * secret or certificate), workload identity (AKS), managed identity, and the developer's Azure CLI, PowerShell or
   * azd login. A system-assigned managed identity needs nothing more.
   */
  def defaultCredential(): AccessTokenProvider = tokenProvider(defaultAzureCredential(None))

  /**
   * [[defaultCredential()* defaultCredential]], with the client id of the user-assigned managed identity - or
   * workload identity - to use.
   */
  def defaultCredential(clientId: String): AccessTokenProvider =
    tokenProvider(defaultAzureCredential(Option(clientId).map(_.trim).filter(_.nonEmpty)))

  private[azure] def defaultAzureCredential(clientId: Option[String]): TokenCredential =
    val builder = new DefaultAzureCredentialBuilder()
    clientId.foreach { id =>
      builder.managedIdentityClientId(id)
      builder.workloadIdentityClientId(id)
    }
    builder.build()

  /**
   * One token for `scope`. A failure is an `AuthenticationError` carrying the credential's own message, which says
   * which sources were tried and why each was unavailable - what an operator needs - and holds no secret.
   */
  private[azure] def fetch(credential: TokenCredential, scope: String): Result[AccessToken] =
    Try(Option(credential.getTokenSync(new TokenRequestContext().addScopes(scope)))).toEither.left
      .map(e =>
        AuthenticationError(
          Provider,
          s"Microsoft Entra ID did not issue a token for $scope: ${e.getClass.getSimpleName}: " +
            Option(e.getMessage).getOrElse("")
        )
      )
      .flatMap(_.toRight(AuthenticationError(Provider, s"Microsoft Entra ID returned no token for $scope")))
      .map(token => AccessToken(token.getToken, token.getExpiresAt.toInstant))

  private val Provider = "azure"

/**
 * Not part of the API: the implementation `llm4s-openai` finds through `META-INF/services` for a section with
 * `authentication = "entra-id"`.
 */
final private[llm4s] class DefaultAzureEntraIdSupport extends AzureEntraIdSupport:
  override def tokenProvider(clientId: Option[String]): Result[AccessTokenProvider] =
    Try(AzureEntraId.tokenProvider(AzureEntraId.defaultAzureCredential(clientId))).toEither.left.map(e =>
      AuthenticationError("azure", s"DefaultAzureCredential could not be built: ${e.getClass.getSimpleName}")
    )
