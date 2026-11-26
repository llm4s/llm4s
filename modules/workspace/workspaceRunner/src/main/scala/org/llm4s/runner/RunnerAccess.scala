package org.llm4s.runner

import org.llm4s.shared.RunnerHandshake
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.util.Try

/**
 * Who may open the runner's WebSocket (#3046): only a handshake carrying the container's token, and never a
 * browser's. A refused handshake gets an HTTP error and no socket.
 */
private[runner] object RunnerAccess {

  private val logger = LoggerFactory.getLogger(getClass)

  /** `accepted` when the handshake of `request` carries `token` and no `Origin`; otherwise a refusal, logged. */
  def guard(token: String, request: cask.Request)(accepted: => cask.WebsocketResult): cask.WebsocketResult =
    RunnerHandshake.check(token, request.headers.map { case (k, vs) => k -> vs.toSeq }) match {
      case Right(()) => accepted
      case Left(why) =>
        logger.warn(
          s"Refused a WebSocket handshake from ${Try(request.exchange.getSourceAddress).getOrElse("?")}: $why"
        )
        val status = if (why.contains("Origin")) 403 else 401
        cask.Response(why, statusCode = status)
    }
}

/**
 * When the runner exits for want of a client (#3053): once no client has been connected for `timeout`, counted from
 * its start or its last client's disconnection. A client that died, or never connected, leaves no container running
 * on. `None` never exits.
 */
final private[runner] class IdleExit(timeout: Option[FiniteDuration], startedAtMillis: Long) {

  private var idleSince: Option[Long] = Some(startedAtMillis)

  /** Whether the runner should exit now, with `connections` clients connected at `nowMillis`. */
  def observe(connections: Int, nowMillis: Long): Boolean = synchronized {
    if (connections > 0) {
      idleSince = None
      false
    } else {
      val since = idleSince.getOrElse(nowMillis)
      idleSince = Some(since)
      timeout.exists(t => nowMillis - since >= t.toMillis)
    }
  }
}

private[runner] object IdleExit {

  /** Used when the container's environment sets no idle timeout. */
  val DefaultTimeout: FiniteDuration = 5.minutes

  /** The idle timeout from the environment's value: whole seconds, `0` never; unset is [[DefaultTimeout]]. */
  def timeoutFrom(value: Option[String]): Either[String, Option[FiniteDuration]] =
    value.map(_.trim).filter(_.nonEmpty) match {
      case None => Right(Some(DefaultTimeout))
      case Some(s) =>
        s.toLongOption match {
          case Some(0L)          => Right(None)
          case Some(n) if n > 0L => Right(Some(n.seconds))
          case _ => Left(s"${RunnerHandshake.IdleTimeoutEnvVar} must be a whole number of seconds (0: never), not '$s'")
        }
    }
}
