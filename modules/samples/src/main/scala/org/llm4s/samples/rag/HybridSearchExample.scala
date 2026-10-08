package org.llm4s.samples.rag

import org.llm4s.types.Result
import org.llm4s.vectorstore.{ FusionStrategy, HybridSearchResult, HybridSearcher, KeywordDocument, VectorRecord }

/**
 * Sample demonstrating `HybridSearcher`: one query, run with each fusion strategy, to show how vector and
 * keyword search disagree and how the strategies reconcile them.
 *
 * Runs offline. The documents carry small hand-written 3-dimensional embeddings and the query embedding is
 * hand-written too, so no embedding model, API key or network is involved.
 *
 * The query is "backup", with an embedding closest to the restore guide:
 *  - by vector, the restore guide is nearest (it is about getting data back, so it sits near the query) but it
 *    never uses the word "backup";
 *  - by keyword, only the cron note contains "backup", so it is the only keyword hit, though its embedding is
 *    mid-distance.
 *
 * What each strategy does with that:
 *  - VectorOnly ranks by embedding similarity alone, so the restore guide wins and the cron note is not
 *    singled out.
 *  - KeywordOnly returns only the documents that contain the word, so the cron note is the sole result.
 *  - RRF adds 1 / (k + rank) for every list a document is in. The restore guide is first in one list, the cron
 *    note is second in the vector list and first in the keyword list, so the cron note collects two
 *    contributions and wins: it is the document both lists agree on.
 *  - WeightedScore normalises each list to (0, 1] and blends them. With the default 0.5 / 0.5 weights the cron
 *    note's full keyword score outweighs the restore guide's small lead in the vector list; weighting the
 *    vector list heavily (0.9 / 0.1) hands the win back to the restore guide.
 *
 * Run with: sbt "samples/runMain org.llm4s.samples.rag.HybridSearchExample"
 */
object HybridSearchExample {

  val Query: String                = "backup"
  val QueryEmbedding: Array[Float] = Array(1.0f, 0.0f, 0.0f)
  private val TopK                 = 4

  /** (id, content, embedding): the embedding is what a model would return, shrunk to three dimensions. */
  val Documents: Seq[(String, String, Array[Float])] = Seq(
    ("restore-guide", "How to get your data back after an outage or a lost disk", Array(0.95f, 0.30f, 0.00f)),
    ("backup-cron", "Schedule a nightly backup job with cron", Array(0.75f, 0.50f, 0.40f)),
    ("recovery-plan", "The disaster plan lists who restores service and in what order", Array(0.60f, 0.40f, 0.60f)),
    ("onboarding", "Welcome to the team: your laptop, accounts and first week", Array(0.05f, 0.30f, 0.95f)),
    ("expenses", "Submit receipts for travel and meals within thirty days", Array(0.00f, 0.95f, 0.30f)),
    ("holiday-calendar", "Public holidays and the office closing days for the year", Array(0.10f, 0.85f, 0.45f))
  )

  /** The strategies to compare, in the order they are printed. */
  val Strategies: Seq[(String, FusionStrategy)] = Seq(
    "VectorOnly"           -> FusionStrategy.VectorOnly,
    "KeywordOnly"          -> FusionStrategy.KeywordOnly,
    "RRF(60)"              -> FusionStrategy.RRF(60),
    "WeightedScore(.5/.5)" -> FusionStrategy.WeightedScore(0.5, 0.5),
    "WeightedScore(.9/.1)" -> FusionStrategy.WeightedScore(0.9, 0.1)
  )

  /** Indexes the documents into both stores and runs the query once per strategy. */
  def run(): Result[Seq[(String, Seq[HybridSearchResult])]] =
    for {
      searcher <- HybridSearcher.inMemory()
      result <- {
        try
          for {
            _ <- indexAll(searcher)
            runs <- sequence(Strategies.map { case (name, strategy) =>
              searcher.search(QueryEmbedding, Query, TopK, strategy).map(name -> _)
            })
          } yield runs
        finally searcher.close()
      }
    } yield result

  private def indexAll(searcher: HybridSearcher): Result[Unit] =
    sequence(Documents.map { case (id, content, embedding) =>
      for {
        _ <- searcher.vectorStore.upsert(VectorRecord(id, embedding, Some(content)))
        _ <- searcher.keywordIndex.index(KeywordDocument(id, content))
      } yield ()
    }).map(_ => ())

  private def sequence[A](results: Seq[Result[A]]): Result[Seq[A]] =
    results.foldLeft[Result[Seq[A]]](Right(Seq.empty)) { (acc, next) =>
      for {
        done <- acc
        item <- next
      } yield done :+ item
    }

  private def fmt(score: Option[Double]): String = score.fold("   -   ")(s => f"$s%7.4f")

  def main(args: Array[String]): Unit =
    run() match {
      case Left(error) =>
        System.err.println(s"Hybrid search example failed: ${error.message}")
        sys.exit(1)
      case Right(runs) =>
        println(
          s"""=== Hybrid Search Demo ===\nQuery: "$Query"  (embedding ${QueryEmbedding.mkString("[", ", ", "]")})\n"""
        )
        runs.foreach { case (name, results) =>
          println(s"--- $name ---")
          println(f"${"rank"}%-5s ${"id"}%-18s ${"vector"}%8s ${"keyword"}%8s ${"score"}%8s")
          results.zipWithIndex.foreach { case (r, i) =>
            println(f"${i + 1}%-5d ${r.id}%-18s ${fmt(r.vectorScore)}%8s ${fmt(r.keywordScore)}%8s ${r.score}%8.4f")
          }
          println()
        }
    }
}
