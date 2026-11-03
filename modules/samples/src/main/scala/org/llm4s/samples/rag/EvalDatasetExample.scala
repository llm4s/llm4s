package org.llm4s.samples.rag

import org.llm4s.eval.dataset.{ ExampleSelector, InMemoryDatasetStore, JsonSchemaValidator }

import java.nio.file.{ Files, Path }
import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * Build a golden set of arithmetic questions in memory, without a model or an API key.
 *
 *  1. Create a dataset with an input schema and validate each candidate before storing it.
 *  2. Select examples by tag or ID and compare the counts with the full set.
 *  3. Freeze a snapshot, then add a question and compare the frozen and live sizes.
 *  4. Export the live set to a temporary JSONL file, import it into a second dataset and see how a malformed line is skipped.
 *
 * {{{
 * sbt "samples/runMain org.llm4s.samples.rag.EvalDatasetExample"
 * }}}
 */
object EvalDatasetExample {

  def main(args: Array[String]): Unit = run().foreach(println)

  private[rag] def run(): List[String] = {
    val schema = ujson.Obj(
      "type"       -> "object",
      "required"   -> ujson.Arr("question"),
      "properties" -> ujson.Obj("question" -> ujson.Obj("type" -> "string"))
    )
    val store = InMemoryDatasetStore()
    val id    = store.create("arithmetic", "Questions with reference answers", inputSchema = Some(schema))

    def add(input: ujson.Value, answer: String, tag: String) =
      JsonSchemaValidator.validate(input, schema).map { _ =>
        store.addExample(id, input, Some(ujson.Str(answer)), Set(tag), Map("topic" -> "arithmetic"))
      }

    val results = List(
      ("2 + 3", "5", "easy"),
      ("6 - 2", "4", "easy"),
      ("7 * 13", "91", "hard"),
      ("144 / 12", "12", "hard")
    ).map { case (question, answer, tag) => add(ujson.Obj("question" -> question), answer, tag) }
    val invalid     = add(ujson.Obj("prompt" -> "2 + 2"), "4", "easy")
    val errors      = (results :+ invalid).collect { case Left(messages) => messages }.flatten
    val selectedIds = results.collect { case Right(exampleId) => exampleId }.take(2).toSet
    val all         = store.getExamples(id, ExampleSelector.All)
    val hard        = store.getExamples(id, ExampleSelector.ByTags(Set("hard")))
    val selected    = store.getExamples(id, ExampleSelector.ByIds(selectedIds))

    val snapshotId = store.createSnapshot(id)
    val extra      = add(ujson.Obj("question" -> "8 + 1"), "9", "easy")
    val frozen     = store.getSnapshot(snapshotId).get.examples.size
    val live       = store.getDataset(id).get.examples.size

    val copyId = store.create("arithmetic-copy", "Imported questions", inputSchema = Some(schema))
    val (imported, skipped) = Using.resource(Files.createTempFile("eval-dataset-", ".jsonl")) { file =>
      Files.write(file, (store.exportJsonl(id) ++ Iterator("not JSON")).toList.asJava)
      store.importJsonl(copyId, Files.readAllLines(file).asScala.iterator)
    }(new Using.Releasable[Path] { def release(file: Path): Unit = Files.delete(file) })

    List("Created arithmetic dataset with required string field 'question'.") ++
      (errors ++ extra.left.toOption.toList.flatten).map(message => s"Rejected input: $message") ++ List(
        s"Selected examples: all=${all.size}, hard=${hard.size}, by ID=${selected.size}",
        s"After adding a question: snapshot=$frozen, live=$live",
        s"JSONL import: imported=$imported, skipped=$skipped"
      )
  }
}
