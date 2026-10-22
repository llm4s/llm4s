package org.llm4s.samples.rag

import org.apache.pdfbox.pdmodel.font.{ PDType1Font, Standard14Fonts }
import org.apache.pdfbox.pdmodel.{ PDDocument, PDPage, PDPageContentStream }
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.llm4s.error.ProcessingError
import org.llm4s.extract.{ ExtractedDocument, TikaDocumentExtractor }
import org.llm4s.media.MediaType
import org.llm4s.types.Result

import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path, Paths }
import scala.jdk.CollectionConverters._
import scala.util.{ Try, Using }

/**
 * Inspect what `llm4s-rag`'s document extraction makes of a file or a directory of files.
 *
 * For every file it prints the MIME type Tika detects, the `org.llm4s.media.MediaType` that
 * MIME resolves to, which extractor `TikaDocumentExtractor` dispatches to, the character and
 * line counts of the extracted text, the extractor's metadata and a short preview. A file that
 * cannot be extracted is reported with its error rather than stopping the run.
 *
 * With no argument it writes a small set of sample documents (text, Markdown, HTML, DOCX, PDF and
 * a deliberately corrupt PDF) into a temporary directory and inspects those. No API key, network
 * or LLM is needed.
 *
 * Run with:
 * {{{
 * sbt "samples/runMain org.llm4s.samples.rag.DocumentExtractionInspector"
 * sbt "samples/runMain org.llm4s.samples.rag.DocumentExtractionInspector /path/to/report.pdf"
 * sbt "samples/runMain org.llm4s.samples.rag.DocumentExtractionInspector /path/to/docs"
 * }}}
 */
object DocumentExtractionInspector {
  def main(args: Array[String]): Unit =
    DocumentExtractionInspection.run(args.toSeq) match {
      case Right(output) => println(output)
      case Left(error)   => println(s"Inspection failed: ${error.message}")
    }
}

/** The logic behind [[DocumentExtractionInspector]], kept apart from `main` so it can be tested. */
object DocumentExtractionInspection {

  /** Characters of extracted text shown in a report's preview. */
  val PreviewLength: Int = 200

  /** What extraction made of one file. `extraction` is the extractor's own `Result`. */
  final case class FileReport(
    path: Path,
    mimeType: String,
    mediaType: Option[MediaType],
    extractor: String,
    extraction: Result[ExtractedDocument]
  ) {
    def succeeded: Boolean = extraction.isRight
  }

  /**
   * Inspect the file or directory named by the first argument, or freshly generated sample
   * documents when there is none, and return the rendered report.
   */
  def run(args: Seq[String]): Result[String] =
    for {
      target <- args.headOption match {
        case Some(arg) => Right(resolvePath(arg))
        case None =>
          Try(Files.createTempDirectory("llm4s-extraction-")).toEither.left
            .map(ex =>
              ProcessingError("extraction-inspector", s"Cannot create a temp directory: ${ex.getMessage}", Some(ex))
            )
            .flatMap(writeSampleDocuments)
      }
      reports <- inspect(target)
    } yield render(reports)

  /** Resolve a command-line path: trimmed, with one pair of surrounding quotes stripped, as `extractFromPath` does. */
  def resolvePath(raw: String): Path = {
    val trimmed = raw.trim
    val quoted  = trimmed.length >= 2 && Seq("\"", "'").exists(q => trimmed.startsWith(q) && trimmed.endsWith(q))
    Paths.get(if (quoted) trimmed.substring(1, trimmed.length - 1) else trimmed).toAbsolutePath
  }

  /** Inspect one file, or every regular, non-hidden file under a directory (recursively, sorted by path). */
  def inspect(target: Path): Result[Seq[FileReport]] =
    if (Files.isRegularFile(target)) Right(Seq(inspectFile(target)))
    else if (Files.isDirectory(target))
      Try(Using.resource(Files.walk(target))(_.iterator().asScala.toVector))
        .map(_.filter(p => Files.isRegularFile(p) && !p.getFileName.toString.startsWith(".")).sortBy(_.toString))
        .toEither
        .left
        .map(ex => ProcessingError("extraction-inspector", s"Cannot list '$target': ${ex.getMessage}", Some(ex)))
        .map(_.map(inspectFile))
    else Left(ProcessingError("extraction-inspector", s"No such file or directory: $target"))

  /** Detect, classify and extract one file. A read or extraction failure is kept in the report. */
  def inspectFile(file: Path): FileReport = {
    val name = file.getFileName.toString
    Try(Files.readAllBytes(file)).toEither match {
      case Left(ex) =>
        FileReport(
          file,
          "unknown",
          MediaType.fromPath(name),
          "none",
          Left(ProcessingError("extraction-inspector", s"Cannot read '$file': ${ex.getMessage}", Some(ex)))
        )
      case Right(bytes) =>
        val mime = TikaDocumentExtractor.detectMimeType(bytes, name)
        FileReport(
          path = file,
          mimeType = mime,
          mediaType = MediaType.fromMimeType(mime).orElse(MediaType.fromPath(name)),
          extractor = extractorFor(mime),
          extraction = TikaDocumentExtractor.extract(bytes, name, Some(mime))
        )
    }
  }

  /**
   * The extractor `TikaDocumentExtractor` dispatches a MIME type to. This mirrors its dispatch so
   * the report can name the library that produced the text.
   */
  def extractorFor(mimeType: String): String = mimeType.toLowerCase match {
    case "application/pdf"                                                         => "Apache PDFBox"
    case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" => "Apache POI (XWPF)"
    case "application/msword"                                                      => "Apache Tika"
    case mt if mt.startsWith("text/")                                              => "UTF-8 text"
    case "application/json" | "application/xml"                                    => "UTF-8 text"
    case _                                                                         => "Apache Tika (fallback)"
  }

  /** Render reports as console text, one block per file, then a one-line summary. */
  def render(reports: Seq[FileReport]): String = {
    val blocks    = reports.map(renderOne)
    val succeeded = reports.count(_.succeeded)
    val summary   = s"Inspected ${reports.size} file(s): $succeeded extracted, ${reports.size - succeeded} failed"
    (blocks :+ summary).mkString("\n\n")
  }

  private def renderOne(report: FileReport): String = {
    val header = Seq(
      s"== ${report.path}",
      s"   MIME type : ${report.mimeType}",
      s"   Media type: ${report.mediaType.fold("unrecognised")(m => s"$m (${m.category})")}",
      s"   Extractor : ${report.extractor}"
    )
    val body = report.extraction match {
      case Right(doc) =>
        Seq(
          s"   Format    : ${doc.format.name}",
          s"   Characters: ${doc.text.length}",
          s"   Lines     : ${doc.text.linesIterator.count(_.trim.nonEmpty)}",
          s"   Metadata  : ${doc.metadata.toSeq.sortBy(_._1).map { case (k, v) => s"$k=$v" }.mkString(", ")}",
          s"   Preview   : ${preview(doc.text)}"
        )
      case Left(error) =>
        Seq(s"   Error     : ${error.message}")
    }
    (header ++ body).mkString("\n")
  }

  /** The first [[PreviewLength]] characters of `text` on one line, with whitespace runs collapsed. */
  def preview(text: String): String = {
    val flat = text.replaceAll("\\s+", " ").trim
    if (flat.isEmpty) "(no text)"
    else if (flat.length <= PreviewLength) flat
    else flat.take(PreviewLength) + "..."
  }

  /**
   * Write the sample documents into `dir`: plain text, Markdown, HTML, a DOCX built with POI, a
   * PDF built with PDFBox, and a corrupt PDF to show how a failure is reported.
   */
  def writeSampleDocuments(dir: Path): Result[Path] =
    Try {
      Files.writeString(
        dir.resolve("notes.txt"),
        "LLM4S extracts text from documents.\nThis is a plain text file.\n",
        StandardCharsets.UTF_8
      )
      Files.writeString(
        dir.resolve("guide.md"),
        "# Extraction guide\n\nMarkdown is read as **text**, markup included.\n",
        StandardCharsets.UTF_8
      )
      Files.writeString(
        dir.resolve("page.html"),
        "<html><head><title>Sample page</title></head><body><p>HTML is read as text.</p></body></html>\n",
        StandardCharsets.UTF_8
      )
      writeDocx(dir.resolve("report.docx"), Seq("Quarterly report", "Revenue grew in every region."))
      writePdf(dir.resolve("summary.pdf"), "A one-page PDF written by PDFBox.")
      Files.write(
        dir.resolve("broken.pdf"),
        "%PDF-1.7\nthe header is right, the rest is not a PDF".getBytes(StandardCharsets.UTF_8)
      )
      dir
    }.toEither.left
      .map(ex => ProcessingError("extraction-inspector", s"Cannot write sample documents: ${ex.getMessage}", Some(ex)))

  /** Write a DOCX with one paragraph per entry of `paragraphs`. */
  def writeDocx(file: Path, paragraphs: Seq[String]): Unit =
    Using.resource(new XWPFDocument()) { doc =>
      paragraphs.foreach(text => doc.createParagraph().createRun().setText(text))
      Using.resource(new FileOutputStream(file.toFile))(out => doc.write(out))
    }

  /** Write a one-page PDF holding `text` in Helvetica. */
  def writePdf(file: Path, text: String): Unit =
    Using.resource(new PDDocument()) { doc =>
      val page = new PDPage()
      doc.addPage(page)
      Using.resource(new PDPageContentStream(doc, page)) { content =>
        content.beginText()
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12)
        content.newLineAtOffset(72, 700)
        content.showText(text)
        content.endText()
      }
      doc.save(file.toFile)
    }
}
