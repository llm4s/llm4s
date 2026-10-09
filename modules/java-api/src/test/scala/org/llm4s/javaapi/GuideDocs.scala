package org.llm4s.javaapi

import java.nio.file.{ Files, Path, Paths }

/**
 * Reads a guide from `docs/` so that a spec can test the guide's own code blocks and not a copy of them: a block
 * that is edited in the guide is the block that is tested. The repository root is found by walking up from the
 * working directory, so the specs run from the build root or from a module directory.
 */
object GuideDocs {

  def repoRoot(markerFile: String): Path =
    Iterator
      .iterate(Paths.get(System.getProperty("user.dir")).toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(dir => Files.exists(dir.resolve(markerFile)))
      .getOrElse(throw new IllegalStateException(s"$markerFile was not found above ${System.getProperty("user.dir")}"))

  def read(markerFile: String, relative: String): String =
    new String(Files.readAllBytes(repoRoot(markerFile).resolve(relative)), "UTF-8")

  /** The fenced code blocks of a markdown text as (language, code), in order. */
  def blocks(markdown: String): List[(String, String)] =
    "(?s)```([a-z]*)\\n(.*?)```".r.findAllMatchIn(markdown).map(m => (m.group(1), m.group(2))).toList

  def blocksIn(markdown: String, language: String): List[String] =
    blocks(markdown).collect { case (`language`, code) => code }

  /** Whitespace collapsed to single spaces, so that indentation and line breaks do not matter in a comparison. */
  def squash(text: String): String = text.replaceAll("\\s+", " ").trim
}
