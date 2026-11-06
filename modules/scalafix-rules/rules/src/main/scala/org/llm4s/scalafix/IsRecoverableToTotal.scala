package org.llm4s.scalafix

import scala.meta._
import scalafix.v1._

/**
 * `error.isRecoverable` becomes `LLMError.isRecoverable(error)`.
 *
 * 0.5.0 removed the member: the total `LLMError.isRecoverable` decides recoverability for every error, including a
 * custom one, which is recoverable only if it mixes in `RecoverableError`.
 */
class IsRecoverableToTotal extends SemanticRule("IsRecoverableToTotal") {

  override def description: String =
    "Rewrites error.isRecoverable to the total LLMError.isRecoverable(error)"

  override def fix(implicit doc: SemanticDocument): Patch = {
    val patch = doc.tree.collect {
      case s @ Term.Select(error, name @ Term.Name("isRecoverable")) if isMember(name) =>
        Patch.replaceTree(s, s"LLMError.isRecoverable(${error.pos.text})")
    }.asPatch
    if (patch.isEmpty) patch else patch + Migration.importOnce("org.llm4s.error", "LLMError")
  }

  /** The 0.4.1 member on `LLMError` or one of its subtypes, not the companion's total function. */
  private def isMember(name: Term.Name)(implicit doc: SemanticDocument): Boolean =
    Migration.symbolOf(name).exists(s => s.value.startsWith("org/llm4s/") && s.value.endsWith("#isRecoverable()."))
}
