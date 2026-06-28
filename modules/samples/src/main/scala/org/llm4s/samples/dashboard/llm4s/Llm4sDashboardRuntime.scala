package org.llm4s.samples.dashboard.llm4s

import java.time.LocalTime
import java.time.format.DateTimeFormatter

private[llm4s] object Llm4sDashboardRuntime:

  private val clockFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

  def currentTime(): String =
    LocalTime.now().format(clockFormat)
