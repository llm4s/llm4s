package org.llm4s.vectorstore.testkit

import org.llm4s.annotation.Experimental
import org.llm4s.vectorstore.MetadataFilter
import org.llm4s.vectorstore.MetadataFilter._

/**
 * The filter matrix every `VectorStore` and `KeywordIndex` answers alike (#2996, #3000): five records and, for each
 * filter, the ids it selects.
 *
 * The answers are the trait's semantics, written out by hand rather than read from a reference backend: `Equals` and
 * `Contains` are exact and case-sensitive, `Contains` is a literal substring test (`%` and `_` are not wildcards),
 * `HasKey` asks whether the key is present, `Not` keeps a record that lacks the key, and an empty `In` or `Not(All)`
 * selects nothing.
 */
@Experimental
object MetadataFilterMatrix {

  /** One filter and the ids of [[records]] it selects. */
  final case class Case(filter: MetadataFilter, expected: Set[String]) {

    /** Whether the filter contains a `Contains`, which some search engines cannot evaluate. */
    def usesContains: Boolean = MetadataFilterMatrix.usesContains(filter)
  }

  /** The records the matrix is evaluated over, as `(id, metadata)`. */
  val records: Seq[(String, Map[String, String])] = Seq(
    "r1" -> Map("lang" -> "en", "path" -> "a_b", "type" -> "doc"),
    "r2" -> Map("lang" -> "fr", "path" -> "axb"),
    "r3" -> Map("path" -> "A_B", "type" -> "code"),
    "r4" -> Map("lang" -> "en", "tag" -> "50%"),
    "r5" -> Map.empty
  )

  /** Every id of [[records]]. */
  val allIds: Set[String] = records.map(_._1).toSet

  /** The filters and the ids each selects. */
  val cases: Seq[Case] = Seq(
    Case(All, allIds),
    Case(Equals("lang", "en"), Set("r1", "r4")),
    Case(Equals("path", "a_b"), Set("r1")),
    Case(Not(Equals("lang", "en")), Set("r2", "r3", "r5")),
    Case(Contains("path", "a_b"), Set("r1")),
    Case(Contains("path", "_"), Set("r1", "r3")),
    Case(Contains("path", "A_B"), Set("r3")),
    Case(Contains("tag", "%"), Set("r4")),
    Case(Not(Contains("path", "x")), Set("r1", "r3", "r4", "r5")),
    Case(HasKey("type"), Set("r1", "r3")),
    Case(Not(HasKey("lang")), Set("r3", "r5")),
    Case(In("lang", Set.empty), Set.empty),
    Case(Not(In("lang", Set.empty)), allIds),
    Case(In("lang", Set("en", "fr")), Set("r1", "r2", "r4")),
    Case(Not(In("lang", Set("fr"))), Set("r1", "r3", "r4", "r5")),
    Case(Not(All), Set.empty),
    Case(And(Equals("lang", "en"), HasKey("type")), Set("r1")),
    Case(Or(Equals("lang", "fr"), Not(HasKey("lang"))), Set("r2", "r3", "r5")),
    Case(And(HasKey("path"), Not(Contains("path", "x"))), Set("r1", "r3")),
    Case(Not(And(Equals("lang", "en"), HasKey("type"))), Set("r2", "r3", "r4", "r5")),
    Case(Or(And(Equals("lang", "en"), Contains("tag", "%")), Equals("type", "code")), Set("r3", "r4"))
  )

  private def usesContains(filter: MetadataFilter): Boolean = filter match {
    case _: Contains => true
    case And(l, r)   => usesContains(l) || usesContains(r)
    case Or(l, r)    => usesContains(l) || usesContains(r)
    case Not(f)      => usesContains(f)
    case _           => false
  }
}
