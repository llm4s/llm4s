package org.llm4s.agent.service

import org.llm4s.agent.graph.{ RetentionPolicy, SweepPolicy }
import org.llm4s.config.WorkerConfigLoader
import org.llm4s.error.{ ConfigurationError, ValidationError }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

import scala.concurrent.duration.*

class WorkerSettingsSpec extends AnyFlatSpec with Matchers with EitherValues {

  "WorkerSettings" should "default to four runs, a one second poll, an hour's unrunnable bound and a 5 minute sweep" in {
    val d = WorkerSettings.default
    d.concurrency shouldBe 4
    d.pollInterval shouldBe 1.second
    d.unrunnableAfter shouldBe 1.hour
    d.drainGrace shouldBe 30.seconds
    d.exitTimeout shouldBe 10.seconds
    d.sweepEvery shouldBe Some(5.minutes)
    d.sweep shouldBe SweepPolicy.keepAll
    d.label shouldBe None
  }

  it should "refuse a concurrency below 1, a non-positive duration and a blank label" in {
    WorkerSettings.of(concurrency = 0).left.value shouldBe a[ValidationError]
    WorkerSettings.of(drainGrace = Duration.Zero).left.value shouldBe a[ValidationError]
    WorkerSettings.of(sweepEvery = Some(Duration.Zero)).left.value shouldBe a[ValidationError]
    WorkerSettings.of(label = Some(" ")).left.value shouldBe a[ValidationError]
    an[IllegalArgumentException] should be thrownBy WorkerSettings.default.withExitTimeout(Duration.Zero)
    WorkerSettings.default.withSweepEvery(None).withLabel("pod-1").label shouldBe Some("pod-1")
  }

  "WorkerConfigLoader" should "load the reference defaults" in {
    WorkerConfigLoader.load(ConfigSource.default).value shouldBe WorkerSettings.default
  }

  it should "load every setting from llm4s.agent.worker" in {
    val source = ConfigSource
      .string(
        """llm4s.agent.worker {
          |  concurrency = 8
          |  pollInterval = 250 milliseconds
          |  unrunnableAfter = 2 hours
          |  drain { grace = 1 minute, exitTimeout = 5 seconds }
          |  sweep {
          |    enabled = true
          |    every = 10 minutes
          |    batchSize = 50
          |    tombstoneAge = 7 days
          |    retention { maxAge = 30 days, maxCheckpoints = 20 }
          |  }
          |  label = "pod-1"
          |}""".stripMargin
      )
      .withFallback(ConfigSource.default)
    WorkerConfigLoader.load(source).value shouldBe WorkerSettings(
      concurrency = 8,
      pollInterval = 250.millis,
      unrunnableAfter = 2.hours,
      drainGrace = 1.minute,
      exitTimeout = 5.seconds,
      sweepEvery = Some(10.minutes),
      sweep = SweepPolicy(RetentionPolicy(maxAge = Some(30.days), maxCheckpoints = Some(20)), Some(7.days), 50),
      label = Some("pod-1")
    )
  }

  it should "turn the sweep off with enabled = false" in {
    val source = ConfigSource.string("llm4s.agent.worker.sweep.enabled = false").withFallback(ConfigSource.default)
    WorkerConfigLoader.load(source).value.sweepEvery shouldBe None
  }

  it should "answer a ConfigurationError naming an invalid or mistyped key" in {
    val invalid = ConfigSource.string("llm4s.agent.worker.concurrency = 0").withFallback(ConfigSource.default)
    WorkerConfigLoader.load(invalid).left.value shouldBe a[ConfigurationError]
    val mistyped = ConfigSource.string("llm4s.agent.worker.pollInterval = soon").withFallback(ConfigSource.default)
    val error    = WorkerConfigLoader.load(mistyped).left.value
    error shouldBe a[ConfigurationError]
    error.message should include("pollInterval")
  }
}
