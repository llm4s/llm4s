import sbt.Keys._
import sbt._

import scala.xml.transform.{ RewriteRule, RuleTransformer }
import scala.xml.{ Elem, Node => XmlNode, NodeSeq }

/**
 * Maven *relocation* stubs for artifacts renamed in 0.4.0.
 *
 * 0.4.0 renamed every published coordinate (`core` -> `llm4s-core` and friends). The old
 * coordinates stop at 0.3.4 and will never gain another version, so anyone who bumps
 * `"org.llm4s" %% "core" % "0.4.0"` gets a bare unresolved-dependency error with no hint
 * that the artifact simply moved.
 *
 * A relocation stub is a POM-only publication of the OLD coordinate at the new version,
 * carrying `<distributionManagement><relocation>` pointing at the new coordinate. Resolvers
 * follow it, so the build gets the renamed artifact instead of an unresolved-dependency
 * error.
 *
 * Scope, honestly stated - two things this does NOT do:
 *   - it does nothing for a build pinned to 0.3.4 forever. That build resolves 0.3.4 forever
 *     and sees no signal at all; no mechanism in Maven can reach that user.
 *   - coursier (sbt's resolver) follows the relocation SILENTLY. Maven prints "has been
 *     relocated"; coursier prints nothing, so an sbt user's build just starts working again
 *     without being told to update the coordinate. Verified locally at sbt 1.12.12 - not a
 *     reason to skip this, but the redirect is the whole benefit, not the notice.
 *
 * Two details are easy to get wrong:
 *   - the relocation target's `artifactId` must be the FULL Maven artifactId including the
 *     Scala suffix (`llm4s-core_3`). Maven has no notion of Scala cross-versioning, so the
 *     suffix is part of the name as far as the relocation is concerned.
 *   - the publication must be `<packaging>pom</packaging>` with no jar/sources/javadoc, or
 *     resolvers will look for a jar that was never built.
 */
object Relocation {

  /** Group id of both the old and the new coordinates. */
  private val Group = "org.llm4s"

  /**
   * Settings for one relocation stub.
   *
   * @param oldName
   *   the sbt `name` of the retired module, i.e. the old Maven artifactId WITHOUT the Scala
   *   suffix (`crossPaths` re-adds `_3`).
   * @param newArtifactId
   *   the FULL new Maven artifactId INCLUDING the Scala suffix (`llm4s-core_3`).
   */
  def settings(oldName: String, newArtifactId: String): Seq[Def.Setting[_]] = Seq(
    name := oldName,
    description :=
      s"Relocation stub: $Group:$oldName was renamed to $Group:$newArtifactId in 0.4.0. " +
        "This artifact contains no code; it exists only to redirect resolvers to the new coordinate.",
    // No sources, no Scala. `crossPaths` still appends the `_3` suffix (it reads
    // `scalaBinaryVersion`, not the library dependency), which is what makes the published
    // coordinate `core_3` rather than `core`.
    autoScalaLibrary    := false,
    crossPaths          := true,
    libraryDependencies := Seq.empty,
    // POM-only. sbt reads `publishArtifact` scoped to each packaging task, so the three
    // jar-producing tasks are switched off individually and `makePom` is left alone.
    // Do NOT use a project-wide `publishArtifact := false` here: that is what `makePom`
    // falls back to as well, and the result is a `publishM2` that silently publishes
    // nothing at all (verified).
    Compile / packageBin / publishArtifact := false,
    Compile / packageSrc / publishArtifact := false,
    Compile / packageDoc / publishArtifact := false,
    pomPostProcess                         := relocate(newArtifactId, version.value)
  ) ++
    // Nothing to compile, so nothing to instrument. Declared explicitly because
    // `coveragePolicyCheck` refuses to let any module sit on the undeclared default.
    Coverage.coverageDisabled

  /**
   * Rewrites the generated POM into a relocation POM: forces `<packaging>pom</packaging>`
   * and appends the `<distributionManagement><relocation>` block.
   */
  private def relocate(newArtifactId: String, newVersion: String)(node: XmlNode): XmlNode = {
    val message =
      s"Renamed to $Group:$newArtifactId in 0.4.0. Update your build to depend on the new artifact."

    val distributionManagement =
      <distributionManagement>
        <relocation>
          <groupId>{Group}</groupId>
          <artifactId>{newArtifactId}</artifactId>
          <version>{newVersion}</version>
          <message>{message}</message>
        </relocation>
      </distributionManagement>

    val rule = new RewriteRule {
      override def transform(n: XmlNode): NodeSeq = n match {
        // sbt emits `<packaging>jar</packaging>` because the project *could* build a jar.
        case e: Elem if e.label == "packaging" =>
          e.copy(child = scala.xml.Text("pom"))
        case e: Elem if e.label == "project" =>
          e.copy(child = e.child ++ distributionManagement)
        case other => other
      }
    }
    new RuleTransformer(rule).transform(node).head
  }
}
