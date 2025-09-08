package org.llm4s.llmconnect.extractors

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.ss.usermodel.{ DataFormatter, Row }
import org.jsoup.Jsoup
import org.llm4s.llmconnect.config.EmbeddingConfig
import org.slf4j.LoggerFactory

import java.io.{ File, FileInputStream }
import java.nio.charset.{ Charset, StandardCharsets }
import java.nio.file.{ Files, Paths }
import scala.collection.mutable
import scala.jdk.CollectionConverters._

/** Unified TEXT extractor for local files and HTTP/HTTPS URLs. */
object TextDataExtractor {
  private val logger = LoggerFactory.getLogger(getClass)

  // ---------- Supported file types ----------
  private val supported: Set[String] = Set(
    // Plain / markup / configs / logs / code treated as text
    "txt",
    "log",
    "md",
    "rst",
    "adoc",
    "asciidoc",
    "tex",
    "csv",
    "tsv",
    "yaml",
    "yml",
    "ini",
    "env",
    "properties",
    "json",
    "jsonl",
    "ndjson",
    "xml",
    "html",
    "htm",
    // Office & PDF
    "pdf",
    "docx",
    "xlsx",
    // Code (text)
    "py",
    "java",
    "scala",
    "kt",
    "js",
    "ts",
    "tsx",
    "jsx",
    "c",
    "cpp",
    "h",
    "hpp",
    "rs",
    "go",
    "rb",
    "php",
    "cs",
    "swift",
    "sh",
    "bat",
    "ps1",
    "sql"
  )

  def supports(ext: String): Boolean = supported.contains(ext.toLowerCase)

  // ---------- URL ----------
  /** Extract readable text from an HTTP/HTTPS URL (HTML). */
  def fromUrl(url: String): ExtractResult = {
    val doc = Jsoup
      .connect(url)
      .timeout(EmbeddingConfig.httpTimeoutMs)
      .userAgent("llm4s/1.0 (+https://github.com)")
      .get()

    val title    = Option(doc.title()).filter(_.nonEmpty)
    val bodyText = Option(doc.body()).map(_.text()).getOrElse("")
    ExtractResult(
      content = bodyText,
      mimeType = Some("text/html"),
      metadata = Map("source" -> "url", "url" -> url) ++ title.map("title" -> _)
    )
  }

  // ---------- Files ----------
  /** Extract readable text from a local file path. */
  def fromFile(path: String): ExtractResult = {
    val file = new File(path)
    require(file.exists(), s"File not found: $path")
    val size = file.length()
    require(size <= EmbeddingConfig.maxBytes, s"File exceeds max size (${EmbeddingConfig.maxBytes} bytes): $path")

    val ext = extension(path)
    val text: String = ext match {
      // Plain-like (include code/configs)
      case "txt" | "log" | "md" | "rst" | "adoc" | "asciidoc" | "tex" | "py" | "java" | "scala" | "kt" | "js" | "ts" |
          "tsx" | "jsx" | "c" | "cpp" | "h" | "hpp" | "rs" | "go" | "rb" | "php" | "cs" | "swift" | "sh" | "bat" |
          "ps1" | "sql" | "yaml" | "yml" | "ini" | "env" | "properties" =>
           "tsx" | "jsx" | "c" | "cpp" | "h" | "hpp" | "rs" | "go" | "rb" | "php" | "cs" | "swift" | "sh" | "bat" |
           "ps1" | "sql" | "yaml" | "yml" | "ini" | "env" | "properties" =>
        readPlain(path)

      // Delimited
      case "csv" => readDelimited(path, ',')
      case "tsv" => readDelimited(path, '\t')

      // Structured
      case "json"             => readJson(path)
      case "jsonl" | "ndjson" => readJsonLines(path)
      case "xml"              => readXml(path)
      case "html" | "htm"     => readHtml(path)

      // Complex containers
      case "pdf"  => readPdf(path)
      case "docx" => readDocx(path)
      case "xlsx" => readXlsx(path)

      case other =>
        logger.warn(s"[TextDataExtractor] Unknown extension '.$other'. Falling back to plain reader.")
        readPlain(path)
    }

    ExtractResult(
      content = text,
      mimeType = Some(guessMime(ext)),
      metadata = Map(
        "source"     -> "file",
        "filename"   -> file.getName,
        "ext"        -> ext,
        "size_bytes" -> size.toString
      )
    )
  }

  // ---------- Helpers ----------

  private def extension(path: String): String =
    path.split("\\.").lastOption.map(_.toLowerCase).getOrElse("")

  /** Read whole file as String with lightweight BOM detection. */
  private def readPlain(path: String): String = {
    val bytes   = Files.readAllBytes(Paths.get(path))
    val charset = detectCharset(bytes).getOrElse(StandardCharsets.UTF_8)
    new String(bytes, charset)
  }

  /** Delimited reader that **does not** use regex and preserves trailing empties. */
  private def readDelimited(path: String, sep: Char): String = {
    val lines = Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8).asScala
    lines.map(line => splitKeepEmpty(line, sep).map(_.trim).mkString(" | ")).mkString("\n")
  }

  /** Split by a single char delimiter, keeping empty fields (including trailing). */
  private def splitKeepEmpty(s: String, sep: Char): Array[String] = {
    import scala.collection.mutable.ArrayBuffer
    val out   = ArrayBuffer[String]()
    var start = 0
    var i     = 0
    while (i <= s.length) {
      if (i == s.length || s.charAt(i) == sep) {
        out += s.substring(start, i)
        start = i + 1
      }
      i += 1
    }
    out.toArray
  }

  /** Flatten JSON into "dotted.key: value" lines (best-effort). */
  private def readJson(path: String): String = {
    val s    = readPlain(path)
    val json = ujson.read(s)
    flattenJson(json).mkString("\n")
  }

  /** Read JSONL/NDJSON by flattening each record onto a single line. */
  private def readJsonLines(path: String): String = {
    val lines = Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8).asScala
    lines
      .filter(_.trim.nonEmpty)
      .map { line =>
        val j = ujson.read(line)
        flattenJson(j).mkString(" ")
      }
      .mkString("\n")
  }

  /** Strip tags from XML, keep text content. */
  private def readXml(path: String): String = {
    val s   = readPlain(path)
    val doc = Jsoup.parse(s, "", org.jsoup.parser.Parser.xmlParser())
    doc.text()
  }

  /** Strip tags from HTML body, keep text content. */
  private def readHtml(path: String): String = {
    val s = readPlain(path)
    Jsoup.parse(s).body().text()
  }

  /** Extract text from PDF with page cap (warns on truncation). */
  private def readPdf(path: String): String = {
    val doc = PDDocument.load(new File(path))
    try {
      val pages = doc.getNumberOfPages
      if (pages > EmbeddingConfig.maxPdfPages) {
        logger.warn(s"[TextDataExtractor] PDF has $pages pages; truncating to ${EmbeddingConfig.maxPdfPages}.")
      }
      val stripper = new PDFTextStripper()
      stripper.setStartPage(1)
      stripper.setEndPage(math.min(pages, EmbeddingConfig.maxPdfPages))
      stripper.getText(doc)
    } finally doc.close()
  }

  /** Extract paragraphs and tables from DOCX. */
  private def readDocx(path: String): String = {
    val is = new FileInputStream(path)
    try {
      val doc = new XWPFDocument(is)
      try {
        val paras = doc.getParagraphs.asScala.map(_.getText).filter(_.trim.nonEmpty)
        val tables = doc.getTables.asScala.flatMap { table =>
          table.getRows.asScala.map(row => row.getTableCells.asScala.map(_.getText.trim).mkString(" | "))
        }
        (paras ++ tables).mkString("\n")
      } finally doc.close()
    } finally is.close()
  }

  /** Extract rows from XLSX using DataFormatter (human-readable), preserving blanks. */
  private def readXlsx(path: String): String = {
    val is = new FileInputStream(path)
    try {
      val wb        = new XSSFWorkbook(is)
      val formatter = new DataFormatter()
      val evaluator = wb.getCreationHelper.createFormulaEvaluator()
      try
        wb.iterator()
          .asScala
          .flatMap { sheet =>
            sheet.iterator().asScala.map { row =>
              val last = math.max(0, row.getLastCellNum.toInt) // lastCellNum can be -1
              val cells = (0 until last).map { idx =>
                val cell = row.getCell(idx, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)
                if (cell == null) "" else formatter.formatCellValue(cell, evaluator)
              }
              cells.mkString(" | ")
            }
          }
          .mkString("\n")
      finally wb.close()
    } finally is.close()
  }

  /** Very small BOM-based charset detector (UTF-8/UTF-16). */
  private def detectCharset(bytes: Array[Byte]): Option[Charset] =
    if (
      bytes.length >= 3 &&
      bytes(0) == 0xef.toByte && bytes(1) == 0xbb.toByte && bytes(2) == 0xbf.toByte
    )
    if (
      bytes.length >= 3 &&
        bytes(0) == 0xef.toByte && bytes(1) == 0xbb.toByte && bytes(2) == 0xbf.toByte
    )
      Some(StandardCharsets.UTF_8)
    else if (bytes.length >= 2 && bytes(0) == 0xfe.toByte && bytes(1) == 0xff.toByte)
      Some(StandardCharsets.UTF_16BE)
    else if (bytes.length >= 2 && bytes(0) == 0xff.toByte && bytes(1) == 0xfe.toByte)
      Some(StandardCharsets.UTF_16LE)
    else None

  /** Flatten JSON values into dotted keys. */
  private def flattenJson(
                           v: ujson.Value,
                           prefix: String = "",
                           acc: mutable.Buffer[String] = mutable.Buffer.empty
                         ): Seq[String] = {
    v match {
      case ujson.Obj(kv) =>
        kv.foreach { case (k, vv) =>
          val p = if (prefix.isEmpty) k else s"$prefix.$k"
          flattenJson(vv, p, acc)
        }
      case ujson.Arr(items) =>
        items.zipWithIndex.foreach { case (vv, i) =>
          flattenJson(vv, s"$prefix[$i]", acc)
        }
      case other =>
        acc.append(s"$prefix: ${other.render()}")
    }
    acc.toSeq
  }

  /** Best-effort MIME by extension (for metadata only). */
  private def guessMime(ext: String): String = ext match {
    case "md" => "text/markdown"
    case "txt" | "log" | "rst" | "adoc" | "asciidoc" | "tex" | "py" | "java" | "scala" | "kt" | "js" | "ts" | "tsx" |
        "jsx" | "c" | "cpp" | "h" | "hpp" | "rs" | "go" | "rb" | "php" | "cs" | "swift" | "sh" | "bat" | "ps1" | "sql" |
        "yaml" | "yml" | "ini" | "env" | "properties" =>
    case "md" => "text/markdown"
    case "txt" | "log" | "rst" | "adoc" | "asciidoc" | "tex" | "py" | "java" | "scala" | "kt" | "js" | "ts" | "tsx" |
         "jsx" | "c" | "cpp" | "h" | "hpp" | "rs" | "go" | "rb" | "php" | "cs" | "swift" | "sh" | "bat" | "ps1" | "sql" |
         "yaml" | "yml" | "ini" | "env" | "properties" =>
      "text/plain"
    case "csv"              => "text/csv"
    case "tsv"              => "text/tab-separated-values"
    case "json"             => "application/json"
    case "jsonl" | "ndjson" => "application/x-ndjson"
    case "xml"              => "application/xml"
    case "html" | "htm"     => "text/html"
    case "pdf"              => "application/pdf"
    case "docx"             => "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    case "xlsx"             => "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    case _                  => "text/plain"
  }
}
