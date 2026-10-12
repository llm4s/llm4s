import sbt._

import scala.xml.{ Elem, XML }

/**
 * The metadata in the POM of every published artifact: what Maven Central requires, and what Scaladex and the
 * IDE dependency pickers read.
 *
 * Maven Central rejects a release whose POM lacks a name, description, project URL, licence, developer or SCM
 * section, and it does so at publish time, after the tag is pushed. Scaladex finds a project only through the
 * POM's `<scm>`: it "associates a new artifact to a project by looking at the `scm` attribute in the pom file"
 * and supports only public GitHub repositories (https://github.com/scalacenter/scaladex#how-it-works). Both read
 * the POM as published, and a Maven Central release cannot be amended, so a wrong value ships for good.
 *
 * `check` reads the POMs sbt actually generates (`makePom`), not the settings, so an override in one module, a
 * POM post-processing rule or a plugin that rewrites the POM is caught as well. It covers every published
 * project: the `llm4s-*` artifacts, the BOM and the relocation stubs for the pre-0.4.0 coordinates.
 * `PomDescriptions` checks that each description is the module's own; this checks that it reaches the POM.
 */
object PomMetadata {

  /** The repository every published artifact must point at, in the form Scaladex parses: `https`, no trailing slash. */
  val RepositoryUrl = "https://github.com/llm4s/llm4s"

  private def text(e: Elem, path: String*): String =
    path.foldLeft(e: scala.xml.NodeSeq)((n, label) => n \ label).text.trim

  private def isHttpsUrl(s: String): Boolean = s.startsWith("https://") && !s.exists(_.isWhitespace)

  /** The problems with one generated POM, each prefixed with its artifactId. Empty when there are none. */
  def problems(pom: Elem): Seq[String] = {
    val artifactId  = text(pom, "artifactId")
    val id          = if (artifactId.isEmpty) "(no artifactId)" else artifactId
    val licenses    = pom \ "licenses" \ "license"
    val developers  = pom \ "developers" \ "developer"
    val scmUrl      = text(pom, "scm", "url")
    val connection  = text(pom, "scm", "connection")
    val description = text(pom, "description")
    val checks: Seq[(Boolean, String)] = Seq(
      (text(pom, "groupId") == "org.llm4s") -> s"<groupId> is '${text(pom, "groupId")}', not org.llm4s",
      artifactId.nonEmpty                   -> "no <artifactId>",
      text(pom, "name").nonEmpty            -> "no <name>",
      (description.nonEmpty && description != text(pom, "name") && description != artifactId) ->
        s"<description> is empty or only repeats the artifact name ('$description')",
      isHttpsUrl(text(pom, "url")) -> s"<url> is not an https URL ('${text(pom, "url")}')",
      licenses.nonEmpty            -> "no <licenses><license>",
      licenses.forall(l => (l \ "name").text.trim.nonEmpty && isHttpsUrl((l \ "url").text.trim)) ->
        "a <license> has no name or no https URL",
      developers.nonEmpty -> "no <developers><developer>",
      developers.forall(d => (d \ "id").text.trim.nonEmpty && (d \ "name").text.trim.nonEmpty) ->
        "a <developer> has no id or no name",
      (scmUrl == RepositoryUrl) ->
        s"<scm><url> is '$scmUrl', not $RepositoryUrl (Scaladex associates the artifact with a repository by it)",
      connection.startsWith(s"scm:git:$RepositoryUrl") ->
        s"<scm><connection> is '$connection', not scm:git:$RepositoryUrl[.git]"
    )
    checks.collect { case (false, problem) => s"$id: $problem" }
  }

  /**
   * Fails the build when a generated POM lacks the metadata above.
   *
   * @param poms the `makePom` output of every published project
   */
  def check(poms: Seq[File], log: Logger): Unit = {
    val found = poms.sortBy(_.getName).flatMap(f => problems(XML.loadFile(f)))
    log.info(s"POMs checked: ${poms.size}; problems: ${found.size}")
    // Logged one per line as well as in the failure, which sbt does not print for every kind of task.
    found.foreach(problem => log.error(s"  - $problem"))
    if (found.nonEmpty)
      sys.error(
        s"""${found.size} problem(s) with the metadata of the published POMs (see project/PomMetadata.scala):
           |${found.map("  - " + _).mkString("\n")}""".stripMargin
      )
  }
}
