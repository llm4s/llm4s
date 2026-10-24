package org.llm4s.samples.docs

import org.llm4s.llmconnect.spi.ProviderRegistry
import org.scalatest.EitherValues
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{ FileVisitResult, Files, Path, SimpleFileVisitor }
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*

/**
 * The generated provider capability matrix (#2290): every in-repo provider module is discovered, every provider id it
 * registers is one row, and `docs/_data/capabilities.yml` is what the generator writes today.
 */
class CapabilityMatrixSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private val root: Path = CapabilityMatrix.repositoryRoot.value

  private val registry  = ProviderRegistry.discover(getClass.getClassLoader)
  private val modules   = CapabilityMatrix.inRepoModules(root)
  private val suites    = CapabilityMatrix.cloudSuites(root)
  private lazy val rows = CapabilityMatrix.rows(registry, modules, suites).value

  private val servicesPath = s"src/main/resources/META-INF/services/${CapabilityMatrix.ServicesFile}"

  /** Every main-source provider services file under modules/, at any depth, found by a full walk. */
  private def servicesFilesByWalk: Seq[Path] = {
    val found = ListBuffer.empty[Path]
    Files.walkFileTree(
      root.resolve("modules"),
      new SimpleFileVisitor[Path] {
        override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult = {
          val name = dir.getFileName.toString
          if (Set("target", "node_modules", "build", ".gradle", ".bsp", "test").contains(name))
            FileVisitResult.SKIP_SUBTREE
          else FileVisitResult.CONTINUE
        }
        override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
          if (root.relativize(file).toString.replace('\\', '/').endsWith(servicesPath)) found += file
          FileVisitResult.CONTINUE
        }
        override def visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
      }
    )
    found.toVector
  }

  "inRepoModules" should "list every provider module the repository's main sources declare" in {
    val byWalk = servicesFilesByWalk.flatMap { file =>
      Files
        .readAllLines(file, StandardCharsets.UTF_8)
        .asScala
        .map(_.takeWhile(_ != '#').trim)
        .filter(_.nonEmpty)
    }
    byWalk should not be empty
    modules.map(_.moduleClass) should contain theSameElementsAs byWalk
  }

  it should "never list a test fixture module" in {
    modules.map(_.moduleClass).filter(_.startsWith("org.llm4s.testutil.")) shouldBe empty
    modules.foreach(m => m.directory should startWith("modules/"))
  }

  "the generator" should "discover every in-repo Llm4sProviderModule" in {
    val discovered = registry.report.modules.map(_.moduleClass).toSet
    modules.map(_.moduleClass).filterNot(discovered.contains) shouldBe empty
  }

  it should "give every provider id the in-repo modules register exactly one row" in {
    val inRepo     = modules.map(_.moduleClass).toSet
    val reports    = registry.report.modules.filter(m => inRepo.contains(m.moduleClass))
    val registered = reports.flatMap(m => m.providerIds ++ m.embeddingProviderIds).distinct
    val rowIds     = rows.map(_.id)

    rowIds.distinct shouldBe rowIds
    rowIds should contain theSameElementsAs registered
    registered.foreach(id => rowIds.count(_ == id) shouldBe 1)
  }

  it should "give every registered chat and embedding id a row, except test fixtures" in {
    val rowIds   = rows.map(_.id).toSet
    val fixtures = Set("fixturechat", "fixtureembedding")
    (registry.ids ++ registry.embeddingIds).distinct.filterNot(fixtures.contains).filterNot(rowIds.contains) shouldBe
      empty
  }

  it should "record the chat and embedding sides of a provider id from their own modules" in {
    val cohere = rows.find(_.id == "cohere").value
    cohere.chat.value.artifact shouldBe "llm4s-openai-compatible"
    cohere.embeddings.value.artifact shouldBe "llm4s-cohere"

    val voyage = rows.find(_.id == "voyage").value
    voyage.chat shouldBe None
    voyage.aliases should contain("voyageai")
  }

  it should "fail rather than drop a module that discovery did not find" in {
    val ghost = CapabilityMatrix.InRepoModule("org.llm4s.example.GhostModule", "modules/providers/ghost")
    CapabilityMatrix.rows(registry, modules :+ ghost, suites).left.value should include("GhostModule")
  }

  it should "attribute every @Cloud provider smoke suite to a provider" in {
    val providerSuites = suites.filter(s => s.endsWith("SmokeSpec") || s.endsWith("AssumptionProbeSpec"))
    val smokeDir       = root.resolve("modules/it/src/test/scala/org/llm4s/llmconnect/smoke")
    val unattributed = providerSuites
      .filter(s => Files.isRegularFile(smokeDir.resolve(s"$s.scala")))
      .filterNot(s => rows.exists(_.cloudSmoke.contains(s)))
    unattributed shouldBe empty
  }

  "suiteProvider" should "match a suite to an id or alias, ignoring case, punctuation and qualifiers" in {
    val known = Seq("openai" -> Seq.empty, "openai-compatible" -> Seq.empty, "voyage" -> Seq("voyageai"))
    CapabilityMatrix.suiteProvider("OpenAISmokeSpec", known) shouldBe Some("openai")
    CapabilityMatrix.suiteProvider("OpenAICompatibleSmokeSpec", known) shouldBe Some("openai-compatible")
    CapabilityMatrix.suiteProvider("VoyageAIEmbeddingsSmokeSpec", known) shouldBe Some("voyage")
    CapabilityMatrix.suiteProvider("ElevenLabsSmokeSpec", known) shouldBe None
    CapabilityMatrix.suiteProvider("OpenAIHelperSpec", known) shouldBe None
  }

  "docs/_data/capabilities.yml" should "be what `sbt generateCompatibilityMatrix` writes today" in {
    val committed =
      new String(Files.readAllBytes(root.resolve(CapabilityMatrix.OutputFile)), StandardCharsets.UTF_8)
        .replace("\r\n", "\n")
    withClue("Run `sbt generateCompatibilityMatrix` and commit docs/_data/capabilities.yml: ") {
      committed shouldBe CapabilityMatrix.yaml(rows)
    }
  }

  it should "mark the capabilities ProviderFeatures cannot express as not declared" in {
    val yaml = CapabilityMatrix.yaml(rows)
    CapabilityMatrix.UndeclaredCapabilities.foreach { capability =>
      yaml.linesIterator.count(_.trim == s"""$capability: "not declared"""") shouldBe rows.size
    }
  }
}
