package org.llm4s.config

import org.llm4s.llmconnect.config.ProviderTimeouts
import pureconfig.error.UserValidationFailed
import pureconfig.{ ConfigObjectCursor, ConfigReader => PureConfigReader }

import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*

/**
 * Reads the `timeouts` block of a provider section, for the chat sections (`llm4s.providers.<name>`) and
 * the embedding sections (`llm4s.embeddings.<id>`) alike, so both accept exactly the same block.
 */
private[config] object ProviderTimeoutsReader:

  /** The keys a `timeouts` block accepts; anything else is a typo, and is reported as one. */
  private val TimeoutKeys: Set[String] = Set("request", "stream")

  /**
   * The `timeouts` block of a section: `request` and `stream`, each a duration such as `30s` or `2m`.
   *
   * An unknown key is an error rather than ignored, so `reqest = 2m` does not silently leave the default
   * in force. A value that is not a finite duration is an error naming its path. Whether a value is
   * positive is checked by the caller, which knows the section's full path.
   *
   * @param cursor the cursor on the section, which may or may not hold a `timeouts` key
   * @return `None` when the section has no block (or a null one)
   */
  def read(cursor: ConfigObjectCursor): PureConfigReader.Result[Option[ProviderTimeouts]] =
    val key = cursor.atKeyOrUndefined("timeouts")
    if key.isUndefined || key.isNull then Right(None)
    else
      key.asObjectCursor.flatMap { block =>
        val unknown = block.objValue.keySet().asScala.toList.sorted.filterNot(TimeoutKeys.contains)
        val unknownKeys = unknown.foldLeft[PureConfigReader.Result[Unit]](Right(())) { (acc, name) =>
          acc.flatMap(_ =>
            block
              .atKey(name)
              .flatMap(
                _.failed(
                  UserValidationFailed(
                    s"unknown key '$name' in 'timeouts': the keys are ${TimeoutKeys.toList.sorted.mkString(", ")}"
                  )
                )
              )
          )
        }
        def duration(name: String): PureConfigReader.Result[Option[FiniteDuration]] =
          val value = block.atKeyOrUndefined(name)
          if value.isUndefined || value.isNull then Right(None)
          else PureConfigReader[FiniteDuration].from(value).map(Some(_))
        for
          _       <- unknownKeys
          request <- duration("request")
          stream  <- duration("stream")
        yield Some(ProviderTimeouts(request, stream))
      }
