package org.llm4s.samples.mill

import com.typesafe.config.{ ConfigFactory, ConfigResolveOptions }
import org.llm4s.config.Llm4sConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.{ ConfigObjectSource, ConfigSource }

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path, Paths }

/**
 * Keeps `modules/samples/mill` and `docs/getting-started/mill.md` in step (#2293).
 *
 * The sample is a standalone Mill project, so sbt does not build it: `.github/workflows/mill-quickstart.yml` builds
 * it and runs its test against the published release. What this spec checks, on every build, is that the page shows
 * the committed files, and that the committed `application.conf` selects the sections the page says it does.
 */
class MillQuickstartSpec extends AnyFlatSpec with Matchers {

  private val root: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(dir => Files.exists(dir.resolve("build.sbt")))
      .getOrElse(throw new IllegalStateException("build.sbt not found above the working directory"))

  private val sampleDir = root.resolve("modules").resolve("samples").resolve("mill")
  private val appDir    = sampleDir.resolve("app")
  private val confFile  = appDir.resolve("resources").resolve("application.conf")

  // A checkout with CRLF line endings must not make the comparisons below fail.
  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n")

  private val build = read(sampleDir.resolve("build.mill"))
  private val page  = read(root.resolve("docs").resolve("getting-started").resolve("mill.md"))

  private val llm4sVersion: String =
    """val llm4sVersion = "([^"]+)"""".r
      .findFirstMatchIn(build)
      .map(_.group(1))
      .getOrElse(fail("build.mill has no `val llm4sVersion = \"...\"`"))

  private val millVersion: String =
    """//\| mill-version: (\S+)""".r
      .findFirstMatchIn(build)
      .map(_.group(1))
      .getOrElse(fail("build.mill has no `//| mill-version:` header"))

  /** The fenced blocks of a language on the page, in order. */
  private def blocks(language: String): Seq[String] =
    ("(?s)```" + language + "\n(.*?)```").r.findAllMatchIn(page).map(_.group(1)).toSeq

  /** The page's block of a language that contains `marker`. */
  private def blockWith(language: String, marker: String): String =
    blocks(language).find(_.contains(marker)).getOrElse(fail(s"the page has no ```$language block with $marker"))

  private def render(text: String): String = text.replace("{{ site.data.project.latest_release }}", llm4sVersion)

  // Explanatory comments are in the files, not on the page: compare the code only. `//|` is Mill's header, not a comment.
  private val scalaComment: String => Boolean = line => line.startsWith("//") && !line.startsWith("//|")
  private val hoconComment: String => Boolean = _.startsWith("#")
  private val xmlComment: String => Boolean   = _.startsWith("<!--")

  private def code(text: String, isComment: String => Boolean): Seq[String] =
    text.linesIterator.map(_.stripTrailing()).filterNot(line => line.isBlank || isComment(line.trim)).toSeq

  // `${?LLM4S_PROVIDER}` and `${?OLLAMA_BASE_URL}` resolve to nothing, as in a run that sets neither.
  private def committedConfig: ConfigObjectSource =
    ConfigSource.fromConfig(
      ConfigFactory.parseFile(confFile.toFile).resolve(ConfigResolveOptions.defaults().setUseSystemEnvironment(false))
    )

  "The Mill sample" should "ship the files the docs page lists" in {
    Seq(
      sampleDir.resolve("mill"),
      sampleDir.resolve("build.mill"),
      appDir.resolve("src").resolve("Hello.scala"),
      confFile,
      appDir.resolve("resources").resolve("logback.xml"),
      appDir.resolve("test").resolve("src").resolve("HelloTests.scala")
    ).foreach(file => withClue(s"$file: ")(Files.isRegularFile(file) shouldBe true))
  }

  it should "match the page's build.mill, Hello.scala and HelloTests.scala blocks" in {
    code(render(blockWith("scala", "//| mill-version")), scalaComment) shouldBe code(build, scalaComment)
    code(blockWith("scala", "object Hello"), scalaComment) shouldBe
      code(read(appDir.resolve("src").resolve("Hello.scala")), scalaComment)
    code(blockWith("scala", "class HelloTests"), scalaComment) shouldBe
      code(read(appDir.resolve("test").resolve("src").resolve("HelloTests.scala")), scalaComment)
  }

  it should "match the page's application.conf and logback.xml blocks" in {
    val hocon = blocks("hocon")
    hocon should have size 1
    code(hocon.head, hoconComment) shouldBe code(read(confFile), hoconComment)
    code(blockWith("xml", "<configuration>"), xmlComment) shouldBe
      code(read(appDir.resolve("resources").resolve("logback.xml")), xmlComment)
  }

  it should "download the launcher of the Mill version build.mill names" in {
    page should include(s"mill-dist/$millVersion/mill-dist-$millVersion-mill.sh")
    page should include(s"mill-dist-$millVersion-mill.bat")
    read(sampleDir.resolve("mill")) should include(s"""DEFAULT_MILL_VERSION="$millVersion"""")
  }

  it should "pin the logback version the build uses" in {
    val logback = """val logback\s*=\s*"([^"]+)"""".r
      .findFirstMatchIn(read(root.resolve("project").resolve("Dependencies.scala")))
      .map(_.group(1))
      .getOrElse(fail("no `val logback` in project/Dependencies.scala"))
    build should include(s"""mvn"ch.qos.logback:logback-classic:$logback"""")
  }

  // --- the configuration ---------------------------------------------------------------------------------------

  it should "load the committed application.conf with OpenAI as the default section" in {
    val withKey = ConfigSource.string("llm4s.providers.openai-main.apiKey = \"test-key\"").withFallback(committedConfig)

    val config = Llm4sConfig.providerFrom(withKey).fold(error => fail(error.formatted), identity)

    config.providerId.asString shouldBe "openai"
    config.model shouldBe "gpt-4o-mini"
  }

  it should "load the Ollama section when LLM4S_PROVIDER names it, with no OpenAI key" in {
    val ollama = ConfigSource.string("llm4s.providers.provider = \"ollama-local\"").withFallback(committedConfig)

    val config = Llm4sConfig.providerFrom(ollama).fold(error => fail(error.formatted), identity)

    config.providerId.asString shouldBe "ollama"
    config.model shouldBe "llama3"
    read(confFile) should include("provider = ${?LLM4S_PROVIDER}")
  }
}
