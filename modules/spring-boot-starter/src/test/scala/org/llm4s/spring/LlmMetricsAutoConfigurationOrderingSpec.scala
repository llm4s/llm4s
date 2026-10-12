package org.llm4s.spring

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.springframework.boot.SpringBootVersion
import org.springframework.boot.autoconfigure.AutoConfiguration

import scala.util.Try

/**
 * #1817: `LlmMetricsAutoConfiguration` orders itself after the auto-configurations that create the `MeterRegistry`,
 * naming them as strings. A name that no longer exists is ignored by Spring, so a Boot upgrade that relocates them
 * would silently drop the ordering. These checks make that loud.
 */
class LlmMetricsAutoConfigurationOrderingSpec extends AnyFlatSpec with Matchers {

  private val afterNames: Set[String] =
    Option(classOf[LlmMetricsAutoConfiguration].getAnnotation(classOf[AutoConfiguration]))
      .map(_.afterName().toSet)
      .getOrElse(fail("LlmMetricsAutoConfiguration has no @AutoConfiguration"))

  /** The registry-creating auto-configurations, by simple name, with the package each Boot line keeps them in. */
  private val targets = Seq(
    "MetricsAutoConfiguration",
    "CompositeMeterRegistryAutoConfiguration",
    "export.simple.SimpleMetricsExportAutoConfiguration"
  )
  private val boot3Package = "org.springframework.boot.actuate.autoconfigure.metrics"
  // Read from spring-boot-micrometer-metrics 4.0.0's AutoConfiguration.imports.
  private val boot4Package = "org.springframework.boot.micrometer.metrics.autoconfigure"

  private def resolves(name: String): Boolean = Try(Class.forName(name, false, getClass.getClassLoader)).isSuccess

  "LlmMetricsAutoConfiguration" should "order itself after the Spring Boot 3 registry auto-configurations" in {
    targets.foreach(t => afterNames should contain(s"$boot3Package.$t"))
  }

  it should "order itself after the Spring Boot 4 registry auto-configurations, which Boot 4 relocated" in {
    targets.foreach(t => afterNames should contain(s"$boot4Package.$t"))
  }

  it should "name only those classes" in {
    afterNames shouldBe targets.flatMap(t => Seq(s"$boot3Package.$t", s"$boot4Package.$t")).toSet
  }

  it should "name, for each target, a class that exists on the Spring Boot it is tested against" in {
    // Fails on a Boot upgrade that moves these classes again, instead of losing the ordering silently.
    val bootPackage = if (SpringBootVersion.getVersion.startsWith("3.")) boot3Package else boot4Package
    targets.foreach { t =>
      val name = s"$bootPackage.$t"
      withClue(s"Spring Boot ${SpringBootVersion.getVersion}: $name ")(resolves(name) shouldBe true)
    }
    afterNames.filter(resolves) should not be empty
  }
}
