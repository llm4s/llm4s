package org.llm4s.scalafix

import scala.meta._
import scalafix.v1._

/**
 * `LLMConnect` has one way to get a client, and `LLMClient.streamComplete` takes its callback in a second parameter
 * list (#2810):
 *
 *   - `LLMConnect.fromConfig(config, options)` and `LLMConnect.fromProvider(provider, config, options)` become
 *     `LLMConnect.getClient(...)`.
 *   - `LLMConnect.getClient(config, metrics)` becomes `getClient(config, LlmClientOptions(metrics = metrics))`, and
 *     the same with a provider first; `import org.llm4s.llmconnect.LlmClientOptions` is added.
 *   - `LLMConnect.getClient(provider, config)`, and `fromProvider(provider, config)`, become `getClient(provider,
 *     config, LlmClientOptions.default)`: only the overload without a provider has a default for its options.
 *   - `client.streamComplete(conversation, options, onChunk)` becomes `client.streamComplete(conversation,
 *     options)(onChunk)`, also where the callback is passed by name; an overriding
 *     `def streamComplete(conversation, options, onChunk)` gets the same two parameter lists.
 *
 * A method value, `.flatMap(LLMConnect.getClient)`, is not rewritten: write `LLMConnect.getClient(_)`. A call of
 * `streamComplete` on a client class of your own is rewritten only where it overrides `LLMClient`'s in a file the
 * rule sees; one it misses fails to compile with "too many arguments".
 */
class ClientEntryPoints extends SemanticRule("ClientEntryPoints") {

  override def description: String =
    "Rewrites LLMConnect.fromConfig/fromProvider and the metrics overloads to getClient, and curries streamComplete's onChunk"

  import ClientEntryPoints._

  override def fix(implicit doc: SemanticDocument): Patch = {
    val patches = doc.tree.collect {
      case n: Term.Name if Migration.symbolOf(n).exists(s => Renamed(s.value)) =>
        List((Patch.replaceTree(n, "getClient"), false))
      case t: Term.Apply if !t.fun.is[Term.Apply] => options(t) ++ streamCall(t).toList
      case d: Defn.Def                            => streamDefinition(d).toList
      case d: Decl.Def                            => streamDeclaration(d).toList
    }.flatten
    val wrapped = patches.exists(_._2)
    val patch   = patches.map(_._1).asPatch
    if (wrapped) patch + Migration.importOnce("org.llm4s.llmconnect", "LlmClientOptions") else patch
  }

  /** The `getClient` overloads that took a collector, or a provider and no options; `true` when an import is needed. */
  private def options(t: Term.Apply)(implicit doc: SemanticDocument): List[(Patch, Boolean)] =
    (Migration.symbolOf(t.fun).map(_.value), t.argClause.values) match {
      case (Some(s), args) if WithMetrics(s) && args.nonEmpty =>
        val metrics = args.last match {
          case Term.Assign(_, rhs) => rhs
          case other               => other
        }
        List((Patch.addLeft(metrics, "LlmClientOptions(metrics = ") + Patch.addRight(metrics, ")"), true))
      case (Some(WithProviderOnly | FromProvider), args) if args.size == 2 =>
        List((Patch.addRight(args.last, ", LlmClientOptions.default"), true))
      case _ => Nil
    }

  /** `streamComplete(a, b, f)` as `streamComplete(a, b)(f)`. */
  private def streamCall(t: Term.Apply)(implicit doc: SemanticDocument): Option[(Patch, Boolean)] =
    if (!Migration.symbolOf(t.fun).exists(s => isStreamComplete(s.value)) || t.parent.exists(_.is[Term.Apply])) None
    else {
      val args = t.argClause.values
      // the callback is the last argument: the third by position, or `onChunk = f` after one or two others
      val callback = args.lastOption.collect {
        case Term.Assign(Term.Name("onChunk"), rhs) if args.size >= 2 => rhs
        case last if args.size == 3 && !last.is[Term.Assign]          => last
      }
      callback.map(value => (curried(args, value), false))
    }

  /** An overriding `def streamComplete(conversation, options, onChunk: StreamedChunk => Unit)`. */
  private def streamDefinition(d: Defn.Def)(implicit doc: SemanticDocument): Option[(Patch, Boolean)] =
    curriedParameters(d.name, d.paramClauseGroups.flatMap(_.paramClauses))

  private def streamDeclaration(d: Decl.Def)(implicit doc: SemanticDocument): Option[(Patch, Boolean)] =
    curriedParameters(d.name, d.paramClauseGroups.flatMap(_.paramClauses))

  private def curriedParameters(name: Term.Name, clauses: List[Term.ParamClause])(implicit
    doc: SemanticDocument
  ): Option[(Patch, Boolean)] =
    clauses match {
      case List(clause) if name.value == "streamComplete" && clause.values.size == 3 =>
        val callback = clause.values(2)
        if (callback.decltpe.exists(_.syntax.replaceAll("\\s", "").endsWith("StreamedChunk=>Unit")))
          Some((curried(clause.values, callback), false))
        else None
      case _ => None
    }

  /**
   * Makes the last argument a list of its own: the comma before it becomes `)(`, and a name (`onChunk = `) goes.
   * The argument's own tokens stay where they are, so another rule's rewrite inside the callback still applies.
   */
  private def curried(args: List[Tree], value: Tree)(implicit doc: SemanticDocument): Patch = {
    val before = args(args.size - 2).pos.end
    val removed =
      doc.tokens.filter(tk => tk.pos.start >= before && tk.pos.end <= value.pos.start && tk.pos.end > tk.pos.start)
    removed.headOption.fold(Patch.empty)(first => Patch.addLeft(first, ")(") + Patch.removeTokens(removed))
  }
}

object ClientEntryPoints {
  private val Connect = "org/llm4s/llmconnect/LLMConnect."

  private val FromProvider = Connect + "fromProvider()."
  private val Renamed      = Set(Connect + "fromConfig().", FromProvider)

  /** `getClient(config, metrics)` and `getClient(provider, config, metrics)`, by their place among the overloads. */
  private val WithMetrics = Set(Connect + "getClient().", Connect + "getClient(+3).")

  /** `getClient(provider, config)`. */
  private val WithProviderOnly = Connect + "getClient(+5)."

  /** `streamComplete` of `LLMClient` or of one of llm4s' own clients and wrappers. */
  private def isStreamComplete(symbol: String): Boolean =
    symbol.startsWith("org/llm4s/") && symbol.endsWith("#streamComplete().")
}
