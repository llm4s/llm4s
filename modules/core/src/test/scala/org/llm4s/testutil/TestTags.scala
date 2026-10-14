package org.llm4s.testutil

import org.scalatest.Tag

/** Tag for slow tests (real timeouts, port-binding, etc.). Excluded from `sbt testFast`. */
object SlowTest extends Tag("org.llm4s.tags.SlowTest")

/**
 * Tag for a test that measures how an operation's cost grows with its input - [[LinearTime.assertLinear]], or any
 * other timing ratio - rather than what the operation returns.
 *
 * `sbt test`, in every module, and so CI's ubuntu and Windows test jobs and the coverage run, leaves these tests out;
 * `sbt testPerformance` runs only them, in CI's ubuntu-only `Performance tests` job and nightly. An algorithm's growth
 * is the same on every platform, so running them on Windows and under coverage instrumentation multiplied their cost
 * - most of each test job's time - without adding a signal. `testOnly` of a named suite still runs them.
 *
 * A spec whose scaling tests carry this tag keeps one untagged test that runs a single ~1 MB worst-case input through
 * the same operation under [[LinearTime.MegabyteHangGuard]], so `sbt test` still fails on a catastrophic regression.
 *
 * Not [[SlowTest]]: that marks a test that is slow but belongs in the ordinary run (only `testFast` skips it).
 */
object PerformanceTest extends Tag("org.llm4s.tags.PerformanceTest")
