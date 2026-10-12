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
 * An artifact is published when its project does not set `publish / skip`, or when it is one of the [[Gradle]]
 * artifacts another build publishes on the same release; the relocation stubs for the pre-0.4.0 coordinates are not
 * `llm4s-*` artifacts and are not checked.
 */
object PublishedArtifacts {

  /**
   * Published artifacts that contain no Scala code and so carry no Scala binary suffix: the BOM
   * (`org.llm4s:llm4s-bom`). Every other `llm4s-*` project is a Scala 3 artifact published as `<name>_3`.
   * The BOM is listed apart from them (`boms`), because a release must find its POM but no jar.
   */
  val Unsuffixed: Set[String] = Set("llm4s-bom")

  /**
   * Artifacts the release publishes from a Gradle build rather than from sbt, by Maven artifactId:
   * `org.llm4s:llm4s-kotlin-api` (`modules/kotlin-api`, its `publishing` block). They are Kotlin, so they carry no Scala
   * suffix, and no sbt project knows them, so they are named here: `listPublishedArtifacts` prints them as `gradle`
   * lines for `scripts/verify-release.sh`, the BOM manages them, and `check` requires them to be documented like any
   * other published artifact. release.yml publishes them at the version sbt publishes the rest at, and
   * `scripts/check-kotlin-publication.sh` (CI) builds a project that takes `llm4s-kotlin-api`'s version from the BOM, so
   * a rename on either side fails CI.
   */
  val Gradle: Seq[String] = Seq("llm4s-kotlin-api")

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
    (
      real.filterNot(Unsuffixed).map(n => s"${n}_$scalaBinaryVersion"),
      stubs.map(n => s"${n}_$scalaBinaryVersion")
    )
  }

  /** The published POM-only BOM artifacts, by Maven artifactId (they have no Scala suffix). */
  def boms(projects: Seq[(String, Boolean)]): Seq[String] =
    projects.collect { case (name, false) if Unsuffixed(name) => name }.distinct.sorted

  /**
   * What a BOM must manage: every real artifact of the release, as its full Maven artifactId. It is the
   * `real` half of [[coordinates]], so a module added to the build is in the BOM the day it is added, and the
   * [[Gradle]] artifacts, which are released at the same version.
   */
  def managed(projects: Seq[(String, Boolean)], scalaBinaryVersion: String): Seq[String] =
    (coordinates(projects, scalaBinaryVersion)._1 ++ Gradle).sorted

  /** The ways a generated BOM POM disagrees with what the release publishes, empty when it agrees. */
  def bomProblems(expected: Seq[String], bom: scala.xml.Elem, version: String): Seq[String] = {
    def text(n: scala.xml.NodeSeq): String = n.text.trim
    val managedDeps = (bom \ "dependencyManagement" \ "dependencies" \ "dependency").map { d =>
      (text(d \ "groupId"), text(d \ "artifactId"), text(d \ "version"))
    }
    val ids          = managedDeps.map(_._2)
    val missing      = expected.filterNot(ids.contains).sorted
    val extra        = ids.filterNot(expected.contains).sorted
    val duplicated   = ids.diff(ids.distinct).distinct.sorted
    val wrongGroup   = managedDeps.collect { case (g, a, _) if g != "org.llm4s" => s"$a (group $g)" }
    val wrongVersion = managedDeps.collect { case (_, a, v) if v != version => s"$a ($v)" }
    val ownDeps      = (bom \ "dependencies" \ "dependency").size

    Seq(
      if (text(bom \ "packaging") == "pom") None else Some(s"packaging is '${text(bom \ "packaging")}', not 'pom'"),
      if (Unsuffixed(text(bom \ "artifactId"))) None
      else Some(s"artifactId is '${text(bom \ "artifactId")}', expected one of ${Unsuffixed.mkString(", ")}"),
      if (ownDeps == 0) None else Some("the BOM declares dependencies of its own; it must only manage versions"),
      if (missing.isEmpty) None else Some(s"missing from the BOM: ${missing.mkString(", ")}"),
      if (extra.isEmpty) None else Some(s"in the BOM but not published: ${extra.mkString(", ")}"),
      if (duplicated.isEmpty) None else Some(s"listed more than once: ${duplicated.mkString(", ")}"),
      if (wrongGroup.isEmpty) None else Some(s"not in group org.llm4s: ${wrongGroup.mkString(", ")}"),
      if (wrongVersion.isEmpty) None else Some(s"not at version $version: ${wrongVersion.mkString(", ")}")
    ).flatten
  }

  /** Fails the build when the generated BOM POM does not list exactly the artifacts the release publishes. */
  /**
   * Published projects that ship a jar, by Maven artifactId, decided without looking at their names: `publish / skip`
   * off and `Compile / packageBin / publishArtifact` on. Relocation stubs and the BOM ship a POM only, so they are not
   * here. [[managed]] decides by name (`llm4s-*`), so comparing the two catches a code-carrying module that would be
   * left out of the BOM because its name does not follow the convention.
   *
   * @param projects
   *   (name, publish / skip, Compile / packageBin / publishArtifact, crossPaths) of every project in the build
   */
  def jarPublishers(projects: Seq[(String, Boolean, Boolean, Boolean)], scalaBinaryVersion: String): Seq[String] =
    projects
      .collect { case (name, false, true, cross) => if (cross) s"${name}_$scalaBinaryVersion" else name }
      .distinct
      .sorted

  /** The ways the name-based [[managed]] list disagrees with the jar-based [[jarPublishers]] list. */
  def namingProblems(managedIds: Seq[String], jarIds: Seq[String]): Seq[String] = {
    val unmanaged = jarIds.filterNot(managedIds.contains).sorted
    // a Gradle artifact ships a jar, but not from an sbt project
    val jarless = managedIds.filterNot(id => jarIds.contains(id) || Gradle.contains(id)).sorted
    Seq(
      if (unmanaged.isEmpty) None
      else Some(s"published with a jar but not in the BOM (is its name llm4s-*?): ${unmanaged.mkString(", ")}"),
      if (jarless.isEmpty) None else Some(s"in the BOM but published without a jar: ${jarless.mkString(", ")}")
    ).flatten
  }

  def checkBom(
    projects: Seq[(String, Boolean, Boolean, Boolean)],
    scalaBinaryVersion: String,
    pom: File,
    version: String,
    log: Logger
  ): Unit = {
    val expected = managed(projects.map(p => (p._1, p._2)), scalaBinaryVersion)
    if (expected.isEmpty)
      throw new MessageOnlyException("No published llm4s-* artifacts were found: the check read an empty project list.")
    val problems = bomProblems(expected, scala.xml.XML.loadFile(pom), version) ++
      namingProblems(expected, jarPublishers(projects, scalaBinaryVersion))
    log.info(s"BOM ${pom.getName}: ${expected.size} published artifacts expected, ${problems.size} problem(s)")
    if (problems.nonEmpty)
      throw new MessageOnlyException(
        s"""The generated BOM (${pom.getPath}) disagrees with what the build publishes:
           |${problems.map("  " + _).mkString("\n")}
           |The BOM is generated from the same project list as `listPublishedArtifacts` (project/Bom.scala);
           |a mismatch means that generator was changed, sbt stopped writing the managed block, or a module that
           |publishes a jar is not named llm4s-*.""".stripMargin
      )
  }

  /**
   * @param projects (artifact name, whether `publish / skip` is set) for every project in the build
   */
  def check(projects: Seq[(String, Boolean)], root: File, log: Logger): Unit = {
    val published = (projects.collect { case (name, false) if name.startsWith("llm4s-") => name } ++ Gradle).distinct
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
