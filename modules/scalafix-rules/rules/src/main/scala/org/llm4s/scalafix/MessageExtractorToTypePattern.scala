package org.llm4s.scalafix

import scala.meta._
import scalafix.v1._

/**
 * `case UserMessage(content) =>` becomes `case userMessage: UserMessage =>`, with `content` read as
 * `userMessage.content` in the guard and the body.
 *
 * From 0.5.1 `UserMessage` and `ToolMessage` carry content parts and have no extractor; `content` (and `toolCallId`)
 * are members. A pattern that is not a whole `case`, or whose arguments are not plain names or `_`, is reported
 * instead of rewritten.
 */
class MessageExtractorToTypePattern extends SemanticRule("MessageExtractorToTypePattern") {

  override def description: String =
    "Rewrites the removed UserMessage(...) and ToolMessage(...) extractors to type patterns"

  import MessageExtractorToTypePattern._

  override def fix(implicit doc: SemanticDocument): Patch = {
    val rewritten = doc.tree.collect { case c: Case =>
      c.pat match {
        case e: Pat.Extract                           => message(e).flatMap(rewrite(c, e, None, _))
        case b @ Pat.Bind(v: Pat.Var, e: Pat.Extract) => message(e).flatMap(rewrite(c, e, Some(b -> v), _))
        case _                                        => None
      }
    }.flatten
    val done = rewritten.map(_._1).toSet
    val nested = doc.tree.collect {
      case e: Pat.Extract if !done.contains(e) && message(e).isDefined =>
        Patch.lint(Diagnostic("messageExtractor", unsupported, e.pos))
    }
    rewritten.map(_._2).asPatch + nested.asPatch
  }

  private def message(e: Pat.Extract)(implicit doc: SemanticDocument): Option[List[String]] =
    Migration.symbolOf(e.fun).map(Migration.owner).flatMap(messages.get)

  private def rewrite(c: Case, e: Pat.Extract, bind: Option[(Pat.Bind, Pat.Var)], fields: List[String])(implicit
    doc: SemanticDocument
  ): Option[(Pat.Extract, Patch)] = {
    val args  = e.argClause.values
    val vars  = args.zip(fields).collect { case (v: Pat.Var, field) => v -> field }
    val plain = args.size == fields.size && args.forall(a => a.is[Pat.Var] || a.is[Pat.Wildcard])
    if (!plain) None
    else {
      val typeName = e.fun.pos.text
      val binder = bind.map(_._2.name.value).orElse {
        if (vars.isEmpty) None else Some(fresh(c, typeName.split('.').last))
      }
      val pattern = binder.fold(s"_: $typeName")(b => s"$b: $typeName")
      val references = vars.map { case (v, field) =>
        val sym   = Migration.symbolOf(v.name)
        val scope = c.cond.toList :+ c.body
        scope
          .flatMap(_.collect {
            case n: Term.Name if refersTo(n, v, sym) =>
              val read = s"${binder.getOrElse("")}.$field"
              val text = if (doc.input.text.charAt(n.pos.start - 1) == '$') s"{$read}" else read
              Patch.replaceTree(n, text)
          })
          .asPatch
      }
      val target: Tree = bind.map(_._1).getOrElse(e)
      Some(e -> (Patch.replaceTree(target, pattern) + references.asPatch))
    }
  }

  private def refersTo(n: Term.Name, v: Pat.Var, sym: Option[Symbol])(implicit doc: SemanticDocument): Boolean =
    n.value == v.name.value && !n.parent.exists {
      case s: Term.Select => s.name eq n
      case _              => false
    } && (sym.isEmpty || Migration.symbolOf(n) == sym)

  /** `userMessage`, or `userMessage1`, ... when the case already uses that name. */
  private def fresh(c: Case, typeName: String): String = {
    val used = c.collect { case n: Name => n.value }.toSet
    val base = s"${typeName.head.toLower}${typeName.tail}"
    (Iterator.single(base) ++ Iterator.from(1).map(base + _)).find(n => !used.contains(n)).get
  }
}

object MessageExtractorToTypePattern {

  /** The 0.4.1 extractors' fields, in order. */
  private val messages: Map[String, List[String]] = Map(
    "org/llm4s/llmconnect/model/UserMessage" -> List("content"),
    "org/llm4s/llmconnect/model/ToolMessage" -> List("content", "toolCallId")
  )

  private val unsupported =
    "UserMessage and ToolMessage have no extractor since 0.5.1: match `case m: UserMessage` and read m.content: " +
      Migration.guide05("7-errors-cancellation-and-types")
}
