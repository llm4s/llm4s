package org.llm4s.llmconnect.provider

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.auth.AccessTokenProvider
import org.llm4s.types.Result

import java.util.ServiceLoader
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * Not part of the API: how `llm4s-openai` reaches Microsoft Entra ID without depending on Azure's SDK (#2274).
 *
 * `azure-identity` brings Netty, Reactor and MSAL, which no OpenAI user who does not run on Azure should carry, so
 * `DefaultAzureCredential` lives in `llm4s-azure-identity`, which registers the one implementation in
 * `META-INF/services/org.llm4s.llmconnect.provider.AzureEntraIdSupport`. A section with
 * `authentication = "entra-id"` finds it here; without the module on the classpath it fails with a
 * `ConfigurationError` naming the artifact to add.
 */
private[llm4s] trait AzureEntraIdSupport:

  /**
   * Tokens for Azure OpenAI from `DefaultAzureCredential`, cached and refreshed before they expire.
   *
   * @param clientId the client id of a user-assigned managed identity or workload identity; `None` for the
   *                 system-assigned identity, or whatever the environment configures
   */
  def tokenProvider(clientId: Option[String]): Result[AccessTokenProvider]

private[llm4s] object AzureEntraIdSupport:

  /** The artifact that supplies the implementation. */
  val Artifact: String = "org.llm4s::llm4s-azure-identity"

  /** The implementation on `loader`'s classpath, or why there is none. */
  def load(loader: ClassLoader = classOf[AzureEntraIdSupport].getClassLoader): Result[AzureEntraIdSupport] =
    Try(ServiceLoader.load(classOf[AzureEntraIdSupport], loader).iterator().asScala.toList).toEither.left
      .map(e => ConfigurationError(s"Loading Microsoft Entra ID support failed: ${e.getClass.getName}"))
      .flatMap(
        _.headOption.toRight(
          ConfigurationError(
            s"""authentication = "entra-id" needs $Artifact on the classpath, which brings Azure's """ +
              "DefaultAzureCredential; add it next to llm4s-openai"
          )
        )
      )

  /** [[AzureEntraIdSupport.tokenProvider]] of the implementation on the classpath. */
  def tokenProvider(clientId: Option[String]): Result[AccessTokenProvider] =
    load().flatMap(_.tokenProvider(clientId))
