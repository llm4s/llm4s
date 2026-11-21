import sbt._
import sbt.Keys._

import com.github.sbt.sbom.BomSbtPlugin

/**
 * What a release uploads to Maven Central, and how many files that is.
 *
 * Sonatype limits an organisation to 1,000 uploaded files a month, and 0.6.0 uploaded 1,752: every module published
 * five files (POM, jar, sources, javadoc, and sbt-sbom's `-cyclonedx.json`), and each of them came with six (the
 * file, its `.asc`, `.md5` and `.sha1`, and `.asc.md5` and `.asc.sha1`, because sbt checksums every file it publishes,
 * the signatures included). Central requires a signature, an MD5 and a SHA-1 of each file, and says that "`.asc` files
 * don't need checksum files" (https://central.sonatype.org/publish/requirements/). So a module now uploads its POM,
 * jar, sources jar and javadoc jar, each with `.asc`, `.md5` and `.sha1`: 16 files, and a POM-only artifact 4.
 *
 *   - [[NoSbomInPublication]] drops sbt-sbom's CycloneDX file from every project's published artifacts. The SBOMs are
 *     still generated (`publishedSboms`), and the release attaches every module's SBOM to its GitHub Release.
 *   - [[make]] replaces sbt's `sonaBundle`, which zipped the whole staging directory that `publishSigned` wrote, with
 *     one that leaves out the signatures' checksums, and refuses to build a bundle that [[problems]] finds wrong.
 */
object CentralBundle {

  /** The most files one release may upload: Sonatype's monthly limit for the organisation. */
  val MaxFiles = 1000

  /** A module's POM, jar, sources jar and javadoc jar, each with `.asc`, `.md5` and `.sha1`. */
  val MaxFilesPerArtifact = 16

  /** The companions Central requires beside each published file. */
  val Companions: Seq[String] = Seq(".asc", ".md5", ".sha1")

  /** A checksum of a signature (`x.jar.asc.md5`): Central does not want one. */
  def isSignatureChecksum(name: String): Boolean = name.matches(""".*\.asc\.(md5|sha1|sha256|sha512)""")

  /** Left out of the bundle: signatures' checksums. */
  def leftOut(name: String): Boolean = isSignatureChecksum(name)

  private def isCompanion(name: String): Boolean =
    Companions.exists(c => name.endsWith(c)) || isSignatureChecksum(name)

  /**
   * What is wrong with a bundle of these files (paths relative to the staging directory, `/`-separated). Each
   * directory is one artifact at one version: its published files are POMs and jars only, none a CycloneDX file, each
   * with `.asc`, `.md5` and `.sha1` and nothing else, at most [[MaxFilesPerArtifact]] files; the whole bundle has at
   * most [[MaxFiles]].
   */
  def problems(paths: Seq[String]): Seq[String] = {
    val byDir = paths.groupBy(p => p.lastIndexOf('/') match { case -1 => ""; case i => p.substring(0, i) })
    val perDir = byDir.toSeq.sortBy(_._1).flatMap { case (dir, files) =>
      val names     = files.map(p => p.substring(p.lastIndexOf('/') + 1)).toSet
      val primaries = names.filterNot(isCompanion)
      val expected  = primaries.flatMap(p => Companions.map(p + _)) ++ primaries
      val kinds = primaries.toSeq.sorted.collect {
        case p if p.contains("cyclonedx") => s"$dir: $p is an SBOM: SBOMs go on the GitHub Release, not to Central"
        case p if !(p.endsWith(".pom") || p.endsWith(".jar")) => s"$dir: $p is neither a POM nor a jar"
      }
      val missing = (expected -- names).toSeq.sorted.map(m => s"$dir: no $m")
      val extra = (names -- expected).toSeq.sorted.map { e =>
        if (isSignatureChecksum(e)) s"$dir: $e is a checksum of a signature, which Central does not need"
        else s"$dir: $e belongs to no published file"
      }
      val tooMany =
        if (names.size > MaxFilesPerArtifact) Seq(s"$dir: ${names.size} files, over $MaxFilesPerArtifact") else Nil
      kinds ++ missing ++ extra ++ tooMany
    }
    val total =
      if (paths.size > MaxFiles)
        Seq(s"${paths.size} files in the bundle, over Sonatype's monthly limit of $MaxFiles for the organisation")
      else Nil
    perDir ++ total
  }

  /** Zips `staging` into `bundle` without the signatures' checksums, after checking what is left. */
  def make(staging: File, bundle: File, log: Logger): File = {
    val all             = (staging ** -DirectoryFilter).get.flatMap(f => IO.relativize(staging, f).map(f -> _))
    val (dropped, kept) = all.partition { case (f, _) => leftOut(f.getName) }
    val found           = problems(kept.map(_._2))
    if (found.nonEmpty) {
      found.foreach(p => log.error(p))
      throw new MessageOnlyException(s"${found.size} problem(s) with the Maven Central bundle of $staging: see above")
    }
    IO.delete(bundle)
    IO.zip(kept.sortBy(_._2), bundle, Some(0L))
    val dirs  = kept.map(_._2).map(p => p.substring(0, p.lastIndexOf('/').max(0))).distinct.size
    val bytes = kept.map(_._1.length).sum
    log.info(
      s"Maven Central bundle: ${kept.size} files ($dirs artifacts, ${bytes / 1024} KB uncompressed), " +
        s"${dropped.size} signature checksums left out; ${bundle.length / 1024} KB zipped at $bundle"
    )
    bundle
  }
}

/**
 * sbt-sbom adds each project's CycloneDX SBOM to its published artifacts, so every module, the BOM and the relocation
 * stubs uploaded one, with its signature and checksums, to Maven Central. Central does not need it, and Sonatype's
 * monthly file limit does not allow it (see [[CentralBundle]]). This drops it from every project's
 * `packagedArtifacts`, which `publish`, `publishSigned`, `publishLocal` and `publishM2` read; `makeBom` and
 * `publishedSboms` are untouched, and the release attaches the SBOMs to the GitHub Release.
 */
object NoSbomInPublication extends AutoPlugin {
  override def requires = BomSbtPlugin
  override def trigger  = allRequirements

  override lazy val projectSettings: Seq[Def.Setting[_]] = Seq(
    packagedArtifacts := packagedArtifacts.value.filterNot { case (a, _) => a.`type` == "cyclonedx" }
  )
}
