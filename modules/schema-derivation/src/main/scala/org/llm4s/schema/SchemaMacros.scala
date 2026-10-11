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

  /**
   * The `@upickle.implicits.key` of each case of the enum or sealed hierarchy `A`, by case name: the string uPickle
   * writes for that case instead of its name.
   */
  inline def caseKeys[A]: Map[String, String] = ${ caseKeysImpl[A] }

  /** The `@description` of the type `A` itself. */
  inline def typeDescription[A]: Option[String] = ${ typeDescriptionImpl[A] }

  /**
   * Fails compilation, naming the type, if `A` is a tuple or a named tuple, refers to itself or is (or holds) a
   * sealed hierarchy with fields. A type with a hand-written `given SchemaOf` of its own, other than the one being
   * derived, is not looked into: its schema is whatever that given says. When that given itself derives a type
   * that reaches back to `A`, the recursion is not visible here; building the schema then fails at run time
   * instead (see `SchemaOf.product`).
   */
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

  private def caseKeysImpl[A: Type](using q: Quotes): Expr[Map[String, String]] = {
    import q.reflect.*
    val annotation = Symbol.requiredClass("upickle.implicits.key")
    // An enum case without parameters is a term; a case object is a module, whose annotations sit on its class.
    def key(child: Symbol): Option[String] =
      List(child, child.moduleClass, child.companionModule)
        .filterNot(_.isNoSymbol)
        .flatMap(_.getAnnotation(annotation))
        .flatMap(a => stringArgument(a))
        .headOption
    val keys = TypeRepr.of[A].typeSymbol.children.flatMap { child =>
      key(child).map(written => child.name.stripSuffix("$") -> written)
    }
    Expr(keys.toMap)
  }

  private def typeDescriptionImpl[A: Type](using q: Quotes): Expr[Option[String]] = {
    import q.reflect.*
    val annotation = Symbol.requiredClass("org.llm4s.schema.description")
    Expr(TypeRepr.of[A].typeSymbol.getAnnotation(annotation).flatMap(a => stringArgument(a)))
  }

  private def checkImpl[A: Type](using q: Quotes): Expr[Unit] = {
    import q.reflect.*

    def fail(message: String): Nothing = report.errorAndAbort(message)

    val root = TypeRepr.of[A]

    // How deep one class may nest inside itself, with different type arguments, before it counts as recursive.
    val maxSameClassDepth = 16

    def isSingleton(child: Symbol): Boolean = child.isTerm || child.flags.is(Flags.Module)

    def recursive(name: String): Nothing =
      fail(
        s"Cannot derive SchemaOf[${root.show}]: $name refers to itself. A recursive type " +
          "needs a JSON Schema $ref, which llm4s-core's schema model cannot express."
      )

    if (root.dealias <:< TypeRepr.of[Tuple]) {
      fail(
        s"Cannot derive SchemaOf[${root.show}]: a tuple is written by uPickle as a JSON array of mixed types, " +
          "which llm4s-core's schema model cannot express. Use a case class with named fields."
      )
    }

    // A named tuple, `(a: Int, b: String)`, has a product Mirror whose labels are its names, so without this it
    // would derive an object schema; uPickle has no ReadWriter for it, so nothing could read the reply back.
    if (root <:< TypeRepr.of[NamedTuple.AnyNamedTuple] || root.dealias <:< TypeRepr.of[NamedTuple.AnyNamedTuple]) {
      fail(
        s"Cannot derive SchemaOf[${root.show}]: a named tuple has no uPickle ReadWriter to read the reply back " +
          "into. Use a case class with named fields."
      )
    }

    // The definitions being expanded: the `given` (or `derived$SchemaOf`) whose right-hand side this is.
    val enclosing: Set[Symbol] =
      Iterator.iterate(Symbol.spliceOwner)(_.owner).takeWhile(s => !s.isNoSymbol && s != defn.RootClass).toSet

    // A type is taken as described when a `given SchemaOf` of it is in scope that was written by hand: not one
    // that `derives SchemaOf` generated (it is derived the same way, so the walk goes on into it and finds
    // recursion through it), and not the one being defined, which would otherwise vouch for itself.
    def describedByAGiven(t: TypeRepr): Boolean =
      Implicits.search(TypeRepr.of[SchemaOf].appliedTo(t)) match {
        case found: ImplicitSearchSuccess =>
          val sym = found.tree.symbol
          !sym.isNoSymbol && !sym.name.startsWith("derived$") && !enclosing.contains(sym) &&
          sym.owner != TypeRepr.of[SchemaOf.type].typeSymbol
        case _ => false
      }

    // Types already walked to the end without a problem: walking them again would find nothing new, and without
    // this a type reached along many paths is walked once per path, which grows exponentially with the depth.
    val checked = scala.collection.mutable.ListBuffer.empty[TypeRepr]

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
        case _ if checked.exists(_ =:= t)                              => ()
        case _ if path.nonEmpty && describedByAGiven(t)                => ()
        case _ =>
          if (path.exists(_ =:= t) || path.count(_.typeSymbol == sym) >= maxSameClassDepth) {
            recursive(sym.name)
          } else if (sym.flags.is(Flags.Case) && sym.isClassDef) {
            sym.caseFields.foreach(field => walk(t.memberType(field), t :: path))
          } else if (sym.flags.is(Flags.Sealed)) {
            sym.children.foreach { child =>
              if (!isSingleton(child)) {
                fail(
                  s"Cannot derive SchemaOf[${root.show}]: ${child.name} of ${sym.name} is not a " +
                    "singleton (it has fields, or is itself a hierarchy). A sealed hierarchy with fields needs a " +
                    "JSON Schema oneOf, which llm4s-core's schema model cannot express. Only enums and sealed " +
                    "hierarchies of singletons (case objects) are supported."
                )
              }
            }
          }
          checked += t
      }
    }

    walk(root, Nil)
    '{ () }
  }
}
