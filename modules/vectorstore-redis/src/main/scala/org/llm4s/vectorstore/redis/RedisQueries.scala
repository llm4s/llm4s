package org.llm4s.vectorstore.redis

import org.llm4s.vectorstore.MetadataFilter

import java.nio.charset.StandardCharsets
import java.nio.{ ByteBuffer, ByteOrder }

/**
 * How [[RedisVectorStore]] lays records out in Redis hashes, and how a [[org.llm4s.vectorstore.MetadataFilter]]
 * becomes a query of the Redis Query Engine (DIALECT 2).
 *
 * A record is a hash at `<keyPrefix><id>` with these fields:
 *
 *  - `id`, `content` (absent when the record has none) and `metadata` (a JSON object), which the store reads back;
 *  - `embedding`, the vector as little-endian FLOAT32 bytes, indexed as a `VECTOR` field;
 *  - `seq`, a write sequence number indexed as a sortable `NUMERIC` field, so `list` returns insertion order;
 *  - `meta_tags` and `meta_keys`, indexed as `TAG` fields: one tag per metadata entry and one per metadata key.
 *
 * The schema of a search index is fixed, but metadata keys are not known in advance, so every entry goes into one
 * tag field. Each tag is encoded: the key as `k` followed by `_xx` for every UTF-8 byte (`xx` in lower-case hex), and an
 * entry as its key's tag, `x`, then the value encoded the same way. The encoding sidesteps everything that would
 * otherwise make an arbitrary string an unreliable tag - the separator, case folding, trimmed whitespace, query
 * escaping - and keeps substring matching sound: an encoded substring can only match at a byte boundary, because `_`
 * appears nowhere else.
 */
private[redis] object RedisQueries {

  val IdField: String        = "id"
  val ContentField: String   = "content"
  val MetadataField: String  = "metadata"
  val EmbeddingField: String = "embedding"
  val SeqField: String       = "seq"
  val TagsField: String      = "meta_tags"
  val KeysField: String      = "meta_keys"

  /** The KNN distance's alias in a search reply. */
  val ScoreField: String = "__vector_distance"

  /** The tag separator: the encoding never produces it. */
  val Separator: String = ","

  // ============================================================
  // Encoding
  // ============================================================

  private def hex(s: String): String =
    s.getBytes(StandardCharsets.UTF_8).map(b => f"_${b & 0xff}%02x").mkString

  /** The tag of a metadata key. */
  def keyTag(key: String): String = s"k${hex(key)}"

  /** The tag of a metadata entry. */
  def entryTag(key: String, value: String): String = s"${keyTag(key)}x${hex(value)}"

  /** A vector as the little-endian FLOAT32 bytes a `VECTOR` field holds. */
  def vectorBytes(vector: Array[Float]): Array[Byte] = {
    val buffer = ByteBuffer.allocate(vector.length * 4).order(ByteOrder.LITTLE_ENDIAN)
    vector.foreach(buffer.putFloat)
    buffer.array()
  }

  /** The vector held in little-endian FLOAT32 bytes; trailing bytes short of a float are ignored. */
  def vectorFrom(bytes: Array[Byte]): Array[Float] = {
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    Array.fill(bytes.length / 4)(buffer.getFloat)
  }

  /** The metadata as the JSON object stored in the `metadata` field. */
  def metadataJson(metadata: Map[String, String]): String =
    ujson.write(ujson.Obj.from(metadata.toSeq.sortBy(_._1).map { case (k, v) => k -> ujson.Str(v) }))

  /** The metadata read back from the `metadata` field: non-string values are kept as their JSON text. */
  def metadataFrom(json: String): Map[String, String] =
    ujson.read(json) match {
      case ujson.Obj(values) =>
        values.iterator.map {
          case (k, ujson.Str(v)) => k -> v
          case (k, ujson.Null)   => k -> ""
          case (k, other)        => k -> ujson.write(other)
        }.toMap
      case _ => Map.empty
    }

  /** A Redis glob pattern matching `literal` exactly: `*`, `?`, `[`, `]` and `\` are escaped. */
  def globLiteral(literal: String): String =
    literal.flatMap(c => if ("*?[]\\".contains(c)) s"\\$c" else c.toString)

  // ============================================================
  // Filters
  // ============================================================

  /** A filter in query form: matching everything, nothing, or a query expression. */
  enum Query {
    case Everything
    case Nothing
    case Expr(text: String)
  }

  /**
   * `filter` as a query. An empty `In` matches nothing, which the query language cannot say, so the translation
   * simplifies: `And` with nothing is nothing, `Or` with everything is everything, and so on. A query that matches
   * nothing is never sent.
   */
  def translate(filter: MetadataFilter): Query = {
    import MetadataFilter.*
    import Query.*
    filter match {
      case All                             => Everything
      case Equals(key, value)              => Expr(s"@$TagsField:{${entryTag(key, value)}}")
      case In(_, values) if values.isEmpty => Nothing
      case In(key, values) =>
        Expr(s"@$TagsField:{${values.toSeq.sorted.map(entryTag(key, _)).mkString(" | ")}}")
      case HasKey(key) => Expr(s"@$KeysField:{${keyTag(key)}}")
      case Contains(key, substring) =>
        val pattern = if (substring.isEmpty) s"${keyTag(key)}x*" else s"${keyTag(key)}x*${hex(substring)}*"
        Expr(s"@$TagsField:{w'$pattern'}")
      case And(left, right) =>
        (translate(left), translate(right)) match {
          case (Nothing, _) | (_, Nothing) => Nothing
          case (Everything, other)         => other
          case (other, Everything)         => other
          case (Expr(a), Expr(b))          => Expr(s"($a) ($b)")
        }
      case Or(left, right) =>
        (translate(left), translate(right)) match {
          case (Everything, _) | (_, Everything) => Everything
          case (Nothing, other)                  => other
          case (other, Nothing)                  => other
          case (Expr(a), Expr(b))                => Expr(s"($a) | ($b)")
        }
      case Not(inner) =>
        translate(inner) match {
          case Everything => Nothing
          case Nothing    => Everything
          case Expr(a)    => Expr(s"-($a)")
        }
    }
  }

  /** The query for an optional filter. */
  def translate(filter: Option[MetadataFilter]): Query =
    filter.fold(Query.Everything)(translate)

  /** The query text, `*` for everything; `None` for a query that matches nothing, which is never sent. */
  def text(query: Query): Option[String] = query match {
    case Query.Expr(t)    => Some(t)
    case Query.Everything => Some("*")
    case Query.Nothing    => None
  }

  // ============================================================
  // Index definition
  // ============================================================

  /**
   * The `FT.CREATE` arguments, after the command name, for an index of `dimension`-sized vectors; `sortable` declares
   * the sequence field `SORTABLE`, which the Redis Query Engine wants for `SORTBY` and `valkey-search` refuses.
   */
  def createIndex(config: RedisConfig, dimension: Int, sortable: Boolean = true): Seq[String] = {
    val vectorAttributes = Seq(
      "TYPE",
      "FLOAT32",
      "DIM",
      dimension.toString,
      "DISTANCE_METRIC",
      config.distanceMetric.wireName
    )
    Seq(config.index, "ON", "HASH", "PREFIX", "1", config.effectivePrefix, "SCHEMA") ++
      Seq(SeqField, "NUMERIC") ++ (if (sortable) Seq("SORTABLE") else Seq.empty) ++
      Seq(TagsField, "TAG", "SEPARATOR", Separator, "CASESENSITIVE") ++
      Seq(KeysField, "TAG", "SEPARATOR", Separator, "CASESENSITIVE") ++
      Seq(EmbeddingField, "VECTOR", config.algorithm.wireName, vectorAttributes.size.toString) ++ vectorAttributes
  }
}
