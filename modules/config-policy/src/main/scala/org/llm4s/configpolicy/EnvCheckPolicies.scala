package org.llm4s.configpolicy

/**
 * Environment-variable-based config-policy CLI.
 *
 * Alternative facet to the catalog/HOCON [[CheckPolicies]] entry point.
 * Reads `LLM_*` environment variables, evaluates them against a named preset
 * ([[DefaultPolicies]]) and reports Pass/Warn/Skip/Fail.
 *
 * Usage: `--env=<name> --preset=<preset> [--verbose]`.
 */
object EnvCheckPolicies {
  final case class Options(
    env: String = "prod",
    preset: String = "prod-safe",
    verbose: Boolean = false
  )

  def parseArgs(args: Array[String]): Options =
    args.foldLeft(Options()) { (acc, arg) =>
      if (arg.startsWith("--env=")) acc.copy(env = arg.stripPrefix("--env=").trim.toLowerCase)
      else if (arg.startsWith("--preset=")) acc.copy(preset = arg.stripPrefix("--preset=").trim.toLowerCase)
      else if (arg == "--verbose") acc.copy(verbose = true)
      else acc
    }

  def snapshotFromEnv(getEnv: String => Option[String]): ConfigSnapshot =
    ConfigSnapshot(
      provider = getEnv("LLM_PROVIDER").map(_.toLowerCase),
      model = getEnv("LLM_MODEL"),
      maxTokens = getEnv("LLM_MAX_TOKENS").flatMap(PolicyBuilder.parseInt),
      reasoningBudget = getEnv("LLM_REASONING_BUDGET").flatMap(PolicyBuilder.parseInt),
      region = getEnv("LLM_REGION").orElse(getEnv("AZURE_REGION")),
      baseUrl = getEnv("OPENAI_BASE_URL")
    )

  // Note: This CLI tool deliberately reads raw env vars for pre-config validation
  // (before Llm4sConfig is available), which is acceptable for a standalone entry point.
  // scalafix:off DisableSyntax.NoSysEnv
  def run(options: Options, getEnv: String => Option[String] = key => sys.env.get(key)): Int =
    // scalafix:on DisableSyntax.NoSysEnv
    DefaultPolicies.getPreset(options.preset) match {
      case None =>
        println(s"Unknown preset '${options.preset}'. Available presets: ${DefaultPolicies.listPresets.mkString(", ")}")
        2
      case Some(policies) =>
        val snapshot = snapshotFromEnv(getEnv)
        val result   = ConfigPolicyRunner.evaluate(snapshot, options.env, policies)
        println(ConfigPolicyRunner.formatReport(result, options.verbose))
        if (result.passed) 0 else 1
    }

  def main(args: Array[String]): Unit = {
    val options = parseArgs(args)
    val code    = run(options)
    sys.exit(code)
  }
}
