package org.llm4s.testutil

import ch.qos.logback.classic.turbo.TurboFilter
import ch.qos.logback.classic.{ Level, Logger, LoggerContext }
import ch.qos.logback.core.spi.FilterReply
import org.slf4j.{ LoggerFactory, Marker }

import java.util.concurrent.CopyOnWriteArrayList
import scala.jdk.CollectionConverters._

/**
 * Records the messages code builds for one logger's debug calls.
 *
 * slf4j asks logback's turbo filters both for `isDebugEnabled` (with no message) and for `debug(message)` (with the
 * message, already built). So with the logger at INFO, a call guarded by `isDebugEnabled` records nothing, and an
 * unguarded `logger.debug(s"...")` records the message it paid to build (#2631).
 */
object DebugMessageProbe {

  /** Runs `body` with the logger named `loggerName` at `level`, and returns the debug messages built meanwhile. */
  def debugMessagesBuilt[A](loggerName: String, level: Level = Level.INFO)(body: => A): (A, Vector[String]) = {
    val context = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
    val logger  = context.getLogger(loggerName)
    val built   = new CopyOnWriteArrayList[String]()
    val filter = new TurboFilter {
      override def decide(
        marker: Marker,
        target: Logger,
        lvl: Level,
        format: String,
        params: Array[AnyRef],
        t: Throwable
      ): FilterReply = {
        if (target.getName == loggerName && lvl == Level.DEBUG && format != null) built.add(format)
        FilterReply.NEUTRAL
      }
    }
    val previous = logger.getLevel
    filter.start()
    context.addTurboFilter(filter)
    logger.setLevel(level)
    val result =
      try body
      finally {
        logger.setLevel(previous)
        context.getTurboFilterList.remove(filter)
      }
    (result, built.asScala.toVector)
  }
}
