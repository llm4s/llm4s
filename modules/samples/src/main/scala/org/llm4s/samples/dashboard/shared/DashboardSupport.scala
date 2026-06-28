package org.llm4s.samples.dashboard.shared

object DashboardSupport:

  def truncate(text: String, width: Int): String =
    if width <= 0 then ""
    else if text.length <= width then text
    else if width == 1 then "…"
    else text.take(width - 1) + "…"

  def safeMessage(error: Throwable): String =
    Option(error.getMessage).filter(_.trim.nonEmpty).getOrElse(error.getClass.getSimpleName)
