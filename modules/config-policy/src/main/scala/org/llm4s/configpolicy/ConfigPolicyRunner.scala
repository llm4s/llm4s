package org.llm4s.configpolicy

object ConfigPolicyRunner {
  def evaluate(config: ConfigSnapshot, env: String, policies: List[EnvConfigPolicy]): PolicyEvaluationResult =
    PolicyEvaluationResult(policies.map(_.evaluate(config, env)))

  def formatReport(result: PolicyEvaluationResult, verbose: Boolean = false): String = {
    val header =
      if (result.passed) "CONFIG POLICY CHECK: PASS"
      else "CONFIG POLICY CHECK: FAIL"

    val details =
      result.results.flatMap {
        case p: PolicyPass if verbose => Some(s"[PASS] ${p.policyName}: ${p.message}")
        case f: PolicyFail            => Some(s"[FAIL] ${f.policyName}: ${f.message}")
        case w: PolicyWarn            => Some(s"[WARN] ${w.policyName}: ${w.message}")
        case s: PolicySkip if verbose => Some(s"[SKIP] ${s.policyName}: ${s.message}")
        case _                        => None
      }

    val summary =
      s"Summary: failures=${result.failures.size}, warnings=${result.warnings.size}, skipped=${result.skipped.size}"

    (header :: summary :: details).mkString("\n")
  }
}
