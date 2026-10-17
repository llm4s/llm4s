// scalafix:off DisableSyntax.NoPureConfigDefault
// The starter is the application edge for a Spring user: it is where Spring's properties meet llm4s's own
// configuration, so it layers them over `ConfigSource.default`, as `Llm4sConfig` does for a HOCON file.
package org.llm4s.spring

import com.typesafe.config.ConfigValueFactory
import org.llm4s.config.Llm4sConfig
import org.llm4s.error.{ ConfigurationError, LLMError }
import org.llm4s.javaapi.LlmResult
import org.llm4s.llmconnect.config.ProviderConfig
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.springframework.core.env.{ ConfigurableEnvironment, EnumerablePropertySource }
import pureconfig.ConfigSource

import java.util.{ LinkedHashMap => JLinkedHashMap, Map => JMap }
import scala.jdk.CollectionConverters._
import scala.util.Try

/**
 * Reads the `llm4s.providers.*` and `llm4s.credentials.*` properties of a Spring [[Environment]] into
 * llm4s's own provider configuration, so every provider on the classpath works with no per-provider Spring
 * code (#1467).
 *
 * The properties mirror the HOCON blocks of the same names, in Spring's usual spelling:
 *
 * {{{
 * llm4s.providers.provider=gemini-main          # the default section, as llm4s.providers.provider in HOCON
 * llm4s.providers.gemini-main.provider=gemini
 * llm4s.providers.gemini-main.model=gemini-2.0-flash
 * llm4s.providers.gemini-main.api-key=${GOOGLE_API_KEY}
 * llm4s.providers.azure-main.provider=azure
 * llm4s.providers.azure-main.endpoint=https://my-resource.openai.azure.com
 * llm4s.credentials.openai.api-key=${OPENAI_API_KEY}   # a vendor's shared key, as in HOCON
 * }}}
 *
 * Within a section, `kebab-case` and `snake_case` names are read as the `camelCase` names HOCON uses
 * (`api-key` is `apiKey`), except under `headers`, whose names are HTTP header names and stay as written.
 * The properties are laid over llm4s's defaults (`application.conf`, every module's `reference.conf`), so a
 * vendor's conventional environment variable still supplies a key a section does not set. The section is then
 * resolved by the same loader a HOCON file goes through, which validates only that section and names the
 * registered providers when the provider is unknown.
 */
private[spring] object SpringProviderSource {

  final val ProvidersPrefix   = "llm4s.providers"
  final val CredentialsPrefix = "llm4s.credentials"
  private val DefaultPointer  = List("llm4s", "providers", "provider")

  /** The provider llm4s resolved from the properties, and the secret values to keep out of reports: those the properties held and the resolved config's own. */
  final case class Resolved(config: ProviderConfig, secrets: Seq[String])

  /** True when the environment holds at least one `llm4s.providers.*` property, which selects this path. */
  def usesProvidersBlock(env: ConfigurableEnvironment): Boolean =
    propertyNames(env).exists(_.startsWith(ProvidersPrefix + "."))

  /**
   * Resolves the default section of `llm4s.providers`. When the properties name no default but the flat
   * `llm4s.provider` names a section, that section is the default.
   */
  def resolve(env: ConfigurableEnvironment, flatProvider: String): LlmResult[Resolved] = {
    val outcome: Either[LLMError, Resolved] = for {
      entries <- collect(env)
      prepared = withDefaultPointer(entries, flatProvider)
      source <- toSource(prepared)
      config <- Llm4sConfig.providerFrom(source)(using ProviderRegistry.default)
    } yield Resolved(config, (secrets(prepared) ++ configSecrets(config)).distinct)
    outcome.fold(error => LlmResult.failure[Resolved](error), resolved => LlmResult.success(resolved))
  }

  /** `api-key` and `api_key` are `apiKey`; a name with neither separator is left as written. */
  def camel(segment: String): String =
    segment.split("[-_]").toList.filter(_.nonEmpty) match {
      case head :: tail if tail.nonEmpty => head + tail.map(part => part.take(1).toUpperCase + part.drop(1)).mkString
      case _                             => segment
    }

  /** The HOCON path a property key stands for, or what is wrong with the key. */
  def hoconPath(key: String): Either[LLMError, List[String]] =
    key.split("\\.").toList match {
      case "llm4s" :: "providers" :: "provider" :: Nil => Right(DefaultPointer)
      case "llm4s" :: "providers" :: section :: first :: rest =>
        val name = camel(first)
        Right(List("llm4s", "providers", section, name) ++ (if (name == "headers") rest else rest.map(camel)))
      case "llm4s" :: "credentials" :: vendor :: first :: rest =>
        Right(List("llm4s", "credentials", vendor, camel(first)) ++ rest.map(camel))
      case _ =>
        Left(
          ConfigurationError(
            s"$key is not a provider property: expected $ProvidersPrefix.<section>.<name>, " +
              s"$ProvidersPrefix.provider or $CredentialsPrefix.<vendor>.<name>",
            List(key)
          )
        )
    }

  private def propertyNames(env: ConfigurableEnvironment): Vector[String] =
    env.getPropertySources.asScala.toVector
      .flatMap {
        case source: EnumerablePropertySource[_] => source.getPropertyNames.toVector
        case _                                   => Vector.empty
      }
      .filter(name => name.startsWith(ProvidersPrefix + ".") || name.startsWith(CredentialsPrefix + "."))
      .distinct

  /**
   * Every provider property with its placeholders resolved; the first one that cannot be is reported.
   *
   * The names come highest-priority source first, so when two sources spell one setting differently
   * (`apiKey` in `application.yml`, `api-key` on the command line) the first spelling seen is the one
   * Spring would use, and a later spelling of the same path is skipped rather than allowed to overwrite it.
   */
  private def collect(env: ConfigurableEnvironment): Either[LLMError, Vector[(List[String], String)]] =
    propertyNames(env)
      .foldLeft[Either[LLMError, (Vector[(List[String], String)], Set[List[String]])]](
        Right((Vector.empty, Set.empty))
      ) { (acc, key) =>
        acc.flatMap { case (soFar, seen) =>
          hoconPath(key).flatMap { path =>
            if (seen.contains(path)) Right((soFar, seen))
            else
              Try(Option(env.getProperty(key))).toEither.left
                .map { cause =>
                  ConfigurationError(s"Cannot resolve the value of $key: ${cause.getMessage}", List(key)): LLMError
                }
                .map(value => value.fold((soFar, seen))(v => (soFar :+ (path -> v), seen + path)))
          }
        }
      }
      .map(_._1)

  private def withDefaultPointer(
    entries: Vector[(List[String], String)],
    flatProvider: String
  ): Vector[(List[String], String)] = {
    val flat  = Option(flatProvider).map(_.trim).filter(_.nonEmpty)
    val named = entries.exists(_._1 == DefaultPointer)
    flat match {
      // Only a section of llm4s.providers counts: llm4s.credentials.<vendor> is not a section to select.
      case Some(name) if !named && entries.exists { case (path, _) => path.slice(1, 3) == List("providers", name) } =>
        entries :+ (DefaultPointer -> name)
      case _ => entries
    }
  }

  private def toSource(entries: Vector[(List[String], String)]): Either[LLMError, ConfigSource] =
    nest(entries).map { tree =>
      ConfigSource.fromConfig(ConfigValueFactory.fromMap(tree).toConfig).withFallback(ConfigSource.default)
    }

  /** The properties as nested maps; a name that is both a value and a section is reported, in either order. */
  private[spring] def nest(entries: Vector[(List[String], String)]): Either[LLMError, JMap[String, AnyRef]] = {
    val root = new JLinkedHashMap[String, AnyRef]()

    def conflict(path: List[String]): Either[LLMError, Nothing] = {
      val key = path.mkString(".")
      Left(ConfigurationError(s"$key is set both as a value and as a section of values", List(key)))
    }

    // A path always has the four segments hoconPath builds, so init and last are safe.
    def put(path: List[String], value: String): Either[LLMError, Unit] =
      path.init
        .foldLeft[Either[LLMError, JMap[String, AnyRef]]](Right(root)) { (parent, name) =>
          parent.flatMap { map =>
            map.get(name) match {
              case null =>
                val child = new JLinkedHashMap[String, AnyRef]()
                map.put(name, child)
                Right(child)
              case child: JMap[_, _] => Right(child.asInstanceOf[JMap[String, AnyRef]])
              case _                 => conflict(path)
            }
          }
        }
        .flatMap { map =>
          map.get(path.last) match {
            case _: JMap[_, _] => conflict(path)
            case _ =>
              map.put(path.last, value)
              Right(())
          }
        }

    entries
      .foldLeft[Either[LLMError, Unit]](Right(())) { case (acc, (path, value)) => acc.flatMap(_ => put(path, value)) }
      .map(_ => root)
  }

  /** The values of properties that hold a credential: used to keep them out of anything reported. */
  private def secrets(entries: Vector[(List[String], String)]): Seq[String] =
    entries.collect {
      case (path, value) if value.trim.nonEmpty && path.lastOption.exists(looksSecret) => value.trim
    }.distinct

  /**
   * The credentials of the resolved config itself, wherever they came from: a key the properties do not
   * hold (one from `application.conf`, a `reference.conf` default or a vendor's environment variable) must be
   * kept out of reports too.
   *
   * A config is read as the case class it is in practice, and so is every case class inside it (#1820): a text
   * field is a credential when its name looks like one (`apiKey`, `secretAccessKey`, `sessionToken`, ...), and
   * that reaches through `Option`, `Seq` and `Map` values, so Bedrock's `credentials: Option[BedrockCredentials]`
   * yields its access key id, secret key and session token. Every value of a `headers` map is a credential,
   * whatever the header is called, and so is the token after an auth scheme (`Bearer`, `Basic`, ...) in one. A
   * nested case class is judged by its own field names, so a URL or a file path beside a credential is not one.
   * A value that is not a case class, or whose reading throws, yields none: this never throws.
   */
  private[spring] def configSecrets(config: ProviderConfig): Seq[String] =
    credentialsIn(config, secret = false, depth = 0).map(_.trim).filter(_.nonEmpty).distinct

  // Deeper than any real config; stops a value that refers to itself.
  private val MaxDepth = 16

  private val SchemeToken = """(?i)^\s*(?:bearer|basic|token|apikey|api-key|digest)\s+(\S+)\s*$""".r

  /** The credential text inside `value`; `secret` says whether its text counts as one already. */
  private def credentialsIn(value: Any, secret: Boolean, depth: Int): Vector[String] =
    if (depth > MaxDepth) Vector.empty
    else
      value match {
        case text: String =>
          if (secret) text +: SchemeToken.findFirstMatchIn(text).map(_.group(1)).toVector else Vector.empty
        case Some(inner) => credentialsIn(inner, secret, depth + 1)
        case map: scala.collection.Map[?, ?] =>
          readSafely(map.toVector.flatMap { case (name, inner) =>
            credentialsIn(inner, secret || looksSecret(String.valueOf(name)), depth + 1)
          })
        case items: Iterable[?] => readSafely(items.toVector.flatMap(credentialsIn(_, secret, depth + 1)))
        case product: Product =>
          readSafely(product.productElementNames.zip(product.productIterator).toVector.flatMap { case (name, inner) =>
            credentialsIn(inner, name == "headers" || looksSecret(name), depth + 1)
          })
        case _ => Vector.empty
      }

  private def readSafely(read: => Vector[String]): Vector[String] = Try(read).getOrElse(Vector.empty)

  /** A name that holds a credential: a key, secret, token, password or credential, but not the URL of one. */
  private def looksSecret(name: String): Boolean = {
    val lower = name.toLowerCase
    Seq("key", "secret", "token", "password", "authorization", "credential").exists(lower.contains) &&
    !lower.endsWith("url")
  }
}
