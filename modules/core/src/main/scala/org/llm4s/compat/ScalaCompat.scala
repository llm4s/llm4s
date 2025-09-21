package org.llm4s.compat

/**
 * Compatibility layer that provides version-specific implementations.
 * This code is common to both Scala 2.13 and Scala 3, but uses
 * version-specific implementations from the scala-2.13 or scala-3 directories.
 */
object ScalaCompat {
  // In Scala 2.13, this will import from Scala213Compat
  // In Scala 3, this will import from Scala3Compat
  def isScala213: Boolean = {
    // The compiler will pick the right implementation at compile time
    import scala.util.Properties
    Properties.versionNumberString.startsWith("2.13")
  }

  def isScala3: Boolean = !isScala213

  // Simple helper for version-dependent code
  def onScala213[T](ifScala213: => T, ifScala3: => T): T =
    if (isScala213) ifScala213 else ifScala3
}
