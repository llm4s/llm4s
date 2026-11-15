package org.llm4s.prompt

import scala.quoted.*

/** The expansion of `prompt"..."` and `system"..."`: each value's `Promptable` is found, or the call does not compile. */
private[prompt] object PromptMacros {

  def interpolate(sc: Expr[StringContext], args: Expr[Seq[Any]], system: Boolean)(using Quotes): Expr[Prompt] = {
    import quotes.reflect.*

    val literals: Seq[String] = sc match {
      case '{ StringContext(${ Varargs(parts) }*) } =>
        parts.map { part =>
          val raw = part.valueOrAbort
          scala.util.Try(StringContext.processEscapes(raw)).getOrElse {
            report.errorAndAbort(s"Invalid escape sequence in the prompt template: $raw", part)
          }
        }
      case _ => report.errorAndAbort("The prompt template must be a string literal", sc)
    }

    val pieces: Seq[Expr[Prompt.Piece]] = args match {
      case Varargs(values) => values.map(piece)
      case _               => report.errorAndAbort("The prompt's values must be passed directly", args)
    }

    '{ Prompt.interpolate(${ Expr(system) }, ${ Expr(literals) }, ${ Expr.ofSeq(pieces) }) }
  }

  private def piece(value: Expr[Any])(using Quotes): Expr[Prompt.Piece] = {
    import quotes.reflect.*

    // `simplified` reduces a match type, such as the type of a named tuple's field.
    val tpe = value.asTerm.tpe.widen.simplified.dealiasKeepOpaques
    val untrusted = TypeRepr.of[Untrusted[Any]] match {
      case AppliedType(tycon, _) => tycon.typeSymbol
      case other                 => other.typeSymbol
    }
    tpe match {
      case AppliedType(tycon, List(content)) if tycon.typeSymbol == untrusted =>
        content.asType match {
          case '[a] =>
            Expr.summon[Promptable[a]] match {
              case Some(p) => '{ Prompt.Piece.Fenced($p.render(${ value.asExprOf[Untrusted[a]] }.value)) }
              case None    => missing(content, value)
            }
        }
      case _ =>
        tpe.asType match {
          case '[t] =>
            Expr.summon[Promptable[t]] match {
              case Some(p) => '{ Prompt.Piece.Value($p.render(${ value.asExprOf[t] })) }
              case None    => missing(tpe, value)
            }
        }
    }
  }

  private def missing(using Quotes)(tpe: quotes.reflect.TypeRepr, at: Expr[Any]): Nothing = {
    val name = tpe.show
    quotes.reflect.report.errorAndAbort(
      s"No Promptable[$name]: llm4s cannot tell how to write a $name into a prompt. Add `derives Promptable` to a " +
        s"case class or enum, define a `given Promptable[$name] = Promptable.instance(...)`, or interpolate a " +
        "field of it instead.",
      at
    )
  }
}
