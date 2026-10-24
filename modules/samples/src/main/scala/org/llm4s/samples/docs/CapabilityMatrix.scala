package org.llm4s.samples.docs

import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.types.ProviderModelTypes.ProviderId

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path, Paths }
import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * The provider capability matrix of `docs/reference/compatibility-matrix.md`, read from the code (#2290).
 *
 * Every row comes from what the provider modules declare, never from prose: the chat and embedding descriptors that
 * [[ProviderRegistry.discover]] finds through each in-repo module's `META-INF/services` entry, their
 * `ProviderFeatures` (streaming, tool calling), their config specs (whether a section needs an API key, and whether it accepts workload identity), and
 * the `@Cloud` smoke suites of `modules/it`. Capabilities that `ProviderFeatures` cannot express yet are written as
 * [[NotDeclared]] until a module declares them.
 *
 * `sbt generateCompatibilityMatrix` runs [[CapabilityMatrixMain]], which writes `docs/_data/capabilities.yml`; CI runs
 * it again and fails when the committed file differs, so a new provider module cannot ship with a stale matrix.
 */
object CapabilityMatrix {

  /** The service file every provider module declares itself in. */
  val ServicesFile: String = "org.llm4s.llmconnect.spi.Llm4sProviderModule"

  /** The value of a capability no provider module declares yet. */
  val NotDeclared: String = "not declared"

  /** Capabilities `ProviderFeatures` cannot express yet; every row carries them as [[NotDeclared]]. */
  val UndeclaredCapabilities: Seq[String] = Seq("structured_output", "vision_input", "reasoning_effort", "stream_usage")

  /** The generated file, relative to the repository root. */
  val OutputFile: String = "docs/_data/capabilities.yml"

  /**
   * A provider module of this repository.
   *
   * @param moduleClass the class its `META-INF/services` entry names.
   * @param directory   its sbt project's directory, relative to the repository root.
   */
  final case class InRepoModule(moduleClass: String, directory: String) {

    /** The published artifact: every provider module is `llm4s-<its directory's name>`. */
    def artifact: String = "llm4s-" + directory.split('/').last
  }

  final case class ChatFacts(
    artifact: String,
    moduleClass: String,
    streaming: Boolean,
    toolCalling: Boolean,
    apiKeyRequired: Boolean,
    workloadIdentity: Boolean
  )

  final case class EmbeddingFacts(artifact: String, moduleClass: String, apiKeyRequired: Boolean)

  /** One provider id: its chat descriptor, its embedding descriptor, or both. */
  final case class Row(
    id: String,
    aliases: Seq[String],
    chat: Option[ChatFacts],
    embeddings: Option[EmbeddingFacts],
    cloudSmoke: Seq[String]
  )

  /** The nearest parent of the working directory that holds `docs/_data` and `build.sbt`. */
  def repositoryRoot: Option[Path] = {
    @tailrec def up(dir: Path): Option[Path] =
      if (Files.isDirectory(dir.resolve("docs/_data")) && Files.isRegularFile(dir.resolve("build.sbt"))) Some(dir)
      else
        Option(dir.getParent) match {
          case Some(parent) => up(parent)
          case None         => None
        }
    up(Paths.get("").toAbsolutePath)
  }

  /**
   * Every provider module the repository's main sources declare: `modules/<m>` and `modules/<group>/<m>` with a
   * `src/main/resources/META-INF/services/org.llm4s.llmconnect.spi.Llm4sProviderModule`. Test fixtures are not main
   * sources, so they are never listed.
   */
  def inRepoModules(root: Path): Seq[InRepoModule] = {
    val modules = root.resolve("modules")
    def children(dir: Path): Seq[Path] =
      if (!Files.isDirectory(dir)) Nil
      else Using.resource(Files.list(dir))(_.iterator().asScala.filter(Files.isDirectory(_)).toVector)
    val projectDirs = children(modules).flatMap(dir => dir +: children(dir))
    projectDirs
      .flatMap { dir =>
        val services = dir.resolve(s"src/main/resources/META-INF/services/$ServicesFile")
        if (!Files.isRegularFile(services)) Nil
        else {
          val directory = root.relativize(dir).iterator().asScala.map(_.toString).mkString("/")
          serviceEntries(services).map(InRepoModule(_, directory))
        }
      }
      .sortBy(_.moduleClass)
  }

  private def serviceEntries(file: Path): Seq[String] =
    Files
      .readAllLines(file, StandardCharsets.UTF_8)
      .asScala
      .map(_.takeWhile(_ != '#').trim)
      .filter(_.nonEmpty)
      .toVector

  /** The `@Cloud` suites of `modules/it`, by class (file) name. */
  def cloudSuites(root: Path): Seq[String] = {
    val tests = root.resolve("modules/it/src/test/scala")
    if (!Files.isDirectory(tests)) Nil
    else
      Using
        .resource(Files.walk(tests))(_.iterator().asScala.toVector)
        .filter(path => path.getFileName.toString.endsWith(".scala"))
        .filter(path => Files.readAllLines(path, StandardCharsets.UTF_8).asScala.exists(_.trim == "@Cloud"))
        .map(_.getFileName.toString.stripSuffix(".scala"))
        .sorted
  }

  private val SuiteSuffixes        = Seq("AssumptionProbeSpec", "SmokeSpec")
  private val SuiteQualifiers      = Seq("StructuredOutput", "Embeddings")
  private def normalise(s: String) = s.toLowerCase.filter(_.isLetterOrDigit)

  /**
   * The provider a smoke suite exercises, from its name: `<Provider>[Embeddings|StructuredOutput]SmokeSpec` or
   * `<Provider>AssumptionProbeSpec`, matched against ids and aliases ignoring case and punctuation. `None` for a suite
   * that is not named after a provider (speech, image generation, tracing).
   */
  def suiteProvider(suite: String, rows: Seq[(String, Seq[String])]): Option[String] =
    SuiteSuffixes.find(suite.endsWith).flatMap { suffix =>
      val stem     = suite.stripSuffix(suffix)
      val provider = normalise(SuiteQualifiers.find(stem.endsWith).fold(stem)(stem.stripSuffix))
      rows.collectFirst { case (id, aliases) if (id +: aliases).map(normalise).contains(provider) => id }
    }

  /**
   * One row per provider id the in-repo modules register, sorted by id.
   *
   * Fails when discovery reported a failure or a collision, or when an in-repo module was not discovered: a matrix
   * built from a partial registry would silently drop providers.
   */
  def rows(registry: ProviderRegistry, modules: Seq[InRepoModule], suites: Seq[String]): Either[String, Seq[Row]] = {
    val report  = registry.report
    val reports = report.modules.map(m => m.moduleClass -> m).toMap
    val missing = modules.filterNot(m => reports.contains(m.moduleClass))
    if (report.hasFailures) Left(s"Provider discovery failed: ${report.failures.map(_.detail).mkString("; ")}")
    else if (report.hasCollisions)
      Left(s"Provider ids collide: ${report.collisions.map(c => s"${c.kind} ${c.id}").mkString(", ")}")
    else if (missing.nonEmpty)
      Left(
        s"Provider modules declared in META-INF/services but not discovered: ${missing.map(_.moduleClass).mkString(", ")}"
      )
    else {
      val chat = for {
        module     <- modules
        id         <- reports(module.moduleClass).providerIds
        descriptor <- registry.find(ProviderId(id)).toSeq
      } yield id -> (
        ChatFacts(
          module.artifact,
          module.moduleClass,
          descriptor.features.streaming,
          descriptor.features.toolCalling,
          descriptor.configSpec.requiresApiKey,
          // A non-empty `authExtras` is how a provider declares workload-identity authentication.
          descriptor.configSpec.authExtras.nonEmpty
        ),
        descriptor.aliases
      )
      val embeddings = for {
        module     <- modules
        id         <- reports(module.moduleClass).embeddingProviderIds
        descriptor <- registry.findEmbedding(ProviderId(id)).toSeq
      } yield id -> (EmbeddingFacts(
        module.artifact,
        module.moduleClass,
        descriptor.configSpec.requiresApiKey
      ), descriptor.aliases)
      val chatById      = chat.toMap
      val embeddingById = embeddings.toMap
      val aliasesById = (chat ++ embeddings)
        .groupMapReduce(_._1)(_._2._2.toSeq)(_ ++ _)
        .toSeq
        .map { case (id, aliases) => id -> aliases.distinct.sorted }
        .sortBy(_._1)
      val suiteOwners = suites.flatMap(suite => suiteProvider(suite, aliasesById).map(_ -> suite))
      Right(aliasesById.map { case (id, aliases) =>
        Row(
          id,
          aliases,
          chatById.get(id).map(_._1),
          embeddingById.get(id).map(_._1),
          suiteOwners.collect { case (`id`, suite) => suite }
        )
      })
    }
  }

  /** The rows as YAML, for Jekyll's `site.data.capabilities`. Deterministic: no timestamp, sorted rows. */
  def yaml(rows: Seq[Row]): String = {
    def list(values: Seq[String]): String = values.map(quote).mkString("[", ", ", "]")
    def row(r: Row): Seq[String] = {
      val chat = r.chat match {
        case Some(c) =>
          Seq(
            "    chat: true",
            s"    chat_artifact: ${quote(c.artifact)}",
            s"    chat_module: ${quote(c.moduleClass)}",
            s"    streaming: ${c.streaming}",
            s"    tool_calling: ${c.toolCalling}",
            s"    chat_api_key: ${quote(apiKey(c.apiKeyRequired))}",
            s"    chat_workload_identity: ${c.workloadIdentity}"
          )
        case None => Seq("    chat: false")
      }
      val embeddings = r.embeddings match {
        case Some(e) =>
          Seq(
            "    embeddings: true",
            s"    embedding_artifact: ${quote(e.artifact)}",
            s"    embedding_module: ${quote(e.moduleClass)}",
            s"    embedding_api_key: ${quote(apiKey(e.apiKeyRequired))}"
          )
        case None => Seq("    embeddings: false")
      }
      Seq(s"  - id: ${quote(r.id)}", s"    aliases: ${list(r.aliases)}") ++ chat ++ embeddings ++
        Seq(s"    cloud_smoke: ${list(r.cloudSmoke)}") ++
        UndeclaredCapabilities.map(c => s"    $c: ${quote(NotDeclared)}")
    }
    val header = Seq(
      "# Generated by `sbt generateCompatibilityMatrix` (org.llm4s.samples.docs.CapabilityMatrix) from the provider",
      "# descriptors that discovery finds in this repository's modules. Do not edit: CI regenerates it and fails on drift.",
      "# Rendered by docs/reference/compatibility-matrix.md.",
      "providers:"
    )
    (header ++ rows.flatMap(row)).mkString("", "\n", "\n")
  }

  private def apiKey(required: Boolean): String = if (required) "required" else "not required"

  private def quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  /** The matrix for the repository at `root`, discovered through `loader`. */
  def generate(root: Path, loader: ClassLoader): Either[String, String] =
    rows(ProviderRegistry.discover(loader), inRepoModules(root), cloudSuites(root)).map(yaml)
}

/** Writes `docs/_data/capabilities.yml`; run by `sbt generateCompatibilityMatrix`. */
object CapabilityMatrixMain {
  def main(args: Array[String]): Unit = {
    val written = for {
      root <- CapabilityMatrix.repositoryRoot.toRight("Run this from inside the llm4s repository")
      yaml <- CapabilityMatrix.generate(root, getClass.getClassLoader)
    } yield {
      val out = root.resolve(CapabilityMatrix.OutputFile)
      Files.write(out, yaml.getBytes(StandardCharsets.UTF_8))
      out
    }
    written match {
      case Right(out)  => println(s"Wrote $out")
      case Left(error) => sys.error(error)
    }
  }
}
