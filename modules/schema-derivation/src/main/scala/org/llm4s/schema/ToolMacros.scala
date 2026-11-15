package org.llm4s.schema

import org.llm4s.error.LLMError
import org.llm4s.toolapi.ToolFunction
import org.llm4s.types.Result
import upickle.default.{ ReadWriter, Reader }

import scala.quoted.*

/**
 * The macros behind [[Tool.fromMethod]] and [[Tools.derive]]: they read a method's parameters, result, annotations and
 * Scaladoc at compile time and generate a call to [[MethodTools.build]]. No reflection is left for run time.
 * Internal to the module.
 */
private[schema] object ToolMacros {

  def fromMethodImpl[F: Type](method: Expr[F])(using Quotes): Expr[Result[ToolFunction[?, ?]]] =
    new Deriver().fromMethod(method)

  def deriveImpl[S: Type](service: Expr[S])(using Quotes): Expr[Result[Seq[ToolFunction[?, ?]]]] =
    new Deriver().derive(service)

  /** Tool names every provider accepts. */
  private[schema] val ValidName = "[a-zA-Z0-9_-]{1,64}".r

  /** `findOrders` as `find_orders`, `getHTTPStatus` as `get_http_status`. */
  private[schema] def snakeCase(name: String): String =
    name
      .replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
      .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
      .toLowerCase

  /**
   * A Scaladoc comment's main text (everything before the first tag, its paragraphs separated by a blank line) and
   * its `@param` texts by parameter name. A link, `[[target text]]`, is written as its text, else its target.
   */
  private[schema] def scaladoc(comment: String): (Option[String], Map[String, String]) = {
    val lines = comment.trim
      .stripPrefix("/**")
      .stripSuffix("*/")
      .linesIterator
      .map(_.trim.stripPrefix("*").trim)
      .toList
    val (main, tagged) = lines.span(!_.startsWith("@"))
    // each tag with the lines that continue it
    val tags = tagged.foldLeft(List.empty[List[String]]) {
      case (acc, line) if line.startsWith("@") => List(line) :: acc
      case (current :: rest, line)             => (line :: current) :: rest
      case (Nil, _)                            => Nil
    }
    def text(ls: List[String]): String =
      ls.mkString("\n")
        .split("\n\\s*\n")
        .map(_.split("\\s+").filter(_.nonEmpty).mkString(" "))
        .filter(_.nonEmpty)
        .mkString("\n\n")
        .replaceAll("""\[\[([^\]\s]+)\s+([^\]]+)\]\]""", "$2")
        .replaceAll("""\[\[([^\]]+)\]\]""", "$1")
    val params = tags.reverse.map(_.reverse).flatMap { tag =>
      text(tag).split("\\s+", 3).toList match {
        case "@param" :: name :: description :: Nil => Some(name -> description)
        case _                                      => None
      }
    }
    (Some(text(main)).filter(_.nonEmpty), params.toMap)
  }

  final private class Deriver(using val q: Quotes) {
    import q.reflect.*

    private val descriptionAnnotation = Symbol.requiredClass("org.llm4s.schema.description")
    private val toolNameAnnotation    = Symbol.requiredClass("org.llm4s.schema.toolName")
    private val notAToolAnnotation    = Symbol.requiredClass("org.llm4s.schema.notATool")

    // the members every class has, which are never tools
    private val excludedOwners =
      Set("scala.Any", "java.lang.Object", "scala.Matchable", "scala.Product", "scala.Equals", "java.io.Serializable")

    private def show(t: TypeRepr): String = t.show(using Printer.TypeReprShortCode)

    private def stringArgument(symbol: Symbol, annotation: Symbol, where: String): Option[String] =
      symbol.getAnnotation(annotation).map {
        case Apply(_, List(Literal(StringConstant(text)))) => text
        case _ =>
          report.errorAndAbort(s"Cannot derive a tool from $where: @${annotation.name} must be given a string literal")
      }

    // The method and those it overrides, nearest first: a method without Scaladoc or annotations of its own takes
    // those of the method it implements.
    private def overridden(method: Symbol): List[Symbol] = method :: method.allOverriddenSymbols.toList

    private def annotated(method: Symbol, annotation: Symbol, at: String): Option[String] =
      overridden(method).flatMap(m => stringArgument(m, annotation, at)).headOption

    // ---- Tool.fromMethod

    def fromMethod[F: Type](method: Expr[F]): Expr[Result[ToolFunction[?, ?]]] = {
      val usage =
        s"Tool.fromMethod takes a method, eta-expanded (Tool.fromMethod(service.method)), not ${method.show}"

      def strip(t: Term): Term = t match {
        case Inlined(_, Nil, e) => strip(e)
        case Typed(e, _)        => strip(e)
        case Block(Nil, e)      => strip(e)
        case other              => other
      }

      // The lambda of the eta-expansion, possibly after the `val` that holds a qualifier that is not stable.
      def lambda(t: Term): (List[ValDef], Term) = strip(t) match {
        case Lambda(params, body) => (params, strip(body))
        case Block(_, e)          => lambda(e)
        case Inlined(_, _, e)     => lambda(e)
        case _                    => report.errorAndAbort(usage)
      }

      val (params, body) = lambda(method.asTerm)
      val (fun, args) = body match {
        case Apply(fun, args) => (fun, args)
        case _                => report.errorAndAbort(usage)
      }
      val forwarded = args.map(strip).collect { case i: Ident => i.symbol }
      val target    = fun.symbol
      fun match {
        case TypeApply(_, _) => polymorphic(where(target))
        case Apply(_, _)     => shape(target, where(target)); report.errorAndAbort(usage)
        case _               => ()
      }
      if (!target.isDefDef || forwarded != params.map(_.symbol)) report.errorAndAbort(usage)
      shape(target, where(target))

      // the method's type as seen from its qualifier
      val (names, types, result) = signature(fun.tpe.widen, target)
      // the function value itself is called, so the qualifier is evaluated as the eta-expansion evaluates it
      ValDef
        .let(Symbol.spliceOwner, "method", method.asTerm) { function =>
          tool(
            target,
            where(target),
            names,
            types,
            result,
            arguments => Select.unique(function, "apply").appliedToArgs(arguments)
          ).asTerm
        }
        .asExprOf[Result[ToolFunction[?, ?]]]
    }

    // ---- Tools.derive

    def derive[S: Type](service: Expr[S]): Expr[Result[Seq[ToolFunction[?, ?]]]] = {
      val owner = TypeRepr.of[S]
      val cls   = owner.typeSymbol
      if (!cls.isClassDef)
        report.errorAndAbort(s"Tools.derive[${show(owner)}] needs a trait or a class, whose methods are the tools")

      def hidden(m: Symbol): Boolean =
        m.isClassConstructor ||
          List(
            Flags.Synthetic,
            Flags.Private,
            Flags.Protected,
            Flags.PrivateLocal,
            Flags.ExtensionMethod,
            Flags.Artifact
          )
            .exists(m.flags.is) ||
          m.privateWithin.isDefined || m.protectedWithin.isDefined ||
          excludedOwners.contains(m.owner.fullName) || m.name.contains("$") ||
          overridden(m).exists(_.hasAnnotation(notAToolAnnotation))

      val methods = cls.methodMembers.filterNot(hidden)
      methods.groupBy(_.name).foreach { case (name, overloads) =>
        if (overloads.size > 1)
          report.errorAndAbort(
            s"Cannot derive a tool from ${cls.name}.$name: it is overloaded (${overloads.size} methods), and a model " +
              "cannot tell two tools of one name apart. Rename one, or mark all but one @notATool."
          )
      }
      val named = methods.map(m => (toolNameOf(m, s"${cls.name}.${m.name}"), m)).sortBy(_._1)
      named.groupBy(_._1).foreach { case (name, same) =>
        if (same.size > 1)
          report.errorAndAbort(
            s"Cannot derive tools from ${cls.name}: ${same.map(_._2.name).sorted.mkString(" and ")} would both be the tool " +
              s"$name. Give one a different @toolName."
          )
      }

      ValDef
        .let(Symbol.spliceOwner, "service", service.asTerm) { instance =>
          val tools = named.map { case (_, m) =>
            val at = s"${cls.name}.${m.name}"
            shape(m, at)
            val (names, types, result) = signature(owner.memberType(m), m)
            tool(
              m,
              at,
              names,
              types,
              result,
              arguments =>
                if (m.paramSymss.isEmpty) Select(instance, m) else Select(instance, m).appliedToArgs(arguments)
            )
          }
          '{ MethodTools.all(${ Expr.ofList(tools) }) }.asTerm
        }
        .asExprOf[Result[Seq[ToolFunction[?, ?]]]]
    }

    // ---- what both share

    private def where(method: Symbol): String = s"${method.owner.name.stripSuffix("$")}.${method.name}"

    private def polymorphic(at: String): Nothing =
      report.errorAndAbort(
        s"Cannot derive a tool from $at: it is polymorphic. A tool's parameters need one schema: " +
          "wrap it in a method with concrete types."
      )

    // Refuses a method whose parameter lists a tool cannot take.
    private def shape(method: Symbol, at: String): Unit = {
      val clauses = method.paramSymss
      if (clauses.exists(_.exists(_.isType))) polymorphic(at)
      if (clauses.exists(_.exists(p => p.flags.is(Flags.Given) || p.flags.is(Flags.Implicit))))
        report.errorAndAbort(
          s"Cannot derive a tool from $at: it takes a context parameter (using or implicit), which a model cannot " +
            "supply. Wrap it in a method that takes only the model's arguments."
        )
      if (clauses.size > 1)
        report.errorAndAbort(
          s"Cannot derive a tool from $at: it has ${clauses.size} parameter lists. A tool takes one: merge them."
        )
    }

    // The parameter names and types and the result type of `method`, seen as `info`.
    private def signature(info: TypeRepr, method: Symbol): (List[String], List[TypeRepr], TypeRepr) = info match {
      case MethodType(names, types, result) => (names, types, result)
      case ByNameType(result)               => (Nil, Nil, result)
      case _: PolyType                      => polymorphic(where(method))
      case other                            => (Nil, Nil, other)
    }

    private def toolNameOf(method: Symbol, at: String): String = {
      val name = annotated(method, toolNameAnnotation, at).getOrElse(snakeCase(method.name))
      if (!ValidName.matches(name))
        report.errorAndAbort(
          s"Cannot derive a tool from $at: its tool name '$name' is not 1 to 64 letters, digits, _ or -, which " +
            "providers require. Give it a @toolName(\"...\")."
        )
      name
    }

    private def search(of: TypeRepr): Either[String, Term] = Implicits.search(of) match {
      case found: ImplicitSearchSuccess  => Right(found.tree)
      case failed: ImplicitSearchFailure => Left(failed.explanation)
    }

    private def tool(
      method: Symbol,
      at: String,
      names: List[String],
      types: List[TypeRepr],
      result: TypeRepr,
      call: List[Term] => Term
    ): Expr[Result[ToolFunction[?, ?]]] = {
      val (summary, paramDocs) =
        overridden(method).flatMap(_.docstring).headOption.map(scaladoc).getOrElse((None, Map.empty[String, String]))
      val name        = toolNameOf(method, at)
      val description = annotated(method, descriptionAnnotation, at).orElse(summary).getOrElse(method.name)
      val descriptions = names.map { n =>
        overridden(method)
          .flatMap(_.paramSymss.flatten.find(p => p.isTerm && p.name == n))
          .flatMap(p => stringArgument(p, descriptionAnnotation, s"$at, parameter $n"))
          .headOption
          .orElse(paramDocs.get(n))
      }

      val (schemas, readers) = names
        .zip(types)
        .map { case (n, t) =>
          t match {
            case ByNameType(_) =>
              report.errorAndAbort(
                s"Cannot derive a tool from $at: parameter $n is by-name (=> ${show(t.widenByName)}). A model's " +
                  "argument is a value: take the parameter by value."
              )
            case _ if t.typeSymbol == defn.RepeatedParamClass =>
              report.errorAndAbort(
                s"Cannot derive a tool from $at: parameter $n is repeated (${show(t)}). Take a List instead."
              )
            case _ => ()
          }
          t.asType match {
            case '[p] =>
              val schema = search(TypeRepr.of[SchemaOf[p]]).fold(
                why =>
                  report.errorAndAbort(
                    s"Cannot derive a tool from $at: parameter $n has type ${show(t)}, which has no SchemaOf. $why"
                  ),
                _.asExprOf[SchemaOf[?]]
              )
              val reader = search(TypeRepr.of[Reader[p]]).fold(
                why =>
                  report.errorAndAbort(
                    s"Cannot derive a tool from $at: parameter $n has type ${show(t)}, which has no uPickle Reader. $why"
                  ),
                _.asExprOf[Reader[?]]
              )
              (schema, reader)
          }
        }
        .unzip

      // The method's result as `Either[String, R]`, and `R`.
      val either = TypeRepr.of[Either].typeSymbol
      val tryCls = TypeRepr.of[scala.util.Try].typeSymbol
      val (kind, value) = result.dealias match {
        case AppliedType(c, List(l, r)) if c.typeSymbol == either && l <:< TypeRepr.of[LLMError] => ("result", r)
        case AppliedType(c, List(l, r)) if c.typeSymbol == either && l =:= TypeRepr.of[String]   => ("either", r)
        case AppliedType(c, List(r)) if c.typeSymbol == tryCls                                   => ("try", r)
        case other                                                                               => ("value", other)
      }

      value.asType match {
        case '[r] =>
          val writer = search(TypeRepr.of[ReadWriter[r]]).fold(
            why =>
              report.errorAndAbort(
                s"Cannot derive a tool from $at: its result ${show(value)} has no uPickle ReadWriter. $why"
              ),
            _.asExprOf[ReadWriter[r]]
          )
          val invoke = Lambda(
            Symbol.spliceOwner,
            MethodType(List("arguments"))(_ => List(TypeRepr.of[List[Any]]), _ => TypeRepr.of[Either[String, r]]),
            { (owner, params) =>
              val arguments = params.head.asInstanceOf[Term].asExprOf[List[Any]]
              val decoded = types.zipWithIndex.map { case (t, i) =>
                t.asType match { case '[p] => '{ $arguments(${ Expr(i) }).asInstanceOf[p] }.asTerm }
              }
              val called = call(decoded)
              val wrapped = kind match {
                case "result" => '{ MethodTools.fromResult[r](${ called.asExprOf[Result[r]] }) }
                case "either" => called.asExprOf[Either[String, r]]
                case "try"    => '{ MethodTools.fromTry[r](${ called.asExprOf[scala.util.Try[r]] }) }
                case _        => '{ Right[String, r](${ called.asExprOf[r] }) }
              }
              wrapped.asTerm.changeOwner(owner)
            }
          ).asExprOf[List[Any] => Either[String, r]]

          '{
            MethodTools.build[r](
              ${ Expr(name) },
              ${ Expr(description) },
              ${ Expr(names) },
              ${ Expr.ofList(schemas) },
              ${ Expr.ofList(readers) },
              ${ Expr(descriptions) },
              $invoke
            )(using $writer)
          }
      }
    }
  }
}
