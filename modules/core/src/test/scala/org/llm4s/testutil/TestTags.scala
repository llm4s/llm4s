package org.llm4s.testutil

import org.scalatest.Tag

/** Tag for tests that require a running Ollama instance. Excluded from default `sbt test`. */
object OllamaRequired extends Tag("org.llm4s.tags.OllamaRequired")

/** Tag for smoke tests that call real cloud APIs. Excluded from default `sbt test`. */
object CloudSmoke extends Tag("org.llm4s.tags.CloudSmoke")

/** Tag for slow tests (real timeouts, port-binding, etc.). Excluded from `sbt testFast`. */
object SlowTest extends Tag("org.llm4s.tags.SlowTest")
