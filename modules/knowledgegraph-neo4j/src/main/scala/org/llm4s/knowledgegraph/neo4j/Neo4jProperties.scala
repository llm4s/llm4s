package org.llm4s.knowledgegraph.neo4j

import org.llm4s.error.ValidationError
import org.llm4s.knowledgegraph.Node
import org.llm4s.types.Result

import scala.jdk.CollectionConverters._
import scala.util.Try

/**
 * How `Neo4jGraphStore` stores `ujson` properties, so that they read back as they were written (#2969).
 *
 * Strings and booleans are stored natively, a whole number within `Long` range as an integer and any other number as
 * a float, and an array whose elements are all strings, all booleans or all numbers as a Neo4j list. Everything Neo4j
 * cannot hold as a property (`null`, an object, a nested or mixed array) goes into one JSON object under
 * [[JsonKey]]. Keys starting with [[ReservedPrefix]] belong to the store.
 *
 * An array, and a value stored as JSON, also gets a [[TextPrefix]] key holding the value's compact JSON (its
 * `toString`), which a property filter compares as the in-memory store does: `toString` on a list fails in Cypher, and
 * a value inside [[JsonKey]] has no property of its own.
 */
private[neo4j] object Neo4jProperties {

  val ReservedPrefix = "llm4s_"
  val IdKey          = "llm4s_id"
  val LabelKey       = "llm4s_label"
  val JsonKey        = "llm4s_json"
  val TextPrefix     = "llm4s_text_"

  /** The key holding the filterable text of the user's property `key`, when it has one. */
  def textKey(key: String): String = TextPrefix + key

  /** The largest whole double that converts to a `Long` without clamping. */
  private val LongBound = 9.2e18

  def encode(props: Map[String, ujson.Value], owner: String): Result[java.util.Map[String, AnyRef]] = {
    val reserved = props.keys.filter(_.startsWith(ReservedPrefix)).toSeq.sorted
    if (reserved.nonEmpty)
      Left(
        ValidationError(
          "properties",
          s"$owner uses ${reserved.mkString(", ")}: keys starting with '$ReservedPrefix' are reserved by Neo4jGraphStore"
        )
      )
    else {
      val m    = new java.util.HashMap[String, AnyRef]()
      val json = scala.collection.mutable.LinkedHashMap.empty[String, ujson.Value]
      props.foreach { case (k, v) =>
        native(v) match {
          case Some(n) => m.put(k, n)
          case None    => json.put(k, v)
        }
        v match {
          case ujson.Str(_) | ujson.Bool(_) | ujson.Num(_) => ()
          case other                                       => m.put(textKey(k), other.toString)
        }
      }
      if (json.nonEmpty) m.put(JsonKey, ujson.write(ujson.Obj.from(json)))
      Right(m)
    }
  }

  /** The user's properties from a stored property map: the store's own keys left out, the JSON ones expanded. */
  def decode(stored: java.util.Map[String, AnyRef]): Map[String, ujson.Value] = {
    val all = stored.asScala.toMap
    val fromJson = all.get(JsonKey) match {
      case Some(s: String) =>
        Try(ujson.read(s)).toOption.collect { case o: ujson.Obj => o.value.toMap }.getOrElse(Map.empty)
      case _ => Map.empty[String, ujson.Value]
    }
    all.view.filterKeys(k => !k.startsWith(ReservedPrefix)).mapValues(fromDriverValue).toMap ++ fromJson
  }

  def toNode(n: org.neo4j.driver.types.Node): Node =
    Node(n.get(IdKey).asString(), n.get(LabelKey).asString(), decode(n.asMap()))

  /** A value read from the driver (`Value.asObject` or a property map's value) as a `ujson.Value`. */
  def fromDriverValue(v: Any): ujson.Value = v match {
    case null                 => ujson.Null
    case s: String            => ujson.Str(s)
    case b: java.lang.Boolean => ujson.Bool(b.booleanValue())
    case n: java.lang.Number  => ujson.Num(n.doubleValue())
    case l: java.util.List[?] => ujson.Arr.from(l.asScala.map(fromDriverValue))
    case n: org.neo4j.driver.types.Node =>
      if (n.containsKey(IdKey)) {
        val node = toNode(n)
        ujson.Obj("id" -> node.id, "label" -> node.label, "properties" -> ujson.Obj.from(node.properties))
      } else
        ujson.Obj(
          "labels"     -> ujson.Arr.from(n.labels().asScala.map(ujson.Str(_))),
          "properties" -> ujson.Obj.from(decode(n.asMap()))
        )
    case r: org.neo4j.driver.types.Relationship =>
      ujson.Obj("type" -> r.`type`(), "properties" -> ujson.Obj.from(decode(r.asMap())))
    case m: java.util.Map[?, ?] =>
      ujson.Obj.from(m.asScala.map { case (k, value) => String.valueOf(k) -> fromDriverValue(value) })
    case other => ujson.Str(other.toString)
  }

  private def isLong(n: Double): Boolean = n.isWhole && math.abs(n) <= LongBound

  /** The value Neo4j stores for `v`, or `None` when it goes into the JSON property. */
  private def native(v: ujson.Value): Option[AnyRef] = v match {
    case ujson.Str(s)                      => Some(s)
    case ujson.Bool(b)                     => Some(java.lang.Boolean.valueOf(b))
    case ujson.Num(n) if isLong(n)         => Some(java.lang.Long.valueOf(n.toLong))
    case ujson.Num(n)                      => Some(java.lang.Double.valueOf(n))
    case ujson.Arr(items) if items.isEmpty => Some(java.util.List.of[AnyRef]())
    case ujson.Arr(items) if items.forall(_.isInstanceOf[ujson.Str]) =>
      Some(items.map(_.str: AnyRef).asJava)
    case ujson.Arr(items) if items.forall(_.isInstanceOf[ujson.Bool]) =>
      Some(items.map(i => java.lang.Boolean.valueOf(i.bool): AnyRef).asJava)
    case ujson.Arr(items) if items.forall(_.isInstanceOf[ujson.Num]) =>
      val nums = items.map(_.num)
      if (nums.forall(isLong)) Some(nums.map(n => java.lang.Long.valueOf(n.toLong): AnyRef).asJava)
      else Some(nums.map(n => java.lang.Double.valueOf(n): AnyRef).asJava)
    case _ => None
  }
}
