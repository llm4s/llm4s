package org.llm4s.samples.cookbook

import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path, Paths }
import scala.jdk.CollectionConverters._

/**
 * Keeps the cookbook's docs and its recipes in step, and runs every recipe, so CI executes the whole cookbook on every
 * pull request with no network and no API key.
 *
 * Each recipe has a page, `docs/examples/cookbook/<id>.md`, whose program is the recipe's whole source file; the index
 * page `docs/examples/cookbook.md` links the pages in the order of [[Cookbook.recipes]], and so do the README and the
 * examples index. The checks fail when a page is missing, out of order or lacks a section, when a page's program is not
 * what the source file says, when a recipe file is not registered in [[Cookbook]], or when a recipe fails.
 */
class CookbookDocsSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val indexPage = "docs/examples/cookbook.md"
  private val pagesDir  = "docs/examples/cookbook"
  private val sections  = Seq("## The problem", "## The program", "## Run it", "## Use a real provider", "## Pitfalls")

  private lazy val repoRoot: Path =
    Iterator
      .iterate(Paths.get(sys.props("user.dir")).toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(dir => Files.exists(dir.resolve("build.sbt")))
      .getOrElse(fail("could not find the repository root (a directory with build.sbt) above the working directory"))

  /** The file's text with Windows line endings normalised, so a checkout with CRLF compares equal. */
  private def read(relative: String): String =
    new String(Files.readAllBytes(repoRoot.resolve(relative)), StandardCharsets.UTF_8).replace("\r\n", "\n")

  /** The `key: value` lines of a page's front matter. */
  private def frontMatter(page: String): Map[String, String] =
    "(?s)\\A---\n(.*?)\n---\n".r
      .findFirstMatchIn(page)
      .map(_.group(1).linesIterator.toSeq)
      .getOrElse(Seq.empty)
      .flatMap(line => "^([a-z_]+):\\s*(.*?)\\s*$".r.findFirstMatchIn(line).map(m => m.group(1) -> m.group(2)))
      .toMap

  /** The first scala code block after the page's "The program" heading. */
  private def program(id: String, page: String): String =
    "(?s)\n## The program\n.*?```scala\n(.*?)\n```".r
      .findFirstMatchIn(page)
      .map(_.group(1).trim)
      .getOrElse(fail(s"the page of $id has no scala block under '## The program'"))

  private def page(recipe: RecipeInfo): String = read(recipe.pagePath)

  /** The recipe ids of the links to recipe pages in `text`, in the order they appear, each id once. */
  private def linkedIds(text: String, prefix: String): Seq[String] =
    s"\\]\\(${java.util.regex.Pattern.quote(prefix)}([a-z-]+)(?:\\.md)?\\)".r
      .findAllMatchIn(text)
      .map(_.group(1))
      .toSeq
      .distinct

  private def relativeMdLinks(text: String): List[String] =
    "\\]\\((?!https?:)[^)]*\\.md[^)]*\\)".r.findAllIn(text.replaceAll("(?s)```.*?```", "")).toList

  "The cookbook index" should "link every recipe's page, in the order of the registry" in {
    linkedIds(read(indexPage), "cookbook/") shouldBe Cookbook.recipes.map(_.id)
  }

  it should "be the parent of the recipe pages in the site's navigation" in {
    val fm = frontMatter(read(indexPage))
    fm.get("title") shouldBe Some("Cookbook")
    fm.get("parent") shouldBe Some("Examples")
    fm.get("has_children") shouldBe Some("true")
  }

  it should "contain no relative link ending in .md, which the site serves as a 404" in {
    relativeMdLinks(read(indexPage)) shouldBe empty
  }

  "The recipe pages" should "be one per recipe, with no page for a recipe that does not exist" in {
    val pages = Files.list(repoRoot.resolve(pagesDir)).iterator().asScala.map(_.getFileName.toString).toList
    pages should contain theSameElementsAs Cookbook.recipes.map(_.id + ".md")
  }

  they should "sit under the cookbook in the site's navigation, in the order of the registry" in {
    Cookbook.recipes.zipWithIndex.foreach { (recipe, i) =>
      withClue(s"recipe ${recipe.id}: ") {
        val fm = frontMatter(page(recipe))
        fm.get("title") shouldBe Some(recipe.title)
        fm.get("parent") shouldBe Some("Cookbook")
        fm.get("grand_parent") shouldBe Some("Examples")
        fm.get("nav_order") shouldBe Some((i + 1).toString)
      }
    }
  }

  they should "give the problem, the program, how to run it, what to change for a real provider and the pitfalls" in {
    Cookbook.recipes.foreach { recipe =>
      withClue(s"recipe ${recipe.id}: ") {
        val lines     = page(recipe).split("\n").toSeq
        val positions = sections.map(section => lines.indexOf(section))
        positions should not contain -1
        positions shouldBe positions.sorted
      }
    }
  }

  they should "show the recipe's whole source file as its program, exactly as the file has it" in {
    Cookbook.recipes.foreach { recipe =>
      withClue(s"recipe ${recipe.id}: ") {
        program(recipe.id, page(recipe)) shouldBe read(recipe.sourcePath).trim
      }
    }
  }

  they should "give the run commands, scripted and live, and a link to the source file" in {
    Cookbook.recipes.foreach { recipe =>
      withClue(s"recipe ${recipe.id}: ") {
        val text = page(recipe)
        text should include(s"""sbt "samples/runMain ${recipe.mainClass}"""")
        text should include(s"""sbt "samples/runMain ${recipe.mainClass} --live"""")
        text should include(s"https://github.com/llm4s/llm4s/blob/main/${recipe.sourcePath}")
      }
    }
  }

  they should "contain no relative link ending in .md, which the site serves as a 404" in {
    Cookbook.recipes.foreach { recipe =>
      withClue(s"recipe ${recipe.id}: ")(relativeMdLinks(page(recipe)) shouldBe empty)
    }
  }

  "The README and the examples index" should "link every recipe's page, in the order of the registry" in {
    linkedIds(read("README.md"), "docs/examples/cookbook/") shouldBe Cookbook.recipes.map(_.id)
    linkedIds(read("docs/examples/index.md"), "cookbook/") shouldBe Cookbook.recipes.map(_.id)
  }

  "The cookbook registry" should "have unique ids and main classes" in {
    Cookbook.recipes.map(_.id).distinct should have size Cookbook.recipes.size.toLong
    Cookbook.recipes.map(_.mainClass).distinct should have size Cookbook.recipes.size.toLong
  }

  it should "have at least the eight recipes the cookbook promises" in {
    Cookbook.recipes.size should be >= 8
  }

  it should "name a source file that exists and a main class that sbt runMain can start" in {
    Cookbook.recipes.foreach { recipe =>
      withClue(s"recipe ${recipe.id}: ") {
        Files.exists(repoRoot.resolve(recipe.sourcePath)) shouldBe true
        Class.forName(recipe.mainClass).getMethod("main", classOf[Array[String]]) should not be null
      }
    }
  }

  it should "register every recipe file in the cookbook directory" in {
    val directory = repoRoot.resolve("modules/samples/src/main/scala/org/llm4s/samples/cookbook")
    val recipeSources = Files
      .list(directory)
      .iterator()
      .asScala
      .filter(_.toString.endsWith(".scala"))
      .filter(path => new String(Files.readAllBytes(path), StandardCharsets.UTF_8).contains("extends RecipeApp"))
      .map(path => "org.llm4s.samples.cookbook." + path.getFileName.toString.stripSuffix(".scala"))
      .toList
    recipeSources should contain theSameElementsAs Cookbook.recipes.map(_.mainClass)
  }

  it should "run every recipe against its scripted client and print something" in {
    Cookbook.apps.foreach { app =>
      withClue(s"recipe ${app.info.id}: ") {
        app.demo(app.script).value.trim should not be empty
      }
    }
  }
}
