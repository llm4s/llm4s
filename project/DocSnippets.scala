import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest

import sbt._

/**
 * Turns the Scala code blocks of the documentation into test sources, so that `sbt samples/Test/compile` (and so every
 * `sbt test`) fails when a documented snippet no longer compiles against the code (issue #1477).
 *
 * Each fenced `scala` block of an in-scope page becomes an object in package `org.llm4s.docsnippets`, whose body is the
 * block as written, in a method of a class that extends `org.llm4s.samples.docs.SnippetEnv` (a few stand-in values such
 * as `client`, for the fragments that assume one). The generated file is padded so that its line numbers are the page's
 * line numbers: a compiler error at `Snippet_first_example_L49.scala:63` is `first-example.md` line 63.
 *
 * Two kinds of block are listed in `modules/samples/src/test/docs-snippets/skip.txt`, as `page | block hash | action |
 * detail`, because they cannot compile as written. `skip | reason` leaves the block out (an sbt build definition, a
 * few lines of a for-comprehension shown on their own). `with | imports` compiles it after those imports, for a
 * fragment that relies on an import shown in a block before it (separate imports with `;`). The hash is of the block's
 * text with whitespace collapsed, so editing a listed block makes its entry stale and the build fails until someone
 * decides again.
 *
 * Everything here fails loudly: an unreadable page, a page with no Scala blocks, a skip entry that matches nothing or
 * has no reason, and a malformed manifest line are build errors, never a silent no-op.
 */
object DocSnippets {

  /** The pages whose snippets are compiled. Paths are relative to `docs/`. */
  val pages: Seq[String] = Seq(
    "getting-started/configuration.md",
    "getting-started/first-example.md",
    "getting-started/ollama-quickstart.md",
    "getting-started/testing-guide.md"
  )

  val packageName = "org.llm4s.docsnippets"

  final case class Block(page: String, firstLine: Int, text: String) {

    /** First 10 hex digits of the SHA-1 of the block with all whitespace runs collapsed. */
    def hash: String = {
      val normalised = text.replaceAll("\\s+", " ").trim
      val digest     = MessageDigest.getInstance("SHA-1").digest(normalised.getBytes(StandardCharsets.UTF_8))
      digest.map(b => f"${b & 0xff}%02x").mkString.take(10)
    }

    def objectName: String = {
      val stem = page.stripSuffix(".md").replaceAll("[^A-Za-z0-9]+", "_")
      s"Snippet_${stem}_L$firstLine"
    }
  }

  final case class Entry(page: String, hash: String, action: String, detail: String)

  val Actions: Set[String] = Set("skip", "with")

  // An opening fence: three backticks, then an optional info string whose first word is the language.
  private val Opening = "^```(\\S*).*$".r
  private val Closing = "^```\\s*$"

  /** The fenced `scala` blocks of a page, with the 1-based line of each block's first code line. */
  def extract(page: String, content: String): Seq[Block] = {
    val lines = content.split("\r?\n", -1).toVector
    val out   = Vector.newBuilder[Block]
    var i     = 0
    while (i < lines.length)
      lines(i) match {
        case Opening(lang) =>
          val start = i + 1
          var end   = start
          while (end < lines.length && !lines(end).matches(Closing)) end += 1
          if (end >= lines.length)
            throw new MessageOnlyException(s"$page: the code fence opened at line ${i + 1} is never closed")
          if (lang == "scala") out += Block(page, start + 1, lines.slice(start, end).mkString("\n"))
          i = end + 1
        case _ => i += 1
      }
    out.result()
  }

  /** Lines of the form `page | hash | action | detail`; blank lines and `#` comments are ignored. */
  def parseManifest(lines: Seq[String], source: String): Seq[Entry] =
    lines.zipWithIndex.flatMap { case (raw, idx) =>
      val line = raw.trim
      if (line.isEmpty || line.startsWith("#")) None
      else {
        val parts = line.split("\\|", 4).map(_.trim)
        if (parts.length != 4 || parts.exists(_.isEmpty))
          throw new MessageOnlyException(
            s"$source:${idx + 1}: expected `page | block hash | action | detail` with all four parts, got: $line"
          )
        if (!Actions.contains(parts(2)))
          throw new MessageOnlyException(
            s"$source:${idx + 1}: the action must be one of ${Actions.toSeq.sorted.mkString(", ")}, got: ${parts(2)}"
          )
        Some(Entry(parts(0), parts(1), parts(2), parts(3)))
      }
    }

  /** The source of one block: header on line 1, padding, the block verbatim from its page line. */
  def render(block: Block, imports: Seq[String] = Nil): String = {
    val preamble = imports.map(i => s" $i;").mkString
    val header = s"package $packageName; object ${block.objectName} extends org.llm4s.samples.docs.SnippetEnv " +
      s"{ def snippet(): Unit = {$preamble"
    val padding = "\n" * (block.firstLine - 1)
    s"$header$padding${block.text}\n}}\n"
  }

  /** Reads every in-scope page, checks the manifest, writes one file per compiled block and returns the files. */
  def generate(docsDir: File, manifest: File, outDir: File, log: Logger): Seq[File] = {
    val entries = readManifest(manifest)
    val all     = pages.map(page => page -> read(docsDir, page))

    entries.groupBy(s => (s.page, s.hash)).foreach { case ((page, hash), dup) =>
      if (dup.length > 1)
        throw new MessageOnlyException(s"${manifest.getName}: $page $hash is listed ${dup.length} times")
    }
    entries.foreach { s =>
      if (!pages.contains(s.page))
        throw new MessageOnlyException(s"${manifest.getName}: ${s.page} is not one of the pages in DocSnippets.pages")
    }

    IO.delete(outDir)
    IO.createDirectory(outDir)

    val compiled = Seq.newBuilder[File]
    all.foreach { case (page, content) =>
      val blocks = extract(page, content)
      if (blocks.isEmpty)
        throw new MessageOnlyException(
          s"docs/$page has no ```scala blocks: either it changed beyond recognition or the extractor is broken"
        )
      val pageEntries = entries.filter(_.page == page)
      val hashes      = blocks.map(_.hash).toSet
      pageEntries.filterNot(s => hashes.contains(s.hash)).foreach { s =>
        throw new MessageOnlyException(
          s"${manifest.getName}: the ${s.action} entry ${s.hash} of $page no longer matches a block (the block was edited " +
            "or removed). Run `sbt samples/docSnippetsReport` for the current hashes, then compile the block or list it again."
        )
      }
      val byHash  = pageEntries.map(e => e.hash -> e).toMap
      val skipped = pageEntries.filter(_.action == "skip").map(_.hash).toSet
      blocks.filterNot(b => skipped.contains(b.hash)).foreach { b =>
        val imports = byHash
          .get(b.hash)
          .filter(_.action == "with")
          .toSeq
          .flatMap(_.detail.split(";").map(_.trim).filter(_.nonEmpty))
        val file = outDir / s"${b.objectName}.scala"
        IO.write(file, render(b, imports), StandardCharsets.UTF_8)
        compiled += file
      }
      log.debug(s"docs/$page: ${blocks.length} Scala blocks, ${skipped.size} skipped")
    }
    compiled.result()
  }

  /** A table of every block: page, line, hash, status and its first line. Run as `sbt docSnippetsReport`. */
  def report(docsDir: File, manifest: File): String = {
    val entries = readManifest(manifest).map(e => (e.page, e.hash) -> e).toMap
    val rows = pages.flatMap { page =>
      extract(page, read(docsDir, page)).map { b =>
        val status = entries.get((page, b.hash)).map(e => s"${e.action}: ${e.detail}").getOrElse("compiled")
        val first  = b.text.split("\n", -1).headOption.getOrElse("").trim
        f"${page.stripPrefix("getting-started/")}%-26s L${b.firstLine}%-5d ${b.hash}%-11s $status%-48s $first"
      }
    }
    rows.mkString("\n")
  }

  private def read(docsDir: File, page: String): String = {
    val file = docsDir / page
    if (!file.isFile) throw new MessageOnlyException(s"docs page not found: $file")
    new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8)
  }

  private def readManifest(manifest: File): Seq[Entry] = {
    if (!manifest.isFile) throw new MessageOnlyException(s"documentation snippet manifest not found: $manifest")
    parseManifest(IO.readLines(manifest, StandardCharsets.UTF_8), manifest.getName)
  }
}
