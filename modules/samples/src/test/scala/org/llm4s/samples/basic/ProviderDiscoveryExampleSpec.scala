package org.llm4s.samples.basic

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests [[ProviderDiscoveryExample]]:
 *  - Classpath discovery successfully discovers modules and contains key expected providers.
 *  - Explicit registry contains only the manually provided module.
 *  - Diagnostic report flags (hasFailures, hasCollisions) and output formatting behave as expected.
 */
class ProviderDiscoveryExampleSpec extends AnyFlatSpec with Matchers {

  "ProviderDiscoveryExample" should "discover providers from the classpath without failures" in {
    val result = ProviderDiscoveryExample.run()

    result.discovered.report.discovered shouldBe true
    result.discovered.report.hasFailures shouldBe false
    result.discovered.report.failures shouldBe empty

    // The samples project depends on provider modules; assert a few known chat provider IDs
    result.discovered.ids should contain("ollama")
    result.discovered.ids should contain("openai")

    // Assert embedding provider IDs include ollama
    result.discovered.embeddingIds should contain("ollama")

    // The discovery report describes loaded modules
    result.discovered.report.describe should include("Discovery scanned")
  }

  it should "build an explicit registry that contains only the provided module" in {
    val result = ProviderDiscoveryExample.run()

    // Explicit registry does not run classpath discovery
    result.explicit.report.discovered shouldBe false
    result.explicit.report.hasFailures shouldBe false
    result.explicit.report.hasCollisions shouldBe false

    // Holds only what Llm4sOllamaModule supplies
    result.explicit.ids shouldBe Seq("ollama")
    result.explicit.embeddingIds shouldBe Seq("ollama")

    // Explicit registry report notes that it was built explicitly
    result.explicit.report.describe should include("Registry built explicitly")
    result.explicit.report.summary shouldBe empty
  }

  it should "render human-readable diagnostic output" in {
    val result = ProviderDiscoveryExample.run()
    val lines  = ProviderDiscoveryExample.render(result)

    lines.exists(_.contains("1. Classpath Discovered Registry")) shouldBe true
    lines.exists(_.contains("2. Explicit Registry (Llm4sOllamaModule only)")) shouldBe true
    lines.exists(_.contains("3. Troubleshooting Notes")) shouldBe true
    lines.exists(_.contains("docs/guide/writing-a-provider.md")) shouldBe true
    lines.exists(_.contains("ollama")) shouldBe true
  }
}
