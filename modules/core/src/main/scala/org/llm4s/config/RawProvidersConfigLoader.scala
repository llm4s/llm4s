package org.llm4s.config

import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result
import org.llm4s.config.ProvidersConfigModel.{ ProviderName, RawNamedProviderSection, RawProvidersConfig }
import pureconfig.error.ConfigReaderFailures
import pureconfig.{ ConfigReader => PureConfigReader, ConfigSource }

import scala.jdk.CollectionConverters.*

private[config] object RawProvidersConfigLoader:

  private given namedProviderSectionReader: PureConfigReader[RawNamedProviderSection] =
    PureConfigReader.forProduct7("provider", "model", "baseUrl", "apiKey", "organization", "endpoint", "apiVersion")(
      RawNamedProviderSection.apply
    )

  private given rawProvidersConfigReader: PureConfigReader[RawProvidersConfig] =
    PureConfigReader.fromCursor { cursor =>
      cursor.asObjectCursor.flatMap { objCursor =>
        def readOptionalString(key: String): Either[ConfigReaderFailures, Option[String]] =
          val keyCursor = objCursor.atKeyOrUndefined(key)
          if keyCursor.isUndefined then Right(None)
          else keyCursor.asString.map(value => Option(value.trim).filter(_.nonEmpty))

        val selectedProviderEither =
          readOptionalString("provider").map(_.map(ProviderName.apply))

        val namedProvidersEither =
          objCursor.objValue
            .keySet()
            .asScala
            .toList
            .filterNot(_ == "provider")
            .foldLeft[Either[ConfigReaderFailures, Map[ProviderName, RawNamedProviderSection]]](Right(Map.empty)) {
              case (accEither, key) =>
                for
                  acc       <- accEither
                  keyCursor <- objCursor.atKey(key)
                  entry     <- namedProviderSectionReader.from(keyCursor)
                yield acc.updated(ProviderName(key), entry)
            }

        for
          selectedProvider <- selectedProviderEither
          namedProviders   <- namedProvidersEither
        yield RawProvidersConfig(
          selectedProvider = selectedProvider,
          namedProviders = namedProviders,
        )
      }
    }

  def load(source: ConfigSource): Result[RawProvidersConfig] =
    source
      .at("llm4s.providers")
      .load[RawProvidersConfig]
      .left
      .map { failures =>
        val msg = failures.toList.map(_.description).mkString("; ")
        ConfigurationError(s"Failed to load raw providers config via PureConfig: $msg")
      }
