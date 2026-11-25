import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import CentralBundle._

/** Tests of `project/CentralBundle.scala`, run with `sbt "reload plugins" test` (CI's quick checks). */
class CentralBundleSpec extends AnyFlatSpec with Matchers with org.scalatest.OptionValues {

  private def signed(path: String): Seq[String] = path +: Companions.map(path + _)

  private def module(id: String, v: String = "1.0.0"): Seq[String] = {
    val dir = s"org/llm4s/$id/$v/$id-$v"
    Seq(".pom", ".jar", "-sources.jar", "-javadoc.jar").flatMap(s => signed(dir + s))
  }

  "problems" should "accept a module of 16 files and a POM-only artifact of 4" in {
    problems(module("llm4s-core_3") ++ signed("org/llm4s/llm4s-bom/1.0.0/llm4s-bom-1.0.0.pom")) shouldBe empty
  }

  it should "refuse a checksum of a signature" in {
    val p = problems(module("llm4s-core_3") :+ "org/llm4s/llm4s-core_3/1.0.0/llm4s-core_3-1.0.0.jar.asc.md5")
    p.mkString should include("checksum of a signature")
  }

  it should "refuse a CycloneDX SBOM" in {
    val p = problems(module("llm4s-core_3") ++ signed("org/llm4s/llm4s-core_3/1.0.0/llm4s-core_3-1.0.0-cyclonedx.json"))
    p.mkString should include("is an SBOM")
  }

  it should "refuse a published file without its signature or checksums" in {
    val p = problems(module("llm4s-core_3").filterNot(_.endsWith("-javadoc.jar.asc")))
    p shouldBe Seq("org/llm4s/llm4s-core_3/1.0.0: no llm4s-core_3-1.0.0-javadoc.jar.asc")
  }

  it should "refuse a bundle over Sonatype's monthly limit" in {
    val many = (1 to MaxFiles / MaxFilesPerArtifact + 1).flatMap(i => module(s"llm4s-m${i}_3"))
    problems(many).last should include(s"over Sonatype's monthly limit of $MaxFiles")
  }

  "releaseOverLimit" should "say when the bundle fits but the release with the Kotlin artifact does not" in {
    releaseOverLimit(MaxFiles - FilesOutsideBundle) shouldBe None
    releaseOverLimit(MaxFiles - FilesOutsideBundle + 4).value should include(s"uploads ${MaxFiles + 4} files")
    // a bundle over the limit by itself is `problems`' to refuse
    releaseOverLimit(MaxFiles + 1) shouldBe None
  }

  "leftOut" should "leave out the signatures' checksums only" in {
    Seq("a.jar.asc.md5", "a.jar.asc.sha1", "a.pom.asc.sha256").forall(leftOut) shouldBe true
    Seq("a.jar", "a.jar.asc", "a.jar.md5", "a.jar.sha1").exists(leftOut) shouldBe false
  }
}
