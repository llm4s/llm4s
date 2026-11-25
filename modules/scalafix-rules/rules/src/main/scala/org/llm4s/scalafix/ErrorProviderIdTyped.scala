package org.llm4s.scalafix

import scala.meta._
import scalafix.v1._

/**
 * The provider an error names is a `ProviderId`, where 0.7.0 took and returned a `String` (#2936): on
 * `AuthenticationError`, `RateLimitError`, `ServiceError`, `CircuitOpenError`, `EmbeddingError`,
 * `ContentFilteredError`, `ContextLengthError`, `ModelNotFoundError` and `UnsupportedContentPartError`. The rule
 * rewrites code compiled against 0.7.0 or earlier:
 *
 *   - the provider a factory is given is wrapped, whatever expression it is: `RateLimitError("openai")` to
 *     `RateLimitError(ProviderId("openai"))`, and likewise `RateLimitError.local`, `AuthenticationError(provider, ...)`,
 *     `ServiceError(status, provider, ...)`, `CircuitOpenError.open` and `probing`, `EmbeddingError(code, message,
 *     provider)`, `ContentFilteredError(...)`, its `prompt` and `reply`, `ContextLengthError`, `ModelNotFoundError`
 *     and `UnsupportedContentPartError`, by position or by name;
 *   - a read of `error.provider` gets `.asString`, which keeps its type, so a comparison with a literal, a metrics
 *     label or a string method still compiles and means what it did. A read inside a string interpolation is left
 *     as it is: it prints the same.
 *
 * The import of `ProviderId` is added where the rule writes one. A pattern on the provider
 * (`case ServiceError(_, _, "openai", _, _)`) is left for the compiler to report. `ProviderId(...)` trims and
 * lower-cases, so a provider given as `"OpenAI"` reads `openai` afterwards.
 */
class ErrorProviderIdTyped extends SemanticRule("ErrorProviderIdTyped") {

  override def description: String =
    "Wraps the String provider of the llm4s errors in ProviderId, and reads it back with .asString"

  import ErrorProviderIdTyped._

  override def fix(implicit doc: SemanticDocument): Patch = {
    val sets        = doc.tree.collect { case t: Term.Apply if !t.fun.is[Term.Apply] => providerArgument(t) }.flatten
    val wraps       = sets.filterNot(isWrapped)
    val wrapPatches = wraps.map(arg => Patch.addLeft(arg, Provider + "(") + Patch.addRight(arg, ")"))
    val reads = doc.tree.collect {
      case s: Term.Select if isRead(s) && !alreadyString(s) && !interpolated(s) && !sets.exists(_ eq s) =>
        Patch.addRight(s, ".asString")
    }
    val imports = if (wraps.isEmpty) Patch.empty else Migration.importOnce(Newtypes, Provider)
    (wrapPatches ++ reads).asPatch + imports
  }

  /** The argument of a factory call that is the error's provider. */
  private def providerArgument(t: Term.Apply)(implicit doc: SemanticDocument): Option[Term] =
    Migration.callee(t).flatMap { callee =>
      Factories.collectFirst { case (sym, index) if matches(callee, sym) => index }.flatMap { index =>
        val args = t.argClause.values
        args
          .collectFirst { case Term.Assign(Term.Name("provider"), rhs) => rhs }
          .orElse(args.lift(index).filter(arg => !arg.is[Term.Assign] && args.take(index).forall(!_.is[Term.Assign])))
      }
    }

  private def matches(callee: String, sym: String): Boolean =
    if (sym.endsWith("().")) callee.startsWith(sym.stripSuffix("().") + "(") else Migration.constructs(callee, sym)

  /** An argument typed already: `ProviderId(...)`, by this rule or by hand, or another error's provider. */
  private def isWrapped(arg: Term)(implicit doc: SemanticDocument): Boolean = arg match {
    case a: Term.Apply  => a.fun.syntax == Provider || a.fun.syntax.endsWith("." + Provider)
    case s: Term.Select => isRead(s)
    case _              => false
  }

  private def isRead(t: Tree)(implicit doc: SemanticDocument): Boolean = t match {
    case s: Term.Select => Migration.symbolOf(s.name).exists(sym => Reads.contains(sym.value))
    case _              => false
  }

  /** A read already unwrapped, by an earlier run of this rule. */
  private def alreadyString(s: Term.Select): Boolean = s.parent match {
    case Some(Term.Select(q, Term.Name("asString"))) => q eq s
    case _                                           => false
  }

  /** A read spliced into a string, directly or in a `${...}` block: it prints as it did. */
  private def interpolated(s: Term.Select): Boolean = s.parent match {
    case Some(_: Term.Interpolate)                                                           => true
    case Some(b: Term.Block) if b.stats.size == 1 && b.parent.exists(_.is[Term.Interpolate]) => true
    case _                                                                                   => false
  }
}

object ErrorProviderIdTyped {

  private val Newtypes = "org.llm4s.types.ProviderModelTypes"
  private val Provider = "ProviderId"
  private val Errors   = "org/llm4s/error/"

  /** Where `EmbeddingError` lived up to 0.6.0. */
  private val Model06 = "org/llm4s/llmconnect/model/"

  /** The factories that take the provider, with its position: an owner (its `apply`) or a method (`...().`). */
  private val Factories: List[(String, Int)] = List(
    Errors + "AuthenticationError"            -> 0,
    Errors + "RateLimitError.local()."        -> 0,
    Errors + "RateLimitError"                 -> 0,
    Errors + "ServiceError"                   -> 1,
    Errors + "CircuitOpenError.open()."       -> 0,
    Errors + "CircuitOpenError.probing()."    -> 0,
    Errors + "EmbeddingError"                 -> 2,
    Model06 + "EmbeddingError"                -> 2,
    Errors + "ContentFilteredError.prompt()." -> 0,
    Errors + "ContentFilteredError.reply()."  -> 0,
    Errors + "ContentFilteredError"           -> 0,
    Errors + "ContextLengthError"             -> 0,
    Errors + "ModelNotFoundError"             -> 0,
    Errors + "UnsupportedContentPartError"    -> 0
  )

  /** The reads that returned a `String` in 0.7.0 and return a `ProviderId` now. */
  private val Reads: Set[String] = Set(
    "AuthenticationError",
    "RateLimitError",
    "ServiceError",
    "CircuitOpenError",
    "EmbeddingError",
    "ContentFilteredError",
    "ContextLengthError",
    "ModelNotFoundError",
    "UnsupportedContentPartError"
  ).map(Errors + _ + "#provider.") + (Model06 + "EmbeddingError#provider.")
}
