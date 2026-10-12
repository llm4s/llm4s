import sbt.Keys._
import sbt._

import scala.xml.transform.{ RewriteRule, RuleTransformer }
import scala.xml.{ Elem, Node => XmlNode, NodeSeq, XML }

/**
 * The `org.llm4s:llm4s-bom` artifact: a POM-only bill of materials that pins every published llm4s
 * artifact to one version, so a Maven or Gradle build that uses several of them (a provider, `llm4s-rag`,
 * `llm4s-observability`) states the version once and cannot end up with mismatched modules.
 *
 * It is generated, never listed by hand. The managed list is the real-artifact half of
 * `PublishedArtifacts.coordinates`, the same source `listPublishedArtifacts` and `scripts/verify-release.sh`
 * use, so a module added to the build is in the BOM the day it is added, and `bomCheck` fails the build when
 * the generated POM disagrees with that list.
 *
 * Two details are easy to get wrong:
 *   - A BOM is not a Scala library, so it has NO `_3` suffix: its coordinate is `org.llm4s:llm4s-bom`.
 *     The managed artifacts do carry it (`llm4s-core_3`), since Maven has no notion of Scala cross-versioning.
 *   - The publication must be `<packaging>pom</packaging>` with no jar, sources or javadoc, or resolvers
 *     look for a jar that was never built. The jar-producing tasks are switched off individually: a
 *     project-wide `publishArtifact := false` also switches off the POM (see `Relocation`). With them off,
 *     sbt writes `<packaging>pom</packaging>` itself (checked at sbt 1.12.12: it writes `jar` when
 *     `packageBin` is left on), so no POM rewrite is needed for it; `bomCheck` fails if that changes.
 */
object Bom {

  private val Group = "org.llm4s"

  /**
   * Settings that make a project publish a POM and nothing else, under an unsuffixed coordinate. The project's
   * `name` (`llm4s-bom`) is set in build.sbt, where the build guards read project names.
   */
  def settings: Seq[Def.Setting[_]] = Seq(
    description :=
      "Bill of materials for LLM4S: import it to use every llm4s-* artifact at one version " +
        "without repeating the version. This artifact contains no code.",
    autoScalaLibrary                       := false,
    crossPaths                             := false,
    libraryDependencies                    := Seq.empty,
    Compile / packageBin / publishArtifact := false,
    Compile / packageSrc / publishArtifact := false,
    Compile / packageDoc / publishArtifact := false
  ) ++
    // Nothing to compile, so nothing to instrument; `coveragePolicyCheck` refuses the undeclared default.
    Coverage.coverageDisabled

  /**
   * Rewrites the POM sbt wrote at `pom` so it manages `artifactIds` at `version`.
   *
   * @param artifactIds
   *   the full Maven artifactIds, Scala suffix included (`llm4s-core_3`)
   */
  def writeManagedDependencies(pom: File, artifactIds: Seq[String], version: String): File = {
    val entries: Seq[Elem] = artifactIds.sorted.map { id =>
      <dependency>
        <groupId>{Group}</groupId>
        <artifactId>{id}</artifactId>
        <version>{version}</version>
      </dependency>
    }
    val managed =
      <dependencyManagement>
        <dependencies>{entries}</dependencies>
      </dependencyManagement>

    val rule = new RewriteRule {
      override def transform(n: XmlNode): NodeSeq = n match {
        case e: Elem if e.label == "project" => e.copy(child = e.child ++ managed)
        case other                           => other
      }
    }
    val updated = new RuleTransformer(rule).transform(XML.loadFile(pom)).head
    XML.save(pom.getPath, updated, "UTF-8", xmlDecl = true)
    pom
  }
}
