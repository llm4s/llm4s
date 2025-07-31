package org.llm4s.llmconnect.utils

object LoggerUtils {

  def info(message: String): Unit =
    println(s"[INFO]  ${timestamp()} - $message")

  def warn(message: String): Unit =
    println(s"[WARN]  ${timestamp()} - $message")

  def error(message: String): Unit =
    println(s"[ERROR] ${timestamp()} - $message")

  private def timestamp(): String =
    java.time.LocalDateTime.now().toString
}
