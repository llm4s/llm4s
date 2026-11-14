package org.llm4s.doctor

import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.testutil.FixtureChatProvider
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * The public entry point, `Doctor.run(options)`, as an application calls it (#2283): the configuration is the
 * application's own (here core's test `application.conf`, whose default section `fixturechat-main` names
 * [[FixtureChatProvider]]) and the providers are the ones on its classpath. Nothing leaves the machine: the fixture
 * provider's client answers every request itself, so even `--live` runs offline.
 */
class DoctorRunSpec extends AnyWordSpec with Matchers {

  private def statuses(report: DoctorReport): List[(String, CheckStatus)] =
    report.checks.map(check => check.name -> check.status)

  private def detail(report: DoctorReport, name: String): String =
    report.checks.find(_.name == name).map(_.detail).getOrElse(fail(s"no '$name' step in the report"))

  "Doctor.run" should {

    "check the application's own configuration against the provider modules on its classpath" in {
      val report = Doctor.run()

      statuses(report) shouldBe List(
        "JDK"              -> CheckStatus.Pass,
        "Configuration"    -> CheckStatus.Pass,
        "Default provider" -> CheckStatus.Pass,
        "Provider module"  -> CheckStatus.Pass,
        "API key"          -> CheckStatus.Pass,
        "Provider config"  -> CheckStatus.Pass,
        "Local server"     -> CheckStatus.Skipped,
        "Live call"        -> CheckStatus.Skipped,
        "Other sections"   -> CheckStatus.Skipped
      )
      detail(report, "Default provider") should include("fixturechat-main")
      detail(report, "Provider module") should include("FixtureChatProviderModule")
      detail(report, "Provider module") should include("on this classpath")
      detail(report, "API key") should include("llm4s.providers.fixturechat-main.apiKey")
      (detail(report, "API key") should not).include("test-key")
      detail(report, "Local server") should include("is not a local server")
      report.exitCode shouldBe 0
      report.verdict shouldBe "pass"
    }

    "make the live request through the provider's own client when asked" in {
      val report = Doctor.run(DoctorOptions().withLive(true))

      report.checks.find(_.name == "Live call").map(_.status) shouldBe Some(CheckStatus.Pass)
      detail(report, "Live call") should include("fixture-model")
      report.exitCode shouldBe 0
    }

    "fail the provider module step, and skip the steps that need it, when the provider is not registered" in {
      val report = Doctor.run()(using ProviderRegistry.of())

      statuses(report).drop(3) shouldBe List(
        "Provider module" -> CheckStatus.Fail,
        "API key"         -> CheckStatus.Skipped,
        "Provider config" -> CheckStatus.Skipped,
        "Local server"    -> CheckStatus.Skipped,
        "Live call"       -> CheckStatus.Skipped,
        "Other sections"  -> CheckStatus.Skipped
      )
      report.exitCode shouldBe 2
      report.verdict shouldBe "fail"
    }

    "find the provider in an explicitly built registry" in {
      val report = Doctor.run()(using ProviderRegistry.of(FixtureChatProvider))

      report.checks.find(_.name == "Provider module").map(_.status) shouldBe Some(CheckStatus.Pass)
      report.exitCode shouldBe 0
    }
  }
}
