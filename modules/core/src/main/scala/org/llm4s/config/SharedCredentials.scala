package org.llm4s.config

import com.typesafe.config.ConfigUtil
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.auth.ApiKeyProvider
import org.llm4s.security.Secret
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.slf4j.LoggerFactory
import pureconfig.error.{ ConfigReaderFailures, ConvertFailure }
import pureconfig.{ ConfigReader => PureConfigReader, ConfigSource }

import java.nio.file.Path
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * The `llm4s.credentials` block: one shared API key per vendor, keyed by provider id.
 *
 * Credentials belong to a vendor; clients belong to a use. A chat section
 * (`llm4s.providers.<name>`), an embeddings block (`llm4s.embeddings.<id>`) and a reranker
 * (`llm4s.rerank.cohere`) are all clients, and each may set its own `apiKey`. One that does not
 * falls back to `llm4s.credentials.<id>.apiKey`, which the vendor's provider module binds to the
 * vendor's conventional environment variable in its own `reference.conf`:
 *
 * {{{
 * llm4s.credentials.openai.apiKey = ${?OPENAI_API_KEY}   # llm4s-openai's reference.conf
 * }}}
 *
 * This is why a chat section needs only `provider` and `model` once `OPENAI_API_KEY` is set,
 * even though chat config is keyed by an instance name no `reference.conf` can know.
 *
 * The block holds `apiKey`, or `apiKeyFile` - a file holding the key, which Vault Agent, the
 * Secrets Store CSI driver or External Secrets rewrites when it rotates the key (#2478) - and
 * nothing else: `baseUrl`, `endpoint`, `model` and `apiVersion` stay in the client's own section
 * and are never defaulted from here. A client sets its own `apiKeyFile` the same way.
 *
 * Each entry is read on its own, so an unreadable entry - `llm4s.credentials.openai = 42` -
 * fails only the clients that fall back to it.
 *
 * @param entries    the key, or the key file, for each provider id that has one, or the error
 *                   reading it. Ids are canonical (`ProviderId` spelling); blank values are absent.
 * @param blockError why `llm4s.credentials` itself could not be read - it is not an object -
 *                   reported by every lookup, and so only by clients that need a shared key.
 */
final private[llm4s] case class SharedCredentials(
  entries: Map[ProviderId, Result[SharedCredentials.Entry]],
  blockError: Option[ConfigurationError] = None
):

  // The values are API keys, and this travels inside `ProviderSections`, whose `toString` would
  // otherwise print them: only the ids are shown.
  override def toString: String =
    s"SharedCredentials(${entries.keys.map(_.asString).toSeq.sorted.map(id => s"$id -> ***").mkString(", ")})"

  /**
   * The shared key for `id`, if one is set.
   *
   * @param id the canonical provider id - `gemini`, not the alias `google`. Callers pass the id
   *           a section or block resolved to, so aliases need no handling here.
   * @return `Right(None)` when no key is set, `Left` when the entry cannot be read - or names a
   *         key file that is missing, empty or unreadable. A key file is read now, so its key is
   *         the one it holds at this call, and [[SharedCredentials.Resolved.file]] names it.
   */
  def apiKey(id: ProviderId): Result[Option[SharedCredentials.Resolved]] =
    blockError match
      case Some(error) => Left(error)
      case None =>
        entries.get(id) match
          case None => Right(None)
          case Some(entry) =>
            entry.flatMap {
              case SharedCredentials.Entry.Key(key) =>
                Right(Some(SharedCredentials.Resolved(key, SharedCredentials.apiKeyPath(id))))
              case SharedCredentials.Entry.File(file) =>
                SharedCredentials.fromFile(file, SharedCredentials.apiKeyFilePath(id)).map(Some(_))
            }

  /**
   * A client's key: its own when it sets one (`apiKey`, or `apiKeyFile`), otherwise the vendor's
   * shared key.
   *
   * @param own     the client's own `apiKey`, as read; blank counts as unset
   * @param ownPath where the client's own key lives, e.g. `llm4s.providers.openai-main.apiKey`; its
   *                `apiKeyFile` is the sibling key, e.g. `llm4s.embeddings.openai.apiKeyFile`
   * @param id      the canonical id of the provider the client resolved to
   * @param ownFile the client's own `apiKeyFile`, made absolute; set with `own` it is an error
   * @return the key and the path it came from, `None` when neither is set, or `Left` when the
   *         client sets both `apiKey` and `apiKeyFile`, a key file cannot be read, or the shared
   *         entry the client would fall back to cannot be read
   */
  def resolve(
    own: Option[String],
    ownPath: String,
    id: ProviderId,
    ownFile: Option[Path] = None
  ): Result[Option[SharedCredentials.Resolved]] =
    val ownFilePath = SharedCredentials.siblingFilePath(ownPath)
    (own.map(_.trim).filter(_.nonEmpty), ownFile) match
      case (Some(_), Some(_)) =>
        Left(
          ConfigurationError(
            s"${ownPath.stripSuffix(".apiKey")} sets both apiKey and apiKeyFile; a client reads its key one way - " +
              "remove one",
            List("apiKeyFile")
          )
        )
      case (Some(key), None)  => Right(Some(SharedCredentials.Resolved(Secret(key), ownPath)))
      case (None, Some(file)) => SharedCredentials.fromFile(file, ownFilePath).map(Some(_))
      case (None, None)       => apiKey(id)

private[llm4s] object SharedCredentials:

  private val logger = LoggerFactory.getLogger(getClass)

  /** One vendor's entry under `llm4s.credentials`: a key, or a file holding it. */
  enum Entry:
    /** `apiKey`: the key itself. */
    case Key(value: Secret)

    /** `apiKeyFile`: the file holding the key, made absolute; read when a client falls back to it. */
    case File(path: Path)

  /**
   * A resolved key and the config path it was read from.
   *
   * `toString` redacts the value: this is passed around to be logged, and only `path` may be.
   *
   * @param file the file the key was read from, when it came from an `apiKeyFile`: a client whose
   *             config takes an `ApiKeyProvider` is given `ApiKeyProvider.file` over it, so it reads
   *             the rotated key per request
   */
  final case class Resolved(value: Secret, path: String, file: Option[Path] = None):
    override def toString: String = s"Resolved(***,$path)"

  /**
   * The key `file` holds now. A missing, unreadable, empty or oversized file is a `ConfigurationError` naming
   * `configPath` and the file, never the content; a file readable by its group or others logs a WARN.
   */
  private[config] def fromFile(file: Path, configPath: String): Result[Resolved] =
    ApiKeyProvider
      .file(file)
      .current()
      .left
      .map(error => ConfigurationError(s"$configPath: ${error.message}", List("apiKeyFile")))
      .map(key => Resolved(Secret(key.asKey), s"file $file ($configPath)", Some(file)))

  /** `path`'s sibling `apiKeyFile`: `llm4s.embeddings.openai.apiKey` becomes `llm4s.embeddings.openai.apiKeyFile`. */
  private def siblingFilePath(path: String): String =
    if path.endsWith(".apiKey") then path + "File" else s"$path.apiKeyFile"

  /**
   * A configured `apiKeyFile` value as an absolute path, or an error naming `configPath`; blank is unset.
   * Relative paths resolve against the working directory, as a chat section's `apiKeyFile` does.
   */
  private[llm4s] def keyFilePath(raw: Option[String], configPath: String): Result[Option[Path]] =
    raw.map(_.trim).filter(_.nonEmpty) match
      case None => Right(None)
      case Some(file) =>
        Try(Path.of(file).toAbsolutePath.normalize).toEither.left
          .map(e => ConfigurationError(s"$configPath is not a valid path on this platform: ${e.getMessage}"))
          .map(Some(_))

  /**
   * Logs, at INFO, where a client's key came from - the path, never the value.
   *
   * Two sections of the same vendor that bill different accounts are told apart by this line:
   * `llm4s.providers.openai-batch: API key from llm4s.credentials.openai.apiKey` says that
   * section inherited the shared key rather than using one of its own.
   *
   * @param client the client's own config path, e.g. `llm4s.providers.openai-main`
   */
  def logSource(client: String, resolved: Resolved): Unit =
    logger.info(s"$client: API key from ${resolved.path}")

  /** No shared keys at all: what a config without an `llm4s.credentials` block reads as. */
  val empty: SharedCredentials = SharedCredentials(Map.empty)

  /**
   * The config path of a vendor's shared key, e.g. `llm4s.credentials.openai.apiKey`.
   *
   * Joined rather than interpolated, as `EmbeddingConfigSpec.sectionPath` is, so an id holding
   * a dot is quoted instead of naming a nested path.
   */
  def apiKeyPath(id: ProviderId): String =
    ConfigUtil.joinPath(List("llm4s", "credentials", id.asString, "apiKey").asJava)

  /** The config path of a vendor's shared key file, e.g. `llm4s.credentials.openai.apiKeyFile`. */
  def apiKeyFilePath(id: ProviderId): String =
    ConfigUtil.joinPath(List("llm4s", "credentials", id.asString, "apiKeyFile").asJava)

  /**
   * How to supply a missing key, shared by every client's "missing apiKey" error:
   * `set OPENAI_API_KEY, or set apiKey under llm4s.providers.openai-main in application.conf`.
   *
   * @param env           the variables the vendor's module binds to [[apiKeyPath]], highest
   *                      precedence first (`ProviderConfigSpec.apiKeyEnv`,
   *                      `EmbeddingConfigSpec.apiKeyEnv`). With none, the shared path itself is
   *                      named, since no variable reaches it.
   * @param id            the canonical id of the vendor
   * @param clientSection the client's own section, e.g. `llm4s.providers.openai-main`
   */
  def missingKeyHint(env: Seq[String], id: ProviderId, clientSection: String): String =
    val shared = env match
      case Seq()    => s"set ${apiKeyPath(id)}"
      case Seq(one) => s"set $one"
      case many     => s"set ${many.init.mkString(", ")} or ${many.last}"
    s"$shared, or set apiKey under $clientSection in application.conf"

  private val BlockPath = "llm4s.credentials"

  /**
   * Reads `llm4s.credentials` from `source`.
   *
   * An absent block is empty rather than an error: most configs never write one, and a module
   * whose variable is unset leaves its entry empty too.
   *
   * Never fails: a block that is not an object is kept as [[SharedCredentials.blockError]], so
   * it fails the clients that fall back to it and no others.
   */
  def read(source: ConfigSource): SharedCredentials =
    val at = source.at(BlockPath)
    if at.value().isLeft then empty
    else
      at.load[SharedCredentials](using reader) match
        case Right(credentials) => credentials
        case Left(failures) =>
          SharedCredentials(Map.empty, Some(ConfigurationError(s"Failed to read $BlockPath: ${describe(failures)}")))

  private val reader: PureConfigReader[SharedCredentials] =
    PureConfigReader.fromCursor { cursor =>
      cursor.asObjectCursor.map { objCursor =>
        val entries =
          objCursor.objValue.keySet().asScala.toList.flatMap { key =>
            val id        = ProviderId(key)
            val entryPath = ConfigUtil.joinPath(List("llm4s", "credentials", key).asJava)
            def optional(entryCursor: pureconfig.ConfigObjectCursor, name: String) =
              val cursor = entryCursor.atKeyOrUndefined(name)
              if cursor.isUndefined || cursor.isNull then Right(None)
              else PureConfigReader[String].from(cursor).map(Some(_))
            val read: Either[ConfigReaderFailures, (Option[String], Option[String])] =
              for
                entryCursor <- objCursor.atKey(key).flatMap(_.asObjectCursor)
                apiKey      <- optional(entryCursor, "apiKey")
                apiKeyFile  <- optional(entryCursor, "apiKeyFile")
              yield (apiKey.map(_.trim).filter(_.nonEmpty), apiKeyFile)

            val entry: Result[Option[Entry]] =
              read.left
                .map(failures => ConfigurationError(s"Failed to read $entryPath: ${describe(failures)}"))
                .flatMap { (apiKey, apiKeyFile) =>
                  keyFilePath(apiKeyFile, ConfigUtil.joinPath(List("llm4s", "credentials", key, "apiKeyFile").asJava))
                    .flatMap {
                      // `apiKey` is usually bound to the vendor's variable by its module's reference.conf, so the
                      // message says where a key the user never wrote may come from.
                      case Some(_) if apiKey.isDefined =>
                        Left(
                          ConfigurationError(
                            s"$entryPath sets both apiKey and apiKeyFile (apiKey may be bound to the vendor's " +
                              "environment variable by its module's reference.conf); remove one",
                            List("apiKeyFile")
                          )
                        )
                      case Some(file) => Right(Some(Entry.File(file)))
                      case None       => Right(apiKey.map(k => Entry.Key(Secret(k))))
                    }
                }

            entry match
              case Right(None)        => Nil
              case Right(Some(value)) => List(id -> Right(value))
              case Left(error)        => List(id -> Left(error))
          }
        SharedCredentials(entries.toMap)
      }
    }

  private def describe(failures: ConfigReaderFailures): String =
    failures.toList
      .map {
        case failure: ConvertFailure if failure.path.nonEmpty => s"${failure.path}: ${failure.description}"
        case failure                                          => failure.description
      }
      .mkString("; ")
