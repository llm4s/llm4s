package org.llm4s.samples.chat.tui

import java.nio.file.{ Files, Path, Paths }

/**
 * Lightweight lookup of environment values that falls back to a `.env`
 * file in the project root.
 *
 * The runtime can't fork (we need the real TTY for the TUI demo), so we
 * can't ask sbt to forward `.env` for us. Instead we parse it ourselves
 * once at startup. The JVM's actual environment always wins — `.env`
 * only fills holes for users who haven't sourced it into their shell.
 *
 * Lines beginning with `#` are ignored. Values may be optionally wrapped
 * in single or double quotes; the wrapping is stripped. Duplicate keys
 * resolve to the *first* occurrence (so users can pin a default at the
 * top of the file and leave alternatives commented or below it).
 */
object ChatTuiEnv:

  private lazy val fromFile: Map[String, String] = loadDotEnv()

  /** Lookup `name`. Real environment first, then `.env` file fallback. */
  def get(name: String): Option[String] =
    sys.env
      .get(name)
      .map(_.trim)
      .filter(_.nonEmpty)
      .orElse(fromFile.get(name).map(_.trim).filter(_.nonEmpty))

  /** Lookup `name`, returning `default` when neither source has a value. */
  def getOrElse(name: String, default: => String): String =
    get(name).getOrElse(default)

  /** All keys defined either in the JVM env or the `.env` file. */
  def keys: Set[String] = sys.env.keySet ++ fromFile.keySet

  private def loadDotEnv(): Map[String, String] =
    candidatePaths().find(p => Files.exists(p) && Files.isRegularFile(p)) match {
      case Some(path) => parse(path)
      case None       => Map.empty
    }

  /** Search the working directory and walk up to two levels of parents. */
  private def candidatePaths(): List[Path] =
    val cwd = Paths.get(sys.props.getOrElse("user.dir", "."))
    val candidates = (0 to 3).flatMap { up =>
      var p: Path = cwd
      var i       = 0
      while i < up && p != null do
        p = p.getParent
        i += 1
      Option(p).map(_.resolve(".env"))
    }
    candidates.toList.distinct

  private def parse(path: Path): Map[String, String] =
    try {
      val builder = Map.newBuilder[String, String]
      val seen    = scala.collection.mutable.Set.empty[String]
      Files.readAllLines(path).forEach { raw =>
        val line = raw.trim
        if line.nonEmpty && !line.startsWith("#") then
          line.split("=", 2) match {
            case Array(k, v) =>
              val key   = k.trim
              val value = stripQuotes(v.trim)
              if key.nonEmpty && !seen.contains(key) then
                seen += key
                builder += (key -> value)
            case _ => ()
          }
      }
      builder.result()
    } catch {
      case _: Throwable => Map.empty
    }

  private def stripQuotes(v: String): String =
    if v.length >= 2 then
      val first = v.charAt(0)
      val last  = v.charAt(v.length - 1)
      if (first == '"' && last == '"') || (first == '\'' && last == '\'') then v.substring(1, v.length - 1)
      else v
    else v
