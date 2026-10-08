package org.llm4s.samples.cookbook

import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path, Paths }
import scala.jdk.CollectionConverters._

/**
 * Keeps `docs/examples/cookbook.md` and the recipes in step, and runs every recipe, so CI executes the whole
 * cookbook on every pull request with no network and no API key.
 *
 * The page lists each recipe after a `<!-- recipe: id -->` marker and embeds the code between the `// snippet:start`
 * and `// snippet:end` lines of its source. The checks fail when the page and the code list different recipes, when
 * an embedded snippet is not what the source says, or when a recipe file is not registered in [[Cookbook]].
 */
class CookbookDocsSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val page = "docs/examples/cookbook.md"

  private lazy val repoRoot: Path =
    Iterator
      .iterate(Paths.get(sys.props("user.dir")).toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(dir => Files.exists(dir.resolve("build.sbt")))
      .getOrElse(fail("could not find the repository root (a directory with build.sbt) above the working directory"))

  /** The file's text with Windows line endings normalised, so a checkout with CRLF compares equal. */
  private def read(relative: String): String =
    new String(Files.readAllBytes(repoRoot.resolve(relative)), StandardCharsets.UTF_8).replace("\r\n", "\n")

  private lazy val pageText: String = read(page)

  private def markers: Seq[String] = "<!-- recipe: ([a-z-]+) -->".r.findAllMatchIn(pageText).map(_.group(1)).toSeq

  /** The code between the snippet markers of a source file, with the common indentation removed. */
  private def snippetOf(source: String): String = {
    val lines = source.split("\n", -1).toVector
    val start = lines.indexWhere(_.trim == "// snippet:start")
    val end   = lines.indexWhere(_.trim == "// snippet:end")
    withClue("a recipe source needs a '// snippet:start' line before a '// snippet:end' line: ") {
      (start >= 0 && end > start) shouldBe true
    }
    val body   = lines.slice(start + 1, end)
    val indent = body.filter(_.trim.nonEmpty).map(_.takeWhile(_ == ' ').length).minOption.getOrElse(0)
    body.map(line => if (line.trim.isEmpty) "" else line.drop(indent)).mkString("\n").trim
  }

  /** The first scala code block after a recipe's marker on the page. */
  private def pageBlock(id: String): String = {
    val pattern = s"(?s)<!-- recipe: $id -->.*?```scala\n(.*?)\n```".r
    pattern.findFirstMatchIn(pageText).map(_.group(1).trim).getOrElse(fail(s"no scala block after the marker of $id"))
  }

  "The cookbook page" should "list the same recipes, in the same order, as the code" in {
    markers shouldBe Cookbook.recipes.map(_.id)
  }

  it should "embed each recipe's snippet exactly as its source has it" in {
    Cookbook.recipes.foreach { recipe =>
      withClue(s"recipe ${recipe.id}: ") {
        pageBlock(recipe.id) shouldBe snippetOf(read(recipe.sourcePath))
      }
    }
  }

  it should "give each recipe's run command and a link to its source" in {
    Cookbook.recipes.foreach { recipe =>
      withClue(s"recipe ${recipe.id}: ") {
        pageText should include(s"""sbt "samples/runMain ${recipe.mainClass}"""")
        pageText should include(s"https://github.com/llm4s/llm4s/blob/main/${recipe.sourcePath}")
      }
    }
  }

  it should "contain no relative link ending in .md, which the site serves as a 404" in {
    val withoutCode = pageText.replaceAll("(?s)```.*?```", "")
    "\\]\\((?!https?:)[^)]*\\.md[^)]*\\)".r.findAllIn(withoutCode).toList shouldBe empty
  }

  "The cookbook registry" should "have unique ids and main classes" in {
    Cookbook.recipes.map(_.id).distinct should have size Cookbook.recipes.size.toLong
    Cookbook.recipes.map(_.mainClass).distinct should have size Cookbook.recipes.size.toLong
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
