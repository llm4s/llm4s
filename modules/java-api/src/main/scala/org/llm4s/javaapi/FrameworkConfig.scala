// scalafix:off DisableSyntax.NoPureConfigDefault
// A framework integration is the application edge: it is where the framework's properties meet llm4s's own
// configuration, so it lays them over `ConfigSource.default`, as `Llm4sConfig` does for a HOCON file.
package org.llm4s.javaapi

import com.typesafe.config.ConfigValueFactory
import org.llm4s.config.Llm4sConfig
import org.llm4s.error.{ ConfigurationError, LLMError }
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.metrics.MetricsCollector
import pureconfig.{ ConfigObjectSource, ConfigSource }

import java.util.{ LinkedHashMap => JLinkedHashMap, List => JList, Map => JMap, Optional }
import scala.jdk.CollectionConverters._

/**
 * Not part of the API: what the Quarkus extension (`modules/quarkus`, built by Maven, in Java) needs from llm4s to turn
 * the application's `llm4s.*` properties into clients (#2280). The properties are spelt as in the Spring Boot starter:
 *
 * {{{
 * llm4s.providers.provider=main                    # the default section
 * llm4s.providers.main.provider=ollama
 * llm4s.providers.main.model=llama3.2
 * llm4s.providers.main.base-url=http://localhost:11434
 * llm4s.credentials.openai.api-key=...             # a vendor's shared key
 * llm4s.embeddings.model=ollama/nomic-embed-text   # the embedding model, provider/model
 * llm4s.embeddings.ollama.base-url=...             # that provider's embeddings block
 * }}}
 *
 * Within a section `kebab-case` and `snake_case` names are read as HOCON's `camelCase` (`api-key` is `apiKey`), except
 * under `headers`, whose names stay as written. Every other `llm4s.*` key (`llm4s.health.*`, ...) is the
 * integration's own and is ignored here. The properties are laid over llm4s's defaults, so a vendor's conventional
 * environment variable still supplies a key a section does not set; a `llm4s.providers` block of an `application.conf`
 * is not read.
 */
private[llm4s] object FrameworkConfig {

  private val Sectioned = Set("providers", "credentials", "embeddings")

  /** The sections of `llm4s.providers`, in alphabetical order. */
  def sections(properties: JMap[String, String]): JList[String] =
    keys(properties).collect { case "llm4s" :: "providers" :: section :: _ :: _ => section }.distinct.asJava

  /**
   * The default section: the one `llm4s.providers.provider` names, else the only section there is; empty when there
   * are several and none is named.
   */
  def defaultSection(properties: JMap[String, String]): Optional[String] = {
    val named = Option(properties.get("llm4s.providers.provider")).map(_.trim).filter(_.nonEmpty)
    val all   = sections(properties).asScala.toList
    Optional.ofNullable(named.orElse(if (all.size == 1) all.headOption else None).orNull)
  }

  /** The embedding model `llm4s.embeddings.model` names, `provider/model`, if one is set. */
  def embeddingModel(properties: JMap[String, String]): Optional[String] =
    Optional.ofNullable(Option(properties.get("llm4s.embeddings.model")).map(_.trim).filter(_.nonEmpty).orNull)

  /**
   * What is wrong with the properties' structure, each message naming the property to fix: a section with no
   * `provider` or `model`, a provider (or embedding provider) not on the classpath, a default that names no section.
   * Keys and other values are not checked here: they may be supplied only when the application runs.
   */
  def problems(properties: JMap[String, String]): JList[String] = {
    val registry           = ProviderRegistry.default
    def value(key: String) = Option(properties.get(key)).map(_.trim).filter(_.nonEmpty)
    val all                = sections(properties).asScala.toList

    val sectionProblems = all.flatMap { section =>
      val prefix = s"llm4s.providers.$section"
      val provider = value(s"$prefix.provider") match {
        case None =>
          List(s"$prefix.provider is not set: name the provider of section '$section' (${known(registry.ids)})")
        case Some(raw) =>
          registry.resolve(registry.canonicalId(raw), Some(s"$prefix.provider")).left.map(_.message).left.toSeq.toList
      }
      val model =
        value(s"$prefix.model").fold(List(s"$prefix.model is not set: name the model of section '$section'"))(_ => Nil)
      provider ++ model
    }

    val defaultProblem = value("llm4s.providers.provider").toList.collect {
      case name if !all.contains(name) =>
        s"llm4s.providers.provider names section '$name', which has no properties " +
          s"(${if (all.isEmpty) "no section is configured" else s"sections: ${all.mkString(", ")}"})"
    }

    val embeddingProblem = value("llm4s.embeddings.model").toList.flatMap { spec =>
      spec.split("/", 2).toList match {
        case provider :: model :: Nil if provider.trim.nonEmpty && model.trim.nonEmpty =>
          registry
            .resolveEmbedding(registry.canonicalEmbeddingId(provider.trim), Some("llm4s.embeddings.model"))
            .left
            .map(_.message)
            .left
            .toSeq
            .toList
        case _ => List(s"llm4s.embeddings.model is '$spec': expected provider/model, such as ollama/nomic-embed-text")
      }
    }

    (sectionProblems ++ defaultProblem ++ embeddingProblem).asJava
  }

  /** The client of section `section`, which reports every call to `metrics` (null records nothing). */
  def client(properties: JMap[String, String], section: String, metrics: MetricsCollector): LlmResult[JLlmClient] =
    source(properties, Some(section))
      .flatMap(Llm4sConfig.providerFrom(_)(using ProviderRegistry.default))
      .fold(error => LlmResult.failure[JLlmClient](error), FacadeInternals.createClient(_, metrics))

  /** The client of the embedding model `model`, `provider/model`, with that provider's `llm4s.embeddings` block. */
  def embeddingClient(properties: JMap[String, String], model: String): LlmResult[JEmbeddingClient] =
    FacadeInternals.result(
      FacadeInternals.embeddingClient(
        source(properties, None).flatMap(Llm4sConfig.embeddingsFor(_, model)(using ProviderRegistry.default))
      )
    )

  /** `api-key` and `api_key` are `apiKey`; a name with neither separator is left as written. */
  def camel(segment: String): String =
    segment.split("[-_]").toList.filter(_.nonEmpty) match {
      case head :: tail if tail.nonEmpty => head + tail.map(part => part.take(1).toUpperCase + part.drop(1)).mkString
      case _                             => segment
    }

  /** The HOCON path a property stands for, or None for a key that is not llm4s's own. */
  def hoconPath(key: String): Option[List[String]] =
    key.split("\\.").toList match {
      case "llm4s" :: "providers" :: "provider" :: Nil => Some(List("llm4s", "providers", "provider"))
      case "llm4s" :: "embeddings" :: "model" :: Nil   => Some(List("llm4s", "embeddings", "model"))
      case "llm4s" :: block :: name :: first :: rest if Sectioned.contains(block) =>
        val head = camel(first)
        Some(List("llm4s", block, name, head) ++ (if (head == "headers") rest else rest.map(camel)))
      case "llm4s" :: "http" :: first :: rest => Some(List("llm4s", "http", camel(first)) ++ rest.map(camel))
      case _                                  => None
    }

  private def keys(properties: JMap[String, String]): List[List[String]] =
    properties.keySet.asScala.toList.sorted.flatMap(key => Option(key).flatMap(hoconPath))

  private def known(ids: Seq[String]): String = ids.mkString("registered providers: ", ", ", "")

  private def source(properties: JMap[String, String], section: Option[String]): Either[LLMError, ConfigSource] = {
    val root = new JLinkedHashMap[String, AnyRef]()
    val entries = properties.asScala.toList.sortBy(_._1).flatMap { case (key, value) =>
      Option(key).flatMap(hoconPath).filter(_ => value != null).map(_ -> value)
    } ++ section.map(name => List("llm4s", "providers", "provider") -> name)

    val conflicts = entries.flatMap { case (path, value) => put(root, path, value) }
    conflicts.headOption match {
      case Some(key) =>
        Left(ConfigurationError(s"$key is set both as a value and as a section of values", List(key)))
      case None =>
        withoutProviders(ConfigSource.default)
          .map(ConfigSource.fromConfig(ConfigValueFactory.fromMap(root).toConfig).withFallback(_))
    }
  }

  /**
   * llm4s's defaults without `llm4s.providers`: the sections are the framework's properties alone. Typesafe Config also
   * reads a classpath `application.properties`, the framework's own file, whose keys it would take as written
   * (`base-url`, not `baseUrl`).
   */
  private[javaapi] def withoutProviders(defaults: ConfigObjectSource): Either[LLMError, ConfigObjectSource] =
    defaults
      .config()
      .left
      .map(failures => ConfigurationError(s"Cannot read llm4s's configuration: ${failures.prettyPrint()}"): LLMError)
      .map(config => ConfigSource.fromConfig(config.withoutPath("llm4s.providers")))

  /** Puts `value` at `path`; the key, when the path crosses a value or ends on a section. */
  private def put(root: JMap[String, AnyRef], path: List[String], value: String): Option[String] = {
    val parent = path.init.foldLeft[Option[JMap[String, AnyRef]]](Some(root)) { (map, name) =>
      map.flatMap { m =>
        m.get(name) match {
          case null =>
            val child = new JLinkedHashMap[String, AnyRef]()
            m.put(name, child)
            Some(child)
          case child: JMap[_, _] => Some(child.asInstanceOf[JMap[String, AnyRef]])
          case _                 => None
        }
      }
    }
    parent match {
      case Some(map) if !map.get(path.last).isInstanceOf[JMap[_, _]] =>
        map.put(path.last, value)
        None
      case _ => Some(path.mkString("."))
    }
  }
}
