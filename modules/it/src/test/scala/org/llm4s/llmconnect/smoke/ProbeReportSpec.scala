package org.llm4s.llmconnect.smoke

import org.llm4s.it.tags.Local
import org.llm4s.llmconnect.smoke.ProbeReport.Status
import org.scalatest.Assertions
import org.scalatest.exceptions.{ TestCanceledException, TestFailedException }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * [[ProbeReport]] is what turns a live run of `WatsonXAssumptionProbeSpec` into a table, so it has to be
 * right without an account: these checks need nothing external.
 *
 * Tier: `@Local`.
 */
@Local
class ProbeReportSpec extends AnyFlatSpec with Matchers {

  private val first  = "the first assumption"
  private val second = "the second assumption"
  private val third  = "the third assumption, never run"

  private def report = new ProbeReport("demo", Seq(first, second, third))

  "ProbeReport.probe" should "record a passing body as held and return its value" in {
    val r = report
    r.probe(first, "nowhere")(42) shouldBe 42
    r.status(first) shouldBe Some(Status.Held)
  }

  it should "record a failing body as NOT HELD and rethrow it with the assumption and the fix" in {
    val r = report
    val e = intercept[TestFailedException](r.probe(second, "change WatsonXClient.foo")(Assertions.fail("boom")))
    r.status(second) shouldBe Some(Status.NotHeld)
    e.getMessage should include(second)
    e.getMessage should include("change WatsonXClient.foo")
    e.getMessage should include("boom")
  }

  it should "record a cancelled body as skipped and rethrow the cancellation, not a failure" in {
    val r = report
    intercept[TestCanceledException](r.probe(first, "nowhere")(Assertions.cancel("no credentials")))
    r.status(first) shouldBe Some(Status.Skipped)
  }

  it should "refuse a probe that was not registered, so a typo cannot hide a row" in {
    val e = intercept[IllegalArgumentException](report.probe("an unregistered assumption", "nowhere")(1))
    e.getMessage should include("an unregistered assumption")
  }

  it should "keep the last outcome when an assumption is probed again" in {
    val r = report
    intercept[TestFailedException](r.probe(first, "x")(Assertions.fail("first try")))
    r.probe(first, "x")(())
    r.status(first) shouldBe Some(Status.Held)
  }

  "ProbeReport.render" should "list every registered assumption, a probe that never ran as skipped" in {
    val r = report
    r.probe(first, "nowhere")(())
    intercept[TestFailedException](r.probe(second, "x")(Assertions.fail("no")))
    val lines = r.render().split("\n").toSeq

    lines.head shouldBe "demo: assumption report"
    lines.exists(l => l.startsWith(first) && l.endsWith("| held")) shouldBe true
    lines.exists(l => l.startsWith(second) && l.endsWith("| NOT HELD")) shouldBe true
    lines.exists(l => l.startsWith(third) && l.endsWith("| skipped")) shouldBe true
  }

  it should "end with the counts of each outcome" in {
    val r = report
    r.probe(first, "nowhere")(())
    intercept[TestFailedException](r.probe(second, "x")(Assertions.fail("no")))

    r.render().split("\n").last shouldBe "held=1  NOT HELD=1  skipped=1"
  }

  it should "report an untouched run as all skipped" in {
    report.render().split("\n").last shouldBe "held=0  NOT HELD=0  skipped=3"
  }

  it should "align the result column whatever the length of the assumption" in {
    // the header and one row per assumption; the title, the rule and the counts have no `|`
    val columns = report.render().split("\n").toSeq.filter(_.contains('|')).map(_.indexOf('|'))
    columns.size shouldBe 4
    columns.distinct.size shouldBe 1
  }
}
