package org.llm4s.javaapi

/**
 * The arguments of one tool call, read into Java values before the handler runs (#1484): each declared parameter the
 * model passed, as the type it was declared with - a `String`, an `Integer`/`Long`/`Short`/`Byte`, a `Double`/`Float`,
 * a `Boolean` or an enum constant. A required parameter is always present; an optional one the model left out is
 * absent (`has` is false, and its getters return `null`, or throw for a primitive one).
 */
final class JToolArgs private[javaapi] (token: FacadeToken, values: java.util.Map[String, AnyRef]) {

  FacadeToken.check(token, "JToolArgs")

  /** Whether the model passed `name`. */
  def has(name: String): Boolean = values.containsKey(name)

  /** `name` as declared, or `null` when absent: cast it with `get(name, Type.class)`, as for an enum. */
  def get[T](name: String, `type`: Class[T]): T = `type`.cast(values.get(name))

  /** A `String` parameter. */
  def getString(name: String): String = get(name, classOf[String])

  /** An integer parameter (declared `int` or `Integer`). */
  def getInt(name: String): Int = get(name, classOf[Integer]).intValue

  /** A `long` parameter. */
  def getLong(name: String): Long = get(name, classOf[java.lang.Long]).longValue

  /** A `double` parameter. */
  def getDouble(name: String): Double = get(name, classOf[java.lang.Double]).doubleValue

  /** A `boolean` parameter. */
  def getBoolean(name: String): Boolean = get(name, classOf[java.lang.Boolean]).booleanValue

  /** Every argument, by name. */
  def asMap(): java.util.Map[String, AnyRef] = java.util.Collections.unmodifiableMap(values)

  override def toString: String = s"JToolArgs($values)"
}
