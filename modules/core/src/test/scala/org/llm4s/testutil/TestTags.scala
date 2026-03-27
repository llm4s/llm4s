package org.llm4s.testutil

import org.scalatest.Tag

/** Tag for slow tests (real timeouts, port-binding, etc.). Excluded from `sbt testFast`. */
object SlowTest extends Tag("org.llm4s.tags.SlowTest")
