package org.llm4s.scalafix

import scala.meta._
import scalafix.v1._

/**
 * The one error model (#2916): `LLMError.isRetryable` replaces the marker traits' checks, and two fields give way
 * to the uniform `httpStatus` and `retryAfter` of every `LLMError`.
 *
 *   - `LLMError.isRecoverable(error)` becomes `error.isRetryable`.
 *   - `LLMError.recoverableErrors(errors)` becomes `errors.filter(_.isRetryable)`, and `nonRecoverableErrors`
 *     `errors.filterNot(_.isRetryable)`.
 *   - `serviceError.isRecoverableStatus` becomes `serviceError.isRetryable`.
 *   - `serviceError.httpStatus` becomes `serviceError.status`: `httpStatus` is now the `Option[Int]` every error has.
 *   - `circuitOpenError.retryAfter` becomes `circuitOpenError.remaining`: `retryAfter` is now only a delay a server
 *     asked for.
 *
 * `isRetryable` is narrower than `isRecoverable` was: a `ServiceError` with a client-error status, an
 * `OptimisticLockFailure` and a `CircuitOpenError` are not retryable. A `with RecoverableError` or
 * `with NonRecoverableError` on an error of your own, and a `retryDelay`, are left for you: see the migration note.
 */
class ErrorIsRetryable extends SemanticRule("ErrorIsRetryable") {

  override def description: String =
    "Rewrites LLMError.isRecoverable(e), recoverableErrors and isRecoverableStatus to isRetryable, " +
      "ServiceError.httpStatus to status and CircuitOpenError.retryAfter to remaining"

  import ErrorIsRetryable._

  override def fix(implicit doc: SemanticDocument): Patch =
    doc.tree.collect {
      case t: Term.Apply if t.argClause.values.size == 1 && calls(t, IsRecoverable) =>
        Patch.replaceTree(t, s"${receiver(t.argClause.values.head)}.isRetryable")
      case t: Term.Apply if t.argClause.values.size == 1 && calls(t, RecoverableErrors) =>
        Patch.replaceTree(t, s"${receiver(t.argClause.values.head)}.filter(_.isRetryable)")
      case t: Term.Apply if t.argClause.values.size == 1 && calls(t, NonRecoverableErrors) =>
        Patch.replaceTree(t, s"${receiver(t.argClause.values.head)}.filterNot(_.isRetryable)")
      case Term.Select(_, member @ Term.Name("isRecoverableStatus")) if symbolIs(member, IsRecoverableStatus) =>
        Patch.replaceTree(member, "isRetryable")
      case Term.Select(_, member @ Term.Name("httpStatus")) if symbolIs(member, ServiceErrorHttpStatus) =>
        Patch.replaceTree(member, "status")
      case Term.Select(_, member @ Term.Name("retryAfter")) if symbolIs(member, CircuitOpenRetryAfter) =>
        Patch.replaceTree(member, "remaining")
    }.asPatch

  private def calls(t: Term.Apply, symbol: String)(implicit doc: SemanticDocument): Boolean =
    Migration.symbolOf(t.fun).exists(_.value == symbol)

  private def symbolIs(name: Term.Name, symbols: Set[String])(implicit doc: SemanticDocument): Boolean =
    Migration.symbolOf(name).exists(s => symbols(s.value))

  /** The argument as the receiver of a method call: as written when it is a name, a selection or a call. */
  private def receiver(arg: Term): String = arg match {
    case _: Term.Name | _: Term.Select | _: Term.Apply => arg.pos.text
    case _                                             => s"(${arg.pos.text})"
  }
}

object ErrorIsRetryable {
  private val IsRecoverable        = "org/llm4s/error/LLMError.isRecoverable()."
  private val RecoverableErrors    = "org/llm4s/error/LLMError.recoverableErrors()."
  private val NonRecoverableErrors = "org/llm4s/error/LLMError.nonRecoverableErrors()."
  // the member of `ServiceError` (0.6.0); `DuplicateApisRemoved` renames the 0.5.x `ServiceErrorOps` extension
  private val IsRecoverableStatus = Set("org/llm4s/error/ServiceError#isRecoverableStatus().")
  private val ServiceErrorHttpStatus = Set("org/llm4s/error/ServiceError#httpStatus.")
  private val CircuitOpenRetryAfter  = Set("org/llm4s/error/CircuitOpenError#retryAfter.")
}
