package org.llm4s.config

import com.typesafe.config.{ ConfigObject, ConfigValueType }
import org.llm4s.error.{ ConfigurationError, LLMError }
import org.llm4s.http.{ HttpNetwork, HttpNetworkSettings, HttpProxy, HttpTls }
import org.llm4s.types.Result
import pureconfig.ConfigSource

import java.nio.file.Path
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * Reads the `llm4s.http` block, and a named provider section's `http` override, into [[HttpNetworkSettings]].
 *
 * {{{
 * llm4s.http {
 *   proxy { url = "http://proxy.corp:3128", username = "...", password = "...", nonProxyHosts = ["localhost"] }
 *   tls   { trustStore = "/etc/pki/corp-ca.p12", trustStorePassword = "...", keyStore = "...", keyStorePassword = "..." }
 * }
 * }}}
 *
 * An unknown key is an error naming it, so a typo does not silently leave traffic unproxied. A blank or
 * absent `proxy.url` means no proxy. `nonProxyHosts` is a list or a comma-separated string (the form
 * `NO_PROXY` takes). The TLS stores are opened here, so a missing file or a wrong password fails the load
 * rather than the first request. No error repeats a password.
 */
private[config] object HttpNetworkReader:

  val GlobalPath: String = "llm4s.http"

  private val BlockKeys: Set[String] = Set("proxy", "tls")
  private val ProxyKeys: Set[String] = Set("url", "username", "password", "nonProxyHosts")
  private val TlsKeys: Set[String]   = Set("trustStore", "trustStorePassword", "keyStore", "keyStorePassword")

  /** The `llm4s.http` block of `source`, as an object; `None` when it is absent. */
  def globalBlock(source: ConfigSource): Result[Option[ConfigObject]] =
    source
      .at(GlobalPath)
      .cursor()
      .toOption
      .filterNot(c => c.isUndefined || c.isNull) match
      case None => Right(None)
      case Some(cursor) =>
        cursor.asObjectCursor
          .map(c => Some(c.objValue))
          .left
          .map(_ => ConfigurationError(s"$GlobalPath must be an object (proxy { ... }, tls { ... })", List(GlobalPath)))

  /** The settings `source`'s `llm4s.http` block gives: [[HttpNetworkSettings.default]] when there is none. */
  def load(source: ConfigSource): Result[HttpNetworkSettings] =
    globalBlock(source).flatMap(_.fold(Right(HttpNetworkSettings.default))(read(_, GlobalPath)))

  /**
   * The settings for one named section: its own `http` block's keys over `llm4s.http`'s, one by one.
   *
   * @param section the section's `http` block, if it has one
   * @param global  the `llm4s.http` block, if there is one
   * @param name    the section's name, for the error path
   */
  def forSection(
    section: Option[ConfigObject],
    global: Option[ConfigObject],
    name: String
  ): Result[HttpNetworkSettings] =
    (section, global) match
      case (None, None)    => Right(HttpNetworkSettings.default)
      case (None, Some(g)) => read(g, GlobalPath)
      case (Some(s), g)    => read(g.fold(s)(s.withFallback(_)), s"llm4s.providers.$name.http")

  /**
   * Reads one block. Every error names its key under `path`.
   *
   * @param block the `http` block
   * @param path  where the block is, such as `llm4s.http`
   */
  def read(block: ConfigObject, path: String): Result[HttpNetworkSettings] =
    val config                                       = block.toConfig
    def fail(key: String, message: String): LLMError = ConfigurationError(s"$path.$key: $message", List(s"$path.$key"))
    def unknown(prefix: String, keys: Iterable[String], known: Set[String]): Result[Unit] =
      keys.toList.sorted.find(k => !known.contains(k)) match
        case Some(k) =>
          Left(fail(prefix + k, s"unknown key; the keys are ${known.toList.sorted.mkString(", ")}"))
        case None => Right(())
    def objectAt(key: String): Result[Option[ConfigObject]] =
      Option(block.get(key)) match
        case None                                           => Right(None)
        case Some(v) if v.valueType == ConfigValueType.NULL => Right(None)
        case Some(o: ConfigObject)                          => Right(Some(o))
        case Some(_)                                        => Left(fail(key, "must be an object"))
    def string(key: String): Result[Option[String]] =
      if !config.hasPath(key) then Right(None)
      else
        config.getValue(key).valueType match
          case ConfigValueType.STRING | ConfigValueType.NUMBER | ConfigValueType.BOOLEAN =>
            Right(Some(config.getValue(key).unwrapped.toString).map(_.trim).filter(_.nonEmpty))
          case _ => Left(fail(key, "must be a string"))
    def hosts(key: String): Result[Option[Seq[String]]] =
      if !config.hasPath(key) then Right(None)
      else
        config.getValue(key).valueType match
          case ConfigValueType.LIST =>
            Try(config.getStringList(key).asScala.toSeq).toEither.left
              .map(_ => fail(key, "must be a list of host names"))
              .map(list => Some(list.map(_.trim).filter(_.nonEmpty)))
          case ConfigValueType.STRING =>
            Right(Some(config.getString(key).split(",").toSeq.map(_.trim).filter(_.nonEmpty)))
          case _ => Left(fail(key, "must be a list of host names, or a comma-separated string"))

    for
      _        <- unknown("", block.keySet.asScala, BlockKeys)
      proxyObj <- objectAt("proxy")
      tlsObj   <- objectAt("tls")
      _        <- proxyObj.fold[Result[Unit]](Right(()))(o => unknown("proxy.", o.keySet.asScala, ProxyKeys))
      _        <- tlsObj.fold[Result[Unit]](Right(()))(o => unknown("tls.", o.keySet.asScala, TlsKeys))
      url      <- string("proxy.url")
      username <- string("proxy.username")
      password <- string("proxy.password")
      bypass   <- hosts("proxy.nonProxyHosts")
      proxy <- url.fold[Result[Option[HttpProxy]]](Right(None)) { u =>
        HttpProxy.fromUrl(u).left.map(e => fail("proxy.url", e.message)).map { parsed =>
          Some(
            parsed
              .withUsername(username.orElse(parsed.username))
              .withPassword(password.orElse(parsed.password))
              .withNonProxyHosts(bypass.getOrElse(parsed.nonProxyHosts))
          )
        }
      }
      trustStore         <- string("tls.trustStore")
      trustStorePassword <- string("tls.trustStorePassword")
      keyStore           <- string("tls.keyStore")
      keyStorePassword   <- string("tls.keyStorePassword")
      tls <- pathOf(trustStore, "tls.trustStore", fail).flatMap(ts =>
        pathOf(keyStore, "tls.keyStore", fail).map(ks => HttpTls(ts, trustStorePassword, ks, keyStorePassword))
      )
      // Opened now, so a missing store or a wrong password fails the load, not the first request.
      _ <- HttpNetwork.tlsMaterial(tls).left.map {
        case e: ConfigurationError => ConfigurationError(s"$path.${e.message}", e.missingKeys.map(k => s"$path.$k"))
        case other                 => other
      }
    yield HttpNetworkSettings(proxy, tls)

  private def pathOf(
    value: Option[String],
    key: String,
    fail: (String, String) => LLMError
  ): Result[Option[Path]] =
    value.fold[Result[Option[Path]]](Right(None))(v =>
      Try(Path.of(v)).toEither.left.map(_ => fail(key, s"$v is not a valid path")).map(Some(_))
    )
