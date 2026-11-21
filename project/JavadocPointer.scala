import sbt._

import java.util.zip.ZipFile
import scala.jdk.CollectionConverters._
import scala.util.Using

/**
 * The `-javadoc.jar` every published module ships: a pointer to the site's Scaladoc, not the module's own Scaladoc.
 *
 * Maven Central requires a javadoc jar beside each jar, but not what is in it. A module's own Scaladoc jar repeated
 * about 6 MB of Scaladoc's static assets (fonts, scripts, images): the 0.6.0 javadoc jars were 165 MB of a 193 MB
 * upload, enough to hit Sonatype's monthly release-size limit for the organisation. The full Scaladoc, of every module
 * in one site with cross-links and source links at the release tag, is the aggregate `docs` project's, published at
 * [[SiteUrl]] by the Pages workflow; an IDE reads the sources jar.
 *
 * `Compile / packageDoc / mappings` is set to [[mappings]] in `commonSettings`, so packaging the jar no longer runs
 * `Compile / doc`: `sbt doc` and `sbt docs/doc` still build the Scaladoc, and a release still checks its warnings
 * (release.yml). `javadocJarCheck` ([[check]]) fails the build when a published module's javadoc jar is not this
 * pointer, or is not published.
 */
object JavadocPointer {

  val SiteUrl = "https://llm4s.org/scaladoc/"

  /** A javadoc jar above this is not the pointer: the smallest module's own Scaladoc jar was about 3 MB. */
  val MaxBytes: Long = 50L * 1024

  /** The entries of the pointer jar. [[check]] requires both. */
  val Entries: Seq[String] = Seq("README.md", "index.html")

  /** The marker [[check]] looks for in each entry. */
  private val Marker = "llm4s-javadoc-pointer"

  def readme(artifact: String, version: String): String =
    s"""<!-- $Marker -->
       |# $artifact $version: API documentation
       |
       |This jar holds no Scaladoc. The API documentation of every llm4s module is published in one place:
       |
       |  $SiteUrl
       |
       |The sources jar beside this one (`$artifact-$version-sources.jar`) is what an IDE reads for
       |documentation and navigation. The source of this version is at
       |https://github.com/llm4s/llm4s/tree/v$version
       |""".stripMargin

  def indexHtml(artifact: String, version: String): String =
    s"""<!doctype html>
       |<!-- $Marker -->
       |<html lang="en">
       |<head>
       |<meta charset="utf-8">
       |<title>$artifact $version API documentation</title>
       |<meta http-equiv="refresh" content="0; url=$SiteUrl">
       |<link rel="canonical" href="$SiteUrl">
       |</head>
       |<body>
       |<p>The API documentation for $artifact $version is at <a href="$SiteUrl">$SiteUrl</a>.</p>
       |</body>
       |</html>
       |""".stripMargin

  /** Writes the pointer's files under `dir` and returns them as `packageDoc` mappings. */
  def mappings(dir: File, artifact: String, version: String): Seq[(File, String)] = {
    val readmeFile = dir / "README.md"
    val indexFile  = dir / "index.html"
    IO.write(readmeFile, readme(artifact, version))
    IO.write(indexFile, indexHtml(artifact, version))
    Seq(readmeFile -> "README.md", indexFile -> "index.html")
  }

  /** What is wrong with `jar` as the pointer jar of `artifact`, if anything. */
  def problems(artifact: String, jar: File): Seq[String] =
    if (!jar.isFile) Seq(s"$artifact: no javadoc jar at $jar")
    else {
      val size = jar.length
      val tooBig =
        if (size > MaxBytes)
          Some(s"$artifact: the javadoc jar is $size bytes, over $MaxBytes: is it the module's Scaladoc again?")
        else None
      val content = Using.resource(new ZipFile(jar)) { zip =>
        Entries.flatMap { entry =>
          Option(zip.getEntry(entry)) match {
            case None => Some(s"$artifact: the javadoc jar has no $entry")
            case Some(e) =>
              val text = new String(zip.getInputStream(e).readAllBytes(), "UTF-8")
              if (text.contains(Marker) && text.contains(SiteUrl) && text.contains(artifact)) None
              else Some(s"$artifact: the javadoc jar's $entry does not point to $SiteUrl")
          }
        }
      }
      tooBig.toSeq ++ content
    }

  /**
   * Fails the build unless every published module that ships a jar also publishes a javadoc jar, and that jar is the
   * pointer.
   *
   * @param modules
   *   (artifact id, Compile / packageDoc / publishArtifact, the packaged javadoc jar) of every such module
   */
  def check(modules: Seq[(String, Boolean, File)], log: Logger): Unit = {
    val found = modules.sortBy(_._1).flatMap { case (artifact, published, jar) =>
      (if (published) Nil else Seq(s"$artifact: publishes no javadoc jar, which Maven Central requires")) ++
        problems(artifact, jar)
    }
    if (found.nonEmpty) {
      found.foreach(p => log.error(p))
      throw new MessageOnlyException(s"${found.size} javadoc jar problem(s): see above")
    }
    log.info(s"${modules.size} javadoc jars, each a pointer to $SiteUrl under $MaxBytes bytes")
  }
}
