package org.llm4s.config

import org.llm4s.error.ConfigurationError
import org.llm4s.types.ProviderModelTypes.*
import org.llm4s.types.Result

object ProvidersConfigModel:
  export org.llm4s.types.ProviderModelTypes.*

  final case class RawNamedProviderSection(
    provider: Option[String],
    model: Option[String],
    baseUrl: Option[String],
    apiKey: Option[String],
    organization: Option[String],
    endpoint: Option[String],
    apiVersion: Option[String]
  )

  final case class RawProvidersConfig(
    selectedProvider: Option[ProviderName],
    namedProviders: Map[ProviderName, RawNamedProviderSection]
  )

  final case class NamedProviderConfig(
    provider: ProviderKind,
    model: ModelName,
    baseUrl: Option[BaseUrl],
    apiKey: Option[ApiKey],
    organization: Option[String],
    endpoint: Option[String],
    apiVersion: Option[String]
  ):
    def requireProvider(expected: ProviderKind): Result[NamedProviderConfig] =
      if provider == expected then Right(this)
      else
        Left(
          ConfigurationError(
            s"Model discovery is not supported yet for provider '${provider.toString.toLowerCase}'"
          )
        )

    def requireBaseUrl: Result[BaseUrl] =
      baseUrl.toRight(ConfigurationError("Configured provider is missing required field `baseUrl`"))

    def baseUrlOrDefault(default: => String): BaseUrl =
      baseUrl.getOrElse(BaseUrl(default))

    def requireApiKey: Result[ApiKey] =
      apiKey.toRight(ConfigurationError("Configured provider is missing required field `apiKey`"))

  final case class ProvidersConfig(
    selectedProvider: Option[ProviderName],
    namedProviders: Map[ProviderName, NamedProviderConfig]
  ):
    def defaultProviderName: Result[ProviderName] =
      selectedProvider.toRight(
        ConfigurationError("No default provider configured under llm4s.providers.provider")
      )
