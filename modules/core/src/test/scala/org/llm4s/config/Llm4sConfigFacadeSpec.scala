package org.llm4s.config

// scalafix:off DisableSyntax.NoConfigFactory
import com.typesafe.config.ConfigFactory
// scalafix:on DisableSyntax.NoConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Tests for zero-coverage Llm4sConfig facade methods: embeddingsUi and
 * experimentalStubsEnabled. (`metrics()` and the three `load*SearchTool()` methods left core with
 * their modules; their cases are in `llm4s-observability-prometheus` and `llm4s-agent-tools`.)
 */
class Llm4sConfigFacadeSpec extends AnyWordSpec with Matchers {

  private def withProps(props: Map[String, String], clearKeys: Set[String] = Set.empty)(
    f: => Unit
  ): Unit = {
    val allKeys   = props.keySet ++ clearKeys
    val originals = allKeys.map(k => k -> Option(System.getProperty(k))).toMap
    // scalafix:off DisableSyntax.NoTryCatch
    try {
      clearKeys.foreach(System.clearProperty)
      props.foreach { case (k, v) => System.setProperty(k, v) }
      ConfigFactory.invalidateCaches()
      f
    } finally {
      originals.foreach {
        case (k, Some(v)) => System.setProperty(k, v)
        case (k, None)    => System.clearProperty(k)
      }
      ConfigFactory.invalidateCaches()
    }
    // scalafix:on DisableSyntax.NoTryCatch
  }

  // --------------------------------------------------------------------------
  // EmbeddingsUi
  // --------------------------------------------------------------------------

  "Llm4sConfig.embeddingsUi" should {

    "fall back to defaults when no config" in {
      val uiKeys = Set(
        "llm4s.embeddings.ui.maxRowsPerFile",
        "llm4s.embeddings.ui.topDimsPerRow",
        "llm4s.embeddings.ui.globalTopK",
        "llm4s.embeddings.ui.showGlobalTop",
        "llm4s.embeddings.ui.colorEnabled",
        "llm4s.embeddings.ui.tableWidth"
      )
      withProps(Map.empty, uiKeys) {
        val result = Llm4sConfig.embeddingsUi()
        result.isRight shouldBe true
        val ui = result.getOrElse(fail("expected Right"))
        ui.maxRowsPerFile shouldBe 200
        ui.topDimsPerRow shouldBe 6
        ui.globalTopK shouldBe 10
        ui.showGlobalTop shouldBe false
        ui.colorEnabled shouldBe true
        ui.tableWidth shouldBe 120
      }
    }
  }

  // --------------------------------------------------------------------------
  // Experimental Stubs
  // --------------------------------------------------------------------------

  "Llm4sConfig.experimentalStubsEnabled" should {

    "default to false when not configured" in {
      withProps(Map.empty, Set("llm4s.embeddings.experimentalStubs")) {
        Llm4sConfig.experimentalStubsEnabled shouldBe false
      }
    }

    "return true when configured" in {
      val props = Map(
        "llm4s.embeddings.experimentalStubs" -> "true"
      )

      withProps(props) {
        Llm4sConfig.experimentalStubsEnabled shouldBe true
      }
    }
  }
}
