package org.llm4s.capabilityusage

import org.llm4s.capability.{ requireCapabilities, Capable, Vision }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.ContentPart
import org.llm4s.media.{ MediaSource, MediaType }
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.nio.file.{ Files, Path, Paths }

/**
 * The code of `docs/guide/capabilities.md`, compiled and run as written, offline: the client is scripted and the
 * registry is the model data bundled with core.
 *
 * Every `scala` block of the guide must appear in this file word for word (the first spec below checks it, ignoring
 * whitespace). Change the guide and this spec together.
 */
class CapabilitiesGuideSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  // ---- Checked once at the edge, typed afterwards

  object Edge {
    import org.llm4s.capability.*

    def caption(c: Capable[Vision], image: ContentPart.Image): Result[String] =
      c.describe(image, "One-line alt text").map(_.content)

    def altText(client: LLMClient, image: ContentPart.Image)(using ModelRegistryService): Result[String] =
      for {
        capable <- client.requireCapabilities[Vision & Tools]("openai", "gpt-4o") // checked once, here
        alt <- caption(capable, image) // compiles: Vision & Tools is a Vision
      } yield alt
  }

  // ---- Models the registry does not know

  object Unknown {
    def local(client: LLMClient): Capable[Vision] =
      Capable.assume[Vision](client) // no check: visible in review
  }

  // ---- The guide, read from the repository

  private val root: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(dir => Files.exists(dir.resolve("docs/guide/capabilities.md")))
      .getOrElse(fail("docs/guide/capabilities.md was not found in this directory or above it"))

  private val guide = Files.readString(root.resolve("docs/guide/capabilities.md"))
  private val thisSource =
    Files.readString(
      root.resolve("modules/capabilities/src/test/scala/org/llm4s/capabilityusage/CapabilitiesGuideSpec.scala")
    )

  private def blocks(language: String): List[String] =
    ("(?s)```" + language + "\n(.*?)```").r.findAllMatchIn(guide).map(_.group(1)).toList

  private def squash(text: String): String = text.replaceAll("\\s+", " ").trim

  private given ModelRegistryService = ModelRegistryService.default().fold(e => fail(e.message), identity)

  private val image =
    ContentPart.image(MediaSource.Url(URI.create("https://example.com/cat.png")), MediaType.Png).value

  "The guide's Scala blocks" should "all appear in this spec word for word" in {
    val scalaBlocks = blocks("scala")
    scalaBlocks should have size 2
    scalaBlocks.foreach(block =>
      withClue(s"This block of the guide is not in the code the spec compiles:\n$block\n") {
        squash(thisSource) should include(squash(block))
      }
    )
  }

  it should "be matched by an sbt block that names the artifact the build publishes" in {
    blocks("sbt").map(squash) shouldBe List(
      """libraryDependencies += "org.llm4s" %% "llm4s-capabilities" % "{{ site.data.project.latest_release }}""""
    )
  }

  "The edge of the guide" should "check gpt-4o against the bundled data and caption the image" in {
    val client = ScriptedLLMClient.always(Reply.text("A cat on a mat"))
    Edge.altText(client, image).value shouldBe "A cat on a mat"
    client.lastCall.value.lastUserText.value shouldBe "One-line alt text"
  }

  it should "refuse a model the bundled data does not mark as seeing images, before any call" in {
    val client = ScriptedLLMClient.always(Reply.text("unused"))
    client.requireCapabilities[Vision]("openai", "gpt-3.5-turbo").left.value.message should include(
      "does not support Vision"
    )
    client.callCount shouldBe 0
  }

  "The unknown model of the guide" should "be assumed, with no metadata" in {
    Unknown.local(ScriptedLLMClient.always(Reply.text("ok"))).metadata shouldBe None
  }
}
