package org.llm4s.schema

import scala.quoted.*

/**
 * Compile-time helpers of the derivation. Scala 3's `Mirror` carries a type's field names and types but not its
 * annotations, and cannot walk a type to detect recursion or to see whether a sealed hierarchy has fields, so
 * those three things are done here, confined to this object.
 *
 * Internal to the module: `inline` code in [[SchemaOf]] expands at the user's call site, and the compiler gives it
 * the access it needs.
 */
private[schema] object SchemaMacros {

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

    // How deep one class may nest inside itself, with different type arguments, before it counts as recursive.
    val maxSameClassDepth = 16

    def isSingleton(child: Symbol): Boolean = child.isTerm || child.flags.is(Flags.Module)

    def recursive(name: String): Nothing =
      fail(
        s"Cannot derive SchemaOf[${TypeRepr.of[A].show}]: $name refers to itself. A recursive type " +
          "needs a JSON Schema $ref, which llm4s-core's schema model cannot express."
      )

    // `path` holds the case class types being walked, outermost last. Recursion is the same *type* again
    // (`Tree` inside `Tree`, `Node[Int]` inside `Node[Int]`), not the same class: `Box[Box[Int]]` nests `Box`
    // twice and is finite. A class that keeps reappearing with ever larger type arguments
    // (`case class W[A](next: Option[W[List[A]]])`) never repeats a type, so the walk also stops once one class
    // is nested `maxSameClassDepth` deep, which bounds the walk for every input.
    def walk(tpe: TypeRepr, path: List[TypeRepr]): Unit = {
      val t   = tpe.dealias
      val sym = t.typeSymbol
      t match {
        // List[Tree], Option[Tree] and the like: the type arguments are what matter
        case AppliedType(_, args) if sym.fullName.startsWith("scala.") => args.foreach(walk(_, path))
        case _ =>
          if (path.exists(_ =:= t) || path.count(_.typeSymbol == sym) >= maxSameClassDepth) {
            recursive(sym.name)
          } else if (sym.flags.is(Flags.Case) && sym.isClassDef) {
            sym.caseFields.foreach(field => walk(t.memberType(field), t :: path))
          } else if (sym.flags.is(Flags.Sealed)) {
            sym.children.foreach { child =>
              if (!isSingleton(child)) {
                fail(
                  s"Cannot derive SchemaOf[${TypeRepr.of[A].show}]: ${child.name} of ${sym.name} is not a " +
                    "singleton (it has fields, or is itself a hierarchy). A sealed hierarchy with fields needs a " +
                    "JSON Schema oneOf, which llm4s-core's schema model cannot express. Only enums and sealed " +
                    "hierarchies of singletons (case objects) are supported."
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
