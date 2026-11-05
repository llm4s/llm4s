package org.llm4s.schema

import org.llm4s.annotation.Experimental
import org.llm4s.error.LLMError
import org.llm4s.toolapi.ToolCallInfo
import org.llm4s.types.Result

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import scala.annotation.{ implicitNotFound, targetName }
import scala.util.boundary

/**
 * The call a [[Tool.scoped]] handler is serving, given to the handler and nowhere else: [[fail]], [[ensure]], `?`,
 * [[ToolScope.call]] and [[ToolScope.idempotencyKey]] need one, so using them outside a scoped handler does not
 * compile.
 *
 * [[fail]] ends the call with `scala.util.boundary`'s `break`, which is an exception the handler's own `Try` or
 * `catch NonFatal` would catch: do not wrap a call to `fail`, `ensure` or `?` in either. Nor keep the scope after the
 * handler returns, or use it on another thread: the call has then ended, and there is nothing to end.
 */
@Experimental
@implicitNotFound(
  "fail, ensure, ? and ToolScope.call/idempotencyKey are only available inside a Tool.scoped handler: no ToolScope here"
)
final class ToolScope private[schema] (
  private val callInfo: Option[ToolCallInfo],
  private val key: String,
  private[schema] val label: boundary.Label[Left[String, Nothing]]
)

@Experimental
object ToolScope {

  /** The call being served, when the caller supplies it (an agent always does); `None` from plain tool calling. */
  def call(using scope: ToolScope): Option[ToolCallInfo] = scope.callInfo

  /**
   * The key to pass to a system with a side effect (an `Idempotency-Key` header, a client token), so that running the
   * same call again does not repeat the effect: the call's `ToolCallInfo.idempotencyKey` when it is supplied, which is
   * the same in every run of the call.
   *
   * Without a `ToolCallInfo` it is derived from the tool's name and the call's arguments (`tool-` and the SHA-256 of
   * the name and the arguments' JSON with the object keys sorted), so it is the same for equal arguments in any key
   * order: two separate calls with equal arguments then share a key, and a system that honours it runs the second as
   * a repeat of the first.
   */
  def idempotencyKey(using scope: ToolScope): String = scope.key

  /** The key of a call that supplies no `ToolCallInfo`: see [[idempotencyKey]]. */
  private[schema] def fallbackKey(toolName: String, arguments: ujson.Value): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(toolName.getBytes(StandardCharsets.UTF_8))
    digest.update(0.toByte)
    digest.update(canonical(arguments).render().getBytes(StandardCharsets.UTF_8))
    "tool-" + HexFormat.of().formatHex(digest.digest())
  }

  private def canonical(value: ujson.Value): ujson.Value = value match {
    case ujson.Obj(fields) => ujson.Obj.from(fields.toSeq.sortBy(_._1).map((k, v) => k -> canonical(v)))
    case ujson.Arr(items)  => ujson.Arr.from(items.map(canonical))
    case other             => other
  }
}

/**
 * Ends the [[Tool.scoped]] call being served: `message` is the tool's error, which the model sees, and the rest of
 * the handler does not run.
 */
def fail(message: String)(using scope: ToolScope): Nothing =
  boundary.break(Left(message))(using scope.label)

/** Ends the [[Tool.scoped]] call being served with `message` as its error unless `condition` holds. */
def ensure(condition: Boolean, message: => String)(using ToolScope): Unit =
  if (!condition) fail(message)

extension [T](result: Result[T])
  /**
   * The value of a `Right`; a `Left` ends the [[Tool.scoped]] call being served with the error's formatted message
   * (`LLMError.formatted`) as the tool's error.
   */
  @targetName("valueOrFailResult")
  def ?(using ToolScope): T = result match {
    case Right(value)          => value
    case Left(error: LLMError) => fail(error.formatted)
  }

extension [T](either: Either[String, T])
  /** The value of a `Right`; a `Left` ends the [[Tool.scoped]] call being served with its message as the tool's error. */
  @targetName("valueOrFailMessage")
  def ?(using ToolScope): T = either match {
    case Right(value)  => value
    case Left(message) => fail(message)
  }
