package org.llm4s.config

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.spi.ProviderConfigSpec
import org.llm4s.types.Result
import org.llm4s.config.ProvidersConfigModel.{ ProviderName, RawNamedProviderSection, RawProvidersConfig }
import pureconfig.error.{ ConfigReaderFailures, ConvertFailure, UserValidationFailed }
import pureconfig.{ ConfigObjectCursor, ConfigReader => PureConfigReader, ConfigSource }

import scala.jdk.CollectionConverters.*

/** Reads the raw providers configuration block from a PureConfig source without validation. */
private[config] object RawProvidersConfigLoader:

  private val builtinFieldsReader: PureConfigReader[RawNamedProviderSection] =
    PureConfigReader.forProduct5(
      "provider",
      "model",
      "baseUrl",
      "apiKey",
      "headers"
    )(RawNamedProviderSection(_, _, _, _, _))

  /**
   * The `auth` block, if there is one, as strings: scalars (strings, numbers, booleans) are read as
   * strings and a `null` as absent, like the provider-specific keys; an object or a list is an
   * error. A key bound to an unset `${?VAR}` is not in the object at all, so it is simply absent.
   */
  private def readAuth(objCursor: ConfigObjectCursor): Either[ConfigReaderFailures, Option[Map[String, String]]] =
    val authCursor = objCursor.atKeyOrUndefined("auth")
    if authCursor.isUndefined || authCursor.isNull then Right(None)
    else
      authCursor.asObjectCursor.flatMap { authObj =>
        authObj.objValue
          .keySet()
          .asScala
          .toList
          .sorted
          .foldLeft[Either[ConfigReaderFailures, Map[String, String]]](Right(Map.empty)) { case (accEither, key) =>
            for
              acc       <- accEither
              keyCursor <- authObj.atKey(key)
              value <-
                if keyCursor.isNull then Right(None)
                else
                  PureConfigReader[String]
                    .from(keyCursor)
                    .map(Some(_))
                    .left
                    .flatMap(_ =>
                      keyCursor.failed(
                        UserValidationFailed(s"auth key '${keyCursor.path}' must be a string, number or boolean")
                      )
                    )
            yield value.fold(acc)(acc.updated(key, _))
          }
          .map(Some(_))
      }

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
        auth <- readAuth(objCursor)
      yield builtins.copy(extras = extras, auth = auth)
    }

  /** The block as read, each section's read failure kept to that section. */
  final private case class RawSections(
    selectedProvider: Option[ProviderName],
    sections: Map[ProviderName, Either[ConfigReaderFailures, RawNamedProviderSection]]
  )

  /**
   * Reads each named section on its own, so a section that cannot be read - a key of the wrong
   * type, a value that is not an object - fails only that section (#1132). Only the block's own
   * shape (`llm4s.providers` not an object, `llm4s.providers.provider` not a string) fails the
   * whole read.
   */
  private given rawSectionsReader: PureConfigReader[RawSections] =
    PureConfigReader.fromCursor { cursor =>
      cursor.asObjectCursor.flatMap { objCursor =>
        val selectedKey = objCursor.atKeyOrUndefined("provider")
        val selectedProvider =
          if selectedKey.isUndefined then Right(None)
          else selectedKey.asString.map(value => Option(value.trim).filter(_.nonEmpty).map(ProviderName.apply))

        val sections =
          objCursor.objValue
            .keySet()
            .asScala
            .toList
            .filterNot(_ == "provider")
            .map(key => ProviderName(key) -> objCursor.atKey(key).flatMap(namedProviderSectionReader.from))
            .toMap

        selectedProvider.map(RawSections(_, sections))
      }
    }

  /**
   * Reads the `llm4s.providers` config block and returns an unvalidated `RawProvidersConfig`.
   *
   * Fails if any section cannot be read. Resolving a single section goes through
   * [[loadSections]] instead, which confines a section's read failure to that section.
   *
   *  @param source the PureConfig source to read from
   *  @return `Right(RawProvidersConfig)` on success, or `Left` with a `ConfigurationError`
   */
  def load(source: ConfigSource): Result[RawProvidersConfig] =
    read(source).flatMap { raw =>
      raw.sections.values.collect { case Left(failures) => failures }.reduceOption(_ ++ _) match
        case Some(failures) => Left(loadError(failures))
        case None =>
          Right(
            RawProvidersConfig(
              selectedProvider = raw.selectedProvider,
              namedProviders = raw.sections.collect { case (name, Right(section)) => name -> section }
            )
          )
    }

  /**
   * Reads the `llm4s.providers` config block, keeping each section's read failure to that section,
   * together with the `llm4s.credentials` block its sections fall back to for an API key.
   *
   *  @param source the PureConfig source to read from
   *  @return `Right` with each section read or failed on its own, or `Left` when the block itself is unreadable
   */
  def loadSections(source: ConfigSource): Result[ProviderSections] =
    read(source).map { raw =>
      ProviderSections(
        selectedProvider = raw.selectedProvider,
        sections = raw.sections.map { case (name, section) =>
          name -> section.left.map { failures =>
            ConfigurationError(s"Failed to read llm4s.providers.${name.asName}: ${describe(failures)}")
          }
        },
        credentials = SharedCredentials.read(source)
      )
    }

  private def read(source: ConfigSource): Result[RawSections] =
    source.at("llm4s.providers").load[RawSections].left.map(loadError)

  private def loadError(failures: ConfigReaderFailures): ConfigurationError =
    ConfigurationError(s"Failed to load raw providers config via PureConfig: ${describe(failures)}")

  // A failure's description alone ("Expected type NUMBER. Found STRING instead.") does not say
  // which key it is about, so the path is shown when there is one.
  private def describe(failures: ConfigReaderFailures): String =
    failures.toList
      .map {
        case failure: ConvertFailure if failure.path.nonEmpty => s"${failure.path}: ${failure.description}"
        case failure                                          => failure.description
      }
      .mkString("; ")
