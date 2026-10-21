package org.llm4s.llmconnect.spi

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * [[ProviderRegistryReport]] text: the one-line `summary`, the multi-line `describe`, and the
 * per-module [[ProviderModuleReport.contribution]]. Reports are built directly; no discovery runs.
 */
class ProviderRegistryReportSpec extends AnyWordSpec with Matchers:

  private def module(name: String, source: Option[String] = None): ProviderModuleReport =
    ProviderModuleReport(name, Seq("acme"), source)

  "ProviderRegistryReport.explicit" should {
    "not be discovered, have an empty summary and say so in describe" in {
      val report = ProviderRegistryReport.explicit
      report.discovered shouldBe false
      report.summary shouldBe ""
      report.describe shouldBe "Registry built explicitly; no classpath discovery ran."
    }
  }

  "ProviderRegistryReport.summary" should {
    "count zero modules" in {
      val summary = ProviderRegistryReport(discovered = true).summary
      summary should include("scanned 0 modules")
      summary should include("0 failed")
    }

    "use the singular for one module" in {
      val summary = ProviderRegistryReport(discovered = true, modules = Seq(module("a.A"))).summary
      summary should include("scanned 1 module;")
      (summary should not).include("modules")
    }

    "use the plural for two modules" in {
      val summary = ProviderRegistryReport(discovered = true, modules = Seq(module("a.A"), module("b.B"))).summary
      summary should include("scanned 2 modules")
    }

    "list the details of failures after their count" in {
      val report = ProviderRegistryReport(
        discovered = true,
        failures = Seq(ProviderDiscoveryFailure("bad.One"), ProviderDiscoveryFailure("bad.Two"))
      )
      report.summary should include("2 failed: bad.One; bad.Two")
      (report.summary should not).include("0 failed")
    }

    "append the number of id collisions" in {
      val report = ProviderRegistryReport(
        discovered = true,
        collisions = Seq(
          ProviderIdCollision("chat", "acme", "b.B", Some("a.A")),
          ProviderIdCollision("embedding", "acme", "b.B", Some("a.A"))
        )
      )
      report.summary should include("2 id collision(s)")
    }

    "not mention collisions when there are none" in {
      (ProviderRegistryReport(discovered = true).summary should not).include("collision")
    }
  }

  "ProviderRegistryReport.hasFailures and hasCollisions" should {
    "be false for empty lists" in {
      val report = ProviderRegistryReport(discovered = true)
      report.hasFailures shouldBe false
      report.hasCollisions shouldBe false
    }

    "follow the lists" in {
      val failing = ProviderRegistryReport(discovered = true, failures = Seq(ProviderDiscoveryFailure("bad.One")))
      failing.hasFailures shouldBe true
      failing.hasCollisions shouldBe false

      val colliding =
        ProviderRegistryReport(discovered = true, collisions = Seq(ProviderIdCollision("chat", "acme", "b.B", None)))
      colliding.hasFailures shouldBe false
      colliding.hasCollisions shouldBe true
    }
  }

  "ProviderModuleReport.contribution" should {
    "say 'no providers' for a module with none" in {
      ProviderModuleReport("a.A", Nil, None).contribution shouldBe "no providers"
    }

    "list chat ids separated by commas" in {
      ProviderModuleReport("a.A", Seq("one", "two"), None).contribution shouldBe "one, two"
    }

    "prefix embedding ids with 'embeddings:'" in {
      ProviderModuleReport("a.A", Nil, None, Seq("emb1", "emb2")).contribution shouldBe "embeddings: emb1, emb2"
    }

    "separate chat and embedding ids with a semicolon" in {
      ProviderModuleReport("a.A", Seq("one"), None, Seq("emb1")).contribution shouldBe "one; embeddings: emb1"
    }
  }

  "ProviderRegistryReport.describe" should {
    "start with the summary and name each module with its contribution" in {
      val report = ProviderRegistryReport(discovered = true, modules = Seq(module("a.A")))
      val lines  = report.describe.split("\n").toList
      lines.head shouldBe report.summary
      lines(1) shouldBe "  - a.A: acme"
    }

    "show the source of a module when it has one" in {
      val report = ProviderRegistryReport(discovered = true, modules = Seq(module("a.A", Some("file:/x.jar"))))
      report.describe should include("  - a.A [file:/x.jar]: acme")
    }

    "list a failure on its own line" in {
      val report = ProviderRegistryReport(discovered = true, failures = Seq(ProviderDiscoveryFailure("bad.One")))
      report.describe.split("\n").toList should contain("  ! bad.One")
    }

    "describe a collision with no dropped module as an earlier registration" in {
      val report = ProviderRegistryReport(
        discovered = true,
        collisions = Seq(ProviderIdCollision("chat", "acme", "b.B", None))
      )
      val line = report.describe.split("\n").toList.last
      line should include("chat id 'acme'")
      line should include("an earlier registration")
      line should include("b.B won")
    }
  }
