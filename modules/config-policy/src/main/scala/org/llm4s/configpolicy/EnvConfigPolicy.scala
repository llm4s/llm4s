package org.llm4s.configpolicy

import scala.util.Try

/**
 * Snapshot of configuration values read from the environment, used by the
 * environment-variable-based policy engine ([[EnvConfigPolicy]]).
 *
 * This is an alternative facet to the catalog/HOCON based engine
 * ([[ConfigPolicy]] / [[ConfigPolicyEngine]]); the two coexist intentionally.
 */
final case class ConfigSnapshot(
  provider: Option[String],
  model: Option[String],
  maxTokens: Option[Int],
  reasoningBudget: Option[Int],
  region: Option[String],
  baseUrl: Option[String]
)

sealed trait PolicyResult {
  def policyName: String
  def message: String
}

final case class PolicyPass(policyName: String, message: String = "passed")                       extends PolicyResult
final case class PolicyFail(policyName: String, message: String)                                  extends PolicyResult
final case class PolicyWarn(policyName: String, message: String)                                  extends PolicyResult
final case class PolicySkip(policyName: String, message: String = "skipped for this environment") extends PolicyResult

final case class PolicyEvaluationResult(results: List[PolicyResult]) {
  val failures: List[PolicyFail] = results.collect { case f: PolicyFail => f }
  val warnings: List[PolicyWarn] = results.collect { case w: PolicyWarn => w }
  val skipped: List[PolicySkip]  = results.collect { case s: PolicySkip => s }

  def passed: Boolean = failures.isEmpty
}

/**
 * A single environment-variable-based configuration policy.
 *
 * Renamed from `ConfigPolicy` to avoid a name collision with the canonical
 * catalog-based [[ConfigPolicy]] data model; both engines live side by side.
 */
trait EnvConfigPolicy {
  def name: String
  def evaluate(config: ConfigSnapshot, env: String): PolicyResult
}

object PolicyBuilder {

  private def shouldRun(targetEnvs: Set[String], env: String): Boolean =
    targetEnvs.isEmpty || targetEnvs.contains(env)

  def allowedProviders(allowed: Set[String], envs: Set[String] = Set.empty): EnvConfigPolicy =
    new EnvConfigPolicy {
      override val name: String = "allowed-providers"

      override def evaluate(config: ConfigSnapshot, env: String): PolicyResult =
        if (!shouldRun(envs, env)) PolicySkip(name)
        else {
          val provider = config.provider.getOrElse("")
          if (provider.nonEmpty && allowed.contains(provider.toLowerCase)) PolicyPass(name)
          else PolicyFail(name, s"provider '$provider' is not allowed in env '$env'")
        }
    }

  def allowedModels(allowed: Set[String], envs: Set[String] = Set.empty): EnvConfigPolicy =
    new EnvConfigPolicy {
      override val name: String = "allowed-models"

      override def evaluate(config: ConfigSnapshot, env: String): PolicyResult =
        if (!shouldRun(envs, env)) PolicySkip(name)
        else {
          val model = config.model.getOrElse("")
          if (model.nonEmpty && allowed.contains(model)) PolicyPass(name)
          else PolicyFail(name, s"model '$model' is not allowed in env '$env'")
        }
    }

  def maxTokensLimit(max: Int, envs: Set[String] = Set.empty): EnvConfigPolicy =
    new EnvConfigPolicy {
      override val name: String = "max-tokens-limit"

      override def evaluate(config: ConfigSnapshot, env: String): PolicyResult =
        if (!shouldRun(envs, env)) PolicySkip(name)
        else {
          config.maxTokens match {
            case Some(value) if value <= max => PolicyPass(name)
            case Some(value) =>
              PolicyFail(name, s"maxTokens $value exceeds allowed limit $max for env '$env'")
            case None =>
              PolicyWarn(name, s"maxTokens is not set for env '$env'")
          }
        }
    }

  def reasoningBudgetLimit(max: Int, envs: Set[String] = Set.empty): EnvConfigPolicy =
    new EnvConfigPolicy {
      override val name: String = "reasoning-budget-limit"

      override def evaluate(config: ConfigSnapshot, env: String): PolicyResult =
        if (!shouldRun(envs, env)) PolicySkip(name)
        else {
          config.reasoningBudget match {
            case Some(value) if value <= max => PolicyPass(name)
            case Some(value) =>
              PolicyFail(name, s"reasoningBudget $value exceeds allowed limit $max for env '$env'")
            case None =>
              PolicyPass(name, "reasoning budget not set")
          }
        }
    }

  def requiredRegion(allowedRegions: Set[String] = Set.empty, envs: Set[String] = Set.empty): EnvConfigPolicy =
    new EnvConfigPolicy {
      override val name: String = "required-region"

      override def evaluate(config: ConfigSnapshot, env: String): PolicyResult =
        if (!shouldRun(envs, env)) PolicySkip(name)
        else {
          config.region match {
            case None | Some("") => PolicyFail(name, s"region is required for env '$env'")
            case Some(value) if allowedRegions.isEmpty || allowedRegions.contains(value) => PolicyPass(name)
            case Some(value) => PolicyFail(name, s"region '$value' is not allowed for env '$env'")
          }
        }
    }

  def customPolicy(
    policyName: String,
    envs: Set[String] = Set.empty
  )(rule: (ConfigSnapshot, String) => Either[String, String]): EnvConfigPolicy =
    new EnvConfigPolicy {
      override val name: String = policyName

      override def evaluate(config: ConfigSnapshot, env: String): PolicyResult =
        if (!shouldRun(envs, env)) PolicySkip(name)
        else {
          rule(config, env) match {
            case Right(msg) => PolicyPass(name, msg)
            case Left(err)  => PolicyFail(name, err)
          }
        }
    }

  def parseInt(value: String): Option[Int] = Try(value.trim.toInt).toOption
}

object DefaultPolicies {
  import PolicyBuilder._

  val productionSafeDefaults: List[EnvConfigPolicy] = List(
    allowedProviders(Set("openai", "anthropic", "azure"), Set("prod")),
    maxTokensLimit(4096, Set("prod")),
    reasoningBudgetLimit(10000, Set("prod")),
    requiredRegion(Set("eastus", "westeurope", "uksouth"), Set("prod"))
  )

  val devSandboxDefaults: List[EnvConfigPolicy] = List(
    maxTokensLimit(16384, Set("dev")),
    reasoningBudgetLimit(50000, Set("dev"))
  )

  val stagingBalancedDefaults: List[EnvConfigPolicy] = List(
    allowedProviders(Set("openai", "anthropic", "gemini", "azure"), Set("staging")),
    allowedModels(
      Set(
        "gpt-4o-mini",
        "gpt-4o",
        "claude-3-5-sonnet",
        "gemini-1.5-pro"
      ),
      Set("staging")
    ),
    maxTokensLimit(8192, Set("staging")),
    requiredRegion(Set("eastus", "westeurope", "uksouth"), Set("staging"))
  )

  val costControlledDefaults: List[EnvConfigPolicy] = List(
    maxTokensLimit(4096),
    reasoningBudgetLimit(10000)
  )

  val complianceDefaults: List[EnvConfigPolicy] = List(
    allowedProviders(Set("azure", "openai", "anthropic")),
    requiredRegion(Set("eastus", "westeurope", "uksouth"))
  )

  val allDefaults: List[EnvConfigPolicy] =
    productionSafeDefaults ++ devSandboxDefaults ++ stagingBalancedDefaults ++ costControlledDefaults ++ complianceDefaults

  def getPreset(name: String): Option[List[EnvConfigPolicy]] = name match {
    case "prod-safe"        => Some(productionSafeDefaults)
    case "dev-sandbox"      => Some(devSandboxDefaults)
    case "staging-balanced" => Some(stagingBalancedDefaults)
    case "cost-controlled"  => Some(costControlledDefaults)
    case "compliance"       => Some(complianceDefaults)
    case "all"              => Some(allDefaults)
    case _                  => None
  }

  def listPresets: List[String] =
    List("prod-safe", "dev-sandbox", "staging-balanced", "cost-controlled", "compliance", "all")
}
