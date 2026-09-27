package org.llm4s.config

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.spi.ProviderConfigSpec
import org.llm4s.types.Result
import org.llm4s.config.ProvidersConfigModel.{ ProviderName, RawNamedProviderSection, RawProvidersConfig }
import pureconfig.error.{ ConfigReaderFailures, UserValidationFailed }
import pureconfig.{ ConfigReader => PureConfigReader, ConfigSource }

import scala.jdk.CollectionConverters.*

/** Reads the raw providers configuration block from a PureConfig source without validation. */
private[config] object RawProvidersConfigLoader:

  private val builtinFieldsReader: PureConfigReader[RawNamedProviderSection] =
    PureConfigReader.forProduct10(
      "provider",
      "model",
      "baseUrl",
      "apiKey",
      "organization",
      "endpoint",
      "apiVersion",
      "contextWindow",
      "reserveCompletion",
      "headers"
    )(RawNamedProviderSection(_, _, _, _, _, _, _, _, _, _))

  /**
   * The built-in fields, plus every other key as a string in `extras`.
   *
   * Which extra keys are valid depends on the provider, which is not known until the
   * section is normalised, so every non-built-in key is kept here and the section
   * validator decides. Scalars (strings, numbers, booleans) are read as strings, a
   * `null` as absent; an object or list is an error, since no extra key takes one.
   */
  private given namedProviderSectionReader: PureConfigReader[RawNamedProviderSection] =
    PureConfigReader.fromCursor { cursor =>
      for
        builtins  <- builtinFieldsReader.from(cursor)
        objCursor <- cursor.asObjectCursor
        extraKeys = objCursor.objValue
          .keySet()
          .asScala
          .toList
          .sorted
          .filterNot(ProviderConfigSpec.BuiltinKeys.contains)
        extras <- extraKeys.foldLeft[Either[ConfigReaderFailures, Map[String, String]]](Right(Map.empty)) {
          case (accEither, key) =>
            for
              acc       <- accEither
              keyCursor <- objCursor.atKey(key)
              value <-
                if keyCursor.isNull then Right(None)
                else
                  PureConfigReader[String]
                    .from(keyCursor)
                    .map(Some(_))
                    .left
                    .flatMap(_ =>
                      keyCursor.failed(
                        UserValidationFailed(
                          s"provider-specific key '${keyCursor.path}' must be a string, number or boolean"
                        )
                      )
                    )
            yield value.fold(acc)(acc.updated(key, _))
        }
      yield builtins.copy(extras = extras)
    }

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

  /**
   * Reads the `llm4s.providers` config block and returns an unvalidated `RawProvidersConfig`.
   *
   *  @param source the PureConfig source to read from
   *  @return `Right(RawProvidersConfig)` on success, or `Left` with a `ConfigurationError`
   */
  def load(source: ConfigSource): Result[RawProvidersConfig] =
    source
      .at("llm4s.providers")
      .load[RawProvidersConfig]
      .left
      .map { failures =>
        val msg = failures.toList.map(_.description).mkString("; ")
        ConfigurationError(s"Failed to load raw providers config via PureConfig: $msg")
      }
