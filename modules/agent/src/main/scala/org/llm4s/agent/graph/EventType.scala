package org.llm4s.agent.graph

import org.slf4j.LoggerFactory
import upickle.default.ReadWriter

import scala.util.Try

/**
 * A typed event: a payload type `A` with a stable `name` and `version`, sent durably with [[emit]]
 * (a [[RunEvent.Custom]], committed with the task) or live with [[progress]] (a
 * [[StreamEvent.Live]]), and matched on either with [[unapply]]:
 *
 * {{{
 * val Ping = EventType[Ping]("my.ping", 1)
 * runtime.subscribe(threadId) {
 *   case Ping(p) => println(p)
 *   case _       => ()
 * }
 * }}}
 *
 * `unapply` is `None` for another name or version, for a kernel event, and for a payload that does
 * not decode (logged at DEBUG), so a listener never throws on an event it does not know.
 */
final class EventType[A] private (val name: String, val version: Int, codec: ReadWriter[A]):

  def encode(value: A): ujson.Value = upickle.default.writeJs(value)(using codec)

  def decode(payload: ujson.Value): Option[A] =
    Try(upickle.default.read[A](payload)(using codec)).fold(
      e =>
        EventType.logger.debug(s"Event '$name' v$version did not decode: ${e.getMessage}")
        None
      ,
      Some(_)
    )

  /** A durable event: committed with the task, delivered after the commit, replayed later. */
  def emit(context: RunContext, value: A): Unit = context.emit(name, version, encode(value))

  /** A live event: delivered at once to current subscribers, never stored. */
  def progress(context: RunContext, value: A): Unit = context.progress(name, version, encode(value))

  def unapply(event: StreamEvent): Option[A] = event match
    case StreamEvent.Durable(record) =>
      record.event match
        case RunEvent.Custom(n, v, payload) if n == name && v == version => decode(payload)
        case _                                                           => None
    case StreamEvent.Live(_, _, _, _, n, v, payload) if n == name && v == version => decode(payload)
    case _                                                                        => None

  override def toString: String = s"EventType($name, v$version)"

object EventType:
  private val logger    = LoggerFactory.getLogger(classOf[EventType[?]])
  private val ValidName = "[a-z0-9_.]{1,64}".r

  /** An event type; an invalid `name` (`[a-z0-9_.]{1,64}`) or a `version` below 1 is a programming error. */
  def apply[A](name: String, version: Int)(using codec: ReadWriter[A]): EventType[A] =
    require(ValidName.matches(name), s"event name '$name' must match [a-z0-9_.]{1,64}")
    require(version >= 1, s"event version must be at least 1, was $version")
    new EventType(name, version, codec)
