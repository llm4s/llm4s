package org.llm4s.vectorstore

import scala.util.Try

/**
 * Writes and reads the flat `Map[String, String]` metadata the Postgres stores keep in `jsonb` columns.
 *
 * Shared by [[PgVectorStore]], [[PgKeywordIndex]], `PgSearchIndex` and `PgCollectionStore`. It goes through
 * ujson, as the SQLite and Redis stores do, so any key or value is escaped correctly: a quote, backslash,
 * newline, tab or control character can't make the `jsonb` cast fail, and a value such as `x","tenant":"other`
 * stays one value instead of adding or overriding a key.
 */
private[llm4s] object PgMetadataJson {

  /** JSON object text for `metadata`, suitable for a `?::jsonb` parameter. */
  def write(metadata: Map[String, String]): String =
    if (metadata.isEmpty) "{}"
    else ujson.write(ujson.Obj.from(metadata.map { case (k, v) => k -> ujson.Str(String.valueOf(v)) }))

  /**
   * The string-valued entries of a JSON object read back from a `jsonb` column.
   *
   * Non-string values (which this module never writes) are skipped, as before. `null`, empty or unparseable
   * text gives an empty map.
   */
  def read(json: String): Map[String, String] =
    if (json == null || json.isEmpty) Map.empty
    else
      Try(ujson.read(json)).toOption match {
        case Some(obj: ujson.Obj) => obj.value.iterator.collect { case (k, ujson.Str(v)) => k -> v }.toMap
        case _                    => Map.empty
      }
}
