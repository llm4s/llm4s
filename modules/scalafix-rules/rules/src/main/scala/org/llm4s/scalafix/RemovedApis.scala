package org.llm4s.scalafix

import scala.meta._
import scalafix.v1._

/**
 * Reports each use of a 0.4.1 API that 0.5.0 removed with no mechanical replacement, with a link to the section of
 * the migration guide that says what to do: the orchestration package (`PlanRunner`, `DAG`, `TypedAgent`,
 * `CancellationToken`), `org.llm4s.types.AgentId` and `PlanId`, and `ToolRegistry.getToolDefinitionsSafe`.
 */
class RemovedApis extends SemanticRule("RemovedApis") {

  override def description: String =
    "Reports uses of llm4s 0.4.1 APIs that 0.5.0 removed without a mechanical replacement"

  override def fix(implicit doc: SemanticDocument): Patch =
    doc.tree.collect {
      case n: Name if !inImport(n) =>
        Migration.symbolOf(n).flatMap(s => RemovedApis.removal(s.value)).fold(Patch.empty) { case (id, message) =>
          Patch.lint(Diagnostic(id, message, n.pos))
        }
    }.asPatch

  /** An import names a removed type without using it; the uses are reported. */
  private def inImport(tree: Tree): Boolean =
    tree.parent.exists(p => p.is[Import] || inImport(p))
}

object RemovedApis {

  private val orchestration = "org/llm4s/agent/orchestration/"

  /** A class, trait or object directly in the orchestration package, not its members (one report per name). */
  private val orchestrationType = (java.util.regex.Pattern.quote(orchestration) + "[^/#.]+[#.]").r

  private val typesId = """org/llm4s/types/(package\.)?(AgentId|PlanId)[#.]""".r

  /** The lint id and message for a removed symbol, if it is one. */
  def removal(symbol: String): Option[(String, String)] =
    if (orchestrationType.matches(symbol))
      Some(
        "orchestrationRemoved" ->
          ("org.llm4s.agent.orchestration is removed in 0.5.0; build the workflow as a graph, and cancel a run with " +
            "AgentRun.cancel(): " + Migration.guide05("orchestration-is-removed"))
      )
    else if (typesId.matches(symbol))
      Some(
        "typesIdRemoved" ->
          ("org.llm4s.types.AgentId and PlanId are removed in 0.5.0; an agent's id is org.llm4s.agent.AgentId " +
            "(llm4s-agent) and a run's is RunId: " + Migration.guide05("7-errors-cancellation-and-types"))
      )
    else if (symbol == "org/llm4s/toolapi/ToolRegistry#getToolDefinitionsSafe().")
      Some(
        "getToolDefinitionsSafeRemoved" ->
          ("ToolRegistry.getToolDefinitionsSafe is removed in 0.5.0; use getOpenAITools(), or the provider's own " +
            "tool conversion: " + Migration.guide05("7-errors-cancellation-and-types"))
      )
    else None
}
