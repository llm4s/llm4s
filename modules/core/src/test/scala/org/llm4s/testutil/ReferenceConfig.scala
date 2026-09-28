package org.llm4s.testutil

// scalafix:off DisableSyntax.NoConfigFactory
import com.typesafe.config.{ ConfigFactory, ConfigResolveOptions }
// scalafix:on DisableSyntax.NoConfigFactory
import pureconfig.ConfigSource

import scala.jdk.CollectionConverters.*

/**
 * Configuration as an application sees it, with the environment supplied by the test.
 *
 * `hocon` (the application's own config) over every `reference.conf` on the classpath, resolved
 * against `env` as if it were the process environment - and only that: the real environment is
 * switched off, so a machine with `OPENAI_API_KEY` set does not change the outcome. This is how a
 * provider module proves its `reference.conf` binds a variable (`${?VAR}` looks at the root,
 * where `env` sits, and would look at the real environment next).
 *
 * The same shape as the `withReference` helpers in `TracingConfigLoaderSpec` and
 * `OpenAIEmbeddingsSpec`; shared here because every provider module's round-trip spec needs it.
 */
object ReferenceConfig:

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
