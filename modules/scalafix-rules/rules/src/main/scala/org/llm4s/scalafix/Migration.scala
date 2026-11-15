package org.llm4s.scalafix

import scala.meta._
import scalafix.v1._

/** What the migration rules share: symbol normalisation, imports and links to the migration guides. */
private[scalafix] object Migration {

  /** The 0.4.1 to 0.5.0 guide, by section anchor. */
  def guide05(anchor: String): String =
    s"https://github.com/llm4s/llm4s/blob/main/docs/migrations/0-4-1-to-0-5-0.md#$anchor"

  private val memberSuffix = """(\.apply|\.unapply|#copy|#`<init>`)\((\+\d+)?\)\.$""".r

  /**
   * The class or object a call names, without the method it calls:
   * `org/llm4s/rag/loader/CrawlerConfig.apply().`, `org/llm4s/rag/loader/CrawlerConfig#copy().` and
   * `org/llm4s/rag/loader/CrawlerConfig#` are all `org/llm4s/rag/loader/CrawlerConfig`.
   */
  def owner(symbol: Symbol): String = owner(symbol.value)

  def owner(symbol: String): String =
    memberSuffix.replaceFirstIn(symbol, "").stripSuffix(".").stripSuffix("#")

  /**
   * The symbol of the method an application calls. Scala 3's SemanticDB records no occurrence for some calls, such as
   * the `copy` in `CompletionOptions().copy(maxTokens = Some(1))`, but it does for their named arguments
   * (`CompletionOptions#copy().(maxTokens)`), so the method is read from the first of those.
   */
  def callee(t: Term.Apply)(implicit doc: SemanticDocument): Option[String] =
    symbolOf(t.fun).map(_.value).orElse {
      t.argClause.values
        .collectFirst { case Term.Assign(n: Term.Name, _) => symbolOf(n) }
        .flatten
        .map(_.value)
        .collect {
          case parameter if parameter.endsWith(")") && parameter.contains(").(") =>
            parameter.substring(0, parameter.lastIndexOf("("))
        }
    }

  /** The tree's symbol, if SemanticDB recorded one for it. */
  def symbolOf(tree: Tree)(implicit doc: SemanticDocument): Option[Symbol] = {
    val s = tree.symbol
    if (s.isNone) None else Some(s)
  }

  /**
   * `import pkg.name`, or `import pkg.*` when `name` is `*`, unless the file already imports it (by name or a
   * wildcard) or is itself in `pkg`.
   */
  def importOnce(pkg: String, name: String)(implicit doc: SemanticDocument): Patch = {
    val present = doc.tree
      .collect {
        case i: Importer if i.ref.syntax == pkg =>
          i.importees.exists {
            case _: Importee.Wildcard => true
            case n: Importee.Name     => n.name.value == name
            case _                    => false
          }
        case p: Pkg if p.ref.syntax == pkg => true
      }
      .contains(true)
    if (present) Patch.empty
    else if (name == "*") wildcardImport(pkg)
    else
      Patch.addGlobalImport(
        Importer(pkg.parse[Term].get.asInstanceOf[Term.Ref], List(Importee.Name(Name.Indeterminate(name))))
      )
  }

  /**
   * `import pkg.*`, after the file's last top-level import (or its package clause). Written as text because Scalafix
   * prints a synthesised wildcard import in the Scala 2 form, `pkg._`.
   */
  private def wildcardImport(pkg: String)(implicit doc: SemanticDocument): Patch = {
    val text = s"import $pkg.*"
    def topLevel(stats: List[Stat]): Patch =
      stats.collect { case i: Import => i }.lastOption match {
        case Some(last) => Patch.addRight(last, s"\n$text")
        case None =>
          stats.collectFirst { case p: Pkg => p } match {
            case Some(p) => topLevel(p.stats)
            case None    => stats.headOption.fold(Patch.empty)(first => Patch.addLeft(first, s"$text\n\n"))
          }
      }
    doc.tree match {
      case source: Source => topLevel(source.stats)
      case _              => Patch.empty
    }
  }
}
