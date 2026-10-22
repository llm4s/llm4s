package org.llm4s.javaapi

import org.llm4s.toolapi.ToolCallInfo

import scala.jdk.OptionConverters.*

/**
 * The arguments of one tool call, read into Java values before the handler runs (#1484): each declared parameter the
 * model passed, as the type it was declared with - a `String`, an `Integer`/`Long`/`Short`/`Byte`, a `Double`/`Float`,
 * a `Boolean` or an enum constant. A required parameter is always present; an optional one the model left out is
 * absent (`has` is false, and its getters return `null`, or throw for a primitive one).
 *
 * When an agent runs the tool, they also carry the call being run (#1740): [[toolCallId]] and [[idempotencyKey]]. The
 * key is the same in every run of one call - a retry, an approval, an answered question, a `recover` - so a tool with an
 * external side effect passes it to the system it calls, which then does the work once. Both are empty when the tool
 * runs outside an agent, through plain `ToolRegistry` tool calling, which has no call to describe.
 */
final class JToolArgs private[javaapi] (
  token: FacadeToken,
  values: java.util.Map[String, AnyRef],
  call: Option[ToolCallInfo]
) {

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

  /** The id the model gave this call, or empty outside an agent run. */
  def toolCallId(): java.util.Optional[String] = call.map(_.toolCallId).toJava

  /**
   * The call's idempotency key, the same in every run of this call and different for every other call, or empty
   * outside an agent run. Pass it to the system a side effect reaches (an `Idempotency-Key` header, a unique column).
   */
  def idempotencyKey(): java.util.Optional[String] = call.map(_.idempotencyKey).toJava

  override def toString: String = s"JToolArgs($values)"
}
