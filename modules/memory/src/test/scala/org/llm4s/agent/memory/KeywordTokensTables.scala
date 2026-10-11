package org.llm4s.agent.memory

import java.sql.DriverManager
import scala.util.{ Try, Using }

/**
 * Regenerates the tables in [[KeywordTokens]] from the `sqlite-jdbc` on the classpath, for when its FTS5 tokenizer
 * changes (a new SQLite with newer Unicode tables). Run it with
 * {{{
 * sbt "memory/Test/runMain org.llm4s.agent.memory.KeywordTokensTables"
 * }}}
 * and replace `SeparatorTable`, `AccentTable` and `CaseTable` in `KeywordTokens.scala` with what it prints (or
 * writes to the file named by its one argument, which keeps sbt's log prefixes out of it).
 *
 * It tokenizes `a<code point>b` for every non-ASCII code point with FTS5 `unicode61` (`remove_diacritics=1`, as
 * `SQLiteMemoryStore` creates its index) and reads the words back through `fts5vocab`, the same way
 * `KeywordSearchSpec` checks `KeywordTokens` against FTS5: one word `a?b` means the code point is a token character
 * that folds to `?`, the words `a` and `b` that it is a separator, and the one word `ab` that it is a combining accent
 * FTS5 drops inside a word (a separator elsewhere, so it goes in the separator table too). Surrogates cannot be
 * tokenized on their own and are separators.
 */
object KeywordTokensTables {

  /** The `sqlite-jdbc` version the tables in `KeywordTokens` were generated with. */
  val GeneratedWith: String = "3.53.2.0"

  /** The sbt command that runs this generator. */
  val Command: String = """sbt "memory/Test/runMain org.llm4s.agent.memory.KeywordTokensTables""""

  final case class Tables(separators: String, accents: String, cases: String) {

    /** The three tables as the Scala source of `KeywordTokens`, wrapped as it is there. */
    def scalaSource: String =
      Seq("SeparatorTable" -> separators, "AccentTable" -> accents, "CaseTable" -> cases)
        .map { case (name, value) => s"  private[memory] val $name: String =\n${wrapped(value)}\n" }
        .mkString("\n")
  }

  /** The words FTS5 `unicode61` (`SQLiteMemoryStore`'s tokenizer) splits each text into, in order. */
  def fts5Words(texts: IndexedSeq[String]): Try[IndexedSeq[Vector[String]]] =
    Using.Manager { use =>
      val connection = use(DriverManager.getConnection("jdbc:sqlite::memory:"))
      val statement  = use(connection.createStatement())
      statement.execute("CREATE VIRTUAL TABLE t USING fts5(id UNINDEXED, content)")
      statement.execute("CREATE VIRTUAL TABLE v USING fts5vocab(t, instance)")
      connection.setAutoCommit(false)
      val insert = use(connection.prepareStatement("INSERT INTO t(rowid, id, content) VALUES (?, '', ?)"))
      texts.zipWithIndex.foreach { case (text, i) =>
        insert.setInt(1, i)
        insert.setString(2, text)
        insert.addBatch()
        if (i % 10000 == 0) insert.executeBatch()
      }
      insert.executeBatch()
      connection.commit()
      val words = Array.fill(texts.size)(Vector.newBuilder[String])
      val rows  = use(statement.executeQuery("SELECT doc, term FROM v ORDER BY doc, offset"))
      while (rows.next()) words(rows.getInt(1)) += rows.getString(2)
      words.toIndexedSeq.map(_.result())
    }

  /** Every code point but the surrogates, the probe text `a<code point>b` for each, and FTS5's words for them. */
  final case class Probes(codePoints: IndexedSeq[Int], texts: IndexedSeq[String], fts5: IndexedSeq[Vector[String]])

  def probes(): Try[Probes] = {
    val codePoints = (1 to Character.MAX_CODE_POINT).filterNot(isSurrogate)
    val texts      = codePoints.map(cp => "a" + new String(Character.toChars(cp)) + "b")
    fts5Words(texts).map(Probes(codePoints, texts, _))
  }

  /** The tables from FTS5's words for the probes. */
  def tables(probes: Probes): Either[String, Tables] = {
    val separators = Vector.newBuilder[Int]
    val folds      = Vector.newBuilder[(Int, Int)]
    val unexpected = Vector.newBuilder[String]
    probes.codePoints.indices.filter(i => probes.codePoints(i) >= 0x80).foreach { i =>
      val cp = probes.codePoints(i)
      probes.fts5(i) match {
        case Vector("a", "b") | Vector("ab") => separators += cp
        case Vector(word)
            if word.startsWith("a") && word.endsWith("b") && word.codePointCount(1, word.length - 1) == 1 =>
          val folded = word.codePointAt(1)
          if (folded != cp) folds += cp -> folded
        case other => unexpected += f"U+$cp%04X: FTS5 $other"
      }
    }
    unexpected.result() match {
      case Vector() =>
        val surrogates      = (0xd800 to 0xdfff).toVector
        val (accent, cased) = folds.result().partition { case (_, to) => to >= 'a' && to <= 'z' }
        Right(
          Tables(
            separators = ranges((separators.result() ++ surrogates).sorted).mkString(" "),
            accents = accent
              .groupBy(_._2)
              .toVector
              .sortBy(_._1)
              .map { case (to, from) => s"${to.toChar}=" + ranges(from.map(_._1).sorted).mkString(",") }
              .mkString(" "),
            cases = caseRuns(cased.sortBy(_._1)).mkString(" ")
          )
        )
      case found => Left(found.mkString("FTS5 tokenized code points in an unexpected way:\n", "\n", ""))
    }
  }

  def main(args: Array[String]): Unit =
    probes().toEither.left.map(_.toString).flatMap(tables) match {
      case Right(generated) =>
        args.headOption match {
          case Some(path) => java.nio.file.Files.writeString(java.nio.file.Path.of(path), generated.scalaSource)
          case None       => print(generated.scalaSource)
        }
      case Left(error) =>
        System.err.println(error)
        sys.exit(1)
    }

  private def isSurrogate(cp: Int): Boolean = cp >= 0xd800 && cp <= 0xdfff

  /** Sorted code points as `hex` and `hex-hex` ranges of consecutive ones. */
  private def ranges(sorted: Vector[Int]): Vector[String] =
    sorted
      .foldLeft(Vector.empty[(Int, Int)]) {
        case (init :+ ((start, end)), cp) if cp == end + 1 => init :+ (start -> cp)
        case (acc, cp)                                     => acc :+ (cp     -> cp)
      }
      .map { case (start, end) => if (start == end) f"$start%x" else f"$start%x-$end%x" }

  /**
   * Case folds as `start[+count[/2]]:delta`: a run of consecutive code points with the same delta, else a run of every
   * other code point with the same delta, else a single code point.
   */
  private def caseRuns(folds: Vector[(Int, Int)]): Vector[String] = {
    val delta = folds.map { case (from, to) => from -> (to - from) }.toMap
    def runFrom(start: Int, step: Int): Int =
      Iterator.iterate(start)(_ + step).takeWhile(cp => delta.get(cp).contains(delta(start))).size
    val out  = Vector.newBuilder[String]
    var left = folds.map(_._1)
    while (left.nonEmpty) {
      val start         = left.head
      val consecutive   = runFrom(start, 1)
      val (count, step) = if (consecutive > 1) (consecutive, 1) else (runFrom(start, 2), 2)
      val members       = Iterator.iterate(start)(_ + step).take(count).toSet
      val d             = delta(start)
      val run           = if (count == 1) f"$start%x" else if (step == 1) f"$start%x+$count" else f"$start%x+$count/2"
      out += run + ":" + (if (d < 0) f"-${-d}%x" else f"$d%x")
      left = left.filterNot(members)
    }
    out.result()
  }

  /** A table's value as the string concatenation `KeywordTokens` writes it as: pieces of at most 111 columns. */
  private def wrapped(value: String): String = {
    val words = value.split(' ')
    val lines = Vector.newBuilder[String]
    var line  = new StringBuilder
    var first = true
    words.zipWithIndex.foreach { case (word, i) =>
      val piece  = if (i < words.length - 1) word + " " else word
      val indent = if (first) 4 else 6
      if (line.nonEmpty && indent + 1 + line.length + piece.length + 3 > 111) {
        lines += " " * indent + "\"" + line + "\" +"
        line = new StringBuilder
        first = false
      }
      line ++= piece
    }
    lines += " " * (if (first) 4 else 6) + "\"" + line + "\""
    lines.result().mkString("\n")
  }
}
