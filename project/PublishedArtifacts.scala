import sbt._

import scala.io.Source
import scala.util.Using

/**
 * Every published artifact is named where a user and a maintainer look for it.
 *
 * `sbt ci-release` publishes the root aggregate as it exists on the tagged commit, and a Maven Central
 * release cannot be amended (`docs/reference/release.md`). A module can therefore ship without ever
 * having been given a stability tier or an install line: [[1.0 Scope]] lists packages, the installation
 * guide lists dependencies, and nothing connected either to what the build publishes. Four published
 * artifacts were in neither place when this check was written.
 *
 * `check` fails the build for a published `llm4s-*` artifact that is not named in
 * `docs/reference/v1-scope.md` (its tier) or in `docs/getting-started/installation.md` (how to depend on it).
 * An artifact is published when its project does not set `publish / skip`; the relocation stubs for the
 * pre-0.4.0 coordinates are not `llm4s-*` artifacts and are not checked.
 */
object PublishedArtifacts {

  /** An artifact name followed by anything but a name character, so `llm4s-agent` does not match `llm4s-agent-tools`. */
  private def mentions(text: String, artifact: String): Boolean =
    ("(?<![A-Za-z0-9-])" + java.util.regex.Pattern.quote(artifact) + "(?![A-Za-z0-9-])").r.findFirstIn(text).isDefined

  private def read(file: File): String = Using.resource(Source.fromFile(file, "UTF-8"))(_.mkString)

  /** The published `llm4s-*` artifacts missing from each document, as (artifact, in v1-scope, in installation). */
  def gaps(published: Seq[String], scope: String, installation: String): Seq[(String, Boolean, Boolean)] =
    published.sorted.map(a => (a, mentions(scope, a), mentions(installation, a))).filterNot { case (_, s, i) => s && i }

  /**
   * The coordinates a release must put on Maven Central, as (real artifacts, relocation stubs).
   *
   * A relocation stub redirects a pre-0.4.0 coordinate (`core`, `workspaceclient`, ...) to its `llm4s-*`
   * successor; it is a published project whose name does not start with `llm4s-`. Both get the Scala
   * binary suffix, since every module here is a Scala 3 artifact.
   */
  def coordinates(projects: Seq[(String, Boolean)], scalaBinaryVersion: String): (Seq[String], Seq[String]) = {
    val published     = projects.collect { case (name, false) => name }.distinct.sorted
    val (real, stubs) = published.partition(_.startsWith("llm4s-"))
    (real.map(n => s"${n}_$scalaBinaryVersion"), stubs.map(n => s"${n}_$scalaBinaryVersion"))
  }

  /**
   * @param projects (artifact name, whether `publish / skip` is set) for every project in the build
   */
  def check(projects: Seq[(String, Boolean)], root: File, log: Logger): Unit = {
    val published = projects.collect { case (name, false) if name.startsWith("llm4s-") => name }.distinct
    if (published.isEmpty)
      throw new MessageOnlyException("No published llm4s-* artifacts were found: the check read an empty project list.")

    val scopeFile   = root / "docs" / "reference" / "v1-scope.md"
    val installFile = root / "docs" / "getting-started" / "installation.md"
    val missing     = gaps(published, read(scopeFile), read(installFile))

    log.info(
      s"Published artifacts: ${published.size}; named in v1-scope.md and installation.md: ${published.size - missing.size}"
    )
    if (missing.nonEmpty) {
      val rows = missing.map { case (artifact, inScope, inInstall) =>
        val where = Seq(
          if (inScope) None else Some("v1-scope.md (no tier)"),
          if (inInstall) None else Some("installation.md (no install line)")
        ).flatten.mkString(" and ")
        s"  $artifact: missing from $where"
      }
      throw new MessageOnlyException(
        s"""${missing.size} published artifact(s) are not documented:
           |${rows.mkString("\n")}
           |A published module needs a row in docs/reference/v1-scope.md that gives its tier, and a
           |dependency line in docs/getting-started/installation.md. If it should not be published, set
           |`publish / skip := true` on its project.""".stripMargin
      )
    }
  }
}
