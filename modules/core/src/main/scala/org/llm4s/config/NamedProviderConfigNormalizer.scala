package org.llm4s.config

import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result
import org.llm4s.config.ProvidersConfigModel.*

private[config] object NamedProviderConfigNormalizer:

  def normalize(
    providerName: ProviderName,
    section: RawNamedProviderSection
  ): Result[NamedProviderConfig] =
    val providerType =
      section.provider.map(_.trim).filter(_.nonEmpty) match
        case None =>
          Left(ConfigurationError(s"Configured provider '${providerName.asName}' is missing required field `provider`"))
        case Some(value) =>
          ProviderKind
            .fromString(value)
            .toRight(
              ConfigurationError(s"Configured provider '${providerName.asName}' has unknown provider '$value'")
            )

    val modelName =
      section.model
        .map(_.trim)
        .filter(_.nonEmpty)
        .toRight(ConfigurationError(s"Configured provider '${providerName.asName}' is missing required field `model`"))

    for
      kind  <- providerType
      model <- modelName
    yield NamedProviderConfig(
      provider = kind,
      model = ModelName(model),
      baseUrl = section.baseUrl.map(_.trim).filter(_.nonEmpty).map(BaseUrl(_)),
      apiKey = section.apiKey.map(_.trim).filter(_.nonEmpty).map(ApiKey(_)),
      organization = section.organization.map(_.trim).filter(_.nonEmpty),
      endpoint = section.endpoint.map(_.trim).filter(_.nonEmpty),
      apiVersion = section.apiVersion.map(_.trim).filter(_.nonEmpty)
    )
