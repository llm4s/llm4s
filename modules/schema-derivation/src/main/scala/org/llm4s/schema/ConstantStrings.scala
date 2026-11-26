package org.llm4s.schema

import scala.quoted.*

/**
 * Reads the string an annotation was given, at compile time (#2951). An annotation's argument reaches a macro as the
 * tree that was written, not as a value, so the text is either a constant the compiler has already worked out (the
 * argument's type is that constant: a literal, a `final val` or `inline val`, a `+` of those) or one worked out here
 * (an `s"..."` whose arguments are all constants, which the compiler folds only after macros have run). An argument
 * that is neither is a compile error, never an annotation silently ignored.
 *
 * Internal to the module's macros.
 */
private[schema] object ConstantStrings {

  /** What an annotation's argument may be, for an error message. */
  val Accepted: String =
    "a string literal, a `final val` or `inline val` declared without a type, a `+` of such constants, " +
      "or an s\"...\" interpolation whose arguments are all such constants"

  /**
   * The string the annotation `annotation` (`new description(...)`, as a symbol's `getAnnotation` returns it) was
   * given. Fails compilation when it is not a constant: `context` says what was being derived and `of` what the
   * annotation is on. The error is placed at the annotation when that is in the file being compiled.
   */
  def read(using q: Quotes)(annotation: q.reflect.Term, context: String, of: String): String = {
    import q.reflect.*
    val (name, written) = annotation match {
      case Apply(constructor, List(argument)) => (constructor.symbol.owner.name, Some(argument))
      case _                                  => (annotation.tpe.typeSymbol.name, None)
    }
    written.flatMap(argument => value(argument)).collect { case text: String => text }.getOrElse {
      val code = written.fold("")(argument => s": ${argument.show(using Printer.TreeShortCode)}")
      val message = s"$context: the @$name of $of is not a constant string$code. " +
        s"It is read at compile time, so it must be $Accepted."
      val here = Position.ofMacroExpansion
      if (annotation.pos.sourceFile.path == here.sourceFile.path) report.errorAndAbort(message, annotation.pos)
      else report.errorAndAbort(message)
    }
  }

  // The constant `term` stands for: a string or a primitive, as it may be one side of a `+` or an interpolated argument
  private def value(using q: Quotes)(term: q.reflect.Term): Option[Any] = {
    import q.reflect.*

    def constant(c: Constant): Option[Any] = c.value match {
      case v @ (_: String | _: Int | _: Long | _: Float | _: Double | _: Boolean | _: Char | _: Short | _: Byte) =>
        Some(v)
      case _ => None
    }

    // A constant the compiler has folded is in the term's type
    def folded: Option[Any] = term.tpe.widenTermRefByName.dealias match {
      case ConstantType(c) => constant(c)
      case _               => None
    }

    def interpolated: Option[Any] = term.asExpr match {
      case '{ StringContext(${ Varargs(parts) }*).s(${ Varargs(arguments) }*) } if parts.size == arguments.size + 1 =>
        for {
          texts  <- sequence(parts.map(part => value(part.asTerm).collect { case s: String => s }))
          values <- sequence(arguments.map(argument => value(argument.asTerm)))
          // `s` reads the escapes of each part when it runs, and fails on one that is not an escape
          read <- scala.util.Try(texts.map(StringContext.processEscapes)).toOption
        } yield read.head + values.zip(read.tail).map { case (v, text) => s"$v$text" }.mkString
      case _ => None
    }

    term match {
      case Inlined(_, Nil, inner) => value(inner)
      case Typed(inner, _)        => value(inner)
      case NamedArg(_, inner)     => value(inner)
      case Block(Nil, inner)      => value(inner)
      case Literal(c)             => constant(c)
      case Apply(Select(left, "+"), List(right)) =>
        folded.orElse(value(left).collect { case s: String => s }.flatMap(s => value(right).map(v => s"$s$v")))
      case _ => folded.orElse(interpolated)
    }
  }

  private def sequence[A](options: Seq[Option[A]]): Option[List[A]] =
    if (options.forall(_.isDefined)) Some(options.flatten.toList) else None
}
