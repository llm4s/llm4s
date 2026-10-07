package org.llm4s.schema

import scala.quoted.*

/**
 * Compile-time helpers of the derivation. Scala 3's `Mirror` carries a type's field names and types but not its
 * annotations, and cannot walk a type to detect recursion or to see whether a sealed hierarchy has fields, so
 * those three things are done here, confined to this object.
 *
 * Internal: it is public only because `inline` code in [[SchemaOf]] expands at the user's call site.
 */
object SchemaMacros {

  /** The `@description` of each constructor parameter of `A`, in declaration order. */
  inline def fieldDescriptions[A]: List[Option[String]] = ${ fieldAnnotationsImpl[A]("org.llm4s.schema.description") }

  /** The `@upickle.implicits.key` of each constructor parameter of `A`: the name uPickle writes the field under. */
  inline def fieldKeys[A]: List[Option[String]] = ${ fieldAnnotationsImpl[A]("upickle.implicits.key") }

  /** The `@description` of the type `A` itself. */
  inline def typeDescription[A]: Option[String] = ${ typeDescriptionImpl[A] }

  /** Fails compilation, naming the type, if `A` refers to itself or is a sealed hierarchy with fields. */
  inline def check[A]: Unit = ${ checkImpl[A] }

  private def stringArgument(using q: Quotes)(annotation: q.reflect.Term): Option[String] = {
    import q.reflect.*
    annotation match {
      case Apply(_, List(Literal(StringConstant(text)))) => Some(text)
      case _                                             => None
    }
  }

  private def fieldAnnotationsImpl[A: Type](annotationClass: String)(using q: Quotes): Expr[List[Option[String]]] = {
    import q.reflect.*
    val annotation = Symbol.requiredClass(annotationClass)
    val parameters = TypeRepr.of[A].typeSymbol.primaryConstructor.paramSymss.flatten.filterNot(_.isType)
    Expr.ofList(parameters.map(p => Expr(p.getAnnotation(annotation).flatMap(a => stringArgument(a)))))
  }

  private def typeDescriptionImpl[A: Type](using q: Quotes): Expr[Option[String]] = {
    import q.reflect.*
    val annotation = Symbol.requiredClass("org.llm4s.schema.description")
    Expr(TypeRepr.of[A].typeSymbol.getAnnotation(annotation).flatMap(a => stringArgument(a)))
  }

  private def checkImpl[A: Type](using q: Quotes): Expr[Unit] = {
    import q.reflect.*

    def fail(message: String): Nothing = report.errorAndAbort(message)

    def isSingleton(child: Symbol): Boolean = child.isTerm || child.flags.is(Flags.Module)

    def walk(tpe: TypeRepr, path: List[Symbol]): Unit = {
      val t   = tpe.dealias
      val sym = t.typeSymbol
      t match {
        // List[Tree], Option[Tree] and the like: the type arguments are what matter
        case AppliedType(_, args) if sym.fullName.startsWith("scala.") => args.foreach(walk(_, path))
        case _ =>
          if (path.contains(sym)) {
            fail(
              s"Cannot derive SchemaOf[${TypeRepr.of[A].show}]: ${sym.name} refers to itself. A recursive type " +
                "needs a JSON Schema $ref, which llm4s-core's schema model cannot express."
            )
          } else if (sym.flags.is(Flags.Case) && sym.isClassDef) {
            sym.caseFields.foreach(field => walk(t.memberType(field), sym :: path))
          } else if (sym.flags.is(Flags.Sealed)) {
            sym.children.foreach { child =>
              if (!isSingleton(child)) {
                fail(
                  s"Cannot derive SchemaOf[${TypeRepr.of[A].show}]: ${child.name} of ${sym.name} has fields. " +
                    "A sealed hierarchy with fields needs a JSON Schema oneOf, which llm4s-core's schema model " +
                    "cannot express. Only enums and sealed hierarchies of singletons (case objects) are supported."
                )
              }
            }
          }
      }
    }

    walk(TypeRepr.of[A], Nil)
    '{ () }
  }
}
