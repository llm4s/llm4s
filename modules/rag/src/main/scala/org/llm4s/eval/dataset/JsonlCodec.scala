package org.llm4s.eval.dataset

import scala.util.Try

/**
 * Codec for reading and writing [[Example]] values as JSONL (newline-delimited JSON).
 *
 * Each line produced by [[encode]] is a compact, single-line JSON object.
 * [[decode]] is the inverse: it parses one such line back into an [[Example]],
 * returning `None` for any malformed or structurally invalid input without throwing.
 *
 * Intended for batch import/export via [[DatasetStore.importJsonl]] and
 * [[DatasetStore.exportJsonl]].
 */
object JsonlCodec {

  /**
   * Serialises an [[Example]] to a compact, single-line JSON string.
   *
   * Field mapping:
   *  - `id`              — the [[ExampleId]] value as a string
   *  - `input`           — the input `ujson.Value` verbatim
   *  - `referenceOutput` — the output `ujson.Value`, or JSON `null` when `None`
   *  - `tags`            — JSON array of tag strings
   *  - `metadata`        — JSON object with string values
   *
   * The result never contains newline characters and is safe to use as a single
   * JSONL record.
   */
  def encode(example: Example[ujson.Value, ujson.Value]): String = {
    val json = ujson.Obj(
      "id"              -> ujson.Str(example.id.value),
      "input"           -> example.input,
      "referenceOutput" -> example.referenceOutput.getOrElse(ujson.Null),
      "tags"            -> ujson.Arr.from(example.tags.map(ujson.Str.apply)),
      "metadata"        -> ujson.Obj.from(example.metadata.map { case (k, v) => k -> ujson.Str(v) })
    )
    ujson.write(json)
  }

  /**
   * Attempts to parse a single JSONL line into an [[Example]].
   *
   * Returns `None` if:
   *  - `line` is not valid JSON
   *  - the top-level value is not a JSON object
   *  - the required `id` or `input` fields are absent or have the wrong type
   *
   * Optional-field behaviour:
   *  - `referenceOutput`: JSON `null` → `None`; any other value → `Some(value)`
   *  - `tags`: absent or non-array → empty `Set`
   *  - `metadata`: absent or non-object → empty `Map`; non-string values coerced to `""`
   *
   * Never throws; all parse errors are caught and collapsed to `None`.
   */
  def decode(line: String): Option[Example[ujson.Value, ujson.Value]] =
    // Try is scoped to ujson.read only (which throws on malformed JSON);
    // structural extraction below uses Option combinators and never throws.
    Try(ujson.read(line)).toOption.flatMap { json =>
      for {
        obj   <- json.objOpt
        idStr <- obj.value.get("id").flatMap(_.strOpt)
        input <- obj.value.get("input")
      } yield {
        val referenceOutput = obj.value.get("referenceOutput").flatMap(v => if (v.isNull) None else Some(v))
        val tags =
          obj.value.get("tags").flatMap(_.arrOpt).map(arr => arr.value.flatMap(_.strOpt).toSet).getOrElse(Set.empty)
        val metadata = obj.value
          .get("metadata")
          .flatMap(_.objOpt)
          .map(mObj => mObj.value.map { case (k, v) => k -> v.strOpt.getOrElse("") }.toMap)
          .getOrElse(Map.empty)
        Example[ujson.Value, ujson.Value](
          id = ExampleId(idStr),
          input = input,
          referenceOutput = referenceOutput,
          tags = tags,
          metadata = metadata
        )
      }
    }
}
