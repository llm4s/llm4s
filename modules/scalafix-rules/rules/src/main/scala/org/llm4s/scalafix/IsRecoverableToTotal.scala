package org.llm4s.scalafix

import scala.meta._
import scalafix.v1._

/**
 * `error.isRecoverable` becomes `error.isRetryable`.
 *
 * 0.5.0 removed the member for the total `LLMError.isRecoverable(error)`, which gave way in turn to the member
 * `isRetryable` (#2916): the one answer to "is the same request worth sending again", `false` for an error that
 * does not override it. It is narrower than `isRecoverable` was: a `ServiceError` with a client-error status is not
 * retryable.
 */
class IsRecoverableToTotal extends SemanticRule("IsRecoverableToTotal") {

  override def description: String =
    "Rewrites error.isRecoverable to error.isRetryable"

  override def fix(implicit doc: SemanticDocument): Patch =
    doc.tree.collect {
      case Term.Select(_, member @ Term.Name("isRecoverable")) if isMember(member) =>
        Patch.replaceTree(member, "isRetryable")
    }.asPatch

  /** The 0.4.1 member on `LLMError` or one of its subtypes, not the companion's total function. */
  private def isMember(name: Term.Name)(implicit doc: SemanticDocument): Boolean =
    Migration.symbolOf(name).exists(s => s.value.startsWith("org/llm4s/") && s.value.endsWith("#isRecoverable()."))
}
