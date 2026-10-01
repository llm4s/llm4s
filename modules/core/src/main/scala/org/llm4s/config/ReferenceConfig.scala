package org.llm4s.config

// Assembling a Config from parts is this object's whole job.
// scalafix:off DisableSyntax.NoConfigFactory
import com.typesafe.config.{ ConfigFactory, ConfigResolveOptions }
import pureconfig.ConfigSource

import scala.jdk.CollectionConverters.*

/**
 * Configuration as an application sees it, with the environment supplied by the caller.
 *
 * `hocon` (the application's own config) over every `reference.conf` on the classpath, resolved
 * against `env` as if it were the process environment - and only that: the real environment is
 * switched off, so a machine with `OPENAI_API_KEY` set does not change the outcome. This is how a
 * module proves its `reference.conf` binds a variable (`${?VAR}` looks at the root, where `env`
 * sits, and would look at the real environment next).
 *
 * It is in main sources, `private[llm4s]`, because both core's own specs and the published
 * `llm4s-provider-testkit` need it, and core's tests cannot depend on the testkit (the testkit
 * depends on core). The testkit offers it to provider authors as
 * `org.llm4s.testkit.ProviderTestConfig`, which takes the same `String` and `Map` and never
 * hands out a PureConfig `ConfigSource`.
 */
private[llm4s] object ReferenceConfig:

  /**
   * @param hocon the application's config; `""` for none
   * @param env   the environment variables `${?VAR}` substitutions see
   */
  def withEnv(hocon: String, env: Map[String, String]): ConfigSource =
    ConfigSource.fromConfig(
      ConfigFactory
        .parseString(hocon)
        .withFallback(ConfigFactory.parseResourcesAnySyntax("reference"))
        .withFallback(ConfigFactory.parseMap(env.asJava))
        .resolve(ConfigResolveOptions.defaults().setUseSystemEnvironment(false))
    )
