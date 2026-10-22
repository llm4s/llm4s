package org.llm4s.samples.rag

import org.llm4s.media.MediaType
import org.llm4s.samples.rag.DocumentExtractionInspection._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.{ ByteArrayOutputStream, PrintStream }
import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import java.util.Comparator
import scala.jdk.CollectionConverters._

class DocumentExtractionInspectorSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val dir: Path = Files.createTempDirectory("extraction-inspector-spec")

  private val textFile = dir.resolve("notes.txt")
  private val docxFile = dir.resolve("report.docx")

  override def beforeAll(): Unit = {
    Files.writeString(textFile, "First line of notes.\n\nSecond line.\n", StandardCharsets.UTF_8)
    writeDocx(docxFile, Seq("Quarterly report", "Revenue grew in every region."))
  }

  override def afterAll(): Unit =
    Files.walk(dir).sorted(Comparator.reverseOrder()).iterator().asScala.foreach(Files.deleteIfExists)

  private def ok[A](result: org.llm4s.types.Result[A]): A = result.fold(e => fail(e.message), identity)

  "DocumentExtractionInspection" should "report a text file's media type, extractor, counts and preview" in {
    val report = inspectFile(textFile)
    val doc    = ok(report.extraction)

    report.mimeType shouldBe "text/plain"
    report.mediaType shouldBe Some(MediaType.PlainText)
    report.extractor shouldBe "UTF-8 text"
    doc.text should include("Second line.")
    doc.metadata("filename") shouldBe "notes.txt"

    val rendered = render(Seq(report))
    rendered should include(s"Characters: ${doc.text.length}")
    rendered should include("Lines     : 2")
    rendered should include("Preview   : First line of notes. Second line.")
    rendered should include("filename=notes.txt")
    rendered should include("Inspected 1 file(s): 1 extracted, 0 failed")
  }

  it should "extract a DOCX generated with POI through the POI extractor" in {
    val report = inspectFile(docxFile)
    val doc    = ok(report.extraction)

    report.mimeType shouldBe "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    report.extractor shouldBe "Apache POI (XWPF)"
    report.mediaType shouldBe None
    doc.text shouldBe "Quarterly report\nRevenue grew in every region."
    render(Seq(report)) should include("Media type: unrecognised")
  }

  it should "inspect every file of a directory, sorted by path" in {
    val reports = ok(inspect(dir))
    reports.map(_.path.getFileName.toString) shouldBe Seq("notes.txt", "report.docx")
    reports.forall(_.succeeded) shouldBe true
  }

  it should "report a missing path as a Result failure" in {
    val missing = dir.resolve("does-not-exist")
    inspect(missing).left.map(_.message).left.getOrElse("") should include(s"No such file or directory: $missing")
  }

  it should "keep a failed extraction in the report instead of throwing" in {
    val broken = dir.resolve("sub").resolve("broken.pdf")
    Files.createDirectories(broken.getParent)
    Files.writeString(broken, "%PDF-1.7\nthe header is right, the rest is not a PDF", StandardCharsets.UTF_8)

    val report = inspectFile(broken)
    report.extractor shouldBe "Apache PDFBox"
    report.succeeded shouldBe false
    render(Seq(report)) should include("Failed to extract text from PDF 'broken.pdf'")
    render(Seq(report)) should include("Inspected 1 file(s): 0 extracted, 1 failed")
    Files.delete(broken)
    Files.delete(broken.getParent)
  }

  it should "inspect the generated sample documents when run with no argument" in {
    val output = ok(DocumentExtractionInspection.run(Nil))

    output should include("notes.txt")
    output should include("Preview   : # Extraction guide")
    output should include("Revenue grew in every region.")
    output should include("A one-page PDF written by PDFBox.")
    output should include(s"Media type: ${MediaType.Pdf} (${MediaType.Pdf.category})")
    output should include("Failed to extract text from PDF 'broken.pdf'")
    output should include("Inspected 6 file(s): 5 extracted, 1 failed")
  }

  it should "resolve a quoted command-line path and run main on it" in {
    resolvePath(s"  '$textFile'  ") shouldBe textFile.toAbsolutePath

    val buffer = new ByteArrayOutputStream()
    Console.withOut(new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
      DocumentExtractionInspector.main(Array(s"\"$textFile\""))
    }
    buffer.toString(StandardCharsets.UTF_8) should include("Inspected 1 file(s): 1 extracted, 0 failed")
  }

  it should "print a failure when main is given a missing path" in {
    val buffer = new ByteArrayOutputStream()
    Console.withOut(new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
      DocumentExtractionInspector.main(Array(dir.resolve("nope").toString))
    }
    buffer
      .toString(StandardCharsets.UTF_8) should (include("Inspection failed:").and(include("No such file or directory")))
  }

  it should "truncate a long preview and mark an empty one" in {
    preview("x" * (PreviewLength + 10)) shouldBe ("x" * PreviewLength) + "..."
    preview(" \n\t ") shouldBe "(no text)"
    extractorFor("application/json") shouldBe "UTF-8 text"
    extractorFor("application/msword") shouldBe "Apache Tika"
    extractorFor("image/png") shouldBe "Apache Tika (fallback)"
  }
}
