package org.llm4s.agent.guardrails.builtin

import java.util.Locale

/**
 * Runs a block with the JVM default locale set to Turkish, where the default-locale `"I".toLowerCase` is the
 * dotless `ı`, and restores the previous default afterwards, whether or not the block throws.
 *
 * The default locale is JVM-global, so the whole save/set/body/restore sequence holds this object's monitor:
 * two callers can never interleave and leave the JVM on `tr-TR`. That serialises only callers of this helper.
 * The agent project's forked test JVM runs suites one at a time (`Test / fork := true` without
 * `testForkedParallel`), so no other suite observes the Turkish locale either; enabling parallel suites there
 * would need these tests isolated in their own JVM.
 */
private[builtin] object TurkishLocale {

  def apply[A](body: => A): A = synchronized {
    val saved = Locale.getDefault
    Locale.setDefault(Locale.forLanguageTag("tr-TR"))
    try body
    finally Locale.setDefault(saved)
  }
}
