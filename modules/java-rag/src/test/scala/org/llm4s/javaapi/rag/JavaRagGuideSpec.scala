package org.llm4s.javaapi.rag

import org.llm4s.error.RateLimitError
import org.llm4s.javaapi.GuideDocs
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.{ ByteArrayOutputStream, PrintStream }
import java.nio.file.{ Files, Path }
import java.util.Comparator
import scala.jdk.CollectionConverters.*

/**
 * `docs/guide/java-rag.md`, compiled and run: its Java blocks are `RagGuideSnippets.java`, which javac compiles against
 * this module's public API, and this spec checks that each block of the guide is in it and runs them over
 * `RagFakes`' word-count embeddings and echoing model.
 */
class JavaRagGuideSpec extends AnyWordSpec with Matchers {

  import GuideDocs.*
  import RagFakes.*

  private val GuideFile = "docs/guide/java-rag.md"
  private val Marker    = "build.sbt"

  private lazy val guide: String = read(Marker, GuideFile)

  private def blocksIn(language: String): List[String] = GuideDocs.blocksIn(guide, language)

  private lazy val snippets: String =
    squash(read(Marker, "modules/java-rag/src/test/java/org/llm4s/javaapi/rag/RagGuideSnippets.java"))

  /** What `body` printed to standard output and standard error. */
  private def captured(body: => Unit): (String, String) = {
    val (out, err)           = (new ByteArrayOutputStream, new ByteArrayOutputStream)
    val (oldOut, oldErr)     = (System.out, System.err)
    val (printOut, printErr) = (new PrintStream(out, true, "UTF-8"), new PrintStream(err, true, "UTF-8"))
    System.setOut(printOut)
    System.setErr(printErr)
    try body
    finally {
      System.setOut(oldOut)
      System.setErr(oldErr)
    }
    (out.toString("UTF-8"), err.toString("UTF-8"))
  }

  private def withDocs[A](body: Path => A): A = {
    val dir = Files.createTempDirectory("java-rag-guide")
    Files.writeString(dir.resolve("shipping.txt"), "Orders ship within 2 days.")
    try body(dir)
    finally Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p): Unit)
  }

  private def pipeline(): JRag = JRag.builder().embeddings(embeddings()).llm(llm()).build().get()

  "The guide's Java blocks" should {

    "all be in RagGuideSnippets.java, so javac compiles each one" in {
      val blocks = blocksIn("java")
      blocks should have size 7
      blocks.foreach { block =>
        withClue(s"This block of $GuideFile is not in RagGuideSnippets.java:\n$block\n") {
          snippets should include(squash(block))
        }
      }
    }

    "build a pipeline from the clients application.conf configures" in {
      val rag = RagGuideSnippets.fromConfiguration()
      try rag.ingestText("A text to index.", "doc").get() shouldBe 1
      finally rag.close()
    }

    "ingest a directory and a text, and answer with the matching source first" in withDocs { docs =>
      val rag = pipeline()
      try {
        RagGuideSnippets.ingest(rag, docs) shouldBe 2
        val (out, _) = captured(RagGuideSnippets.ask(rag))
        val lines    = out.linesIterator.toList
        lines.head shouldBe "Refunds are paid within 14 days."
        lines(1) should startWith("  refund-policy (")
        lines(1) should endWith("): Refunds are paid within 14 days.")
        lines.drop(1).exists(_.startsWith("  shipping.txt (")) shouldBe true
        RagGuideSnippets.search(rag).asScala.map(_.documentId).head shouldBe "refund-policy"
      } finally rag.close()
    }

    "keep a SQLite store in the files it names" in withDocs { dir =>
      val rag = RagGuideSnippets.sqlite(embeddings(), llm(), dir)
      try {
        rag.ingestText("Refunds are paid within 14 days.", "refund-policy").get() shouldBe 1
        Files.exists(dir.resolve("docs.db")) shouldBe true
        Files.exists(dir.resolve("docs-fts.db")) shouldBe true
      } finally rag.close()
    }

    "close the pipeline it opens" in withDocs { docs =>
      val (out, _) = captured(RagGuideSnippets.closing(embeddings(), llm(), docs))
      // the only chunk is the best match there is, so it is the context the model answers from
      out.trim shouldBe "Orders ship within 2 days."
    }

    "react to each kind of failure" in {
      val limited = JRag.builder().embeddings(embeddings()).llm(llm(new EchoingLlm(Some(RateLimitError("openai")))))
      val noModel = JRag.builder().embeddings(embeddings())
      Seq(limited -> "Try again later", noModel -> "Fix the setup: JRag.ask needs a chat client").foreach {
        case (builder, expected) =>
          val rag = builder.build().get()
          try captured(RagGuideSnippets.handle(rag))._2 should startWith(expected)
          finally rag.close()
      }
      val rag = pipeline()
      try captured(RagGuideSnippets.handle(rag)) shouldBe (("", ""))
      finally rag.close()
    }
  }

  "The guide's dependency blocks" should {

    "name llm4s-java-rag with the Scala suffix for Maven and Gradle, and the version placeholder everywhere" in {
      val maven  = blocksIn("xml").mkString
      val gradle = blocksIn("kotlin").mkString
      val sbt    = blocksIn("scala").mkString
      maven should include("<artifactId>llm4s-java-rag_3</artifactId>")
      gradle should include("org.llm4s:llm4s-java-rag_3:")
      sbt should include(""""org.llm4s" %% "llm4s-java-rag"""")
      Seq(maven, gradle, sbt).foreach(_ should include("{{ site.data.project.latest_release }}"))
    }
  }
}
