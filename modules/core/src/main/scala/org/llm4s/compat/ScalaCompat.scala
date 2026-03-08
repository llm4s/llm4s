package org.llm4s.compat

/** Compatibility helpers retained after the Scala 3-only migration. */
object ScalaCompat {
  def isScala213: Boolean = false

  def isScala3: Boolean = true

  def onScala213[T](@annotation.unused ifScala213: => T, ifScala3: => T): T =
    ifScala3
}
