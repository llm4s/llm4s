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
    val source      = configOpt.map(ConfigSource.file).getOrElse(ConfigSource.default)

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

  private def parseArg(args: Array[String], name: String): Option[String] = {
    val idx = args.indexOf(name)
    if (idx >= 0 && idx + 1 < args.length) Some(args(idx + 1))
    else args.find(_.startsWith(name + "=")).map(_.stripPrefix(name + "="))
  }
}
