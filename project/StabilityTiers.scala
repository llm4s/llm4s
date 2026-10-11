import sbt._

import scala.io.Source
import scala.util.Using

/**
 * The stability tier of a frozen module's public types, recorded in the code.
 *
 * `docs/reference/v1-scope.md` says which packages 1.0 freezes. That page drifts from the code it
 * describes, so every top-level public type of a frozen module carries `@Stable` (covered by the
 * 1.x compatibility promise) or `@Experimental` (not covered), from `org.llm4s.annotation` in
 * `llm4s-core`. A Stable companion shares its type's tier; an Experimental companion needs its own annotation
 * because MiMa reads the separate companion class.
 *
 * `check` fails the build for a type that has neither annotation, for one that has both, and for a
 * file the build lists as an exception (a Beta dialect inside a frozen module) whose types are not
 * `@Experimental`. It reads sources, not classes: Scala compiles `private[llm4s]` to a public
 * bytecode member, so the compiled classes cannot say which types are public API.
 */
object StabilityTiers {

  final case class Violation(file: File, line: Int, text: String, problem: String)

  private val Modifier = "(?:(?:final|sealed|abstract|case|implicit|open|transparent|inline|infix)\\s+)*"
  private val Decl =
    ("^(?:@\\w+(?:\\([^)]*\\))?\\s+)*" + Modifier + "(class|trait|object|enum)\\s+([A-Za-z_]\\w*)").r
  private val NonPublic       = "^(?:@\\w+(?:\\([^)]*\\))?\\s+)*(?:private|protected)(?:\\[\\w+\\])?\\s".r
  private val StableAnn       = "^\\s*(?:@\\w+(?:\\([^)]*\\))?\\s+)*@Stable\\b".r
  private val ExperimentalAnn = "^\\s*(?:@\\w+(?:\\([^)]*\\))?\\s+)*@Experimental\\b".r

  private def sources(dir: File): Seq[File] =
    if (!dir.exists) Nil else (dir ** "*.scala").get.sortBy(_.getPath)

  /** Top-level public types of one file, as (zero-based line, kind, name), Stable companions removed. */
  private[this] def declarations(lines: Vector[String]): Seq[(Int, String, String)] = {
    val found = lines.zipWithIndex.collect {
      case (l, i)
          if l.nonEmpty && !l.head.isWhitespace && !l
            .startsWith("package object") && NonPublic.findFirstIn(l).isEmpty =>
        Decl.findFirstMatchIn(l).map(m => (i, m.group(1), m.group(2)))
    }.flatten
    val typed = found.collect {
      case (i, kind, name) if kind != "object" && !annotated(lines, i, ExperimentalAnn) => name
    }.toSet
    found.filterNot { case (_, kind, name) => kind == "object" && typed.contains(name) }
  }

  /** Whether the annotation is on the declaration's own line or on the annotation lines just above it. */
  private[this] def annotated(lines: Vector[String], at: Int, ann: scala.util.matching.Regex): Boolean = {
    def above(i: Int): Boolean =
      i >= 0 && lines(i).trim.startsWith("@") && (ann.findFirstIn(lines(i)).nonEmpty || above(i - 1))
    ann.findFirstIn(lines(at)).nonEmpty || above(at - 1)
  }

  /**
   * @param modules         the frozen modules' base directories (`modules/<name>`)
   * @param experimentalIn  regexes over a file's path: its types must be `@Experimental`
   */
  def violations(
    modules: Seq[File],
    experimentalIn: Seq[scala.util.matching.Regex]
  ): (Seq[Violation], Map[String, (Int, Int)]) = {
    val perModule = modules.map { module =>
      val results = sources(module / "src" / "main" / "scala").flatMap { file =>
        val lines              = Using.resource(Source.fromFile(file, "UTF-8"))(_.getLines().toVector)
        val mustBeExperimental = experimentalIn.exists(_.findFirstIn(file.getPath).nonEmpty)
        declarations(lines).map { case (i, _, _) =>
          val stable       = annotated(lines, i, StableAnn)
          val experimental = annotated(lines, i, ExperimentalAnn)
          val problem =
            if (stable && experimental) Some("carries both @Stable and @Experimental")
            else if (!stable && !experimental) Some("carries neither @Stable nor @Experimental")
            else if (mustBeExperimental && !experimental)
              Some("is in a file the build lists as Experimental but is @Stable")
            else None
          (problem.map(Violation(file, i + 1, lines(i).trim, _)), stable, experimental)
        }
      }
      module.getName -> ((results.count(_._2), results.count(_._3), results.flatMap(_._1)))
    }
    (perModule.flatMap(_._2._3), perModule.map { case (m, (s, e, _)) => m -> (s -> e) }.toMap)
  }

  def check(root: File, moduleNames: Seq[String], experimentalIn: Seq[scala.util.matching.Regex], log: Logger): Unit = {
    val (bad, counts) = violations(moduleNames.map(n => root / "modules" / n), experimentalIn)
    log.info("Stability tier per frozen module (top-level public types):")
    counts.toSeq.sortBy(_._1).foreach { case (m, (s, e)) => log.info(f"  $m%-20s $s%4d @Stable  $e%3d @Experimental") }
    if (bad.nonEmpty) {
      val rows =
        bad.map(v => s"  ${v.file.relativeTo(root).getOrElse(v.file)}:${v.line}  ${v.text.take(80)}  ${v.problem}")
      throw new MessageOnlyException(
        s"""${bad.size} top-level public type(s) of a frozen module have no valid stability tier:
           |${rows.mkString("\n")}
           |Every top-level public type of a frozen module needs exactly one of
           |  import org.llm4s.annotation.Stable        // covered by the 1.x compatibility promise
           |  import org.llm4s.annotation.Experimental  // not covered; also required for the files the build lists
           |above its declaration (an Experimental companion needs its own annotation). A type that is
           |private or private[x] needs neither. See docs/reference/api-stability.md.""".stripMargin
      )
    }
  }
}
