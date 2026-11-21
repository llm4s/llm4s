package org.llm4s.util

/**
 * Lower-case hex, two digits per byte: what `f"${b & 0xff}%02x"`, `f"$b%02x"` and `"%02x".format(b)` each give for
 * a byte, without a `String.format` per byte (#2642).
 */
private[llm4s] object Hex:
  private val lower = java.util.HexFormat.of()

  def of(bytes: Array[Byte]): String = lower.formatHex(bytes)
