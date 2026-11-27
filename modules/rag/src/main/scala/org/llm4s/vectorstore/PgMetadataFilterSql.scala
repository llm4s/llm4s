package org.llm4s.vectorstore

/**
 * The SQL condition a [[MetadataFilter]] becomes over a `jsonb` `metadata` column, shared by [[PgVectorStore]],
 * [[PgKeywordIndex]] and `PgSearchIndex` (#2996).
 *
 * It selects the records `SQLiteVectorStore` selects:
 *  - every leaf is two-valued (`COALESCE(..., FALSE)`), so `Not` keeps a record that lacks the key instead of
 *    dropping it on a NULL;
 *  - `HasKey` is `jsonb_exists`, not the `?` operator, which pgjdbc reads as a placeholder;
 *  - `Contains` is `strpos`, a literal, case-sensitive substring test: `LIKE` would read `%` and `_` as wildcards;
 *  - an empty `In` is `FALSE`, not `IN ()`, a syntax error;
 *  - `All` is `TRUE`, so `Not(All)` matches nothing.
 *
 * Returns the condition and its parameters, all strings, in order.
 */
private[llm4s] object PgMetadataFilterSql {

  def apply(filter: MetadataFilter): (String, Seq[String]) = filter match {
    case MetadataFilter.All => ("TRUE", Seq.empty)

    case MetadataFilter.Equals(key, value) =>
      ("COALESCE(metadata->>? = ?, FALSE)", Seq(key, value))

    case MetadataFilter.Contains(key, substring) =>
      ("COALESCE(strpos(metadata->>?, ?) > 0, FALSE)", Seq(key, substring))

    case MetadataFilter.HasKey(key) =>
      ("jsonb_exists(metadata, ?)", Seq(key))

    case MetadataFilter.In(_, values) if values.isEmpty =>
      ("FALSE", Seq.empty)

    case MetadataFilter.In(key, values) =>
      val ordered = values.toSeq
      (s"COALESCE(metadata->>? IN (${ordered.map(_ => "?").mkString(",")}), FALSE)", key +: ordered)

    case MetadataFilter.And(left, right) =>
      val (leftSql, leftParams)   = apply(left)
      val (rightSql, rightParams) = apply(right)
      (s"($leftSql AND $rightSql)", leftParams ++ rightParams)

    case MetadataFilter.Or(left, right) =>
      val (leftSql, leftParams)   = apply(left)
      val (rightSql, rightParams) = apply(right)
      (s"($leftSql OR $rightSql)", leftParams ++ rightParams)

    case MetadataFilter.Not(inner) =>
      val (innerSql, innerParams) = apply(inner)
      (s"NOT ($innerSql)", innerParams)
  }
}
