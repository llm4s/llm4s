package org.llm4s.agent.guardrails.builtin

import java.util.Locale

import scala.util.Try

/**
 * Runs a block with the JVM default locale set to Turkish, where the default-locale `"I".toLowerCase` is the
 * dotless `ı`, and restores the previous default afterwards, whether or not the block throws.
 */
private[builtin] object TurkishLocale {

  def apply[A](body: => A): A = {
    val saved = Locale.getDefault
    Locale.setDefault(Locale.forLanguageTag("tr-TR"))
    val result = Try(body)
    Locale.setDefault(saved)
    result.get
  }
}
