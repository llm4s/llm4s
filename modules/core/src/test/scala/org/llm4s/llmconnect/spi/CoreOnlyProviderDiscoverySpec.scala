package org.llm4s.llmconnect.spi

import org.llm4s.types.ProviderModelTypes.ProviderId
import org.scalatest.EitherValues
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.net.{ URL, URLClassLoader }
import java.util.Enumeration
import scala.util.Using

class CoreOnlyProviderDiscoverySpec extends AnyFunSuite with Matchers with EitherValues {
  test("core main resources contain no provider service registration") {
    val core    = classOf[Llm4sProviderModule].getProtectionDomain.getCodeSource.getLocation
    val service = "META-INF/services/" + classOf[Llm4sProviderModule].getName
    // This runs in the core module, without vendor modules. Exclude its test-only service fixtures.
    Using.resource(new URLClassLoader(Array(core), getClass.getClassLoader) {
      override def getResources(name: String): Enumeration[URL] =
        if (name == service) findResources(name) else super.getResources(name)
    }) { loader =>
      loader.getResources(service).hasMoreElements shouldBe false
      val registry = ProviderRegistry.discover(loader)
      registry.report.discovered shouldBe true
      registry.report.modules shouldBe empty
      registry.report.failures shouldBe empty
      registry.ids shouldBe empty
      registry.embeddingIds shouldBe empty
      val message = registry.get(ProviderId("openai")).left.value.message
      message should include("is not registered")
      message should include("add the dependency that supplies it")
    }
  }
}
