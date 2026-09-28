// scalafix:off DisableSyntax.NoPureConfigDefault
package org.llm4s.configpolicy

import org.llm4s.config.Llm4sConfig
import pureconfig.ConfigSource

object CheckPolicies {
  def main(args: Array[String]): Unit = {
    val envName   = parseArg(args, "--env").getOrElse("prod")
    val configOpt = parseArg(args, "--config")

    val environment = CatalogEnvironment.fromString(envName)
    val policy      = ConfigPolicy.preset(envName).getOrElse(ConfigPolicy.prodSafeDefaults)
    val source      = sourceFor(configOpt)

    val checked = for {
      providerConfig <- Llm4sConfig.providerFrom(source)
      keySources     <- Llm4sConfig.apiKeySourcesFrom(source)
    } yield ConfigPolicyEngine.check(providerConfig, policy, environment) ++
      ConfigPolicyEngine.checkApiKeySources(keySources, policy, environment)

    checked match {
      case Right(violations) =>
        if (violations.isEmpty) {
          println(s"Config policy check passed for env=$envName")
          sys.exit(0)
        } else {
          Console.err.println(s"Config policy check failed for env=$envName")
          violations.foreach(v => Console.err.println(s" - [${v.rule}] ${v.message}"))
          sys.exit(1)
        }
      case Left(error) =>
        Console.err.println(s"Failed to load provider config: ${error.formatted}")
        sys.exit(1)
    }
  }

  /**
   * The config to check. Without `--config`, the application's own: `ConfigSource.default`.
   *
   * With `--config <file>`, that file in place of `application.conf`, layered as normal loading
   * layers it: `-D` system properties over the file over every module's `reference.conf`. The
   * references matter: they are where provider modules bind their vendor's variable to
   * `llm4s.credentials.<id>.apiKey`, so without them a section relying on `OPENAI_API_KEY`
   * would be reported as missing its key, though the application would load it.
   */
  private[configpolicy] def sourceFor(configFile: Option[String]): ConfigSource =
    configFile.fold[ConfigSource](ConfigSource.default) { path =>
      ConfigSource.defaultOverrides
        .withFallback(ConfigSource.file(path))
        .withFallback(ConfigSource.defaultReference)
    }

  private def parseArg(args: Array[String], name: String): Option[String] = {
    val idx = args.indexOf(name)
    if (idx >= 0 && idx + 1 < args.length) Some(args(idx + 1))
    else args.find(_.startsWith(name + "=")).map(_.stripPrefix(name + "="))
  }
}
