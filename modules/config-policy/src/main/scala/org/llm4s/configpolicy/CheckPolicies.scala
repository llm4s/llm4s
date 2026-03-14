package org.llm4s.configpolicy

import org.llm4s.config.Llm4sConfig
import pureconfig.ConfigSource

import java.nio.file.Paths

/**
 * CLI entrypoint for config policy checks in CI.
 *
 * Usage:
 * {{{
 *   sbt "configPolicy/runMain org.llm4s.configpolicy.CheckPolicies --env=prod"
 *   sbt "configPolicy/runMain org.llm4s.configpolicy.CheckPolicies --env=dev --config=config/application-dev.conf"
 * }}}
 *
 * Exits 0 if config loads and passes policy; 1 if config load fails or policy is violated.
 */
object CheckPolicies {

  def main(args: Array[String]): Unit = {
    val envOpt = parseArg(args, "--env").orElse(Some("prod"))
    val configPathOpt = parseArg(args, "--config")

    val env = envOpt.getOrElse("prod")
    val policy = ConfigPolicy.preset(env).getOrElse(ConfigPolicy.prodSafeDefaults)

    val source = configPathOpt match {
      case Some(path) =>
        val resourcePath = path.replace('\\', '/').stripPrefix("/")
        val fromResource = getClass.getResource("/" + resourcePath) != null
        val f            = Paths.get(path).toAbsolutePath.normalize().toFile
        val fromFile     = f.exists()
        if (fromFile)
          ConfigSource.file(f)
        else if (fromResource)
          ConfigSource.resources(resourcePath)
        else
          ConfigSource.default
      case None => ConfigSource.default
    }

    Llm4sConfig.providerFrom(source) match {
      case Right(config) =>
        val violations = ConfigPolicyRunner.check(config, policy)
        if (violations.isEmpty) {
          println(s"Config policy check passed (env=$env).")
          sys.exit(0)
        } else {
          Console.err.println(s"Config policy check failed (env=$env):")
          violations.foreach(v => Console.err.println("  " + v.toString))
          sys.exit(1)
        }
      case Left(err) =>
        Console.err.println("Failed to load provider config: " + err.formatted)
        sys.exit(1)
    }
  }

  private def parseArg(args: Array[String], name: String): Option[String] = {
    val idx = args.indexOf(name)
    if (idx >= 0 && idx + 1 < args.length)
      Some(args(idx + 1))
    else {
      val prefix = name + "="
      args.find(_.startsWith(prefix)).map(_.stripPrefix(prefix))
    }
  }
}
