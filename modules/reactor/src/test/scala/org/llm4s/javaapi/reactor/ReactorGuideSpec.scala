package org.llm4s.javaapi.reactor

import org.llm4s.error.{ CancelledError, RateLimitError, ServiceError }
import org.llm4s.javaapi.{ GuideDocs, JLlmClient, Llm4s }
import org.llm4s.javaapi.StreamFixtures.*
import org.llm4s.llmconnect.model.StreamedChunk
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.{ ByteArrayOutputStream, PrintStream }
import scala.jdk.CollectionConverters.*

/**
 * `docs/guide/java-reactor.md`, compiled and run: its Java blocks are `ReactorGuideSnippets.java`, which javac compiles
 * against this module's public API, and this spec checks that each block of the guide is in it and runs them over
 * scripted clients.
 */
class ReactorGuideSpec extends AnyWordSpec with Matchers {

  import GuideDocs.*

  private val GuideFile = "docs/guide/java-reactor.md"
  private val Marker    = "build.sbt"

  private lazy val guide: String = read(Marker, GuideFile)

  private def blocksIn(language: String): List[String] = GuideDocs.blocksIn(guide, language)

  private lazy val snippets: String =
    squash(read(Marker, "modules/reactor/src/test/java/org/llm4s/javaapi/reactor/ReactorGuideSnippets.java"))

  private def streaming(pieces: String*): JLlmClient =
    Llm4s.wrapClient(
      new Scripted(
        onChunk => { pieces.foreach(p => onChunk(StreamedChunk(id = "c", content = Some(p)))); Right(completion("")) },
        () => Right(completion(""))
      )
    )

  private def failing(error: org.llm4s.error.LLMError): JLlmClient =
    Llm4s.wrapClient(new Scripted(_ => Left(error), () => Left(error)))

  /** What `body` printed to standard output. */
  private def printed(body: => Unit): String = {
    val out    = new ByteArrayOutputStream
    val oldOut = System.out
    System.setOut(new PrintStream(out, true, "UTF-8"))
    try body
    finally System.setOut(oldOut)
    out.toString("UTF-8")
  }

  "The guide's Java blocks" should {

    "all be in ReactorGuideSnippets.java, so javac compiles each one" in {
      val blocks = blocksIn("java")
      blocks should have size 4
      blocks.foreach { block =>
        withClue(s"This block of $GuideFile is not in ReactorGuideSnippets.java:\n$block\n") {
          snippets should include(squash(block))
        }
      }
    }

    "stream a reply, printing each piece and returning the whole text" in {
      var text = ""
      printed { text = ReactorGuideSnippets.stream(streaming("Mon", "ads")) } shouldBe "Monads"
      text shouldBe "Monads"
    }

    "stream a conversation with options" in {
      ReactorGuideSnippets.conversation(streaming("Scala", ", Kotlin", ", Java")).asScala shouldBe
        List("Scala", ", Kotlin", ", Java")
    }

    "take the first pieces and stop the call" in {
      val endless = Llm4s.wrapClient(
        new Scripted(
          onChunk => {
            var i = 0
            while (!Thread.currentThread().isInterrupted && i < 1_000_000) {
              onChunk(StreamedChunk(id = "c", content = Some(s"$i"))); i += 1
            }
            Left(CancelledError("test"))
          },
          () => Right(completion(""))
        )
      )
      ReactorGuideSnippets.firstPieces(endless).asScala shouldBe List("0", "1", "2", "3", "4")
    }

    "react to each kind of failure" in {
      ReactorGuideSnippets.handle(streaming("Hi")) shouldBe "Hi"
      ReactorGuideSnippets.handle(failing(RateLimitError("openai"))) shouldBe "Busy, try again later"
      ReactorGuideSnippets.handle(failing(ServiceError(500, "openai", "boom"))) should startWith("Failed: ")
    }
  }

  "The guide's dependency blocks" should {

    "name llm4s-reactor with the Scala suffix for Maven and Gradle, and the version placeholder everywhere" in {
      val maven  = blocksIn("xml").mkString
      val gradle = blocksIn("kotlin").mkString
      val sbt    = blocksIn("scala").mkString
      maven should include("<artifactId>llm4s-reactor_3</artifactId>")
      gradle should include("org.llm4s:llm4s-reactor_3:")
      sbt should include(""""org.llm4s" %% "llm4s-reactor"""")
      Seq(maven, gradle, sbt).foreach(_ should include("{{ site.data.project.latest_release }}"))
    }
  }
}
